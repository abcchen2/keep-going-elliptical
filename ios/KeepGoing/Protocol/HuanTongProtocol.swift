import CoreBluetooth
import Foundation

/// "环通" (HuanTong) control board: service FFF0, notify FFF1, write FFF2, advertised name
/// "MOBI-..." (but not "MOBI-C"); "MOBI-E..." is the elliptical. Port of HuanTongProtocol.kt.
///
/// This board has NO "set resistance once" command. The official app
/// (HuanTongHandler / BluetoothLeService.huanTongTimedTask) does:
///   1. after FFF1 notifications are enabled, write the user weight:
///        40 00 LB KG CS      LB = kg × 2.2046226, CS = (0x40 + LB + KG) & 0xFF
///   2. then, EVERY SECOND for the whole session, write
///        20 C1 LV 00 CS      LV = resistance level, CS = (0x20 + 0xC1 + LV) & 0xFF
/// A single write changes the magnet briefly and then the board falls back.
///
/// RPM arrives in 12-byte FFF1 frames as BCD: bytes 2..3 printed as "%X%02X" and parsed
/// as a decimal number (0x01 0x23 -> 123 rpm).
final class HuanTongProtocol: MachineProtocol {
    override var kind: ProtocolKind { .huanTong }

    private var level: Int
    private var heartbeat: DispatchWorkItem?
    private var sessionStarted = false
    private var released = false

    private static let heartbeatMs = 1000

    override init(host: ProtocolHost) {
        level = max(host.state.targetResistance, 1)
        super.init(host: host)
    }

    override func start() {
        let isElliptical = host.deviceName.hasPrefix("MOBI-E")
        host.update { $0.machineDescription = UiText(isElliptical ? "ht_desc_elliptical" : "ht_desc_bike") }
        reportMachineType(isElliptical ? ResistanceTables.typeElliptical : ResistanceTables.typeBike, nil)
        // The board never reports the level back; the official app marks it write-only
        // and uses its default range of 1..24.
        applyRange(1, 24, "src_ht_default", mode: 1)

        if let notify = link.characteristic(BleUuids.htService, BleUuids.htNotify) {
            link.enableNotify(notify) { [weak self] ok in
                guard let self else { return }
                if !ok { self.logRes("log_ht_fff1_failed") }
                self.startSession()
            }
        } else {
            logRes("log_ht_no_fff1")
            startSession()
        }
    }

    private func writeCharacteristic() -> CBCharacteristic? {
        link.characteristic(BleUuids.htService, BleUuids.htWrite) ?? link.findCharacteristic(BleUuids.htWrite)
    }

    private func startSession() {
        if sessionStarted || released { return }
        sessionStarted = true
        guard let ch = writeCharacteristic() else {
            logRes("log_ht_no_fff2")
            return
        }
        let kg = host.settings.userWeightKg
        let lb = Int(Double(kg) * 2.2046226) & 0xFF
        let weightFrame = bytesOf(0x40, 0x00, lb, kg & 0xFF, (0x40 + lb + kg) & 0xFF)
        logRes("log_ht_weight", kg)
        link.write(ch, weightFrame) { [weak self] _ in
            guard let self else { return }
            self.host.update { $0.controlReady = true }
            self.scheduleHeartbeat(0)
        }
    }

    private func scheduleHeartbeat(_ delayMs: Int) {
        if released { return }
        heartbeat = host.postDelayed(delayMs) { [weak self] in
            guard let self, !self.released else { return }
            // Do not pile up frames if the radio is slower than 1 Hz.
            if let ch = self.writeCharacteristic(), self.link.pendingWrites(ch.uuid) == 0 {
                self.link.write(ch, self.frame(self.level))
            }
            self.scheduleHeartbeat(Self.heartbeatMs)
        }
    }

    private func frame(_ lv: Int) -> [UInt8] {
        bytesOf(0x20, 0xC1, lv, 0x00, (0x20 + 0xC1 + lv) & 0xFF)
    }

    override func setResistance(_ level: Int) {
        self.level = level
        // Send right away so the change is felt immediately; the heartbeat keeps it applied.
        guard let ch = link.characteristic(BleUuids.htService, BleUuids.htWrite) else { return }
        if sessionStarted { link.write(ch, frame(level)) }
        // No read-back on this board: show what is being held.
        host.update { $0.reportedResistance = level }
    }

    override func onNotify(_ ch: CBUUID, _ value: [UInt8]) {
        if handleStandardNotify(ch, value) { return }
        if ch == BleUuids.htNotify && value.count == 12 && value[1] != 0 {
            let text = String(format: "%lX%02lX", Int(value[2]), Int(value[3]))
            if let rpm = Double(text) { reportRpm(rpm) }
        }
    }

    override func release() {
        released = true
        if let hb = heartbeat { host.cancel(hb) }
        heartbeat = nil
    }
}
