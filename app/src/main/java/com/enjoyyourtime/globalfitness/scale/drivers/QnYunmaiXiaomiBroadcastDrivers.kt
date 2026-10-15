// Protocol ported from openScale (GPL-3.0), https://github.com/oliexdev/openScale
package com.enjoyyourtime.globalfitness.scale.drivers

import com.enjoyyourtime.globalfitness.scale.AdvertisementInfo
import com.enjoyyourtime.globalfitness.scale.Bytes
import com.enjoyyourtime.globalfitness.scale.ConfigField
import com.enjoyyourtime.globalfitness.scale.ConfigKind
import com.enjoyyourtime.globalfitness.scale.DriverEnv
import com.enjoyyourtime.globalfitness.scale.FormulaLib
import com.enjoyyourtime.globalfitness.scale.MatchConfidence
import com.enjoyyourtime.globalfitness.scale.PassiveScaleDriver
import com.enjoyyourtime.globalfitness.scale.PassiveSession
import com.enjoyyourtime.globalfitness.scale.ScaleCapabilities
import com.enjoyyourtime.globalfitness.scale.ScaleReading
import com.enjoyyourtime.globalfitness.scale.ScaleSample
import com.enjoyyourtime.globalfitness.scale.SimplePassiveSession
import com.enjoyyourtime.globalfitness.scale.libs.AesCcm

/**
 * QN / Renpho / FITINDEX scales in non-connectable broadcast mode: manufacturer data under
 * company id 0xFFFF starting with the magic `AA BB`; weight LE /100 at 17, status at 15.
 * Stable flag is bit 5, or bit 0 on the FITINDEX FT-26R family (recognised by a fixed signature).
 */
class QnBroadcastDriver : PassiveScaleDriver() {
    override val id = "qn_broadcast"
    override val displayName = "QN / Renpho / FITINDEX (anuncios AABB)"
    override val brand = "QN / Renpho / FITINDEX"
    override val capabilities = ScaleCapabilities()
    override val keywords = listOf("Yolanda", "FT-26R", "ES-CS20M", "AABB")

    override fun match(adv: AdvertisementInfo): MatchConfidence? {
        val data = adv.manufacturer(COMPANY_ID) ?: return null
        return when {
            parseFrame(data) != null -> MatchConfidence.HIGH
            hasMagic(data) && data.size >= 19 -> MatchConfidence.MEDIUM
            else -> null
        }
    }

    override fun newSession(env: DriverEnv): PassiveSession = object : SimplePassiveSession(null) {
        override fun parse(adv: AdvertisementInfo, env: DriverEnv): ScaleSample? {
            val frame = parseFrame(adv.manufacturer(COMPANY_ID)) ?: return null
            if (!frame.stable) {
                liveWeight(frame.weightKg)
                return null
            }
            return ScaleSample().apply { weightKg = frame.weightKg }
        }
    }

    data class Frame(val weightKg: Float, val statusByte: Int, val stable: Boolean, val isFt26rFamily: Boolean)

    companion object {
        const val COMPANY_ID = 0xFFFF
        private val FT26R_SIGNATURE = byteArrayOf(0x51, 0x0E, 0x03)

        fun hasMagic(data: ByteArray) = data.size >= 2 && data[0] == 0xAA.toByte() && data[1] == 0xBB.toByte()

        fun parseFrame(data: ByteArray?): Frame? {
            if (data == null || data.size < 19 || !hasMagic(data)) return null
            val kg = Bytes.fromUnsignedInt16Le(data, 17) / 100.0f
            if (kg < 0.5f || kg > 300f) return null
            val status = Bytes.u8(data, 15)
            val ft26r = data.size >= 22 && FT26R_SIGNATURE.indices.all { data[19 + it] == FT26R_SIGNATURE[it] }
            val mask = if (ft26r) 0x21 else 0x20
            return Frame(kg, status, status and mask != 0, ft26r)
        }
    }
}

/**
 * Yunmai X (YMBS-M268): nameless broadcast scale (service 0x1320). The 14-byte manufacturer
 * payload echoes the MAC, carries the signature 0B 74 17, a state (1 live, 2 stable, 3 final with
 * impedance), weight and impedance (BE) and an XOR checksum. Composition with the Yunmai formulas.
 */
class YunmaiXDriver : PassiveScaleDriver() {
    override val id = "yunmai_x"
    override val displayName = "Yunmai X (YMBS-M268)"
    override val brand = "Yunmai"
    override val capabilities = ScaleCapabilities(impedance = true)
    override val formulaLib = FormulaLib.YUNMAI
    override val keywords = listOf("YMBS-M268", "YUNMAI-ADV")

