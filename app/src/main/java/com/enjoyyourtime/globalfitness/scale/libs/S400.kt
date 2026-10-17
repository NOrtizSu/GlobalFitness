// Protocol ported from openScale (GPL-3.0), https://github.com/oliexdev/openScale
// (originally based on https://github.com/lswiderski/mi-scale-exporter)
package com.enjoyyourtime.globalfitness.scale.libs

import com.enjoyyourtime.globalfitness.scale.Bytes
import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * Decoded payload of one S400 advertisement. The scale emits two packets per weighing:
 * A (weight + heart rate + high-frequency impedance) and B (no weight, low-frequency impedance).
 */
data class S400Packet(
    val weightKg: Float,
    val impedanceHigh: Float?,
    val impedanceLow: Float?,
    val heartRate: Int?,
    /** Scale-side user profile the measurement belongs to (object byte 3). */
    val profileId: Int = 0,
    /** MiBeacon frame counter (extended counter << 8 | counter): identical for re-broadcasts. */
    val counter: Long = 0,
)

/** MiBeacon v5 AES-CCM decryption of Xiaomi Body Composition Scale S400 advertisements. */
object S400Decryptor {
    private const val DATA_LENGTH = 24
    private const val DATA_LENGTH_WITH_HEADER = 26
    private const val TAG_BYTES = 4

    /**
     * [advertisementData]: 24-byte MiBeacon service data (or 26 bytes with the 0xFE95 header),
     * [macAddress] "AA:BB:CC:DD:EE:FF", [bindKey] 32 hex characters. Null when it does not decrypt.
     */
    fun decrypt(advertisementData: ByteArray, macAddress: String, bindKey: String): S400Packet? {
        if (!isValidBindKey(bindKey) || !isValidMacAddress(macAddress)) return null
        val data = when (advertisementData.size) {
            DATA_LENGTH_WITH_HEADER -> advertisementData.copyOfRange(2, DATA_LENGTH_WITH_HEADER)
            DATA_LENGTH -> advertisementData
            else -> return null
        }
        return try {
            val mac = Bytes.fromHex(macAddress)
            val key = Bytes.fromHex(bindKey)
            val nonce = mac.reversedArray() + data.copyOfRange(2, 5) + data.copyOfRange(data.size - 7, data.size - 4)
            val cipherText = data.copyOfRange(5, data.size - 7) + data.copyOfRange(data.size - TAG_BYTES, data.size)
            val plain = AesCcm.decrypt(key, nonce, cipherText, byteArrayOf(0x11), TAG_BYTES) ?: return null
            val counter = (Bytes.fromUnsignedInt24Le(data, data.size - 7).toLong() shl 8) or Bytes.u8(data, 4).toLong()
            parseDecrypted(plain)?.copy(counter = counter)
        } catch (e: Exception) {
            null
        }
    }

    /**
     * The measurement object packs a 32-bit word at offset 4: bits 0..10 weight (×0.1 kg),
     * 11..17 heart rate (+50 bpm), 18..31 impedance (×0.1 Ω; high band when weight present).
     */
    fun parseDecrypted(decrypted: ByteArray): S400Packet? {
        if (decrypted.size < 12) return null
        // Object 0x6E16 ("body composition"), 9 data bytes.
        if (Bytes.fromUnsignedInt16Le(decrypted, 0) != OBJECT_TYPE || Bytes.u8(decrypted, 2) != OBJECT_LENGTH) return null
        val value = Bytes.fromSignedInt32Le(decrypted, 4)
        val weightRaw = value and 0x7FF
        val heartRateRaw = (value shr 11) and 0x7F
        val impedanceRaw = value ushr 18
        val heartRate = if (heartRateRaw in 1..126) heartRateRaw + 50 else null
        val impedanceHigh = if (weightRaw != 0 && impedanceRaw != 0) impedanceRaw / 10f else null
        val impedanceLow = if (weightRaw == 0 && impedanceRaw != 0) impedanceRaw / 10f else null
        return if (weightRaw != 0 || impedanceLow != null) {
            S400Packet(weightRaw / 10f, impedanceHigh, impedanceLow, heartRate, profileId = Bytes.u8(decrypted, 3))
        } else {
            null
        }
    }

    const val OBJECT_TYPE = 0x6E16
    const val OBJECT_LENGTH = 9

    fun isValidBindKey(bindKey: String?): Boolean = bindKey != null && bindKey.length == 32 && Bytes.isHex(bindKey)

