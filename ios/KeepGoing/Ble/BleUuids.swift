import CoreBluetooth

/// GATT UUIDs used by Mobi (莫比) machines. Port of app/.../ble/BleUuids.kt; names follow the
/// official app (BLEConstant) so docs/PROTOCOL.md can be cross-checked.
enum BleUuids {
    // --- Standard services ---
    static let serviceDeviceInfo = CBUUID(string: "180A")
    static let serviceBattery = CBUUID(string: "180F")
    static let serviceHeartRate = CBUUID(string: "180D")
    static let charModelNumber = CBUUID(string: "2A24")
    static let charFirmware = CBUUID(string: "2A26")
    static let charBatteryLevel = CBUUID(string: "2A19")
    static let charHeartRate = CBUUID(string: "2A37")

    // --- Mobi "2.0" protocol (service 0x8800) ---
    static let v2Service = CBUUID(string: "8800")
    static let v2UploadType = CBUUID(string: "8801")       // read: [0] data upload mode
    static let v2SportType = CBUUID(string: "8802")        // read: [0] device type, [1] sub type
    static let v2Magnet = CBUUID(string: "8805")           // read: [0] number of magnets (for RPM)
    static let v2ResistanceRange = CBUUID(string: "8806")  // read: [0] r/w mode code, [1] min, [2] max
    static let v2MachineState = CBUUID(string: "880E")     // notify: 0 stop / 1 run / 2 pause
    static let v2Control = CBUUID(string: "880F")          // write: control instructions
    static let v2Interval = CBUUID(string: "8811")         // notify: u16be revolution interval (ms)
    static let v2ResistanceGear = CBUUID(string: "8812")   // read/notify: [0] current resistance
    static let v2AllData = CBUUID(string: "8813")          // notify: speed / cadence / time / distance / kcal
    static let v2Error = CBUUID(string: "88E1")
    static let v2Unlock = CBUUID(string: "88FF")           // write 11 82 07 before any 880f write

    // --- Mobi "1.0" protocol (old box, 0xAB frames) ---
    static let v1ServiceFFE0 = CBUUID(string: "FFE0")
    static let v1ServiceFFF0 = CBUUID(string: "FFF0")
    static let v1ServiceFFC0 = CBUUID(string: "FFC0")
    static let v1NotifyChars = [CBUUID(string: "FFE4"), CBUUID(string: "FFE1"), CBUUID(string: "FFEA"),
                                CBUUID(string: "FFF4"), CBUUID(string: "FFEB")]
    static let v1CharFFE4 = CBUUID(string: "FFE4")
    static let v1WriteElliptical = CBUUID(string: "FFE3")

    // --- "环通" (HuanTong) board, name "MOBI-E..." for ellipticals ---
    static let htService = CBUUID(string: "FFF0")
    static let htNotify = CBUUID(string: "FFF1")
    static let htWrite = CBUUID(string: "FFF2")

    // --- FTMS (Fitness Machine Service) ---
    static let ftmsService = CBUUID(string: "1826")
    static let ftmsFeature = CBUUID(string: "2ACC")
    static let ftmsCrossTrainerData = CBUUID(string: "2ACE")
    static let ftmsIndoorBikeData = CBUUID(string: "2AD2")
    static let ftmsTrainingStatus = CBUUID(string: "2AD3")
    static let ftmsResistanceRange = CBUUID(string: "2AD6")
    static let ftmsControlPoint = CBUUID(string: "2AD9")
    static let ftmsStatus = CBUUID(string: "2ADA")

    /// Services the official app scans for.
    static let scanServices = [v2Service, ftmsService, htService, v1ServiceFFE0, v1ServiceFFC0]
}
