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
import com.enjoyyourtime.globalfitness.scale.uuid16
import java.time.Instant
import java.time.ZoneId
import java.util.Locale
import java.util.UUID

/** ICOMON-style config frame `AC 02 cmd b3 b4 b5 CC sum(cmd..CC)` on FFB1 (EEBBL, Runstar R5). */
internal fun icomonConfig(cmd: Int, b3: Int, b4: Int, b5: Int): ByteArray {
    val buf = byteArrayOf(0xAC.toByte(), 0x02, cmd.toByte(), b3.toByte(), b4.toByte(), b5.toByte(), 0xCC.toByte(), 0)
    buf[7] = Bytes.sum8(buf, 2, 7).toByte()
    return buf
}

/** EEBBL Body Fat Scale (P1, 8 electrodes): weight published after 8 identical stable A2 frames. */
class EEBBLDriver : GattScaleDriver() {
    override val id = "eebbl_p1"
    override val displayName = "EEBBL Body Fat Scale P1 (8 electrodos)"
    override val brand = "EEBBL"
    override val capabilities = ScaleCapabilities()
    override val keywords = listOf("P1")

    override fun match(adv: AdvertisementInfo): MatchConfidence? {
        val n = adv.upperName
        return when {
            n.contains("EEBBL") -> MatchConfidence.HIGH
            n.contains("P1") && adv.hasService16(0xFFB0) -> MatchConfidence.LOW
            else -> null
        }
    }

    override fun newProtocol(adv: AdvertisementInfo?): GattProtocol = object : GattProtocol() {
        private val svc = uuid16(0xFFB0)
        private val chrNotify = uuid16(0xFFB2)
        private val chrWrite = uuid16(0xFFB1)
        private var lastRaw: Int? = null
        private var stableCount = 0
        private var published = false
        private var publishedKg = 0f

        override fun onConnected() {
            setNotifyOn(svc, chrNotify)
            val now = env.wallClock()
            writeTo(svc, chrWrite, icomonConfig(0xF7, 0, 0, 0))
            writeTo(svc, chrWrite, icomonConfig(0xFA, 0, 0, 0))
            writeTo(svc, chrWrite, icomonConfig(0xFB, if (user.isMale) 1 else 2, user.ageAt(now), user.heightCm.toInt().coerceIn(100, 220)))
            val t = Instant.ofEpochMilli(now).atZone(ZoneId.systemDefault())
            writeTo(svc, chrWrite, icomonConfig(0xFD, (t.year - 2000).coerceIn(0, 99), t.monthValue, t.dayOfMonth))
            writeTo(svc, chrWrite, icomonConfig(0xFC, t.hour, t.minute, t.second))
            writeTo(svc, chrWrite, icomonConfig(0xFE, 6, user.unit.ordinal, 0))
            userInfo("Súbete a la báscula")
        }

        override fun onNotification(characteristic: UUID, data: ByteArray) {
            if (characteristic != chrNotify || data.size < 20) return
            if (data[1] == 0x07.toByte() && data[2] == 0x00.toByte() && data[3] == 0xA2.toByte()) {
                val flag = Bytes.u8(data, 4)
                val raw = Bytes.fromUnsignedInt24Be(data, 6)
                val kg = raw / 1000.0f
                if (flag == 0x03) {
                    if (lastRaw == raw) stableCount++ else {
                        lastRaw = raw
                        stableCount = 1
                    }
                    if (!published && stableCount >= 8 && kg in 30f..250f) {
                        published = true
                        publishedKg = kg
                        publish(ScaleSample().apply { weightKg = kg })
                        requestDisconnect()
                    }
                } else {
                    live(kg)
                    if (!published || (publishedKg > 0f && kg < publishedKg * 0.5f)) {
                        stableCount = 0
                        lastRaw = null
                        if (published && kg < publishedKg * 0.5f) {
                            published = false
                            publishedKg = 0f
                        }
                    }
                }
                return
            }
            // Alternative "rich" indication carrying the weight after a 1F 01 0x marker.
            for (i in 0 until data.size - 4) {
                if (data[i] == 0x1F.toByte() && data[i + 1] == 0x01.toByte() && Bytes.u8(data, i + 2) in 1..3) {
                    val kg = Bytes.fromUnsignedInt24Be(data, i + 2) / 1000.0f
                    publish(ScaleSample().apply { weightKg = kg })
                    return
                }
            }
        }
    }
}

/** Vitafit VT701: `5A len 26 type … xor AA` frames; stable weight (type 0x10) then impedance (type 0x11). */
class VitafitVT701Driver : GattScaleDriver() {
    override val id = "vitafit_vt701"
    override val displayName = "Vitafit VT701"
    override val brand = "Vitafit"
    override val capabilities = ScaleCapabilities(impedance = true)
    override val formulaLib = FormulaLib.STANDARD_IMPEDANCE

