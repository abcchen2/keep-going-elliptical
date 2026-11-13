import Foundation

// Port of model/MachineState.kt. String resource ids become string keys (see L.swift).

/// Monotonic milliseconds, the counterpart of Android SystemClock.elapsedRealtime().
func monotonicMs() -> Int64 { Int64(ProcessInfo.processInfo.systemUptime * 1000) }

/// Wall-clock milliseconds since 1970, like System.currentTimeMillis().
func wallClockMs() -> Int64 { Int64(Date().timeIntervalSince1970 * 1000) }

enum ConnectionPhase {
    case idle, scanning, connecting, settingUp, ready, reconnecting

    var labelKey: String {
        switch self {
        case .idle: return "phase_idle"
        case .scanning: return "phase_scanning"
        case .connecting: return "phase_connecting"
        case .settingUp: return "phase_setting_up"
        case .ready: return "phase_ready"
        case .reconnecting: return "phase_reconnecting"
        }
    }
}

enum ProtocolKind {
    case v2, v1, huanTong, ftms, demo

    var labelKey: String {
        switch self {
        case .v2: return "protocol_v2"
        case .v1: return "protocol_v1"
        case .huanTong: return "protocol_huantong"
        case .ftms: return "protocol_ftms"
        case .demo: return "protocol_demo"
        }
    }
}

/// User choice for protocol selection. AUTO mirrors the official detection order.
/// Raw values are the Android enum names, used as the stored setting.
enum ProtocolChoice: String, CaseIterable {
    case auto = "AUTO", v2 = "V2", v1 = "V1", huanTong = "HUANTONG", ftms = "FTMS"

    var kind: ProtocolKind? {
        switch self {
        case .auto: return nil
        case .v2: return .v2
        case .v1: return .v1
        case .huanTong: return .huanTong
        case .ftms: return .ftms
        }
    }
}

/// How the FTMS "Set Target Resistance Level" (0x04) parameter is encoded.
/// The official app sends a single byte = level × 10 (FTMS v1.0 UINT8, 0.1 resolution).
enum FtmsResistanceEncoding: String, CaseIterable {
    case uint8x10 = "UINT8_X10", sint16x10 = "SINT16_X10", uint8x1 = "UINT8_X1"
}

enum WorkoutCommand { case start, pause, stop }

/// Calorie formula. Both are reimplemented in `CalorieModel` from the formulas and constants of
/// SportStateMachineToolKt (official app 4.5.17), so totals match what owners saw in the official
/// app. Documented in docs/PROTOCOL.en.md §7.
enum CalorieAlgorithm: String, CaseIterable {
    case officialNew = "OFFICIAL_NEW", officialOld = "OFFICIAL_OLD"
}

struct MachineState {
    var phase: ConnectionPhase = .idle
    var deviceName: String?
    var deviceAddress: String?
    var protocolKind: ProtocolKind?
    /// Resolved at display time so it follows the system language.
    var machineDescription: UiText?
    var resistanceMin = 1
    var resistanceMax = 24
    /// Where the min/max came from, shown to the user (string key).
    var resistanceRangeSource = "range_default"
    /// Mobi resistance mode: 0 none, 1 write only, 2 read only, 3 read+write.
    var resistanceMode: Int?
    /// Last level this app sent (or will keep sending, for HuanTong).
    var targetResistance = 1
    /// Level reported back by the machine, if the protocol reports it.
    var reportedResistance: Int?
    /// Device type code as reported by the machine (9 rowing, 10 bike, 11 elliptical); nil = unknown.
    var machineType: Int?
    var subType: Int?
    /// Live cadence. Considered stale when rpmUpdatedAt is older than a few seconds.
    var rpm: Double?
    var rpmUpdatedAt: Int64 = 0
    /// Speed reported by the machine (km/h), if any.
    var deviceSpeedKmh: Double?
    /// Running totals reported by the machine itself (V2 0x8813, FTMS 2ACE/2AD2). Absolute counters
    /// that may start non-zero or reset; the workout tracker only uses their per-second increments.
    var deviceDistanceM: Double?
    var deviceCalories: Double?
    var heartRate: Int?
    var heartRateUpdatedAt: Int64 = 0
    var battery: Int?
    /// String key.
    var machineRunState: String?
    var modelNumber: String?
    var firmware: String?
    /// True once the protocol finished its setup and accepts resistance commands.
    var controlReady = false
    /// String key.
    var lastError: String?
}

struct FoundDevice: Identifiable {
    /// CoreBluetooth peripheral identifier (iOS has no MAC addresses).
    let address: String
    let name: String?
    let rssi: Int
    let serviceHints: [String]
    var id: String { address }
}

enum WorkoutStatus {
    case idle, running, autoPaused, paused, finished

    var labelKey: String {
        switch self {
        case .idle: return "status_idle"
        case .running: return "status_running"
        case .autoPaused: return "status_auto_paused"
        case .paused: return "status_paused"
        case .finished: return "status_finished"
        }
    }
}

/// Where a total comes from, so the user can judge the number.
enum DataSource {
    case none, machine, formula

    var labelKey: String {
        switch self {
        case .none: return "source_none"
        case .machine: return "source_machine"
        case .formula: return "source_formula"
        }
    }
}

struct WorkoutStats {
    var status: WorkoutStatus = .idle
    var startedAt: Int64 = 0
    /// Seconds spent in RUNNING (pauses do not count).
    var activeSec = 0
    var distanceM = 0.0
    var calories = 0.0
    var distanceSource: DataSource = .none
    var calorieSource: DataSource = .none
    var speedKmh = 0.0
    var avgRpm = 0.0
    var avgResistance = 0.0
    var avgHeartRate: Int?
    var maxHeartRate: Int?
    /// Current calorie burn rate (kcal/min), shown so an unrealistic value is easy to spot.
    var kcalPerMin = 0.0
}

struct WorkoutRecord {
    let startedAt: Int64
    let activeSec: Int
    let distanceM: Double
    let calories: Double
    let avgRpm: Double
    let avgResistance: Double
    let avgHeartRate: Int?
    let maxHeartRate: Int?
}
