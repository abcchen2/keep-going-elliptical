import CoreBluetooth
import Foundation

protocol GattLinkListener: AnyObject {
    /// Bluetooth is off, unauthorised or unsupported (only problem states are reported).
    func onBluetoothUnavailable(_ state: CBManagerState)
    func onBluetoothAvailable()
    func onScanResult(id: UUID, name: String?, rssi: Int, services: [CBUUID])
    /// "Connect last" asked for a device iOS no longer knows.
    func onPeripheralNotFound()
    func onLinkConnected()
    /// Services and characteristics discovered; protocol setup can start.
    func onLinkReady()
    func onLinkDisconnected(status: Int, userRequested: Bool)
    func onNotify(_ characteristic: CBUUID, _ value: [UInt8])
    func onLog(_ line: String)
}

/// CoreBluetooth counterpart of app/.../ble/GattLink.kt, with the same strictly serialized
/// operation queue (one op in flight, FIFO, 3 s timeout), like the official app's
/// BleGattMangerQueue. Everything runs on the main queue (spec ios-app, design "GATT link").
///
/// iOS differences handled here:
///  - No MAC address: devices are identified by `CBPeripheral.identifier`.
///  - `connect` never times out on iOS, so a 15 s timeout is added.
///  - MTU cannot be requested; iOS negotiates it. The usable size is logged.
///  - Reads and notifications both arrive in `didUpdateValueFor`.
///  - Write-without-response has no completion callback; flow control uses `canSendWriteWithoutResponse`.
final class GattLink: NSObject, CBCentralManagerDelegate, CBPeripheralDelegate {
    private final class Op {
        enum Kind { case read, write, notify }
        let kind: Kind
        let ch: CBCharacteristic
        let data: [UInt8]
        let writeType: CBCharacteristicWriteType
        let onRead: (([UInt8]?) -> Void)?
        let onDone: ((Bool) -> Void)?
        let label: String

        init(kind: Kind, ch: CBCharacteristic, data: [UInt8] = [], writeType: CBCharacteristicWriteType = .withResponse,
             onRead: (([UInt8]?) -> Void)? = nil, onDone: ((Bool) -> Void)? = nil) {
            self.kind = kind
            self.ch = ch
            self.data = data
            self.writeType = writeType
            self.onRead = onRead
            self.onDone = onDone
            switch kind {
            case .read: label = "READ \(ch.uuid.shortName)"
            case .write: label = "WRITE \(ch.uuid.shortName) [\(data.hex)]"
            case .notify: label = "CCCD \(ch.uuid.shortName)"
            }
        }
    }

    private weak var listener: GattLinkListener?
    private var central: CBCentralManager?
    private var whenPoweredOn: [() -> Void] = []
    private var seen: [UUID: CBPeripheral] = [:]
    private var device: CBPeripheral?
    private var queue: [Op] = []
    private var current: Op?
    private var waitingToWrite = false
    private var opTimeout: DispatchWorkItem?
    private var connectTimeout: DispatchWorkItem?
    private var pendingDiscoveries = 0
    /// Peripherals whose connection closeLink() cancelled, with the time. Their late disconnect
    /// callback must not abort a new connection to the same peripheral.
    private var cancelledAt: [UUID: Int64] = [:]
    private var userDisconnect = false
    private var ready = false

    init(listener: GattLinkListener) {
        self.listener = listener
        super.init()
    }

    var isConnected: Bool { device != nil && ready }

    /// GAP name of the connected device, if iOS knows it.
    var peripheralName: String? { device?.name }

    // ---------------------------------------------------------------- central / power

    /// Creates the central on first use, which shows the Bluetooth permission prompt. Demo mode
    /// never gets here, so it needs no permission (spec demo-mode, req 1.2).
    private func onPoweredOn(_ block: @escaping () -> Void) {
        guard let c = central else {
            whenPoweredOn.append(block)
            central = CBCentralManager(delegate: self, queue: .main,
                                       options: [CBCentralManagerOptionShowPowerAlertKey: true])
            return
        }
        switch c.state {
        case .poweredOn: block()
        case .unknown, .resetting: whenPoweredOn.append(block)
        default: listener?.onBluetoothUnavailable(c.state)
        }
    }

