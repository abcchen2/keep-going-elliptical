package com.keepgoing.elliptical.model

import android.content.Context

/** Small SharedPreferences wrapper. All values are read on demand so changes apply immediately. */
class AppSettings(context: Context) {
    private val sp = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    var protocolChoice: ProtocolChoice
        get() = enumOr(sp.getString(KEY_PROTOCOL, null), ProtocolChoice.AUTO)
        set(v) = sp.edit().putString(KEY_PROTOCOL, v.name).apply()

    var ftmsEncoding: FtmsResistanceEncoding
        get() = enumOr(sp.getString(KEY_FTMS_ENC, null), FtmsResistanceEncoding.UINT8_X10)
        set(v) = sp.edit().putString(KEY_FTMS_ENC, v.name).apply()

    /** User weight, sent to HuanTong boards on connect (the official app sends it too). */
    var userWeightKg: Int
        get() = sp.getInt(KEY_WEIGHT, 60)
        set(v) = sp.edit().putInt(KEY_WEIGHT, v.coerceIn(20, 200)).apply()

    /** 0 = use what the machine/official tables say. */
    var maxResistanceOverride: Int
        get() = sp.getInt(KEY_MAX_RES, 0)
        set(v) = sp.edit().putInt(KEY_MAX_RES, v.coerceIn(0, 100)).apply()

    var autoReconnect: Boolean
        get() = sp.getBoolean(KEY_RECONNECT, true)
        set(v) = sp.edit().putBoolean(KEY_RECONNECT, v).apply()

    var lastDeviceAddress: String?
        get() = sp.getString(KEY_LAST_ADDR, null)
        set(v) = sp.edit().putString(KEY_LAST_ADDR, v).apply()

    var lastDeviceName: String?
        get() = sp.getString(KEY_LAST_NAME, null)
        set(v) = sp.edit().putString(KEY_LAST_NAME, v).apply()

    var lastResistance: Int
        get() = sp.getInt(KEY_LAST_RES, 1)
        set(v) = sp.edit().putInt(KEY_LAST_RES, v).apply()

    var calorieAlgorithm: CalorieAlgorithm
        get() = enumOr(sp.getString(KEY_CAL_ALGO, null), CalorieAlgorithm.OFFICIAL_NEW)
        set(v) = sp.edit().putString(KEY_CAL_ALGO, v.name).apply()

    /** Scales formula-based calories (not machine-reported ones). 100 = unchanged. */
    var calorieFactorPercent: Int
        get() = sp.getInt(KEY_CAL_FACTOR, 100)
        set(v) = sp.edit().putInt(KEY_CAL_FACTOR, v.coerceIn(30, 200)).apply()

    /** Seconds without pedalling before the workout pauses itself. 0 = never. Official default 60. */
    var autoPauseSec: Int
        get() = sp.getInt(KEY_AUTO_PAUSE, 60)
        set(v) = sp.edit().putInt(KEY_AUTO_PAUSE, v.coerceIn(0, 600)).apply()

    var workoutHistoryJson: String?
        get() = sp.getString(KEY_HISTORY, null)
        set(v) = sp.edit().putString(KEY_HISTORY, v).apply()

    private inline fun <reified T : Enum<T>> enumOr(name: String?, fallback: T): T =
        name?.let { n -> enumValues<T>().firstOrNull { it.name == n } } ?: fallback

    private companion object {
        const val KEY_PROTOCOL = "protocol_choice"
        const val KEY_FTMS_ENC = "ftms_encoding"
        const val KEY_WEIGHT = "user_weight"
        const val KEY_MAX_RES = "max_resistance_override"
        const val KEY_RECONNECT = "auto_reconnect"
        const val KEY_LAST_ADDR = "last_device_address"
        const val KEY_LAST_NAME = "last_device_name"
        const val KEY_LAST_RES = "last_resistance"
        const val KEY_CAL_ALGO = "calorie_algorithm"
        const val KEY_CAL_FACTOR = "calorie_factor"
        const val KEY_AUTO_PAUSE = "auto_pause_sec"
        const val KEY_HISTORY = "workout_history"
    }
}
