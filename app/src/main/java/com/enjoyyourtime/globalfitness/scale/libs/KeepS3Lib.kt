// Protocol and formulas ported from openScale (GPL-3.0), https://github.com/oliexdev/openScale
package com.enjoyyourtime.globalfitness.scale.libs

import java.util.Locale
import kotlin.math.roundToInt

/** Keep S3 wire format: `type 53 opcode 00 len payload` frames, ID24 token, profile and records. */
object KeepS3Protocol {
    const val FRAME_REQUEST = 0x01
    const val FRAME_RESPONSE = 0x02
    const val FRAME_EVENT = 0x03
    const val FRAME_ACK = 0x04
    const val MAGIC = 0x53
    const val STATUS_OK = 0x80
    const val OP_SET_TIME = 0x01
    const val OP_BATTERY = 0x03
    const val OP_SET_UNIT = 0x05
    const val OP_READ_TIME = 0x0A
    const val OP_UNKNOWN_20 = 0x20
    const val OP_PROFILE = 0x32
    const val OP_MEASUREMENT_CONTROL = 0x36
    const val OP_NEGOTIATE = 0x38
    const val OP_MEASUREMENT_EVENT = 0x57
    const val OP_FINAL_RECORD = 0x58
    const val OP_DEVICE_INFO = 0xE7
    const val OP_UNKNOWN_F5 = 0xF5
    const val STAGE_FINAL = 0x29
    private const val TOKEN_SIZE = 24

    /** Initialisation sequence: each step is sent once the previous one was acknowledged. */
    val INIT_SEQUENCE = listOf(
        OP_NEGOTIATE, OP_READ_TIME, OP_SET_TIME, OP_SET_UNIT, OP_DEVICE_INFO, OP_UNKNOWN_F5, OP_UNKNOWN_F5,
        OP_BATTERY, OP_UNKNOWN_20, OP_PROFILE, OP_MEASUREMENT_CONTROL,
    )

    data class FrameHeader(val type: Int, val opcode: Int, val payloadLength: Int)

    data class Response(val opcode: Int, val data: ByteArray)

    data class MeasurementEvent(val stage: Int, val weightKg: Float, val impedanceOhm: Int, val heartRateBpm: Int)

    data class FinalRecord(val weightKg: Float, val impedance50Ohm: Int?, val impedance100Ohm: Int?, val heartRateBpm: Int)

    data class PreviousRecord(val weightKg: Float = 0f, val timestampSeconds: Long = 0L, val impedanceOhm: Double = 0.0)

    private fun u8(b: Byte) = b.toInt() and 0xFF

    fun peekFrameHeader(frame: ByteArray): FrameHeader? {
        if (frame.size < 5 || u8(frame[1]) != MAGIC || u8(frame[3]) != 0) return null
        val type = u8(frame[0])
        if (type !in FRAME_REQUEST..FRAME_ACK) return null
        return FrameHeader(type, u8(frame[2]), u8(frame[4]))
    }

    fun parseFrameHeader(frame: ByteArray): FrameHeader? {
        val header = peekFrameHeader(frame) ?: return null
        val expected = if (header.type == FRAME_RESPONSE || header.type == FRAME_ACK) 6 + header.payloadLength else 5 + header.payloadLength
        return header.takeIf { frame.size == expected }
    }

    fun parseResponse(frame: ByteArray): Response? {
        val header = parseFrameHeader(frame) ?: return null
        if (header.type != FRAME_RESPONSE || u8(frame[5]) != STATUS_OK) return null
        return Response(header.opcode, frame.copyOfRange(6, frame.size))
    }

    fun parseMeasurementEvent(frame: ByteArray): MeasurementEvent? {
        val header = parseFrameHeader(frame) ?: return null
        if (header.type != FRAME_EVENT || header.opcode != OP_MEASUREMENT_EVENT || header.payloadLength != 8 || frame.size != 13) return null
        return MeasurementEvent(u8(frame[5]), u16(frame, 6) / 200.0f, u16(frame, 10), u8(frame[12]))
    }

