// Protocol ported from openScale (GPL-3.0), https://github.com/oliexdev/openScale
package com.enjoyyourtime.globalfitness.scale.drivers

import com.enjoyyourtime.globalfitness.scale.ActivityLevel
import com.enjoyyourtime.globalfitness.scale.AdvertisementInfo
import com.enjoyyourtime.globalfitness.scale.Bytes
import com.enjoyyourtime.globalfitness.scale.Cancellable
import com.enjoyyourtime.globalfitness.scale.FormulaLib
import com.enjoyyourtime.globalfitness.scale.GattPacing
import com.enjoyyourtime.globalfitness.scale.GattProtocol
import com.enjoyyourtime.globalfitness.scale.GattScaleDriver
import com.enjoyyourtime.globalfitness.scale.MatchConfidence
import com.enjoyyourtime.globalfitness.scale.ScaleCapabilities
import com.enjoyyourtime.globalfitness.scale.ScaleSample
import com.enjoyyourtime.globalfitness.scale.ScaleUnit
import com.enjoyyourtime.globalfitness.scale.libs.AfuB1Lib
import com.enjoyyourtime.globalfitness.scale.libs.EufyAuthHandler
import com.enjoyyourtime.globalfitness.scale.libs.EufyP2Lib
import com.enjoyyourtime.globalfitness.scale.libs.YunmaiLib
import com.enjoyyourtime.globalfitness.scale.uuid16
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.Locale
import java.util.UUID
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * 1byone "Health Scale" (classic) and Eufy C1/P1/A1 (T9146/T9147/T9120): FFF0 with FD/F1/F2
 * commands; live and history 0xCF frames with impedance. Composition with the 1byone formulas.
 */
class OneByoneDriver : GattScaleDriver() {
    override val id = "onebyone_classic"
    override val displayName = "1byone Health Scale · Eufy C1/P1/A1"
    override val brand = "1byone / Eufy"
    override val capabilities = ScaleCapabilities(impedance = true, history = true)
    override val formulaLib = FormulaLib.ONE_BYONE
    override val keywords = listOf("Health Scale", "T9146", "T9147", "T9120", "Eufy C1", "Eufy P1", "Eufy A1")

    override fun match(adv: AdvertisementInfo): MatchConfidence? {
        val n = adv.nameOrEmpty.lowercase(Locale.ROOT)
        return when {
            "t9146" in n || "t9147" in n || "t9120" in n -> MatchConfidence.HIGH
            isClassicName(n) -> MatchConfidence.MEDIUM
            else -> null
        }
    }

    override fun newProtocol(adv: AdvertisementInfo?): GattProtocol = Protocol(isClassicName(adv?.nameOrEmpty.orEmpty()))

    internal class Protocol(private val classic: Boolean) : GattProtocol() {
        private val svc = uuid16(0xFFF0)
        private val chrNotify = uuid16(0xFFF4)
        private val chrWrite = uuid16(0xFFF1)
        private val svcBattery = uuid16(0x180F)
        private val chrBattery = uuid16(0x2A19)
        private var waitAckClock = false
        private var historicMode = false
        private var historyCount = 0
        private var publishedLive = false
        private var prompted = false
        private var clockFallback: Cancellable? = null

        /** "Step on" prompt at most once per connection (some models never acknowledge the clock). */
        private fun prompt() {
            if (prompted) return
            prompted = true
            userInfo("Súbete a la báscula")
        }

        override fun onConnected() {
            setNotifyOn(svc, chrNotify)
            writeTo(svc, chrWrite, modeUnitCommand(user.unit))
            waitAckClock = true
            writeTo(svc, chrWrite, clockCommand(env.wallClock()))
            setNotifyOn(svcBattery, chrBattery)
            readFrom(svcBattery, chrBattery)
            clockFallback = schedule(3_000) { if (waitAckClock) prompt() }
        }

        override fun onDisconnected() {
            clockFallback?.cancel()
        }