    private val MAC_REGEX = Regex("^([0-9A-Fa-f]{2}:){5}[0-9A-Fa-f]{2}$")

    fun isValidMacAddress(mac: String?): Boolean = mac != null && MAC_REGEX.matches(mac)
}

/**
 * Combines packet A and B of one weighing; finalizes on both, or on A alone after
 * [sessionTimeoutMs] (older firmware). Suppresses re-broadcasts of the same result.
 */
class S400Aggregator(
    private val sessionTimeoutMs: Long = 10_000L,
    private val dedupWeightToleranceKg: Float = 0.05f,
    private val dedupImpedanceToleranceOhm: Float = 1.0f,
) {
    sealed interface Outcome {
        data object Pending : Outcome
        data object Duplicate : Outcome
        data class Finalized(
            val weightKg: Float,
            val impedanceHigh: Float,
            val impedanceLow: Float?,
            val heartRate: Int?,
            val timedOut: Boolean,
        ) : Outcome
    }

    private class Session(
        var weightKg: Float? = null,
        var impedanceHigh: Float? = null,
        var impedanceLow: Float? = null,
        var heartRate: Int? = null,
        var firstSeenAt: Long = 0L,
    )

    private class Recent(val weight: Float, val impedanceHigh: Float, val timeMs: Long)

    private val sessions = HashMap<String, Session>()
    private val recent = HashMap<String, Recent>()

    /** True while a weighing is being collected (call [flush] about once per second). */
    val hasPending: Boolean get() = sessions.isNotEmpty()

    fun ingest(deviceMac: String, packet: S400Packet, nowMs: Long): Outcome {
        // A half weighing left over from before must never complete the next one.
        sessions[deviceMac]?.let { if (nowMs - it.firstSeenAt > sessionTimeoutMs) sessions.remove(deviceMac) }
        val session = sessions.getOrPut(deviceMac) { Session(firstSeenAt = nowMs) }
        if (packet.weightKg > 0f) session.weightKg = packet.weightKg
        packet.impedanceHigh?.let { session.impedanceHigh = it }
        packet.impedanceLow?.let { session.impedanceLow = it }
        packet.heartRate?.let { session.heartRate = it }

        val weight = session.weightKg
        val impHigh = session.impedanceHigh
        val impLow = session.impedanceLow
        if (weight == null || impHigh == null) return Outcome.Pending

        val haveBoth = impLow != null
        val timedOut = !haveBoth && nowMs - session.firstSeenAt >= sessionTimeoutMs
        if (!haveBoth && !timedOut) return Outcome.Pending

        recent[deviceMac]?.let { previous ->
            val weightClose = abs(previous.weight - weight) < dedupWeightToleranceKg
            val impClose = abs(previous.impedanceHigh - impHigh) < dedupImpedanceToleranceOhm
            if (weightClose && impClose && nowMs - previous.timeMs < sessionTimeoutMs) {
                sessions.remove(deviceMac)
                return Outcome.Duplicate
            }
        }
        recent[deviceMac] = Recent(weight, impHigh, nowMs)
        sessions.remove(deviceMac)
        return Outcome.Finalized(weight, impHigh, impLow, session.heartRate, timedOut)
    }

    /**
     * Time-based end of weighings: a packet A whose packet B never came is finalized alone after
     * [sessionTimeoutMs] (older firmware); anything else that old is dropped.
     */
    fun flush(nowMs: Long): List<Pair<String, Outcome.Finalized>> {
        val out = ArrayList<Pair<String, Outcome.Finalized>>()
        val it = sessions.entries.iterator()
        while (it.hasNext()) {
            val (mac, s) = it.next()
            if (nowMs - s.firstSeenAt < sessionTimeoutMs) continue
            it.remove()
            val weight = s.weightKg ?: continue
            val impHigh = s.impedanceHigh ?: continue
            val previous = recent[mac]
            if (previous != null && abs(previous.weight - weight) < dedupWeightToleranceKg &&
                abs(previous.impedanceHigh - impHigh) < dedupImpedanceToleranceOhm && nowMs - previous.timeMs < sessionTimeoutMs
            ) {
                continue
            }
            recent[mac] = Recent(weight, impHigh, nowMs)
            out += mac to Outcome.Finalized(weight, impHigh, s.impedanceLow, s.heartRate, timedOut = true)
        }
        return out
    }
}