    func centralManagerDidUpdateState(_ c: CBCentralManager) {
        switch c.state {
        case .poweredOn:
            listener?.onBluetoothAvailable()
            let blocks = whenPoweredOn
            whenPoweredOn.removeAll()
            blocks.forEach { $0() }
        case .unknown, .resetting:
            break
        default:
            whenPoweredOn.removeAll()
            listener?.onBluetoothUnavailable(c.state)
            // iOS drops every connection when Bluetooth goes off, often without a disconnect callback.
            if device != nil { handleDisconnected(status: -1) }
        }
    }

    // ---------------------------------------------------------------- scanning

    func startScan() {
        onPoweredOn { [weak self] in
            // No service filter: several Mobi boxes advertise only their name.
            self?.central?.scanForPeripherals(withServices: nil,
                                              options: [CBCentralManagerScanOptionAllowDuplicatesKey: true])
        }
    }

    func stopScan() {
        if central?.state == .poweredOn { central?.stopScan() }
    }

    func centralManager(_ c: CBCentralManager, didDiscover p: CBPeripheral,
                        advertisementData: [String: Any], rssi RSSI: NSNumber) {
        seen[p.identifier] = p
        let name = advertisementData[CBAdvertisementDataLocalNameKey] as? String ?? p.name
        let services = advertisementData[CBAdvertisementDataServiceUUIDsKey] as? [CBUUID] ?? []
        let rssi = RSSI.intValue
        listener?.onScanResult(id: p.identifier, name: name, rssi: rssi == 127 ? -127 : rssi, services: services)
    }

    // ---------------------------------------------------------------- connection

    func connect(id: UUID) {
        onPoweredOn { [weak self] in
            guard let self, let c = self.central else { return }
            guard let p = self.seen[id] ?? c.retrievePeripherals(withIdentifiers: [id]).first else {
                self.listener?.onPeripheralNotFound()
                return
            }
            self.closeLink()
            self.userDisconnect = false
            self.ready = false
            self.device = p
            p.delegate = self
            self.logRes("log_gatt_connecting", id.uuidString)
            c.connect(p, options: nil)
            let timeout = DispatchWorkItem { [weak self] in
                guard let self, self.device === p, p.state != .connected else { return }
                self.logRes("ios_connect_timeout")
                self.handleDisconnected(status: -2)
            }
            self.connectTimeout = timeout
            DispatchQueue.main.asyncAfter(deadline: .now() + Self.connectTimeoutSec, execute: timeout)
        }
    }

    func disconnect() {
        guard let p = device else { return }
        userDisconnect = true
        clearQueue()
        central?.cancelPeripheralConnection(p)
        // Make sure we clean up even if the disconnect callback never comes.
        DispatchQueue.main.asyncAfter(deadline: .now() + 1.5) { [weak self] in
            guard let self, self.device === p else { return }
            self.handleDisconnected(status: 0)
        }
    }

    /// Forgets the current device without reporting anything (like BluetoothGatt.close()).
    private func closeLink() {
        clearQueue()
        connectTimeout?.cancel()
        connectTimeout = nil
        if let p = device {
            p.delegate = nil
            if p.state == .connected || p.state == .connecting {
                cancelledAt[p.identifier] = monotonicMs()
            }
            central?.cancelPeripheralConnection(p)
        }
        device = nil
        ready = false
        pendingDiscoveries = 0
    }

    private func handleDisconnected(status: Int) {
        let wasUser = userDisconnect
        closeLink()
        listener?.onLinkDisconnected(status: status, userRequested: wasUser)
    }

    func centralManager(_ c: CBCentralManager, didConnect p: CBPeripheral) {
        guard p === device else { return }
        connectTimeout?.cancel()
        connectTimeout = nil
        listener?.onLinkConnected()
        p.discoverServices(nil)
    }

    /// True for the late callback of a connection that closeLink() cancelled (within 2 s).
    private func isStaleCallback(_ p: CBPeripheral) -> Bool {
        guard let t = cancelledAt.removeValue(forKey: p.identifier) else { return false }
        return monotonicMs() - t < 2000
    }