    fun parseFinalRecord(frame: ByteArray): FinalRecord? {
        val header = parseFrameHeader(frame) ?: return null
        if (header.type != FRAME_EVENT || header.opcode != OP_FINAL_RECORD || header.payloadLength != 61 || frame.size != 66) return null
        return FinalRecord(
            weightKg = u16(frame, 53) / 200.0f,
            impedance50Ohm = decodeEncodedImpedance(u24(frame, 55)),
            impedance100Ohm = decodeEncodedImpedance(u24(frame, 58)),
            heartRateBpm = u8(frame[65]),
        )
    }

    fun buildRequest(opcode: Int, payload: ByteArray = byteArrayOf()): ByteArray =
        byteArrayOf(FRAME_REQUEST.toByte(), MAGIC.toByte(), opcode.toByte(), 0x00, payload.size.toByte()) + payload

    fun buildAck(opcode: Int): ByteArray =
        byteArrayOf(FRAME_ACK.toByte(), MAGIC.toByte(), opcode.toByte(), 0x00, 0x00, STATUS_OK.toByte())

    fun buildProfilePayload(token: String, previous: PreviousRecord?, heightCm: Int, birthYear: Int, birthMonth: Int, birthDay: Int): ByteArray {
        val payload = ByteArray(63)
        repeatedTokenBytes(token).copyInto(payload)
        val record = previous ?: PreviousRecord()
        putU16(payload, 48, encodeWeightRaw(record.weightKg))
        putU32(payload, 50, record.timestampSeconds.coerceIn(0L, 0xFFFF_FFFFL))
        putU16(payload, 54, if (record.impedanceOhm.isFinite() && record.impedanceOhm > 0.0) record.impedanceOhm.roundToInt() else 0)
        putU16(payload, 56, 0)
        payload[58] = heightCm.coerceIn(0, 0xFF).toByte()
        putU16(payload, 59, birthYear.coerceIn(0, 0xFFFF))
        payload[61] = birthMonth.coerceIn(1, 12).toByte()
        payload[62] = birthDay.coerceIn(1, 31).toByte()
        return payload
    }

    fun buildMeasurementControl(token: String, start: Boolean): ByteArray =
        repeatedTokenBytes(token) + byteArrayOf(0x00, if (start) 0x01 else 0x00)

    fun repeatedTokenBytes(token: String): ByteArray {
        require(validateToken(token)) { "invalid Keep S3 ID24 token" }
        return (token + token).encodeToByteArray()
    }

    fun validateToken(token: String): Boolean = token.length == TOKEN_SIZE && token.all { it in '0'..'9' || it in 'a'..'f' }

    fun generateToken(randomBytes: ByteArray): String {
        require(randomBytes.size == 12) { "Keep S3 ID24 requires 12 random bytes" }
        return randomBytes.joinToString("") { String.format(Locale.ROOT, "%02x", u8(it)) }
    }

    /** `seconds:weightRaw:impedance` of the last measurement, sent back to the scale as its reference. */
    fun serializePrevious(timestampSeconds: Long, weightKg: Float, impedanceOhm: Int): String? {
        if (timestampSeconds !in 1L..0xFFFF_FFFFL || impedanceOhm !in 1..0xFFFF) return null
        val weightRaw = encodeWeightRaw(weightKg).takeIf { it > 0 } ?: return null
        return "$timestampSeconds:$weightRaw:$impedanceOhm"
    }

    /** The stored reference, only if it is still the app's last weight. */
    fun parsePrevious(value: String?, lastWeightKg: Float?): PreviousRecord? {
        val f = value?.split(':')?.takeIf { it.size == 3 } ?: return null
        val ts = f[0].toLongOrNull()?.takeIf { it in 1L..0xFFFF_FFFFL } ?: return null
        val weightRaw = f[1].toIntOrNull()?.takeIf { it in 1..0xFFFF } ?: return null
        val impedance = f[2].toIntOrNull()?.takeIf { it in 1..0xFFFF } ?: return null
        if (lastWeightKg == null || encodeWeightRaw(lastWeightKg) != weightRaw) return null
        return PreviousRecord(weightRaw / 200f, ts, impedance.toDouble())
    }

    fun decodeEncodedImpedance(encoded: Int): Int? {
        if (encoded !in 0..0xFF_FFFF || encoded == 0xFF_FFFF) return null
        val upper = (encoded and 0x0F00) or ((encoded ushr 16) and 0xFF)
        val lower = ((encoded and 0xFF) shl 2) + ((encoded ushr 12) and 0x0F)
        return ((upper - lower) / 2).takeIf { it in 200..1200 }
    }

