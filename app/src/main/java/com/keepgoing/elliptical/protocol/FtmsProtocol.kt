package com.keepgoing.elliptical.protocol

import android.bluetooth.BluetoothGattCharacteristic
import com.keepgoing.elliptical.ble.BleUuids
import com.keepgoing.elliptical.ble.bytesOf
import com.keepgoing.elliptical.ble.s16le
import com.keepgoing.elliptical.ble.toHex
import com.keepgoing.elliptical.ble.u
import com.keepgoing.elliptical.ble.u16le
import com.keepgoing.elliptical.ble.u24le
import com.keepgoing.elliptical.model.FtmsResistanceEncoding
import com.keepgoing.elliptical.model.ProtocolKind
import com.keepgoing.elliptical.model.WorkoutCommand
import java.util.UUID
import kotlin.math.roundToInt

/**
 * FTMS (0x1826) as spoken by Mobi machines (FtmsHandler in official app 4.5.17).
 *
 * Differences from what a generic FTMS client does:
 *  - "Set Target Resistance Level" is sent as `04 PP` with ONE byte PP = level × 10
 *    (FTMS 1.0 UINT8, resolution 0.1). Sending SINT16 (`04 lo hi`) or the raw level
 *    makes the machine land on the wrong level (level 8 sent raw = 0.8 -> level 1).
 *  - "Request Control" (`00`) is written first, before subscribing to anything.
 *  - Supported Resistance Level Range (2AD6) min/max are SINT16 × 0.1.
 *  - Cross Trainer Data (2ACE) uses a 2-byte flags field on most Mobi ellipticals instead of
 *    the 3 bytes required by the spec ("MB-E1010" / "Fibonacci" use 3). The official parser
 *    picks whichever size makes the payload length add up; this one does the same.
 *  - Start = `07`, Stop = `08 01`, Pause = `08 02`.
 */
class FtmsProtocol(host: ProtocolHost) : MachineProtocol(host) {
    override val kind = ProtocolKind.FTMS

    private var controlPoint: BluetoothGattCharacteristic? = null
    private var crossTrainerFlagSize =
        if (host.deviceName.startsWith("MB-E1010") || host.deviceName.startsWith("Fibonacci")) 3 else 2
    private var lastCommand: ByteArray? = null
    private var retriedAfterDenied = false

    override fun start() {
        val svc = BleUuids.FTMS_SERVICE
        controlPoint = link.characteristic(svc, BleUuids.FTMS_CONTROL_POINT)
        val cp = controlPoint
        if (cp == null) {
            log("警告: 没有 0x2AD9 控制点，无法调阻力")
        } else {
            // The official app never subscribes to the control point. Spec-compliant machines
            // require it before accepting writes, Mobi machines do not care, so enable it when offered.
            if (cp.properties and BluetoothGattCharacteristic.PROPERTY_INDICATE != 0) link.enableNotify(cp)
            log("请求控制权 00 -> 0x2AD9")
            link.write(cp, bytesOf(0x00))
        }

        link.characteristic(svc, BleUuids.FTMS_FEATURE)?.let { ch ->
            link.read(ch) { v -> if (v != null && v.size >= 8) onFeatures(v) }
        }
        link.characteristic(svc, BleUuids.FTMS_RESISTANCE_RANGE)?.let { ch ->
            link.read(ch) { v -> if (v != null) onResistanceRange(v) }
        }
        for (id in listOf(
            BleUuids.FTMS_CROSS_TRAINER_DATA,
            BleUuids.FTMS_INDOOR_BIKE_DATA,
            BleUuids.FTMS_TRAINING_STATUS,
            BleUuids.FTMS_STATUS,
        )) {
            val ch = link.characteristic(svc, id) ?: continue
            if (ch.properties and (BluetoothGattCharacteristic.PROPERTY_NOTIFY or BluetoothGattCharacteristic.PROPERTY_INDICATE) != 0) {
                link.enableNotify(ch)
            }
        }
        setupStandardServices()
        val elliptical = link.characteristic(svc, BleUuids.FTMS_CROSS_TRAINER_DATA) != null
        reportMachineType(if (elliptical) ResistanceTables.TYPE_ELLIPTICAL else ResistanceTables.TYPE_BIKE, null)
        host.update {
            it.copy(controlReady = true, machineDescription = it.machineDescription ?: if (elliptical) "FTMS 椭圆机" else "FTMS 健身设备")
        }
    }

