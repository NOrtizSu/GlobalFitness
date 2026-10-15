// Protocol and formulas ported from openScale (GPL-3.0), https://github.com/oliexdev/openScale
package com.enjoyyourtime.globalfitness.scale.libs

/**
 * AFU-BH-TZ-B1 wire format (20-byte `AF 45 cmd body[15] wireType crc5` frames) and the vendor's
 * body-composition model (body fat from impedance, then the rest from body fat).
 */
object AfuB1Lib {
    const val FRAME_SIZE = 20
    const val WIRE_STORED_RECORD = 0x54
    const val WIRE_IMPEDANCE_RESULT = 0x52
    const val WIRE_LIVE_WEIGHT = 0x51
    const val WIRE_CONTROL = 0x5F
    const val WIRE_SYNC_REQUEST = 0x62
    const val WIRE_USER_PROFILE = 0x61
    const val CMD_TIME_SYNC = 0x16
    const val CMD_SYNC_REQUEST = 0x04
    const val CMD_ACK = 0x12
    const val CMD_BODY_COMP_CHUNK = 0x14
    const val CMD_HISTORY_DUMP = 0x8E
    const val WEIGHT_G_MASK = 0x3FFFF

    enum class ProfileVariant(val code: Int, val openByte: Int) {
        INITIAL(0x3F, 0xB6),
        GET_WEIGH_AGAIN_1(0x4B, 0xB6),
        GET_WEIGH_AGAIN_2(0x5B, 0xD9),
        FINAL(0x68, 0xD9),
    }

    enum class SyncRequestVariant { FIRST, SECOND }

    fun convertIntEndian(v: Int): Int =
        ((v and 0xFF) shl 24) or ((v and 0xFF00) shl 8) or ((v ushr 8) and 0xFF00) or ((v ushr 24) and 0xFF)

    fun buildTimeSync(nowEpochSeconds: Long): ByteArray =
        buildFrame(CMD_TIME_SYNC, WIRE_CONTROL, time4(nowEpochSeconds) + byteArrayOf(0x20) + ByteArray(10))

    fun buildSyncRequest(variant: SyncRequestVariant): ByteArray {
        val body = if (variant == SyncRequestVariant.FIRST) {
            byteArrayOf(0x00, 0x01, 0xAF.toByte(), 0xB6.toByte(), 0x17, 0x1D, 0x01, 0x00, 0x02, 0xB4.toByte(), 0xC6.toByte(), 0x1B, 0x56, 0x01, 0x00)
        } else {
            byteArrayOf(0x01, 0x03, 0xA5.toByte(), 0xF8.toByte(), 0x11, 0x1D, 0x02, 0x00, 0x04, 0xB4.toByte(), 0xC6.toByte(), 0x1B, 0x1D, 0x01, 0x00)
        }
        return buildFrame(CMD_SYNC_REQUEST, WIRE_SYNC_REQUEST, body)
    }

    private fun profileBody(nowEpochSeconds: Long, user: Int, openByte: Int, age: Int, sex: Int, targetKg: Float): ByteArray {
        val target = Math.round(targetKg * 100f) and 0xFFFF
        return time4(nowEpochSeconds) + byteArrayOf(
            0x00, (user and 0xFF).toByte(), 0xAF.toByte(), openByte.toByte(), 0x17,
            (age and 0xFF).toByte(), (sex and 0xFF).toByte(), (target and 0xFF).toByte(), ((target ushr 8) and 0xFF).toByte(), 0x03, 0x00,
        )
    }

    fun buildUserProfile(variant: ProfileVariant, nowEpochSeconds: Long, user: Int, age: Int, sex: Int, targetKg: Float): ByteArray =
        buildFrame(variant.code, WIRE_USER_PROFILE, profileBody(nowEpochSeconds, user, variant.openByte, age, sex, targetKg))

    fun buildHistoryDumpRequest(nowEpochSeconds: Long, user: Int, age: Int, sex: Int, targetKg: Float): ByteArray =
        buildFrame(CMD_HISTORY_DUMP, WIRE_USER_PROFILE, profileBody(nowEpochSeconds, user, 0xDE, age, sex, targetKg))

