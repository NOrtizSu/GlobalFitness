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
import com.enjoyyourtime.globalfitness.scale.uuid16
import java.util.Locale
import java.util.TimeZone
import java.util.UUID
import kotlin.math.roundToInt

/**
 * RyFit (云悦) / Chronocloud: FFE0/FFE1; time sync, then the user profile (guest slot 9) is sent
 * when weight appears; packet 1 (weight, fat, water) + packet 2 (muscle, bone %, BMR, visceral).
 */
class RyFitDriver : GattScaleDriver() {
    override val id = "ryfit"
    override val displayName = "RyFit (云悦) / Chronocloud"
    override val brand = "RyFit"
    override val capabilities = ScaleCapabilities(deviceComposition = true)
    override val keywords = listOf("Chronocloud", "云悦")

    override fun match(adv: AdvertisementInfo): MatchConfidence? {
        val n = adv.upperName
        return when {
            n.contains("CHRONOCLOUD") || n.contains("RYFIT") -> MatchConfidence.HIGH
            adv.address.uppercase(Locale.ROOT).startsWith("D0:39:72") -> MatchConfidence.LOW
            else -> null
        }
    }

    override fun newProtocol(adv: AdvertisementInfo?): GattProtocol = Protocol()

    internal class Protocol : GattProtocol() {
        private val svc = UUID.fromString("0000ffe0-0000-1000-8000-00805f9b34fb")
        private val chr = UUID.fromString("0000ffe1-0000-1000-8000-00805f9b34fb")
        private var pendingWeight: Float? = null
        private var pendingFat: Float? = null
        private var pendingWater: Float? = null
        private var measuring = false
        private var c0Triggered = false
        private var timeout: Cancellable? = null
        private val bursts = ArrayList<Cancellable>()

        override fun onConnected() {
            setNotifyOn(svc, chr)
            val ts = env.wallClock() / 1000L
            writeTo(svc, chr, byteArrayOf(0xFA.toByte(), 0xF8.toByte()) + Bytes.toInt32Le(ts))
        }

        override fun onNotification(characteristic: UUID, data: ByteArray) {
            if (characteristic != chr || data.isEmpty()) return
            when (Bytes.u8(data, 0)) {
                0xD2 -> liveWeight(data)
                0xD0, 0xD1 -> if (data.size >= 14) {
                    if (Bytes.u8(data, 2) and 0x01 == 1) packet1(data) else packet2(data)
                }
                0xFB -> if (data.size >= 2 && Bytes.u8(data, 1) == 0xF8) sendC0()
            }
        }

        private fun sendC0() {
            val height = user.heightCm.toInt().coerceIn(50, 250)
            val age = user.ageAt(env.wallClock()).coerceIn(10, 100)
            writeTo(svc, chr, byteArrayOf(0xC0.toByte(), GUEST_SLOT.toByte(), (2 * height - 200).coerceIn(0, 255).toByte(), age.toByte(), if (user.isMale) 1 else 0))
        }

        private fun burstC0(initialDelay: Long) {
            for (i in 0 until 3) bursts += schedule(initialDelay + i * 500L) { sendC0() }
        }

        private fun liveWeight(data: ByteArray) {
            if (data.size < 4) return
            val kg = Bytes.fromUnsignedInt16Be(data, 2) / 10.0f
            if (kg <= 0.1f) {
                if (measuring || c0Triggered) reset()
                return
            }
            live(kg)
            if (measuring || c0Triggered) return
            c0Triggered = true
            burstC0(1_000)
            timeout?.cancel()
            timeout = schedule(15_000) {
                if (!measuring) {
                    c0Triggered = false
                    burstC0(0)
                }
            }
        }

        private fun packet1(d: ByteArray) {
            measuring = true
            pendingWeight = Bytes.fromUnsignedInt16Be(d, 8) / 10.0f
            pendingFat = Bytes.fromUnsignedInt16Be(d, 10) / 10.0f
            pendingWater = Bytes.fromUnsignedInt16Be(d, 12) / 10.0f
            ack(d)
        }

