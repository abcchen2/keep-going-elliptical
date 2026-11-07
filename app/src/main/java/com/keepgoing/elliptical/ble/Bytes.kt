package com.keepgoing.elliptical.ble

import java.util.UUID

/** Unsigned value of a byte (0..255). */
fun Byte.u(): Int = toInt() and 0xFF

fun ByteArray.toHex(): String = joinToString(" ") { "%02X".format(it.u()) }

/** Big-endian unsigned 16-bit value starting at [offset]. Used by the Mobi private protocols. */
fun ByteArray.u16be(offset: Int): Int = (this[offset].u() shl 8) or this[offset + 1].u()

/** Big-endian unsigned 32-bit value starting at [offset]. */
fun ByteArray.u32be(offset: Int): Long =
    (this[offset].u().toLong() shl 24) or
        (this[offset + 1].u().toLong() shl 16) or
        (this[offset + 2].u().toLong() shl 8) or
        this[offset + 3].u().toLong()

/** Little-endian unsigned 16-bit value (FTMS / Bluetooth SIG byte order). */
fun ByteArray.u16le(offset: Int): Int = this[offset].u() or (this[offset + 1].u() shl 8)

/** Little-endian signed 16-bit value. */
fun ByteArray.s16le(offset: Int): Int = u16le(offset).toShort().toInt()

/** Little-endian unsigned 24-bit value. */
fun ByteArray.u24le(offset: Int): Int =
    this[offset].u() or (this[offset + 1].u() shl 8) or (this[offset + 2].u() shl 16)

fun bytesOf(vararg values: Int): ByteArray = ByteArray(values.size) { values[it].toByte() }

/** Expands a 16-bit Bluetooth SIG short UUID ("8800") into the full 128-bit form. */
fun uuid16(short: String): UUID = UUID.fromString("0000$short-0000-1000-8000-00805f9b34fb")

/** "0000ffe3-0000-..." -> "ffe3", otherwise the full UUID string. Only for logging. */
fun UUID.shortName(): String {
    val s = toString()
    return if (s.startsWith("0000") && s.endsWith("-0000-1000-8000-00805f9b34fb")) s.substring(4, 8) else s
}
