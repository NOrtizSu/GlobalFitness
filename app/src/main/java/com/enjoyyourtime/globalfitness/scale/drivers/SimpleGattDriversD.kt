// Protocol ported from openScale (GPL-3.0), https://github.com/oliexdev/openScale
package com.enjoyyourtime.globalfitness.scale.drivers

import com.enjoyyourtime.globalfitness.scale.AdvertisementInfo
import com.enjoyyourtime.globalfitness.scale.Bytes
import com.enjoyyourtime.globalfitness.scale.Cancellable
import com.enjoyyourtime.globalfitness.scale.FormulaLib
import com.enjoyyourtime.globalfitness.scale.GattProtocol
import com.enjoyyourtime.globalfitness.scale.GattScaleDriver
import com.enjoyyourtime.globalfitness.scale.MatchConfidence
import com.enjoyyourtime.globalfitness.scale.ScaleCapabilities
import com.enjoyyourtime.globalfitness.scale.ScaleSample
import com.enjoyyourtime.globalfitness.scale.ScaleUnit
import com.enjoyyourtime.globalfitness.scale.libs.StandardImpedanceLib
import com.enjoyyourtime.globalfitness.scale.libs.Wla25BodyComposition
import com.enjoyyourtime.globalfitness.scale.uuid16
import java.time.Instant
import java.time.ZoneId
import java.util.Locale
import java.util.TimeZone
import java.util.UUID
import kotlin.math.abs

private fun unitToKg(value: Float, unit: ScaleUnit): Float = when (unit) {
    ScaleUnit.KG -> value
    ScaleUnit.LB -> value * 0.45359237f
    ScaleUnit.ST -> value * 6.35029318f
}

/** Writes the ICOMON profile/clock/unit config sequence used by the FFB0 "AC 02" family. */
private fun GattProtocol.icomonHandshake(write: (ByteArray) -> Unit, isMale: Boolean, age: Int, heightCm: Int, unitCode: Int, nowMillis: Long) {
    write(icomonConfig(0xF7, 0, 0, 0))
    write(icomonConfig(0xFA, 0, 0, 0))
    write(icomonConfig(0xFB, if (isMale) 1 else 2, age, heightCm))
    val t = Instant.ofEpochMilli(nowMillis).atZone(ZoneId.systemDefault())
    write(icomonConfig(0xFD, (t.year - 2000).coerceIn(0, 99), t.monthValue, t.dayOfMonth))
    write(icomonConfig(0xFC, t.hour, t.minute, t.second))
    write(icomonConfig(0xFE, 6, unitCode, 0))
}

/**
 * FitTrack Dara 2.0 (FFB0, "AC 02" framing): live/stable weight frames (0xCE/0xCA) and an
 * index/value body-composition dump (0xCB) after the reading locks; weight-only after a grace time.
 */
class FitTrackDaraDriver : GattScaleDriver() {
    override val id = "fittrack_dara"
    override val displayName = "FitTrack Dara 2.0"
    override val brand = "FitTrack"
    override val capabilities = ScaleCapabilities(deviceComposition = true)

    override fun match(adv: AdvertisementInfo): MatchConfidence? = if (adv.upperName.startsWith("FITTRACK")) MatchConfidence.HIGH else null