        override fun onNotification(characteristic: UUID, data: ByteArray) {
            if (characteristic == chrBattery) {
                if (data.isNotEmpty() && Bytes.u8(data, 0) <= 10) userInfo("Batería de la báscula baja")
                return
            }
            if (characteristic != chrNotify) return
            if (data.size == 2) {
                if (waitAckClock && data[0] == 0xF1.toByte() && data[1] == 0x00.toByte()) {
                    clockFallback?.cancel()
                    waitAckClock = false
                    historicMode = true
                    historyCount = 0
                    writeTo(svc, chrWrite, byteArrayOf(0xF2.toByte(), 0x00))
                    return
                }
                if (data[0] == 0xF2.toByte() && data[1] == 0x00.toByte() && historicMode) {
                    historicMode = false
                    if (historyCount > 0) writeTo(svc, chrWrite, byteArrayOf(0xF2.toByte(), 0x01))
                    prompt()
                    return
                }
            }
            if (data.isNotEmpty() && data[0] == 0xCF.toByte() && data.size >= 11) {
                if (historicMode) {
                    historyCount++
                } else {
                    clockFallback?.cancel()
                    prompted = true
                }
                parseFrame(data, historicMode)
            }
        }

        private fun parseFrame(b: ByteArray, historic: Boolean) {
            val kg = Bytes.fromUnsignedInt16Le(b, 3) / 100.0f
            val impedance = ((Bytes.u8(b, 2) shl 8) + Bytes.u8(b, 1)) * 0.1f
            val hasTimestamp = hasHistoryTimestamp(b)
            if (historic && !hasTimestamp) return
            if (classic) {
                if (!historic && !isFinalReading(b)) {
                    live(kg)
                    return
                }
            } else {
                if (Bytes.u8(b, 9) == 1 || impedance == 0f) {
                    if (!historic) live(kg)
                    return
                }
            }
            if (kg <= 0f || (!historic && publishedLive)) return
            val time = if (hasTimestamp) {
                runCatching {
                    LocalDateTime.of(Bytes.fromUnsignedInt16Be(b, 11), Bytes.u8(b, 13).coerceIn(1, 12), Bytes.u8(b, 14).coerceAtLeast(1), Bytes.u8(b, 15), Bytes.u8(b, 16), Bytes.u8(b, 17))
                        .atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
                }.getOrNull() ?: return
            } else {
                null
            }
            if (!historic) publishedLive = true
            publish(ScaleSample().apply {
                weightKg = kg
                dateTime = time
                fromHistory = historic
                if (impedance != 0f) this.impedance = impedance
            })
        }
    }

    companion object {
        fun isClassicName(name: String) = "health scale" in name.lowercase(Locale.ROOT)

        fun modeUnitCommand(unit: ScaleUnit): ByteArray {
            val p = byteArrayOf(0xFD.toByte(), 0x37, unit.ordinal.toByte(), 0x01, 0, 0, 0, 0, 0, 0, 0)
            p[p.lastIndex] = Bytes.xor(p, 0, p.size - 1).toByte()
            return p
        }

        fun clockCommand(nowMillis: Long): ByteArray {
            val t = Instant.ofEpochMilli(nowMillis).atZone(ZoneId.systemDefault())
            return byteArrayOf(0xF1.toByte()) + Bytes.toInt16Be(t.year) +
                byteArrayOf(t.monthValue.toByte(), t.dayOfMonth.toByte(), t.hour.toByte(), t.minute.toByte(), t.second.toByte())
        }

        fun isLiveFrame(b: ByteArray) = b.size >= 11 && Bytes.u8(b, 10) == Bytes.xor(b, 0, 10)

        fun isFinalReading(b: ByteArray) = b.size >= 11 && (Bytes.u8(b, 9) == 0x00 || Bytes.u8(b, 9) == 0x36)

        fun hasHistoryTimestamp(b: ByteArray): Boolean = b.size >= 18 && !(isLiveFrame(b) && b[11] == 0xCF.toByte())
    }
}

/**
 * 1byone "new" scale ("1byone scale", FFB0 `AB 2A` frames): profile/measurement setup (D7) and
 * user history (D4) frames; final weight (0x80) + impedance (0x01) and history records (0x00).
 */
class OneByoneNewDriver : GattScaleDriver() {
    override val id = "onebyone_new"
    override val displayName = "1byone (nuevo)"
    override val brand = "1byone"
    override val capabilities = ScaleCapabilities(impedance = true, history = true)
    override val formulaLib = FormulaLib.ONE_BYONE_NEW

    override fun match(adv: AdvertisementInfo): MatchConfidence? =
        if (adv.nameOrEmpty.equals("1byone scale", ignoreCase = true)) MatchConfidence.HIGH else null

