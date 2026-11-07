package com.keepgoing.elliptical.protocol

import android.os.SystemClock
import com.keepgoing.elliptical.ble.BleUuids
import com.keepgoing.elliptical.ble.GattLink
import com.keepgoing.elliptical.ble.u
import com.keepgoing.elliptical.ble.u16le
import com.keepgoing.elliptical.model.AppSettings
import com.keepgoing.elliptical.model.MachineState
import com.keepgoing.elliptical.model.ProtocolKind
import com.keepgoing.elliptical.model.WorkoutCommand
import java.util.UUID

/** What a protocol implementation may use. Everything runs on the main thread. */
interface ProtocolHost {
    val link: GattLink
    val deviceName: String
    val settings: AppSettings
    val state: MachineState
    fun update(transform: (MachineState) -> MachineState)
    fun log(line: String)
    fun postDelayed(delayMs: Long, block: () -> Unit): Runnable
    fun cancel(r: Runnable)
}

abstract class MachineProtocol(protected val host: ProtocolHost) {
    abstract val kind: ProtocolKind

    /** Called once after service discovery + MTU. Subscribe, read capabilities, etc. */
    abstract fun start()

    abstract fun onNotify(ch: UUID, value: ByteArray)

    /** Sends a resistance level (already clamped to the machine range by the controller). */
    abstract fun setResistance(level: Int)

    /** Start/pause/stop for machines that need it. 1.0 and HuanTong boards have no such command. */
    open fun sendWorkout(cmd: WorkoutCommand) {}

    /** Stop timers. The link is closed by the controller. */
    open fun release() {}

    // ------------------------------------------------------------- shared helpers

    protected val link: GattLink get() = host.link

    protected fun log(line: String) = host.log(line)

    /** Resistance range from the official tables, unless the user set an override. */
    protected fun applyRange(min: Int, max: Int, source: String, mode: Int? = null) {
        val override = host.settings.maxResistanceOverride
        val realMax = if (override > 0) override else max
        val realSource = if (override > 0) "手动设置" else source
        val newMin = min.coerceAtLeast(1)
        val newMax = realMax.coerceAtLeast(newMin)
        host.update {
            it.copy(
                resistanceMin = newMin,
                resistanceMax = newMax,
                resistanceRangeSource = realSource,
                resistanceMode = mode ?: it.resistanceMode,
                targetResistance = it.targetResistance.coerceIn(newMin, newMax),
            )
        }
        log("阻力范围 ${min.coerceAtLeast(1)}..$realMax ($realSource)" + (mode?.let { ", 模式=${ResistanceTables.modeText(it)}" } ?: ""))
    }

    protected fun reportResistance(level: Int) {
        if (level <= 0) return
        host.update { it.copy(reportedResistance = level) }
    }

    protected fun reportRpm(rpm: Double) {
        // Same sanity window as BaseHandler.upRpm() in the official app.
        if (rpm >= 0 && rpm <= 200) {
            host.update { it.copy(rpm = rpm, rpmUpdatedAt = SystemClock.elapsedRealtime()) }
        }
    }

    protected fun reportHeartRate(hr: Int) {
        if (hr in 30..250) host.update { it.copy(heartRate = hr, heartRateUpdatedAt = SystemClock.elapsedRealtime()) }
    }

    protected fun reportMachineType(type: Int, subType: Int?) {
        host.update { it.copy(machineType = type, subType = subType) }
    }

    /** Device information, battery and heart rate, handled the same way for every protocol. */
    protected fun setupStandardServices() {
        for (id in listOf(BleUuids.CHAR_MODEL_NUMBER, BleUuids.CHAR_FIRMWARE)) {
            val ch = link.characteristic(BleUuids.SERVICE_DEVICE_INFO, id) ?: continue
            link.read(ch) { v ->
                val text = v?.toString(Charsets.UTF_8)?.trim()?.trimEnd('\u0000') ?: return@read
                log("${if (id == BleUuids.CHAR_MODEL_NUMBER) "型号" else "固件"}: $text")
                host.update {
                    if (id == BleUuids.CHAR_MODEL_NUMBER) it.copy(modelNumber = text) else it.copy(firmware = text)
                }
            }
        }
        link.characteristic(BleUuids.SERVICE_BATTERY, BleUuids.CHAR_BATTERY_LEVEL)?.let { ch ->
            link.read(ch) { v -> if (v != null && v.isNotEmpty()) host.update { it.copy(battery = v[0].u()) } }
        }
        link.characteristic(BleUuids.SERVICE_HEART_RATE, BleUuids.CHAR_HEART_RATE)?.let { link.enableNotify(it) }
    }

    /** @return true if [ch] was a standard characteristic and has been handled. */
    protected fun handleStandardNotify(ch: UUID, value: ByteArray): Boolean {
        when (ch) {
            BleUuids.CHAR_HEART_RATE -> {
                if (value.size >= 2) {
                    val hr = if (value[0].toInt() and 1 == 0) value[1].u() else if (value.size >= 3) value.u16le(1) else 0
                    reportHeartRate(hr)
                }
                return true
            }
            BleUuids.CHAR_BATTERY_LEVEL -> {
                if (value.isNotEmpty()) host.update { it.copy(battery = value[0].u()) }
                return true
            }
        }
        return false
    }
}