enum class S400BoneFormula { MI_LEGACY, HEYMSFIELD }
enum class S400BmrFormula { CUNNINGHAM_1991, CUNNINGHAM_1980 }
enum class S400Reliability { OK, APPROXIMATE, UNRELIABLE, NOT_AVAILABLE }

data class S400Inputs(
    val age: Int,
    val sexMale: Boolean,
    val heightCm: Float,
    val weightKg: Float,
    val rHighRaw: Float,
    val rLowRaw: Float,
)

data class S400Result(
    val weightKg: Float,
    val bmi: Float,
    val tbwKg: Float?,
    val tbwPct: Float?,
    val ecwKg: Float?,
    val icwKg: Float?,
    val ffmKg: Float?,
    val bfPct: Float?,
    val smmKg: Float?,
    val smmPct: Float?,
    val boneKg: Float?,
    val vfi: Float?,
    val bmrKcal: Float?,
    val proteinPct: Float?,
    val reliability: S400Reliability,
    val labelSwapApplied: Boolean,
)

/**
 * Literature-based dual-frequency BIA pipeline for the S400 (Sun 2003 TBW, Hanai ECW,
 * Pace & Rathbun FFM, Janssen 2000 SMM, Cunningham BMR, empirical bone/VFI), with the
 * foot-to-foot correction and suppression rules of the openScale implementation.
 */
object S400BodyComposition {
    const val FOOT_TO_FOOT_CORRECTION = 1.10f

    private fun cbrt(x: Double): Double = x.pow(1.0 / 3.0)
    private val K_ECW_M = (cbrt(4.3 * 4.3 * 40.5 * 40.5 / 1.05) / 100.0).toFloat()
    private val K_ECW_F = (cbrt(4.3 * 4.3 * 39.0 * 39.0 / 1.05) / 100.0).toFloat()

    fun compute(
        inputs: S400Inputs,
        boneFormula: S400BoneFormula = S400BoneFormula.MI_LEGACY,
        bmrFormula: S400BmrFormula = S400BmrFormula.CUNNINGHAM_1991,
        footToFootCorrection: Float = FOOT_TO_FOOT_CORRECTION,
    ): S400Result {
        val w = inputs.weightKg
        val h = inputs.heightCm
        val bmi = if (h > 0f) w / (h / 100f).pow(2) else 0f
        if (!isWithinValidationRange(inputs, bmi)) return notAvailable(w, bmi)

        var rHigh = inputs.rHighRaw
        var rLow = inputs.rLowRaw
        val labelSwap = rLow < rHigh
        if (labelSwap) {
            val swap = rHigh
            rHigh = rLow
            rLow = swap
        }
        val rHighRawAfterSwap = rHigh
        val unreliableContact = abs(rLow - rHigh) / rHigh < 0.01f

        val rH = rHigh * footToFootCorrection
        val rL = rLow * footToFootCorrection

        val sexM = if (inputs.sexMale) 1f else 0f
        val tbwRaw = if (inputs.sexMale) {
            1.20f + 0.45f * (h * h / rH) + 0.18f * w
        } else {
            3.75f + 0.45f * (h * h / rH) + 0.11f * w
        }
        val tbwOk = tbwRaw in (0.30f * w)..(0.75f * w)
        val tbw = if (tbwOk) tbwRaw else null

        val kEcw = if (inputs.sexMale) K_ECW_M else K_ECW_F
        val ecwRaw = kEcw * ((h * h * sqrt(w)) / rL).toDouble().pow(2.0 / 3.0).toFloat()
        val ecwTbwRatio = if (tbw != null && tbw > 0f) ecwRaw / tbw else null
        val ratioOk = ecwTbwRatio != null && ecwTbwRatio in 0.30f..0.55f
        val ecw = if (tbw != null && ratioOk) ecwRaw else null
        val icw = if (tbw != null && ecw != null) tbw - ecw else null

        val ffmRaw = if (tbw != null) tbw / 0.732f else null
        val ffmOk = ffmRaw != null && ffmRaw / w in 0.30f..0.97f
        val ffm = if (ffmOk) ffmRaw else null

        val bf = if (ffm != null) w - ffm else null
        val bfPctRaw = if (bf != null) bf / w * 100f else null
        val bfRange = if (inputs.sexMale) 3f..60f else 8f..70f
        val bfPctOk = bfPctRaw != null && bfPctRaw in bfRange
        val bfPct = if (bfPctOk) bfPctRaw else null

        val smm = (0.401f * (h * h / rH) + 3.825f * sexM - 0.071f * inputs.age + 5.102f).coerceIn(8f, 75f)

        val bone = when (boneFormula) {
            S400BoneFormula.MI_LEGACY -> empiricalBone(h, w, inputs.age, rHighRawAfterSwap, inputs.sexMale)
            S400BoneFormula.HEYMSFIELD -> if (inputs.sexMale) 0.041f * w else 0.036f * w
        }.coerceIn(1.0f, 6.0f)

        val vfi = empiricalVfi(h, w, inputs.age, inputs.sexMale).coerceIn(1f, 30f)

        val bmrRaw = if (ffm != null) {
            when (bmrFormula) {
                S400BmrFormula.CUNNINGHAM_1991 -> 370f + 21.6f * ffm
                S400BmrFormula.CUNNINGHAM_1980 -> 500f + 22.0f * ffm
            }
        } else {
            10f * w + 6.25f * h - 5f * inputs.age + if (inputs.sexMale) 5f else -161f
        }
        val bmr = bmrRaw.coerceIn(800f, 4000f)

        val proteinKg = if (ffm != null) (0.20f * ffm - bone).coerceAtLeast(0f) else null
        val proteinPct = if (proteinKg != null) proteinKg / w * 100f else null

        val reliability = when {
            unreliableContact -> S400Reliability.UNRELIABLE
            !tbwOk || !ffmOk || !bfPctOk -> S400Reliability.APPROXIMATE
            else -> S400Reliability.OK
        }
        val suppress = reliability == S400Reliability.UNRELIABLE
        return S400Result(
            weightKg = w,
            bmi = bmi,
            tbwKg = if (suppress) null else tbw,
            tbwPct = if (suppress || tbw == null) null else tbw / w * 100f,
            ecwKg = if (suppress) null else ecw,
            icwKg = if (suppress) null else icw,
            ffmKg = if (suppress) null else ffm,
            bfPct = if (suppress) null else bfPct,
            smmKg = if (suppress) null else smm,
            smmPct = if (suppress) null else smm / w * 100f,
            boneKg = bone,
            vfi = vfi,
            bmrKcal = if (suppress) null else bmr,
            proteinPct = if (suppress) null else proteinPct,
            reliability = reliability,
            labelSwapApplied = labelSwap,
        )
    }

