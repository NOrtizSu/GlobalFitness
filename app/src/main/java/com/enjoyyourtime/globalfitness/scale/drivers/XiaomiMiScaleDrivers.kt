package com.enjoyyourtime.globalfitness.scale.drivers

import com.enjoyyourtime.globalfitness.ble.AssemblerState
import com.enjoyyourtime.globalfitness.ble.CompletedMeasurement
import com.enjoyyourtime.globalfitness.ble.MeasurementAssembler
import com.enjoyyourtime.globalfitness.ble.MiScaleParser
import com.enjoyyourtime.globalfitness.scale.AdvertisementInfo
import com.enjoyyourtime.globalfitness.scale.CompositionSource
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
import com.enjoyyourtime.globalfitness.scale.uuid16
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * Xiaomi Mi Body Composition Scale 2 (XMTZC05HM): passive 0x181B advertisement with weight and
 * impedance. Wraps the verified [MiScaleParser] + [MeasurementAssembler] path unchanged: same
 * commit rules, same dedupe keys ("scale time|raw weight"), timestamp = receive time.
 */
class MiScale2Driver : PassiveScaleDriver() {
    override val id = ID
    override val displayName = "Xiaomi Mi Body Composition Scale 2"
    override val brand = "Xiaomi"
    override val verified = true
    override val capabilities = ScaleCapabilities(impedance = true)
    override val formulaLib = FormulaLib.MI_SCALE
    override val keywords = listOf("XMTZC05HM", "MIBCS", "MIBFS", "Mi Scale 2", "Mi Body Composition", "Mi Smart Scale")

    override fun match(adv: AdvertisementInfo): MatchConfidence? {
        val data = adv.serviceData16(0x181B)
        if (data != null && MiScaleParser.parse(data) != null) return MatchConfidence.HIGH
        val name = adv.upperName
        if (name == "MIBCS" || name == "MIBFS" || name == "MI SCALE2") return MatchConfidence.HIGH
        return null
    }

    override fun newSession(env: DriverEnv): PassiveSession = Session(env)

    private inner class Session(env: DriverEnv) : PassiveSession {
        private val assembler = MeasurementAssembler(env.wallClock, env.elapsedClock)

        override val live: LiveState
            get() = when (val s = assembler.state) {
                AssemblerState.Idle -> LiveState.Idle
                is AssemblerState.Measuring -> LiveState.Measuring(s.weightKg)
                is AssemblerState.WaitingImpedance -> LiveState.Stabilized(s.weightKg)
                is AssemblerState.Saved -> LiveState.Done
            }

        override val needsTick: Boolean get() = assembler.needsTick

        override fun parseAdvertisement(adv: AdvertisementInfo, env: DriverEnv): List<ScaleReading> {
            val frame = MiScaleParser.parse(adv.serviceData16(0x181B)) ?: return emptyList()
            return assembler.onFrame(frame).map { it.toReading(env) }
        }

        override fun tick(env: DriverEnv): List<ScaleReading> = assembler.tick().map { it.toReading(env) }
    }

    companion object {
        const val ID = "xiaomi_mi_scale_2"

        fun CompletedMeasurement.toReading(env: DriverEnv): ScaleReading = ScaleReading(
            weightKg = weightKg,
            impedance = impedance,
            composition = null,
            measuredAtMillis = receivedAtMillis,
            receivedAtMillis = receivedAtMillis,
            dedupeKey = scaleKey,
            compositionSource = if (impedance != null) CompositionSource.Formula(FormulaLib.MI_SCALE) else CompositionSource.None,
            formulaLib = if (impedance != null) FormulaLib.MI_SCALE else null,
            driverId = ID,
            deviceAddress = env.address,
            isImpedanceUpgrade = isImpedanceUpgrade,
        )
    }
}

/**
 * Xiaomi Mi Smart Scale (v1, XMTZC01HM / XMTZC04HM): passive 0x181D advertisement, weight only.
 * Payload layout ported from openScale's Mi Scale history record:
 * `[status][weight LE(2)][year LE(2)][month][day][hour][min][sec]`, status bit0 = lb, bit4 = jin,
 * bit5 = stabilized, bit7 = weight removed.
 */
// Protocol ported from openScale (GPL-3.0), https://github.com/oliexdev/openScale
class MiScale1Driver : PassiveScaleDriver() {
    override val id = "xiaomi_mi_scale_1"
    override val displayName = "Xiaomi Mi Smart Scale (1ª gen.)"
    override val brand = "Xiaomi"
    override val capabilities = ScaleCapabilities()
    override val keywords = listOf("XMTZC01HM", "XMTZC04HM", "MI_SCALE", "MI SCALE", "Mi Scale v1")

    override fun match(adv: AdvertisementInfo): MatchConfidence? {
        val data = adv.serviceData16(0x181D)
        val name = adv.upperName
        val miName = name.startsWith("MI_SCALE") || name == "MI SCALE" || name.startsWith("MI SCALE ")
        return when {
            data != null && data.size == FRAME_LENGTH && miName -> MatchConfidence.HIGH
            data != null && data.size == FRAME_LENGTH -> MatchConfidence.MEDIUM
            miName -> MatchConfidence.HIGH
            else -> null
        }
    }

    override fun newSession(env: DriverEnv): PassiveSession = object : SimplePassiveSession(formulaLib = null) {
        override fun parse(adv: AdvertisementInfo, env: DriverEnv): ScaleSample? {
            val frame = parseFrame(adv.serviceData16(0x181D)) ?: return null
            if (frame.removed) return null
            if (!frame.stable) {
                liveWeight(frame.weightKg)
                return null
            }
            return ScaleSample().apply {
                weightKg = frame.weightKg
                dateTime = frame.scaleTimeMillis
            }
        }
    }

    data class Frame(val weightKg: Float, val stable: Boolean, val removed: Boolean, val scaleTimeMillis: Long?)

    companion object {
        const val FRAME_LENGTH = 10
        private const val LB_TO_KG = 0.45359237f

        fun parseFrame(d: ByteArray?): Frame? {
            if (d == null || d.size != FRAME_LENGTH) return null
            val status = d[0].toInt() and 0xFF
            val isLbs = status and 0x01 != 0
            val isCatty = status and 0x10 != 0
            val stable = status and 0x20 != 0
            val removed = status and 0x80 != 0
            val raw = ((d[2].toInt() and 0xFF) shl 8) or (d[1].toInt() and 0xFF)
            val kg = when {
                isLbs -> raw / 100f * LB_TO_KG
                isCatty -> raw / 100f * 0.5f
                else -> raw / 200f
            }
            val year = ((d[4].toInt() and 0xFF) shl 8) or (d[3].toInt() and 0xFF)
            val time = runCatching {
                LocalDateTime.of(year, d[5].toInt() and 0xFF, d[6].toInt() and 0xFF, d[7].toInt() and 0xFF, d[8].toInt() and 0xFF, d[9].toInt() and 0xFF)
                    .atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
            }.getOrNull()
            return Frame(kg, stable, removed, time)
        }

        internal val SERVICE_WEIGHT = uuid16(0x181D)
    }
}