    override fun match(adv: AdvertisementInfo): MatchConfidence? {
        if (adv.manufacturerData.any { (id, data) -> parseFrame(id, data, adv.address) != null }) return MatchConfidence.HIGH
        if (adv.hasService16(SERVICE16) && adv.nameOrEmpty.isEmpty()) return MatchConfidence.MEDIUM
        return null
    }

    override fun newSession(env: DriverEnv): PassiveSession = object : SimplePassiveSession(FormulaLib.YUNMAI) {
        private var armed = true

        override fun parse(adv: AdvertisementInfo, env: DriverEnv): ScaleSample? {
            val f = adv.manufacturerData.entries.firstNotNullOfOrNull { (id, data) -> parseFrame(id, data, adv.address) } ?: return null
            return when (f.state) {
                STATE_LIVE, STATE_STABLE -> {
                    armed = true
                    liveWeight(f.weightKg)
                    null
                }
                STATE_FINAL -> {
                    if (!armed || f.weightKg <= 0f) return null
                    armed = false
                    ScaleSample().apply {
                        weightKg = f.weightKg
                        if (f.impedanceOhm in 1..3000) impedance = f.impedanceOhm.toFloat()
                    }
                }
                else -> null
            }
        }
    }

    data class Frame(val state: Int, val weightKg: Float, val impedanceOhm: Int)

    companion object {
        const val SERVICE16 = 0x1320
        const val STATE_LIVE = 0x01
        const val STATE_STABLE = 0x02
        const val STATE_FINAL = 0x03

        /** [address] enables the MAC-echo check (skipped when null). */
        fun parseFrame(manufacturerId: Int, payload: ByteArray?, address: String?): Frame? {
            if (payload == null || payload.size != 14) return null
            if (payload[4] != 0x0B.toByte() || payload[5] != 0x74.toByte() || payload[6] != 0x17.toByte()) return null
            if (Bytes.xor(payload, 7, 13) != Bytes.u8(payload, 13)) return null
            val octets = address?.split(":")?.mapNotNull { it.toIntOrNull(16) }
            if (octets != null && octets.size == 6) {
                val echo = manufacturerId == ((octets[4] shl 8) or octets[5]) &&
                    Bytes.u8(payload, 0) == octets[3] && Bytes.u8(payload, 1) == octets[2] &&
                    Bytes.u8(payload, 2) == octets[1] && Bytes.u8(payload, 3) == octets[0]
                if (!echo) return null
            }
            return Frame(Bytes.u8(payload, 8), Bytes.fromUnsignedInt16Be(payload, 9) / 100.0f, Bytes.fromUnsignedInt16Be(payload, 11))
        }
    }
}

/**
 * Xiaomi Mijia Body Composition Scale S800 (MS116): MiBeacon v5 (0xFE95, product 0x51E2)
 * encrypted with the bind key; object 0x4E16 carries the weight. Weight only (segmental data is
 * only available over the encrypted Mi-auth GATT path).
 */
class XiaomiS800Driver : PassiveScaleDriver() {
    override val id = "xiaomi_s800"
    override val displayName = "Xiaomi Body Composition Scale S800"
    override val brand = "Xiaomi"
    override val capabilities = ScaleCapabilities(
        config = listOf(
            ConfigField(
                key = ConfigField.KEY_BINDKEY,
                label = "Bind key (clave BLE)",
                help = "Clave de 32 caracteres hexadecimales de la nube de Xiaomi.",
                kind = ConfigKind.HEX_KEY_32,
                required = true,
            ),
        ),
    )
    override val keywords = listOf("MS116", "MIJIA SCALE S800", "S800")

    override fun match(adv: AdvertisementInfo): MatchConfidence? {
        val name = adv.upperName
        if (name.contains("MIJIA SCALE S800") || name.contains("MS116")) return MatchConfidence.HIGH
        val data = adv.serviceData16(0xFE95)
        if (data != null && data.size >= 4 && Bytes.fromUnsignedInt16Le(data, 2) == PRODUCT_ID) return MatchConfidence.HIGH
        return null
    }