    override fun newProtocol(adv: AdvertisementInfo?): GattProtocol = object : GattProtocol() {
        private val svc = uuid16(0xFFB0)
        private val chrNotify = uuid16(0xFFB2)
        private val chrWrite = uuid16(0xFFB1)
        private var pending: ScaleSample? = null

        override fun onConnected() {
            setNotifyOn(svc, chrNotify)
            writeTo(svc, chrWrite, measurementRequest())
            writeTo(svc, chrWrite, usersHistory())
            userInfo("Súbete a la báscula")
        }

        override fun onNotification(characteristic: UUID, data: ByteArray) {
            if (characteristic != chrNotify || data.size < 20 || data[0] != 0xAB.toByte() || data[1] != 0x2A.toByte()) return
            when (Bytes.u8(data, 2)) {
                0x80 -> pending = ScaleSample().apply { weightKg = (Bytes.fromUnsignedInt24Be(data, 3) and 0x3FFFF) / 1000.0f }
                0x01 -> {
                    val m = pending ?: return
                    m.impedance = Bytes.fromUnsignedInt16Be(data, 4).toFloat()
                    publish(m)
                    pending = null
                    writeTo(svc, chrWrite, usersHistory())
                }
                0x00 -> {
                    if (data[7] != 0x80.toByte()) return
                    publish(ScaleSample().apply {
                        dateTime = Bytes.fromUnsignedInt32Be(data, 3) * 1000L
                        weightKg = (Bytes.fromUnsignedInt24Be(data, 8) and 0x3FFFF) / 1000.0f
                        impedance = Bytes.fromUnsignedInt16Be(data, 15).toFloat()
                        fromHistory = true
                    })
                }
            }
        }

        /**
         * The user's last weight and impedance (openScale back-computes the impedance from the
         * stored LBM, which for a measurement of this scale is the impedance itself).
         */
        private fun entry(msg: ByteArray, offset: Int, entryNum: Int, impedanceMsbFirst: Boolean) {
            val weightTimes10 = ((user.lastWeightKg ?: 0f) * 10f).roundToInt() * 10
            val impedance = if (user.lastWeightKg != null) user.lastImpedance ?: 0 else 0
            msg[offset] = (entryNum and 0xFF).toByte()
            msg[offset + 1] = (user.heightCm.roundToInt() and 0xFF).toByte()
            Bytes.toInt16Be(msg, offset + 2, weightTimes10)
            msg[offset + 4] = (((if (user.isMale) 1 else 0) shl 7) or (user.ageAt(env.wallClock()) and 0x7F)).toByte()
            if (impedanceMsbFirst) {
                msg[offset + 5] = (impedance ushr 8).toByte()
                msg[offset + 6] = impedance.toByte()
            } else {
                msg[offset + 5] = impedance.toByte()
                msg[offset + 6] = (impedance ushr 8).toByte()
            }
        }

        private fun measurementRequest(): ByteArray {
            val msg = ByteArray(20)
            msg[0] = 0xAB.toByte()
            msg[1] = 0x2A
            Bytes.toInt32Be(msg, 2, env.wallClock() / 1000L)
            msg[7] = user.unit.ordinal.toByte()
            entry(msg, 8, user.appUserId, impedanceMsbFirst = false)
            msg[18] = 0xD7.toByte()
            msg[19] = (Bytes.sum8(msg, 2, 19) - msg[14]).toByte()
            return msg
        }

        private fun usersHistory(): ByteArray {
            val msg = ByteArray(20)
            msg[0] = 0xAB.toByte()
            msg[1] = 0x2A
            msg[2] = 1
            msg[3] = 1
            entry(msg, 4, 1, impedanceMsbFirst = true)
            msg[18] = 0xD4.toByte()
            var sum = Bytes.sum8(msg, 2, 20)
            for (i in intArrayOf(9, 13, 14, 16)) sum -= msg[i].toInt()
            msg[19] = sum.toByte()
            return msg
        }
    }
}

/**
 * Yunmai Mini / SE (FFE0/FFE4 notify, FFE5/FFE9 commands): user and time packets, final frame
 * with weight, resistance and (protocol ≥ 0x1E) the scale's own fat percentage. Mini only:
 * composition (Yunmai formulas); SE: weight only, as in openScale.
 */
