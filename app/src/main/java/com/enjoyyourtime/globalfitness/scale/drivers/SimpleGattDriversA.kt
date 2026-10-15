// Protocol ported from openScale (GPL-3.0), https://github.com/oliexdev/openScale
package com.enjoyyourtime.globalfitness.scale.drivers

import com.enjoyyourtime.globalfitness.scale.ActivityLevel
import com.enjoyyourtime.globalfitness.scale.AdvertisementInfo
import com.enjoyyourtime.globalfitness.scale.Bytes
import com.enjoyyourtime.globalfitness.scale.FormulaLib
import com.enjoyyourtime.globalfitness.scale.GattProtocol
import com.enjoyyourtime.globalfitness.scale.GattScaleDriver
import com.enjoyyourtime.globalfitness.scale.MatchConfidence
import com.enjoyyourtime.globalfitness.scale.ScaleCapabilities
import com.enjoyyourtime.globalfitness.scale.ScaleNotice
import com.enjoyyourtime.globalfitness.scale.ScaleSample
import com.enjoyyourtime.globalfitness.scale.ScaleUnit
import com.enjoyyourtime.globalfitness.scale.uuid16
import java.util.Locale
import java.util.UUID
import kotlin.math.roundToInt

private const val LB_TO_KG = 0.45359237f

private fun toKg(value: Float, unit: ScaleUnit): Float = when (unit) {
    ScaleUnit.KG -> value
    ScaleUnit.LB -> value * LB_TO_KG
    ScaleUnit.ST -> value * 6.35029318f
}

/** Excelvan CF36xBLE ("Electronic Scale"): user config on FFF1, 16/17-byte result (0xCF) on FFF4. */
class ExcelvanCF36xDriver : GattScaleDriver() {
    override val id = "excelvan_cf36x"
    override val displayName = "Excelvan CF36xBLE"
    override val brand = "Excelvan"
    override val capabilities = ScaleCapabilities(deviceComposition = true)
    override val keywords = listOf("Electronic Scale", "CF369", "CF366")

    override fun match(adv: AdvertisementInfo): MatchConfidence? =
        if (adv.nameOrEmpty.equals("Electronic Scale", ignoreCase = true)) MatchConfidence.MEDIUM else null

    override fun newProtocol(adv: AdvertisementInfo?): GattProtocol = object : GattProtocol() {
        private val svc = uuid16(0xFFF0)
        private val chrWrite = uuid16(0xFFF1)
        private val chrNotify = uuid16(0xFFF4)
        private var lastFrame: ByteArray? = null

        override fun onConnected() {
            writeTo(svc, chrWrite, userConfig(user.isMale, user.activityLevel, user.heightCm, user.ageAt(env.wallClock()), user.unit))
            setNotifyOn(svc, chrNotify)
            userInfo(ScaleNotice.Kind.STEP_ON)
        }

        override fun onNotification(characteristic: UUID, data: ByteArray) {
            if (characteristic != chrNotify || data.size !in 16..17 || data[0] != 0xCF.toByte()) return
            if (lastFrame?.contentEquals(data) == true) return
            lastFrame = data.copyOf()
            publish(parse(data, user.unit))
            requestDisconnect()
        }
    }

    companion object {
        /** `[FE, user=1, sex, activity, height, age, unit, xor(1..6)]`. */
        fun userConfig(male: Boolean, activity: ActivityLevel, heightCm: Float, age: Int, unit: ScaleUnit): ByteArray {
            val act = when (activity) {
                ActivityLevel.SEDENTARY, ActivityLevel.MILD -> 0
                ActivityLevel.MODERATE -> 1
                ActivityLevel.HEAVY, ActivityLevel.EXTREME -> 2
            }
            val unitCode = when (unit) {
                ScaleUnit.KG -> 1
                ScaleUnit.LB -> 2
                ScaleUnit.ST -> 4
            }
            val cfg = byteArrayOf(
                0xFE.toByte(), 0x01, if (male) 1 else 0, act.toByte(), heightCm.roundToInt().coerceIn(0, 255).toByte(),
                age.coerceIn(0, 255).toByte(), unitCode.toByte(), 0,
            )
            cfg[7] = Bytes.xor(cfg, 1, 7).toByte()
            return cfg
        }

        fun parse(frame: ByteArray, unit: ScaleUnit) = ScaleSample().apply {
            weightKg = toKg(Bytes.fromUnsignedInt16Be(frame, 4) / 10.0f, unit)
            fatPercent = Bytes.fromUnsignedInt16Be(frame, 6) / 10.0f
            boneKg = Bytes.u8(frame, 8) / 10.0f
            musclePercent = Bytes.fromUnsignedInt16Be(frame, 9) / 10.0f
            visceralFat = Bytes.u8(frame, 11).toFloat()
            waterPercent = Bytes.fromUnsignedInt16Be(frame, 12) / 10.0f
        }
    }
}