    private fun onFeatures(v: ByteArray) {
        val machine = v[0].u() or (v[1].u() shl 8) or (v[2].u() shl 16) or (v[3].u() shl 24)
        val target = v[4].u() or (v[5].u() shl 8) or (v[6].u() shl 16) or (v[7].u() shl 24)
        var mode = 0
        if (machine shr 7 and 1 == 1) mode = 2          // Resistance Level Supported
        if (target shr 2 and 1 == 1) mode = 3           // Resistance Target Setting Supported
        log("2ACC 特性: machine=0x%08X target=0x%08X".format(machine, target))
        host.update { it.copy(resistanceMode = mode) }
        if (target shr 2 and 1 == 0) log("提示: 设备未声明支持阻力目标设置，仍会尝试发送")
    }

    private fun onResistanceRange(v: ByteArray) {
        log("2AD6 阻力范围: ${v.toHex()}")
        if (v.size < 4) return
        val rawMin = v.s16le(0)
        val rawMax = v.s16le(2)
        var min = (rawMin * 0.1).roundToInt()
        var max = (rawMax * 0.1).roundToInt()
        if (max < 2 && rawMax in 2..100) {
            // Some boards report whole levels here instead of 0.1 units.
            log("2AD6 看起来是整数档位，按原值使用")
            min = rawMin
            max = rawMax
        }
        if (max > 0) applyRange(min, max, "设备 0x2AD6")
    }

    override fun setResistance(level: Int) {
        val cp = controlPoint ?: return log("没有 0x2AD9，无法设置阻力")
        val cmd = when (host.settings.ftmsEncoding) {
            FtmsResistanceEncoding.UINT8_X10 -> {
                val p = level * 10
                if (p > 255) log("档位 $level ×10 超过 1 字节，已截断为 25.5；如需更高档位请切换为 SINT16 编码")
                bytesOf(0x04, p.coerceAtMost(255))
            }
            FtmsResistanceEncoding.SINT16_X10 -> {
                val p = level * 10
                bytesOf(0x04, p and 0xFF, (p shr 8) and 0xFF)
            }
            FtmsResistanceEncoding.UINT8_X1 -> bytesOf(0x04, level)
        }
        send(cp, cmd)
    }

    override fun sendWorkout(cmd: WorkoutCommand) {
        val cp = controlPoint ?: return
        send(
            cp,
            when (cmd) {
                WorkoutCommand.START -> bytesOf(0x07)
                WorkoutCommand.STOP -> bytesOf(0x08, 0x01)
                WorkoutCommand.PAUSE -> bytesOf(0x08, 0x02)
            },
        )
    }

    private fun send(cp: BluetoothGattCharacteristic, cmd: ByteArray) {
        lastCommand = cmd
        retriedAfterDenied = false
        link.write(cp, cmd)
    }

    override fun onNotify(ch: UUID, value: ByteArray) {
        if (handleStandardNotify(ch, value) || value.isEmpty()) return
        when (ch) {
            BleUuids.FTMS_CONTROL_POINT -> onControlResponse(value)
            BleUuids.FTMS_CROSS_TRAINER_DATA -> parseCrossTrainer(value)
            BleUuids.FTMS_INDOOR_BIKE_DATA -> parseIndoorBike(value)
            BleUuids.FTMS_STATUS -> onMachineStatus(value)
            BleUuids.FTMS_TRAINING_STATUS -> if (value.size >= 2) log("训练状态: ${value[1].u()}")
        }
    }

    private fun onControlResponse(v: ByteArray) {
        if (v.size < 3 || v[0].u() != 0x80) return
        val op = v[1].u()
        val result = v[2].u()
        val text = when (result) {
            1 -> "成功"
            2 -> "不支持该指令"
            3 -> "参数无效"
            4 -> "执行失败"
            5 -> "没有控制权"
            else -> "结果 $result"
        }
        log("2AD9 应答: 指令 0x%02X -> %s".format(op, text))
        if (result == 5 && !retriedAfterDenied) {
            val again = lastCommand ?: return
            val cp = controlPoint ?: return
            retriedAfterDenied = true
            log("重新请求控制权后重发")
            link.write(cp, bytesOf(0x00))
            link.write(cp, again)
        }
    }

    private fun onMachineStatus(v: ByteArray) {
        when (v[0].u()) {
            0x02 -> host.update { it.copy(machineRunState = if (v.size > 1 && v[1].u() == 2) "暂停" else "停止") }
            0x04 -> host.update { it.copy(machineRunState = "运行") }
            0x07 -> if (v.size >= 2) log("设备确认目标阻力 ${v[1].u() / 10.0}")
            0xFF -> log("设备收回了控制权，下次调节会重新请求")
            else -> log("2ADA 状态: ${v.toHex()}")
        }
    }

