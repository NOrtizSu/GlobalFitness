// Protocol ported from openScale (GPL-3.0), https://github.com/oliexdev/openScale
package com.enjoyyourtime.globalfitness.scale.drivers

import com.enjoyyourtime.globalfitness.scale.AdvertisementInfo
import com.enjoyyourtime.globalfitness.scale.Bytes
import com.enjoyyourtime.globalfitness.scale.FormulaLib
import com.enjoyyourtime.globalfitness.scale.GattProtocol
import com.enjoyyourtime.globalfitness.scale.GattScaleDriver
import com.enjoyyourtime.globalfitness.scale.MatchConfidence
import com.enjoyyourtime.globalfitness.scale.ScaleCapabilities
import com.enjoyyourtime.globalfitness.scale.ScaleSample
import com.enjoyyourtime.globalfitness.scale.libs.SoehnleLib
import com.enjoyyourtime.globalfitness.scale.libs.TrisaBodyAnalyzeLib
import com.enjoyyourtime.globalfitness.scale.libs.trisaImpedanceFromResistance
import com.enjoyyourtime.globalfitness.scale.uuid16
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.UUID

/**
 * Soehnle Shape 200/100/50 and Style 100: user created/selected through the User Data Service
 * control point, history requested for slots 1..7 on the vendor service, composition from the
 * 50 kHz / 5 kHz impedances with the Soehnle formulas.
 *
 * Unlike openScale, the scale is never factory-reset when the app has no user mapping (that would
 * delete other people's users): a new user is created instead.
 */
class SoehnleDriver : GattScaleDriver() {
    override val id = "soehnle_shape"
    override val displayName = "Soehnle Shape 200/100/50 · Style 100"
    override val brand = "Soehnle"
    override val capabilities = ScaleCapabilities(impedance = true, userRegistration = true, history = true)
    override val formulaLib = FormulaLib.SOEHNLE
    override val keywords = listOf("Shape200", "Shape100", "Shape50", "Style100")

    override fun match(adv: AdvertisementInfo): MatchConfidence? {
        val n = adv.nameOrEmpty
        return if (n.startsWith("Shape200") || n.startsWith("Shape100") || n.startsWith("Shape50") || n.startsWith("Style100")) {
            MatchConfidence.HIGH
        } else {
            null
        }
    }

    override fun newProtocol(adv: AdvertisementInfo?): GattProtocol = Protocol()

    internal class Protocol : GattProtocol() {
        private val svcBattery = uuid16(0x180F)
        private val chrBattery = uuid16(0x2A19)
        private val svcCurrentTime = uuid16(0x1805)
        private val chrCurrentTime = uuid16(0x2A2B)
        private val svcUserData = uuid16(0x181C)
        private val chrUcp = uuid16(0x2A9F)
        private val chrAge = uuid16(0x2A80)
        private val chrGender = uuid16(0x2A8C)
        private val chrHeight = uuid16(0x2A8E)
        private val svcSoehnle = UUID.fromString("352e3000-28e9-40b8-a361-6db4cca4147c")
        private val chrSoehnleA = UUID.fromString("352e3001-28e9-40b8-a361-6db4cca4147c")
        private val chrSoehnleB = UUID.fromString("352e3004-28e9-40b8-a361-6db4cca4147c")
        private val chrSoehnleCmd = UUID.fromString("352e3002-28e9-40b8-a361-6db4cca4147c")

        override fun onConnected() {
            setNotifyOn(svcBattery, chrBattery)
            readFrom(svcBattery, chrBattery)
            val now = java.time.Instant.ofEpochMilli(env.wallClock()).atZone(ZoneId.systemDefault())
            // Calendar.DAY_OF_WEEK as in the original (1 = Sunday).
            val dayOfWeek = now.dayOfWeek.value % 7 + 1
            writeTo(
                svcCurrentTime,
                chrCurrentTime,
                Bytes.toInt16Le(now.year) + byteArrayOf(
                    now.monthValue.toByte(), now.dayOfMonth.toByte(), now.hour.toByte(), now.minute.toByte(), now.second.toByte(),
                    dayOfWeek.toByte(), 0,
                ),
            )
            setNotifyOn(svcUserData, chrUcp)
            val index = settingsGetInt(KEY_INDEX, -1)
            if (index == -1) {
                writeTo(svcUserData, chrUcp, byteArrayOf(0x01, 0x00, 0x00))
            } else {
                writeTo(svcUserData, chrUcp, byteArrayOf(0x02, index.toByte(), 0x00, 0x00))
            }
            writeTo(svcUserData, chrAge, byteArrayOf(user.ageAt(env.wallClock()).toByte()))
            writeTo(svcUserData, chrGender, byteArrayOf(if (user.isMale) 0x00 else 0x01))
            writeTo(svcUserData, chrHeight, Bytes.toInt16Le(user.heightCm.toInt()))
            setNotifyOn(svcSoehnle, chrSoehnleA)
            setNotifyOn(svcSoehnle, chrSoehnleB)
            for (i in 1..7) writeTo(svcSoehnle, chrSoehnleCmd, byteArrayOf(0x09, i.toByte()))
        }

