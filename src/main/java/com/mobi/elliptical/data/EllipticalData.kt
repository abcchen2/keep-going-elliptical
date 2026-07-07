package com.mobi.elliptical.data

import androidx.compose.runtime.Immutable
import java.util.UUID

/**
 * 椭圆机实时运动数据
 */
@Immutable
data class ExerciseData(
    val cadence: Int = 0,          // 踏频 (步/分钟)
    val avgCadence: Int = 0,        // 平均踏频
    val resistance: Int = 1,         // 阻力等级 (1-16)
    val calories: Float = 0f,        // 消耗热量 (千卡)
    val duration: Long = 0L,         // 运动时长 (秒，来自设备)
    val distance: Float = 0f,        // 距离 (米)
    val power: Float = 0f,           // 瞬时功率 (瓦特)
    val avgPower: Float = 0f,        // 平均功率 (瓦特)
    val speed: Float = 0f,           // 瞬时速度 (公里/小时)
    val avgSpeed: Float = 0f,        // 平均速度 (公里/小时)
    val heartRate: Int = 0,          // 心率 (次/分钟)
    val strideCount: Int = 0,        // 总步数
    val isRunning: Boolean = false,   // 是否正在运动
    val movementDirection: MovementDirection = MovementDirection.FORWARD,
    val timestamp: Long = System.currentTimeMillis()
)

/**
 * 运动方向
 */
enum class MovementDirection {
    FORWARD, BACKWARD
}

/**
 * 设备连接状态
 */
sealed class ConnectionState {
    object Disconnected : ConnectionState()
    object Scanning : ConnectionState()
    object Connecting : ConnectionState()
    data class Connected(val deviceName: String, val deviceAddress: String) : ConnectionState()
    data class Error(val message: String) : ConnectionState()
}

/**
 * 训练状态（从设备获取）
 */
data class TrainingStatus(
    val status: Byte,
    val description: String
)

/**
 * 健身机状态
 */
data class MachineStatus(
    val status: Byte,
    val description: String
)

/**
 * 支持的阻力范围
 */
data class SupportedResistanceRange(val min: Int, val max: Int)

/**
 * 运动会话记录
 */
data class ExerciseSession(
    val id: String = java.util.UUID.randomUUID().toString(),
    val startTime: Long = 0L,
    val endTime: Long = 0L,
    val totalCalories: Float = 0f,
    val totalDistance: Float = 0f,
    val totalSteps: Int = 0,
    val avgCadence: Int = 0,
    val avgPower: Float = 0f,
    val maxResistance: Int = 1,
    val avgHeartRate: Int = 0
)

/**
 * ============================================================
 * 莫比椭圆机蓝牙协议常量 — 基于 nRF Connect 抓包结果
 * 设备型号: MB-MEH-3202G
 * MAC: 57:4C:71:40:00:ED
 * 制造商: HUAWEI Technologies Co., Ltd.
 * ============================================================
 */
object MobiBleProfile {

    // ==================== 服务 UUID ====================

    /** 健身机服务 (Fitness Machine Service) - 标准 FTMS */
    val FITNESS_MACHINE_SERVICE: UUID = UUID.fromString("00001826-0000-1000-8000-00805f9b34fb")

    /** 心率服务 (Heart Rate Service) */
    val HEART_RATE_SERVICE: UUID = UUID.fromString("0000180d-0000-1000-8000-00805f9b34fb")

    /** 设备信息服务 (Device Information Service) */
    val DEVICE_INFO_SERVICE: UUID = UUID.fromString("0000180a-0000-1000-8000-00805f9b34fb")

    /** 莫比自定义服务 (Mobi Custom Service) */
    val MOBI_CUSTOM_SERVICE: UUID = UUID.fromString("00008800-0000-1000-8000-00805f9b34fb")

    // ==================== FTMS 特征 UUID (Service 0x1826) ====================

    /** 椭圆机数据 (Cross Trainer Data) [Notify] ⭐ 主要数据源 */
    val CROSS_TRAINER_DATA: UUID = UUID.fromString("00002ace-0000-1000-8000-00805f9b34fb")

    /** 健身机控制点 (Control Point) [Indicate+Write] ⭐ 阻力控制 */
    val FITNESS_MACHINE_CONTROL_POINT: UUID = UUID.fromString("00002ad9-0000-1000-8000-00805f9b34fb")

    /** 健身机状态 (Machine Status) [Notify] */
    val FITNESS_MACHINE_STATUS: UUID = UUID.fromString("00002ada-0000-1000-8000-00805f9b34fb")

    /** 健身机特性 (Feature) [Read] */
    val FITNESS_MACHINE_FEATURE: UUID = UUID.fromString("00002acc-0000-1000-8000-00805f9b34fb")

    /** 训练状态 (Training Status) [Notify+Read] */
    val TRAINING_STATUS: UUID = UUID.fromString("00002ad3-0000-1000-8000-00805f9b34fb")

    /** 支持的速度范围 [Read] */
    val SUPPORTED_SPEED_RANGE: UUID = UUID.fromString("00002ad4-0000-1000-8000-00805f9b34fb")