    fun buildAck(replyWireType: Int, correlationIndex: Int): ByteArray =
        buildFrame(CMD_ACK, WIRE_CONTROL, byteArrayOf(replyWireType.toByte(), 0x00, correlationIndex.toByte()) + ByteArray(12))

    fun buildChunkRequest(): ByteArray = buildFrame(CMD_BODY_COMP_CHUNK, WIRE_CONTROL, ByteArray(15))

    sealed class ScaleFrame {
        abstract val ackReplyByte: Int

        data class StoredRecord(
            val historyType: Int,
            val sequence: Int,
            val timestampEpochSeconds: Long,
            val weightGrams: Int,
            val locked: Boolean,
            val resistanceOhms: Int,
        ) : ScaleFrame() {
            override val ackReplyByte = WIRE_STORED_RECORD
        }

        data class LiveWeight(val weightGrams: Int, val mode: Int, val locked: Boolean) : ScaleFrame() {
            override val ackReplyByte = WIRE_LIVE_WEIGHT
        }

        data class ImpedanceResult(val valid: Boolean, val adcOhms: Int) : ScaleFrame() {
            override val ackReplyByte = WIRE_IMPEDANCE_RESULT
        }

        data class BodyCompChunk(val chunkIndex: Int) : ScaleFrame() {
            override val ackReplyByte = CMD_BODY_COMP_CHUNK
        }

        data class Other(val cmd: Int) : ScaleFrame() {
            override val ackReplyByte = 0
        }
    }

    fun parse(raw: ByteArray): ScaleFrame? {
        if (raw.size != FRAME_SIZE || raw[0] != 0xAF.toByte() || raw[1] != 0x45.toByte()) return null
        if (crc(raw) != raw[19].toInt() and 0x1F) return null
        val cmd = raw[2].toInt() and 0xFF
        return when (raw[18].toInt() and 0xFF) {
            WIRE_STORED_RECORD -> {
                val ts = (raw[3].toLong() and 0xFF) or ((raw[4].toLong() and 0xFF) shl 8) or
                    ((raw[5].toLong() and 0xFF) shl 16) or ((raw[6].toLong() and 0xFF) shl 24)
                val wf = convertIntEndian(be32(raw, 7))
                ScaleFrame.StoredRecord(cmd and 0x03, (cmd ushr 2) and 0x3F, ts, wf and WEIGHT_G_MASK, (wf ushr 31) and 1 == 1, le16(raw, 13))
            }
            WIRE_IMPEDANCE_RESULT -> ScaleFrame.ImpedanceResult(cmd == 0x01, le16(raw, 4))
            WIRE_LIVE_WEIGHT -> {
                val wf = convertIntEndian(be32(raw, 2))
                ScaleFrame.LiveWeight(wf and WEIGHT_G_MASK, raw[6].toInt() and 0xFF, (wf ushr 31) and 1 == 1)
            }
            WIRE_CONTROL -> if (cmd == CMD_BODY_COMP_CHUNK) ScaleFrame.BodyCompChunk(raw[3].toInt() and 0xFF) else ScaleFrame.Other(cmd)
            else -> null
        }
    }

    fun bodyFatPercent(weightKg: Double, heightCm: Double, age: Int, sex: Int, impedanceOhm: Double): Float? {
        if (impedanceOhm == 0.0 || age <= 0) return null
        val h = (heightCm / 100.0).toFloat()
        val hd = h.toDouble()
        val h2 = hd * hd
        var r: Double
        if (sex == 1) {
            val h2f = (h * (h * -486583.0f)).toDouble()
            r = 1625303.0 / impedanceOhm / impedanceOhm
            r += 9.146 * weightKg / h2 / impedanceOhm
            r += h2f / weightKg / impedanceOhm + 61.8
            r += -251.193 * h2 / weightKg / age
            r += impedanceOhm * -0.0139
            r += age * 0.05975
        } else {
            val h2f = (h * (h * -382280.0f)).toDouble()
            r = -186.422 * h2 / weightKg + 58.82
            r += h2f / weightKg / impedanceOhm
            r += 128.005 * weightKg / hd / impedanceOhm
            r += -0.0728 * weightKg / hd
            r += 7816.359 / hd / impedanceOhm
            r += -3.333 * weightKg / h2 / age
        }
        val clamped = r.toFloat().coerceIn(5f, 80f)
        return (Math.rint(clamped * 10.0) / 10.0).toFloat()
    }

