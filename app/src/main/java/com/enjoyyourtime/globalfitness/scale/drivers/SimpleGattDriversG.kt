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
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.Locale
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

private fun localMillis(y: Int, mo: Int, d: Int, h: Int, mi: Int, s: Int = 0): Long? = runCatching {
    LocalDateTime.of(y, mo, d, h, mi, s).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
}.getOrNull()

private const val LB_TO_KG = 0.45359237f

/** Etekcity ESF-551 (FFF0: FFF1 notify, FFF2 unit command): final frame with weight + impedance. */
class EtekcityESF551Driver : GattScaleDriver() {
    override val id = "etekcity_esf551"
    override val displayName = "Etekcity ESF-551"
    override val brand = "Etekcity"
    override val capabilities = ScaleCapabilities(impedance = true)
    override val formulaLib = FormulaLib.STANDARD_IMPEDANCE
    override val keywords = listOf("Etekcity Smart Fitness Scale", "ESF-551", "ESF551")

    override fun match(adv: AdvertisementInfo): MatchConfidence? =
        if (adv.nameOrEmpty.startsWith("Etekcity Smart Fitness Scale", ignoreCase = true)) MatchConfidence.HIGH else null

    override fun newProtocol(adv: AdvertisementInfo?): GattProtocol = object : GattProtocol() {
        private val svc = uuid16(0xFFF0)
        private val chrNotify = uuid16(0xFFF1)
        private val chrCmd = uuid16(0xFFF2)

        override fun onConnected() {
            writeTo(svc, chrCmd, unitCommand(user.unit), withResponse = false)
            setNotifyOn(svc, chrNotify)
            userInfo("Súbete a la báscula")
        }

        private var weightOnly: Cancellable? = null
        private var done = false

        override fun onNotification(characteristic: UUID, data: ByteArray) {
            if (characteristic != chrNotify || done) return
            val frame = parse(data) ?: return
            if (frame.final) {
                finish(frame)
                return
            }
            live(frame.weightKg)
            // Locked without impedance (shoes, no contact): weight only, unless the impedance follows.
            if (frame.locked && weightOnly == null) weightOnly = schedule(WEIGHT_ONLY_WAIT_MS) { finish(frame) }
        }

        private fun finish(frame: Frame) {
            if (done) return
            done = true
            weightOnly?.cancel()
            publish(ScaleSample().apply {
                weightKg = frame.weightKg
                if (frame.impedance in 1 until 1500) impedance = frame.impedance.toFloat()
            })
            requestDisconnect()
        }
    }

    data class Frame(val weightKg: Float, val impedance: Int, val final: Boolean, val locked: Boolean = final)

    companion object {
        private const val WEIGHT_ONLY_WAIT_MS = 4_000L

        fun unitCommand(unit: ScaleUnit): ByteArray {
            val u = unit.ordinal
            return byteArrayOf(0xA5.toByte(), 0x22, 0x03, 0x05, 0x00, (43 - u).toByte(), 0x01, 0x63, 0xA1.toByte(), 0x00, u.toByte())
        }

        /** 22-byte `A5 02 ?? 10 00 ?? 01 61 A1 00` frames; final when byte 20 is 1 and an impedance was measured. */
        fun parse(d: ByteArray): Frame? {
            if (d.size != 22 || d[0] != 0xA5.toByte() || d[1] != 0x02.toByte() || d[3] != 0x10.toByte() || d[4] != 0.toByte() ||
                d[6] != 0x01.toByte() || d[7] != 0x61.toByte() || d[8] != 0xA1.toByte() || d[9] != 0.toByte()
            ) {
                return null
            }
            val kg = Bytes.fromUnsignedInt24Le(d, 10) / 1000f
            val impedance = Bytes.fromUnsignedInt16Le(d, 13)
            val locked = d[20] == 1.toByte()
            return Frame(kg, impedance, locked && impedance > 0, locked)
        }
    }
}

/** DIY "openScale" (HM-10 serial on FFE0/FFE1): ASCII lines, `$D$` records with fat/water/muscle. */
class CustomOpenScaleDriver : GattScaleDriver() {
    override val id = "custom_openscale"
    override val displayName = "openScale DIY (Arduino + HM-10)"
    override val brand = "openScale"
    override val capabilities = ScaleCapabilities(deviceComposition = true, history = true)
    override val formulaLib: FormulaLib? = null