    override fun match(adv: AdvertisementInfo): MatchConfidence? =
        if (adv.nameOrEmpty.startsWith("Vitafit", ignoreCase = true)) MatchConfidence.HIGH else null

    override fun newProtocol(adv: AdvertisementInfo?): GattProtocol = object : GattProtocol() {
        private val svc = uuid16(0xFFF0)
        private val chrNotify = uuid16(0xFFF1)
        private val chrWrite = uuid16(0xFFF2)
        private var savedKg = -1f
        private var published = false
        private var fallback: Cancellable? = null

        override fun onConnected() {
            setNotifyOn(svc, chrNotify)
            writeTo(svc, chrWrite, buildCommand(0x33, byteArrayOf(0x00)))
            writeTo(svc, chrWrite, buildCommand(0x44, byteArrayOf()))
            writeTo(svc, chrWrite, buildCommand(0x17, byteArrayOf(0x01)))
            userInfo("Súbete a la báscula")
        }

        override fun onNotification(characteristic: UUID, data: ByteArray) {
            if (characteristic != chrNotify || published) return
            parseStableWeightRaw(data)?.let { raw ->
                if (savedKg >= 0f) return
                savedKg = raw / 100.0f
                live(savedKg)
                writeTo(svc, chrWrite, buildCommand(TYPE_WEIGHT, byteArrayOf(STABLE_FLAG.toByte())))
                fallback = schedule(6_000) { if (!published && savedKg >= 0f) finish(0) }
                return
            }
            parseImpedanceOhm(data)?.let { ohm ->
                if (savedKg < 0f) return
                writeTo(svc, chrWrite, buildCommand(TYPE_IMPEDANCE, byteArrayOf(0x00)))
                finish(ohm)
            }
        }

        private fun finish(ohm: Int) {
            if (published) return
            published = true
            fallback?.cancel()
            publish(ScaleSample().apply {
                weightKg = savedKg
                if (ohm in 1 until 1500) impedance = ohm.toFloat()
            })
            requestDisconnect()
        }
    }

    companion object {
        private const val TYPE_WEIGHT = 0x10
        private const val TYPE_IMPEDANCE = 0x11
        private const val STABLE_FLAG = 0x02

        fun isValidScaleFrame(f: ByteArray): Boolean =
            f.size >= 6 && Bytes.u8(f, 0) == 0x5A && Bytes.u8(f, 2) == 0x26 && Bytes.u8(f, f.size - 1) == 0xAA &&
                Bytes.u8(f, 1) == f.size - 2 && Bytes.xor(f, 1, f.size - 2) == Bytes.u8(f, f.size - 2)

        fun parseStableWeightRaw(f: ByteArray): Int? {
            if (!isValidScaleFrame(f) || Bytes.u8(f, 3) != TYPE_WEIGHT || f.size < 12 || Bytes.u8(f, 4) != STABLE_FLAG) return null
            return Bytes.fromUnsignedInt16Be(f, 8)
        }

        fun parseImpedanceOhm(f: ByteArray): Int? {
            if (!isValidScaleFrame(f) || Bytes.u8(f, 3) != TYPE_IMPEDANCE || f.size < 13) return null
            return Bytes.fromUnsignedInt16Be(f, 9).takeIf { it > 0 }
        }

        fun buildCommand(type: Int, data: ByteArray): ByteArray {
            val frame = ByteArray(data.size + 6)
            frame[0] = 0xA5.toByte()
            frame[1] = (data.size + 4).toByte()
            frame[2] = 0x26
            frame[3] = type.toByte()
            data.copyInto(frame, 4)
            frame[frame.size - 2] = Bytes.xor(frame, 1, frame.size - 2).toByte()
            frame[frame.size - 1] = 0xAA.toByte()
            return frame
        }
    }
}

/** FFB0 "seq/len/00/type" family checksum: `sum(bytes[3..18]) & 0x1F` (Robi S9, Runstar R6). */
internal object Ffb0Frames {
    fun checksum(frame: ByteArray): Int = Bytes.sum8(frame, 3, 19) and 0x1F
    fun isValid(frame: ByteArray): Boolean = frame.size == 20 && Bytes.u8(frame, 19) == checksum(frame)

    fun build(seq: Int, type: Int, payload: ByteArray): ByteArray {
        val frame = ByteArray(20)
        frame[0] = seq.toByte()
        frame[1] = (payload.size + 1).toByte()
        frame[3] = type.toByte()
        payload.copyInto(frame, 4)
        frame[19] = checksum(frame).toByte()
        return frame
    }
}