    func centralManager(_ c: CBCentralManager, didFailToConnect p: CBPeripheral, error: Error?) {
        if isStaleCallback(p) { return }
        guard p === device else { return }
        logRes("log_gatt_connect_error", (error as NSError?)?.code ?? -1)
        handleDisconnected(status: (error as NSError?)?.code ?? -1)
    }

    func centralManager(_ c: CBCentralManager, didDisconnectPeripheral p: CBPeripheral, error: Error?) {
        if isStaleCallback(p) { return }
        guard p === device else { return }
        handleDisconnected(status: (error as NSError?)?.code ?? 0)
    }

    func peripheral(_ p: CBPeripheral, didDiscoverServices error: Error?) {
        guard p === device else { return }
        if let error {
            logRes("log_gatt_discover_failed", (error as NSError).code)
            central?.cancelPeripheralConnection(p)
            return
        }
        let services = p.services ?? []
        if services.isEmpty {
            discoveryFinished(p)
            return
        }
        pendingDiscoveries = services.count
        for s in services { p.discoverCharacteristics(nil, for: s) }
    }

    func peripheral(_ p: CBPeripheral, didDiscoverCharacteristicsFor service: CBService, error: Error?) {
        guard p === device, pendingDiscoveries > 0 else { return }
        pendingDiscoveries -= 1
        if pendingDiscoveries == 0 { discoveryFinished(p) }
    }

    private func discoveryFinished(_ p: CBPeripheral) {
        for s in p.services ?? [] {
            let chars = (s.characteristics ?? []).map { "\($0.uuid.shortName)(\(Self.propsText($0.properties)))" }
            logRes("log_gatt_service", s.uuid.shortName, chars.joined(separator: ", "))
        }
        // iOS negotiates the MTU itself (the official Android app asks for 128). ATT MTU = payload + 3.
        logRes("log_gatt_mtu", p.maximumWriteValueLength(for: .withoutResponse) + 3)
        ready = true
        listener?.onLinkReady()
    }

    // ---------------------------------------------------------------- lookup

    var services: [CBService] { device?.services ?? [] }

    func hasService(_ uuid: CBUUID) -> Bool { services.contains { $0.uuid == uuid } }

    func characteristic(_ service: CBUUID, _ ch: CBUUID) -> CBCharacteristic? {
        services.first { $0.uuid == service }?.characteristics?.first { $0.uuid == ch }
    }

    /// First characteristic with this UUID in any service.
    func findCharacteristic(_ ch: CBUUID) -> CBCharacteristic? {
        for s in services {
            if let c = s.characteristics?.first(where: { $0.uuid == ch }) { return c }
        }
        return nil
    }

    // ---------------------------------------------------------------- operations

    func read(_ ch: CBCharacteristic, _ done: @escaping ([UInt8]?) -> Void) {
        enqueue(Op(kind: .read, ch: ch, onRead: done))
    }

    /// Keeps the write type the characteristic offers (without response if supported),
    /// the same choice as Android's default write type / FastBle in the official app.
    func write(_ ch: CBCharacteristic, _ data: [UInt8], _ done: ((Bool) -> Void)? = nil) {
        let type: CBCharacteristicWriteType = ch.properties.contains(.writeWithoutResponse) ? .withoutResponse : .withResponse
        enqueue(Op(kind: .write, ch: ch, data: data, writeType: type, onDone: done))
    }

    /// Number of writes for `ch` waiting in the queue (used to avoid piling up heartbeat frames).
    func pendingWrites(_ ch: CBUUID) -> Int {
        let queued = queue.filter { $0.kind == .write && $0.ch.uuid == ch }.count
        let inFlight = (current?.kind == .write && current?.ch.uuid == ch) ? 1 : 0
        return queued + inFlight
    }

    /// Enables notifications (iOS picks indications itself when that is all the characteristic offers).
    func enableNotify(_ ch: CBCharacteristic, _ done: ((Bool) -> Void)? = nil) {
        enqueue(Op(kind: .notify, ch: ch, onDone: done))
    }

    private func enqueue(_ op: Op) {
        guard device != nil else {
            logRes("log_gatt_not_connected", op.label)
            fail(op)
            return
        }
        queue.append(op)
        pump()
    }

    private func clearQueue() {
        opTimeout?.cancel()
        opTimeout = nil
        current = nil
        waitingToWrite = false
        queue.removeAll()
    }

