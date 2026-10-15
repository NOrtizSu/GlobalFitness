// Protocol ported from openScale (GPL-3.0), https://github.com/oliexdev/openScale
package com.enjoyyourtime.globalfitness.scale.drivers

import com.enjoyyourtime.globalfitness.scale.AdvertisementInfo
import com.enjoyyourtime.globalfitness.scale.Bytes
import com.enjoyyourtime.globalfitness.scale.GattProtocol
import com.enjoyyourtime.globalfitness.scale.GattScaleDriver
import com.enjoyyourtime.globalfitness.scale.MatchConfidence
import com.enjoyyourtime.globalfitness.scale.ScaleCapabilities
import com.enjoyyourtime.globalfitness.scale.ScaleSample
import com.enjoyyourtime.globalfitness.scale.ScaleUnit
import com.enjoyyourtime.globalfitness.scale.uuid16
import java.text.Normalizer
import java.util.Locale
import java.util.UUID
import kotlin.math.max

/**
 * Beurer BF700/BF800/Runtastic Libra, Beurer BF710, Sanitas SBF70 / SilverCrest SBF75 / Crane:
 * single vendor characteristic FFE1 with a start byte (0xF7/0xE7), user management (the app user
 * is matched on the scale by name + birth year, or created) and saved-measurement download.
 */
class BeurerSanitasDriver : GattScaleDriver() {
    enum class DeviceType { BF700_800_LIBRA, BF710, SBF70 }

    override val id = "beurer_sanitas_ffe0"
    override val displayName = "Beurer BF700/710/800 · Sanitas SBF70 · SilverCrest SBF75"
    override val brand = "Beurer / Sanitas / SilverCrest"
    override val capabilities = ScaleCapabilities(impedance = true, deviceComposition = true, userRegistration = true, history = true)
    override val keywords = listOf("BF700", "BF710", "BF800", "Runtastic", "Libra", "SBF70", "SBF75", "Silvercrest", "Crane", "AICDSCALE1")

    override fun match(adv: AdvertisementInfo): MatchConfidence? = if (typeOf(adv.nameOrEmpty) != null) MatchConfidence.HIGH else null

    override fun newProtocol(adv: AdvertisementInfo?): GattProtocol = Protocol(typeOf(adv?.nameOrEmpty.orEmpty()) ?: DeviceType.SBF70)

    companion object {
        fun typeOf(name: String): DeviceType? {
            val n = name.lowercase(Locale.US)
            return when {
                "bf-700" in n || "beurer bf700" in n || "bf-800" in n || "beurer bf800" in n ||
                    "rt-libra-b" in n || "rt-libra-w" in n || "libra-b" in n || "libra-w" in n -> DeviceType.BF700_800_LIBRA
                "bf700" in n || "beurer bf710" in n -> DeviceType.BF710
                "sanitas sbf70" in n || "sbf75" in n || "aicdscale1" in n -> DeviceType.SBF70
                else -> null
            }
        }
    }

    internal class Protocol(private val deviceType: DeviceType) : GattProtocol() {
        private val service: UUID = uuid16(0xFFE0)
        private val chr: UUID = uuid16(0xFFE1)

        private class RemoteUser(val remoteUserId: Long, val name: String, val year: Int, var localUserId: Int = -1, var isNew: Boolean = false)

        private val remoteUsers = mutableListOf<RemoteUser>()
        private var currentRemoteUser: RemoteUser? = null
        private var measurementData: ByteArray? = null
        private var storedData: ByteArray? = null
        private var storedUid = -1L
        private var candidateUid = -1L
        private var readyForData = false
        private var dataReceived = false
        private var waitForDataInStep = -1
        private var step = 0
        private var startByte: Byte = 0

        override fun onConnected() {
            startByte = when (deviceType) {
                DeviceType.BF700_800_LIBRA -> ((0xF shl 4) or NIBBLE_CMD).toByte()
                DeviceType.BF710, DeviceType.SBF70 -> ((0xE shl 4) or NIBBLE_CMD).toByte()
            }
            setNotifyOn(service, chr)
            proceedTo(1)
        }

        override fun onNotification(characteristic: UUID, data: ByteArray) {
            if (characteristic != chr || data.isEmpty()) return
            if (data[0] == alternativeStartByte(NIBBLE_INIT)) {
                waitForDataInStep = -1
                proceedTo(2)
                return
            }
            if (data[0] != startByte || data.size < 2) return
            when (data[1]) {
                CMD_USER_INFO -> processUserInfo(data)
                CMD_SAVED_MEASUREMENT -> processSavedMeasurement(data)
                CMD_WEIGHT_MEASUREMENT -> if (data.size >= 5 && data[2] != 0.toByte()) live(kg(data, 3))
                CMD_MEASUREMENT -> processMeasurement(data)
                CMD_SCALE_ACK -> processScaleAck(data)
            }
        }