    private fun encodeWeightRaw(weightKg: Float): Int =
        if (!weightKg.isFinite() || weightKg <= 0f) 0 else (weightKg.toDouble() * 200.0).roundToInt().coerceIn(0, 0xFFFF)

    private fun u16(d: ByteArray, o: Int) = (u8(d[o]) shl 8) or u8(d[o + 1])

    private fun u24(d: ByteArray, o: Int) = (u8(d[o]) shl 16) or (u8(d[o + 1]) shl 8) or u8(d[o + 2])

    private fun putU16(t: ByteArray, o: Int, v: Int) {
        val c = v.coerceIn(0, 0xFFFF)
        t[o] = (c ushr 8).toByte()
        t[o + 1] = c.toByte()
    }

    fun putU32(t: ByteArray, o: Int, v: Long) {
        for (i in 0 until 4) t[o + i] = (v ushr (8 * (3 - i))).toByte()
    }
}

/**
 * BestHealth "BHKeep" two-leg dual-frequency body composition (local estimate, fixed-point and
 * single-precision arithmetic as in the vendor routine).
 */
object KeepS3BodyComposition {
    data class Result(
        val bodyFatPercent: Float,
        val fatFreeMassKg: Float,
        val waterPercent: Float,
        val boneKg: Float,
        val musclePercent: Float,
        val skeletalMusclePercent: Float,
        val proteinPercent: Float,
        val visceralFatLevel: Int,
        val basalMetabolicRateKcal: Int,
        val bodyAge: Int,
    )

    fun calculate(male: Boolean, age: Int, heightCm: Int, weightKg: Float, impedance50Ohm: Int, impedance100Ohm: Int, athlete: Boolean = false): Result? {
        if (age !in 6..99 || heightCm !in 90..220) return null
        if (!weightKg.isFinite() || weightKg !in 10f..200f) return null
        if (impedance50Ohm !in 200..1200 || impedance100Ohm !in 200..1200) return null
        val weightRaw = (weightKg * 10f).toInt().coerceIn(100, 2000)
        val height = heightCm
        val averageImpedance = (impedance50Ohm + impedance100Ohm) ushr 1
        val heightSquared = height * height
        val bmiRaw = weightRaw * 10_000 / heightSquared
        val rawFatFreeMass = heightSquared.toFloat() * 9.058f / 10_000f + 12.226f + weightRaw.toFloat() * 0.032f -
            averageImpedance.toFloat() * 0.0068f - age.toFloat() * 0.0542f
        var adjusted = rawFatFreeMass - when {
            male -> 0.8f
            age < 50 -> 9.25f
            else -> 7.25f
        }
        if (male) {
            adjusted *= 1.05f
            if (averageImpedance.toFloat() / height.toFloat() < 2.6f) {
                adjusted *= 1.03f
            } else {
                adjusted *= 0.96f
                if (weightRaw < 610) adjusted *= 0.97f
                if (height > 170) adjusted *= 0.98f
            }
        } else {
            adjusted *= 1.02f
            if (weightRaw < 500) adjusted *= 1.02f
            if (weightRaw > 600) adjusted *= 0.96f
            if (height > 160) adjusted *= 1.03f
        }
        var fatKg = weightRaw.toFloat() / 10f - adjusted
        if (athlete) fatKg = if (male) fatKg * 0.778f - 0.93f else fatKg * 0.992f - 1.5f
        val fatRateRaw = (fatKg * 10_000f / weightRaw.toFloat()).toInt().coerceIn(50, 750)
        val fatKgRaw = fatRateRaw * weightRaw / 1000
        val fatFreeMassRaw = weightRaw - fatKgRaw
        var waterRateRaw = ((1000 - fatRateRaw) * 7 / 10).let { base -> base * if (base > 500) 98 else 102 } / 100
        if (athlete) waterRateRaw = (waterRateRaw.toFloat() * (if (male) 0.996f else 0.985f) + (if (male) 4f else 9f)).toInt()
        waterRateRaw = waterRateRaw.coerceAtLeast(350)
        var boneRaw = (rawFatFreeMass * 0.5158f - if (male) 1.802f else 2.4569f).toInt()
        boneRaw += if (boneRaw > 22) 1 else -1
        if (athlete) {
            boneRaw += when {
                boneRaw < 20 -> 1
                boneRaw < 30 -> 2
                else -> 3
            }
        }
        var bmrRaw = if (male) {
            weightRaw.toFloat() * 1.4916f + 877.8f - height.toFloat() * 0.726f - age.toFloat() * 8.976f
        } else {
            weightRaw.toFloat() * 1.0204f + 864.6f - height.toFloat() * 0.3934f - age.toFloat() * 6.204f
        }.toInt()
        if (athlete) bmrRaw = (bmrRaw.toFloat() * 1.16f - 149f).toInt()
        bmrRaw = bmrRaw.coerceAtLeast(500)
        val muscleKgRaw = (fatFreeMassRaw - boneRaw).coerceAtLeast(0)
        val muscleRateRaw = (muscleKgRaw.toFloat() * 1000f / weightRaw.toFloat()).toInt()
        val waterKgRaw = waterRateRaw * weightRaw / 1000
        val skeletalMuscleKgRaw = (waterKgRaw.toFloat() * 0.832f - 27.354f).toInt().coerceAtLeast(0)
        val boneRateRaw = boneRaw.toFloat() * 1000f / weightRaw.toFloat()
        val proteinRateRaw = ((1000 - fatRateRaw).toFloat() - waterRateRaw.toFloat() * 1.08f - boneRateRaw).toInt().coerceIn(20, 300)
        return Result(
            bodyFatPercent = fatRateRaw / 10f,
            fatFreeMassKg = fatFreeMassRaw / 10f,
            waterPercent = waterRateRaw / 10f,
            boneKg = boneRaw / 10f,
            musclePercent = muscleRateRaw / 10f,
            skeletalMusclePercent = skeletalMuscleKgRaw.toFloat() * 100f / weightRaw.toFloat(),
            proteinPercent = proteinRateRaw / 10f,
            visceralFatLevel = visceralFat(male, athlete, age, height, weightRaw, heightSquared),
            basalMetabolicRateKcal = bmrRaw,
            bodyAge = bodyAge(age, bmiRaw),
        )
    }

