package com.keepgoing.elliptical.protocol

import android.bluetooth.BluetoothGattCharacteristic
import com.keepgoing.elliptical.R
import com.keepgoing.elliptical.ble.BleUuids
import com.keepgoing.elliptical.ble.bytesOf
import com.keepgoing.elliptical.ble.toHex
import com.keepgoing.elliptical.ble.u
import com.keepgoing.elliptical.ble.u32be
import com.keepgoing.elliptical.model.ProtocolKind
import com.keepgoing.elliptical.model.UiText
import java.util.UUID

/**
 * Mobi "1.0" box (service FFE0 / FFF0 / FFC0, frames starting with 0xAB).
 *
 * The machine streams status frames:
 *   AB 04 st TT SS bb xx i0 i1 i2 i3 .. .. RR ..
 *   TT = device type (11 = elliptical), SS = sub type, bb = battery,
 *   i0..i3 = revolution interval in ms (big endian), RR (byte 13) = current resistance.
 *
 * Resistance write (writeEllipticalMachineBleVer0x01 in the official app), to FFE0/FFE3:
 *   AB 03 00 TT SS LV xx
 * TT, SS and xx are COPIED from the most recent frame the machine sent. A fixed or
 * zero-filled frame is ignored by the box, and nothing can be sent before the first
 * frame arrives (the box only starts streaming once it is powered/pedalled).
 */
class V1Protocol(host: ProtocolHost) : MachineProtocol(host) {
    override val kind = ProtocolKind.V1

    private var lastFrame: ByteArray? = null
    private var statusFrame: ByteArray? = null
    private var pendingLevel: Int? = null
    private var typeKnown = false
    private var type = -1
    private var subType = -1
    private var writeChar: BluetoothGattCharacteristic? = null
    private val timers = mutableListOf<Runnable>()

    override fun start() {
        for (svc in listOf(BleUuids.V1_SERVICE_FFE0, BleUuids.V1_SERVICE_FFF0, BleUuids.V1_SERVICE_FFC0)) {
            for (id in BleUuids.V1_NOTIFY_CHARS) {
                val ch = link.characteristic(svc, id) ?: continue
                if (ch.properties and BluetoothGattCharacteristic.PROPERTY_NOTIFY == 0) continue
                // The official app delays the FFE4 subscription by 2 s on "MB-HW" boxes.
                if (id == BleUuids.V1_CHAR_FFE4 && host.deviceName.startsWith("MB-HW")) {
                    timers += host.postDelayed(2000) { link.enableNotify(ch) }
                } else {
                    link.enableNotify(ch)
                }
            }
        }
        setupStandardServices()
        writeChar = link.characteristic(BleUuids.V1_SERVICE_FFE0, BleUuids.V1_WRITE_ELLIPTICAL)
            ?: link.findCharacteristic(BleUuids.V1_WRITE_ELLIPTICAL)
        if (writeChar == null) logRes(R.string.warn_no_ffe3)
        host.update {
            it.copy(controlReady = true, machineDescription = it.machineDescription ?: UiText(R.string.v1_waiting_frame))
        }
    }

    override fun onNotify(ch: UUID, value: ByteArray) {
        if (handleStandardNotify(ch, value) || value.isEmpty()) return
        lastFrame = value
        if (value[0].u() != 0xAB || value.size < 11) return
        if (value[1].u() == 4) statusFrame = value

        if (value[2].u() in 0..3) host.update { it.copy(battery = value[5].u()) }

        if (!typeKnown && value[1].u() == 4) {
            typeKnown = true
            type = value[3].u()
            subType = value[4].u()
            val desc = ResistanceTables.describe(type, subType)
            logRes(R.string.log_v1_frame, type, subType, desc.resolve(host.context), value.toHex())
            host.update { it.copy(machineDescription = desc) }
            reportMachineType(type, subType)
            val caps = ResistanceTables.v1(type, subType)
            applyRange(1, caps.max, R.string.src_official_table, caps.mode)
        }

        if (value[1].u() == 4 && (type == ResistanceTables.TYPE_BIKE || type == ResistanceTables.TYPE_ELLIPTICAL) && value.size > 13) {
            reportResistance(value[13].u())
        }

        if (type == ResistanceTables.TYPE_BIKE || type == ResistanceTables.TYPE_ELLIPTICAL) {
            val interval = value.u32be(7)
            if (interval > 0) {
                var rpm = 60_000.0 / interval
                if (type == ResistanceTables.TYPE_ELLIPTICAL && subType == 17) rpm /= 2
                reportRpm(rpm)
            }
        }

        pendingLevel?.let {
            pendingLevel = null
            logRes(R.string.log_v1_sending_queued, it)
            setResistance(it)
        }
    }

    override fun setResistance(level: Int) {
        val ch = writeChar ?: return logRes(R.string.err_no_ffe3)
        val f = statusFrame ?: lastFrame?.takeIf { it.size > 6 }
        if (f == null) {
            pendingLevel = level
            logRes(R.string.log_v1_queued, level)
            return
        }
        link.write(ch, bytesOf(0xAB, 0x03, 0x00, f[3].u(), f[4].u(), level, f[6].u()))
    }

    override fun release() {
        timers.forEach { host.cancel(it) }
        timers.clear()
    }
}
