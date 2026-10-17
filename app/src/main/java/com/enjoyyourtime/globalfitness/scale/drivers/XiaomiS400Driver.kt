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
import com.enjoyyourtime.globalfitness.scale.ScaleNotice
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
            ConfigField.bindKey(),
            ConfigField(key = KEY_PROFILE, kind = ConfigKind.USER_SLOT, maxSlot = 255),
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
        private var keyFailures = 0
        private var keyWarned = false
        private var lastGrams: Long? = null
        private var lastAt = 0L
        private var lastCounter: Long? = null
        private var pendingCounter: Long = 0
        private var pendingReceived: Long = 0
        override var live: LiveState = LiveState.Idle
            private set

        override val needsTick: Boolean get() = aggregator.hasPending

        override fun parseAdvertisement(adv: AdvertisementInfo, env: DriverEnv): List<ScaleReading> {
            val bindKey = env.store.getString(ConfigField.KEY_BINDKEY)?.trim()?.lowercase()
            if (!S400Decryptor.isValidBindKey(bindKey)) {
                if (!warned) {
                    warned = true
                    env.messages(ScaleNotice(ScaleNotice.Kind.BIND_KEY_NEEDED, listOf("S400")))
                }
                return emptyList()
            }
            val data = serviceData(adv) ?: return emptyList()
            val packet = S400Decryptor.decrypt(data, adv.address, bindKey!!)
            if (packet == null) {
                if (data.size >= 5 && Bytes.fromUnsignedInt16Le(data, if (data.size == 26) 4 else 2) == PRODUCT_ID) keyFailure(env)
                return emptyList()
            }
            keyFailures = 0
            return ingest(packet, adv, env)
        }

        private fun keyFailure(env: DriverEnv) {
            keyFailures++
            if (keyFailures >= KEY_FAILURES_BEFORE_HINT && !keyWarned) {
                keyWarned = true
                env.messages(ScaleNotice(ScaleNotice.Kind.BIND_KEY_WRONG, listOf("S400")))
            }
        }

        override fun tick(env: DriverEnv): List<ScaleReading> =
            aggregator.flush(env.elapsedClock()).flatMap { (_, outcome) -> finalized(outcome, pendingCounter, pendingReceived, env) }

        fun ingest(packet: S400Packet, adv: AdvertisementInfo, env: DriverEnv): List<ScaleReading> {
            val wanted = env.store.getString(KEY_PROFILE)?.toIntOrNull()
            if (wanted != null && packet.profileId != wanted) return emptyList()
            if (packet.counter != 0L && packet.counter == lastCounter) {
                live = LiveState.Done // a re-broadcast of the committed weighing
                return emptyList()
            }
            if (packet.weightKg > 0f) {
                live = LiveState.Stabilized(packet.weightKg.toDouble())
                pendingCounter = packet.counter
                pendingReceived = if (adv.receivedAtMillis > 0) adv.receivedAtMillis else env.wallClock()
            }
            val now = env.elapsedClock()
            val outcome = aggregator.ingest(adv.address, packet, now)
            if (outcome is S400Aggregator.Outcome.Duplicate) live = LiveState.Done
            if (outcome !is S400Aggregator.Outcome.Finalized) return emptyList()
            val received = if (adv.receivedAtMillis > 0) adv.receivedAtMillis else env.wallClock()
            return finalized(outcome, pendingCounter.takeIf { it != 0L } ?: packet.counter, received, env)
        }

        private fun finalized(outcome: S400Aggregator.Outcome.Finalized, counter: Long, received: Long, env: DriverEnv): List<ScaleReading> {
            val now = env.elapsedClock()
            lastCounter = counter.takeIf { it != 0L }
            live = LiveState.Done
            val grams = Math.round(outcome.weightKg * 1000.0)
            val previous = lastGrams
            if (previous != null && kotlin.math.abs(previous - grams) <= 50 && now - lastAt <= REBROADCAST_WINDOW_MS) {
                return emptyList()
            }
            lastGrams = grams
            lastAt = now
            val ctx = env.ctx
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
                skeletalMusclePercent = comp.smmPct
                boneKg = comp.boneKg
                lbmKg = comp.ffmKg
                visceralFat = comp.vfi
                bmrKcal = comp.bmrKcal
                proteinPercent = comp.proteinPct
                computedWith = FormulaLib.S400
            }
            val reading = sample.toReading(env, FormulaLib.S400, received) ?: return emptyList()
            // The MiBeacon counter identifies the weighing across app restarts (no scale clock).
            val key = if (counter != 0L) "${reading.driverId}|${env.address.orEmpty()}|c$counter|$grams" else reading.dedupeKey
            return listOf(reading.copy(dedupeKey = key))
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
        const val KEY_PROFILE = ConfigField.KEY_SCALE_PROFILE
        private const val REBROADCAST_WINDOW_MS = 60_000L
        private const val KEY_FAILURES_BEFORE_HINT = 10
    }
}