    override fun newProtocol(adv: AdvertisementInfo?): GattProtocol = object : GattProtocol() {
        private val svc = uuid16(0xFFB0)
        private val chrCfg = uuid16(0xFFB1)
        private val chrData = uuid16(0xFFB2)
        private var pendingKg = 0f
        private var stableCount = 0
        private var lastDeci = -1
        private var published = false
        private var grace: Cancellable? = null
        private val pending = ScaleSample()

        override fun onConnected() {
            setNotifyOn(svc, chrData)
            icomonHandshake({ writeTo(svc, chrCfg, it, withResponse = false) }, user.isMale, user.ageAt(env.wallClock()).coerceIn(0, 255), user.heightCm.toInt().coerceIn(0, 255), user.unit.ordinal, env.wallClock())
            userInfo("Súbete a la báscula")
        }

        override fun onDisconnected() {
            grace?.cancel()
            if (!published) publishFinal()
        }

        override fun onNotification(characteristic: UUID, data: ByteArray) {
            if (characteristic != chrData) return
            if (data.size >= 20 && Bytes.u8(data, 0) == 0x01 && Bytes.u8(data, 1) == 0x00) {
                publishFinal()
                return
            }
            if (data.size < 8 || Bytes.u8(data, 0) != 0xAC) return
            val b2 = Bytes.u8(data, 2)
            if (data.size >= 20 && b2 == 0xFF) {
                for ((i, off) in listOf(0 to 12, 1 to 14, 2 to 16, 3 to 18)) applyIndex(i, Bytes.fromUnsignedInt16Be(data, off))
                armGrace()
                return
            }
            if (data.size == 8 && Bytes.sum8(data, 2, 7) != Bytes.u8(data, 7)) return
            when (Bytes.u8(data, 6)) {
                0xCE -> weightFrame(data, stable = false)
                0xCA -> weightFrame(data, stable = true)
                0xCB -> if (b2 == 0xFE) applyIndex(Bytes.u8(data, 3), Bytes.fromUnsignedInt16Be(data, 4))
            }
        }

        private fun weightFrame(d: ByteArray, stable: Boolean) {
            val deci = Bytes.fromUnsignedInt16Be(d, 2)
            if (deci <= 0 || deci >= 6000) return
            pendingKg = unitToKg(deci / 10.0f, user.unit)
            live(pendingKg)
            stableCount = if (deci == lastDeci) stableCount + 1 else 1
            lastDeci = deci
            if (stable || stableCount >= 4) armGrace()
        }

        private fun armGrace() {
            if (grace != null || published) return
            grace = schedule(10_000) { if (!published && pendingKg > 0f) publishFinal() }
        }

        private fun applyIndex(idx: Int, v: Int) {
            when (idx) {
                0 -> pending.weightKg = unitToKg(v / 10.0f, user.unit)
                2 -> pending.fatPercent = v / 10.0f
                4 -> pending.visceralFat = v.toFloat()
                5 -> pending.lbmKg = v / 10.0f
                7 -> pending.boneKg = v / 10.0f
                8 -> pending.waterPercent = v / 10.0f
                10 -> pending.proteinPercent = v / 10.0f
            }
        }

        private fun publishFinal() {
            if (published) return
            val kg = pending.weightKg?.takeIf { it > 0f } ?: pendingKg
            if (kg <= 0f) return
            published = true
            grace?.cancel()
            pending.weightKg = kg
            publish(pending)
            requestDisconnect()
        }
    }
}

/**
 * Hume Health "Dara 2.0" (LeFu chip, FFF0/FFF4): 11-byte 0xCF frames, impedance BE ÷100 at 1,
 * weight LE ÷100 at 3, XOR checksum at 10; the locked frame (bytes 1,2,5,6,7 populated) is final.
 */
class HumeDara2Driver : GattScaleDriver() {
    override val id = "hume_dara2"
    override val displayName = "Hume Health Dara 2.0"
    override val brand = "Hume Health"
    override val capabilities = ScaleCapabilities(impedance = true)
    override val formulaLib = FormulaLib.STANDARD_IMPEDANCE

    override fun match(adv: AdvertisementInfo): MatchConfidence? =
        if (adv.nameOrEmpty.equals("Dara 2.0", ignoreCase = true)) MatchConfidence.HIGH else null

    override fun newProtocol(adv: AdvertisementInfo?): GattProtocol = object : GattProtocol() {
        private val svc = uuid16(0xFFF0)
        private val chrWrite = uuid16(0xFFF1)
        private val chrNotify = uuid16(0xFFF4)
        private var lastFrame: ByteArray? = null

        override fun onConnected() {
            writeTo(svc, chrWrite, ExcelvanCF36xDriver.userConfig(user.isMale, user.activityLevel, user.heightCm, user.ageAt(env.wallClock()), user.unit))
            setNotifyOn(svc, chrNotify)
            userInfo("Súbete a la báscula")
        }

        override fun onNotification(characteristic: UUID, data: ByteArray) {
            if (characteristic != chrNotify || data.size != 11 || data[0] != 0xCF.toByte()) return
            if (Bytes.u8(data, 10) != Bytes.xor(data, 0, 10)) return
            if (!isLocked(data)) {
                live(unitToKg(Bytes.fromUnsignedInt16Le(data, 3) / 100.0f, user.unit))
                return
            }
            if (lastFrame?.contentEquals(data) == true) return
            lastFrame = data.copyOf()
            publish(parse(data, user.unit))
            requestDisconnect()
        }
    }

    companion object {
        val PLAUSIBLE_IMPEDANCE = 300.0..900.0

        fun isLocked(f: ByteArray): Boolean = (f[1].toInt() or f[2].toInt() or f[5].toInt() or f[6].toInt() or f[7].toInt()) != 0

        fun parse(f: ByteArray, unit: ScaleUnit) = ScaleSample().apply {
            weightKg = unitToKg(Bytes.fromUnsignedInt16Le(f, 3) / 100.0f, unit)
            val ohm = Bytes.fromUnsignedInt16Be(f, 1) / 100.0
            if (ohm in PLAUSIBLE_IMPEDANCE) impedance = ohm.toFloat()
        }
    }
}