    override fun match(adv: AdvertisementInfo): MatchConfidence? =
        if (adv.nameOrEmpty.trim().equals("openScale", ignoreCase = true)) MatchConfidence.HIGH else null

    override fun newProtocol(adv: AdvertisementInfo?): GattProtocol = object : GattProtocol() {
        private val svc = uuid16(0xFFE0)
        private val chr = uuid16(0xFFE1)
        private val lines = StringBuilder()

        override fun onConnected() {
            setNotifyOn(svc, chr)
            writeTo(svc, chr, timeCommand(env.wallClock()))
        }

        override fun onNotification(characteristic: UUID, data: ByteArray) {
            if (characteristic != chr || data.isEmpty()) return
            lines.append(String(data, Charsets.ISO_8859_1))
            var idx = lines.indexOf("\n")
            while (idx >= 0) {
                val line = lines.substring(0, idx).trimEnd('\r')
                lines.delete(0, idx + 1)
                handleLine(line)
                idx = lines.indexOf("\n")
            }
        }

        private fun handleLine(line: String) {
            if (line.length < 3 || line[0] != '$' || line[2] != '$') return
            when (line[1]) {
                'F' -> {
                    // All records sent: clear the scale's memory and finish.
                    writeTo(svc, chr, byteArrayOf('9'.code.toByte()))
                    requestDisconnect()
                }
                'D' -> parseRecord(line.substring(3))?.let { publish(it) } ?: userInfo("Registro de la báscula no válido")
                else -> Unit
            }
        }
    }

    companion object {
        fun timeCommand(nowMillis: Long): ByteArray {
            val t = Instant.ofEpochMilli(nowMillis).atZone(ZoneId.systemDefault())
            return String.format(Locale.US, "2%d,%d,%d,%d,%d,%d,", t.year - 2000, t.monthValue, t.dayOfMonth, t.hour, t.minute, t.second)
                .toByteArray(Charsets.ISO_8859_1)
        }

        /** `idx,year,month,day,hour,minute,weight,fat,water,muscle,checksum` (XOR of the integer parts). */
        fun parseRecord(csv: String): ScaleSample? {
            val f = csv.split(',')
            if (f.size < 11) return null
            return runCatching {
                val yearRaw = f[1].trim().toInt()
                val month = f[2].trim().toInt()
                val day = f[3].trim().toInt()
                val hour = f[4].trim().toInt()
                val minute = f[5].trim().toInt()
                val weight = f[6].trim().toFloat()
                val fat = f[7].trim().toFloat()
                val water = f[8].trim().toFloat()
                val muscle = f[9].trim().toFloat()
                val checksum = listOf(f[0].trim().toInt(), yearRaw, month, day, hour, minute, weight.toInt(), fat.toInt(), water.toInt(), muscle.toInt())
                    .fold(0) { acc, v -> acc xor v }
                if (checksum != f[10].trim().toInt()) return null
                val time = localMillis(if (yearRaw < 100) 2000 + yearRaw else yearRaw, month, day, hour, minute) ?: return null
                ScaleSample().apply {
                    dateTime = time
                    weightKg = weight
                    fatPercent = fat
                    waterPercent = water
                    musclePercent = muscle
                    fromHistory = true
                }
            }.getOrNull()
        }
    }
}

/**
 * Hoffen BBS-8107 / ProfiCare PC-PW 3008 BT (Chipsea "WeChat scale", FFB0/FFB2): `FA cmd len payload xor`
 * frames; user and unit handshake, final frame with the scale's composition.
 */
class HoffenBbs8107Driver : GattScaleDriver() {
    override val id = "hoffen_bbs8107"
    override val displayName = "Hoffen BBS-8107 · ProfiCare PC-PW 3008 BT"
    override val brand = "Hoffen / ProfiCare"
    override val capabilities = ScaleCapabilities(deviceComposition = true)
    override val formulaLib: FormulaLib? = null
    override val keywords = listOf("Hoffen BS-8107", "PC-PW 3008 BT")

    override fun match(adv: AdvertisementInfo): MatchConfidence? =
        if (adv.nameOrEmpty.trim().lowercase(Locale.US) in NAMES) MatchConfidence.HIGH else null

