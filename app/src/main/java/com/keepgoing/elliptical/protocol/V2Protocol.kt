package com.keepgoing.elliptical.protocol

import com.keepgoing.elliptical.ble.BleUuids
import com.keepgoing.elliptical.ble.bytesOf
import com.keepgoing.elliptical.ble.toHex
import com.keepgoing.elliptical.ble.u
import com.keepgoing.elliptical.ble.u16be
import com.keepgoing.elliptical.model.ProtocolKind
import com.keepgoing.elliptical.model.WorkoutCommand
import java.util.UUID

/**
 * Mobi "2.0" box (GATT service 0x8800).
 *
 * Resistance control, as implemented by V2Handler.bleVer0x02Control() in the official app:
 *  1. Write `11 82 07` to 0x88FF once per connection ("unlock write permission").
 *     Without this the box silently ignores every 0x880F write.
 *  2. Write `02 LL VV` to 0x880F, where VV is the level and LL is the number of
 *     DECIMAL DIGITS of the level (String.valueOf(level).length): 5 -> `02 01 05`, 12 -> `02 02 0C`.
 *     LL is not a byte length; sending `02 01 0C` for level 12 is a malformed command.
 *  3. The machine echoes the active level on 0x8812 (notify/read).
 *
 * Start/pause/stop (`04` / `05` / `03` on 0x880F) are only sent when bit 1 of 0x8801[0] is set,
 * same condition as V2Handler.setCurMachineStatus().
 */
class V2Protocol(host: ProtocolHost) : MachineProtocol(host) {
    override val kind = ProtocolKind.V2

    private var unlocked = false
    private var unlocking = false
    private val afterUnlock = ArrayDeque<() -> Unit>()

    /** 0x8801[0]: 1 = RPM via 8811 only, 2/3 = 8813 aggregate data (3 = both). */
    private var uploadMode = 0
    private var magnets = 1
    private var rangeFromDevice = false

    override fun start() {
        val svc = BleUuids.V2_SERVICE
        link.characteristic(svc, BleUuids.V2_UPLOAD_TYPE)?.let { ch ->
            link.read(ch) { v ->
                if (v != null && v.isNotEmpty()) {
                    uploadMode = v[0].u()
                    log("8801 数据模式=$uploadMode" + if (supportsStartStop()) " (需要开始指令)" else "")
                }
            }
        }
        link.characteristic(svc, BleUuids.V2_SPORT_TYPE)?.let { ch ->
            link.read(ch) { v -> if (v != null && v.size >= 2) onSportType(v[0].u(), v[1].u()) }
        }
        link.characteristic(svc, BleUuids.V2_MAGNET)?.let { ch ->
            link.read(ch) { v ->
                if (v != null && v.isNotEmpty() && v[0].u() > 0) magnets = v[0].u()
            }
        }
        link.characteristic(svc, BleUuids.V2_RESISTANCE_RANGE)?.let { ch ->
            link.read(ch) { v ->
                if (v == null) return@read
                log("8806 阻力能力: ${v.toHex()}")
                if (v.size >= 3 && v[2].u() > 0) {
                    rangeFromDevice = true
                    applyRange(v[1].u(), v[2].u(), "设备 0x8806", ResistanceTables.readWriteMode(v[0].u()))
                }
            }
        }
        link.characteristic(svc, BleUuids.V2_RESISTANCE_GEAR)?.let { ch ->
            if (ch.properties and android.bluetooth.BluetoothGattCharacteristic.PROPERTY_READ != 0) {
                link.read(ch) { v -> if (v != null && v.isNotEmpty()) reportResistance(v[0].u()) }
            }
        }
        for (id in listOf(
            BleUuids.V2_MACHINE_STATE,
            BleUuids.V2_INTERVAL,
            BleUuids.V2_RESISTANCE_GEAR,
            BleUuids.V2_ALL_DATA,
            BleUuids.V2_ERROR,
        )) {
            link.characteristic(svc, id)?.let { link.enableNotify(it) }
        }
        setupStandardServices()
        if (link.characteristic(svc, BleUuids.V2_CONTROL) == null) {
            log("警告: 没有找到 0x880F 控制特征，无法调阻力")
        }
        // The GATT queue is FIFO, so commands sent from now on run after the setup reads.
        host.update { it.copy(controlReady = true) }
    }

