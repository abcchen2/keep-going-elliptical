import CoreBluetooth
import Foundation

/// Mobi "1.0" box (service FFE0 / FFF0 / FFC0, frames starting with 0xAB). Port of V1Protocol.kt.
///
/// The machine streams status frames:
///   AB 04 st TT SS bb xx i0 i1 i2 i3 .. .. RR ..
///   TT = device type (11 = elliptical), SS = sub type, bb = battery,
///   i0..i3 = revolution interval in ms (big endian), RR (byte 13) = current resistance.
///
/// Resistance write (writeEllipticalMachineBleVer0x01 in the official app), to FFE0/FFE3:
///   AB 03 00 TT SS LV xx
/// TT, SS and xx are COPIED from the most recent frame the machine sent. A fixed or
/// zero-filled frame is ignored by the box, and nothing can be sent before the first frame arrives.
final class V1Protocol: MachineProtocol {
    override var kind: ProtocolKind { .v1 }

    private var lastFrame: [UInt8]?
    private var statusFrame: [UInt8]?
    private var pendingLevel: Int?
    private var typeKnown = false
    private var type = -1
    private var subType = -1
    private var writeChar: CBCharacteristic?
    private var timers: [DispatchWorkItem] = []

    override func start() {
        for svc in [BleUuids.v1ServiceFFE0, BleUuids.v1ServiceFFF0, BleUuids.v1ServiceFFC0] {
            for id in BleUuids.v1NotifyChars {
                guard let ch = link.characteristic(svc, id), ch.properties.contains(.notify) else { continue }
                // The official app delays the FFE4 subscription by 2 s on "MB-HW" boxes.
                if id == BleUuids.v1CharFFE4 && host.deviceName.hasPrefix("MB-HW") {
                    timers.append(host.postDelayed(2000) { [weak self] in self?.link.enableNotify(ch) })
                } else {
                    link.enableNotify(ch)
                }
            }
        }
        setupStandardServices()
        writeChar = link.characteristic(BleUuids.v1ServiceFFE0, BleUuids.v1WriteElliptical)
            ?? link.findCharacteristic(BleUuids.v1WriteElliptical)
        if writeChar == nil { logRes("warn_no_ffe3") }
        host.update {
            $0.controlReady = true
            if $0.machineDescription == nil { $0.machineDescription = UiText("v1_waiting_frame") }
        }
    }

    override func onNotify(_ ch: CBUUID, _ value: [UInt8]) {
        if handleStandardNotify(ch, value) || value.isEmpty { return }
        lastFrame = value
        guard value[0] == 0xAB, value.count >= 11 else { return }
        if value[1] == 4 { statusFrame = value }

        if value[2] <= 3 { host.update { $0.battery = Int(value[5]) } }

        if !typeKnown && value[1] == 4 {
            typeKnown = true
            type = Int(value[3])
            subType = Int(value[4])
            let desc = ResistanceTables.describe(type, subType)
            logRes("log_v1_frame", type, subType, desc.resolve(), value.hex)
            host.update { $0.machineDescription = desc }
            reportMachineType(type, subType)
            let caps = ResistanceTables.v1(type, subType)
            applyRange(1, caps.max, "src_official_table", mode: caps.mode)
        }

        let bikeOrElliptical = type == ResistanceTables.typeBike || type == ResistanceTables.typeElliptical
        if value[1] == 4 && bikeOrElliptical && value.count > 13 {
            reportResistance(Int(value[13]))
        }

        if bikeOrElliptical {
            let interval = value.u32be(7)
            if interval > 0 {
                var rpm = 60_000.0 / Double(interval)
                if type == ResistanceTables.typeElliptical && subType == 17 { rpm /= 2 }
                reportRpm(rpm)
            }
        }

        if let level = pendingLevel {
            pendingLevel = nil
            logRes("log_v1_sending_queued", level)
            setResistance(level)
        }
    }

    override func setResistance(_ level: Int) {
        guard let ch = writeChar else {
            logRes("err_no_ffe3")
            return
        }
        let frame = statusFrame ?? lastFrame.flatMap { $0.count > 6 ? $0 : nil }
        guard let f = frame else {
            pendingLevel = level
            logRes("log_v1_queued", level)
            return
        }
        link.write(ch, bytesOf(0xAB, 0x03, 0x00, Int(f[3]), Int(f[4]), level, Int(f[6])))
    }

    override func release() {
        timers.forEach { host.cancel($0) }
        timers.removeAll()
    }
}