    // ------------------------------------------------------------- data parsing

    private class Part(val size: Int, val signed: Boolean, val resolution: Double, val key: String?)
    private class Field(val bit: Int, val inverted: Boolean, val parts: List<Part>) {
        val size get() = parts.sumOf { it.size }
    }

    private fun p(size: Int, key: String? = null, res: Double = 1.0, signed: Boolean = false) = Part(size, signed, res, key)

    private val crossTrainerFields = listOf(
        Field(0, true, listOf(p(2, "speed", 0.01))),
        Field(1, false, listOf(p(2))),
        Field(2, false, listOf(p(3, "distance"))),
        Field(3, false, listOf(p(2, "cadence"), p(2))),
        Field(4, false, listOf(p(2))),
        Field(5, false, listOf(p(2), p(2))),
        Field(6, false, listOf(p(2, signed = true), p(2, signed = true))),
        Field(7, false, listOf(p(2, "resistance", 0.1, signed = true))),
        Field(8, false, listOf(p(2, signed = true))),
        Field(9, false, listOf(p(2, signed = true))),
        Field(10, false, listOf(p(2, "energy"), p(2), p(1))),
        Field(11, false, listOf(p(1, "hr"))),
        Field(12, false, listOf(p(1))),
        Field(13, false, listOf(p(2, "elapsed"))),
        Field(14, false, listOf(p(2))),
    )

    private val indoorBikeFields = listOf(
        Field(0, true, listOf(p(2, "speed", 0.01))),
        Field(1, false, listOf(p(2))),
        Field(2, false, listOf(p(2, "cadence", 0.5))),
        Field(3, false, listOf(p(2))),
        Field(4, false, listOf(p(3, "distance"))),
        Field(5, false, listOf(p(2, "resistance", 1.0, signed = true))),
        Field(6, false, listOf(p(2, signed = true))),
        Field(7, false, listOf(p(2, signed = true))),
        Field(8, false, listOf(p(2, "energy"), p(2), p(1))),
        Field(9, false, listOf(p(1, "hr"))),
        Field(10, false, listOf(p(1))),
        Field(11, false, listOf(p(2, "elapsed"))),
        Field(12, false, listOf(p(2))),
    )

    private fun present(fields: List<Field>, flags: Int) = fields.filter { f ->
        val set = (flags shr f.bit) and 1 == 1
        if (f.inverted) !set else set
    }

    private fun parseCrossTrainer(v: ByteArray) {
        if (v.size < 2) return
        val flags = v.u16le(0)
        val fields = present(crossTrainerFields, flags)
        val body = fields.sumOf { it.size }
        val size = when (v.size) {
            2 + body -> 2
            3 + body -> 3
            else -> crossTrainerFlagSize
        }
        if (size != crossTrainerFlagSize) {
            log("2ACE 标志位长度按实际数据调整为 $size 字节")
            crossTrainerFlagSize = size
        }
        applyValues(decode(v, size, fields))
    }

    private fun parseIndoorBike(v: ByteArray) {
        if (v.size < 2) return
        applyValues(decode(v, 2, present(indoorBikeFields, v.u16le(0))))
    }

    private fun decode(v: ByteArray, flagSize: Int, fields: List<Field>): Map<String, Double> {
        val out = HashMap<String, Double>()
        var offset = flagSize
        loop@ for (f in fields) {
            for (part in f.parts) {
                if (offset + part.size > v.size) break@loop
                val raw = when (part.size) {
                    1 -> if (part.signed) v[offset].toInt() else v[offset].u()
                    2 -> if (part.signed) v.s16le(offset) else v.u16le(offset)
                    3 -> v.u24le(offset)
                    else -> 0
                }
                part.key?.let { out[it] = raw * part.resolution }
                offset += part.size
            }
        }
        return out
    }

    private fun applyValues(m: Map<String, Double>) {
        m["resistance"]?.let { reportResistance(it.roundToInt()) }
        m["cadence"]?.let { reportRpm(it) }
        m["hr"]?.let { reportHeartRate(it.toInt()) }
        // Total distance / total energy are running counters; WorkoutTracker uses the increments.
        host.update { s ->
            s.copy(
                deviceSpeedKmh = m["speed"] ?: s.deviceSpeedKmh,
                deviceDistanceM = m["distance"] ?: s.deviceDistanceM,
                // 0xFFFF means "not available" in FTMS.
                deviceCalories = m["energy"]?.takeIf { it < 65535 } ?: s.deviceCalories,
            )
        }
    }
}