        private fun proceedTo(next: Int) {
            step = next
            when (step) {
                1 -> {
                    waitForDataInStep = 1
                    sendAlternativeStart(NIBBLE_INIT, 0x01)
                }
                2 -> {
                    sendAlternativeStart(NIBBLE_SET_TIME, *Bytes.toInt32Be(env.wallClock() / 1000L))
                    proceedTo(3)
                }
                3 -> {
                    waitForDataInStep = 3
                    sendCommand(CMD_SCALE_STATUS, *encodeUserId(null))
                }
                4 -> {
                    waitForDataInStep = 4
                    sendCommand(CMD_USER_LIST)
                }
                5 -> {
                    val nextIdx = remoteUsers.indexOf(currentRemoteUser).let { if (it < 0) 0 else it + 1 }
                    currentRemoteUser = (nextIdx until remoteUsers.size).map { remoteUsers[it] }.firstOrNull { it.localUserId != -1 }
                    val ru = currentRemoteUser
                    if (ru != null) {
                        waitForDataInStep = 5
                        sendCommand(CMD_GET_SAVED_MEASUREMENTS, *encodeUserId(ru))
                    } else {
                        proceedTo(6)
                    }
                }
                6 -> {
                    val mapped = remoteUsers.firstOrNull { it.localUserId == user.appUserId }
                    if (mapped == null) {
                        waitForDataInStep = 6
                        createRemoteUser()
                    } else {
                        currentRemoteUser = mapped
                        proceedTo(7)
                    }
                }
                7 -> {
                    val ru = currentRemoteUser
                    if (ru != null) {
                        waitForDataInStep = 7
                        sendCommand(CMD_USER_DETAILS, *encodeUserId(ru))
                    } else {
                        proceedTo(8)
                    }
                }
                8 -> {
                    if (storedData != null) publishStoredIfOurs(currentRemoteUser)
                    when {
                        !dataReceived && currentRemoteUser != null && currentRemoteUser?.isNew == false -> {
                            waitForDataInStep = 8
                            userInfo("Súbete a la báscula")
                            sendCommand(CMD_DO_MEASUREMENT, *encodeUserId(currentRemoteUser))
                        }
                        dataReceived -> requestDisconnect()
                    }
                }
            }
        }

        private fun processUserInfo(data: ByteArray) {
            if (data.size < 16) return
            val count = Bytes.u8(data, 2)
            val current = Bytes.u8(data, 3)
            if (remoteUsers.size == current - 1) {
                remoteUsers += RemoteUser(decodeUserId(data, 4), decodeString(data, 12, 3), 1900 + Bytes.u8(data, 15))
            }
            sendAck(data)
            if (current != count) return
            val localName = scaleName()
            val year = user.birthDate.year
            remoteUsers.firstOrNull { localName.startsWith(it.name) && year == it.year }?.localUserId = user.appUserId
            waitForDataInStep = -1
            proceedTo(5)
        }

        /**
         * A measurement taken before our user was identified is published only if the scale
         * attributed it to our remote user; anyone else's weighing is dropped.
         */
        private fun publishStoredIfOurs(ru: RemoteUser?) {
            val stored = storedData ?: return
            storedData = null
            if (ru != null && ru.localUserId == user.appUserId && storedUid == ru.remoteUserId) {
                addMeasurement(stored)
                dataReceived = true
            }
        }

        private fun processSavedMeasurement(data: ByteArray) {
            if (data.size < 4) return
            val count = Bytes.u8(data, 2)
            val current = Bytes.u8(data, 3)
            processMeasurementData(data, 4, firstPart = current % 2 == 1, saved = true)
            sendAck(data)
            if (current != count) return
            if (waitForDataInStep != 5) {
                if (waitForDataInStep >= 0) proceedTo(max(step - 1, 1))
                return
            }
            readyForData = true
            currentRemoteUser?.let { sendCommand(CMD_DELETE_SAVED_MEASUREMENTS, *encodeUserId(it)) }
        }