    override fun newProtocol(adv: AdvertisementInfo?): GattProtocol = object : GattProtocol() {
        private val svc = uuid16(0xFFB0)
        private val chr = uuid16(0xFFB2)
        private var phase = 0 // 0 idle, 1 user sent, 2 unit sent, 3 waiting, 4 done sent

        private fun send(cmd: Int, payload: ByteArray) = writeTo(svc, chr, frame(cmd, payload))

        override fun onConnected() {
            setNotifyOn(svc, chr)
            send(0x85, byteArrayOf(0x00, if (user.isMale) 0x01 else 0x00, user.ageAt(env.wallClock()).toByte(), user.heightCm.toInt().toByte()))
            phase = 1
        }

        override fun onNotification(characteristic: UUID, data: ByteArray) {
            if (characteristic != chr || data.size < 2) return
            val checksumOk = data.size >= 4 && Bytes.xor(data, 1, data.size) == 0
            if (!checksumOk && !(data[0] == 0xFA.toByte() && data[1] == 0x02.toByte())) return
            if (data[0] != 0xFA.toByte()) return
            when (data[1].toInt()) {
                0x03 -> when (phase) {
                    1 -> {
                        send(0x83, byteArrayOf((1 + user.unit.ordinal).toByte(), 0x00))
                        phase = 2
                    }
                    2 -> {
                        userInfo("Súbete a la báscula")
                        phase = 3
                    }
                    4 -> {
                        requestDisconnect()
                        phase = 0
                    }
                }
                0x01 -> if (data.size >= 6) live(toKg(Bytes.fromUnsignedInt16Le(data, 4) / 10f, user.unit))
                0x02 -> {
                    parseFinal(data, user.unit)?.let { publish(it) }
                    send(0x82, byteArrayOf(0x00))
                    phase = 4
                }
            }
        }
    }

    companion object {
        private val NAMES = setOf("hoffen bs-8107", "pc-pw 3008 bt")

        fun frame(cmd: Int, payload: ByteArray): ByteArray {
            val out = byteArrayOf(0xFA.toByte(), cmd.toByte(), payload.size.toByte()) + payload + byteArrayOf(0)
            out[out.lastIndex] = Bytes.xor(out, 1, out.lastIndex).toByte()
            return out
        }

        /** The scale reports pounds when set to lb/st. */
        private fun toKg(value: Float, unit: ScaleUnit) = if (unit == ScaleUnit.KG) value else value * LB_TO_KG

        fun parseFinal(f: ByteArray, unit: ScaleUnit): ScaleSample? {
            if (f.size < 6) return null
            val m = ScaleSample().apply { weightKg = maxOf(0f, toKg(Bytes.fromUnsignedInt16Le(f, 3) / 10f, unit)) }
            if (f[5] == 0x00.toByte() && f.size >= 19) {
                // Barefoot contact: the scale computed the composition.
                m.fatPercent = Bytes.fromUnsignedInt16Le(f, 6) / 10f
                m.waterPercent = Bytes.fromUnsignedInt16Le(f, 8) / 10f
                m.musclePercent = Bytes.fromUnsignedInt16Le(f, 10) / 10f
                m.boneKg = Bytes.u8(f, 14) / 10f
                m.visceralFat = Bytes.fromUnsignedInt16Le(f, 17) / 10f
            }
            return m
        }
    }
}

/**
 * Senssun Fat (model A FFF0/FFF1/FFF2 or model B FFB0/FFB2): date/time sync, user sent once the
 * weight is stable, then fat/water, muscle/bone and kcal frames (all four needed to publish).
 */
class SenssunDriver : GattScaleDriver() {
    override val id = "senssun_fat"
    override val displayName = "Senssun Fat"
    override val brand = "Senssun"
    override val capabilities = ScaleCapabilities(deviceComposition = true)
    override val formulaLib: FormulaLib? = null

    override fun match(adv: AdvertisementInfo): MatchConfidence? =
        if (adv.nameOrEmpty.equals("SENSSUN FAT", ignoreCase = true)) MatchConfidence.HIGH else null

    override fun newProtocol(adv: AdvertisementInfo?): GattProtocol = Protocol()

    internal class Protocol : GattProtocol() {
        private val svcA = uuid16(0xFFF0)
        private val notifyA = uuid16(0xFFF1)
        private val writeA = uuid16(0xFFF2)
        private val svcB = uuid16(0xFFB0)
        private val chrB = uuid16(0xFFB2)
        private var active: Pair<UUID, UUID>? = null
        private var weight = 0
        private var fat = 0
        private var water = 0
        private var muscle = 0
        private var bone = 0
        private var stable = false
        private var prompted = false
        private var mask = 0

