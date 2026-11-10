import CoreBluetooth
import Foundation

/// FTMS (0x1826) as spoken by Mobi machines (FtmsHandler in official app 4.5.17). Port of FtmsProtocol.kt.
///
/// Differences from what a generic FTMS client does:
///  - "Set Target Resistance Level" is sent as `04 PP` with ONE byte PP = level × 10.
///  - "Request Control" (`00`) is written first.
///  - Supported Resistance Level Range (2AD6) min/max are SINT16 × 0.1.
///  - Cross Trainer Data (2ACE) uses a 2-byte flags field on most Mobi ellipticals instead of
///    the 3 bytes required by the spec ("MB-E1010" / "Fibonacci" use 3); the size that makes
///    the payload length add up wins.
///  - Start = `07`, Stop = `08 01`, Pause = `08 02`.
final class FtmsProtocol: MachineProtocol {
    override var kind: ProtocolKind { .ftms }

    private var controlPoint: CBCharacteristic?
    private var crossTrainerFlagSize: Int
    private var lastCommand: [UInt8]?
    private var retriedAfterDenied = false

    override init(host: ProtocolHost) {
        let name = host.deviceName
        crossTrainerFlagSize = (name.hasPrefix("MB-E1010") || name.hasPrefix("Fibonacci")) ? 3 : 2
        super.init(host: host)
    }

    override func start() {
        let svc = BleUuids.ftmsService
        controlPoint = link.characteristic(svc, BleUuids.ftmsControlPoint)
        if let cp = controlPoint {
            // The official app never subscribes to the control point. Spec-compliant machines
            // require it before accepting writes; Mobi machines do not care.
            if cp.properties.contains(.indicate) { link.enableNotify(cp) }
            logRes("log_ftms_request_control")
            link.write(cp, bytesOf(0x00))
        } else {
            logRes("warn_no_2ad9")
        }

        if let ch = link.characteristic(svc, BleUuids.ftmsFeature) {
            link.read(ch) { [weak self] v in
                guard let self, let v, v.count >= 8 else { return }
                self.onFeatures(v)
            }
        }
        if let ch = link.characteristic(svc, BleUuids.ftmsResistanceRange) {
            link.read(ch) { [weak self] v in
                guard let self, let v else { return }
                self.onResistanceRange(v)
            }
        }
        for id in [BleUuids.ftmsCrossTrainerData, BleUuids.ftmsIndoorBikeData, BleUuids.ftmsTrainingStatus, BleUuids.ftmsStatus] {
            guard let ch = link.characteristic(svc, id) else { continue }
            if ch.properties.contains(.notify) || ch.properties.contains(.indicate) {
                link.enableNotify(ch)
            }
        }
        setupStandardServices()
        let elliptical = link.characteristic(svc, BleUuids.ftmsCrossTrainerData) != nil
        reportMachineType(elliptical ? ResistanceTables.typeElliptical : ResistanceTables.typeBike, nil)
        host.update {
            $0.controlReady = true
            if $0.machineDescription == nil {
                $0.machineDescription = UiText(elliptical ? "ftms_desc_elliptical" : "ftms_desc_generic")
            }
        }
    }

    private func onFeatures(_ v: [UInt8]) {
        let machine = Int(v[0]) | (Int(v[1]) << 8) | (Int(v[2]) << 16) | (Int(v[3]) << 24)
        let target = Int(v[4]) | (Int(v[5]) << 8) | (Int(v[6]) << 16) | (Int(v[7]) << 24)
        var mode = 0
        if (machine >> 7) & 1 == 1 { mode = 2 }   // Resistance Level Supported
        if (target >> 2) & 1 == 1 { mode = 3 }    // Resistance Target Setting Supported
        log(String(format: "2ACC: machine=0x%08lX target=0x%08lX", machine, target))
        host.update { $0.resistanceMode = mode }
        if (target >> 2) & 1 == 0 { logRes("log_ftms_no_target") }
    }

    private func onResistanceRange(_ v: [UInt8]) {
        logRes("log_ftms_range_raw", v.hex)
        guard v.count >= 4 else { return }
        let rawMin = v.s16le(0)
        let rawMax = v.s16le(2)
        var minLevel = Int((Double(rawMin) * 0.1).rounded())
        var maxLevel = Int((Double(rawMax) * 0.1).rounded())
        if maxLevel < 2 && (2...100).contains(rawMax) {
            // Some boards report whole levels here instead of 0.1 units.
            logRes("log_ftms_range_integer")
            minLevel = rawMin
            maxLevel = rawMax
        }
        if maxLevel > 0 { applyRange(minLevel, maxLevel, "src_device_2ad6") }
    }

