import CoreBluetooth
import Foundation

/// What a protocol implementation may use. Everything runs on the main queue.
/// Port of protocol/MachineProtocol.kt.
protocol ProtocolHost: AnyObject {
    var link: GattLink { get }
    var deviceName: String { get }
    var settings: AppSettings { get }
    var state: MachineState { get }
    func update(_ transform: (inout MachineState) -> Void)
    func log(_ line: String)
    @discardableResult
    func postDelayed(_ delayMs: Int, _ block: @escaping () -> Void) -> DispatchWorkItem
    func cancel(_ item: DispatchWorkItem)
}

class MachineProtocol {
    unowned let host: ProtocolHost

    init(host: ProtocolHost) {
        self.host = host
    }

    var kind: ProtocolKind { fatalError("subclass must override kind") }

    /// Called once after service discovery. Subscribe, read capabilities, etc.
    func start() {}

    func onNotify(_ ch: CBUUID, _ value: [UInt8]) {}

    /// Sends a resistance level (already clamped to the machine range by the controller).
    func setResistance(_ level: Int) {}

    /// Start/pause/stop for machines that need it. 1.0 and HuanTong boards have no such command.
    func sendWorkout(_ cmd: WorkoutCommand) {}

    /// Stop timers. The link is closed by the controller.
    func release() {}

    // ------------------------------------------------------------- shared helpers

    var link: GattLink { host.link }

    func log(_ line: String) { host.log(line) }

    func logRes(_ key: String, _ args: Any...) { host.log(Lf(key, args)) }

    /// Resistance range from the official tables, unless the user set an override.
    func applyRange(_ minLevel: Int, _ maxLevel: Int, _ sourceKey: String, mode: Int? = nil) {
        let override = host.settings.maxResistanceOverride
        let realMax = override > 0 ? override : maxLevel
        let realSource = override > 0 ? "range_manual" : sourceKey
        let newMin = max(minLevel, 1)
        let newMax = max(realMax, newMin)
        host.update {
            $0.resistanceMin = newMin
            $0.resistanceMax = newMax
            $0.resistanceRangeSource = realSource
            if let mode { $0.resistanceMode = mode }
            $0.targetResistance = min(max($0.targetResistance, newMin), newMax)
        }
        if let mode {
            logRes("log_range_mode", newMin, newMax, L(realSource), L(ResistanceTables.modeText(mode)))
        } else {
            logRes("log_range", newMin, newMax, L(realSource))
        }
    }

    func reportResistance(_ level: Int) {
        guard level > 0 else { return }
        host.update { $0.reportedResistance = level }
    }

    func reportRpm(_ rpm: Double) {
        // Same sanity window as BaseHandler.upRpm() in the official app.
        guard rpm >= 0, rpm <= 200 else { return }
        let now = monotonicMs()
        host.update {
            $0.rpm = rpm
            $0.rpmUpdatedAt = now
        }
    }

    func reportHeartRate(_ hr: Int) {
        guard (30...250).contains(hr) else { return }
        let now = monotonicMs()
        host.update {
            $0.heartRate = hr
            $0.heartRateUpdatedAt = now
        }
    }

    func reportMachineType(_ type: Int, _ subType: Int?) {
        host.update {
            $0.machineType = type
            $0.subType = subType
        }
    }

    /// Device information, battery and heart rate, handled the same way for every protocol.
    func setupStandardServices() {
        for id in [BleUuids.charModelNumber, BleUuids.charFirmware] {
            guard let ch = link.characteristic(BleUuids.serviceDeviceInfo, id) else { continue }
            link.read(ch) { [weak self] v in
                guard let self, let v else { return }
                let trim = CharacterSet.whitespacesAndNewlines.union(CharacterSet(charactersIn: "\u{0}"))
                let text = String(decoding: v, as: UTF8.self).trimmingCharacters(in: trim)
                let isModel = id == BleUuids.charModelNumber
                self.logRes(isModel ? "log_model" : "log_firmware", text)
                self.host.update {
                    if isModel { $0.modelNumber = text } else { $0.firmware = text }
                }
            }
        }
        if let ch = link.characteristic(BleUuids.serviceBattery, BleUuids.charBatteryLevel) {
            link.read(ch) { [weak self] v in
                guard let self, let v, let first = v.first else { return }
                self.host.update { $0.battery = Int(first) }
            }
        }
        if let ch = link.characteristic(BleUuids.serviceHeartRate, BleUuids.charHeartRate) {
            link.enableNotify(ch)
        }
    }

    /// Returns true if `ch` was a standard characteristic and has been handled.
    func handleStandardNotify(_ ch: CBUUID, _ value: [UInt8]) -> Bool {
        if ch == BleUuids.charHeartRate {
            if value.count >= 2 {
                let hr: Int
                if value[0] & 1 == 0 {
                    hr = Int(value[1])
                } else {
                    hr = value.count >= 3 ? value.u16le(1) : 0
                }
                reportHeartRate(hr)
            }
            return true
        }
        if ch == BleUuids.charBatteryLevel {
            if let first = value.first { host.update { $0.battery = Int(first) } }
            return true
        }
        return false
    }
}