class YunmaiDriver(private val isMini: Boolean) : GattScaleDriver() {
    override val id = if (isMini) "yunmai_mini" else "yunmai_se"
    override val displayName = if (isMini) "Yunmai Mini (Signal, ISM, ISC2P, Scale-3)" else "Yunmai SE"
    override val brand = "Yunmai"
    override val capabilities = ScaleCapabilities(impedance = isMini, deviceComposition = isMini)
    override val formulaLib = if (isMini) FormulaLib.YUNMAI else null
    override val keywords = if (isMini) listOf("YUNMAI-SIGNAL", "YUNMAI-ISM", "YUNMAI-ISC2P", "YUNMAI-Scale-3") else listOf("YUNMAI-ISSE")

    override fun match(adv: AdvertisementInfo): MatchConfidence? {
        val n = adv.nameOrEmpty
        val ok = if (isMini) {
            n.startsWith("YUNMAI-SIGNAL") || n.startsWith("YUNMAI-ISM") || n.startsWith("YUNMAI-ISC2P") || n == "YUNMAI-Scale-3"
        } else {
            n.startsWith("YUNMAI-ISSE")
        }
        return if (ok) MatchConfidence.HIGH else null
    }

    override fun newProtocol(adv: AdvertisementInfo?): GattProtocol = object : GattProtocol() {
        private val svcMeas = uuid16(0xFFE0)
        private val chrMeas = uuid16(0xFFE4)
        private val svcCmd = uuid16(0xFFE5)
        private val chrCmd = uuid16(0xFFE9)
        private var last: ScaleSample? = null

        private val uid16: Int get() = user.appUserId.takeIf { it > 0 }?.and(0xFFFF) ?: 1

        override fun onConnected() {
            val now = env.wallClock()
            val userPacket = byteArrayOf(0x0D, 0x12, 0x10, 0x01, 0x00, 0x00) + Bytes.toInt16Be(uid16) + byteArrayOf(
                user.heightCm.toInt().toByte(), if (user.isMale) 0x01 else 0x02, user.ageAt(now).toByte(), 0x55, 0x5A, 0x00, 0x00,
                if (user.unit == ScaleUnit.KG) 0x01 else 0x02, YunmaiLib.toYunmaiActivityLevel(user.activityLevel).toByte(), 0x00,
            )
            userPacket[userPacket.lastIndex] = Bytes.xor(userPacket, 1, userPacket.lastIndex).toByte()
            writeTo(svcCmd, chrCmd, userPacket)
            val timePacket = byteArrayOf(0x0D, 0x0D, 0x11) + Bytes.toInt32Be(now / 1000L) + byteArrayOf(0, 0, 0, 0, 0, 0)
            timePacket[timePacket.lastIndex] = Bytes.xor(timePacket, 1, timePacket.lastIndex).toByte()
            writeTo(svcCmd, chrCmd, timePacket)
            setNotifyOn(svcMeas, chrMeas)
            writeTo(svcCmd, chrCmd, byteArrayOf(0x0D, 0x05, 0x13, 0x00, 0x16))
            userInfo("Súbete a la báscula")
        }

        override fun onNotification(characteristic: UUID, data: ByteArray) {
            if (characteristic != chrMeas || data.size < 18 || data[3] != 0x02.toByte()) return
            if (Bytes.fromUnsignedInt32Be(data, 9) != uid16.toLong()) return
            val m = parseFinal(data) ?: return
            val prev = last
            if (prev != null && abs((prev.dateTime ?: 0L) - (m.dateTime ?: 0L)) <= 2_000 &&
                abs((prev.weightKg ?: 0f) - (m.weightKg ?: 0f)) <= 0.01f && abs((prev.fatPercent ?: 0f) - (m.fatPercent ?: 0f)) <= 0.01f
            ) {
                return
            }
            publish(m)
            last = m
            finishSoon()
        }

        private fun parseFinal(f: ByteArray): ScaleSample? {
            var ts = Bytes.fromUnsignedInt32Be(f, 5) * 1000L
            val kg = Bytes.fromUnsignedInt16Be(f, 13) / 100.0f
            if (kg <= 0f) return null
            if (ts < 315532800000L) ts = env.wallClock()
            val m = ScaleSample().apply {
                dateTime = ts
                weightKg = kg
            }
            if (isMini) {
                val resistance = Bytes.fromUnsignedInt16Be(f, 15)
                if (Bytes.u8(f, 1) >= 0x1E) {
                    // The scale computes fat itself; the rest follows from it (Yunmai formulas).
                    val fat = Bytes.fromUnsignedInt16Be(f, 17) / 100.0f
                    if (fat > 0f) {
                        val lib = YunmaiLib(if (user.isMale) 1 else 0, user.heightCm, user.activityLevel)
                        m.fatPercent = fat
                        m.skeletalMusclePercent = lib.getSkeletalMuscle(fat)
                        m.waterPercent = lib.getWater(fat)
                        m.boneKg = lib.getBoneMass(lib.getMuscle(fat), kg)
                        m.lbmKg = lib.getLeanBodyMass(kg, fat)
                        m.visceralFat = lib.getVisceralFat(fat, user.ageAt(ts))
                        m.computedWith = FormulaLib.YUNMAI
                    }
                }
                if (resistance > 0) m.impedance = resistance.toFloat()
            }
            return m
        }
    }
}