/** Digoo DG-SO38H ("Mengii"): 20-byte frames on FFF1; when stable, the user profile is sent on FFF2. */
class DigooDGSO38HDriver : GattScaleDriver() {
    override val id = "digoo_dgso38h"
    override val displayName = "Digoo DG-SO38H (Mengii)"
    override val brand = "Digoo"
    override val capabilities = ScaleCapabilities(deviceComposition = true)
    override val keywords = listOf("Mengii", "DG-SO38H")

    override fun match(adv: AdvertisementInfo): MatchConfidence? =
        if (adv.nameOrEmpty.equals("Mengii", ignoreCase = true)) MatchConfidence.HIGH else null

    override fun newProtocol(adv: AdvertisementInfo?): GattProtocol = object : GattProtocol() {
        private val svc = uuid16(0xFFF0)
        private val chrMeasurement = uuid16(0xFFF1)
        private val chrExtra = uuid16(0xFFF2)

        override fun onConnected() {
            setNotifyOn(svc, chrMeasurement)
            userInfo(ScaleNotice.Kind.STEP_ON)
        }

        override fun onNotification(characteristic: UUID, data: ByteArray) {
            if (characteristic != chrMeasurement || data.size != 20) return
            val ctrl = data[5].toInt()
            if (ctrl and 0x01 != 0) {
                live(Bytes.fromUnsignedInt16Be(data, 3) / 100.0f)
                writeTo(svc, chrExtra, allValuesRequest(user.isMale, user.heightCm, user.ageAt(env.wallClock()), user.unit))
                return
            }
            if (ctrl and 0x02 != 0) {
                publish(parse(data))
                finishSoon()
            }
        }
    }

    companion object {
        fun allValuesRequest(male: Boolean, heightCm: Float, age: Int, unit: ScaleUnit): ByteArray {
            val p = ByteArray(16)
            p[0] = 0x09; p[1] = 0x10; p[2] = 0x12; p[3] = 0x11; p[4] = 0x0D; p[5] = 0x01
            p[6] = heightCm.toInt().toByte()
            p[7] = age.toByte()
            p[8] = if (male) 0x00 else 0x01
            p[9] = when (unit) {
                ScaleUnit.KG -> 0x01
                ScaleUnit.LB -> 0x02
                ScaleUnit.ST -> 0x08
            }
            p[15] = Bytes.sum8(p, 3, 15).toByte()
            return p
        }

        fun parse(f: ByteArray) = ScaleSample().apply {
            weightKg = Bytes.fromUnsignedInt16Be(f, 3) / 100.0f
            val fat = Bytes.fromUnsignedInt16Be(f, 6) / 10.0f
            if (fat > 0f) {
                fatPercent = fat
                visceralFat = Bytes.u8(f, 10) / 10.0f
                waterPercent = Bytes.fromUnsignedInt16Be(f, 11) / 10.0f
                musclePercent = Bytes.fromUnsignedInt16Be(f, 16) / 10.0f
                boneKg = Bytes.u8(f, 18) / 10.0f
            }
        }
    }
}

/** Hesley ("YunChen"): magic command on FFF1, 20-byte composition frame on FFF4. */
class HesleyDriver : GattScaleDriver() {
    override val id = "hesley_yunchen"
    override val displayName = "Hesley (YunChen)"
    override val brand = "Hesley"
    override val capabilities = ScaleCapabilities(deviceComposition = true)
    override val keywords = listOf("YunChen")

    override fun match(adv: AdvertisementInfo): MatchConfidence? =
        if (adv.nameOrEmpty.equals("yunchen", ignoreCase = true)) MatchConfidence.HIGH else null