    private fun bodyAge(age: Int, bmiRaw: Int): Int {
        val first = (age.toFloat() + 28.428f - bmiRaw.toFloat() * 0.1428f).toInt().coerceIn(age - 5, age + 5)
        val second = (bmiRaw.toFloat() * 0.1724f + age.toFloat() - 34.931f).toInt().coerceIn(age - 8, age + 8)
        val result = if (bmiRaw < 30) first.toFloat() * 0.6f + second.toFloat() * 0.4f else first.toFloat() * 0.4f + second.toFloat() * 0.6f
        return result.toInt().coerceIn(6, 99)
    }

    private fun visceralFat(male: Boolean, athlete: Boolean, age: Int, height: Int, weightRaw: Int, heightSquared: Int): Int {
        val weight = weightRaw.toFloat()
        val stature = height.toFloat()
        var visceral = if (male) {
            if (weight * 0.16f + 63f > stature) {
                weight * 30.5f / (heightSquared.toFloat() * 0.0826f - stature * 0.4f + 48f) - 2.9f + age.toFloat() * 0.15f
            } else {
                (stature * -0.0015f + 0.765f) * weight / 10f - stature * 0.143f + age.toFloat() * 0.15f - 5f
            }
        } else {
            if (stature * 5f - 130f < weight) {
                weight * 50f / (heightSquared.toFloat() * 0.1158f + stature * 1.45f - 144f) - 6f + age.toFloat() * 0.07f
            } else {
                (stature * -0.0024f + 0.691f) * weight / 10f - stature * 0.027f + age.toFloat() * 0.07f - 10.5f
            }
        }
        if (athlete) {
            visceral = when {
                visceral < 2f -> 1f
                visceral < 10f -> visceral - 2f
                visceral < 20f -> visceral * 0.8f
                else -> visceral * 0.85f
            }
        }
        return visceral.toInt().coerceIn(1, 50)
    }
}