        override fun onNotification(characteristic: UUID, data: ByteArray) {
            if (data.isEmpty()) return
            when (characteristic) {
                chrSoehnleA -> handleMeasurement(data)
                chrUcp -> handleUcp(data)
                chrBattery -> if (Bytes.u8(data, 0) <= 10) userInfo("Batería de la báscula baja")
            }
        }

        private fun handleUcp(value: ByteArray) {
            if (value.size < 3 || value[0] != 0x20.toByte()) return
            when (value[1].toInt()) {
                0x01 -> if (value[2] == 0x01.toByte() && value.size >= 4) {
                    settingsPutInt(KEY_INDEX, Bytes.u8(value, 3))
                    userInfo("Usuario creado en la báscula: súbete para la medición de referencia")
                } else {
                    userInfo("La báscula no pudo crear el usuario (¿sin huecos libres?)")
                }
                0x02 -> if (value[2] != 0x01.toByte()) {
                    settingsRemove(KEY_INDEX)
                    writeTo(svcUserData, chrUcp, byteArrayOf(0x01, 0x00, 0x00))
                }
            }
        }

        private fun handleMeasurement(v: ByteArray) {
            if (v.size != 15 || v[0] != 0x09.toByte()) return
            val index = Bytes.u8(v, 1)
            if (index != settingsGetInt(KEY_INDEX, -1)) return // another user's weighing
            val weight = Bytes.fromUnsignedInt16Be(v, 9) / 10.0f
            val imp5 = Bytes.fromUnsignedInt16Be(v, 11).toFloat()
            val imp50 = Bytes.fromUnsignedInt16Be(v, 13).toFloat()
            val time = runCatching {
                LocalDateTime.of(Bytes.fromUnsignedInt16Be(v, 2), Bytes.u8(v, 4), Bytes.u8(v, 5), Bytes.u8(v, 6), Bytes.u8(v, 7), Bytes.u8(v, 8))
                    .atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
            }.getOrNull()
            val sample = ScaleSample().apply {
                weightKg = weight
                dateTime = time
                fromHistory = true
            }
            if (imp50 > 0f) {
                val lib = SoehnleLib(user.isMale, user.ageAt(time ?: env.wallClock()), user.heightCm, SoehnleLib.activity(user.activityLevel))
                sample.impedance = imp50
                sample.waterPercent = lib.getWater(weight, imp50)
                sample.fatPercent = lib.getFat(weight, imp50)
                if (imp5 > 0f) sample.musclePercent = lib.getMuscle(weight, imp50, imp5)
                sample.computedWith = FormulaLib.SOEHNLE
            }
            publish(sample)
        }

        companion object {
            const val KEY_INDEX = "soehnle_index"
        }
    }
}

/**
 * Trisa Body Analyze 4.0 (names 01257B / 11257B): pairing stores the scale password, later
 * connections answer the XOR challenge and set the clock (seconds since 2010).
 */
class TrisaBodyAnalyzeDriver : GattScaleDriver() {
    override val id = "trisa_body_analyze"
    override val displayName = "Trisa Body Analyze 4.0"
    override val brand = "Trisa"
    override val capabilities = ScaleCapabilities(impedance = true)
    override val formulaLib = FormulaLib.TRISA_RESISTANCE

    override fun match(adv: AdvertisementInfo): MatchConfidence? {
        val n = adv.nameOrEmpty
        return if (n.startsWith("01257B") || n.startsWith("11257B")) MatchConfidence.HIGH else null
    }