/**
 * Relaxmedic (Fitdays, icomon protocol 107; FFB0 20-byte fragmented frames): generated 0xBA
 * profile, final 0xA3 result with weight and ten impedances, composition with the WLA25 algorithm.
 */
class RelaxmedicDriver : GattScaleDriver() {
    override val id = "relaxmedic"
    override val displayName = "Relaxmedic"
    override val brand = "Relaxmedic"
    override val capabilities = ScaleCapabilities(deviceComposition = true, userRegistration = true)
    override val keywords = listOf("Fitdays", "icomon")

    override fun match(adv: AdvertisementInfo): MatchConfidence? {
        val n = adv.nameOrEmpty.lowercase(Locale.ROOT)
        if (n.startsWith("swan") || n == "icomon" || n == "yg") return null
        return if (n.startsWith("relaxmedic")) MatchConfidence.HIGH else null
    }

    override fun newProtocol(adv: AdvertisementInfo?): GattProtocol = Protocol()

    internal class Protocol : GattProtocol() {
        private val svc = uuid16(0xFFB0)
        private val chrWrite = uuid16(0xFFB1)
        private val chrLive = uuid16(0xFFB2)
        private val chrResult = uuid16(0xFFB3)
        private var outgoingSeq = 0
        private var lastPublishedGrams: Int? = null
        private var stream = ByteArray(0)
        private var expectedLength = 0

        override fun onConnected() {
            setNotifyOn(svc, chrLive)
            setNotifyOn(svc, chrResult)
            sendUserProfile()
            userInfo("Súbete a la báscula")
        }

        override fun onNotification(characteristic: UUID, data: ByteArray) {
            if (data.size != 20 || !Ffb0Frames.isValid(data)) return
            val message = reassemble(data) ?: return
            val payload = message.copyOfRange(1, message.size)
            val seq = Bytes.u8(data, 0)
            when (Bytes.u8(message, 0)) {
                0xA2 -> if (payload.size >= 5) live((Bytes.fromUnsignedInt32Be(payload, 1).toInt() and WEIGHT_MASK) / 1000.0f)
                0xA3 -> finalResult(payload, seq)
                0xA0 -> sendAck(seq)
            }
        }

        private fun reassemble(frame: ByteArray): ByteArray? {
            val length = Bytes.u8(frame, 1)
            val chunk = frame.copyOfRange(3, 19)
            if (Bytes.u8(frame, 2) == 0) {
                stream = chunk
                expectedLength = length
            } else {
                if (stream.isEmpty()) return null
                stream += chunk
            }
            if (stream.size < expectedLength + 1) return null
            val message = stream.copyOfRange(0, expectedLength + 1)
            stream = ByteArray(0)
            expectedLength = 0
            return message
        }

        private fun finalResult(payload: ByteArray, seq: Int) {
            if (payload.size < 5) return
            val grams = Bytes.fromUnsignedInt32Be(payload, 0).toInt() and WEIGHT_MASK
            if (lastPublishedGrams == grams) {
                sendAck(seq)
                return
            }
            val imps = DoubleArray((payload.size - 5) / 2) { i -> Bytes.fromUnsignedInt16Be(payload, 5 + 2 * i) / 10.0 }
            val sample = ScaleSample().apply { weightKg = grams / 1000.0f }
            Wla25BodyComposition.compute(user.heightCm.toInt(), grams / 1000.0, imps)?.let { r ->
                sample.weightKg = r.weightKg
                sample.fatPercent = r.fat
                sample.waterPercent = r.water
                sample.musclePercent = r.musclePercent
                sample.muscleKg = r.muscleKg
                sample.boneKg = r.boneKg
                sample.visceralFat = r.visceralFat.toFloat()
                sample.proteinPercent = r.protein
                sample.bmrKcal = r.bmrKcal.toFloat()
                sample.lbmKg = r.lbmKg
                Wla25BodyComposition.bodyAge(
                    user.ageAt(env.wallClock()),
                    r.fat.toDouble(),
                    if (user.isMale) Wla25BodyComposition.SEX_MALE else Wla25BodyComposition.SEX_FEMALE,
                ).takeIf { it in 10..99 }?.let { sample.metabolicAge = it.toFloat() }
                sample.computedWith = FormulaLib.WLA25
            }
            publish(sample)
            lastPublishedGrams = grams
            sendAck(seq)
        }