/** Robi S9 (FFB0, replayed vendor handshake; final A3 result with weight in grams). */
class RobiS9Driver : GattScaleDriver() {
    override val id = "robi_s9"
    override val displayName = "Robi S9"
    override val brand = "Robi"
    override val capabilities = ScaleCapabilities()

    override fun match(adv: AdvertisementInfo): MatchConfidence? {
        val n = adv.nameOrEmpty.lowercase(Locale.ROOT)
        if (n.startsWith("swan") || n == "icomon" || n == "yg") return null
        return if (n.contains("robi")) MatchConfidence.HIGH else null
    }

    override fun newProtocol(adv: AdvertisementInfo?): GattProtocol = object : GattProtocol() {
        private val svc = uuid16(0xFFB0)
        private val chrWrite = uuid16(0xFFB1)
        private val chrLive = uuid16(0xFFB2)
        private val chrResult = uuid16(0xFFB3)

        override fun onConnected() {
            setNotifyOn(svc, chrLive)
            setNotifyOn(svc, chrResult)
            HANDSHAKE.forEach { writeTo(svc, chrWrite, Bytes.fromHex(it)) }
            userInfo("Súbete a la báscula")
        }

        override fun onNotification(characteristic: UUID, data: ByteArray) {
            val kg = parseFinalWeightKg(data) ?: return
            publish(ScaleSample().apply { weightKg = kg })
        }
    }

    companion object {
        /** Captured vendor handshake (Fitdays app), replayed verbatim in order. */
        val HANDSHAKE = listOf(
            "000300b000000000000000000000000000000010",
            "011000b16a2eefa9003c01aa1e55b20f1b581403",
            "021000b16a2eefa9003c01aa1e55b20f1b581403",
            "030600b201aa1e55b20000000000000000000002",
            "040200bd09000000000000000000000000000006",
            "051000b16a2eefa9003c01aa1e55b20f1b581403",
            "061000b16a2eefa9003c01aa1e55b20f1b581403",
            "070600b201aa1e55b20000000000000000000002",
            "081000b16a2eefa9003c01aa1e55b20f1b581403",
            "090300b001000000000000000000000000000011",
            "0a0300b002000000000000000000000000000012",
        )

        fun parseFinalWeightKg(f: ByteArray): Float? {
            if (f.size < 11 || Bytes.u8(f, 2) != 0x00 || Bytes.u8(f, 3) != 0xA3) return null
            val kg = Bytes.fromUnsignedInt24Be(f, 5) / 1000.0f
            return if (kg > 0f && kg.isFinite()) kg else null
        }
    }
}

/** Runstar R5 / RX (FFB0, ICOMON config frames; stable A2 frame with weight offset 0x680000 g). */
class RunstarR5Driver : GattScaleDriver() {
    override val id = "runstar_r5"
    override val displayName = "Runstar R5"
    override val brand = "Runstar"
    override val capabilities = ScaleCapabilities()
    override val keywords = listOf("RUNSTAR-RX")

    override fun match(adv: AdvertisementInfo): MatchConfidence? {
        val n = adv.upperName
        return if (n == "RUNSTAR-R5" || n == "RUNSTAR-RX") MatchConfidence.HIGH else null
    }

    override fun newProtocol(adv: AdvertisementInfo?): GattProtocol = object : GattProtocol() {
        private val svc = uuid16(0xFFB0)
        private val chrWrite = uuid16(0xFFB1)
        private val chrNotify = uuid16(0xFFB2)
        private var lastPublishedRaw: Int? = null

        override fun onConnected() {
            setNotifyOn(svc, chrNotify)
            val now = env.wallClock()
            writeTo(svc, chrWrite, icomonConfig(0xF7, 0, 0, 0))
            writeTo(svc, chrWrite, icomonConfig(0xFA, 0, 0, 0))
            writeTo(svc, chrWrite, icomonConfig(0xFB, if (user.isMale) 1 else 2, user.ageAt(now).coerceAtLeast(0), user.heightCm.toInt().coerceAtLeast(0)))
            val t = Instant.ofEpochMilli(now).atZone(ZoneId.systemDefault())
            writeTo(svc, chrWrite, icomonConfig(0xFD, (t.year - 2000).coerceIn(0, 99), t.monthValue, t.dayOfMonth))
            writeTo(svc, chrWrite, icomonConfig(0xFC, t.hour, t.minute, t.second))
            writeTo(svc, chrWrite, icomonConfig(0xFE, 6, unitCode(user.unit), 0))
            userInfo("Súbete a la báscula")
        }

        override fun onNotification(characteristic: UUID, data: ByteArray) {
            if (characteristic != chrNotify || data.size != 20 || data[3] != 0xA2.toByte()) return
            val raw = Bytes.fromUnsignedInt24Be(data, 6)
            val kg = decodeKg(raw)
            when (Bytes.u8(data, 4)) {
                0x01 -> live(kg)
                0x02 -> {
                    if (lastPublishedRaw == raw) return
                    lastPublishedRaw = raw
                    publish(ScaleSample().apply { weightKg = kg })
                    requestDisconnect()
                }
            }
        }
    }