        private fun processMeasurement(data: ByteArray) {
            if (data.size < 4) return
            val count = Bytes.u8(data, 2)
            val current = Bytes.u8(data, 3)
            if (current == 1) {
                if (data.size < 13) return
                val uid = decodeUserId(data, 5)
                candidateUid = uid
                currentRemoteUser = remoteUsers.firstOrNull { it.remoteUserId == uid }
            } else {
                processMeasurementData(data, 4, firstPart = current == 2, saved = false)
            }
            sendAck(data)
            if (current != count) return
            val ru = currentRemoteUser
            if (ru != null && readyForData) {
                sendCommand(CMD_DELETE_SAVED_MEASUREMENTS, *encodeUserId(ru))
                return
            }
            if (waitForDataInStep == 6 || waitForDataInStep == 8) {
                proceedTo(step + 1)
            } else if (waitForDataInStep >= 0) {
                proceedTo(max(step - 1, 1))
            }
        }

        private fun processScaleAck(data: ByteArray) {
            if (data.size < 4) return
            when (data[2]) {
                CMD_SCALE_STATUS -> {
                    if (data.size < 12) return
                    if (Bytes.u8(data, 4) <= 10) userInfo("Batería de la báscula baja")
                    val currentUnit = Bytes.u8(data, 7)
                    val desired = when (user.unit) {
                        ScaleUnit.KG -> 1
                        ScaleUnit.LB -> 2
                        ScaleUnit.ST -> 4
                    }
                    if (desired != currentUnit) {
                        sendCommand(CMD_SET_UNIT, desired.toByte())
                    } else {
                        waitForDataInStep = -1
                        proceedTo(4)
                    }
                }
                CMD_SET_UNIT -> {
                    waitForDataInStep = -1
                    proceedTo(4)
                }
                CMD_USER_LIST -> {
                    if (data.size >= 5 && Bytes.u8(data, 4) == 0) {
                        waitForDataInStep = -1
                        proceedTo(5)
                    }
                }
                CMD_GET_SAVED_MEASUREMENTS -> {
                    if (Bytes.u8(data, 3) == 0) {
                        readyForData = true
                        waitForDataInStep = -1
                        proceedTo(6)
                    }
                }
                CMD_DELETE_SAVED_MEASUREMENTS -> {
                    waitForDataInStep = -1
                    if (step < 6) proceedTo(6) else proceedTo(8)
                }
                CMD_USER_ADD -> {
                    if (data[3] == 0.toByte()) {
                        val ru = currentRemoteUser
                        if (ru != null) {
                            ru.isNew = true
                            publishStoredIfOurs(ru)
                            readyForData = true
                            userInfo("Usuario creado en la báscula: súbete para la medición de referencia")
                            sendCommand(CMD_DO_MEASUREMENT, *encodeUserId(ru))
                            waitForDataInStep = 6
                        } else {
                            proceedTo(7)
                        }
                    } else {
                        userInfo("La báscula no admite más usuarios")
                    }
                }
                CMD_DO_MEASUREMENT -> {
                    if (data[3] != 0.toByte()) {
                        waitForDataInStep = -1
                        proceedTo(7)
                    } else {
                        userInfo("Súbete a la báscula")
                    }
                }
                CMD_USER_DETAILS -> {
                    waitForDataInStep = -1
                    proceedTo(8)
                }
            }
        }

        private fun processMeasurementData(data: ByteArray, offset: Int, firstPart: Boolean, saved: Boolean) {
            if (firstPart) {
                measurementData = data.copyOfRange(offset, data.size)
                return
            }
            val existing = measurementData ?: return
            val merged = existing + data.copyOfRange(offset, data.size)
            measurementData = merged
            val ru = currentRemoteUser
            if (ru != null && (readyForData || saved)) {
                // Only the app user's slot is ours (saved data is fetched for mapped users only).
                if (ru.localUserId == user.appUserId) addMeasurement(merged, fromHistory = saved)
                dataReceived = true
                // The deferred copy is dropped only when it is this very record.
                val pending = storedData
                if (pending != null && ru.remoteUserId == storedUid && timestamp(merged) == timestamp(pending)) storedData = null
                measurementData = null
            } else if (!saved) {
                storedData = merged
                storedUid = candidateUid
            } else {
                measurementData = null
            }
        }

        private fun addMeasurement(buf: ByteArray, fromHistory: Boolean = false) {
            if (buf.size < 16) return
            val sample = ScaleSample().apply {
                dateTime = timestamp(buf) * 1000L
                weightKg = kg(buf, 4)
                Bytes.fromUnsignedInt16Be(buf, 6).takeIf { it > 0 }?.let { impedance = it.toFloat() }
                fatPercent = percent(buf, 8)
                waterPercent = percent(buf, 10)
                musclePercent = percent(buf, 12)
                boneKg = kg(buf, 14)
                this.fromHistory = fromHistory
            }
            publish(sample)
        }

