package com.keepgoing.elliptical.ble

/**
 * GATT UUIDs used by Mobi (莫比) machines. Names follow the official app
 * (com.anytum.mobi.device.bluetoothLe.BLEConstant) so the protocol notes in docs/PROTOCOL.md
 * can be cross-checked against the decompiled sources.
 */
object BleUuids {
    // --- Standard services ---
    val SERVICE_DEVICE_INFO = uuid16("180a")
    val SERVICE_BATTERY = uuid16("180f")
    val SERVICE_HEART_RATE = uuid16("180d")
    val CHAR_MODEL_NUMBER = uuid16("2a24")
    val CHAR_SERIAL_NUMBER = uuid16("2a25")
    val CHAR_FIRMWARE = uuid16("2a26")
    val CHAR_BATTERY_LEVEL = uuid16("2a19")
    val CHAR_HEART_RATE = uuid16("2a37")
    val CCCD = uuid16("2902")

    // --- Mobi "2.0" protocol (service 0x8800) ---
    val V2_SERVICE = uuid16("8800")
    val V2_UPLOAD_TYPE = uuid16("8801")        // read: [0] data upload mode ("intervalValue")
    val V2_SPORT_TYPE = uuid16("8802")         // read: [0] device type, [1] sub type
    val V2_MACHINE_TYPE = uuid16("8803")
    val V2_DEGREE = uuid16("8804")
    val V2_MAGNET = uuid16("8805")             // read: [0] number of magnets (for RPM)
    val V2_RESISTANCE_RANGE = uuid16("8806")   // read: [0] r/w mode code, [1] min, [2] max
    val V2_MACHINE_STATE = uuid16("880e")      // notify: 0 stop / 1 run / 2 pause
    val V2_CONTROL = uuid16("880f")            // write: control instructions
    val V2_INTERVAL = uuid16("8811")           // notify: u16be revolution interval (ms)
    val V2_RESISTANCE_GEAR = uuid16("8812")    // read/notify: [0] current resistance
    val V2_ALL_DATA = uuid16("8813")           // notify: speed / cadence / time / distance / kcal
    val V2_ERROR = uuid16("88e1")
    val V2_UNLOCK = uuid16("88ff")             // write 11 82 07 before any 880f write

    // --- Mobi "1.0" protocol (old box, 0xAB frames) ---
    val V1_SERVICE_FFE0 = uuid16("ffe0")
    val V1_SERVICE_FFF0 = uuid16("fff0")
    val V1_SERVICE_FFC0 = uuid16("ffc0")
    val V1_NOTIFY_CHARS = listOf(uuid16("ffe4"), uuid16("ffe1"), uuid16("ffea"), uuid16("fff4"), uuid16("ffeb"))
    val V1_CHAR_FFE4 = uuid16("ffe4")
    val V1_WRITE_ELLIPTICAL = uuid16("ffe3")

    // --- "环通" (HuanTong) board, name "MOBI-E..." for ellipticals ---
    val HT_SERVICE = uuid16("fff0")
    val HT_NOTIFY = uuid16("fff1")
    val HT_WRITE = uuid16("fff2")

    // --- FTMS (Fitness Machine Service) ---
    val FTMS_SERVICE = uuid16("1826")
    val FTMS_FEATURE = uuid16("2acc")
    val FTMS_TREADMILL_DATA = uuid16("2acd")
    val FTMS_CROSS_TRAINER_DATA = uuid16("2ace")
    val FTMS_ROWER_DATA = uuid16("2ad1")
    val FTMS_INDOOR_BIKE_DATA = uuid16("2ad2")
    val FTMS_TRAINING_STATUS = uuid16("2ad3")
    val FTMS_RESISTANCE_RANGE = uuid16("2ad6")
    val FTMS_CONTROL_POINT = uuid16("2ad9")
    val FTMS_STATUS = uuid16("2ada")

    /** Services the official app scans for. */
    val SCAN_SERVICES = listOf(V2_SERVICE, FTMS_SERVICE, HT_SERVICE, V1_SERVICE_FFE0, V1_SERVICE_FFC0)
}
