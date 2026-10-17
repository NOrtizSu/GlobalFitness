package com.enjoyyourtime.globalfitness.ble

import java.util.Locale
import java.util.UUID

/** Unit reported by the scale display (the weight is always converted to kg). */
enum class WeightUnit(val label: String) {
    KG("kg"),
    LBS("lb"),
    JIN("jin"),
}

/** One decoded advertisement frame of the Mi Body Composition Scale 2 (XMTZC05HM). */
data class ScaleFrame(
    val weightKg: Double,
    val rawWeight: Int,
    /** Impedance in ohms, or null when the frame does not carry a valid value. */
    val impedance: Int?,
    val hasImpedance: Boolean,
    val stabilized: Boolean,
    val loadRemoved: Boolean,
    val unit: WeightUnit,
    /** Scale clock as "yyyy-MM-dd HH:mm:ss" built from the 7 date bytes (only used for dedupe). */
    val scaleTimeKey: String,
    val rawHex: String,
) {
    /** Dedupe key for one physical weighing: scale time + raw weight. */
    val dedupeKey: String get() = "$scaleTimeKey|$rawWeight"
}

/**
 * Parser for the 13-byte service data (UUID 0x181B) broadcast by the Mi Scale 2.
 * Pure Kotlin so it can be unit tested on the JVM.
 */
object MiScaleParser {
    val SERVICE_UUID: UUID = UUID.fromString("0000181b-0000-1000-8000-00805f9b34fb")
    const val PAYLOAD_LENGTH = 13
    const val MAX_VALID_IMPEDANCE = 3000

    private const val LBS_TO_KG = 0.45359237
    private const val JIN_TO_KG = 0.5

    fun parse(bytes: ByteArray?): ScaleFrame? {
        if (bytes == null || bytes.size != PAYLOAD_LENGTH) return null
        val ctrl0 = u8(bytes, 0)
        val ctrl1 = u8(bytes, 1)

        val isLbs = ctrl0 and 0x01 != 0
        val hasImpedance = ctrl1 and 0x02 != 0
        val stabilized = ctrl1 and 0x20 != 0
        val isJin = ctrl1 and 0x40 != 0
        val loadRemoved = ctrl1 and 0x80 != 0

        val year = u16le(bytes, 2)
        val month = u8(bytes, 4)
        val day = u8(bytes, 5)
        val hour = u8(bytes, 6)
        val minute = u8(bytes, 7)
        val second = u8(bytes, 8)
        val impedanceRaw = u16le(bytes, 9)
        val rawWeight = u16le(bytes, 11)

        val unit = when {
            isLbs -> WeightUnit.LBS
            isJin -> WeightUnit.JIN
            else -> WeightUnit.KG
        }
        val weightKg = when (unit) {
            WeightUnit.KG -> rawWeight / 200.0
            WeightUnit.LBS -> rawWeight / 100.0 * LBS_TO_KG
            WeightUnit.JIN -> rawWeight / 100.0 * JIN_TO_KG
        }
        val impedance = if (hasImpedance && impedanceRaw in 1..MAX_VALID_IMPEDANCE) impedanceRaw else null
        val timeKey = String.format(
            Locale.ROOT, "%04d-%02d-%02d %02d:%02d:%02d", year, month, day, hour, minute, second,
        )

        return ScaleFrame(
            weightKg = weightKg,
            rawWeight = rawWeight,
            impedance = impedance,
            hasImpedance = hasImpedance,
            stabilized = stabilized,
            loadRemoved = loadRemoved,
            unit = unit,
            scaleTimeKey = timeKey,
            rawHex = toHex(bytes),
        )
    }

    fun toHex(bytes: ByteArray): String = buildString(bytes.size * 2) {
        for (b in bytes) append(String.format(Locale.ROOT, "%02x", b.toInt() and 0xFF))
    }

    fun fromHex(hex: String): ByteArray {
        val clean = hex.filter { !it.isWhitespace() }
        require(clean.length % 2 == 0) { "Odd hex length" }
        return ByteArray(clean.length / 2) { i -> clean.substring(i * 2, i * 2 + 2).toInt(16).toByte() }
    }

    private fun u8(bytes: ByteArray, index: Int): Int = bytes[index].toInt() and 0xFF

    private fun u16le(bytes: ByteArray, index: Int): Int = u8(bytes, index) or (u8(bytes, index + 1) shl 8)
}
