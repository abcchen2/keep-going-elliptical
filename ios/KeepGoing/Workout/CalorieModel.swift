import Foundation

/// Speed and calorie formulas of the official app, for machines that only report cadence.
/// Port of workout/CalorieModel.kt; see that file and .kiro/steering/workout-metrics.md.
///
/// Sources (official 4.5.17):
///  - MotionData.setRPM():         speed(km/h) = rpm / 2.68, elliptical additionally / 4
///  - MotionData.cumulativeData(): once per second: distance += speed / 3.6, calories += kcalPerSecond
///  - SportStateMachineToolKt.calculateCalories / calculatePower
///
/// The "new" formula returns kcal per HOUR (divide by 3600). The rate is integrated over real
/// elapsed time by WorkoutTracker and capped at maxKcalPerMin.
enum CalorieModel {
    /// Hard ceiling for the burn rate. Above ~20 kcal/min is elite-athlete territory.
    static let maxKcalPerMin = 20.0

    static func speedKmh(_ rpm: Double, _ machineType: Int?) -> Double {
        if rpm <= 0 { return 0 }
        var kmh = rpm / 2.68
        if machineType == nil || machineType == ResistanceTables.typeElliptical { kmh /= 4 }
        return kmh
    }

    /// Calorie burn rate in kcal per second, before the user factor.
    static func kcalPerSecond(
        algorithm: CalorieAlgorithm,
        rpm: Double,
        resistance: Int,
        resistanceMax: Int,
        weightKg: Int,
        machineType: Int?,
        subType: Int?
    ) -> Double {
        // No pedalling, no calories. (The "new" formula has a +125 kcal/h constant.)
        if rpm <= 0 { return 0 }
        let maxLevel = max(resistanceMax, 1)
        let rate: Double
        switch algorithm {
        case .officialOld:
            let power = powerOld(rpm, resistance, maxLevel, machineType, subType)
            rate = power == 0 ? 0 : power / 1000.0 + Double(weightKg) / 3600.0
        case .officialNew:
            rate = kcalPerHourNew(rpm, resistance, maxLevel) / 3600.0
        }
        return min(max(rate, 0), maxKcalPerMin / 60.0)
    }

    /// SportStateMachineToolKt.calculatePower(rpm, res, 0): surface fit, watts.
    private static func powerOld(_ rpm: Double, _ resistance: Int, _ maxLevel: Int, _ machineType: Int?, _ subType: Int?) -> Double {
        let e1 = exp(rpm / 100.0)
        let e2 = exp((Double(resistance) * 32.0 / Double(maxLevel)) / 10.0)
        var p = 39.94501450202982
        p += -67.52588516 * e1
        p += -39.15593086 * e2
        p += 27.55487038 * e1 * e1
        p += 38.79652081 * e1 * e2
        p += -0.30231185 * e2 * e2
        if p < 0 { p = 0 }
        // DeviceToolsKt.elliptical0B11()
        if machineType == ResistanceTables.typeElliptical && subType == 17 { p *= 1.5 }
        return p
    }

    /// SportStateMachineToolKt.calculateCalories(rpm, res): kcal per hour.
    private static func kcalPerHourNew(_ rpm: Double, _ resistance: Int, _ maxLevel: Int) -> Double {
        let res = min(max(resistance, 1), maxLevel)
        // Integer division, as in the official code.
        let index = min(96, (96 / maxLevel) * res)
        var kcal = Double(newTable[index]) / 1000.0 * rpm * 60
        kcal += 0.058264444 * rpm * rpm - 4.3285 * rpm
        kcal += 125.303
        kcal += (12.0 * Double(res - 1) / 7.0) * (rpm / 20.0 - 1.8)
        return kcal
    }

    /// 97 lookup values of the official "new" formula (SportStateMachineToolKt, 4.5.17).
    /// Numeric data kept as-is so results match the official app; see docs/PROTOCOL.en.md §7.
    private static let newTable: [Int] = [
        71, 71, 71, 40, 41, 42, 43, 44, 45, 47,
        48, 49, 50, 51, 52, 54, 55, 56, 58, 60,
        63, 67, 68, 69, 71, 72, 73, 74, 76, 79,
        82, 84, 86, 89, 91, 94, 97, 99, 101, 104,
        106, 108, 111, 113, 116, 119, 121, 123, 126, 128,
        130, 132, 134, 136, 138, 140, 142, 145, 147, 149,
        152, 154, 157, 160, 162, 164, 167, 169, 172, 175,
        177, 180, 183, 185, 188, 191, 193, 195, 198, 201,
        204, 207, 210, 213, 216, 219, 222, 226, 229, 232,
        235, 239, 243, 247, 251, 255, 260,
    ]
}