        override fun onConnected() {
            setNotifyOn(svcA, notifyA)
            setNotifyOn(svcB, chrB)
            send(dateCommand(env.wallClock()))
            send(timeCommand(env.wallClock()))
        }

        /** To the model that answered, or to both until one does (the missing one just fails). */
        private fun send(msg: ByteArray) {
            val target = active
            if (target != null) {
                writeTo(target.first, target.second, msg)
            } else {
                writeTo(svcA, writeA, msg)
                writeTo(svcB, chrB, msg)
            }
        }

        override fun onNotification(characteristic: UUID, data: ByteArray) {
            if (data.isEmpty() || data[0] != 0xFF.toByte()) return
            if (active == null) {
                active = when (characteristic) {
                    notifyA -> svcA to writeA
                    chrB -> svcB to chrB
                    else -> null
                }
            }
            val f = data.copyOfRange(1, data.size)
            if (f.size < 6 || f[0] != 0xA5.toByte()) return
            when (Bytes.u8(f, 5)) {
                0xAA, 0xA0 -> {
                    if (!prompted) {
                        prompted = true
                        userInfo("Súbete a la báscula")
                    }
                    if (stable) return
                    stable = Bytes.u8(f, 5) == 0xAA
                    weight = Bytes.fromUnsignedInt16Be(f, 1)
                    if (stable) {
                        mask = mask or 0x01
                        send(userCommand(user.isMale, user.userSlot, user.ageAt(env.wallClock()), user.heightCm.toInt()))
                    } else {
                        live(weight / 10f)
                    }
                }
                0xBE -> {
                    userInfo("La báscula no pudo medir la grasa corporal")
                    requestDisconnect()
                }
                0xB0 -> {
                    fat = Bytes.fromUnsignedInt16Be(f, 1)
                    water = Bytes.fromUnsignedInt16Be(f, 3)
                    mask = mask or 0x02
                }
                0xC0 -> {
                    muscle = Bytes.fromUnsignedInt16Be(f, 1)
                    bone = Bytes.fromUnsignedInt16Le(f, 3)
                    mask = mask or 0x04
                }
                0xD0 -> mask = mask or 0x08
            }
            if (mask == 0x0F) {
                mask = 0
                publish(ScaleSample().apply {
                    weightKg = weight / 10f
                    fatPercent = fat / 10f
                    waterPercent = water / 10f
                    boneKg = bone / 10f
                    musclePercent = muscle / 10f
                })
                requestDisconnect()
            }
        }
    }

    companion object {
        private fun withChecksum(msg: ByteArray): ByteArray {
            msg[msg.size - 2] = Bytes.sum8(msg, 1, msg.size - 2).toByte()
            return msg
        }

        fun dateCommand(nowMillis: Long): ByteArray {
            val t = Instant.ofEpochMilli(nowMillis).atZone(ZoneId.systemDefault())
            return withChecksum(byteArrayOf(0xA5.toByte(), 0x30, (t.year % 100).toByte(), (t.dayOfYear shr 8).toByte(), t.dayOfYear.toByte(), 0, 0, 0, 0))
        }

        fun timeCommand(nowMillis: Long): ByteArray {
            val t = Instant.ofEpochMilli(nowMillis).atZone(ZoneId.systemDefault())
            return withChecksum(byteArrayOf(0xA5.toByte(), 0x31, t.hour.toByte(), t.minute.toByte(), t.second.toByte(), 0, 0, 0, 0))
        }

        fun userCommand(male: Boolean, userId: Int, age: Int, heightCm: Int): ByteArray =
            withChecksum(byteArrayOf(0xA5.toByte(), 0x10, ((if (male) 15 else 0) * 16 + userId).toByte(), age.toByte(), heightCm.toByte(), 0, 0, 0, 0))
    }
}

/**
 * Shared transport of the Huawei AH100 / CH100 and Honor CH100S (service FAA0): MAC-XOR
 * obfuscation, AES-CTR with the vendor key, auth token and command frames.
 */
internal object HuaweiFaa0 {
    val SERVICE: UUID = uuid16(0xFAA0)
    val CHAR_TX: UUID = uuid16(0xFAA1)
    val CHAR_RX: UUID = uuid16(0xFAA2)
    val INITIAL_KEY: ByteArray = Bytes.fromHex("3DA2784AFB87B12A980FDE3456732156")
    val INITIAL_IV: ByteArray = Bytes.fromHex("4EF764322FDA7632123DEB8790FEA219")

