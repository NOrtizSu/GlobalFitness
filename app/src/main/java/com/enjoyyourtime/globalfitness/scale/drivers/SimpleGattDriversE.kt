// Protocol ported from openScale (GPL-3.0), https://github.com/oliexdev/openScale
package com.enjoyyourtime.globalfitness.scale.drivers

import com.enjoyyourtime.globalfitness.scale.ActivityLevel
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
import com.enjoyyourtime.globalfitness.scale.libs.RenphoEs26bbLib
import com.enjoyyourtime.globalfitness.scale.uuid16
import java.util.Locale
import java.util.UUID
import kotlin.math.ceil

/**
 * Active Era BF-06 ("AE BS-06", FFB0, `AC 27` 20-byte packets): configuration with profile and
 * reference weights, stable weight (D5), balance (D0), impedance (D6, WLA07 algorithm) and heart
 * rate (D7). Published once weight (and heart rate, when supported) are known.
 */
class ActiveEraBF06Driver : GattScaleDriver() {
    override val id = "active_era_bf06"
    override val displayName = "Active Era BF-06"
    override val brand = "Active Era"
    override val capabilities = ScaleCapabilities(impedance = true)
    override val formulaLib = FormulaLib.WLA07
    override val keywords = listOf("AE BS-06")

    override fun match(adv: AdvertisementInfo): MatchConfidence? =
        if (adv.nameOrEmpty.equals("AE BS-06", ignoreCase = true)) MatchConfidence.HIGH else null

    override fun newProtocol(adv: AdvertisementInfo?): GattProtocol = object : GattProtocol() {
        private val svc = uuid16(0xFFB0)
        private val chrWrite = uuid16(0xFFB1)
        private val chrNotify = uuid16(0xFFB2)
        private var weightStable = false
        private var stableKg = 0f
        private var supportsHr = false
        private var pending = ScaleSample()

        override fun onConnected() {
            setNotifyOn(svc, chrNotify)
            writeTo(svc, chrWrite, configPacket())
            userInfo("Súbete a la báscula")
        }

        private fun configPacket(): ByteArray {
            val now = env.wallClock()
            val initW = ceil((user.lastWeightKg ?: 0f) * 100.0).toInt().coerceIn(0, 0xFFFF)
            val target = user.goalWeightKg?.takeIf { it > 0f }?.let { ceil(it * 100.0).toInt().coerceIn(0, 0xFFFF) } ?: initW
            val pkt = byteArrayOf(0xAC.toByte(), 0x27) + Bytes.toInt32Be(now / 1000L) + byteArrayOf(
                0x04, user.unit.ordinal.toByte(), 0x01, (user.heightCm.toInt() and 0xFF).toByte(),
            ) + Bytes.toInt16Be(initW) + byteArrayOf((user.ageAt(now) and 0xFF).toByte(), if (user.isMale) 1 else 2) +
                Bytes.toInt16Be(target) + byteArrayOf(0x03, 0x00, 0xD0.toByte(), 0x00)
            pkt[pkt.lastIndex] = Bytes.sum8(pkt, 2, pkt.size - 3).toByte()
            return pkt
        }

        override fun onNotification(characteristic: UUID, data: ByteArray) {
            if (characteristic != chrNotify || data.size != 20 || data[0] != 0xAC.toByte()) return
            when (Bytes.u8(data, 0x12)) {
                0xD5 -> {
                    val flags = Bytes.u8(data, 2)
                    val stable = flags and 0x80 != 0
                    supportsHr = flags and 0x02 != 0
                    val kg = (Bytes.fromUnsignedInt24Be(data, 3) and 0x3FFFF) / 1000.0f
                    if (stable && !weightStable) {
                        weightStable = true
                        stableKg = kg
                        pending.weightKg = kg
                        maybePublish()
                    } else if (!stable) {
                        live(kg)
                    }
                }
                0xD6 -> {
                    if (Bytes.u8(data, 2) != 1) return
                    var imp = Bytes.fromUnsignedInt16Be(data, 4).toDouble()
                    if (imp >= 1500.0) imp = ((imp - 1000.0) + stableKg * 10.0 * -0.4) / 0.6 / 10.0
                    if (imp > 10.0 && stableKg > 0f) {
                        pending.impedance = imp.toFloat()
                        // Algorithm 7 = WLA07 (recomputed from impedance); others: standard formulas.
                        if (Bytes.u8(data, 0x11) != 0x07) pending.impedanceLib = FormulaLib.STANDARD_IMPEDANCE
                    }
                    maybePublish()
                }
                0xD7 -> {
                    pending.heartRate = Bytes.u8(data, 3)
                    maybePublish()
                }
            }
        }

        private fun maybePublish() {
            if (!weightStable) return
            if (supportsHr && (pending.heartRate ?: 0) == 0) return
            publish(pending)
            pending = ScaleSample().apply { weightKg = stableKg }
        }
    }
}

