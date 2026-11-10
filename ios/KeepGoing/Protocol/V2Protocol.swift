import CoreBluetooth
import Foundation

/// Mobi "2.0" box (GATT service 0x8800). Port of protocol/V2Protocol.kt.
///
/// Resistance control, as implemented by V2Handler.bleVer0x02Control() in the official app:
///  1. Write `11 82 07` to 0x88FF once per connection ("unlock write permission").
///     Without this the box silently ignores every 0x880F write.
///  2. Write `02 LL VV` to 0x880F, where VV is the level and LL is the number of
///     DECIMAL DIGITS of the level: 5 -> `02 01 05`, 12 -> `02 02 0C`.
///  3. The machine echoes the active level on 0x8812 (notify/read).
///
/// Start/pause/stop (`04` / `05` / `03` on 0x880F) are only sent when bit 1 of 0x8801[0] is set.
final class V2Protocol: MachineProtocol {
    override var kind: ProtocolKind { .v2 }

    private var unlocked = false
    private var unlocking = false
    private var afterUnlock: [() -> Void] = []

    /// 0x8801[0]: 1 = RPM via 8811 only, 2/3 = 8813 aggregate data (3 = both).
    private var uploadMode = 0
    private var magnets = 1
    private var rangeFromDevice = false

    override func start() {
        let svc = BleUuids.v2Service
        if let ch = link.characteristic(svc, BleUuids.v2UploadType) {
            link.read(ch) { [weak self] v in
                guard let self, let v, let first = v.first else { return }
                self.uploadMode = Int(first)
                self.logRes(self.supportsStartStop() ? "log_v2_upload_mode_start" : "log_v2_upload_mode", self.uploadMode)
            }
        }
        if let ch = link.characteristic(svc, BleUuids.v2SportType) {
            link.read(ch) { [weak self] v in
                guard let self, let v, v.count >= 2 else { return }
                self.onSportType(Int(v[0]), Int(v[1]))
            }
        }
        if let ch = link.characteristic(svc, BleUuids.v2Magnet) {
            link.read(ch) { [weak self] v in
                guard let self, let v, let first = v.first, first > 0 else { return }
                self.magnets = Int(first)
            }
        }
        if let ch = link.characteristic(svc, BleUuids.v2ResistanceRange) {
            link.read(ch) { [weak self] v in
                guard let self, let v else { return }
                self.logRes("log_v2_range_raw", v.hex)
                if v.count >= 3 && v[2] > 0 {
                    self.rangeFromDevice = true
                    self.applyRange(Int(v[1]), Int(v[2]), "src_device_8806", mode: ResistanceTables.readWriteMode(Int(v[0])))
                }
            }
        }
        if let ch = link.characteristic(svc, BleUuids.v2ResistanceGear), ch.properties.contains(.read) {
            link.read(ch) { [weak self] v in
                guard let self, let v, let first = v.first else { return }
                self.reportResistance(Int(first))
            }
        }
        for id in [BleUuids.v2MachineState, BleUuids.v2Interval, BleUuids.v2ResistanceGear, BleUuids.v2AllData, BleUuids.v2Error] {
            if let ch = link.characteristic(svc, id) { link.enableNotify(ch) }
        }
        setupStandardServices()
        if link.characteristic(svc, BleUuids.v2Control) == nil {
            logRes("warn_no_880f")
        }
        // The GATT queue is FIFO, so commands sent from now on run after the setup reads.
        host.update { $0.controlReady = true }
    }

    private func onSportType(_ type: Int, _ subType: Int) {
        let desc = ResistanceTables.describe(type, subType)
        logRes("log_v2_sport_type", type, subType, desc.resolve())
        host.update { $0.machineDescription = desc }
        reportMachineType(type, subType)
        if !rangeFromDevice {
            let caps = ResistanceTables.v2(type, subType)
            applyRange(1, caps.max, "src_official_table", mode: caps.mode)
        }
    }

    private func supportsStartStop() -> Bool { (uploadMode >> 1) & 1 == 1 }

    override func setResistance(_ level: Int) {
        guard let ch = link.characteristic(BleUuids.v2Service, BleUuids.v2Control) else {
            logRes("err_no_880f")
            return
        }
        let frame = bytesOf(0x02, String(level).count, level)
        withUnlock { [weak self] in self?.link.write(ch, frame) }
    }

    override func sendWorkout(_ cmd: WorkoutCommand) {
        guard supportsStartStop() else {
            logRes("log_v2_no_start", uploadMode)
            return
        }
        guard let ch = link.characteristic(BleUuids.v2Service, BleUuids.v2Control) else { return }
        let code: Int
        switch cmd {
        case .start: code = 0x04
        case .pause: code = 0x05
        case .stop: code = 0x03
        }
        withUnlock { [weak self] in self?.link.write(ch, bytesOf(code)) }
    }

    private func withUnlock(_ block: @escaping () -> Void) {
        if unlocked {
            block()
            return
        }
        afterUnlock.append(block)
        if unlocking { return }
        guard let ch = link.characteristic(BleUuids.v2Service, BleUuids.v2Unlock) else {
            logRes("log_v2_no_unlock")
            unlocked = true
            drainUnlockQueue()
            return
        }
        unlocking = true
        logRes("log_v2_unlock")
        link.write(ch, bytesOf(0x11, 0x82, 0x07)) { [weak self] ok in
            guard let self else { return }
            self.unlocking = false
            self.unlocked = ok
            if !ok { self.logRes("log_v2_unlock_failed") }
            self.drainUnlockQueue()
        }
    }

    private func drainUnlockQueue() {
        while !afterUnlock.isEmpty { afterUnlock.removeFirst()() }
    }

    override func onNotify(_ ch: CBUUID, _ value: [UInt8]) {
        if handleStandardNotify(ch, value) || value.isEmpty { return }
        switch ch {
        case BleUuids.v2ResistanceGear:
            reportResistance(Int(value[0]))

        case BleUuids.v2Interval:
            if (uploadMode == 1 || uploadMode == 3) && value.count >= 2 {
                let interval = value.u16be(0)
                reportRpm(interval == 0 ? 0 : 60_000.0 / Double(interval) / Double(magnets))
            }

        // [0] speed/10, [1] incline, [2] cadence, [3..4] duration, [5..6] distance, [7..8] kcal/10 (big endian).
        // Distance and kcal are the machine's running totals; WorkoutTracker uses their increments.
        case BleUuids.v2AllData:
            if (uploadMode == 2 || uploadMode == 3) && [9, 11, 13].contains(value.count) {
                reportRpm(Double(value[2]))
                host.update {
                    $0.deviceSpeedKmh = Double(value[0]) / 10.0
                    $0.deviceDistanceM = Double(value.u16be(5))
                    $0.deviceCalories = Double(value.u16be(7)) / 10.0
                }
            }

        case BleUuids.v2MachineState:
            let state: String?
            switch value[0] {
            case 0: state = "run_stopped"
            case 1: state = "run_running"
            case 2: state = "run_paused"
            default:
                state = nil
                logRes("run_state_n", Int(value[0]))
            }
            host.update { $0.machineRunState = state }

        case BleUuids.v2Error:
            logRes("log_v2_error", value.hex)

        default:
            break
        }
    }
}