        private fun packet2(d: ByteArray) {
            val weight = pendingWeight
            val fat = pendingFat
            if (weight == null || fat == null) {
                reset()
                return
            }
            val visceralLevel = Bytes.u8(d, 12)
            ack(d)
            publish(ScaleSample().apply {
                weightKg = weight
                fatPercent = fat
                waterPercent = pendingWater
                musclePercent = Bytes.fromUnsignedInt16Be(d, 4) / 10.0f
                boneKg = weight * (Bytes.fromUnsignedInt16Be(d, 6) / 10.0f) / 100.0f
                bmrKcal = Bytes.fromUnsignedInt16Be(d, 8).toFloat()
                visceralFat = if (visceralLevel < 10) visceralLevel.toFloat() else 13.0f
                lbmKg = weight * (1 - fat / 100.0f)
            })
            reset()
        }

        private fun ack(d: ByteArray) {
            if (d.size >= 3) writeTo(svc, chr, byteArrayOf(0xFA.toByte(), d[0], d[1], d[2]))
        }

        private fun reset() {
            timeout?.cancel()
            timeout = null
            measuring = false
            pendingWeight = null
            pendingFat = null
            pendingWater = null
            c0Triggered = false
        }

        override fun onDisconnected() {
            timeout?.cancel()
            bursts.forEach { it.cancel() }
        }

        companion object {
            const val GUEST_SLOT = 9
        }
    }
}

/**
 * Cult Smart Scale Pro (8-electrode, FFF0/FFF4): live weight (0xCF), raw multi-frequency
 * impedance (0xBE; whole-body = value 2 of set 0) and four proprietary result packets (0xDF)
 * marking the end. Composition from the whole-body impedance (standard formulas).
 */
class CultSmartScaleProDriver : GattScaleDriver() {
    override val id = "cult_smart_scale_pro"
    override val displayName = "Cult Smart Scale Pro"
    override val brand = "Cult"
    override val capabilities = ScaleCapabilities(impedance = true)
    override val formulaLib = FormulaLib.STANDARD_IMPEDANCE

    override fun match(adv: AdvertisementInfo): MatchConfidence? = when {
        adv.upperName.startsWith("CULT SMART SCALE") -> MatchConfidence.HIGH
        adv.upperName.startsWith("CULT") -> MatchConfidence.MEDIUM
        else -> null
    }

    override fun newProtocol(adv: AdvertisementInfo?): GattProtocol = object : GattProtocol() {
        private val svc = uuid16(0xFFF0)
        private val chrNotify = uuid16(0xFFF4)
        private var pendingWeight = 0f
        private var pendingImpedance = 0
        private var bodyCompCount = 0
        private var stableCount = 0

        override fun onConnected() {
            setNotifyOn(svc, chrNotify)
            userInfo("Súbete a la báscula")
        }

        override fun onDisconnected() {
            if (pendingWeight > 0f && bodyCompCount == 0 && stableCount > 0) publishCurrent(withImpedance = false)
        }

        override fun onNotification(characteristic: UUID, data: ByteArray) {
            if (characteristic != chrNotify || data.isEmpty()) return
            when (data[0]) {
                0xCF.toByte() -> {
                    if (data.size < 11) return
                    val kg = Bytes.fromUnsignedInt16Le(data, 3) / 100.0f
                    when (val status = Bytes.u8(data, 9)) {
                        0x01 -> live(kg)
                        else -> {
                            if (kg <= 0f) return
                            pendingWeight = kg
                            stableCount++
                            if (status == 0x00) publishCurrent(withImpedance = false)
                        }
                    }
                }
                0xBE.toByte() -> if (data.size >= 13 && Bytes.u8(data, 1) == 0x00) pendingImpedance = Bytes.fromUnsignedInt16Le(data, 6)
                0xDF.toByte() -> if (data.size >= 17) {
                    bodyCompCount++
                    if (bodyCompCount >= 4 && pendingImpedance > 0 && pendingWeight > 0f) {
                        publishCurrent(withImpedance = true)
                        requestDisconnect()
                    }
                }
            }
        }

        private fun publishCurrent(withImpedance: Boolean) {
            publish(ScaleSample().apply {
                weightKg = pendingWeight
                if (withImpedance && pendingImpedance > 0) impedance = pendingImpedance.toFloat()
            })
        }
    }
}