    override fun newProtocol(adv: AdvertisementInfo?): GattProtocol = object : GattProtocol() {
        private val svc = uuid16(0xFFF0)
        private val chrCmd = uuid16(0xFFF1)
        private val chrNotify = uuid16(0xFFF4)

        override fun onConnected() {
            setNotifyOn(svc, chrNotify)
            writeTo(svc, chrCmd, byteArrayOf(0xA5.toByte(), 0x01, 0x2C, 0xAB.toByte(), 0x50, 0x5A, 0x29))
            userInfo(ScaleNotice.Kind.STEP_ON)
        }

        override fun onNotification(characteristic: UUID, data: ByteArray) {
            if (characteristic == chrNotify && data.size == 20) {
                publish(parse(data))
                finishSoon()
            }
        }
    }

    companion object {
        fun parse(f: ByteArray) = ScaleSample().apply {
            weightKg = Bytes.fromUnsignedInt16Be(f, 2) / 100.0f
            fatPercent = Bytes.fromUnsignedInt16Be(f, 4) / 10.0f
            waterPercent = Bytes.fromUnsignedInt16Be(f, 8) / 10.0f
            musclePercent = Bytes.fromUnsignedInt16Be(f, 10) / 10.0f
            boneKg = Bytes.fromUnsignedInt16Be(f, 12) / 10.0f
            Bytes.u8(f, 17).takeIf { it in 10..99 }?.let { metabolicAge = it.toFloat() }
        }
    }
}

/**
 * Inlife (000fatscale01/02, 042fatscale01): 14-byte frames `02 cmd ... xor AA`; legacy results
 * carry LBM + visceral factor (composition derived as in the vendor app), newer ones impedance.
 */
class InlifeDriver : GattScaleDriver() {
    override val id = "inlife"
    override val displayName = "Inlife"
    override val brand = "Inlife"
    override val capabilities = ScaleCapabilities(impedance = true, deviceComposition = true)
    override val formulaLib = FormulaLib.STANDARD_IMPEDANCE
    override val keywords = listOf("fatscale")

    override fun match(adv: AdvertisementInfo): MatchConfidence? =
        if (adv.nameOrEmpty.lowercase(Locale.ROOT) in NAMES) MatchConfidence.HIGH else null

    override fun newProtocol(adv: AdvertisementInfo?): GattProtocol = Protocol()

    internal class Protocol : GattProtocol() {
        private val svc = uuid16(0xFFF0)
        private val chrNotify = uuid16(0xFFF1)
        private val chrCmd = uuid16(0xFFF2)
        private var lastFrame: ByteArray? = null

        override fun onConnected() {
            setNotifyOn(svc, chrNotify)
            val level = athleteLevel(user.activityLevel) + 1
            sendCommand(CMD_SET_USER, level, if (user.isMale) 0 else 1, user.appUserId and 0xFF, user.ageAt(env.wallClock()) and 0xFF, user.heightCm.toInt() and 0xFF)
            userInfo(ScaleNotice.Kind.STEP_ON)
        }

        override fun onNotification(characteristic: UUID, data: ByteArray) {
            if (characteristic != chrNotify || data.size != FRAME_LEN) return
            if (data[0] != START || data.last() != END) return
            if (Bytes.xor(data, 1, FRAME_LEN - 1) != 0) return
            if (lastFrame?.contentEquals(data) == true) return
            lastFrame = data.copyOf()
            when (Bytes.u8(data, 1)) {
                CMD_WEIGHT -> live(Bytes.fromUnsignedInt16Be(data, 2) / 10.0f)
                CMD_RESULT -> {
                    val flag = Bytes.u8(data, 11)
                    val sample = if (flag == 0x80 || flag == 0x81) parseNew(data) else parseLegacy(data)
                    if (sample != null) publish(sample)
                    sendCommand(CMD_FINISH)
                }
            }
        }

        private fun parseNew(d: ByteArray) = ScaleSample().apply {
            weightKg = Bytes.fromUnsignedInt16Be(d, 2) / 10.0f
            val imp = Bytes.fromUnsignedInt32Be(d, 4)
            if (imp > 0) impedance = imp.toFloat()
        }