/**
 * ES-CS20M / ES-32MD (Lefu "55 AA" frames on 1A10/2A10-2A11): profile (0x97) → pre-measure
 * history config (0x96) → start (0x90); BIA result (0x18) and publish on the stop frame (0x11).
 */
class ESCS20MDriver : GattScaleDriver() {
    override val id = "es_cs20m"
    override val displayName = "ES-CS20M / ES-32MD"
    override val brand = "Lefu / QN"
    override val capabilities = ScaleCapabilities(impedance = true)
    override val formulaLib = FormulaLib.TRISA_RESISTANCE
    override val keywords = listOf("ES-32MD", "113360_")

    override fun match(adv: AdvertisementInfo): MatchConfidence? {
        val n = adv.nameOrEmpty.lowercase(Locale.ROOT)
        return when {
            n.contains("es-cs20m") || n.contains("es-32md") || n.startsWith("113360_") -> MatchConfidence.HIGH
            adv.serviceUuids.contains(SVC) -> MatchConfidence.MEDIUM
            else -> null
        }
    }

    override fun newProtocol(adv: AdvertisementInfo?): GattProtocol = object : GattProtocol() {
        private val chrCmd = uuid16(0x2A11)
        private val chrResults = uuid16(0x2A10)
        private var phase = PHASE_WAIT_PROFILE_ACK
        private var resistance = 0
        private var lastWeightRaw = 0
        private var fragment: ByteArray? = null
        private var published = false

        override fun onConnected() {
            reset()
            setNotifyOn(SVC, chrResults)
            writeTo(SVC, chrCmd, cmd97())
        }

        override fun onDisconnected() = reset()

        override fun onNotification(characteristic: UUID, data: ByteArray) {
            if (characteristic != chrResults || data.isEmpty()) return
            when (Bytes.u8(data, 0)) {
                0xAD -> fragment = data.copyOfRange(3, data.size)
                0xAF -> {
                    val f = fragment ?: return
                    fragment = null
                    frame(f + data.copyOfRange(3, data.size))
                }
                else -> frame(data)
            }
        }

        private fun frame(d: ByteArray) {
            if (d.size < 3) return
            when (Bytes.u8(d, 2)) {
                0x14 -> if (d.size >= 12) {
                    val raw = Bytes.fromUnsignedInt16Be(d, 8)
                    if (raw > 0) {
                        lastWeightRaw = raw
                        live(raw / 100f)
                    }
                }
                0x17 -> if (phase == PHASE_WAIT_PROFILE_ACK) {
                    writeTo(SVC, chrCmd, cmd96(((user.lastWeightKg ?: 0f).coerceAtLeast(0f) * 100f).toInt()))
                    phase = PHASE_WAIT_HIST_ACK
                }
                0x16 -> if (phase == PHASE_WAIT_HIST_ACK) {
                    if (d.size >= 6 && Bytes.u8(d, 5) == 0x01) writeTo(SVC, chrCmd, frameOf(0x90, byteArrayOf(0x01, 0x00, 0x01, 0x00)))
                    phase = PHASE_MEASURING
                }
                0x19 -> {
                    val interim = if (d.size >= 13) Bytes.fromUnsignedInt16Be(d, 11) else lastWeightRaw
                    writeTo(SVC, chrCmd, frameOf(0x99, byteArrayOf(0x01)))
                    writeTo(SVC, chrCmd, cmd96(interim))
                }
                0x18 -> if (d.size >= 16) {
                    val w = Bytes.fromUnsignedInt16Be(d, 10)
                    val r1 = Bytes.fromUnsignedInt16Be(d, 12)
                    if (r1 > 0) resistance = r1
                    if (w > 0) lastWeightRaw = w
                    val post = cmd96(lastWeightRaw)
                    writeTo(SVC, chrCmd, post)
                    writeTo(SVC, chrCmd, post)
                    phase = PHASE_BIA_DONE
                }
                0x11 -> if (d.size >= 6) {
                    if (Bytes.u8(d, 5) != 0) {
                        if (published) {
                            reset()
                            writeTo(SVC, chrCmd, cmd97())
                        }
                    } else if (!published) {
                        publishMeasurement()
                    }
                }
            }
        }

        private fun publishMeasurement() {
            val kg = lastWeightRaw / 100.0f
            if (kg < 0.5f || kg > 300f) {
                reset()
                return
            }
            val r = resistance
            publish(ScaleSample().apply {
                weightKg = kg
                if (r > 0) impedance = r.toFloat()
            })
            reset()
            published = true
        }

        private fun reset() {
            phase = PHASE_WAIT_PROFILE_ACK
            resistance = 0
            lastWeightRaw = 0
            fragment = null
            published = false
        }

        private fun cmd97(): ByteArray {
            val ts = (env.wallClock() / 1000L).toInt()
            return frameOf(0x97, byteArrayOf(0x01, 0x00, 0x00, (ts ushr 24).toByte(), (ts ushr 16).toByte(), (ts ushr 8).toByte(), ts.toByte(), 0x01, 0x06))
        }

        private fun cmd96(weightRaw: Int): ByteArray {
            val dob = user.birthDate
            val heightMm = (user.heightCm * 10f).toInt()
            val mode = if (user.activityLevel == ActivityLevel.HEAVY || user.activityLevel == ActivityLevel.EXTREME) 0x6A else 0xAA
            val w = weightRaw.coerceIn(0, 0xFFFF)
            return frameOf(
                0x96,
                byteArrayOf(
                    if (user.isMale) 0x11 else 0x21, (dob.year ushr 8).toByte(), dob.year.toByte(), dob.monthValue.toByte(), dob.dayOfMonth.toByte(),
                    (heightMm ushr 8).toByte(), heightMm.toByte(), 0x00, 0x00, (w ushr 8).toByte(), w.toByte(), mode.toByte(), 0x01, 0x05,
                ),
            )
        }
    }

