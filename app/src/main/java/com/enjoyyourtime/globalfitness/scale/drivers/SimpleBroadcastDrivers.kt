// Protocol ported from openScale (GPL-3.0), https://github.com/oliexdev/openScale
package com.enjoyyourtime.globalfitness.scale.drivers

import com.enjoyyourtime.globalfitness.scale.AdvertisementInfo
import com.enjoyyourtime.globalfitness.scale.Bytes
import com.enjoyyourtime.globalfitness.scale.DriverEnv
import com.enjoyyourtime.globalfitness.scale.FormulaLib
import com.enjoyyourtime.globalfitness.scale.LiveState
import com.enjoyyourtime.globalfitness.scale.MatchConfidence
import com.enjoyyourtime.globalfitness.scale.PassiveScaleDriver
import com.enjoyyourtime.globalfitness.scale.PassiveSession
import com.enjoyyourtime.globalfitness.scale.ScaleCapabilities
import com.enjoyyourtime.globalfitness.scale.ScaleReading
import com.enjoyyourtime.globalfitness.scale.ScaleSample
import com.enjoyyourtime.globalfitness.scale.SimplePassiveSession
import java.util.Locale

/**
 * "AAA002 / AAA007 / AAA013" broadcast scales: weight in manufacturer data, last 6 bytes XORed
 * with the company id high byte and protected by a 5-bit checksum.
 */
class AaaBroadcastDriver : PassiveScaleDriver() {
    override val id = "aaa_broadcast"
    override val displayName = "Báscula AAA002 / AAA007 / AAA013"
    override val brand = "Genérica (AAA)"
    override val capabilities = ScaleCapabilities()
    override val keywords = listOf("AAA002", "AAA007", "AAA013")

    override fun match(adv: AdvertisementInfo): MatchConfidence? {
        val n = adv.nameOrEmpty
        return if (n == "AAA002" || n == "AAA007" || n == "AAA013") MatchConfidence.HIGH else null
    }

    override fun newSession(env: DriverEnv): PassiveSession = object : SimplePassiveSession(null) {
        override fun parse(adv: AdvertisementInfo, env: DriverEnv): ScaleSample? {
            for ((companyId, data) in adv.manufacturerData) {
                val frame = decode(companyId, data) ?: continue
                if (frame.first) return ScaleSample().apply { weightKg = frame.second }
                liveWeight(frame.second)
                return null
            }
            return null
        }
    }

    companion object {
        /** (final, kg) for a valid weight frame, else null. */
        fun decode(companyId: Int, data: ByteArray): Pair<Boolean, Float>? {
            if (data.size < 12) return null
            val key = (companyId ushr 8) and 0xFF
            val p = ByteArray(6) { i -> (data[6 + i].toInt() xor key).toByte() }
            var chk = 0
            for (i in 0 until 5) chk += p[i].toInt() and 0xFF
            if (chk and 0x1F != p[5].toInt() and 0x1F) return null
            if (p[4].toInt() and 0xFF != 0xAD) return null
            val value = Bytes.fromUnsignedInt32Be(p, 0)
            val final = (value ushr 31) and 1L != 0L
            val grams = (value and 0x3FFFF).toInt()
            if (grams <= 0) return null
            return final to grams / 1000f
        }
    }
}

/**
 * Eufy Smart Scale C20 (T9130): broadcast frames (company id 48228) carrying weight, impedance
 * and heart rate. Composition with the Mi Fit formulas (as openScale does).
 */
class EufyC20Driver : PassiveScaleDriver() {
    override val id = "eufy_c20"
    override val displayName = "Eufy Smart Scale C20 (T9130)"
    override val brand = "Eufy"
    override val capabilities = ScaleCapabilities(impedance = true)
    override val formulaLib = FormulaLib.MI_SCALE
    override val keywords = listOf("T9130", "C20", "Anker")

    override fun match(adv: AdvertisementInfo): MatchConfidence? {
        adv.manufacturer(MANUFACTURER_ID)?.let { return if (decode(it) != null) MatchConfidence.HIGH else MatchConfidence.MEDIUM }
        val n = adv.upperName
        return if (n.startsWith("EUFY") && (n.contains("C20") || n.contains("T9130"))) MatchConfidence.HIGH else null
    }