    private fun isWithinValidationRange(i: S400Inputs, bmi: Float): Boolean =
        i.age in 18..120 && i.heightCm in 100f..230f && i.weightKg in 20f..250f &&
            i.rHighRaw in 200f..1500f && i.rLowRaw in 200f..1500f && bmi in 12f..60f

    private fun notAvailable(w: Float, bmi: Float) = S400Result(
        weightKg = w, bmi = bmi, tbwKg = null, tbwPct = null, ecwKg = null, icwKg = null, ffmKg = null,
        bfPct = null, smmKg = null, smmPct = null, boneKg = null, vfi = null, bmrKcal = null, proteinPct = null,
        reliability = S400Reliability.NOT_AVAILABLE, labelSwapApplied = false,
    )

    private fun empiricalBone(h: Float, w: Float, age: Int, rHighRaw: Float, sexMale: Boolean): Float {
        val lbmCoeff = (h * 9.058f / 100f) * (h / 100f) + 0.32f * w + 12.226f - 0.0068f * rHighRaw - 0.0542f * age
        val base = if (sexMale) 0.18016894f else 0.245691014f
        val boneRaw = -(base - 0.05158f * lbmCoeff)
        return if (boneRaw > 2.2f) boneRaw + 0.1f else boneRaw - 0.1f
    }

    private fun empiricalVfi(h: Float, w: Float, age: Int, sexMale: Boolean): Float =
        if (sexMale) {
            if (h < 1.6f * w) {
                305f * w / (-(0.4f * h - 0.0826f * h * h) + 48f) - 2.9f + 0.15f * age
            } else {
                -(0.143f * h - (0.765f - 0.0015f * h) * w) + 0.15f * age - 5f
            }
        } else {
            val threshold = -(13f - 0.5f * h)
            if (w > threshold) {
                500f * w / (1.45f * h + 0.1158f * h * h - 120f) - 6f + 0.07f * age
            } else {
                -(0.027f * h - (0.691f - 0.0048f * h) * w) + 0.07f * age - age
            }
        }
}
