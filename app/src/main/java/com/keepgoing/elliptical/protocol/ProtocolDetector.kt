package com.keepgoing.elliptical.protocol

import com.keepgoing.elliptical.ble.BleUuids
import com.keepgoing.elliptical.ble.GattLink
import com.keepgoing.elliptical.model.ProtocolKind

/**
 * Same priority as BaseHandler.createHandler() in the official app 4.5.17:
 *   0x8800 -> 2.0 box, FFE0 -> 1.0 box, FFF0 + name "MOBI-" (not "MOBI-C") -> HuanTong, 0x1826 -> FTMS.
 * The older 3.5.x app additionally treated any remaining FFF0/FFC0 device as a 1.0 box.
 */
object ProtocolDetector {
    fun detect(link: GattLink, deviceName: String): ProtocolKind? {
        if (link.hasService(BleUuids.V2_SERVICE)) return ProtocolKind.V2
        if (link.hasService(BleUuids.V1_SERVICE_FFE0)) return ProtocolKind.V1
        if (link.hasService(BleUuids.HT_SERVICE) && isHuanTongName(deviceName)) return ProtocolKind.HUANTONG
        if (link.hasService(BleUuids.FTMS_SERVICE)) return ProtocolKind.FTMS
        if (link.hasService(BleUuids.V1_SERVICE_FFF0) || link.hasService(BleUuids.V1_SERVICE_FFC0)) return ProtocolKind.V1
        return null
    }

    fun isHuanTongName(name: String) = name.startsWith("MOBI-") && !name.startsWith("MOBI-C")

    fun create(kind: ProtocolKind, host: ProtocolHost): MachineProtocol = when (kind) {
        ProtocolKind.V2 -> V2Protocol(host)
        ProtocolKind.V1 -> V1Protocol(host)
        ProtocolKind.HUANTONG -> HuanTongProtocol(host)
        ProtocolKind.FTMS -> FtmsProtocol(host)
        ProtocolKind.DEMO -> error("Demo mode is started with EllipticalController.startDemo(), not over BLE")
    }
}