/**
 * Realme Smart Scale (service A602): handshake on A624 and a 1 s keep-alive on A622; frames on
 * A621 are XOR-obfuscated with the scale MAC. Weight, scale time and impedance (Yunmai formulas).
 */
class RealmeSmartScaleDriver : GattScaleDriver() {
    override val id = "realme_smart_scale"
    override val displayName = "realme Smart Scale"
    override val brand = "realme"
    override val capabilities = ScaleCapabilities(impedance = true, history = true)
    override val formulaLib = FormulaLib.YUNMAI

    override fun match(adv: AdvertisementInfo): MatchConfidence? {
        val n = adv.nameOrEmpty.lowercase(Locale.ROOT)
        val service = adv.serviceUuids.contains(SVC)
        return when {
            n.startsWith("realme smart scale") || (service && n.contains("realme")) -> MatchConfidence.HIGH
            n.contains("realme") || service -> MatchConfidence.MEDIUM
            else -> null
        }
    }

    override fun newProtocol(adv: AdvertisementInfo?): GattProtocol = Protocol()

    internal class Protocol : GattProtocol() {
        private val chrData = UUID.fromString("0000a621-0000-1000-8000-00805f9b34fb")
        private val chrKeepAlive = UUID.fromString("0000a622-0000-1000-8000-00805f9b34fb")
        private val chrHandshake = UUID.fromString("0000a624-0000-1000-8000-00805f9b34fb")
        private val chrAux = UUID.fromString("0000a625-0000-1000-8000-00805f9b34fb")
        private var mac = ByteArray(6)
        private var keepAlive: Cancellable? = null
        private var running = false

        override fun onConnected() {
            mac = macBytes(env.address)
            setNotifyOn(SVC, chrData)
            setNotifyOn(SVC, chrAux)
            buildHandshake().forEach { writeTo(SVC, chrHandshake, it) }
            running = true
            keepAlive = schedule(500) { tickKeepAlive() }
        }

        private fun tickKeepAlive() {
            if (!running) return
            writeTo(SVC, chrKeepAlive, KEEP_ALIVE)
            keepAlive = schedule(1_000) { tickKeepAlive() }
        }

        override fun onDisconnected() {
            running = false
            keepAlive?.cancel()
        }

        override fun onNotification(characteristic: UUID, data: ByteArray) {
            if (characteristic != chrData || data.size < 19 || data[0] != 0x10.toByte() || data[1] != 0x11.toByte()) return
            val p = xorWithMac(data.copyOfRange(2, data.size), mac)
            val kg = Bytes.fromUnsignedInt16Be(p, 8) / 100.0f
            if (kg <= 0.5f || kg > 300f) return
            val scaleTime = Bytes.fromUnsignedInt32Be(p, 10)
            val imp = Bytes.fromUnsignedInt16Be(p, 14)
            publish(ScaleSample().apply {
                weightKg = kg
                if (scaleTime > 0) dateTime = scaleTime * 1000L
                if (imp > 0) impedance = imp.toFloat()
            })
            finishSoon() // also stops the keep-alive (timers end with the session)
        }

