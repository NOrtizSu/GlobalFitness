// Protocol ported from openScale (GPL-3.0), https://github.com/oliexdev/openScale
package com.enjoyyourtime.globalfitness.scale.drivers

import com.enjoyyourtime.globalfitness.scale.AdvertisementInfo
import com.enjoyyourtime.globalfitness.scale.Bytes
import com.enjoyyourtime.globalfitness.scale.ConfigField
import com.enjoyyourtime.globalfitness.scale.ConfigKind
import com.enjoyyourtime.globalfitness.scale.DriverEnv
import com.enjoyyourtime.globalfitness.scale.FormulaLib
import com.enjoyyourtime.globalfitness.scale.LiveState
import com.enjoyyourtime.globalfitness.scale.MatchConfidence
import com.enjoyyourtime.globalfitness.scale.PassiveScaleDriver
import com.enjoyyourtime.globalfitness.scale.PassiveSession
import com.enjoyyourtime.globalfitness.scale.ScaleCapabilities
import com.enjoyyourtime.globalfitness.scale.ScaleReading
import com.enjoyyourtime.globalfitness.scale.ScaleSample
import com.enjoyyourtime.globalfitness.scale.libs.S400Aggregator
import com.enjoyyourtime.globalfitness.scale.libs.S400BodyComposition
import com.enjoyyourtime.globalfitness.scale.libs.S400Decryptor
import com.enjoyyourtime.globalfitness.scale.libs.S400Inputs
import com.enjoyyourtime.globalfitness.scale.libs.S400Packet

/**
 * Xiaomi Body Composition Scale S400 (XMTZC14HM): MiBeacon v5 advertisements (service 0xFE95)
 * encrypted with AES-CCM. Needs the 32-hex bind key from the Xiaomi cloud. Dual-frequency
 * impedance; composition computed at capture time with the S400 pipeline.
 */
class XiaomiS400Driver : PassiveScaleDriver() {
    override val id = "xiaomi_s400"
    override val displayName = "Xiaomi Body Composition Scale S400"
    override val brand = "Xiaomi"
    override val capabilities = ScaleCapabilities(
        impedance = true,
        config = listOf(
            ConfigField(
                key = ConfigField.KEY_BINDKEY,
                label = "Bind key (clave BLE)",
                help = "Clave de 32 caracteres hexadecimales de la nube de Xiaomi (p. ej. con Xiaomi Cloud Tokens Extractor).",
                kind = ConfigKind.HEX_KEY_32,
                required = true,
            ),
        ),
    )
    override val formulaLib = FormulaLib.S400
    override val keywords = listOf("XMTZC14HM", "S400", "Mi Body Composition Scale S400", "MiBeacon")

    override fun match(adv: AdvertisementInfo): MatchConfidence? {
        val name = adv.upperName
        if (name.contains("SCALE S400") || name.contains("XMTZC14HM")) return MatchConfidence.HIGH
        val beacon = adv.serviceData16(MIBEACON_SERVICE)
        if (beacon != null && beacon.size >= 5 && Bytes.fromUnsignedInt16Le(beacon, 2) == PRODUCT_ID) {
            return MatchConfidence.HIGH
        }
        if (name.startsWith("XMTZC")) return MatchConfidence.MEDIUM
        return null
    }

    override fun newSession(env: DriverEnv): PassiveSession = Session()

    private inner class Session : PassiveSession {
        private val aggregator = S400Aggregator()
        private var warned = false
        private var lastGrams: Long? = null
        private var lastAt = 0L
        override var live: LiveState = LiveState.Idle
            private set

        override fun parseAdvertisement(adv: AdvertisementInfo, env: DriverEnv): List<ScaleReading> {
            val bindKey = env.store.getString(ConfigField.KEY_BINDKEY)?.trim()?.lowercase()
            if (!S400Decryptor.isValidBindKey(bindKey)) {
                if (!warned) {
                    warned = true
                    env.messages("Báscula S400 detectada: configura su bind key en Básculas para leerla.")
                }
                return emptyList()
            }
            val data = serviceData(adv) ?: return emptyList()
            val packet = S400Decryptor.decrypt(data, adv.address, bindKey!!) ?: return emptyList()
            return ingest(packet, adv, env)
        }

        fun ingest(packet: S400Packet, adv: AdvertisementInfo, env: DriverEnv): List<ScaleReading> {
            if (packet.weightKg > 0f) live = LiveState.Stabilized(packet.weightKg.toDouble())
            val now = env.elapsedClock()
            val outcome = aggregator.ingest(adv.address, packet, now)
            if (outcome !is S400Aggregator.Outcome.Finalized) return emptyList()
            val grams = Math.round(outcome.weightKg * 1000.0)
            val previous = lastGrams
            if (previous != null && kotlin.math.abs(previous - grams) <= 50 && now - lastAt <= REBROADCAST_WINDOW_MS) {
                lastAt = now
                return emptyList()
            }
            lastGrams = grams
            lastAt = now
            val ctx = env.ctx
            val received = if (adv.receivedAtMillis > 0) adv.receivedAtMillis else env.wallClock()
            val comp = S400BodyComposition.compute(
                S400Inputs(
                    age = ctx.ageAt(received),
                    sexMale = ctx.isMale,
                    heightCm = ctx.heightCm,
                    weightKg = outcome.weightKg,
                    rHighRaw = outcome.impedanceHigh,
                    rLowRaw = outcome.impedanceLow ?: outcome.impedanceHigh,
                ),
            )
            val sample = ScaleSample().apply {
                weightKg = outcome.weightKg
                impedance = outcome.impedanceHigh
                impedanceLow = outcome.impedanceLow
                heartRate = outcome.heartRate
                fatPercent = comp.bfPct
                waterPercent = comp.tbwPct
                musclePercent = comp.smmPct
                muscleKg = comp.smmKg
                boneKg = comp.boneKg
                lbmKg = comp.ffmKg
                visceralFat = comp.vfi
                bmrKcal = comp.bmrKcal
                proteinPercent = comp.proteinPct
                computedWith = FormulaLib.S400
            }
            live = LiveState.Done
            return listOfNotNull(sample.toReading(env, FormulaLib.S400, received))
        }

        private fun serviceData(adv: AdvertisementInfo): ByteArray? {
            adv.serviceData16(MIBEACON_SERVICE)?.takeIf { it.size == 24 || it.size == 26 }?.let { return it }
            adv.serviceData16(0x181B)?.takeIf { it.size >= 24 }?.let { return it }
            return adv.serviceData.values.firstOrNull { it.size in 24..26 }
        }
    }

    companion object {
        const val MIBEACON_SERVICE = 0xFE95
        const val PRODUCT_ID = 0x3BD5
        private const val REBROADCAST_WINDOW_MS = 60_000L
    }
}