    companion object {
        val SVC: UUID = uuid16(0x1A10)
        private const val PHASE_WAIT_PROFILE_ACK = 0
        private const val PHASE_WAIT_HIST_ACK = 1
        private const val PHASE_MEASURING = 2
        private const val PHASE_BIA_DONE = 3

        /** `55 AA cmd 00 len payload sum8`. */
        fun frameOf(cmd: Int, payload: ByteArray): ByteArray {
            val f = byteArrayOf(0x55, 0xAA.toByte(), cmd.toByte(), 0x00, payload.size.toByte()) + payload + byteArrayOf(0)
            f[f.size - 1] = Bytes.sum8(f, 0, f.size - 1).toByte()
            return f
        }
    }
}

/**
 * RENPHO ES-26BB-B / Elis Solar (R-A012, R-A020): Lefu frames on 1A10; final live frames (0x14)
 * and offline records (0x15, acknowledged), composition with RENPHO's resistance formulas.
 */
class RenphoES26BBDriver : GattScaleDriver() {
    override val id = "renpho_es26bb"
    override val displayName = "RENPHO ES-26BB-B · Elis Solar (R-A012/R-A020)"
    override val brand = "RENPHO"
    override val capabilities = ScaleCapabilities(impedance = true, history = true)
    override val formulaLib = FormulaLib.RENPHO_ES26BB
    override val keywords = listOf("ES-26BB-B", "R-A012", "R-A020", "Elis Solar")