    override func setResistance(_ level: Int) {
        guard let cp = controlPoint else {
            logRes("err_no_2ad9")
            return
        }
        let cmd: [UInt8]
        switch host.settings.ftmsEncoding {
        case .uint8x10:
            let p = level * 10
            if p > 255 { logRes("log_ftms_truncated", level) }
            cmd = bytesOf(0x04, min(p, 255))
        case .sint16x10:
            let p = level * 10
            cmd = bytesOf(0x04, p & 0xFF, (p >> 8) & 0xFF)
        case .uint8x1:
            cmd = bytesOf(0x04, level)
        }
        send(cp, cmd)
    }

    override func sendWorkout(_ cmd: WorkoutCommand) {
        guard let cp = controlPoint else { return }
        switch cmd {
        case .start: send(cp, bytesOf(0x07))
        case .stop: send(cp, bytesOf(0x08, 0x01))
        case .pause: send(cp, bytesOf(0x08, 0x02))
        }
    }

    private func send(_ cp: CBCharacteristic, _ cmd: [UInt8]) {
        lastCommand = cmd
        retriedAfterDenied = false
        link.write(cp, cmd)
    }

    override func onNotify(_ ch: CBUUID, _ value: [UInt8]) {
        if handleStandardNotify(ch, value) || value.isEmpty { return }
        switch ch {
        case BleUuids.ftmsControlPoint: onControlResponse(value)
        case BleUuids.ftmsCrossTrainerData: parseCrossTrainer(value)
        case BleUuids.ftmsIndoorBikeData: parseIndoorBike(value)
        case BleUuids.ftmsStatus: onMachineStatus(value)
        case BleUuids.ftmsTrainingStatus:
            if value.count >= 2 { logRes("log_ftms_training_status", Int(value[1])) }
        default: break
        }
    }

    private func onControlResponse(_ v: [UInt8]) {
        guard v.count >= 3, v[0] == 0x80 else { return }
        let op = Int(v[1])
        let result = Int(v[2])
        let text: String
        switch result {
        case 1: text = L("ftms_result_1")
        case 2: text = L("ftms_result_2")
        case 3: text = L("ftms_result_3")
        case 4: text = L("ftms_result_4")
        case 5: text = L("ftms_result_5")
        default: text = L("ftms_result_n", result)
        }
        logRes("log_ftms_response", op, text)
        if result == 5 && !retriedAfterDenied, let again = lastCommand, let cp = controlPoint {
            retriedAfterDenied = true
            logRes("log_ftms_retry")
            link.write(cp, bytesOf(0x00))
            link.write(cp, again)
        }
    }

    private func onMachineStatus(_ v: [UInt8]) {
        switch v[0] {
        case 0x02:
            let paused = v.count > 1 && v[1] == 2
            host.update { $0.machineRunState = paused ? "run_paused" : "run_stopped" }
        case 0x04:
            host.update { $0.machineRunState = "run_running" }
        case 0x07:
            if v.count >= 2 { logRes("log_ftms_target_ack", Double(v[1]) / 10.0) }
        case 0xFF:
            logRes("log_ftms_control_lost")
        default:
            logRes("log_ftms_status", v.hex)
        }
    }

    // ------------------------------------------------------------- data parsing

    private struct Part {
        let size: Int
        let signed: Bool
        let resolution: Double
        let key: String?
    }

    private struct Field {
        let bit: Int
        let inverted: Bool
        let parts: [Part]
        var size: Int { parts.reduce(0) { $0 + $1.size } }
    }

    private static func p(_ size: Int, _ key: String? = nil, res: Double = 1.0, signed: Bool = false) -> Part {
        Part(size: size, signed: signed, resolution: res, key: key)
    }

