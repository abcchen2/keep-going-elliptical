package com.keepgoing.elliptical.protocol

import androidx.annotation.StringRes
import com.keepgoing.elliptical.R
import com.keepgoing.elliptical.model.UiText

/**
 * Resistance capabilities hard-coded in the official app, keyed by the
 * device type / sub type the machine reports (8802 for 2.0 boxes, 0xAB frames for 1.0 boxes).
 *
 * Mode values are the app's ResistanceMode constants:
 * 0 = NO_READ_WRITE, 1 = ONLY_WRITE, 2 = ONLY_READ, 3 = READ_WRITE.
 */
object ResistanceTables {
    const val TYPE_ROWING = 9
    const val TYPE_BIKE = 10
    const val TYPE_ELLIPTICAL = 11
    const val TYPE_TREADMILL = 12

    data class Caps(val mode: Int, val max: Int)

    /** From V2Handler (official app 4.5.17), characteristic 0x8802. */
    fun v2(type: Int, subType: Int): Caps = when (type) {
        TYPE_ELLIPTICAL -> when (subType) {
            1 -> Caps(0, 24)
            17 -> Caps(3, 24)
            18 -> Caps(2, 8)
            19, 20, 21 -> Caps(3, 24)
            22 -> Caps(3, 40)
            else -> Caps(0, 24)
        }
        TYPE_BIKE -> bike(subType)
        TYPE_ROWING -> Caps(0, 24)
        else -> Caps(2, 24)
    }

    /** From V1Handler / BluetoothLeService (official app), 0xAB status frame byte 3/4. */
    fun v1(type: Int, subType: Int): Caps = when (type) {
        TYPE_ELLIPTICAL -> when (subType) {
            1 -> Caps(0, 24)
            17, 19 -> Caps(3, 24)
            18 -> Caps(2, 8)
            else -> Caps(0, 24)
        }
        TYPE_BIKE -> bike(subType)
        else -> Caps(0, 24)
    }

    private fun bike(subType: Int): Caps = when (subType) {
        1 -> Caps(0, 24)
        18, 23 -> Caps(3, 24)
        19, 20, 22 -> Caps(2, 32)
        21 -> Caps(3, 32)
        else -> Caps(0, 24)
    }

    /** DeviceToolsKt.getReadWriteMode(): maps the raw 0x8806 byte 0 to a mode. */
    fun readWriteMode(raw: Int): Int = when (raw) {
        0 -> 0
        4 -> 1
        5 -> 3
        else -> 2
    }

    fun typeName(type: Int): UiText = when (type) {
        TYPE_ROWING -> UiText(R.string.type_rowing)
        TYPE_BIKE -> UiText(R.string.type_bike)
        TYPE_ELLIPTICAL -> UiText(R.string.type_elliptical)
        TYPE_TREADMILL -> UiText(R.string.type_treadmill)
        else -> UiText(R.string.type_unknown, type)
    }

    /** Elliptical model names from EnumDeviceKt.getDeviceSubtypeName(). */
    fun ellipticalModel(subType: Int): String? = when (subType) {
        18 -> "MEH3205"
        19 -> "MEH3204"
        20 -> "MEH3201"
        else -> null
    }

    /** "Elliptical MEH3204" / "Spin bike, sub type 21", localised. */
    fun describe(type: Int, subType: Int): UiText {
        val name = typeName(type)
        val model = if (type == TYPE_ELLIPTICAL) ellipticalModel(subType) else null
        return if (model != null) {
            UiText(R.string.machine_desc_model, name, model)
        } else {
            UiText(R.string.machine_desc_subtype, name, subType)
        }
    }

    @StringRes
    fun modeText(mode: Int): Int = when (mode) {
        0 -> R.string.mode_0
        1 -> R.string.mode_1
        2 -> R.string.mode_2
        3 -> R.string.mode_3
        else -> R.string.mode_unknown
    }
}
