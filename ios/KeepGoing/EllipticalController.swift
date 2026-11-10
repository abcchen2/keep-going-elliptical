import CoreBluetooth
import Foundation
import UIKit

/// App-wide owner of the BLE session: scanning, connecting, picking the protocol, reconnecting
/// and forwarding resistance commands. Port of EllipticalController.kt. Main queue only.
final class EllipticalController: ObservableObject, GattLinkListener, ProtocolHost {
    let settings = AppSettings()

    @Published private(set) var state = MachineState() {
        didSet { updateIdleTimer() }
    }
    @Published private(set) var devices: [FoundDevice] = []
    @Published private(set) var logs: [String] = []
    /// String key of a Bluetooth problem (off / permission denied), shown on the connect card.
    @Published private(set) var bluetoothIssue: String? = nil
    /// Demo mode "Pedalling" switch.
    @Published var demoPedalling = true {
        didSet { (machineProtocol as? DemoMachine)?.pedalling = demoPedalling }
    }

    private(set) lazy var link = GattLink(listener: self)
    private var machineProtocol: MachineProtocol?

    private var targetAddress: String?
    private var targetName = ""
    private var reconnectAttempts = 0
    private var restoreResistanceAfterConnect = false
    private var pendingResistanceSend: DispatchWorkItem?
    private var reconnectItem: DispatchWorkItem?
    private var stopScanItem: DispatchWorkItem?
    private var scanning = false
    private var keepAwake = false

    /// Log every notification frame (noisy, for protocol debugging). No switch in v1.
    var verboseNotify = false

    private static let maxReconnect = 5
    private static let maxLogLines = 400

    private let timeFormat: DateFormatter = {
        let f = DateFormatter()
        f.locale = Locale(identifier: "en_US_POSIX")
        f.dateFormat = "HH:mm:ss.SSS"
        return f
    }()

    /// Per-workout time / distance / calories / heart rate. Starts by itself when pedalling begins.
    private(set) lazy var workout: WorkoutTracker = {
        let tracker = WorkoutTracker(
            settings: self.settings,
            machine: { [unowned self] in self.state },
            sendToMachine: { [unowned self] cmd in self.machineProtocol?.sendWorkout(cmd) },
            log: { [unowned self] line in self.log(line) }
        )
        tracker.begin()
        return tracker
    }()

    var isDemo: Bool { machineProtocol is DemoMachine }

    // ------------------------------------------------------------------ scanning

    func startScan() {
        if scanning { return }
        devices = []
        scanning = true
        if state.phase == .idle { setPhase(.scanning) }
        logRes("log_scan_start")
        link.startScan()
        stopScanItem?.cancel()
        let item = DispatchWorkItem { [weak self] in self?.stopScan() }
        stopScanItem = item
        DispatchQueue.main.asyncAfter(deadline: .now() + 12, execute: item)
    }

    func stopScan() {
        stopScanItem?.cancel()
        stopScanItem = nil
        guard scanning else { return }
        scanning = false
        link.stopScan()
        if state.phase == .scanning { setPhase(.idle) }
        logRes("log_scan_done", devices.count)
    }

    func onScanResult(id: UUID, name: String?, rssi: Int, services: [CBUUID]) {
        guard scanning else { return }
        let hints = services.map { $0.shortName }
        let cleanName = (name?.trimmingCharacters(in: .whitespaces)).flatMap { $0.isEmpty ? nil : $0 }
        if cleanName == nil && hints.isEmpty { return }
        let found = FoundDevice(address: id.uuidString, name: cleanName, rssi: rssi, serviceHints: hints)
        var list = devices.filter { $0.address != found.address }
        list.append(found)
        list.sort { a, b in
            let la = likelyMachine(a), lb = likelyMachine(b)
            if la != lb { return la }
            return a.rssi > b.rssi
        }
        devices = list
    }

    func likelyMachine(_ d: FoundDevice) -> Bool {
        let n = (d.name ?? "").uppercased()
        let known = BleUuids.scanServices.map { $0.shortName }
        return n.hasPrefix("MOBI") || n.hasPrefix("MB-") || d.serviceHints.contains { known.contains($0) }
    }