    const val NTFY_WAKEUP = 0x00
    const val NTFY_MEASUREMENT = 0x0E
    const val NTFY_MEASUREMENT2 = 0x8E
    const val NTFY_HISTORY_RECORD = 0x10
    const val NTFY_HISTORY_RECORD2 = 0x90
    const val NTFY_USER_CHANGED = 0x20
    const val NTFY_AUTH_RESULT = 0x26
    const val NTFY_BIND_OK = 0x27
    const val CMD_SET_UNIT = 2
    const val CMD_SET_CLOCK = 8
    const val CMD_USER_INFO = 9
    const val CMD_GET_RECORD = 11
    const val CMD_GET_VERSION = 12
    const val CMD_FAT_ACK = 19
    const val CMD_AUTH = 36
    const val CMD_BIND = 37

    fun macBytes(address: String?): ByteArray {
        val clean = address.orEmpty().replace(":", "").replace("-", "")
        return if (clean.length == 12 && Bytes.isHex(clean)) Bytes.fromHex(clean) else ByteArray(6)
    }

    fun macXor(data: ByteArray, mac: ByteArray): ByteArray = ByteArray(data.size) { i -> (data[i].toInt() xor mac[i % mac.size].toInt()).toByte() }

    fun aesCtr(data: ByteArray, key: ByteArray, iv: ByteArray = INITIAL_IV): ByteArray =
        Cipher.getInstance("AES/CTR/NoPadding").apply { init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv)) }.doFinal(data)

    fun authToken(appUserId: Int): ByteArray {
        val auth = byteArrayOf(0x11, 0x22, 0x33, 0x44, 0x55, 0x00, appUserId.toByte())
        auth[5] = Bytes.xor(auth).toByte()
        return auth
    }

    /** AH100/CH100 session key: obfuscated auth token + the tail of the vendor key. */
    fun magicKey(auth: ByteArray, mac: ByteArray): ByteArray = macXor(auth, mac) + INITIAL_KEY.copyOfRange(7, 16)

    fun plain(cmd: Int, payload: ByteArray, mac: ByteArray, len: Int = payload.size + 1): ByteArray =
        byteArrayOf(0xDB.toByte(), len.toByte(), cmd.toByte()) + macXor(payload, mac)

    fun clock(nowMillis: Long): ByteArray {
        val t = Instant.ofEpochMilli(nowMillis).atZone(ZoneId.systemDefault())
        // Day of week Monday = 1 … Sunday = 7, as the vendor app sends it.
        return Bytes.toInt16Le(t.year) +
            byteArrayOf(t.monthValue.toByte(), t.dayOfMonth.toByte(), t.hour.toByte(), t.minute.toByte(), t.second.toByte(), t.dayOfWeek.value.toByte())
    }

    /** Body weight for the user record, never 0 (the scale loops on a weightless record). */
    fun userWeightTenths(lastWeightKg: Float?, heightCm: Float): Int {
        val kg = lastWeightKg?.takeIf { it.isFinite() && it > 0f } ?: (heightCm / 100f).let { h -> if (h > 0.5f) 22f * h * h else 70f }
        return (kg * 10f).toInt().coerceAtLeast(1)
    }

    data class Record(val userId: Int, val weightKg: Float, val fatPercent: Float, val impedance: Int, val year: Int, val month: Int, val day: Int, val hour: Int, val minute: Int, val second: Int)

    /** `[userId, weight LE /10, fat LE /10, year LE, month, day, hour, min, sec, dow, impedance LE]`. */
    fun parseRecord(d: ByteArray): Record? {
        if (d.size < 15) return null
        return Record(
            userId = Bytes.u8(d, 0), weightKg = Bytes.fromUnsignedInt16Le(d, 1) / 10f, fatPercent = Bytes.fromUnsignedInt16Le(d, 3) / 10f,
            impedance = Bytes.fromUnsignedInt16Le(d, 13), year = Bytes.fromUnsignedInt16Le(d, 5), month = Bytes.u8(d, 7), day = Bytes.u8(d, 8),
            hour = Bytes.u8(d, 9), minute = Bytes.u8(d, 10), second = Bytes.u8(d, 11),
        )
    }

    fun sample(r: Record, requirePlausibleYear: Boolean): ScaleSample = ScaleSample().apply {
        weightKg = r.weightKg
        fatPercent = r.fatPercent.takeIf { it > 0f }
        if (r.impedance in 1..3999) impedance = r.impedance.toFloat()
        dateTime = if (!requirePlausibleYear || (r.year in 2020..2099 && r.month in 1..12 && r.day in 1..31)) {
            localMillis(r.year, r.month.coerceIn(1, 12), r.day, r.hour, r.minute, r.second)
        } else {
            null
        }
    }
}