/**
 * Eufy Smart Scale P2 / P2 Pro (T9148/T9149): AES-CBC authentication (C0..C3) on FFF1/FFF4, then
 * weight + impedance notifications on FFF2.
 */
class EufyP2Driver : GattScaleDriver() {
    override val id = "eufy_p2"
    override val displayName = "Eufy Smart Scale P2 / P2 Pro (T9148/T9149)"
    override val brand = "Eufy"
    override val capabilities = ScaleCapabilities(impedance = true)
    override val formulaLib = FormulaLib.STANDARD_IMPEDANCE
    override val keywords = listOf("T9148", "T9149", "Anker")

    // The C0/C2 handshake frames need slow pacing (as in openScale's conservative tuning).
    override val pacing = GattPacing.CONSERVATIVE

    override fun match(adv: AdvertisementInfo): MatchConfidence? {
        val n = adv.nameOrEmpty.lowercase(Locale.ROOT)
        return if (n.startsWith("eufy t9148") || n.startsWith("eufy t9149")) MatchConfidence.HIGH else null
    }

    override fun newProtocol(adv: AdvertisementInfo?): GattProtocol = object : GattProtocol() {
        private val svc = uuid16(0xFFF0)
        private val chrWrite = uuid16(0xFFF1)
        private val chrData = uuid16(0xFFF2)
        private val chrAuth = uuid16(0xFFF4)
        private var auth: EufyAuthHandler? = null
        private var c3Seen = false

        override fun onConnected() {
            val mac = env.address
            if (mac.isNullOrEmpty()) return
            setNotifyOn(svc, chrData)
            setNotifyOn(svc, chrAuth)
            val handler = runCatching { EufyAuthHandler(mac) }.getOrNull() ?: return
            auth = handler
            handler.buildC0().forEach { writeTo(svc, chrWrite, it) }
            userInfo("Súbete a la báscula")
        }

        override fun onNotification(characteristic: UUID, data: ByteArray) {
            when (characteristic) {
                chrAuth -> {
                    val handler = auth ?: return
                    if (data.isEmpty()) return
                    when (Bytes.u8(data, 0)) {
                        0xC1 -> if (handler.handleC1(data)) handler.buildC2().forEach { writeTo(svc, chrWrite, it) }
                        0xC3 -> if (!c3Seen) {
                            c3Seen = true
                            handler.handleC3(data)
                            if (!handler.isAuthenticated) userInfo("La báscula Eufy rechazó la autenticación")
                        }
                    }
                }
                chrData -> {
                    if (auth?.isAuthenticated != true) return
                    val r = EufyP2Lib.parseWeightNotification(data) ?: return
                    publish(ScaleSample().apply {
                        weightKg = r.weightKg
                        if (r.impedanceOhm > 0) impedance = r.impedanceOhm.toFloat()
                    })
                    finishSoon()
                }
            }
        }
    }
}

/**
 * AFU-BH-TZ-B1 (service FC50): subscribes to every notifiable characteristic, handshake and
 * history dump; live weight (0x51), stored records (0x54) with resistance, commit flow once the
 * scale goes idle. Composition with the AFU vendor model (recomputed from impedance).
 */
class AfuB1Driver : GattScaleDriver() {
    override val id = "afu_b1"
    override val displayName = "AFU-BH-TZ-B1"
    override val brand = "AFU"
    override val capabilities = ScaleCapabilities(impedance = true, history = true)
    override val formulaLib = FormulaLib.AFU_B1