    func onBluetoothUnavailable(_ btState: CBManagerState) {
        bluetoothIssue = btState == .unauthorized ? "toast_need_permission" : "toast_enable_bt"
        logRes("log_bt_off")
        if scanning {
            stopScanItem?.cancel()
            scanning = false
        }
        // A reconnect attempt made while Bluetooth is off ends here (no disconnect callback follows),
        // so RECONNECTING must be reset too or the app stays in it with the screen kept awake.
        if state.phase == .scanning || state.phase == .connecting || state.phase == .reconnecting {
            setPhase(.idle)
        }
    }

    func onBluetoothAvailable() {
        bluetoothIssue = nil
    }

    // ------------------------------------------------------------------ connection

    func connect(address: String, name: String?) {
        stopScan()
        reconnectItem?.cancel()
        machineProtocol?.release()
        machineProtocol = nil
        targetAddress = address
        targetName = name ?? ""
        reconnectAttempts = 0
        restoreResistanceAfterConnect = false
        settings.lastDeviceAddress = address
        settings.lastDeviceName = name
        var s = MachineState()
        s.phase = .connecting
        s.deviceName = name
        s.deviceAddress = address
        s.targetResistance = 1
        state = s
        openLink()
    }

    /// Simulated elliptical for people without a Mobi machine (spec: .kiro/specs/demo-mode).
    /// Needs no Bluetooth permission and drives the real workout tracker.
    func startDemo() {
        stopScan()
        reconnectItem?.cancel()
        machineProtocol?.release()
        machineProtocol = nil
        targetAddress = nil
        if link.isConnected { link.disconnect() }
        targetName = "Demo"
        var s = MachineState()
        s.phase = .ready
        s.deviceName = "Demo Elliptical"
        s.protocolKind = .demo
        s.targetResistance = 8
        state = s
        demoPedalling = true
        let demo = DemoMachine(host: self)
        machineProtocol = demo
        demo.start()
    }

    var lastDeviceLabel: String? { settings.lastDeviceName ?? settings.lastDeviceAddress }

    func connectLast() {
        guard let addr = settings.lastDeviceAddress else { return }
        connect(address: addr, name: settings.lastDeviceName)
    }

    private func openLink() {
        // No target (e.g. a late reconnect after disconnect / demo): do nothing, like the Android app.
        guard let addr = targetAddress else { return }
        guard let id = UUID(uuidString: addr) else {
            logRes("log_invalid_address", addr)
            setPhase(.idle)
            return
        }
        link.connect(id: id)
    }

    func disconnect() {
        reconnectItem?.cancel()
        targetAddress = nil
        machineProtocol?.release()
        machineProtocol = nil
        if link.isConnected || state.phase != .idle {
            link.disconnect()
        }
        update {
            $0.phase = .idle
            $0.controlReady = false
        }
    }

    func onPeripheralNotFound() {
        logRes("ios_unknown_peripheral")
        targetAddress = nil
        update {
            $0.phase = .idle
            $0.lastError = "ios_unknown_peripheral"
        }
        startScan()
    }

    func onLinkConnected() {
        if targetName.isEmpty { targetName = link.peripheralName ?? "" }
        logRes("log_link_connected")
        setPhase(.settingUp)
    }

    func onLinkReady() {
        let auto = ProtocolDetector.detect(link, targetName)
        let forced = settings.protocolChoice.kind
        guard let kind = forced ?? auto, let p = ProtocolDetector.create(kind, self) else {
            logRes("log_unknown_protocol")
            update { $0.lastError = "err_unknown_protocol" }
            link.disconnect()
            return
        }
        if forced != nil {
            logRes("log_protocol_forced", L(kind.labelKey), auto.map { L($0.labelKey) } ?? L("none"))
        } else {
            logRes("log_protocol", L(kind.labelKey))
        }
        reconnectAttempts = 0
        update {
            $0.phase = .ready
            $0.protocolKind = kind
            $0.lastError = nil
            $0.controlReady = false
        }
        machineProtocol = p
        p.start()
        if restoreResistanceAfterConnect {
            restoreResistanceAfterConnect = false
            let level = state.targetResistance
            logRes("log_restore_level", level)
            sendResistanceNow(level)
        }
    }

