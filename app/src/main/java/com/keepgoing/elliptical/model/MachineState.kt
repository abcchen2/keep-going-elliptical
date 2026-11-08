package com.keepgoing.elliptical.model

import androidx.annotation.StringRes
import com.keepgoing.elliptical.R

enum class ConnectionPhase(@StringRes val labelRes: Int) {
    IDLE(R.string.phase_idle),
    SCANNING(R.string.phase_scanning),
    CONNECTING(R.string.phase_connecting),
    SETTING_UP(R.string.phase_setting_up),
    READY(R.string.phase_ready),
    RECONNECTING(R.string.phase_reconnecting),
}

enum class ProtocolKind(@StringRes val labelRes: Int) {
    V2(R.string.protocol_v2),
    V1(R.string.protocol_v1),
    HUANTONG(R.string.protocol_huantong),
    FTMS(R.string.protocol_ftms),
    DEMO(R.string.protocol_demo),
}

/** User choice for protocol selection. AUTO mirrors the official detection order. */
enum class ProtocolChoice(@StringRes val labelRes: Int, val kind: ProtocolKind?) {
    AUTO(R.string.choice_auto, null),
    V2(R.string.choice_v2, ProtocolKind.V2),
    V1(R.string.choice_v1, ProtocolKind.V1),
    HUANTONG(R.string.choice_huantong, ProtocolKind.HUANTONG),
    FTMS(R.string.choice_ftms, ProtocolKind.FTMS),
}

/**
 * How the FTMS "Set Target Resistance Level" (op code 0x04) parameter is encoded.
 * The official app sends a single byte = level × 10 (FTMS v1.0 UINT8, 0.1 resolution).
 * Generic FTMS apps usually send SINT16, which these machines misinterpret.
 */
enum class FtmsResistanceEncoding(@StringRes val labelRes: Int) {
    UINT8_X10(R.string.ftms_enc_uint8_x10),
    SINT16_X10(R.string.ftms_enc_sint16_x10),
    UINT8_X1(R.string.ftms_enc_uint8_x1),
}

enum class WorkoutCommand(@StringRes val labelRes: Int) {
    START(R.string.cmd_start),
    PAUSE(R.string.cmd_pause),
    STOP(R.string.cmd_stop),
}

data class MachineState(
    val phase: ConnectionPhase = ConnectionPhase.IDLE,
    val deviceName: String? = null,
    val deviceAddress: String? = null,
    val protocol: ProtocolKind? = null,
    /** Resolved at display time so it follows the system language. */
    val machineDescription: UiText? = null,
    val resistanceMin: Int = 1,
    val resistanceMax: Int = 24,
    /** Where the min/max came from, shown to the user. */
    @StringRes val resistanceRangeSource: Int = R.string.range_default,
    /** Mobi resistance mode: 0 none, 1 write only, 2 read only, 3 read+write. */
    val resistanceMode: Int? = null,
    /** Last level this app sent (or will keep sending, for HuanTong). */
    val targetResistance: Int = 1,
    /** Level reported back by the machine, if the protocol reports it. */
    val reportedResistance: Int? = null,
    /** Device type code as reported by the machine (9 rowing, 10 bike, 11 elliptical); null = unknown. */
    val machineType: Int? = null,
    val subType: Int? = null,
    /** Live cadence. Considered stale when [rpmUpdatedAt] is older than a few seconds. */
    val rpm: Double? = null,
    val rpmUpdatedAt: Long = 0,
    /** Speed reported by the machine (km/h), if any. */
    val deviceSpeedKmh: Double? = null,
    /**
     * Running totals reported by the machine itself (V2 0x8813, FTMS 2ACE/2AD2).
     * These are absolute counters that may start from a non-zero value or reset; the
     * workout tracker only uses their per-second increments.
     */
    val deviceDistanceM: Double? = null,
    val deviceCalories: Double? = null,
    val heartRate: Int? = null,
    val heartRateUpdatedAt: Long = 0,
    val battery: Int? = null,
    @StringRes val machineRunState: Int? = null,
    val modelNumber: String? = null,
    val firmware: String? = null,
    /** True once the protocol finished its setup and accepts resistance commands. */
    val controlReady: Boolean = false,
    @StringRes val lastError: Int? = null,
)

data class FoundDevice(
    val address: String,
    val name: String?,
    val rssi: Int,
    val serviceHints: List<String>,
)

/** Calorie formula. Both are copied from SportStateMachineToolKt in the official app. */
enum class CalorieAlgorithm(@StringRes val labelRes: Int) {
    OFFICIAL_NEW(R.string.calorie_new),
    OFFICIAL_OLD(R.string.calorie_old),
}

enum class WorkoutStatus(@StringRes val labelRes: Int) {
    IDLE(R.string.status_idle),
    RUNNING(R.string.status_running),
    AUTO_PAUSED(R.string.status_auto_paused),
    PAUSED(R.string.status_paused),
    FINISHED(R.string.status_finished),
}

/** Where a total comes from, so the user can judge the number. */
enum class DataSource(@StringRes val labelRes: Int) {
    NONE(R.string.source_none),
    MACHINE(R.string.source_machine),
    FORMULA(R.string.source_formula),
}

data class WorkoutStats(
    val status: WorkoutStatus = WorkoutStatus.IDLE,
    val startedAt: Long = 0,
    /** Seconds spent in RUNNING (pauses do not count). */
    val activeSec: Int = 0,
    val distanceM: Double = 0.0,
    val calories: Double = 0.0,
    val distanceSource: DataSource = DataSource.NONE,
    val calorieSource: DataSource = DataSource.NONE,
    val speedKmh: Double = 0.0,
    val avgRpm: Double = 0.0,
    val avgResistance: Double = 0.0,
    val avgHeartRate: Int? = null,
    val maxHeartRate: Int? = null,
    /** Current calorie burn rate (kcal/min), shown so an unrealistic value is easy to spot. */
    val kcalPerMin: Double = 0.0,
)

data class WorkoutRecord(
    val startedAt: Long,
    val activeSec: Int,
    val distanceM: Double,
    val calories: Double,
    val avgRpm: Double,
    val avgResistance: Double,
    val avgHeartRate: Int?,
    val maxHeartRate: Int?,
)