    private static let crossTrainerFields: [Field] = [
        Field(bit: 0, inverted: true, parts: [p(2, "speed", res: 0.01)]),
        Field(bit: 1, inverted: false, parts: [p(2)]),
        Field(bit: 2, inverted: false, parts: [p(3, "distance")]),
        Field(bit: 3, inverted: false, parts: [p(2, "cadence"), p(2)]),
        Field(bit: 4, inverted: false, parts: [p(2)]),
        Field(bit: 5, inverted: false, parts: [p(2), p(2)]),
        Field(bit: 6, inverted: false, parts: [p(2, signed: true), p(2, signed: true)]),
        Field(bit: 7, inverted: false, parts: [p(2, "resistance", res: 0.1, signed: true)]),
        Field(bit: 8, inverted: false, parts: [p(2, signed: true)]),
        Field(bit: 9, inverted: false, parts: [p(2, signed: true)]),
        Field(bit: 10, inverted: false, parts: [p(2, "energy"), p(2), p(1)]),
        Field(bit: 11, inverted: false, parts: [p(1, "hr")]),
        Field(bit: 12, inverted: false, parts: [p(1)]),
        Field(bit: 13, inverted: false, parts: [p(2, "elapsed")]),
        Field(bit: 14, inverted: false, parts: [p(2)]),
    ]

    private static let indoorBikeFields: [Field] = [
        Field(bit: 0, inverted: true, parts: [p(2, "speed", res: 0.01)]),
        Field(bit: 1, inverted: false, parts: [p(2)]),
        Field(bit: 2, inverted: false, parts: [p(2, "cadence", res: 0.5)]),
        Field(bit: 3, inverted: false, parts: [p(2)]),
        Field(bit: 4, inverted: false, parts: [p(3, "distance")]),
        Field(bit: 5, inverted: false, parts: [p(2, "resistance", res: 1.0, signed: true)]),
        Field(bit: 6, inverted: false, parts: [p(2, signed: true)]),
        Field(bit: 7, inverted: false, parts: [p(2, signed: true)]),
        Field(bit: 8, inverted: false, parts: [p(2, "energy"), p(2), p(1)]),
        Field(bit: 9, inverted: false, parts: [p(1, "hr")]),
        Field(bit: 10, inverted: false, parts: [p(1)]),
        Field(bit: 11, inverted: false, parts: [p(2, "elapsed")]),
        Field(bit: 12, inverted: false, parts: [p(2)]),
    ]

    private func present(_ fields: [Field], _ flags: Int) -> [Field] {
        fields.filter { f in
            let set = (flags >> f.bit) & 1 == 1
            return f.inverted ? !set : set
        }
    }

    private func parseCrossTrainer(_ v: [UInt8]) {
        guard v.count >= 2 else { return }
        let fields = present(Self.crossTrainerFields, v.u16le(0))
        let body = fields.reduce(0) { $0 + $1.size }
        let size: Int
        if v.count == 2 + body {
            size = 2
        } else if v.count == 3 + body {
            size = 3
        } else {
            size = crossTrainerFlagSize
        }
        if size != crossTrainerFlagSize {
            logRes("log_ftms_flag_size", size)
            crossTrainerFlagSize = size
        }
        applyValues(decode(v, size, fields))
    }

    private func parseIndoorBike(_ v: [UInt8]) {
        guard v.count >= 2 else { return }
        applyValues(decode(v, 2, present(Self.indoorBikeFields, v.u16le(0))))
    }

    private func decode(_ v: [UInt8], _ flagSize: Int, _ fields: [Field]) -> [String: Double] {
        var out: [String: Double] = [:]
        var offset = flagSize
        outer: for f in fields {
            for part in f.parts {
                if offset + part.size > v.count { break outer }
                let raw: Int
                switch part.size {
                case 1: raw = part.signed ? Int(Int8(bitPattern: v[offset])) : Int(v[offset])
                case 2: raw = part.signed ? v.s16le(offset) : v.u16le(offset)
                case 3: raw = v.u24le(offset)
                default: raw = 0
                }
                if let key = part.key { out[key] = Double(raw) * part.resolution }
                offset += part.size
            }
        }
        return out
    }

    private func applyValues(_ m: [String: Double]) {
        if let r = m["resistance"] { reportResistance(Int(r.rounded())) }
        if let c = m["cadence"] { reportRpm(c) }
        if let hr = m["hr"] { reportHeartRate(Int(hr)) }
        // Total distance / total energy are running counters; WorkoutTracker uses the increments.
        host.update { s in
            if let speed = m["speed"] { s.deviceSpeedKmh = speed }
            if let distance = m["distance"] { s.deviceDistanceM = distance }
            // 0xFFFF means "not available" in FTMS.
            if let energy = m["energy"], energy < 65535 { s.deviceCalories = energy }
        }
    }
}
