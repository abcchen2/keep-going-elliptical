import Foundation

/// Resistance capabilities hard-coded in the official app, keyed by the device type / sub type
/// the machine reports (8802 for 2.0 boxes, 0xAB frames for 1.0 boxes). Port of ResistanceTables.kt.
///
/// Mode values are the app's ResistanceMode constants:
/// 0 = NO_READ_WRITE, 1 = ONLY_WRITE, 2 = ONLY_READ, 3 = READ_WRITE.
enum ResistanceTables {
    static let typeRowing = 9
    static let typeBike = 10
    static let typeElliptical = 11
    static let typeTreadmill = 12

    struct Caps {
        let mode: Int
        let max: Int
    }

    /// From V2Handler (official app 4.5.17), characteristic 0x8802.
    static func v2(_ type: Int, _ subType: Int) -> Caps {
        switch type {
        case typeElliptical:
            switch subType {
            case 1: return Caps(mode: 0, max: 24)
            case 17: return Caps(mode: 3, max: 24)
            case 18: return Caps(mode: 2, max: 8)
            case 19, 20, 21: return Caps(mode: 3, max: 24)
            case 22: return Caps(mode: 3, max: 40)
            default: return Caps(mode: 0, max: 24)
            }
        case typeBike: return bike(subType)
        case typeRowing: return Caps(mode: 0, max: 24)
        default: return Caps(mode: 2, max: 24)
        }
    }

    /// From V1Handler / BluetoothLeService (official app), 0xAB status frame byte 3/4.
    static func v1(_ type: Int, _ subType: Int) -> Caps {
        switch type {
        case typeElliptical:
            switch subType {
            case 1: return Caps(mode: 0, max: 24)
            case 17, 19: return Caps(mode: 3, max: 24)
            case 18: return Caps(mode: 2, max: 8)
            default: return Caps(mode: 0, max: 24)
            }
        case typeBike: return bike(subType)
        default: return Caps(mode: 0, max: 24)
        }
    }

    private static func bike(_ subType: Int) -> Caps {
        switch subType {
        case 1: return Caps(mode: 0, max: 24)
        case 18, 23: return Caps(mode: 3, max: 24)
        case 19, 20, 22: return Caps(mode: 2, max: 32)
        case 21: return Caps(mode: 3, max: 32)
        default: return Caps(mode: 0, max: 24)
        }
    }

    /// DeviceToolsKt.getReadWriteMode(): maps the raw 0x8806 byte 0 to a mode.
    static func readWriteMode(_ raw: Int) -> Int {
        switch raw {
        case 0: return 0
        case 4: return 1
        case 5: return 3
        default: return 2
        }
    }

    static func typeName(_ type: Int) -> UiText {
        switch type {
        case typeRowing: return UiText("type_rowing")
        case typeBike: return UiText("type_bike")
        case typeElliptical: return UiText("type_elliptical")
        case typeTreadmill: return UiText("type_treadmill")
        default: return UiText("type_unknown", type)
        }
    }

    /// Elliptical model names from EnumDeviceKt.getDeviceSubtypeName().
    static func ellipticalModel(_ subType: Int) -> String? {
        switch subType {
        case 18: return "MEH3205"
        case 19: return "MEH3204"
        case 20: return "MEH3201"
        default: return nil
        }
    }

    /// "Elliptical MEH3204" / "Spin bike, sub type 21", localised.
    static func describe(_ type: Int, _ subType: Int) -> UiText {
        let name = typeName(type)
        if type == typeElliptical, let model = ellipticalModel(subType) {
            return UiText("machine_desc_model", name, model)
        }
        return UiText("machine_desc_subtype", name, subType)
    }

    static func modeText(_ mode: Int) -> String {
        switch mode {
        case 0: return "mode_0"
        case 1: return "mode_1"
        case 2: return "mode_2"
        case 3: return "mode_3"
        default: return "mode_unknown"
        }
    }
}