    override fun newProtocol(adv: AdvertisementInfo?): GattProtocol = Protocol()

    internal class Protocol : GattProtocol() {
        private val svc = uuid16(0x7802)
        private val chrMeasurement = uuid16(0x8A21)
        private val chrDownload = uuid16(0x8A81)
        private val chrUpload = uuid16(0x8A82)

        override fun onConnected() {
            setNotifyOn(svc, chrMeasurement)
            setNotifyOn(svc, chrUpload)
        }

        override fun onNotification(characteristic: UUID, data: ByteArray) {
            when (characteristic) {
                chrUpload -> handleUpload(data)
                chrMeasurement -> parseMeasurement(data, user.isMale, user.ageAt(env.wallClock()), user.heightCm)?.let { publish(it) }
            }
        }

        private fun handleUpload(data: ByteArray) {
            if (data.size < 5) return
            when (data[0]) {
                UPLOAD_PASSWORD -> {
                    settingsPutInt(KEY_PASSWORD, Bytes.fromSignedInt32Le(data, 1))
                    userInfo("Trisa emparejada. Súbete de nuevo para medir")
                    writeCommand(CMD_BROADCAST_ID, 0)
                    requestDisconnect()
                }
                UPLOAD_CHALLENGE -> {
                    val stored = env.store.getString(KEY_PASSWORD)?.toIntOrNull()
                    if (stored == null) {
                        userInfo("Trisa no emparejada: pulsa el botón de emparejamiento de la báscula")
                        requestDisconnect()
                        return
                    }
                    writeCommand(CMD_RESULT, Bytes.fromSignedInt32Le(data, 1) xor stored)
                    writeCommand(CMD_UTC, ((env.wallClock() + 500) / 1000 - EPOCH_2010).toInt())
                }
            }
        }

        private fun writeCommand(opcode: Byte, arg: Int) {
            writeTo(svc, chrDownload, byteArrayOf(opcode) + Bytes.toInt32Le(arg.toLong() and 0xFFFFFFFFL))
        }

        companion object {
            const val KEY_PASSWORD = "trisa_password"
            private const val UPLOAD_PASSWORD: Byte = 0xA0.toByte()
            private const val UPLOAD_CHALLENGE: Byte = 0xA1.toByte()
            private const val CMD_UTC: Byte = 0x02
            private const val CMD_RESULT: Byte = 0x20
            private const val CMD_BROADCAST_ID: Byte = 0x21
        }
    }

    companion object {
        const val EPOCH_2010 = 1262304000L

        /** Weight and resistance are "base-10 floats": 24-bit mantissa + signed exponent. */
        fun base10Float(data: ByteArray, offset: Int): Float =
            (Bytes.fromUnsignedInt24Le(data, offset) * Math.pow(10.0, data[offset + 3].toDouble())).toFloat()

        /**
         * Layout: flags (bit0 timestamp, bit1 resistance1, bit2 resistance2), weight, timestamp,
         * [resistance1], [resistance2]. Composition uses impedance derived from resistance2.
         */
        fun parseMeasurement(data: ByteArray, isMale: Boolean, age: Int, heightCm: Float): ScaleSample? {
            if (data.size < 9) return null
            val info = data[0].toInt()
            if (info and 0x01 == 0) return null
            val weight = base10Float(data, 1)
            val sample = ScaleSample().apply {
                weightKg = weight
                dateTime = 1000L * (EPOCH_2010 + Bytes.fromSignedInt32Le(data, 5).toLong())
            }
            val r2Offset = 9 + if (info and 0x02 != 0) 4 else 0
            if (info and 0x04 != 0 && r2Offset + 4 <= data.size && age > 0 && heightCm > 0f) {
                val r2 = base10Float(data, r2Offset)
                val impedance = trisaImpedanceFromResistance(r2)
                val lib = TrisaBodyAnalyzeLib(if (isMale) 1 else 0, age, heightCm)
                sample.fatPercent = lib.getFat(weight, impedance)
                sample.waterPercent = lib.getWater(weight, impedance)
                sample.musclePercent = lib.getMuscle(weight, impedance)
                sample.boneKg = lib.getBone(weight, impedance)
                // The raw resistance is stored (as openScale does); composition derives from it.
                sample.impedance = r2
                sample.computedWith = FormulaLib.TRISA_RESISTANCE
            }
            return sample
        }
    }
}