        private fun sendUserProfile() {
            val now = env.wallClock()
            val offset = TimeZone.getDefault().getOffset(now) / 60000
            val encodedOffset = (abs(offset) and 0x7FFF).let { if (offset < 0) it or 0x8000 else it }
            val weight = ((user.lastWeightKg?.takeIf { it > 0f } ?: 60f) * 100f).toInt()
            val flags1 = ((if (user.isMale) 1 else 0) shl 7) or (user.ageAt(now) and 0x7F)
            val message = byteArrayOf(0xBA.toByte()) + Bytes.toInt32Be(now / 1000L) + Bytes.toInt16Be(encodedOffset) +
                Bytes.toInt32Be(0L) + byteArrayOf(user.heightCm.toInt().toByte()) + Bytes.toInt16Be(weight) +
                byteArrayOf(flags1.toByte(), FLAGS2.toByte(), TRAILING.toByte())
            writeTo(svc, chrWrite, buildFrame(message))
        }

        private fun sendAck(seq: Int) = writeTo(svc, chrWrite, buildFrame(byteArrayOf(0xB0.toByte(), seq.toByte(), 0x00)))

        private fun buildFrame(message: ByteArray): ByteArray {
            val frame = ByteArray(20)
            frame[0] = outgoingSeq.toByte()
            outgoingSeq = (outgoingSeq + 1) and 0xFF
            frame[1] = (message.size - 1).toByte()
            message.copyInto(frame, 3, 0, minOf(message.size, 16))
            frame[19] = Ffb0Frames.checksum(frame).toByte()
            return frame
        }

        companion object {
            const val WEIGHT_MASK = 0x3FFFF
            const val FLAGS2 = 0x2F
            const val TRAILING = 0x0F
        }
    }
}

/**
 * Dr. Trust SSW532 / FG2211 (FFB0, 8 electrodes): session setup on FFB3, profile frames, weight
 * on FFB2, final measurement frames with segment impedances (sum used as whole-body value).
 */
class DrTrustSSW532Driver : GattScaleDriver() {
    override val id = "drtrust_ssw532"
    override val displayName = "Dr. Trust SSW532"
    override val brand = "Dr. Trust"
    override val capabilities = ScaleCapabilities(impedance = true)
    override val formulaLib = FormulaLib.STANDARD_IMPEDANCE
    override val keywords = listOf("FG2211", "SSW")

    override fun match(adv: AdvertisementInfo): MatchConfidence? {
        val n = adv.nameOrEmpty.lowercase(Locale.ROOT)
        val nameMatch = n == "ssw532" || n.startsWith("ssw") || n.contains("fg2211")
        return if (nameMatch && adv.hasService16(0xFFB0)) MatchConfidence.HIGH else null
    }

    override fun newProtocol(adv: AdvertisementInfo?): GattProtocol = Protocol()

    internal class Protocol : GattProtocol() {
        private enum class State { WAITING_SESSION, WAITING_CONFIRM, MEASURING }

        private val svc = uuid16(0xFFB0)
        private val chrCmd = uuid16(0xFFB1)
        private val chrWeight = uuid16(0xFFB2)
        private val chrBc = uuid16(0xFFB3)
        private var state = State.WAITING_SESSION
        private var sessionId = 0
        private var pendingKg = 0f
        private var savedKg = 0f
        private var published = false
        private var pkt0Valid = false
        private var z3 = 0.0
        private var z4 = 0.0
        private var z5 = 0.0
        private var gotImpedance = false
        private var weightLocked = false
        private var liveMeasurement = false
        private var fallback: Cancellable? = null

        override fun onConnected() {
            setNotifyOn(svc, chrBc)
        }

        override fun onDisconnected() {
            fallback?.cancel()
            if (!published) {
                val kg = when {
                    savedKg > 0f -> savedKg
                    weightLocked && pendingKg > 0f -> pendingKg
                    else -> 0f
                }
                if (kg > 0f) publish(ScaleSample().apply { weightKg = kg })
            }
        }

        override fun onNotification(characteristic: UUID, data: ByteArray) {
            when (characteristic) {
                chrBc -> if (data.size >= 20) {
                    when (Bytes.u8(data, 1)) {
                        0x18 -> setupFrame(data)
                        0x23 -> if (state == State.MEASURING) measurementFrame(data)
                    }
                }
                chrWeight -> weightFrame(data)
            }
        }

        private fun setupFrame(d: ByteArray) {
            when (Bytes.u8(d, 2)) {
                0x00 -> {
                    sessionId = Bytes.u8(d, 0)
                    setNotifyOn(svc, chrWeight)
                    state = State.WAITING_CONFIRM
                }
                0x01 -> {
                    sendUserProfile()
                    userInfo("Súbete a la báscula")
                    state = State.MEASURING
                }
            }
        }

