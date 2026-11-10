import Foundation

/// UserDefaults wrapper with the Android keys and defaults (model/AppSettings.kt).
/// Values are read on demand so changes apply immediately. There is no settings screen in
/// v1 (spec ios-app, req 4.3), so the defaults below are what the app uses.
final class AppSettings {
    private let d = UserDefaults.standard

    var protocolChoice: ProtocolChoice {
        get { d.string(forKey: Keys.protocolChoice).flatMap(ProtocolChoice.init(rawValue:)) ?? .auto }
        set { d.set(newValue.rawValue, forKey: Keys.protocolChoice) }
    }

    var ftmsEncoding: FtmsResistanceEncoding {
        get { d.string(forKey: Keys.ftmsEncoding).flatMap(FtmsResistanceEncoding.init(rawValue:)) ?? .uint8x10 }
        set { d.set(newValue.rawValue, forKey: Keys.ftmsEncoding) }
    }

    /// User weight, sent to HuanTong boards on connect (the official app sends it too).
    var userWeightKg: Int {
        get { int(Keys.weight, 60) }
        set { d.set(min(max(newValue, 20), 200), forKey: Keys.weight) }
    }

    /// 0 = use what the machine / official tables say.
    var maxResistanceOverride: Int {
        get { int(Keys.maxResistance, 0) }
        set { d.set(min(max(newValue, 0), 100), forKey: Keys.maxResistance) }
    }

    var autoReconnect: Bool {
        get { d.object(forKey: Keys.reconnect) as? Bool ?? true }
        set { d.set(newValue, forKey: Keys.reconnect) }
    }

    /// Peripheral identifier (UUID string).
    var lastDeviceAddress: String? {
        get { d.string(forKey: Keys.lastAddress) }
        set { d.set(newValue, forKey: Keys.lastAddress) }
    }

    var lastDeviceName: String? {
        get { d.string(forKey: Keys.lastName) }
        set { d.set(newValue, forKey: Keys.lastName) }
    }

    var lastResistance: Int {
        get { int(Keys.lastResistance, 1) }
        set { d.set(newValue, forKey: Keys.lastResistance) }
    }

    var calorieAlgorithm: CalorieAlgorithm {
        get { d.string(forKey: Keys.calorieAlgorithm).flatMap(CalorieAlgorithm.init(rawValue:)) ?? .officialNew }
        set { d.set(newValue.rawValue, forKey: Keys.calorieAlgorithm) }
    }

    /// Scales formula-based calories (not machine-reported ones). 100 = unchanged.
    var calorieFactorPercent: Int {
        get { int(Keys.calorieFactor, 100) }
        set { d.set(min(max(newValue, 30), 200), forKey: Keys.calorieFactor) }
    }

    /// Seconds without pedalling before the workout pauses itself. 0 = never. Official default 60.
    var autoPauseSec: Int {
        get { int(Keys.autoPause, 60) }
        set { d.set(min(max(newValue, 0), 600), forKey: Keys.autoPause) }
    }

    var workoutHistoryJson: String? {
        get { d.string(forKey: Keys.history) }
        set { d.set(newValue, forKey: Keys.history) }
    }

    private func int(_ key: String, _ fallback: Int) -> Int {
        d.object(forKey: key) as? Int ?? fallback
    }

    private enum Keys {
        static let protocolChoice = "protocol_choice"
        static let ftmsEncoding = "ftms_encoding"
        static let weight = "user_weight"
        static let maxResistance = "max_resistance_override"
        static let reconnect = "auto_reconnect"
        static let lastAddress = "last_device_address"
        static let lastName = "last_device_name"
        static let lastResistance = "last_resistance"
        static let calorieAlgorithm = "calorie_algorithm"
        static let calorieFactor = "calorie_factor"
        static let autoPause = "auto_pause_sec"
        static let history = "workout_history"
    }
}