    override fun match(adv: AdvertisementInfo): MatchConfidence? =
        if (adv.upperName.contains("AFU") && adv.serviceUuids.contains(SERVICE)) MatchConfidence.HIGH else null

    override fun newProtocol(adv: AdvertisementInfo?): GattProtocol = Protocol()

    internal class Protocol : GattProtocol() {
        private var age = 30
        private var sex = 1
        private var targetKg = 60f
        private var handshakeComplete = false
        private var liveInProgress = false
        private var profileSent = false
        private var historyDumpRequested = false
        private var weightFinal = false
        private var commitInProgress = false
        private var committedSeen = false
        private var lastLiveGrams: Int? = null
        private var lastFrameAt = 0L
        private var weighStartedAt = 0L
        private var timer: Cancellable? = null

        private fun writeService(): UUID =
            characteristics().firstOrNull { it.characteristic == WRITE_CHARACTERISTIC }?.service ?: WRITE_SERVICE

        private fun write(frame: ByteArray) = writeTo(writeService(), WRITE_CHARACTERISTIC, frame)

        private val nowSeconds: Long get() = env.wallClock() / 1000L

        override fun onConnected() {
            val now = env.wallClock()
            age = user.ageAt(now)
            sex = if (user.isMale) 1 else 0
            targetKg = user.goalWeightKg?.takeIf { it > 0f } ?: user.lastWeightKg?.takeIf { it > 0f } ?: 60f
            characteristics().filter { it.canNotify }.forEach { setNotifyOn(it.service, it.characteristic) }
            lastFrameAt = env.elapsedClock()
            write(AfuB1Lib.buildTimeSync(nowSeconds))
            write(AfuB1Lib.buildSyncRequest(AfuB1Lib.SyncRequestVariant.FIRST))
            write(AfuB1Lib.buildSyncRequest(AfuB1Lib.SyncRequestVariant.SECOND))
            handshakeComplete = true
            write(AfuB1Lib.buildHistoryDumpRequest(nowSeconds, SCALE_USER_SLOT, age, sex, targetKg))
            userInfo("Súbete a la báscula")
        }

        override fun onDisconnected() {
            timer?.cancel()
        }

        override fun onNotification(characteristic: UUID, data: ByteArray) {
            lastFrameAt = env.elapsedClock()
            when (val f = AfuB1Lib.parse(data) ?: return) {
                is AfuB1Lib.ScaleFrame.StoredRecord -> storedRecord(f)
                is AfuB1Lib.ScaleFrame.LiveWeight -> liveWeight(f)
                is AfuB1Lib.ScaleFrame.ImpedanceResult -> {
                    ack(f)
                    if (handshakeComplete && commitInProgress && !committedSeen) {
                        profile(AfuB1Lib.ProfileVariant.FINAL)
                        write(AfuB1Lib.buildTimeSync(nowSeconds))
                        write(AfuB1Lib.buildSyncRequest(AfuB1Lib.SyncRequestVariant.FIRST))
                        write(AfuB1Lib.buildSyncRequest(AfuB1Lib.SyncRequestVariant.SECOND))
                    }
                }
                is AfuB1Lib.ScaleFrame.BodyCompChunk -> ack(f)
                is AfuB1Lib.ScaleFrame.Other -> Unit
            }
        }

        private fun ack(f: AfuB1Lib.ScaleFrame) {
            val correlation = (f as? AfuB1Lib.ScaleFrame.StoredRecord)?.sequence ?: 0
            write(AfuB1Lib.buildAck(f.ackReplyByte, correlation))
        }

        private fun profile(variant: AfuB1Lib.ProfileVariant) =
            write(AfuB1Lib.buildUserProfile(variant, nowSeconds, SCALE_USER_SLOT, age, sex, targetKg))

