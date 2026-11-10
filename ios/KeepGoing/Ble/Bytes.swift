import CoreBluetooth
import Foundation

// Port of app/.../ble/Bytes.kt. Byte arrays are [UInt8], so every byte is already unsigned.

extension Array where Element == UInt8 {
    /// Big-endian unsigned 16-bit value starting at `offset`. Used by the Mobi private protocols.
    func u16be(_ offset: Int) -> Int { (Int(self[offset]) << 8) | Int(self[offset + 1]) }

    /// Big-endian unsigned 32-bit value.
    func u32be(_ offset: Int) -> Int64 {
        (Int64(self[offset]) << 24) | (Int64(self[offset + 1]) << 16) |
            (Int64(self[offset + 2]) << 8) | Int64(self[offset + 3])
    }

    /// Little-endian unsigned 16-bit value (FTMS / Bluetooth SIG byte order).
    func u16le(_ offset: Int) -> Int { Int(self[offset]) | (Int(self[offset + 1]) << 8) }

    /// Little-endian signed 16-bit value.
    func s16le(_ offset: Int) -> Int { Int(Int16(bitPattern: UInt16(u16le(offset)))) }

    /// Little-endian unsigned 24-bit value.
    func u24le(_ offset: Int) -> Int {
        Int(self[offset]) | (Int(self[offset + 1]) << 8) | (Int(self[offset + 2]) << 16)
    }

    /// "AB 03 00 ..."
    var hex: String { map { String(format: "%02X", $0) }.joined(separator: " ") }
}

/// bytesOf(0x02, 0x01, 5): values are truncated to their low byte, like Kotlin's toByte().
func bytesOf(_ values: Int...) -> [UInt8] { values.map { UInt8(truncatingIfNeeded: $0) } }

extension CBUUID {
    /// "8800" for 16-bit UUIDs, the full lower-case string otherwise. Only for logging.
    var shortName: String { uuidString.lowercased() }
}