    override fun newSession(env: DriverEnv): PassiveSession = Session()

    /**
     * Collects one weighing: the weight must be seen at least twice (the first frames are still
     * moving); impedance and heart rate are added when they come. Everything expires after
     * [QUIET_MS] without frames: a weight-only reading is committed if it was confirmed.
     */
    private inner class Session : PassiveSession {
        private var stored: ScaleSample? = null
        private var weightRepeats = 0
        private var lastAt = 0L
        private var lastGrams: Long? = null
        private var lastFinishAt = 0L
        override var live: LiveState = LiveState.Idle
            private set

        override val needsTick: Boolean get() = stored != null

        override fun parseAdvertisement(adv: AdvertisementInfo, env: DriverEnv): List<ScaleReading> {
            val payload = adv.manufacturer(MANUFACTURER_ID) ?: return emptyList()
            val m = decode(payload) ?: return emptyList()
            val now = env.elapsedClock()
            if (stored != null && now - lastAt >= QUIET_MS) expire(env)
            lastAt = now
            val s = stored ?: ScaleSample().also {
                stored = it
                weightRepeats = 0
            }
            m.weightKg?.let { w ->
                weightRepeats = if (s.weightKg == w) weightRepeats + 1 else 1
                s.weightKg = w
            }
            m.impedance?.let { s.impedance = it }
            m.heartRate?.let { s.heartRate = it }
            s.weightKg?.let { live = LiveState.Measuring(it.toDouble()) }
            val complete = weightRepeats >= 2 && (s.impedance ?: 0f) > 0f && (s.heartRate ?: 0) > 0
            return if (complete) finish(env) else emptyList()
        }

        /** Quiet period over: commit a confirmed weight (weight only if that is all that came). */
        override fun tick(env: DriverEnv): List<ScaleReading> =
            if (stored != null && env.elapsedClock() - lastAt >= QUIET_MS) expire(env) else emptyList()

        private fun expire(env: DriverEnv): List<ScaleReading> {
            if (weightRepeats >= 2 && stored?.hasWeight() == true) return finish(env)
            stored = null
            weightRepeats = 0
            if (live !is LiveState.Done) live = LiveState.Idle
            return emptyList()
        }

        private fun finish(env: DriverEnv): List<ScaleReading> {
            val s = stored ?: return emptyList()
            stored = null
            weightRepeats = 0
            live = LiveState.Done
            val reading = s.toReading(env, FormulaLib.MI_SCALE) ?: return emptyList()
            val grams = ScaleReading.grams(reading.weightKg)
            val now = env.elapsedClock()
            val previous = lastGrams
            val repeated = previous != null && kotlin.math.abs(previous - grams) <= 50 && now - lastFinishAt <= REBROADCAST_MS
            lastGrams = grams
            lastFinishAt = now
            return if (repeated) emptyList() else listOf(reading)
        }
    }

    companion object {
        const val MANUFACTURER_ID = 48228
        private const val QUIET_MS = 8_000L
        private const val REBROADCAST_MS = 60_000L

        /** Flags at [10]: bit0 weight (LE u16 /100 at 12), bit6 impedance (LE /10 at 17), bit7 heart rate (15). */
        fun decode(payload: ByteArray): ScaleSample? {
            if (payload.size < 14) return null
            val flags = Bytes.u8(payload, 10)
            val m = ScaleSample()
            if (flags and 0x01 != 0) {
                val w = Bytes.fromUnsignedInt16Le(payload, 12) / 100.0f
                if (w != 0f) m.weightKg = w
            }
            if (flags and 0x80 != 0 && payload.size >= 16) Bytes.u8(payload, 15).takeIf { it != 0 }?.let { m.heartRate = it }
            if (flags and 0x40 != 0 && payload.size >= 19) {
                val imp = Bytes.fromUnsignedInt16Le(payload, 17) / 10.0f
                if (imp != 0f) m.impedance = imp
            }
            if (!m.hasWeight() && m.impedance == null && m.heartRate == null) return null
            return m
        }
    }
}