/**
 * Huawei AH100 / Huawei-Honor CH100 (FAA0): auth/bind handshake, user record encrypted with a
 * session key derived from the auth token, measurements and history records (weight, fat %,
 * impedance) AES-CTR encrypted.
 */
class HuaweiAh100Driver : GattScaleDriver() {
    override val id = "huawei_ah100"
    override val displayName = "Huawei AH100 / CH100"
    override val brand = "Huawei / Honor"
    override val capabilities = ScaleCapabilities(impedance = true, deviceComposition = true, userRegistration = true)
    override val formulaLib: FormulaLib? = null
    override val keywords = listOf("AH100", "CH100", "Honor", "Body Fat Scale")

    override fun match(adv: AdvertisementInfo): MatchConfidence? {
        val cleaned = adv.nameOrEmpty.replace("\u0000", "").trim().uppercase(Locale.US)
        if (cleaned.isEmpty()) return null
        val candidates = setOf(
            cleaned, cleaned.removePrefix("HUAWEI ").trim(), cleaned.removePrefix("HONOR ").trim(),
            cleaned.substringBefore('-'), cleaned.substringBefore('_'), cleaned.substringBefore(' '),
        )
        return if ("AH100" in candidates || "CH100" in candidates) MatchConfidence.HIGH else null
    }

    override fun newProtocol(adv: AdvertisementInfo?): GattProtocol = Protocol()

    internal class Protocol : GattProtocol() {
        private lateinit var auth: ByteArray
        private lateinit var mac: ByteArray
        private var magicKey: ByteArray? = null
        private var tries = 0
        private var authorised = false
        private var lastWeightTenths = -1
        private var pendingFirst: ByteArray? = null
        private var pendingType = 0

        private fun cmd(cmd: Int, payload: ByteArray) = writeTo(HuaweiFaa0.SERVICE, HuaweiFaa0.CHAR_TX, HuaweiFaa0.plain(cmd, payload, mac))

        override fun onConnected() {
            auth = HuaweiFaa0.authToken(user.appUserId)
            mac = HuaweiFaa0.macBytes(env.address)
            setNotifyOn(HuaweiFaa0.SERVICE, HuaweiFaa0.CHAR_RX)
            userInfo("Súbete a la báscula")
        }

        override fun onNotification(characteristic: UUID, data: ByteArray) {
            if (characteristic != HuaweiFaa0.CHAR_RX || data.size < 3) return
            val op = Bytes.u8(data, 2)
            val tail = HuaweiFaa0.macXor(data.copyOfRange(3, data.size), mac)
            when (op) {
                HuaweiFaa0.NTFY_WAKEUP -> if (!authorised) cmd(HuaweiFaa0.CMD_AUTH, auth)
                HuaweiFaa0.NTFY_AUTH_RESULT -> if (tail.isNotEmpty() && tail[0].toInt() == 1) {
                    authorised = true
                    magicKey = HuaweiFaa0.magicKey(auth, mac)
                    cmd(HuaweiFaa0.CMD_SET_UNIT, byteArrayOf(0x01))
                    cmd(HuaweiFaa0.CMD_SET_CLOCK, HuaweiFaa0.clock(env.wallClock()))
                    sendUserInfo()
                    cmd(HuaweiFaa0.CMD_GET_VERSION, byteArrayOf())
                } else if (tries++ < 2) {
                    cmd(HuaweiFaa0.CMD_AUTH, auth)
                } else {
                    cmd(HuaweiFaa0.CMD_BIND, auth)
                }
                HuaweiFaa0.NTFY_BIND_OK -> cmd(HuaweiFaa0.CMD_AUTH, auth)
                HuaweiFaa0.NTFY_USER_CHANGED -> if (authorised) sendUserInfo()
                HuaweiFaa0.NTFY_MEASUREMENT, HuaweiFaa0.NTFY_HISTORY_RECORD -> if (data[0] == 0xBC.toByte()) {
                    pendingType = op
                    pendingFirst = data
                    // A long first half already holds the whole record.
                    if (tail.size >= 15 && decode(data, op)) {
                        pendingFirst = null
                        pendingType = 0
                    }
                }
                HuaweiFaa0.NTFY_MEASUREMENT2, HuaweiFaa0.NTFY_HISTORY_RECORD2 -> if (data[0] == 0xBC.toByte()) {
                    val first = pendingFirst
                    val type = pendingType
                    pendingFirst = null
                    pendingType = 0
                    if (first != null) decode(first, type)
                }
            }
        }

