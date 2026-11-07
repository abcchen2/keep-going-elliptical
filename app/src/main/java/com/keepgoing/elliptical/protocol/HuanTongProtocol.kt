package com.keepgoing.elliptical.protocol

import com.keepgoing.elliptical.ble.BleUuids
import com.keepgoing.elliptical.ble.bytesOf
import com.keepgoing.elliptical.ble.u
import com.keepgoing.elliptical.model.ProtocolKind
import java.util.UUID

/**
 * "环通" (HuanTong) control board: service FFF0, notify FFF1, write FFF2,
 * advertised name "MOBI-..." (but not "MOBI-C"); "MOBI-E..." is the elliptical.
 *
 * This board has NO "set resistance once" command. The official app
 * (HuanTongHandler / BluetoothLeService.huanTongTimedTask) does:
 *   1. after FFF1 notifications are enabled, write the user weight:
 *        40 00 LB KG CS      LB = kg × 2.2046226, CS = (0x40 + LB + KG) & 0xFF
 *   2. then, EVERY SECOND for the whole session, write
 *        20 C1 LV 00 CS      LV = resistance level, CS = (0x20 + 0xC1 + LV) & 0xFF
 * The board treats this frame as a keep-alive that carries the level. A single write
 * changes the magnet briefly and then the board falls back, which is exactly the
 * "resistance does not stay / behaves oddly" symptom of a one-shot implementation.
 *
 * RPM arrives in 12-byte FFF1 frames as BCD: bytes 2..3 printed as "%X%02X" and parsed
 * as a decimal number (0x01 0x23 -> 123 rpm).
 */
class HuanTongProtocol(host: ProtocolHost) : MachineProtocol(host) {
    override val kind = ProtocolKind.HUANTONG

    private var level = host.state.targetResistance.coerceAtLeast(1)
    private var heartbeat: Runnable? = null
    private var sessionStarted = false
    private var released = false

    override fun start() {
        val isElliptical = host.deviceName.startsWith("MOBI-E")
        host.update {
            it.copy(machineDescription = if (isElliptical) "椭圆机 (环通主板)" else "单车 (环通主板)")
        }
        reportMachineType(if (isElliptical) ResistanceTables.TYPE_ELLIPTICAL else ResistanceTables.TYPE_BIKE, null)
        // The board never reports the level back; the official app marks it write-only
        // and uses its default range of 1..24.
        applyRange(1, 24, "官方默认 (环通无阻力回读)", mode = 1)

        val notify = link.characteristic(BleUuids.HT_SERVICE, BleUuids.HT_NOTIFY)
        if (notify == null) {
            log("没有 FFF1 通知特征，直接开始心跳")
            startSession()
        } else {
            link.enableNotify(notify) { ok ->
                if (!ok) log("FFF1 订阅失败，仍然开始心跳")
                startSession()
            }
        }
    }

    private fun startSession() {
        if (sessionStarted || released) return
        sessionStarted = true
        val ch = link.characteristic(BleUuids.HT_SERVICE, BleUuids.HT_WRITE)
            ?: link.findCharacteristic(BleUuids.HT_WRITE)
        if (ch == null) {
            log("没有 FFF2 写特征，无法控制阻力")
            return
        }
        val kg = host.settings.userWeightKg
        val lb = (kg * 2.2046226).toInt() and 0xFF
        val weightFrame = bytesOf(0x40, 0x00, lb, kg and 0xFF, (0x40 + lb + kg) and 0xFF)
        log("发送体重 ${kg}kg")
        link.write(ch, weightFrame) {
            host.update { s -> s.copy(controlReady = true) }
            scheduleHeartbeat(0)
        }
    }

    private fun scheduleHeartbeat(delayMs: Long) {
        if (released) return
        heartbeat = host.postDelayed(delayMs) {
            val ch = link.characteristic(BleUuids.HT_SERVICE, BleUuids.HT_WRITE)
                ?: link.findCharacteristic(BleUuids.HT_WRITE)
            // Do not pile up frames if the radio is slower than 1 Hz.
            if (ch != null && link.pendingWrites(ch.uuid) == 0) link.write(ch, frame(level))
            scheduleHeartbeat(HEARTBEAT_MS)
        }
    }

    private fun frame(lv: Int) = bytesOf(0x20, 0xC1, lv, 0x00, (0x20 + 0xC1 + lv) and 0xFF)

    override fun setResistance(level: Int) {
        this.level = level
        // Send right away so the change is felt immediately; the heartbeat keeps it applied.
        val ch = link.characteristic(BleUuids.HT_SERVICE, BleUuids.HT_WRITE) ?: return
        if (sessionStarted) link.write(ch, frame(level))
        // No read-back on this board: show what is being held.
        host.update { it.copy(reportedResistance = level) }
    }

    override fun onNotify(ch: UUID, value: ByteArray) {
        if (handleStandardNotify(ch, value)) return
        if (ch == BleUuids.HT_NOTIFY && value.size == 12 && value[1].u() != 0) {
            val text = "%X%02X".format(value[2].u(), value[3].u())
            text.toDoubleOrNull()?.let { reportRpm(it) }
        }
    }

    override fun release() {
        released = true
        heartbeat?.let { host.cancel(it) }
        heartbeat = null
    }

    private companion object {
        const val HEARTBEAT_MS = 1000L
    }
}
