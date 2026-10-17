package com.enjoyyourtime.globalfitness.scale

import java.util.Locale
import java.util.UUID

/**
 * Little helpers for reading/writing integers in BLE payloads. Names follow the converter helpers
 * of openScale so ported protocol code stays recognisable. All reads are unsigned unless stated.
 */
object Bytes {
    fun u8(data: ByteArray, offset: Int): Int = data[offset].toInt() and 0xFF

    fun fromUnsignedInt16Le(data: ByteArray, offset: Int): Int = u8(data, offset) or (u8(data, offset + 1) shl 8)

    fun fromUnsignedInt16Be(data: ByteArray, offset: Int): Int = (u8(data, offset) shl 8) or u8(data, offset + 1)

    fun fromSignedInt16Le(data: ByteArray, offset: Int): Int = fromUnsignedInt16Le(data, offset).toShort().toInt()

    fun fromSignedInt16Be(data: ByteArray, offset: Int): Int = fromUnsignedInt16Be(data, offset).toShort().toInt()

    fun fromUnsignedInt24Le(data: ByteArray, offset: Int): Int =
        u8(data, offset) or (u8(data, offset + 1) shl 8) or (u8(data, offset + 2) shl 16)

    fun fromUnsignedInt24Be(data: ByteArray, offset: Int): Int =
        (u8(data, offset) shl 16) or (u8(data, offset + 1) shl 8) or u8(data, offset + 2)

    fun fromUnsignedInt32Le(data: ByteArray, offset: Int): Long =
        (fromUnsignedInt16Le(data, offset).toLong()) or (fromUnsignedInt16Le(data, offset + 2).toLong() shl 16)

    fun fromUnsignedInt32Be(data: ByteArray, offset: Int): Long =
        (fromUnsignedInt16Be(data, offset).toLong() shl 16) or fromUnsignedInt16Be(data, offset + 2).toLong()

    fun fromSignedInt32Le(data: ByteArray, offset: Int): Int = fromUnsignedInt32Le(data, offset).toInt()

    fun fromSignedInt32Be(data: ByteArray, offset: Int): Int = fromUnsignedInt32Be(data, offset).toInt()

    fun toInt16Le(value: Int): ByteArray = byteArrayOf((value and 0xFF).toByte(), ((value shr 8) and 0xFF).toByte())

    fun toInt16Be(value: Int): ByteArray = byteArrayOf(((value shr 8) and 0xFF).toByte(), (value and 0xFF).toByte())

    fun toInt16Le(data: ByteArray, offset: Int, value: Int) {
        data[offset] = (value and 0xFF).toByte()
        data[offset + 1] = ((value shr 8) and 0xFF).toByte()
    }

    fun toInt16Be(data: ByteArray, offset: Int, value: Int) {
        data[offset] = ((value shr 8) and 0xFF).toByte()
        data[offset + 1] = (value and 0xFF).toByte()
    }

    fun toInt32Le(value: Long): ByteArray = ByteArray(4) { i -> ((value shr (8 * i)) and 0xFF).toByte() }

    fun toInt32Be(value: Long): ByteArray = ByteArray(4) { i -> ((value shr (8 * (3 - i))) and 0xFF).toByte() }

    fun toInt32Le(data: ByteArray, offset: Int, value: Long) {
        for (i in 0 until 4) data[offset + i] = ((value shr (8 * i)) and 0xFF).toByte()
    }

    fun toInt32Be(data: ByteArray, offset: Int, value: Long) {
        for (i in 0 until 4) data[offset + i] = ((value shr (8 * (3 - i))) and 0xFF).toByte()
    }

    fun toHex(bytes: ByteArray?): String {
        if (bytes == null) return ""
        return buildString(bytes.size * 2) {
            for (b in bytes) append(String.format(Locale.ROOT, "%02x", b.toInt() and 0xFF))
        }
    }

    /** Parses hex, ignoring whitespace, ':' separators and an optional 0x prefix. */
    fun fromHex(hex: String): ByteArray {
        val clean = hex.removePrefix("0x").filter { !it.isWhitespace() && it != ':' }
        require(clean.length % 2 == 0) { "Odd hex length" }
        return ByteArray(clean.length / 2) { i -> clean.substring(i * 2, i * 2 + 2).toInt(16).toByte() }
    }

    fun isHex(s: String): Boolean = s.isNotEmpty() && s.all { it.isDigit() || it.lowercaseChar() in 'a'..'f' }

    private const val BASE_UUID_LSB = -0x7fffff7fa064cb05L // 0x800000805F9B34FB
    private const val BASE_UUID_MSB_LOW = 0x1000L

    /** 16-bit Bluetooth SIG UUID on the base UUID, e.g. `uuid16(0x181D)` (no string parsing). */
    fun uuid16(short: Int): UUID = UUID(((short.toLong() and 0xFFFF) shl 32) or BASE_UUID_MSB_LOW, BASE_UUID_LSB)

    /** XOR of all bytes in [from, to). */
    fun xor(data: ByteArray, from: Int = 0, to: Int = data.size): Int {
        var x = 0
        for (i in from until to) x = x xor (data[i].toInt() and 0xFF)
        return x and 0xFF
    }

    /** Sum of all bytes in [from, to) masked to 8 bits. */
    fun sum8(data: ByteArray, from: Int = 0, to: Int = data.size): Int {
        var s = 0
        for (i in from until to) s += data[i].toInt() and 0xFF
        return s and 0xFF
    }
}

/** Shorthand used by ported drivers. */
internal fun uuid16(short: Int): UUID = Bytes.uuid16(short)