        private fun weightFrame(d: ByteArray) {
            if (d.size < 9 || Bytes.u8(d, 1) != 0x07 || Bytes.u8(d, 3) != 0xA2) return
            val kg = Bytes.fromUnsignedInt24Be(d, 6) / 1000.0f
            if (kg > 0f && Bytes.u8(d, 4) == 0x03) {
                pendingKg = kg
                weightLocked = true
            } else if (kg < 2.0f && savedKg > 0f && !published) {
                published = true
                publish(ScaleSample().apply { weightKg = savedKg })
                savedKg = 0f
                teardownAck()
                requestDisconnect()
            } else if (kg > 0f) {
                live(kg)
            }
        }

        private fun measurementFrame(d: ByteArray) {
            when (Bytes.u8(d, 2)) {
                0x00 -> {
                    val cmd = Bytes.u8(d, 3)
                    liveMeasurement = cmd == 0xA3 || cmd == 0xA7
                    if (!liveMeasurement) {
                        pkt0Valid = false
                        return
                    }
                    pkt0Valid = Bytes.u8(d, 14) == 0x01
                    if (!pkt0Valid) return
                    val kg = Bytes.fromUnsignedInt24Be(d, 9) / 1000.0f
                    if (kg > 0f) pendingKg = kg
                }
                0x01 -> {
                    if (!pkt0Valid || !liveMeasurement) return
                    z3 = Bytes.fromUnsignedInt16Le(d, 7) / 10.0
                    z4 = Bytes.fromUnsignedInt16Le(d, 9) / 10.0
                    z5 = Bytes.fromUnsignedInt16Le(d, 11) / 10.0
                    gotImpedance = true
                }
                0x02 -> {
                    if (!weightLocked || !liveMeasurement) {
                        teardownAck()
                    } else if (pendingKg > 0f) {
                        if (pkt0Valid && gotImpedance) {
                            publishWithComposition()
                        } else {
                            savedKg = pendingKg
                            fallback?.cancel()
                            fallback = schedule(5_000) {
                                if (!published && savedKg > 0f) {
                                    published = true
                                    publish(ScaleSample().apply { weightKg = savedKg })
                                    savedKg = 0f
                                    teardownAck()
                                    requestDisconnect()
                                }
                            }
                        }
                    }
                    resetMeasurement()
                }
            }
        }

        private fun publishWithComposition() {
            fallback?.cancel()
            val wholeBody = z3 + z4 + z5
            val age = user.ageAt(env.wallClock())
            val lib = StandardImpedanceLib(user.isMale, age, pendingKg.toDouble(), user.heightCm / 100.0, wholeBody)
            val fat = lib.totalFatPercentage.toFloat()
            if (fat <= 0f) {
                savedKg = pendingKg
                requestDisconnect()
                return
            }
            savedKg = 0f
            published = true
            val trunkLoad = (pendingKg / z3).toFloat()
            publish(ScaleSample().apply {
                weightKg = pendingKg
                fatPercent = fat.coerceIn(0f, 75f)
                waterPercent = lib.totalBodyWaterPercentage.toFloat().coerceIn(0f, 80f)
                musclePercent = lib.skeletalMusclePercentage.toFloat().coerceIn(0f, 99f)
                boneKg = lib.boneMassKg.toFloat().coerceIn(0f, 10f)
                bmrKcal = lib.basalMetabolicRate.toFloat().coerceIn(0f, 5000f)
                lbmKg = lib.fatFreeMassKg.toFloat().coerceIn(0f, 150f)
                visceralFat = (2.87f * trunkLoad + 0.01f * age + if (user.isMale) 0f else -1f).coerceIn(1f, 25f)
                impedance = wholeBody.toFloat()
                computedWith = FormulaLib.STANDARD_IMPEDANCE
            })
            teardownAck()
            requestDisconnect()
        }

        private fun resetMeasurement() {
            pendingKg = 0f
            pkt0Valid = false
            z3 = 0.0
            z4 = 0.0
            z5 = 0.0
            gotImpedance = false
            liveMeasurement = false
        }

        private fun sendUserProfile() {
            val ts = (env.wallClock() / 1000L).toInt()
            writeCmd(withChecksum(byteArrayOf(0x00, 0x03, 0x00, 0xB0.toByte(), sessionId.toByte()) + ByteArray(15)))
            val h = user.heightCm.toInt().coerceIn(100, 220)
            val age = user.ageAt(env.wallClock()).coerceIn(0, 127)
            writeCmd(
                withChecksum(
                    byteArrayOf(
                        0x01, 0x1A, 0x00, 0xB8.toByte(), (ts shr 24).toByte(), (ts shr 16).toByte(), (ts shr 8).toByte(), ts.toByte(),
                        0x01, 0x4A, 0x01, h.toByte(), 0x17, 0x70, (0x80 or age).toByte(), 0x13, 0x88.toByte(), 0x0F, 0x00, 0x00,
                    ),
                ),
            )
            writeCmd(withChecksum(byteArrayOf(0x01, 0x1A, 0x01, 0x00, 0x00, 0x00, 0x06) + "icomon".toByteArray(Charsets.US_ASCII) + ByteArray(7)))
        }