    override fun newSession(env: DriverEnv): PassiveSession = object : SimplePassiveSession(null) {
        private var cachedMac: ByteArray? = null
        private var warned = false
        private var keyFailures = 0
        private var keyWarned = false
        private var lastCounter: Long? = null

        override fun parseAdvertisement(adv: AdvertisementInfo, env: DriverEnv): List<ScaleReading> {
            val frame = adv.serviceData16(0xFE95)
            val counter = frame?.let { frameCounter(it) }
            if (counter != null && counter == lastCounter) return emptyList() // same packet re-broadcast
            val readings = super.parseAdvertisement(adv, env)
            if (readings.isEmpty() || counter == null) return readings
            lastCounter = counter
            // The MiBeacon counter identifies the weighing across app restarts (no scale clock).
            return readings.map { r -> r.copy(dedupeKey = "${r.driverId}|${env.address.orEmpty()}|c$counter|${ScaleReading.grams(r.weightKg)}") }
        }

        override fun parse(adv: AdvertisementInfo, env: DriverEnv): ScaleSample? {
            val key = env.store.getString(ConfigField.KEY_BINDKEY)?.trim()
            if (key == null || key.length != 32 || !Bytes.isHex(key)) {
                if (!warned) {
                    warned = true
                    env.messages("Báscula S800 detectada: configura su bind key en Básculas para leerla.")
                }
                return null
            }
            val frame = adv.serviceData16(0xFE95) ?: return null
            if (frame.size >= 4 && Bytes.fromUnsignedInt16Le(frame, 2) != PRODUCT_ID) return null
            macFrameOrder(frame)?.let { cachedMac = it }
            val mac = cachedMac ?: runCatching { Bytes.fromHex(adv.address).reversedArray() }.getOrNull() ?: return null
            val plain = decrypt(frame, Bytes.fromHex(key), mac)
            if (plain == null) {
                if (frame.size >= 12 && Bytes.fromUnsignedInt16Le(frame, 0) and FC_ENCRYPTED != 0 && ++keyFailures >= 10 && !keyWarned) {
                    keyWarned = true
                    env.messages("La bind key de la S800 no descifra sus datos: revísala en Básculas.")
                }
                return null
            }
            keyFailures = 0
            val kg = parseWeightKg(plain) ?: return null
            return ScaleSample().apply { weightKg = kg }
        }
    }

    companion object {
        const val PRODUCT_ID = 0x51E2
        const val FC_ENCRYPTED = 0x08
        private const val FC_MAC_INCLUDED = 0x10

        private const val FC_CAPABILITY = 0x20
        private const val CAP_IO = 0x20

        fun macFrameOrder(data: ByteArray): ByteArray? {
            if (data.size < 11) return null
            if (Bytes.fromUnsignedInt16Le(data, 0) and FC_MAC_INCLUDED == 0) return null
            return data.copyOfRange(5, 11)
        }

        /** Frame counter (extended counter << 8 | counter) of an encrypted frame, else null. */
        fun frameCounter(data: ByteArray): Long? {
            if (data.size < 12 || Bytes.fromUnsignedInt16Le(data, 0) and FC_ENCRYPTED == 0) return null
            return (Bytes.fromUnsignedInt24Le(data, data.size - 7).toLong() shl 8) or Bytes.u8(data, 4).toLong()
        }

        /** Start of the encrypted object: after the optional MAC and capability (+ IO capability) bytes. */
        private fun payloadStart(data: ByteArray, fc: Int): Int? {
            var start = 5
            if (fc and FC_MAC_INCLUDED != 0) start += 6
            if (fc and FC_CAPABILITY != 0) {
                if (data.size <= start) return null
                val capability = Bytes.u8(data, start)
                start += 1
                if (capability and CAP_IO != 0) start += 2
            }
            return start
        }

        /** `FC | PID | cnt | [MAC] | [cap [io]] | cipher | extCnt(3) | MIC(4)`; nonce = MAC + data[2..5) + extCnt. */
        fun decrypt(data: ByteArray, bindKey: ByteArray, macFrameOrder: ByteArray): ByteArray? {
            if (data.size < 12 || bindKey.size != 16 || macFrameOrder.size != 6) return null
            val fc = Bytes.fromUnsignedInt16Le(data, 0)
            if (fc and FC_ENCRYPTED == 0) return null
            val start = payloadStart(data, fc) ?: return null
            if (data.size < start + 7) return null
            val cipher = data.copyOfRange(start, data.size - 7)
            val ext = data.copyOfRange(data.size - 7, data.size - 4)
            val mic = data.copyOfRange(data.size - 4, data.size)
            val nonce = macFrameOrder + data.copyOfRange(2, 5) + ext
            return runCatching { AesCcm.decrypt(bindKey, nonce, cipher + mic, byteArrayOf(0x11), 4) }.getOrNull()
        }

        /** Object 0x4E16: trailing u16 LE / 100 at value offset 7 (10..250 kg). */
        fun parseWeightKg(decrypted: ByteArray): Float? {
            if (decrypted.size < 3) return null
            val type = Bytes.fromUnsignedInt16Le(decrypted, 0)
            val len = Bytes.u8(decrypted, 2)
            if (type != 0x4E16 || len < 9 || decrypted.size < 3 + len) return null
            val w = Bytes.fromUnsignedInt16Le(decrypted, 3 + 7) / 100.0f
            return if (w in 10f..250f) w else null
        }
    }
}