        private fun decode(first: ByteArray, type: Int): Boolean {
            val key = magicKey ?: return false
            val plain = runCatching { HuaweiFaa0.aesCtr(HuaweiFaa0.macXor(first.copyOfRange(3, first.size), mac), key) }.getOrNull() ?: return false
            val record = HuaweiFaa0.parseRecord(plain) ?: return false
            if (record.userId != (user.appUserId and 0xFF)) {
                // Another person's record: acknowledged (so history moves on) but not imported.
                cmd(HuaweiFaa0.CMD_FAT_ACK, byteArrayOf(0x00))
                if (type == HuaweiFaa0.NTFY_HISTORY_RECORD) cmd(HuaweiFaa0.CMD_GET_RECORD, byteArrayOf(0x01))
                return true
            }
            lastWeightTenths = (record.weightKg * 10f).toInt()
            publish(HuaweiFaa0.sample(record, requirePlausibleYear = false).apply { fromHistory = type == HuaweiFaa0.NTFY_HISTORY_RECORD })
            cmd(HuaweiFaa0.CMD_FAT_ACK, byteArrayOf(0x00))
            if (type == HuaweiFaa0.NTFY_HISTORY_RECORD) cmd(HuaweiFaa0.CMD_GET_RECORD, byteArrayOf(0x01))
            return true
        }

        private fun sendUserInfo() {
            val key = magicKey ?: return
            val ageSex = (user.ageAt(env.wallClock()) and 0xFF) or (if (user.isMale) 0x00 else 0x80)
            val w = lastWeightTenths.takeIf { it > 0 } ?: HuaweiFaa0.userWeightTenths(user.lastWeightKg, user.heightCm)
            val payload = auth + byteArrayOf(ageSex.toByte(), user.heightCm.toInt().toByte(), 0x00) + Bytes.toInt16Le(w) +
                byteArrayOf(0xFF.toByte(), 0xFF.toByte(), 0x1C, 0xE2.toByte())
            val encrypted = HuaweiFaa0.aesCtr(payload, key)
            // The declared length leaves out the two trailing bytes.
            val frame = byteArrayOf(0xDC.toByte(), (payload.size - 2).toByte(), HuaweiFaa0.CMD_USER_INFO.toByte()) + HuaweiFaa0.macXor(encrypted, mac)
            writeTo(HuaweiFaa0.SERVICE, HuaweiFaa0.CHAR_TX, frame)
        }
    }
}

/**
 * Honor Smart Scale CH100S (Chipsea CST34M97, FAA0): like the AH100 but measurement halves are
 * AES-CTR encrypted with the vendor key and then MAC-XORed; composition from the Chipsea model.
 */
class HuaweiCh100sDriver : GattScaleDriver() {
    override val id = "honor_ch100s"
    override val displayName = "Honor Smart Scale CH100S"
    override val brand = "Huawei / Honor"
    override val capabilities = ScaleCapabilities(impedance = true, deviceComposition = true, userRegistration = true, history = true)
    override val formulaLib = FormulaLib.ETEKCITY

    override fun match(adv: AdvertisementInfo): MatchConfidence? =
        if (adv.nameOrEmpty.uppercase(Locale.US) == "CH100S") MatchConfidence.HIGH else null

    override fun newProtocol(adv: AdvertisementInfo?): GattProtocol = Protocol()

    internal class Protocol : GattProtocol() {
        private lateinit var auth: ByteArray
        private lateinit var mac: ByteArray
        private var tries = 0
        private var authorised = false
        private var userInfoRetries = 0
        private var lastWeightTenths = -1
        private var pendingFirst: ByteArray? = null
        private var pendingType = 0

        private fun plain(cmd: Int, payload: ByteArray) =
            writeTo(HuaweiFaa0.SERVICE, HuaweiFaa0.CHAR_TX, HuaweiFaa0.plain(cmd, payload, mac, len = payload.size))

        override fun onConnected() {
            auth = HuaweiFaa0.authToken(user.appUserId)
            mac = HuaweiFaa0.macBytes(env.address)
            setNotifyOn(HuaweiFaa0.SERVICE, HuaweiFaa0.CHAR_RX)
            userInfo("Súbete a la báscula")
        }