        private fun teardownAck() {
            writeCmd(withChecksum(byteArrayOf(0x04, 0x03, 0x00, 0xB0.toByte(), sessionId.toByte()) + ByteArray(15)))
        }

        private fun withChecksum(buf: ByteArray): ByteArray = buf.also { it[19] = (Bytes.sum8(it, 3, 19) % 32).toByte() }

        private fun writeCmd(payload: ByteArray) = writeTo(svc, chrCmd, payload)
    }
}

/**
 * Taylor 5331891 BIA and ALPHA Smart Scale PRO 3 ("MY_SCALE", FFB0): `AC 27` 20-byte frames with
 * live weight (D5), final result with impedance (D6) and stored records (D8, acked on the PRO 3).
 */
class TaylorBIADriver : GattScaleDriver() {
    enum class Model { TAYLOR_BIA, ALPHA_PRO3 }

    override val id = "taylor_bia"
    override val displayName = "Taylor 5331891 BIA · ALPHA Smart Scale PRO 3"
    override val brand = "Taylor / ALPHA"
    override val capabilities = ScaleCapabilities(impedance = true, history = true)
    override val formulaLib = FormulaLib.STANDARD_IMPEDANCE
    override val keywords = listOf("5331891", "BIA SCALE", "MY_SCALE", "PRO 3")

    override fun match(adv: AdvertisementInfo): MatchConfidence? {
        val model = modelFor(adv.nameOrEmpty) ?: return null
        if (model == Model.ALPHA_PRO3 && !adv.hasService16(0xFFB0)) return null
        return MatchConfidence.HIGH
    }

    override fun newProtocol(adv: AdvertisementInfo?): GattProtocol = Protocol(modelFor(adv?.nameOrEmpty.orEmpty()) ?: Model.TAYLOR_BIA)

    internal class Protocol(private val model: Model) : GattProtocol() {
        private val svc = uuid16(0xFFB0)
        private val chrCfg = uuid16(0xFFB1)
        private val chrData = uuid16(0xFFB2)
        private var pendingGrams = 0
        private var stableCount = 0
        private var published = false
        private var fallback: Cancellable? = null

        override fun onConnected() {
            setNotifyOn(svc, chrData)
            val now = env.wallClock()
            when (model) {
                Model.TAYLOR_BIA -> icomonHandshake({ writeTo(svc, chrCfg, it) }, user.isMale, user.ageAt(now), user.heightCm.toInt().coerceAtLeast(0), user.unit.ordinal, now)
                Model.ALPHA_PRO3 -> {
                    val profile = buildProfile(now / 1000L, user.heightCm.toInt(), user.ageAt(now), if (user.isMale) SEX_MALE else SEX_FEMALE)
                    writeTo(svc, chrCfg, profile)
                    writeTo(svc, chrCfg, buildPhoneFrame(byteArrayOf(0x01) + ByteArray(15), WIRE_CONTROL))
                    writeTo(svc, chrCfg, profile)
                }
            }
            userInfo("Súbete a la báscula")
        }

        override fun onDisconnected() {
            fallback?.cancel()
            if (model == Model.TAYLOR_BIA && !published && pendingGrams > 0) publishFinal(pendingGrams, 0)
        }

        override fun onNotification(characteristic: UUID, data: ByteArray) {
            if (characteristic != chrData) return
            when (val f = parse(data)) {
                is Frame.LiveWeight -> liveWeight(f)
                is Frame.FinalResult -> {
                    ack(WIRE_FINAL_RESULT)
                    if (model == Model.TAYLOR_BIA) {
                        if (publishFinal(f.weightGrams, f.impedanceOhms)) requestDisconnect()
                    } else if (!published && publishFinal(f.weightGrams, f.impedanceOhms)) {
                        schedule(1_000) { requestDisconnect() }
                    }
                }
                is Frame.StoredRecord -> {
                    ack(WIRE_STORED_RECORD)
                    if (f.weightGrams > 0) {
                        publish(ScaleSample().apply {
                            weightKg = f.weightGrams / 1000f
                            dateTime = f.timestampEpochSeconds * 1000L
                            fromHistory = true
                        })
                    }
                }
                null -> Unit
            }
        }