        private fun storedRecord(r: AfuB1Lib.ScaleFrame.StoredRecord) {
            ack(r)
            if (!liveInProgress && !historyDumpRequested) {
                historyDumpRequested = true
                write(AfuB1Lib.buildHistoryDumpRequest(nowSeconds, SCALE_USER_SLOT, age, sex, targetKg))
            }
            publish(ScaleSample().apply {
                weightKg = r.weightGrams / 1000.0f
                dateTime = r.timestampEpochSeconds * 1000L
                if (r.resistanceOhms > 0) impedance = r.resistanceOhms.toFloat()
                fromHistory = true
            })
            if (isFreshCommitted(r) && !committedSeen) {
                committedSeen = true
                timer?.cancel()
                write(AfuB1Lib.buildTimeSync(nowSeconds))
                write(AfuB1Lib.buildSyncRequest(AfuB1Lib.SyncRequestVariant.FIRST))
                write(AfuB1Lib.buildSyncRequest(AfuB1Lib.SyncRequestVariant.SECOND))
                write(AfuB1Lib.buildHistoryDumpRequest(nowSeconds, SCALE_USER_SLOT, age, sex, targetKg))
                val deadline = env.elapsedClock() + COMMIT_TIMEOUT_MS
                pollUntil({ idle() || env.elapsedClock() >= deadline }) { requestDisconnect() }
            }
        }

        private fun liveWeight(w: AfuB1Lib.ScaleFrame.LiveWeight) {
            liveInProgress = true
            if (weighStartedAt == 0L) weighStartedAt = env.wallClock()
            lastLiveGrams = w.weightGrams
            live(w.weightGrams / 1000f)
            if (!profileSent && handshakeComplete) {
                profileSent = true
                profile(AfuB1Lib.ProfileVariant.INITIAL)
            }
            if (w.locked && !weightFinal && handshakeComplete) {
                weightFinal = true
                ack(w)
                startCommitFlow()
            }
        }

        private fun startCommitFlow() {
            pollUntil({ committedSeen || idle() }) {
                if (committedSeen) return@pollUntil
                if (!commitInProgress) {
                    commitInProgress = true
                    write(AfuB1Lib.buildTimeSync(nowSeconds))
                    write(AfuB1Lib.buildSyncRequest(AfuB1Lib.SyncRequestVariant.FIRST))
                    write(AfuB1Lib.buildSyncRequest(AfuB1Lib.SyncRequestVariant.SECOND))
                    profile(AfuB1Lib.ProfileVariant.GET_WEIGH_AGAIN_1)
                    profile(AfuB1Lib.ProfileVariant.GET_WEIGH_AGAIN_2)
                    write(AfuB1Lib.buildChunkRequest())
                }
                val deadline = env.elapsedClock() + COMMIT_TIMEOUT_MS
                pollUntil({ committedSeen || env.elapsedClock() >= deadline }) {
                    val grams = lastLiveGrams
                    if (!committedSeen && grams != null) {
                        committedSeen = true
                        publish(ScaleSample().apply { weightKg = grams / 1000.0f })
                        requestDisconnect()
                    }
                }
            }
        }

        /** Re-checks [condition] every 250 ms (protocol thread) and runs [then] once it holds. */
        private fun pollUntil(condition: () -> Boolean, then: () -> Unit) {
            timer?.cancel()
            if (condition()) {
                then()
                return
            }
            timer = schedule(POLL_MS) { pollUntil(condition, then) }
        }

        private fun idle(): Boolean = env.elapsedClock() - lastFrameAt > IDLE_DELAY_MS

        private fun isFreshCommitted(r: AfuB1Lib.ScaleFrame.StoredRecord): Boolean {
            if (!commitInProgress || weighStartedAt == 0L) return false
            val ts = r.timestampEpochSeconds
            if (ts < weighStartedAt / 1000L - 60L) return false
            if (abs(nowSeconds - ts) > FRESH_RECORD_WINDOW_S) return false
            val live = lastLiveGrams ?: return false
            return abs(r.weightGrams - live) <= FRESH_WEIGHT_TOLERANCE_G
        }
    }

    companion object {
        val SERVICE: UUID = UUID.fromString("0000fc50-0000-1000-8000-00805f9b34fb")
        val WRITE_SERVICE: UUID = UUID.fromString("af000000-9f2d-4c8a-8d6f-6a7b45f90000")
        val WRITE_CHARACTERISTIC: UUID = UUID.fromString("af000002-9f2d-4c8a-8d6f-6a7b45f90000")
        private const val SCALE_USER_SLOT = 1
        private const val IDLE_DELAY_MS = 2_000L
        private const val POLL_MS = 250L
        private const val COMMIT_TIMEOUT_MS = 15_000L
        private const val FRESH_RECORD_WINDOW_S = 600L
        private const val FRESH_WEIGHT_TOLERANCE_G = 1_000
    }
}
