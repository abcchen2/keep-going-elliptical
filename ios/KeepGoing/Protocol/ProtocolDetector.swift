import Foundation

/// Same priority as BaseHandler.createHandler() in the official app 4.5.17 (port of ProtocolDetector.kt):
///   0x8800 -> 2.0 box, FFE0 -> 1.0 box, FFF0 + name "MOBI-" (not "MOBI-C") -> HuanTong, 0x1826 -> FTMS.
/// The older 3.5.x app additionally treated any remaining FFF0/FFC0 device as a 1.0 box.
enum ProtocolDetector {
    static func detect(_ link: GattLink, _ deviceName: String) -> ProtocolKind? {
        if link.hasService(BleUuids.v2Service) { return .v2 }
        if link.hasService(BleUuids.v1ServiceFFE0) { return .v1 }
        if link.hasService(BleUuids.htService) && isHuanTongName(deviceName) { return .huanTong }
        if link.hasService(BleUuids.ftmsService) { return .ftms }
        if link.hasService(BleUuids.v1ServiceFFF0) || link.hasService(BleUuids.v1ServiceFFC0) { return .v1 }
        return nil
    }

    static func isHuanTongName(_ name: String) -> Bool {
        name.hasPrefix("MOBI-") && !name.hasPrefix("MOBI-C")
    }

    /// Demo mode is started with EllipticalController.startDemo(), not over BLE.
    static func create(_ kind: ProtocolKind, _ host: ProtocolHost) -> MachineProtocol? {
        switch kind {
        case .v2: return V2Protocol(host: host)
        case .v1: return V1Protocol(host: host)
        case .huanTong: return HuanTongProtocol(host: host)
        case .ftms: return FtmsProtocol(host: host)
        case .demo: return nil
        }
    }
}
