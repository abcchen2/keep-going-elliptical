package com.keepgoing.elliptical.model

enum class ConnectionPhase(val label: String) {
    IDLE("未连接"),
    SCANNING("扫描中"),
    CONNECTING("连接中"),
    SETTING_UP("初始化设备"),
    READY("已连接"),
    RECONNECTING("重新连接中"),
}

enum class ProtocolKind(val label: String) {
    V2("莫比 2.0 协议 (服务 0x8800)"),
    V1("莫比 1.0 协议 (FFE0, 0xAB 帧)"),
    HUANTONG("环通协议 (FFF0, 每秒心跳)"),
    FTMS("FTMS 标准协议 (0x1826, 莫比变体)"),
    DEMO("演示模式 Demo — 模拟椭圆机，未连接真实设备"),
}

/** User choice for protocol selection. AUTO mirrors the official detection order. */
enum class ProtocolChoice(val label: String, val kind: ProtocolKind?) {
    AUTO("自动识别", null),
    V2("强制 莫比 2.0", ProtocolKind.V2),
    V1("强制 莫比 1.0", ProtocolKind.V1),
    HUANTONG("强制 环通", ProtocolKind.HUANTONG),
    FTMS("强制 FTMS", ProtocolKind.FTMS),
}

/**
 * How the FTMS "Set Target Resistance Level" (op code 0x04) parameter is encoded.
 * The official app sends a single byte = level × 10 (FTMS v1.0 UINT8, 0.1 resolution).
 * Generic FTMS apps usually send SINT16, which these machines misinterpret.
 */
enum class FtmsResistanceEncoding(val label: String) {
    UINT8_X10("官方: 1 字节, 档位×10"),
    SINT16_X10("2 字节 SINT16, 档位×10"),
    UINT8_X1("1 字节, 档位原值"),
}

enum class WorkoutCommand(val label: String) {
    START("开始"),
    PAUSE("暂停"),
    STOP("停止"),
}

data class MachineState(
    val phase: ConnectionPhase = ConnectionPhase.IDLE,
    val deviceName: String? = null,
    val deviceAddress: String? = null,
    val protocol: ProtocolKind? = null,
    val machineDescription: String? = null,
    val resistanceMin: Int = 1,
    val resistanceMax: Int = 24,
    /** Where the min/max came from, shown to the user. */
    val resistanceRangeSource: String = "默认值",
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
    val machineRunState: String? = null,
    val modelNumber: String? = null,
    val firmware: String? = null,
    /** True once the protocol finished its setup and accepts resistance commands. */
    val controlReady: Boolean = false,
    val lastError: String? = null,
)

data class FoundDevice(
    val address: String,
    val name: String?,
    val rssi: Int,
    val serviceHints: List<String>,
)

/** Calorie formula. Both are copied from SportStateMachineToolKt in the official app. */
enum class CalorieAlgorithm(val label: String) {
    OFFICIAL_NEW("官方新算法（推荐）"),
    OFFICIAL_OLD("官方老算法（高阻力时偏高）"),
}

enum class WorkoutStatus(val label: String) {
    IDLE("未开始"),
    RUNNING("运动中"),
    AUTO_PAUSED("自动暂停（没有踩动）"),
    PAUSED("已暂停"),
    FINISHED("已结束"),
}

/** Where a total comes from, so the user can judge the number. */
enum class DataSource(val label: String) {
    NONE("—"),
    MACHINE("机器上报"),
    FORMULA("按官方公式计算"),
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