    override fun match(adv: AdvertisementInfo): MatchConfidence? {
        val n = adv.nameOrEmpty.trim()
        return if (n.equals("ES-26BB-B", true) || n.equals("R-A012", true) || n.equals("R-A020", true)) MatchConfidence.HIGH else null
    }

    override fun newProtocol(adv: AdvertisementInfo?): GattProtocol = object : GattProtocol() {
        private val svc = uuid16(0x1A10)
        private val chrNotify = uuid16(0x2A10)
        private val chrWrite = uuid16(0x2A11)

        override fun onConnected() {
            setNotifyOn(svc, chrNotify)
            writeTo(svc, chrWrite, Bytes.fromHex("55AA9000040100000094"))
        }

        override fun onNotification(characteristic: UUID, data: ByteArray) {
            if (characteristic != chrNotify || data.isEmpty()) return
            if (Bytes.sum8(data, 0, data.size - 1) != Bytes.u8(data, data.size - 1)) return
            when (Bytes.u8(data, 2)) {
                0x14 -> parseLiveFrame(data)?.let { (w, r) -> publish(sample(w, r, null)) }
                0x15 -> parseOfflineRecord(data)?.let { (w, r, ago) ->
                    publish(sample(w, r, env.wallClock() - ago * 1000L).apply { fromHistory = true })
                    val ack = byteArrayOf(0x55, 0xAA.toByte(), 0x95.toByte(), 0x00, 0x01, 0x01, 0x00)
                    ack[6] = Bytes.sum8(ack, 0, 6).toByte()
                    writeTo(svc, chrWrite, ack)
                }
            }
        }

        private fun sample(weightX100: Long, resistance: Int, time: Long?) = ScaleSample().apply {
            weightKg = weightX100 / 100f
            dateTime = time
            if (resistance > 0) impedance = resistance.toFloat()
        }
    }

    companion object {
        /** (weight ×100, resistance) of a final live frame (type 0x01/0x11 at byte 5). */
        fun parseLiveFrame(d: ByteArray): Pair<Long, Int>? {
            if (d.size < 12) return null
            val type = Bytes.u8(d, 5)
            if (type != 0x01 && type != 0x11) return null
            return Bytes.fromUnsignedInt32Be(d, 6) to Bytes.fromUnsignedInt16Be(d, 10)
        }

        /** (weight ×100, resistance, seconds ago). */
        fun parseOfflineRecord(d: ByteArray): Triple<Long, Int, Long>? {
            if (d.size < 15) return null
            return Triple(Bytes.fromUnsignedInt32Be(d, 5), Bytes.fromUnsignedInt16Be(d, 9), Bytes.fromUnsignedInt32Be(d, 11))
        }
    }
}

/**
 * RENPHO ES-WBE28 ("renpho-scale" without the QN services): SIG-like setup with vendor magic and
 * user data writes; weight arrives in proprietary 0x2E frames on 2A9D (weight only).
 */
class RenphoWBE28Driver : GattScaleDriver() {
    override val id = "renpho_es_wbe28"
    override val displayName = "RENPHO ES-WBE28"
    override val brand = "RENPHO"
    override val capabilities = ScaleCapabilities()
    override val keywords = listOf("renpho-scale", "WBE28")

    override fun match(adv: AdvertisementInfo): MatchConfidence? {
        if (adv.hasService16(0xFFE0) || adv.hasService16(0xFFF0)) return null
        return if (adv.nameOrEmpty.lowercase(Locale.ROOT).contains("renpho-scale")) MatchConfidence.HIGH else null
    }

