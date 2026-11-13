package com.keepgoing.elliptical.workout

import com.keepgoing.elliptical.model.CalorieAlgorithm
import com.keepgoing.elliptical.protocol.ResistanceTables
import kotlin.math.exp
import kotlin.math.min

/**
 * Speed and calorie formulas of the official app, for machines that only report cadence
 * (1.0 boxes, HuanTong boards, 2.0 boxes in upload mode 1).
 *
 * Sources (official 4.5.17):
 *  - MotionData.setRPM():            speed(km/h) = rpm / 2.68, elliptical additionally / 4
 *  - MotionData.cumulativeData():    once per second: distance += speed / 3.6, calories += kcalPerSecond
 *  - SportStateMachineToolKt.calculateCalories / calculatePower
 *
 * Both official formulas are kcal **per second** and are summed once per second. Two easy
 * mistakes produce "300 kcal in 10 minutes":
 *  - the "new" formula returns kcal per HOUR; forgetting the / 3600 inflates it 3600×.
 *  - adding a per-second value on every BLE notification (several per second) instead of
 *    once per elapsed second, or adding a machine's running total instead of its increment.
 * This class always integrates rate × elapsed time and caps the rate at a physical limit.
 *
 * The OLD formula is an exponential surface fit and grows quickly at high levels:
 * 60 rpm at level 20/24 gives ~25 kcal/min uncapped (250 kcal in 10 minutes). The NEW
 * formula gives ~14 kcal/min for the same input, which is why it is the default here.
 */
object CalorieModel {

    /** Hard ceiling for the burn rate. Above ~20 kcal/min is elite-athlete territory. */
    const val MAX_KCAL_PER_MIN = 20.0

    fun speedKmh(rpm: Double, machineType: Int?): Double {
        if (rpm <= 0) return 0.0
        var kmh = rpm / 2.68
        if (machineType == null || machineType == ResistanceTables.TYPE_ELLIPTICAL) kmh /= 4
        return kmh
    }

    /**
     * Calorie burn rate in kcal per second, before the user factor.
     * @param resistance level currently applied (1..max)
     * @param subType machine sub type; the official app multiplies power by 1.5 for elliptical sub type 17
     */
    fun kcalPerSecond(
        algorithm: CalorieAlgorithm,
        rpm: Double,
        resistance: Int,
        resistanceMax: Int,
        weightKg: Int,
        machineType: Int?,
        subType: Int?,
    ): Double {
        // No pedalling, no calories. (The official "new" formula has a +125 kcal/h constant
        // that would otherwise keep counting while the user stands still.)
        if (rpm <= 0) return 0.0
        val max = resistanceMax.coerceAtLeast(1)
        val rate = when (algorithm) {
            CalorieAlgorithm.OFFICIAL_OLD -> {
                val power = powerOld(rpm, resistance, max, machineType, subType)
                if (power == 0.0) 0.0 else power / 1000.0 + weightKg / 3600.0
            }
            CalorieAlgorithm.OFFICIAL_NEW -> kcalPerHourNew(rpm, resistance, max) / 3600.0
        }
        return rate.coerceIn(0.0, MAX_KCAL_PER_MIN / 60.0)
    }

    /** SportStateMachineToolKt.calculatePower(rpm, res, 0): surface fit, watts. */
    private fun powerOld(rpm: Double, resistance: Int, max: Int, machineType: Int?, subType: Int?): Double {
        val e1 = exp(rpm / 100.0)
        val e2 = exp((resistance * 32.0 / max) / 10.0)
        var p = 39.94501450202982 +
            -67.52588516 * e1 +
            -39.15593086 * e2 +
            27.55487038 * e1 * e1 +
            38.79652081 * e1 * e2 +
            -0.30231185 * e2 * e2
        if (p < 0) p = 0.0
        // DeviceToolsKt.elliptical0B11()
        if (machineType == ResistanceTables.TYPE_ELLIPTICAL && subType == 17) p *= 1.5
        return p
    }

    /** SportStateMachineToolKt.calculateCalories(rpm, res): kcal per hour. */
    private fun kcalPerHourNew(rpm: Double, resistance: Int, max: Int): Double {
        val res = resistance.coerceIn(1, max)
        val index = min(96, (96 / max) * res)
        return (NEW_TABLE[index] / 1000.0) * rpm * 60 +
            (0.058264444 * rpm * rpm - 4.3285 * rpm) + 125.303 +
            (12.0 * (res - 1) / 7.0) * (rpm / 20.0 - 1.8)
    }

    /**
     * 97 lookup values of the official "new" formula (SportStateMachineToolKt, 4.5.17).
     * Numeric data kept as-is so results match the official app; see docs/PROTOCOL.en.md §7.
     */
    private val NEW_TABLE = intArrayOf(
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
    )
}