    /** 支持的阻力范围 [Read] ⭐ 用于查询最大阻力 */
    val SUPPORTED_RESISTANCE_RANGE: UUID = UUID.fromString("00002ad6-0000-1000-8000-00805f9b34fb")

    /** 支持的心率范围 [Read] */
    val SUPPORTED_HEART_RATE_RANGE: UUID = UUID.fromString("00002ad7-0000-1000-8000-00805f9b34fb")

    /** 支持的功率范围 [Read] */
    val SUPPORTED_POWER_RANGE: UUID = UUID.fromString("00002ad8-0000-1000-8000-00805f9b34fb")

    /** 莫比自定义控制特征 (Mobi custom control) [Write] */
    val MOBI_CUSTOM_CONTROL: UUID = UUID.fromString("d18d2c10-c44c-11e8-a355-529269fb1459")

    // ==================== 莫比自定义特征 (Service 0x8800) ====================

    /** 莫比实时数据通知 [Notify] */
    val MOBI_REALTIME_DATA: UUID = UUID.fromString("00008811-0000-1000-8000-00805f9b34fb")

    /** 莫比解锁特征 (Unlock GATT) [Write] */
    val MOBI_UNLOCK: UUID = UUID.fromString("000088ff-0000-1000-8000-00805f9b34fb")

    // ==================== 心率特征 (Service 0x180D) ====================

    /** 心率测量 [Notify] */
    val HEART_RATE_MEASUREMENT: UUID = UUID.fromString("00002a37-0000-1000-8000-00805f9b34fb")

    // ==================== 通用特征 ====================

    /** 客户端特征配置描述符 (CCCD) - 用于启用通知 */
    val CCCD: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

    // ==================== 设备名称前缀（用于扫描过滤）====================
    val DEVICE_NAME_PREFIXES = listOf("MB-MEH", "MOBI", "Mobi", "Elliptical")

    // ==================== 阻力范围（默认值，实际值从 0x2AD6 读取）====================
    const val MIN_RESISTANCE = 1
    const val MAX_RESISTANCE = 24  // MB-MEH-3202G 物理最大只支持 24 级阻力

    // ==================== FTMS 控制点操作码 (Op Code) ====================
    object ControlPointOpcode {
        const val REQUEST_CONTROL: Byte = 0x00       // 请求控制权
        const val RESET: Byte = 0x01                   // 重置
        const val SET_TARGET_SPEED: Byte = 0x02        // 设置目标速度
        const val SET_TARGET_INCLINE: Byte = 0x03      // 设置目标坡度
        const val SET_TARGET_RESISTANCE: Byte = 0x04   // 设置目标阻力 ⭐
        const val SET_TARGET_POWER: Byte = 0x05        // 设置目标功率
        const val START_OR_RESUME: Byte = 0x06        // 开始/恢复运动
        const val STOP_OR_PAUSE: Byte = 0x07           // 停止/暂停运动
        const val SET_TARGET_ZONE_HEART_RATE: Byte = 0x08  // 设置目标心率区间
    }

    /**
     * 控制命令工厂方法
     */
    object Commands {
        /**
         * 设置阻力等级（通过 FTMS 控制点 0x2AD9, opcode=0x04）
         *
         * ⚠️ 修复：莫比官方 App 实际只发送 2 字节数据 (Opcode + 1 字节参数)
         * 发送 3 字节会导致设备解析错误，出现阻力值交替变化（加一档重，再加一档轻）的 Bug。
         *
         *   level=1  → {0x04, 0x0A}
         *   level=13 → {0x04, 0x82}
         *   level=24 → {0x04, 0xF0}
         *
         * ⚠️ 关键修复：不要对level进行任何clamping或截断操作！
         * 直接使用传入的level值，让设备自己处理。
         * 如果设备返回超出范围的阻力值，那是设备的问题，不要在应用端强行截断。
         *
         * @param level 目标阻力等级 (1-24)
         * @return 写入控制点的字节数组 (2 字节)
         */
        fun setResistance(level: Int, maxLevel: Int = 24): ByteArray {
            val resistanceValue = level * 10
            val lowByte = (resistanceValue and 0xFF).toByte()
            return byteArrayOf(ControlPointOpcode.SET_TARGET_RESISTANCE, lowByte)
        }

        /**
         * 开始/恢复运动
         */
        fun startExercise(): ByteArray {
            return byteArrayOf(ControlPointOpcode.START_OR_RESUME)
        }

        /**
         * 停止/暂停运动
         */
        fun stopExercise(): ByteArray {
            return byteArrayOf(ControlPointOpcode.STOP_OR_PAUSE)
        }

        /**
         * 请求控制权（必须先调用才能发送控制指令）
         */
        fun requestControl(): ByteArray {
            return byteArrayOf(ControlPointOpcode.REQUEST_CONTROL)
        }

        /**
         * 解锁莫比协议 (必须在请求控制权之前或连接后尽早发送)
         */
        fun unlockGatt(): ByteArray {
            return byteArrayOf(0x11, 0x82.toByte(), 0x07)
        }
    }
}