        /** Bit 31 is set by some firmwares in live frames (BF800-W): masked off. */
        private fun timestamp(buf: ByteArray): Long = Bytes.fromUnsignedInt32Be(buf, 0) and 0x7FFF_FFFFL

        private fun kg(data: ByteArray, offset: Int): Float = Bytes.fromUnsignedInt16Be(data, offset) * 50.0f / 1000.0f

        private fun percent(data: ByteArray, offset: Int): Float = Bytes.fromUnsignedInt16Be(data, offset) / 10.0f

        private fun decodeUserId(data: ByteArray, offset: Int): Long =
            (Bytes.fromUnsignedInt32Be(data, offset) shl 32) or (Bytes.fromUnsignedInt32Be(data, offset + 4) and 0xFFFFFFFFL)

        private fun encodeUserId(ru: RemoteUser?): ByteArray {
            val uid = ru?.remoteUserId ?: 0L
            return Bytes.toInt32Be(uid shr 32) + Bytes.toInt32Be(uid and 0xFFFFFFFFL)
        }

        private fun decodeString(data: ByteArray, offset: Int, maxLen: Int): String {
            var len = 0
            while (len < maxLen && data[offset + len] != 0.toByte()) len++
            return String(data, offset, len, Charsets.ISO_8859_1)
        }

        private fun scaleName(): String {
            val n = Normalizer.normalize(user.name, Normalizer.Form.NFD).replace(Regex("[^A-Za-z0-9]"), "")
            return if (n.isEmpty()) user.appUserId.toString() else n.uppercase(Locale.US)
        }

        private fun createRemoteUser() {
            val nick = scaleName().toByteArray(Charsets.US_ASCII).copyOf(3)
            val dob = user.birthDate
            val year = (dob.year - 1900).toByte()
            val month = (dob.monthValue - 1).toByte() // device expects 0..11
            val day = dob.dayOfMonth.toByte()
            val height = user.heightCm.toInt().toByte()
            val sex = if (user.isMale) 0x80 else 0x00
            val activity = user.activityLevel.ordinal + 1
            var maxUid = if (remoteUsers.isEmpty()) 100L else 0L
            for (ru in remoteUsers) maxUid = max(maxUid, ru.remoteUserId)
            val created = RemoteUser(maxUid + 1, String(nick, Charsets.US_ASCII), 1900 + year, user.appUserId, isNew = true)
            currentRemoteUser = created
            sendCommand(CMD_USER_ADD, *(encodeUserId(created) + nick + byteArrayOf(year, month, day, height, (sex or activity).toByte())))
        }

        private fun sendCommand(command: Byte, vararg params: Byte) {
            writeTo(service, chr, byteArrayOf(startByte, command) + params)
        }

        private fun sendAck(incoming: ByteArray) {
            writeTo(service, chr, byteArrayOf(startByte, CMD_APP_ACK) + incoming.copyOfRange(1, 4))
        }

        private fun sendAlternativeStart(nibble: Int, vararg payload: Byte) {
            writeTo(service, chr, byteArrayOf(alternativeStartByte(nibble)) + payload)
        }

        private fun alternativeStartByte(nibble: Int): Byte = ((startByte.toInt() and 0xF0) or (nibble and 0x0F)).toByte()

        companion object {
            const val NIBBLE_INIT = 6
            const val NIBBLE_CMD = 7
            const val NIBBLE_SET_TIME = 9
            const val CMD_SET_UNIT: Byte = 0x4d
            const val CMD_SCALE_STATUS: Byte = 0x4f
            const val CMD_USER_ADD: Byte = 0x31
            const val CMD_USER_LIST: Byte = 0x33
            const val CMD_USER_INFO: Byte = 0x34
            const val CMD_USER_DETAILS: Byte = 0x36
            const val CMD_DO_MEASUREMENT: Byte = 0x40
            const val CMD_GET_SAVED_MEASUREMENTS: Byte = 0x41
            const val CMD_SAVED_MEASUREMENT: Byte = 0x42
            const val CMD_DELETE_SAVED_MEASUREMENTS: Byte = 0x43
            const val CMD_WEIGHT_MEASUREMENT: Byte = 0x58
            const val CMD_MEASUREMENT: Byte = 0x59
            const val CMD_SCALE_ACK: Byte = 0xf0.toByte()
            const val CMD_APP_ACK: Byte = 0xf1.toByte()
        }
    }
}