    func onLinkDisconnected(status: Int, userRequested: Bool) {
        if machineProtocol is DemoMachine {
            // A real link closed after the user switched to demo mode; leave the demo running.
            logRes("log_demo_link_closed")
            return
        }
        machineProtocol?.release()
        machineProtocol = nil
        let wasReady = state.phase == .ready
        logRes(userRequested ? "log_disconnected_user" : "log_disconnected", status)
        update { $0.controlReady = false }
        guard !userRequested, targetAddress != nil else {
            setPhase(.idle)
            return
        }
        // Retry: first attempts often fail on cheap BLE modules; also reconnect during a workout.
        let allowed = wasReady ? settings.autoReconnect : true
        if allowed && reconnectAttempts < Self.maxReconnect {
            reconnectAttempts += 1
            restoreResistanceAfterConnect = wasReady || restoreResistanceAfterConnect
            let delay = 1.5 * Double(reconnectAttempts)
            logRes("log_reconnect", delay, reconnectAttempts)
            setPhase(.reconnecting)
            let item = DispatchWorkItem { [weak self] in self?.openLink() }
            reconnectItem = item
            DispatchQueue.main.asyncAfter(deadline: .now() + delay, execute: item)
        } else {
            update {
                $0.phase = .idle
                $0.lastError = "err_disconnected"
            }
        }
    }

    func onNotify(_ characteristic: CBUUID, _ value: [UInt8]) {
        if verboseNotify { logRes("log_notify", characteristic.shortName, value.hex) }
        machineProtocol?.onNotify(characteristic, value)
    }

    func onLog(_ line: String) { log(line) }

    // ------------------------------------------------------------------ control

    func setResistance(_ level: Int) {
        let clamped = min(max(level, state.resistanceMin), state.resistanceMax)
        update { $0.targetResistance = clamped }
        settings.lastResistance = clamped
        // Coalesce fast +/- taps into one command (the last value wins).
        pendingResistanceSend?.cancel()
        let item = DispatchWorkItem { [weak self] in self?.sendResistanceNow(clamped) }
        pendingResistanceSend = item
        DispatchQueue.main.asyncAfter(deadline: .now() + 0.12, execute: item)
    }

    private func sendResistanceNow(_ level: Int) {
        guard let p = machineProtocol else {
            logRes("log_level_pending", level)
            restoreResistanceAfterConnect = true
            return
        }
        // Protocols queue their setup first (FIFO GATT queue) and buffer the level if
        // they still need data from the machine, so it is safe to forward right away.
        logRes("log_set_level", level)
        p.setResistance(level)
    }

    func clearLogs() { logs = [] }

    // ------------------------------------------------------------------ ProtocolHost

    var deviceName: String { targetName }

    func update(_ transform: (inout MachineState) -> Void) {
        var s = state
        transform(&s)
        state = s
    }

    func log(_ line: String) {
        let entry = timeFormat.string(from: Date()) + "  " + line
        print("KeepGoing: \(line)")
        var l = logs
        l.append(entry)
        if l.count > Self.maxLogLines { l.removeFirst(l.count - Self.maxLogLines) }
        logs = l
    }

    @discardableResult
    func postDelayed(_ delayMs: Int, _ block: @escaping () -> Void) -> DispatchWorkItem {
        let item = DispatchWorkItem(block: block)
        DispatchQueue.main.asyncAfter(deadline: .now() + Double(delayMs) / 1000.0, execute: item)
        return item
    }

    func cancel(_ item: DispatchWorkItem) { item.cancel() }

    // ------------------------------------------------------------------ helpers

    private func setPhase(_ phase: ConnectionPhase) { update { $0.phase = phase } }

    private func logRes(_ key: String, _ args: Any...) { log(Lf(key, args)) }

    /// Keep the screen on while connected or reconnecting, like the Android app.
    private func updateIdleTimer() {
        let active = state.phase == .ready || state.phase == .reconnecting
        if active != keepAwake {
            keepAwake = active
            DispatchQueue.main.async { UIApplication.shared.isIdleTimerDisabled = active }
        }
    }
}