        private fun parseLegacy(d: ByteArray): ScaleSample? {
            val weight = Bytes.fromUnsignedInt16Be(d, 2) / 10.0f
            val lbmRaw = Bytes.fromUnsignedInt24Be(d, 4)
            if (lbmRaw >= 0xFFFFFF) {
                userInfo(ScaleNotice.Kind.MEASUREMENT_FAILED_FEET)
                return null
            }
            var lbm = lbmRaw / 1000.0f
            val viscF = Bytes.fromUnsignedInt16Be(d, 7) / 10.0f
            val lvl = athleteLevel(user.activityLevel)
            when (lvl) {
                1 -> lbm *= 1.0427f
                2 -> lbm *= 1.0958f
            }
            val fatKg = weight - lbm
            val height = user.heightCm
            var visceral = viscF - 50.0
            if (user.isMale) {
                visceral += if (height >= 1.6 * weight + 63) (0.765 - 0.002 * height) * weight else 380 * weight / ((0.0826 * height * height - 0.4 * height) + 48)
            } else {
                visceral += if (weight <= height / 2 - 13) (0.691 - 0.0024 * height) * weight else 500 * weight / ((0.1158 * height * height + 1.45 * height) - 120)
            }
            if (lvl != 0) {
                if (visceral >= 21) visceral *= 0.85
                if (visceral >= 10) visceral *= 0.8
                visceral -= lvl * 2
            }
            return ScaleSample().apply {
                weightKg = weight
                fatPercent = (fatKg / weight * 100.0).coerceIn(5.0, 80.0).toFloat()
                waterPercent = (0.73 * (weight - fatKg) / weight * 100.0).coerceIn(5.0, 80.0).toFloat()
                skeletalMusclePercent = (0.548 * lbm / weight * 100.0).coerceIn(5.0, 80.0).toFloat()
                boneKg = (0.05158 * lbm).coerceIn(0.5, 8.0).toFloat()
                lbmKg = lbm
                visceralFat = visceral.coerceIn(1.0, 50.0).toFloat()
            }
        }

        private fun sendCommand(command: Int, vararg params: Int) {
            val frame = ByteArray(FRAME_LEN)
            frame[0] = START
            frame[1] = command.toByte()
            params.forEachIndexed { i, p -> if (2 + i < FRAME_LEN - 2) frame[2 + i] = p.toByte() }
            frame[FRAME_LEN - 2] = Bytes.xor(frame, 1, FRAME_LEN - 2).toByte()
            frame[FRAME_LEN - 1] = END
            writeTo(svc, chrCmd, frame)
        }
    }

    companion object {
        private val NAMES = setOf("000fatscale01", "000fatscale02", "042fatscale01")
        private const val START: Byte = 0x02
        private const val END: Byte = 0xAA.toByte()
        private const val FRAME_LEN = 14
        private const val CMD_SET_USER = 0xD2
        private const val CMD_WEIGHT = 0xD8
        private const val CMD_RESULT = 0xDD
        private const val CMD_FINISH = 0xD4

        fun athleteLevel(level: ActivityLevel): Int = when (level) {
            ActivityLevel.SEDENTARY, ActivityLevel.MILD -> 0
            ActivityLevel.MODERATE -> 1
            ActivityLevel.HEAVY, ActivityLevel.EXTREME -> 2
        }
    }
}

/** Exingtech Y1 ("VScale"): user block on the command characteristic, 20-byte result frame. */
class ExingtechY1Driver : GattScaleDriver() {
    override val id = "exingtech_y1"
    override val displayName = "Exingtech Y1 (VScale)"
    override val brand = "Exingtech"
    override val capabilities = ScaleCapabilities(deviceComposition = true)
    override val keywords = listOf("VScale")

    override fun match(adv: AdvertisementInfo): MatchConfidence? = when {
        adv.nameOrEmpty.equals("vscale", ignoreCase = true) -> MatchConfidence.HIGH
        adv.serviceUuids.contains(SERVICE) -> MatchConfidence.HIGH
        else -> null
    }

