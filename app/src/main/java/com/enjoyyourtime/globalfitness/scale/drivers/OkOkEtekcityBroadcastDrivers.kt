// Protocol ported from openScale (GPL-3.0), https://github.com/oliexdev/openScale
package com.enjoyyourtime.globalfitness.scale.drivers

import com.enjoyyourtime.globalfitness.scale.AdvertisementInfo
import com.enjoyyourtime.globalfitness.scale.Bytes
import com.enjoyyourtime.globalfitness.scale.DriverEnv
import com.enjoyyourtime.globalfitness.scale.FormulaLib
import com.enjoyyourtime.globalfitness.scale.MatchConfidence
import com.enjoyyourtime.globalfitness.scale.PassiveScaleDriver
import com.enjoyyourtime.globalfitness.scale.PassiveSession
import com.enjoyyourtime.globalfitness.scale.ScaleCapabilities
import com.enjoyyourtime.globalfitness.scale.ScaleSample
import com.enjoyyourtime.globalfitness.scale.SimplePassiveSession
import com.enjoyyourtime.globalfitness.scale.libs.OkOkV2Lib
import java.util.UUID

/**
 * OKOK / Chipsea broadcast scales ("ADV", "Chipsea-BLE", "Yoda0/1", nameless): variants by
 * manufacturer id — V20 (0x20CA, stable flag + XOR checksum, impedance), V11 (0x11CA), VF0 (0xF0FF)
 * and "C0" (company id low byte 0xC0, impedance; composition with the OKOK V2 formulas).
 */
class OkOkDriver : PassiveScaleDriver() {
    override val id = "okok_broadcast"
    override val displayName = "OKOK / Chipsea (anuncios)"
    override val brand = "OKOK / Chipsea"
    override val capabilities = ScaleCapabilities(impedance = true)
    override val keywords = listOf("ADV", "Chipsea-BLE", "Yoda", "V20", "V11", "VF0")

    override fun match(adv: AdvertisementInfo): MatchConfidence? {
        val name = adv.nameOrEmpty
        val knownName = name == "ADV" || name == "Chipsea-BLE" || name.startsWith("Yoda0", true) || name.startsWith("Yoda1", true)
        val m = adv.manufacturerData
        val variant = m.containsKey(MANUF_V20) || m.containsKey(MANUF_V11) || m.containsKey(MANUF_VF0) || hasC0(adv)
        return when {
            knownName && variant -> MatchConfidence.HIGH
            name.isEmpty() && hasC0(adv) -> MatchConfidence.MEDIUM
            else -> null
        }
    }

    override fun newSession(env: DriverEnv): PassiveSession = Session()

    private class Session : SimplePassiveSession(null) {
        override fun parse(adv: AdvertisementInfo, env: DriverEnv): ScaleSample? {
            val m = adv.manufacturerData
            m[MANUF_V20]?.let { data ->
                parseV20(data)?.let { (kg, imp) ->
                    return ScaleSample().apply {
                        weightKg = kg
                        if (imp > 0f) {
                            impedance = imp
                            impedanceLib = FormulaLib.STANDARD_IMPEDANCE
                        }
                    }
                }
            }
            m[MANUF_V11]?.let { data -> parseV11(data)?.let { return ScaleSample().apply { weightKg = it } } }
            m[MANUF_VF0]?.let { data -> parseVF0(data)?.let { return ScaleSample().apply { weightKg = it } } }
            val key = m.keys.firstOrNull { it and 0xFF == 0xC0 } ?: return null
            val (kg, imp) = parseC0(m.getValue(key)) ?: return null
            return ScaleSample().apply {
                weightKg = kg
                val age = env.ctx.ageAt(env.wallClock())
                if (imp > 0f && age > 5) {
                    impedance = imp
                    val lib = OkOkV2Lib(age, if (env.ctx.isMale) 1 else 0, env.ctx.heightCm)
                    waterPercent = lib.getWater(kg, imp)
                    visceralFat = lib.getVisceralFat(kg, imp)
                    fatPercent = lib.getBodyFat(kg, imp)
                    musclePercent = lib.getMuscle(kg, imp)
                    lbmKg = lib.getLBM(kg, imp)
                    boneKg = lib.getBoneMass(kg, imp)
                    bmrKcal = lib.getBMR(kg)
                    proteinPercent = lib.getProtein(kg, imp)
                    lib.getBodyAge(kg, imp).takeIf { it in 10..99 }?.let { metabolicAge = it.toFloat() }
                    computedWith = FormulaLib.OKOK_V2
                }
            }
        }
    }