    companion object {
        private const val WEIGHT_RAW_BASE = 0x680000

        fun decodeKg(raw: Int): Float = (raw - WEIGHT_RAW_BASE).coerceAtLeast(0) / 1000.0f

        fun unitCode(unit: ScaleUnit) = unit.ordinal
    }
}

/**
 * Runstar R6 (FFB0 frame family): live A2, final A3 (weight, heart rate, impedance), offline
 * history A4 (timestamp + weight, each acked with B0 so the scale retires it).
 */
class RunstarR6Driver : GattScaleDriver() {
    override val id = "runstar_r6"
    override val displayName = "Runstar R6"
    override val brand = "Runstar"
    override val capabilities = ScaleCapabilities(impedance = true, history = true)
    override val formulaLib = FormulaLib.STANDARD_IMPEDANCE

    override fun match(adv: AdvertisementInfo): MatchConfidence? =
        if (adv.nameOrEmpty.lowercase(Locale.ROOT).startsWith("runstar-r6")) MatchConfidence.HIGH else null

    override fun newProtocol(adv: AdvertisementInfo?): GattProtocol = object : GattProtocol() {
        private val svc = uuid16(0xFFB0)
        private val chrWrite = uuid16(0xFFB1)
        private val chrLive = uuid16(0xFFB2)
        private val chrResult = uuid16(0xFFB3)
        private var lastPublishedGrams: Int? = null
        private var outgoingSeq = 0

        override fun onConnected() {
            setNotifyOn(svc, chrLive)
            setNotifyOn(svc, chrResult)
            userInfo("Súbete a la báscula")
        }

        override fun onNotification(characteristic: UUID, data: ByteArray) {
            if (data.size != 20 || Bytes.u8(data, 2) != 0x00 || !Ffb0Frames.isValid(data)) return
            val seq = Bytes.u8(data, 0)
            when (Bytes.u8(data, 3)) {
                0xA2 -> {
                    val state = Bytes.u8(data, 4)
                    if (state == 0x01 || state == 0x02) live(Bytes.fromUnsignedInt24Be(data, 6) / 1000.0f)
                }
                0xA1 -> sendAck(seq)
                0xA3 -> {
                    val r = decodeFinalResult(data) ?: return
                    if (lastPublishedGrams != r.grams) {
                        publish(ScaleSample().apply {
                            weightKg = r.grams / 1000.0f
                            if (r.heartRate != 0) heartRate = r.heartRate
                            if (r.impedanceOhm in IMPEDANCE_MIN..IMPEDANCE_MAX) impedance = r.impedanceOhm.toFloat()
                        })
                        lastPublishedGrams = r.grams
                        sendAck(seq)
                    }
                }
                0xA4 -> {
                    publish(ScaleSample().apply {
                        dateTime = Bytes.fromUnsignedInt32Be(data, 4) * 1000L
                        weightKg = Bytes.fromUnsignedInt24Be(data, 9) / 1000.0f
                        fromHistory = true
                    })
                    sendAck(seq)
                }
            }
        }

        private fun sendAck(seq: Int) {
            writeTo(svc, chrWrite, Ffb0Frames.build(outgoingSeq, 0xB0, byteArrayOf(seq.toByte(), 0x00)))
            outgoingSeq = (outgoingSeq + 1) and 0xFF
        }
    }

    data class FinalResult(val status: Int, val grams: Int, val heartRate: Int, val impedanceOhm: Int)

    companion object {
        const val IMPEDANCE_MIN = 200
        const val IMPEDANCE_MAX = 1200

        fun decodeFinalResult(d: ByteArray): FinalResult? {
            if (d.size != 20 || Bytes.u8(d, 2) != 0x00 || Bytes.u8(d, 3) != 0xA3 || !Ffb0Frames.isValid(d)) return null
            return FinalResult(Bytes.u8(d, 4), Bytes.fromUnsignedInt24Be(d, 5), Bytes.u8(d, 8), Bytes.fromUnsignedInt16Be(d, 9))
        }
    }
}