    override fun newProtocol(adv: AdvertisementInfo?): GattProtocol = object : GattProtocol() {
        private val svcBodyComp = uuid16(0x181B)
        private val svcUserData = uuid16(0x181C)
        private val svcWeight = uuid16(0x181D)
        private val svcTime = uuid16(0x1805)

        override fun onConnected() {
            setNotifyOn(svcTime, uuid16(0x2A2B))
            setNotifyOn(svcUserData, uuid16(0x2A9F))
            setNotifyOn(svcTime, uuid16(0xFFF1))
            setNotifyOn(svcBodyComp, uuid16(0xFFE1))
            writeTo(svcTime, uuid16(0x2A2B), StandardWeightProfileProtocol.currentTimePayload(env.wallClock()))
            writeTo(svcBodyComp, uuid16(0xFFE2), byteArrayOf(0x10, 0x01, 0x00, 0x11))
            writeTo(svcBodyComp, uuid16(0xFFE2), byteArrayOf(0x03, 0x00, 0x01, 0x04))
            writeTo(svcUserData, uuid16(0x2A9F), byteArrayOf(0x02, 0xAA.toByte(), 0x0F, 0x27))
            writeTo(svcUserData, uuid16(0x2A8C), byteArrayOf(if (user.isMale) 0 else 1))
            writeTo(svcUserData, uuid16(0x2A8E), Bytes.toInt16Le(user.heightCm.toInt().coerceIn(0, 300)))
            val dob = user.birthDate
            writeTo(svcUserData, uuid16(0x2A85), Bytes.toInt16Le(dob.year) + byteArrayOf(dob.monthValue.toByte(), dob.dayOfMonth.toByte()))
            writeTo(svcUserData, uuid16(0x2A80), byteArrayOf(user.ageAt(env.wallClock()).coerceIn(0, 120).toByte()))
            val athlete = user.activityLevel == ActivityLevel.HEAVY || user.activityLevel == ActivityLevel.EXTREME
            writeTo(svcUserData, uuid16(0x2AFF), if (athlete) byteArrayOf(0x0D, 0x00) else byteArrayOf(0x03, 0x00))
            readFrom(svcBodyComp, uuid16(0x2A9B))
            setNotifyOn(svcWeight, uuid16(0x2A9D))
            setNotifyOn(svcBodyComp, uuid16(0x2A9C))
            userInfo("Súbete a la báscula")
        }

        override fun onNotification(characteristic: UUID, data: ByteArray) {
            if (characteristic != uuid16(0x2A9D) || data.size < 3 || data[0] != 0x2E.toByte()) return
            publish(ScaleSample().apply { weightKg = Bytes.fromUnsignedInt16Le(data, 1) / 20.0f })
        }
    }
}

/**
 * QN / Yolanda / RENPHO / FITINDEX connectable scales (FFE0 type 1 or FFF0 type 2): protocol
 * type learned from the first frame, unit/profile/time configuration, stable live weight with
 * resistance, or the latest stored record. Composition with the Trisa formulas.
 */
class QnDriver : GattScaleDriver() {
    override val id = "qn_scale"
    override val displayName = "QN / Yolanda / RENPHO / FITINDEX (conexión)"
    override val brand = "QN / RENPHO / FITINDEX"
    override val capabilities = ScaleCapabilities(impedance = true)
    override val formulaLib = FormulaLib.TRISA
    override val keywords = listOf("QN-Scale", "Renpho", "SEB-Scale", "Fit Plus", "Yolanda", "Fitindex", "Etekcity")

    override fun match(adv: AdvertisementInfo): MatchConfidence? {
        val n = adv.nameOrEmpty.lowercase(Locale.ROOT)
        val hasQn = adv.hasService16(0xFFE0) || adv.hasService16(0xFFF0)
        val qnName = n.contains("qn-scale") || n.contains("renpho-scale") || n.contains("seb-scale") || n == "fit plus"
        return if (hasQn && (qnName || adv.hasService16(0xAE00))) MatchConfidence.HIGH else null
    }