    data class BodyComposition(
        val waterPercent: Double,
        val skeletalMusclePercent: Double,
        val boneMassKg: Float,
        val visceralFatIndex: Int,
        val proteinPercent: Double,
        val fatFreeMassKg: Float,
    )

    fun bodyComposition(sex: Int, age: Int, heightCm: Float, weightKg: Float, bodyFatPercent: Float): BodyComposition? {
        if (sex > 1 || age !in 10..99) return null
        if (heightCm < 90f || heightCm > 240f || weightKg < 10f || weightKg > 200f) return null
        val bf = bodyFatPercent.toDouble()
        val w = weightKg.toDouble()
        val h = heightCm.toDouble()
        if ((bf <= 4.0 && bf <= 3.999) || (bf >= 75.0 && (bf <= 74.999 || bf > 75.001))) return null
        val bandLow = bf <= 4.0
        val bandHigh = bf >= 75.0
        val bmi = (w * 10000.0 / (heightCm * heightCm).toDouble()).toFloat()
        val recomputedBf: Float = run {
            val num: Double
            val denom: Double
            if (sex == 0) {
                if (age <= 15) {
                    num = ((-556421.0 / bmi).toFloat() + 17387.0).toFloat().toDouble()
                    denom = bf - 44.19
                } else {
                    num = ((-518695.0 / bmi).toFloat() + 15802.0).toFloat().toDouble()
                    denom = bf - 42.86 + age * -0.057
                }
            } else {
                if (age <= 15) {
                    num = ((-506791.0 / bmi).toFloat() + 12009.0).toFloat().toDouble()
                    denom = bf - 63.25 + age * 1.121
                } else {
                    num = ((-460857.0 / bmi).toFloat() + 10645.0).toFloat().toDouble()
                    denom = bf - 37.74 + age * -0.0511
                }
            }
            (num / denom).toFloat()
        }
        val c0: Float = when {
            bandLow -> (if (sex == 0) 66.5 else 66.0).toFloat()
            bandHigh -> (if (sex == 0) 18.0 else 15.0).toFloat()
            sex == 0 -> {
                var s2 = (3752.4 * heightCm).toFloat()
                s2 *= heightCm
                var s3 = (444.93 + (556707.0 / recomputedBf).toFloat()).toFloat()
                s2 /= recomputedBf
                s3 /= weightKg
                s2 = (s2 / 10000.0).toFloat()
                val sa = (10276.0 / recomputedBf).toFloat()
                s2 = s3 + s2
                (24.305 + (s2 - sa)).toFloat()
            }
            else -> {
                var s2 = (3705.3 * heightCm).toFloat()
                s2 *= heightCm
                var s3 = (299.43 + (696819.0 / recomputedBf).toFloat()).toFloat()
                s2 /= recomputedBf
                s3 /= weightKg
                s2 = (s2 / 10000.0).toFloat()
                val sa = (10770.0 / recomputedBf).toFloat()
                s2 = s3 + s2
                (29.61 + (s2 - sa)).toFloat()
            }
        }
        val c4 = (c0.toDouble() * w / 100.0).toFloat()
        val boneMassKg: Float = if (bandLow || bandHigh) {
            val fac = if (bandLow) (if (sex == 0) 5.8 else 5.0) else (if (sex == 0) 1.6 else 1.3)
            ((w * fac).toFloat() / 100.0).toFloat()
        } else {
            val p1 = (w * if (sex == 0) 0.061 else 0.052).toFloat()
            val p2 = (100.0 - bf).toFloat()
            (p1.toDouble() * p2.toDouble() / 100.0).toFloat()
        }
        val s1: Float
        val s4: Float
        when {
            bandLow -> {
                s1 = (if (sex == 0) 56.6 else 59.0).toFloat()
                s4 = (if (sex == 0) 90.2 else 91.0).toFloat()
            }
            bandHigh -> {
                s1 = (if (sex == 0) 15.1 else 17.9).toFloat()
                s4 = (if (sex == 0) 23.4 else 23.7).toFloat()
            }
            else -> {
                s1 = if (sex == 0) c0 * 0.857f - 0.36f else c0 * 0.895f
                s4 = (if (sex == 0) 0.939f else 0.948f) * (100f - bf.toFloat())
            }
        }
        val skeletalMusclePercent = ((s1 * w).toFloat() / 100.0).toFloat() * 100.0 / w
        val muscleKg = ((s4 * w).toFloat() / 100.0).toFloat()
        val proteinRate = ((muscleKg - c4) * 100.0 / w).toFloat()
        val vfal: Int = when {
            bandLow -> 1
            bandHigh -> 30
            sex == 1 -> {
                val bf64 = recomputedBf.toDouble()
                val d17 = (77.216 / bf64 + 0.068) * w + 0.5
                var d5 = (-5489.0 / recomputedBf).toFloat().toDouble() + 2.8
                d5 *= h
                d5 *= h
                val d6 = d17 + d5 / 10000.0 + 6096.3 / bf64
                (-1.22 + (d6 + 0.1668 * age)).toInt().coerceIn(1, 30)
            }
            else -> {
                val bf64 = recomputedBf.toDouble()
                val d17 = (71.92 / bf64 + 0.0876) * w + 0.5
                var d5 = (-1261.7 / bf64).toFloat().toDouble() - 2.88
                d5 *= h
                d5 *= h
                val d6 = d17 + d5 / 10000.0 + -1545.6 / bf64
                (5.9 + (d6 + 0.068 * age)).toInt().coerceIn(1, 30)
            }
        }
        val bonePct = boneMassKg * 100.0 / w
        val fatMassKg = (w * bf).toFloat()
        val fatFreeMassKg = (w + (fatMassKg / -100.0).toFloat()).toFloat()
        return BodyComposition(
            waterPercent = 100.0 - bf - bonePct - proteinRate,
            skeletalMusclePercent = skeletalMusclePercent,
            boneMassKg = boneMassKg,
            visceralFatIndex = vfal,
            proteinPercent = proteinRate.toDouble(),
            fatFreeMassKg = fatFreeMassKg,
        )
    }