        private fun liveWeight(f: Frame.LiveWeight) {
            if (f.weightGrams > 0) live(f.weightGrams / 1000f)
            if (model == Model.ALPHA_PRO3) {
                pendingGrams = f.weightGrams
                return
            }
            if (f.weightGrams <= 0) return
            stableCount = if (f.weightGrams == pendingGrams) stableCount + 1 else 1
            pendingGrams = f.weightGrams
            if (f.locked || stableCount >= 4) {
                if (publishFinal(f.weightGrams, 0)) requestDisconnect()
            } else if (fallback == null && !published) {
                fallback = schedule(8_000) { if (!published && pendingGrams > 0 && publishFinal(pendingGrams, 0)) requestDisconnect() }
            }
        }

        private fun publishFinal(grams: Int, impedanceOhms: Int): Boolean {
            if (published || grams <= 0) return false
            published = true
            fallback?.cancel()
            publish(ScaleSample().apply {
                weightKg = grams / 1000f
                if (impedanceOhms > 0) impedance = impedanceOhms.toFloat()
            })
            return true
        }

        private fun ack(wireType: Int) {
            if (model == Model.ALPHA_PRO3) writeTo(svc, chrCfg, buildPhoneFrame(byteArrayOf(0x04, wireType.toByte()) + ByteArray(14), WIRE_CONTROL))
        }
    }

    sealed interface Frame {
        data class LiveWeight(val weightGrams: Int, val locked: Boolean) : Frame
        data class FinalResult(val weightGrams: Int, val impedanceOhms: Int, val locked: Boolean) : Frame
        data class StoredRecord(val timestampEpochSeconds: Long, val weightGrams: Int) : Frame
    }

    companion object {
        const val WIRE_LIVE_WEIGHT = 0xD5
        const val WIRE_FINAL_RESULT = 0xD6
        const val WIRE_STORED_RECORD = 0xD8
        const val WIRE_PROFILE = 0xD0
        const val WIRE_CONTROL = 0xDF
        const val SEX_MALE = 1
        const val SEX_FEMALE = 2

        fun modelFor(name: String): Model? {
            val n = name.trim().uppercase(Locale.ROOT)
            return when {
                n == "MY_SCALE" -> Model.ALPHA_PRO3
                n.startsWith("5331891") || n.contains("BIA SCALE") -> Model.TAYLOR_BIA
                else -> null
            }
        }

        private fun weightGrams(raw: ByteArray, at: Int): Int = Bytes.fromUnsignedInt24Be(raw, at) and 0x3FFFF

        fun parse(raw: ByteArray): Frame? {
            if (raw.size != 20 || Bytes.u8(raw, 0) != 0xAC || Bytes.u8(raw, 1) != 0x27) return null
            if (Bytes.u8(raw, 19) and 0x1F != Bytes.sum8(raw, 2, 19) and 0x1F) return null
            return when (Bytes.u8(raw, 18)) {
                WIRE_LIVE_WEIGHT -> Frame.LiveWeight(weightGrams(raw, 3), Bytes.u8(raw, 2) and 0x80 != 0)
                WIRE_FINAL_RESULT -> Frame.FinalResult(weightGrams(raw, 8), Bytes.fromUnsignedInt16Be(raw, 4), Bytes.u8(raw, 7) and 0x80 != 0)
                WIRE_STORED_RECORD -> Frame.StoredRecord(Bytes.fromUnsignedInt32Be(raw, 3), weightGrams(raw, 8))
                else -> null
            }
        }

        fun buildProfile(nowEpochSeconds: Long, heightCm: Int, age: Int, sex: Int): ByteArray {
            val body = ByteArray(16)
            Bytes.toInt32Be(body, 0, nowEpochSeconds)
            body[4] = 0x08
            body[7] = heightCm.coerceIn(0, 255).toByte()
            body[10] = age.coerceIn(0, 255).toByte()
            body[11] = sex.toByte()
            body[14] = 0x03
            return buildPhoneFrame(body, WIRE_PROFILE)
        }

        fun buildPhoneFrame(body: ByteArray, wireType: Int): ByteArray {
            require(body.size == 16)
            val f = ByteArray(20)
            f[0] = 0xAC.toByte()
            f[1] = 0x27
            body.copyInto(f, 2)
            f[18] = wireType.toByte()
            f[19] = Bytes.sum8(f, 2, 19).toByte()
            return f
        }
    }
}

/**
 * SWAN / Icomon / "YG" (MGB protocol, FFB0): ICOMON config frames; either a two-frame composition
 * result (AC 02 FF … + 01 00 …) or 8-byte streaming frames with final weight (CA) and impedance (CB).
 * Also the generic fallback for scales advertising service FFB0.
 */