    override fun newProtocol(adv: AdvertisementInfo?): GattProtocol = Protocol()

    internal class Protocol : GattProtocol() {
        private val svcT1 = uuid16(0xFFE0)
        private val chrT1Notify = uuid16(0xFFE1)
        private val chrT1Indicate = uuid16(0xFFE2)
        private val chrT1Config = uuid16(0xFFE3)
        private val chrT1Time = uuid16(0xFFE4)
        private val svcT2 = uuid16(0xFFF0)
        private val chrT2Notify = uuid16(0xFFF1)
        private val chrT2Shared = uuid16(0xFFF2)
        private var published = false
        private var scaleFactor = 100.0f
        private var protocolType: Byte = 0
        private var gotScaleInfo = false
        private var historyAttempts = 0
        private var connected = false
        private var sessionStartScaleSeconds = 0L
        private var retry: Cancellable? = null
        private val universal: Boolean get() = protocolType == 0xFF.toByte()

        override fun onConnected() {
            connected = true
            sessionStartScaleSeconds = env.wallClock() / 1000L - QN_EPOCH_OFFSET
            if (hasCharacteristic(svcT1, chrT1Notify)) setNotifyOn(svcT1, chrT1Notify)
            if (hasCharacteristic(svcT1, chrT1Indicate)) setNotifyOn(svcT1, chrT1Indicate)
            if (hasCharacteristic(svcT2, chrT2Notify)) setNotifyOn(svcT2, chrT2Notify)
            userInfo("Súbete a la báscula")
        }

        override fun onDisconnected() {
            connected = false
            retry?.cancel()
        }

        override fun onNotification(characteristic: UUID, data: ByteArray) {
            if ((characteristic != chrT1Notify && characteristic != chrT2Notify) || data.size < 3) return
            if (protocolType == 0.toByte()) protocolType = data[2]
            when (Bytes.u8(data, 0)) {
                0x10 -> liveFrame(data)
                0x14 -> {
                    val t = (env.wallClock() / 1000L - QN_EPOCH_OFFSET).toInt()
                    writeCommand(withSum(byteArrayOf(0x20, 0x08, protocolType) + Bytes.toInt32Le(t.toLong() and 0xFFFFFFFFL) + byteArrayOf(0)))
                }
                0x12 -> if (data.size > 10) {
                    scaleFactor = if (data[10].toInt() == 1) 100.0f else 10.0f
                    if (!gotScaleInfo) {
                        gotScaleInfo = true
                        sendConfiguration()
                    }
                }
                0x21 -> {
                    writeCommand(withSum(byteArrayOf(0xA0.toByte(), 0x0D, 0x04, 0xFE.toByte(), 0, 0, 0, 0, 0, 0, 0, 0, 0)))
                    writeCommand(withSum(byteArrayOf(0xA0.toByte(), 0x0D, 0x02, 0x01, 0x00, 0x08, 0x00, 0x21, 0x06, 0xB8.toByte(), 0x04, 0x02, 0)))
                    queryStored()
                }
                0x23 -> storedFrame(data)
            }
        }

        private fun liveFrame(d: ByteArray) {
            val b4 = Bytes.u8(d, 4)
            val es30m = b4 <= 0x02 && scaleFactor == 10.0f
            val stable: Boolean
            val raw: Int
            val r1: Int
            if (es30m) {
                if (d.size < 11) return
                stable = if (universal) b4 == 0x02 else b4 == 0x02 || b4 == 0x01
                raw = Bytes.fromUnsignedInt16Be(d, 5)
                r1 = Bytes.fromUnsignedInt16Be(d, 7)
            } else {
                if (d.size < 10) return
                stable = d[5].toInt() == 1
                raw = Bytes.fromUnsignedInt16Be(d, 3)
                r1 = Bytes.fromUnsignedInt16Be(d, 6)
            }
            var kg = raw / scaleFactor
            if (kg <= 5f || kg >= 250f) kg /= 10.0f
            if (!stable) {
                live(kg)
                return
            }
            if (universal) writeCommand(withSum(byteArrayOf(0x1F, 0x05, protocolType, 0x10, 0x00)))
            if (published || kg <= 0f) return
            publishQn(kg, r1.toFloat())
            published = true
        }