    companion object {
        const val MANUF_V20 = 0x20CA
        const val MANUF_V11 = 0x11CA
        const val MANUF_VF0 = 0xF0FF
        private const val UNIT_KG = 0
        private const val UNIT_JIN = 1
        private const val UNIT_LB = 2
        private const val UNIT_STLB = 3

        fun hasC0(adv: AdvertisementInfo) = adv.manufacturerData.keys.any { it and 0xFF == 0xC0 }

        /** (kg, impedance Ω) of a valid final V20 frame. */
        fun parseV20(data: ByteArray): Pair<Float, Float>? {
            if (data.size != 19) return null
            if (Bytes.u8(data, 6) and 0x01 == 0) return null
            var checksum = 0x20
            for (i in 0 until 12) checksum = checksum xor Bytes.u8(data, i)
            if (Bytes.u8(data, 12) != checksum and 0xFF) return null
            val divider = if (Bytes.u8(data, 6) and 0x04 != 0) 100.0f else 10.0f
            return Bytes.fromUnsignedInt16Be(data, 8) / divider to Bytes.fromUnsignedInt16Be(data, 10) / 10.0f
        }

        fun parseV11(data: ByteArray): Float? {
            if (data.size != 23) return null
            var checksum = 0xCA xor 0x11
            for (i in 0 until 16) checksum = checksum xor Bytes.u8(data, i)
            if (Bytes.u8(data, 16) != checksum and 0xFF) return null
            val props = Bytes.u8(data, 9)
            val divider = when ((props shr 1) and 0x3) {
                1 -> 1.0f
                2 -> 100.0f
                else -> 10.0f
            }
            val weight = Bytes.fromUnsignedInt16Be(data, 3)
            return when ((props shr 3) and 0x3) {
                UNIT_KG -> weight / divider
                UNIT_JIN -> weight / (divider * 2.0f)
                UNIT_LB -> weight / divider / 2.204623f
                else -> (weight shr 8) * 6.350293f + (weight and 0xFF) / divider * 0.453592f
            }
        }

        fun parseVF0(data: ByteArray): Float? {
            if (data.size < 4) return null
            return ((Bytes.u8(data, 3) shl 8) or Bytes.u8(data, 2)) / 10.0f
        }

        /** (kg, impedance Ω) of a stable C0 frame. */
        fun parseC0(data: ByteArray): Pair<Float, Float>? {
            if (data.size < 13) return null
            val attrib = Bytes.u8(data, 6)
            if (attrib and 0x01 == 0) return null
            val divider = when ((attrib shr 1) and 0x3) {
                1 -> 1f
                2 -> 100f
                else -> 10f
            }
            val raw = Bytes.fromUnsignedInt16Be(data, 0)
            val kg = when ((attrib shr 3) and 0x3) {
                UNIT_KG -> raw / divider
                UNIT_JIN -> raw / divider / 2
                UNIT_LB -> raw / divider * 0.45359237f
                else -> Bytes.u8(data, 0) * 6.350293f + Bytes.u8(data, 1) / divider * 0.453592f
            }
            return kg to Bytes.fromUnsignedInt16Be(data, 2) / 10f
        }
    }
}

/**
 * Etekcity Fit 8S: nameless broadcast scale identified by company id 0x06D0 plus service 0xFFD0.
 * 20-byte record: weight (grams, 24-bit LE) at 10, impedance (LE) at 13, stable flag at 15.
 */
class EtekcityFit8SDriver : PassiveScaleDriver() {
    override val id = "etekcity_fit8s"
    override val displayName = "Etekcity Fit 8S"
    override val brand = "Etekcity"
    override val capabilities = ScaleCapabilities(impedance = true)
    override val formulaLib = FormulaLib.STANDARD_IMPEDANCE

    override fun match(adv: AdvertisementInfo): MatchConfidence? =
        if (adv.manufacturerData.containsKey(MANUFACTURER_ID) && adv.serviceUuids.contains(SERVICE)) MatchConfidence.HIGH else null

    override fun newSession(env: DriverEnv): PassiveSession = object : SimplePassiveSession(FormulaLib.STANDARD_IMPEDANCE) {
        override fun parse(adv: AdvertisementInfo, env: DriverEnv): ScaleSample? {
            val p = adv.manufacturer(MANUFACTURER_ID) ?: return null
            if (p.size != 20) return null
            val grams = Bytes.fromUnsignedInt24Le(p, 10)
            if (grams <= 0) return null
            if (Bytes.u8(p, 15) != 1) {
                liveWeight(grams / 1000f)
                return null
            }
            return ScaleSample().apply {
                weightKg = grams / 1000f
                Bytes.fromUnsignedInt16Le(p, 13).takeIf { it != 0 }?.let { impedance = it.toFloat() }
            }
        }
    }

    companion object {
        const val MANUFACTURER_ID = 0x06D0
        val SERVICE: UUID = UUID.fromString("0000ffd0-0000-1000-8000-00805f9b34fb")
    }
}