    override fun newProtocol(adv: AdvertisementInfo?): GattProtocol = object : GattProtocol() {
        private val chrNotify = UUID.fromString("1a2ea400-75b9-11e2-be05-0002a5d5c51b")
        private val chrCmd = UUID.fromString("29f11080-75b9-11e2-8bf6-0002a5d5c51b")

        override fun onConnected() {
            setNotifyOn(SERVICE, chrNotify)
            writeTo(
                SERVICE,
                chrCmd,
                byteArrayOf(0x10, (user.appUserId and 0xFF).toByte(), if (user.isMale) 0 else 1, (user.ageAt(env.wallClock()) and 0xFF).toByte(), (user.heightCm.toInt() and 0xFF).toByte()),
            )
            userInfo(ScaleNotice.Kind.STEP_ON)
        }

        override fun onNotification(characteristic: UUID, data: ByteArray) {
            if (characteristic != chrNotify || data.size != 20) return
            if (Bytes.u8(data, 6) == 0xFF) {
                live(Bytes.fromUnsignedInt16Be(data, 4) / 10.0f)
                return
            }
            publish(parse(data))
        }
    }

    companion object {
        val SERVICE: UUID = UUID.fromString("f433bd80-75b8-11e2-97d9-0002a5d5c51b")

        fun parse(f: ByteArray) = ScaleSample().apply {
            weightKg = Bytes.fromUnsignedInt16Be(f, 4) / 10.0f
            fatPercent = Bytes.fromUnsignedInt16Be(f, 6) / 10.0f
            waterPercent = Bytes.fromUnsignedInt16Be(f, 8) / 10.0f
            boneKg = Bytes.fromUnsignedInt16Be(f, 10) / 10.0f
            musclePercent = Bytes.fromUnsignedInt16Be(f, 12) / 10.0f
            visceralFat = Bytes.u8(f, 14).toFloat()
        }
    }
}

/** Belter / Ebelter Body Fat-B2 (EF-916B4): handshake on FAA1, weight/fat notifications (8D 11) on FAA2. */
class EbelterBodyFatB2Driver : GattScaleDriver() {
    override val id = "ebelter_body_fat_b2"
    override val displayName = "Belter / Ebelter Body Fat-B2 (EF-916B4)"
    override val brand = "Ebelter"
    override val capabilities = ScaleCapabilities(deviceComposition = true)
    override val keywords = listOf("Body Fat-B2", "EF-916B4", "Belter")

    override fun match(adv: AdvertisementInfo): MatchConfidence? =
        if (adv.upperName.startsWith("BODY FAT-B2")) MatchConfidence.HIGH else null

    override fun newProtocol(adv: AdvertisementInfo?): GattProtocol = object : GattProtocol() {
        private val svc = uuid16(0xFAA0)
        private val chrWrite = uuid16(0xFAA1)
        private val chrNotify = uuid16(0xFAA2)
        private var lastHandshake = Long.MIN_VALUE / 2
        private var lastWeightRaw = -1
        private var lastFatRaw = -1
        private var repeats = 0
        private var published = false
        private val STABLE_FRAMES = 3

        override fun onConnected() {
            setNotifyOn(svc, chrNotify)
            handshake(force = true)
        }

        override fun onNotification(characteristic: UUID, data: ByteArray) {
            if (characteristic != chrNotify || data.size < 2) return
            val b0 = Bytes.u8(data, 0)
            val b1 = Bytes.u8(data, 1)
            if (b0 == 0x8D && b1 == 0x01) {
                handshake(force = false)
                return
            }
            if (b0 != 0x8D || b1 != 0x11 || data.size < 8) return
            val weightRaw = Bytes.fromUnsignedInt16Le(data, 4)
            val fatRaw = Bytes.fromUnsignedInt16Le(data, 6)
            val kg = weightRaw / 10.0f
            if (kg < 10.0f) return
            live(kg)
            if (published) return
            repeats = if (weightRaw == lastWeightRaw && fatRaw == lastFatRaw) repeats + 1 else 1
            lastWeightRaw = weightRaw
            lastFatRaw = fatRaw
            if (repeats < STABLE_FRAMES) return
            published = true
            publish(ScaleSample().apply {
                weightKg = kg
                if (fatRaw != 0) fatPercent = fatRaw / 10.0f
            })
            requestDisconnect()
        }

        private fun handshake(force: Boolean) {
            val now = env.elapsedClock()
            if (!force && now - lastHandshake < 2_500L) return
            lastHandshake = now
            writeTo(svc, chrWrite, Bytes.fromHex("ab0998fc121711180a2714"))
            writeTo(svc, chrWrite, Bytes.fromHex("ab0e9906151442148bbc0aa611bd12e9ea"))
        }
    }
}