        override fun onNotification(characteristic: UUID, data: ByteArray) {
            if (characteristic != HuaweiFaa0.CHAR_RX || data.size < 3) return
            val op = Bytes.u8(data, 2)
            val payload = HuaweiFaa0.macXor(data.copyOfRange(3, data.size), mac)
            when (op) {
                HuaweiFaa0.NTFY_WAKEUP -> if (!authorised) plain(HuaweiFaa0.CMD_AUTH, auth)
                HuaweiFaa0.NTFY_AUTH_RESULT -> if (payload.isNotEmpty() && payload[0].toInt() == 1) {
                    authorised = true
                    plain(HuaweiFaa0.CMD_SET_UNIT, byteArrayOf(0x01))
                    plain(HuaweiFaa0.CMD_SET_CLOCK, HuaweiFaa0.clock(env.wallClock()))
                    sendUserInfo()
                    plain(HuaweiFaa0.CMD_GET_VERSION, byteArrayOf())
                    userInfoRetries = 0
                } else if (tries++ < 2) {
                    plain(HuaweiFaa0.CMD_AUTH, auth)
                } else {
                    plain(HuaweiFaa0.CMD_BIND, auth)
                }
                HuaweiFaa0.NTFY_USER_CHANGED -> if (userInfoRetries++ < 5) sendUserInfo()
                HuaweiFaa0.NTFY_MEASUREMENT, HuaweiFaa0.NTFY_HISTORY_RECORD -> if (data[0] == 0xBC.toByte()) {
                    pendingType = op
                    pendingFirst = data
                }
                HuaweiFaa0.NTFY_MEASUREMENT2, HuaweiFaa0.NTFY_HISTORY_RECORD2 -> if (data[0] == 0xBC.toByte()) {
                    val first = pendingFirst
                    val type = pendingType
                    pendingFirst = null
                    pendingType = 0
                    if (first != null) handlePair(first, data, type)
                }
            }
        }

        private fun handlePair(first: ByteArray, second: ByteArray, type: Int) {
            fun decrypt(frame: ByteArray): ByteArray {
                val raw = frame.copyOfRange(3, frame.size)
                return HuaweiFaa0.macXor(runCatching { HuaweiFaa0.aesCtr(raw, HuaweiFaa0.INITIAL_KEY) }.getOrDefault(raw), mac)
            }
            val record = HuaweiFaa0.parseRecord(decrypt(first) + decrypt(second)) ?: return
            if (record.userId != (user.appUserId and 0xFF)) {
                // Another person's record: acknowledged (so history moves on) but not imported.
                plain(HuaweiFaa0.CMD_FAT_ACK, byteArrayOf(0x00))
                if (type == HuaweiFaa0.NTFY_HISTORY_RECORD) plain(HuaweiFaa0.CMD_GET_RECORD, byteArrayOf(0x01))
                return
            }
            lastWeightTenths = (record.weightKg * 10f).toInt()
            publish(HuaweiFaa0.sample(record, requirePlausibleYear = true).apply { fromHistory = type == HuaweiFaa0.NTFY_HISTORY_RECORD })
            plain(HuaweiFaa0.CMD_FAT_ACK, byteArrayOf(0x00))
            if (type == HuaweiFaa0.NTFY_HISTORY_RECORD) plain(HuaweiFaa0.CMD_GET_RECORD, byteArrayOf(0x01))
        }

        private fun sendUserInfo() {
            val ageSex = (user.ageAt(env.wallClock()) and 0x7F) or (if (user.isMale) 0x00 else 0x80)
            val w = lastWeightTenths.takeIf { it > 0 } ?: HuaweiFaa0.userWeightTenths(user.lastWeightKg, user.heightCm)
            val payload = auth + byteArrayOf(ageSex.toByte(), user.heightCm.toInt().toByte(), 0x00) + Bytes.toInt16Le(w) + Bytes.toInt16Le(0xFFFF)
            val encrypted = HuaweiFaa0.aesCtr(HuaweiFaa0.macXor(payload, mac), HuaweiFaa0.INITIAL_KEY)
            writeTo(HuaweiFaa0.SERVICE, HuaweiFaa0.CHAR_TX, byteArrayOf(0xDC.toByte(), payload.size.toByte(), HuaweiFaa0.CMD_USER_INFO.toByte()) + encrypted)
        }
    }
}