class MgbDriver : GattScaleDriver() {
    override val id = "mgb_swan_icomon"
    override val displayName = "SWAN / Icomon (protocolo MGB)"
    override val brand = "SWAN / Icomon"
    override val capabilities = ScaleCapabilities(impedance = true, deviceComposition = true)
    override val formulaLib = FormulaLib.STANDARD_IMPEDANCE
    override val keywords = listOf("SWAN", "Icomon", "YG", "FFB0", "MGB")

    override fun match(adv: AdvertisementInfo): MatchConfidence? {
        val n = adv.nameOrEmpty.lowercase(Locale.ROOT)
        return when {
            n.startsWith("swan") || n == "icomon" || n == "yg" -> MatchConfidence.HIGH
            adv.hasService16(0xFFB0) -> MatchConfidence.LOW
            else -> null
        }
    }

    override fun newProtocol(adv: AdvertisementInfo?): GattProtocol = object : GattProtocol() {
        private val svc = uuid16(0xFFB0)
        private val chrCfg = uuid16(0xFFB1)
        private val chrCtrl = uuid16(0xFFB2)
        private var pending: ScaleSample? = null
        private var streamingWeightRaw: Int? = null
        private var streamingImpedance: Int? = null
        private var streamingPublished = false

        override fun onConnected() {
            setNotifyOn(svc, chrCtrl)
            val now = env.wallClock()
            icomonHandshake({ writeTo(svc, chrCfg, it) }, user.isMale, user.ageAt(now), user.heightCm.toInt().coerceAtLeast(0), user.unit.ordinal, now)
            userInfo("Súbete a la báscula")
        }

        override fun onNotification(characteristic: UUID, data: ByteArray) {
            if (characteristic != chrCtrl) return
            when (data.size) {
                20 -> compositeFrame(data)
                8 -> {
                    if (streamingPublished) return
                    parseLiveWeightRaw(data)?.let { live(it / 100.0f) }
                    parseFinalWeightRaw(data)?.let {
                        streamingWeightRaw = it
                        tryPublishStreaming()
                        return
                    }
                    parseImpedanceOhm(data)?.let {
                        streamingImpedance = it
                        tryPublishStreaming()
                    }
                }
            }
        }

        private fun compositeFrame(d: ByteArray) {
            val b0 = Bytes.u8(d, 0)
            val b1 = Bytes.u8(d, 1)
            if (b0 == 0xAC && (b1 == 0x02 || b1 == 0x03) && Bytes.u8(d, 2) == 0xFF) {
                pending = ScaleSample().apply {
                    weightKg = Bytes.fromUnsignedInt16Be(d, 12) / 10.0f
                    fatPercent = Bytes.fromUnsignedInt16Be(d, 16) / 10.0f
                }
                return
            }
            if (b0 == 0x01 && b1 == 0x00) {
                val m = pending ?: return
                m.musclePercent = Bytes.fromUnsignedInt16Le(d, 2) / 10.0f
                m.boneKg = Bytes.fromUnsignedInt16Le(d, 6) / 10.0f
                m.waterPercent = Bytes.fromUnsignedInt16Le(d, 8) / 10.0f
                publish(m)
                pending = null
            }
        }

        private fun tryPublishStreaming() {
            val raw = streamingWeightRaw ?: return
            val ohm = streamingImpedance ?: return
            streamingPublished = true
            publish(ScaleSample().apply {
                weightKg = raw / 100.0f
                if (ohm in 1 until 1500) impedance = ohm.toFloat()
            })
        }
    }

    companion object {
        fun isValidStreamingFrame(f: ByteArray): Boolean =
            f.size == 8 && Bytes.u8(f, 0) == 0xAC && Bytes.u8(f, 1) == 0x02 && Bytes.sum8(f, 2, 7) == Bytes.u8(f, 7)

        private fun weightRaw(f: ByteArray, flag: Int): Int? {
            if (!isValidStreamingFrame(f) || Bytes.u8(f, 6) != flag || Bytes.u8(f, 4) != 0 || Bytes.u8(f, 5) != 0) return null
            return Bytes.fromUnsignedInt16Be(f, 2)
        }

        fun parseFinalWeightRaw(f: ByteArray): Int? = weightRaw(f, 0xCA)
        fun parseLiveWeightRaw(f: ByteArray): Int? = weightRaw(f, 0xCE)

        fun parseImpedanceOhm(f: ByteArray): Int? {
            if (!isValidStreamingFrame(f) || Bytes.u8(f, 6) != 0xCB || Bytes.u8(f, 2) != 0xFD || Bytes.u8(f, 3) != 0x01) return null
            return Bytes.fromUnsignedInt16Be(f, 4)
        }
    }
}