    private func pump() {
        guard current == nil, !queue.isEmpty else { return }
        let op = queue.removeFirst()
        guard let p = device, p.state == .connected else {
            fail(op)
            queue.removeAll()
            return
        }
        current = op
        scheduleTimeout(op)
        switch op.kind {
        case .read:
            p.readValue(for: op.ch)
        case .notify:
            p.setNotifyValue(true, for: op.ch)
        case .write:
            if op.writeType == .withResponse {
                p.writeValue(Data(op.data), for: op.ch, type: .withResponse)
            } else if p.canSendWriteWithoutResponse {
                writeWithoutResponse(op, p)
            } else {
                // Wait for peripheralIsReady(toSendWriteWithoutResponse:).
                waitingToWrite = true
            }
        }
    }

    private func writeWithoutResponse(_ op: Op, _ p: CBPeripheral) {
        waitingToWrite = false
        p.writeValue(Data(op.data), for: op.ch, type: .withoutResponse)
        // No callback for this write type; complete on the next main-queue turn.
        DispatchQueue.main.async { [weak self] in self?.finish(op, success: true, value: nil) }
    }

    private func scheduleTimeout(_ op: Op) {
        opTimeout?.cancel()
        let item = DispatchWorkItem { [weak self] in
            guard let self, self.current === op else { return }
            self.logRes("log_gatt_timeout", op.label)
            self.finish(op, success: false, value: nil)
        }
        opTimeout = item
        DispatchQueue.main.asyncAfter(deadline: .now() + Self.opTimeoutSec, execute: item)
    }

    private func finish(_ op: Op, success: Bool, value: [UInt8]?) {
        guard current === op else { return }
        opTimeout?.cancel()
        opTimeout = nil
        current = nil
        waitingToWrite = false
        switch op.kind {
        case .read:
            op.onRead?(success ? value : nil)
        case .write:
            if !success { logRes("log_gatt_write_failed", op.label) }
            op.onDone?(success)
        case .notify:
            if !success { logRes("log_gatt_cccd_failed", op.label) }
            op.onDone?(success)
        }
        pump()
    }

    private func fail(_ op: Op) {
        switch op.kind {
        case .read: op.onRead?(nil)
        case .write, .notify: op.onDone?(false)
        }
    }

    // ---------------------------------------------------------------- device callbacks

    func peripheral(_ p: CBPeripheral, didUpdateValueFor ch: CBCharacteristic, error: Error?) {
        guard p === device else { return }
        let value = [UInt8](ch.value ?? Data())
        if let op = current, op.kind == .read, op.ch.uuid == ch.uuid {
            finish(op, success: error == nil, value: value)
            return
        }
        if error == nil { listener?.onNotify(ch.uuid, value) }
    }

    func peripheral(_ p: CBPeripheral, didWriteValueFor ch: CBCharacteristic, error: Error?) {
        guard p === device, let op = current, op.kind == .write, op.ch.uuid == ch.uuid else { return }
        finish(op, success: error == nil, value: nil)
    }

    func peripheral(_ p: CBPeripheral, didUpdateNotificationStateFor ch: CBCharacteristic, error: Error?) {
        guard p === device, let op = current, op.kind == .notify, op.ch.uuid == ch.uuid else { return }
        finish(op, success: error == nil, value: nil)
    }

    func peripheralIsReady(toSendWriteWithoutResponse p: CBPeripheral) {
        guard p === device, waitingToWrite, let op = current, op.kind == .write else { return }
        writeWithoutResponse(op, p)
    }

    // ---------------------------------------------------------------- helpers

    private func logRes(_ key: String, _ args: Any...) {
        listener?.onLog(Lf(key, args))
    }

    private static let opTimeoutSec = 3.0
    private static let connectTimeoutSec = 15.0

    static func propsText(_ p: CBCharacteristicProperties) -> String {
        var parts: [String] = []
        if p.contains(.read) { parts.append("R") }
        if p.contains(.write) { parts.append("W") }
        if p.contains(.writeWithoutResponse) { parts.append("WnR") }
        if p.contains(.notify) { parts.append("N") }
        if p.contains(.indicate) { parts.append("I") }
        return parts.joined(separator: "/")
    }
}