    private fun buildFrame(cmd: Int, wireType: Int, body: ByteArray): ByteArray {
        require(body.size == 15) { "AFU frame body must be 15 bytes" }
        val b = ByteArray(FRAME_SIZE)
        b[0] = 0xAF.toByte()
        b[1] = 0x45
        b[2] = cmd.toByte()
        body.copyInto(b, 3)
        b[18] = wireType.toByte()
        b[19] = crc(b).toByte()
        return b
    }

    private fun time4(nowEpochSeconds: Long): ByteArray {
        val v = convertIntEndian((nowEpochSeconds and 0xFFFFFFFFL).toInt())
        return byteArrayOf((v ushr 24).toByte(), (v ushr 16).toByte(), (v ushr 8).toByte(), v.toByte())
    }

    private fun crc(raw: ByteArray): Int {
        var sum = 0
        for (i in 2 until 19) sum += raw[i].toInt() and 0xFF
        return sum and 0x1F
    }

    private fun le16(raw: ByteArray, off: Int): Int = (raw[off].toInt() and 0xFF) or ((raw[off + 1].toInt() and 0xFF) shl 8)

    private fun be32(raw: ByteArray, start: Int): Int =
        ((raw[start].toInt() and 0xFF) shl 24) or ((raw[start + 1].toInt() and 0xFF) shl 16) or
            ((raw[start + 2].toInt() and 0xFF) shl 8) or (raw[start + 3].toInt() and 0xFF)
}