/**
 * Sinocare "Weight Scale": manufacturer data 0xFF64 (or 0x0000), XOR checksum over [6..15] at
 * [16], weight = [10]:[9] / 100. Stable after 9 identical consecutive frames.
 */
class SinocareDriver : PassiveScaleDriver() {
    override val id = "sinocare"
    override val displayName = "Sinocare Weight Scale"
    override val brand = "Sinocare"
    override val capabilities = ScaleCapabilities()

    override fun match(adv: AdvertisementInfo): MatchConfidence? = when {
        adv.manufacturer(MANUFACTURER_ID)?.let { weightRaw(it) } != null -> MatchConfidence.HIGH
        adv.manufacturerData.containsKey(MANUFACTURER_ID) -> MatchConfidence.MEDIUM
        adv.nameOrEmpty.equals("Weight Scale", ignoreCase = true) -> MatchConfidence.MEDIUM
        else -> null
    }

    override fun newSession(env: DriverEnv): PassiveSession = object : SimplePassiveSession(null) {
        private var lastRaw = 0
        private var repeat = 0
        private var lastAt = 0L

        override fun parse(adv: AdvertisementInfo, env: DriverEnv): ScaleSample? {
            val data = adv.manufacturer(MANUFACTURER_ID) ?: adv.manufacturer(MANUFACTURER_ID_ALT) ?: return null
            val raw = weightRaw(data) ?: return null
            val now = env.elapsedClock()
            val silent = now - lastAt > SILENCE_RESET_MS
            lastAt = now
            if (raw <= 0 || silent) {
                lastRaw = 0
                repeat = 0
                if (raw <= 0) {
                    noLoad()
                    return null
                }
            }
            if (raw != lastRaw) {
                lastRaw = raw
                repeat = 1
                liveWeight(raw / 100f)
                return null
            }
            repeat++
            if (repeat < STABLE_REPEATS) {
                liveWeight(raw / 100f)
                return null
            }
            if (repeat > STABLE_REPEATS) return null // already published this weighing
            return ScaleSample().apply { weightKg = raw / 100f }
        }
    }

    companion object {
        const val MANUFACTURER_ID = 0xFF64
        const val MANUFACTURER_ID_ALT = 0x0000
        const val STABLE_REPEATS = 9
        private const val SILENCE_RESET_MS = 5_000L

        /** Raw weight (kg × 100) of a frame with a valid checksum, else null. */
        fun weightRaw(data: ByteArray): Int? {
            if (data.size <= 16) return null
            if (Bytes.xor(data, 6, 16) != Bytes.u8(data, 16)) return null
            return (Bytes.u8(data, 10) shl 8) or Bytes.u8(data, 9)
        }
    }
}

/**
 * "Scale Up" protocol: non-connectable manufacturer data where Android's company id is really
 * `[flag][weight MSB]` (flag 0xD0 measuring, 0xE0 stable) and the data starts with the weight LSB.
 */
class ScaleupDriver : PassiveScaleDriver() {
    override val id = "scaleup"
    override val displayName = "Scale Up (anuncio 0xD0/0xE0)"
    override val brand = "Genérica (Scale Up)"
    override val capabilities = ScaleCapabilities()

    override fun match(adv: AdvertisementInfo): MatchConfidence? {
        val frames = adv.manufacturerData.entries.filter { (key, data) ->
            (key and 0xFF == FLAG_MEASURING || key and 0xFF == FLAG_STABLE) && data.size >= 9 && weightKg(key, data) in 0.5f..300f
        }
        return when {
            frames.any { macEcho(it.value, adv.address) } -> MatchConfidence.MEDIUM
            adv.nameOrEmpty.trim().equals("da", ignoreCase = true) -> MatchConfidence.MEDIUM
            frames.isNotEmpty() -> MatchConfidence.LOW
            else -> null
        }
    }