    private fun onSportType(type: Int, subType: Int) {
        val desc = when (type) {
            ResistanceTables.TYPE_ELLIPTICAL -> "椭圆机 " + ResistanceTables.ellipticalModel(subType)
            else -> ResistanceTables.typeName(type) + " 子类型 $subType"
        }
        log("8802 设备类型=$type 子类型=$subType ($desc)")
        host.update { it.copy(machineDescription = desc) }
        reportMachineType(type, subType)
        if (!rangeFromDevice) {
            val caps = ResistanceTables.v2(type, subType)
            applyRange(1, caps.max, "官方型号表", caps.mode)
        }
    }

    private fun supportsStartStop() = (uploadMode shr 1) and 1 == 1

    override fun setResistance(level: Int) {
        val ch = link.characteristic(BleUuids.V2_SERVICE, BleUuids.V2_CONTROL)
            ?: return log("没有 0x880F，无法设置阻力")
        val frame = bytesOf(0x02, level.toString().length, level)
        withUnlock { link.write(ch, frame) }
    }

    override fun sendWorkout(cmd: WorkoutCommand) {
        if (!supportsStartStop()) {
            log("8801 未声明需要开始/停止指令 (模式=$uploadMode)，与官方 App 一致不发送")
            return
        }
        val ch = link.characteristic(BleUuids.V2_SERVICE, BleUuids.V2_CONTROL) ?: return
        val code = when (cmd) {
            WorkoutCommand.START -> 0x04
            WorkoutCommand.PAUSE -> 0x05
            WorkoutCommand.STOP -> 0x03
        }
        withUnlock { link.write(ch, bytesOf(code)) }
    }

    private fun withUnlock(block: () -> Unit) {
        if (unlocked) {
            block()
            return
        }
        afterUnlock.addLast(block)
        if (unlocking) return
        val ch = link.characteristic(BleUuids.V2_SERVICE, BleUuids.V2_UNLOCK)
        if (ch == null) {
            log("没有 0x88FF 解锁特征，直接发送")
            unlocked = true
            drainUnlockQueue()
            return
        }
        unlocking = true
        log("发送写权限解锁 11 82 07 -> 0x88FF")
        link.write(ch, bytesOf(0x11, 0x82, 0x07)) { ok ->
            unlocking = false
            unlocked = ok
            if (!ok) log("解锁写入失败，仍尝试发送命令（下次会重新解锁）")
            drainUnlockQueue()
        }
    }

    private fun drainUnlockQueue() {
        while (afterUnlock.isNotEmpty()) afterUnlock.removeFirst().invoke()
    }

    override fun onNotify(ch: UUID, value: ByteArray) {
        if (handleStandardNotify(ch, value) || value.isEmpty()) return
        when (ch) {
            BleUuids.V2_RESISTANCE_GEAR -> reportResistance(value[0].u())

            BleUuids.V2_INTERVAL -> if ((uploadMode == 1 || uploadMode == 3) && value.size >= 2) {
                val interval = value.u16be(0)
                reportRpm(if (interval == 0) 0.0 else 60_000.0 / interval / magnets)
            }

            // [0] speed/10, [1] incline, [2] cadence, [3..4] duration, [5..6] distance, [7..8] kcal/10 (big endian).
            // Distance and kcal are the machine's running totals; WorkoutTracker uses their increments.
            BleUuids.V2_ALL_DATA -> if ((uploadMode == 2 || uploadMode == 3) && value.size in setOf(9, 11, 13)) {
                reportRpm(value[2].u().toDouble())
                host.update {
                    it.copy(
                        deviceSpeedKmh = value[0].u() / 10.0,
                        deviceDistanceM = value.u16be(5).toDouble(),
                        deviceCalories = value.u16be(7) / 10.0,
                    )
                }
            }

            BleUuids.V2_MACHINE_STATE -> {
                val text = when (value[0].u()) {
                    0 -> "停止"
                    1 -> "运行"
                    2 -> "暂停"
                    else -> "状态 ${value[0].u()}"
                }
                host.update { it.copy(machineRunState = text) }
            }

            BleUuids.V2_ERROR -> log("88E1 设备错误: ${value.toHex()}")
        }
    }
}