        private fun buildHandshake(): List<ByteArray> {
            val now = env.wallClock()
            val ts = (now / 1000L).toInt()
            val tz = (TimeZone.getDefault().getOffset(now) / 60000).toByte()
            val h = user.heightCm.roundToInt()
            val w = user.lastWeightKg?.takeIf { it > 0f }?.let { (it * 100f).roundToInt() } ?: 0xFFFF
            val payloads = listOf(
                byteArrayOf(0x00, 0x08, 0x01, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x02),
                byteArrayOf(0x00, 0x0A, 0x18, (ts shr 24).toByte(), (ts shr 16).toByte(), (ts shr 8).toByte(), ts.toByte(), tz),
                byteArrayOf(0x48, 0x01, 0x00, 0x01),
                byteArrayOf(0x10, 0x01, 0x01, if (user.isMale) 0x00 else 0x80.toByte(), user.ageAt(now).toByte(), (h shr 8).toByte(), h.toByte(), 0x00, 0x00, (w shr 8).toByte(), w.toByte()),
                byteArrayOf(0x10, 0x04, 0x00),
                byteArrayOf(0x10, 0x07, 0x01),
            )
            return payloads.map { byteArrayOf(0x10, it.size.toByte()) + xorWithMac(it, mac) }
        }
    }

    companion object {
        val SVC: UUID = UUID.fromString("0000a602-0000-1000-8000-00805f9b34fb")
        private val KEEP_ALIVE = byteArrayOf(0x00, 0x01, 0xD9.toByte())

        fun macBytes(address: String?): ByteArray =
            runCatching { Bytes.fromHex(address.orEmpty()) }.getOrNull()?.takeIf { it.size == 6 } ?: ByteArray(6)

        fun xorWithMac(data: ByteArray, mac: ByteArray): ByteArray =
            ByteArray(data.size) { i -> (data[i].toInt() xor (mac[i % 6].toInt() and 0xFF)).toByte() }
    }
}

/** Healthkeep Smart Scale (FFB0): profile frame, live weight (FFB2), final A3 indication (FFB3) with HR + impedance. */
class HealthKeep280Driver : GattScaleDriver() {
    override val id = "healthkeep_280"
    override val displayName = "Healthkeep Smart Scale (280)"
    override val brand = "Healthkeep"
    override val capabilities = ScaleCapabilities(impedance = true)
    override val formulaLib = FormulaLib.STANDARD_IMPEDANCE

    override fun match(adv: AdvertisementInfo): MatchConfidence? =
        if (adv.upperName.startsWith("HEALTHKEEP")) MatchConfidence.HIGH else null

    override fun newProtocol(adv: AdvertisementInfo?): GattProtocol = object : GattProtocol() {
        private val svc = uuid16(0xFFB0)
        private val chrWrite = uuid16(0xFFB1)
        private val chrNotify = uuid16(0xFFB2)
        private val chrIndicate = uuid16(0xFFB3)

        override fun onConnected() {
            setNotifyOn(svc, chrNotify)
            setNotifyOn(svc, chrIndicate)
            val payload = byteArrayOf(
                0xAC.toByte(), 0x02, 0xFB.toByte(), if (user.isMale) 1 else 2, user.ageAt(env.wallClock()).toByte(),
                user.heightCm.toInt().toByte(), 0xCC.toByte(), 0,
            )
            payload[7] = Bytes.sum8(payload, 0, 7).toByte()
            writeTo(svc, chrWrite, payload)
            userInfo("Súbete a la báscula")
        }

        override fun onNotification(characteristic: UUID, data: ByteArray) {
            if (characteristic == chrNotify && data.size >= 9) {
                val status = Bytes.u8(data, 4)
                if (status == 0x01 || status == 0x02) live(Bytes.fromUnsignedInt24Be(data, 6) / 1000.0f)
            }
            if (characteristic == chrIndicate && data.size >= 11 && Bytes.u8(data, 3) == 0xA3) {
                val hr = Bytes.u8(data, 8)
                val imp = Bytes.fromUnsignedInt16Be(data, 9)
                publish(ScaleSample().apply {
                    weightKg = Bytes.fromUnsignedInt24Be(data, 5) / 1000.0f
                    if (hr > 0) heartRate = hr
                    if (imp > 0) impedance = imp.toFloat()
                })
                requestDisconnect()
            }
        }
    }
}