        private fun storedFrame(d: ByteArray) {
            if (published) return
            if (d.size < 17) {
                scheduleRetry()
                return
            }
            val kg = Bytes.fromUnsignedInt16Be(d, 10) / 100.0f
            val recordSeconds = Bytes.fromUnsignedInt32Le(d, 6)
            if (kg <= 5f || kg >= 300f || recordSeconds + 90 < sessionStartScaleSeconds) {
                scheduleRetry()
                return
            }
            publishQn(kg, Bytes.fromUnsignedInt16Le(d, 13).toFloat())
            published = true
        }

        private fun publishQn(kg: Float, r1: Float) {
            val ohm = if (universal) r1 / 10f else r1
            publish(ScaleSample().apply {
                weightKg = kg
                if (ohm > 0f) {
                    // The resistance is stored; only the older QN families need the Trisa calibration.
                    impedance = ohm
                    impedanceLib = if (universal) FormulaLib.TRISA else FormulaLib.TRISA_RESISTANCE
                }
            })
        }

        private fun queryStored() {
            if (!connected || published || historyAttempts >= 10) return
            historyAttempts++
            writeCommand(withSum(byteArrayOf(0x22, 0x06, protocolType, 0x00, 0x03, 0x00)))
        }

        private fun scheduleRetry() {
            if (!connected || published || historyAttempts >= 10) return
            retry?.cancel()
            retry = schedule(5_000) { queryStored() }
        }

        private fun sendConfiguration() {
            val unit: Byte = if (user.unit == ScaleUnit.KG) 0x01 else 0x02
            val height: Byte = if (universal) user.heightCm.toInt().coerceIn(60, 220).toByte() else 0
            val age: Byte = if (universal) user.ageAt(env.wallClock()).coerceIn(6, 80).toByte() else 0
            val gender: Byte = if (universal && !user.isMale) 0x01 else 0x00
            val cfg = byteArrayOf(0x13, 0x09, protocolType, unit, 0x10, height, age, gender, 0x00)
            cfg[cfg.lastIndex] = Bytes.sum8(cfg, 0, cfg.lastIndex).toByte()
            if (hasCharacteristic(svcT1, chrT1Config)) writeTo(svcT1, chrT1Config, cfg)
            if (hasCharacteristic(svcT2, chrT2Shared)) writeTo(svcT2, chrT2Shared, cfg)
            val t = (env.wallClock() / 1000L - QN_EPOCH_OFFSET).toInt()
            val timeMagic = byteArrayOf(0x02) + Bytes.toInt32Le(t.toLong() and 0xFFFFFFFFL)
            if (hasCharacteristic(svcT1, chrT1Time)) writeTo(svcT1, chrT1Time, timeMagic)
            if (hasCharacteristic(svcT2, chrT2Shared)) writeTo(svcT2, chrT2Shared, timeMagic)
        }

        /** Writes to the T2 shared characteristic, else to T1 config. */
        private fun writeCommand(msg: ByteArray) {
            when {
                hasCharacteristic(svcT2, chrT2Shared) -> writeTo(svcT2, chrT2Shared, msg)
                hasCharacteristic(svcT1, chrT1Config) -> writeTo(svcT1, chrT1Config, msg)
            }
        }

        /** Last byte = sum8 of all previous bytes. */
        private fun withSum(msg: ByteArray): ByteArray = msg.also { it[it.lastIndex] = Bytes.sum8(it, 0, it.lastIndex).toByte() }
    }

    companion object {
        const val QN_EPOCH_OFFSET = 946_702_800L
    }
}