    override fun newSession(env: DriverEnv): PassiveSession = object : SimplePassiveSession(null) {
        override fun parse(adv: AdvertisementInfo, env: DriverEnv): ScaleSample? {
            for ((key, data) in adv.manufacturerData) {
                if (data.size < 9) continue
                val flag = key and 0xFF
                if (flag != FLAG_MEASURING && flag != FLAG_STABLE) continue
                val kg = weightKg(key, data)
                if (kg < 0.5f || kg > 300f) continue
                if (flag == FLAG_MEASURING) {
                    liveWeight(kg)
                    return null
                }
                return ScaleSample().apply { weightKg = kg }
            }
            return null
        }
    }

    companion object {
        const val FLAG_MEASURING = 0xD0
        const val FLAG_STABLE = 0xE0

        fun weightKg(key: Int, data: ByteArray): Float = (((key shr 8) shl 8) or Bytes.u8(data, 0)) / 100.0f

        /** Bytes 3..8 repeat the device address (either byte order). */
        fun macEcho(data: ByteArray, address: String): Boolean {
            if (data.size < 9) return false
            val mac = address.split(":").mapNotNull { it.toIntOrNull(16) }
            if (mac.size != 6) return false
            val echo = (3..8).map { Bytes.u8(data, it) }
            return echo == mac || echo == mac.reversed()
        }
    }
}

internal fun String.lowerRoot(): String = lowercase(Locale.ROOT)

/**
 * PICOOC Mini Lite ("PICOOC-L"): 16-byte manufacturer value (Android splits the first two bytes,
 * part of the embedded MAC, as company id) with state (0x39 = completed), weight BE ×0.05 kg,
 * impedance BE ×0.1 Ω (0xFFFF = none) and a one's-complement checksum.
 *
 * openScale derives composition with PICOOC's own model plus a per-user calibration learner;
 * that model is not ported here, so composition uses the standard impedance formulas.
 */
class PicoocMiniLiteDriver : PassiveScaleDriver() {
    override val id = "picooc_mini_lite"
    override val displayName = "PICOOC Mini Lite"
    override val brand = "PICOOC"
    override val capabilities = ScaleCapabilities(impedance = true)
    override val formulaLib = FormulaLib.STANDARD_IMPEDANCE
    override val keywords = listOf("PICOOC-L")

    override fun match(adv: AdvertisementInfo): MatchConfidence? =
        if (adv.nameOrEmpty.equals("PICOOC-L", ignoreCase = true)) MatchConfidence.HIGH else null

    override fun newSession(env: DriverEnv): PassiveSession = object : SimplePassiveSession(FormulaLib.STANDARD_IMPEDANCE) {
        private var armed = true

        override fun parse(adv: AdvertisementInfo, env: DriverEnv): ScaleSample? {
            val f = adv.manufacturerData.entries.firstNotNullOfOrNull { (id, data) -> parseFrame(id, data) } ?: return null
            if (f.state != COMPLETE_STATE) {
                armed = true
                liveWeight(f.weightKg)
                return null
            }
            if (!armed || f.weightKg !in 0.5f..300f) return null
            armed = false
            return ScaleSample().apply {
                weightKg = f.weightKg
                f.impedanceOhm?.takeIf { it >= 50f }?.let { impedance = it }
            }
        }
    }

    data class Frame(val state: Int, val weightKg: Float, val impedanceOhm: Float?)

    companion object {
        const val COMPLETE_STATE = 0x39

        fun parseFrame(manufacturerId: Int, payload: ByteArray?): Frame? {
            if (payload == null || payload.size != 14) return null
            val mfr = byteArrayOf(manufacturerId.toByte(), (manufacturerId ushr 8).toByte()) + payload
            if (Bytes.sum8(mfr) != 0xFF) return null
            val impedanceRaw = Bytes.fromUnsignedInt16Be(mfr, 10)
            return Frame(
                state = Bytes.u8(mfr, 6),
                weightKg = Bytes.fromUnsignedInt16Be(mfr, 8) / 20.0f,
                impedanceOhm = if (impedanceRaw == 0xFFFF) null else impedanceRaw / 10.0f,
            )
        }
    }
}
