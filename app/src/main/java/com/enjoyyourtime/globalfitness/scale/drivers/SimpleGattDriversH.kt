// Protocol ported from openScale (GPL-3.0), https://github.com/oliexdev/openScale
package com.enjoyyourtime.globalfitness.scale.drivers

import com.enjoyyourtime.globalfitness.scale.AdvertisementInfo
import com.enjoyyourtime.globalfitness.scale.Bytes
import com.enjoyyourtime.globalfitness.scale.Cancellable
import com.enjoyyourtime.globalfitness.scale.ConfigField
import com.enjoyyourtime.globalfitness.scale.FormulaLib
import com.enjoyyourtime.globalfitness.scale.GattPacing
import com.enjoyyourtime.globalfitness.scale.GattProtocol
import com.enjoyyourtime.globalfitness.scale.GattScaleDriver
import com.enjoyyourtime.globalfitness.scale.MatchConfidence
import com.enjoyyourtime.globalfitness.scale.ScaleCapabilities
import com.enjoyyourtime.globalfitness.scale.ScaleSample
import com.enjoyyourtime.globalfitness.scale.ScaleUnit
import com.enjoyyourtime.globalfitness.scale.libs.KeepS3BodyComposition
import com.enjoyyourtime.globalfitness.scale.libs.KeepS3Protocol
import com.enjoyyourtime.globalfitness.scale.libs.OmronBodyCompositionLib
import com.enjoyyourtime.globalfitness.scale.libs.OmronWlpFrame
import com.enjoyyourtime.globalfitness.scale.uuid16
import java.security.SecureRandom
import java.util.Locale
import java.util.TimeZone
import java.util.UUID
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * Slot the app uses on a Transtek-style scale with eight named user slots, without asking: the
 * configured one, else the one learned earlier, else a slot already named like the user, else
 * the lowest free slot. Null when none of those exists (the scale is full).
 */
internal fun transtekSlot(configured: Int?, learned: Int?, userName: String, nameLength: Int, slotNames: Map<Int, String>, freeSlots: Set<Int>): Int? {
    configured?.takeIf { it in 1..8 }?.let { return it }
    learned?.takeIf { it in 1..8 }?.let { return it }
    val wanted = userName.take(nameLength).trim()
    if (wanted.isNotEmpty()) {
        slotNames.entries.filter { it.value.equals(wanted, ignoreCase = true) }.minByOrNull { it.key }?.let { return it.key }
    }
    return freeSlots.minOrNull()
}

internal fun transtekSlotName(frame: ByteArray, nameLength: Int): String =
    if (frame.size <= 2) "" else frame.copyOfRange(2, minOf(frame.size, 2 + nameLength)).map { (it.toInt() and 0xFF).toChar() }
        .filter { it.code in 0x20..0x7E }.joinToString("").trim()

internal fun transtekSlotOccupied(frame: ByteArray): Boolean = (2 until frame.size).any { i -> Bytes.u8(frame, i) != 0x00 && Bytes.u8(frame, i) != 0x20 }

private const val TRANSTEK_FULL = "La báscula no tiene usuarios libres: elige tu usuario (1-8) en la configuración de la báscula"

/**
 * 1BODY CONNECT / X-LINE (Transtek, service 7892): password pairing, XOR challenge, user slot
 * registration (eight named slots), then weight (0x1F) + composition (0x7F) frames.
 */
class BodyConnectDriver : GattScaleDriver() {
    override val id = "body_connect"
    override val displayName = "1BODY CONNECT · X-LINE"
    override val brand = "1BODY / Transtek"
    override val capabilities = ScaleCapabilities(deviceComposition = true, userRegistration = true, history = true, config = listOf(ConfigField.userSlot(8)))
    override val formulaLib: FormulaLib? = null

    override fun match(adv: AdvertisementInfo): MatchConfidence? {
        val n = adv.nameOrEmpty
        return if (listOf("1BODY CONNECT", "0BODY CONNECT", "0X-LINE", "1X-LINE").any { n.startsWith(it) }) MatchConfidence.HIGH else null
    }

    override fun newProtocol(adv: AdvertisementInfo?): GattProtocol = Protocol()

    internal class Protocol : GattProtocol() {
        private val svc = uuid16(0x7892)
        private val chrWeight = uuid16(0x8A24)
        private val chrBody = uuid16(0x8A22)
        private val chrDownload = uuid16(0x8A81)
        private val chrUpload = uuid16(0x8A82)
        private var pairing = false
        private var password: Int? = null
        private var slotAcked = false
        private val freeSlots = mutableSetOf<Int>()
        private val slotNames = mutableMapOf<Int, String>()
        private var lastTs: Int? = null
        private var lastWeight: Float? = null

        private fun cmd(op: Int, payload: ByteArray = byteArrayOf()) = writeTo(svc, chrDownload, byteArrayOf(op.toByte()) + payload)

        private fun cmdInt(op: Int, arg: Int) = cmd(op, Bytes.toInt32Le(arg.toLong() and 0xFFFFFFFFL))

        private fun currentSlot(): Int = settingsGetString(ConfigField.KEY_USER_SLOT)?.toIntOrNull()?.takeIf { it in 1..8 }
            ?: settingsGetInt(KEY_SLOT).takeIf { it in 1..8 } ?: 1

        override fun onConnected() {
            setNotifyOn(svc, chrWeight)
            setNotifyOn(svc, chrBody)
            setNotifyOn(svc, chrUpload)
            password = settingsGetString(KEY_PASSWORD)?.toIntOrNull()
        }

        override fun onNotification(characteristic: UUID, data: ByteArray) {
            when (characteristic) {
                chrUpload -> if (data.isNotEmpty()) {
                    when (Bytes.u8(data, 0)) {
                        0xA0 -> if (data.size >= 5) {
                            val pw = Bytes.fromSignedInt32Le(data, 1)
                            password = pw
                            settingsPutString(KEY_PASSWORD, pw.toString())
                            userInfo("Báscula emparejada")
                            pairing = true
                            cmdInt(0x21, SecureRandom().nextInt(Int.MAX_VALUE - 1) + 1)
                        }
                        0xA1 -> if (data.size >= 5) {
                            val pw = password
                            if (pw == null) {
                                userInfo("Báscula no emparejada: pon la báscula en modo emparejamiento")
                                requestDisconnect()
                                return
                            }
                            cmdInt(0x20, Bytes.fromSignedInt32Le(data, 1) xor pw)
                            if (!pairing) {
                                writeProfile(currentSlot())
                                cmdInt(0x02, javaTimeToDevice(env.wallClock()))
                            }
                        }
                        0xC0 -> cmd(0x22)
                        0x83 -> onSlotStatus(data)
                    }
                }
                chrWeight -> if (data.size >= 20 && data[0] == 0x1F.toByte()) {
                    lastWeight = Bytes.fromUnsignedInt16Le(data, 1) / 100f
                    lastTs = Bytes.fromSignedInt32Le(data, 5)
                    lastWeight?.let { live(it) }
                }
                chrBody -> if (data.size >= 20 && data[0] == 0x7F.toByte()) onBody(data)
            }
        }

        private fun onSlotStatus(data: ByteArray) {
            if (data.size < 18) return
            val slot = Bytes.u8(data, 1)
            if (!transtekSlotOccupied(data)) freeSlots.add(slot)
            slotNames[slot] = transtekSlotName(data, 16)
            if (slot != 8 || slotAcked) return
            slotAcked = true
            val chosen = transtekSlot(
                settingsGetString(ConfigField.KEY_USER_SLOT)?.toIntOrNull(), settingsGetInt(KEY_SLOT), user.name, 16, slotNames, freeSlots,
            )
            if (chosen == null) {
                userInfo(TRANSTEK_FULL)
                requestDisconnect()
                return
            }
            settingsPutInt(KEY_SLOT, chosen)
            val name = ByteArray(16) { 0x20 }
            user.name.take(16).replace(Regex("[^\\x00-\\x7F]"), "-").toByteArray(Charsets.ISO_8859_1).copyInto(name)
            cmd(0x03, byteArrayOf(chosen.toByte()) + name)
            writeProfile(chosen)
            cmdInt(0x02, javaTimeToDevice(env.wallClock()))
            cmd(0x22)
        }

        private fun writeProfile(slot: Int) {
            val b = ByteArray(14)
            b[0] = 0xDF.toByte()
            b[1] = slot.toByte()
            b[2] = if (user.isMale) 0x01 else 0x02
            b[3] = user.ageAt(env.wallClock()).toByte()
            b[4] = user.heightCm.toInt().toByte()
            b[5] = 0xE0.toByte()
            b[6] = 0x00
            Bytes.toInt16Le(b, 8, ((user.lastWeightKg ?: 0f) * 100).toInt())
            b[11] = 0xFE.toByte()
            cmd(0x51, b)
        }

        private fun bodyValue(data: ByteArray, off: Int): Float? {
            val lo = Bytes.u8(data, off)
            val hi = Bytes.u8(data, off + 1)
            return if (hi and 0xF0 == 0xF0) (((hi and 0x0F) shl 8) or lo) / 10f else null
        }

        private fun onBody(data: ByteArray) {
            val fat = bodyValue(data, 8)
            val water = bodyValue(data, 10)
            val muscle = bodyValue(data, 14)
            val bone = bodyValue(data, 16)
            if (fat == null && water == null && muscle == null && bone == null) return
            val ts = Bytes.fromSignedInt32Le(data, 1)
            val wts = lastTs ?: return
            if (wts != ts && wts != ts + ALT_TS_OFFSET) return
            val weight = lastWeight?.takeIf { it > 0f } ?: return
            publish(ScaleSample().apply {
                dateTime = deviceTimeToJava(wts)
                weightKg = weight
                fatPercent = fat
                waterPercent = water
                musclePercent = muscle
                boneKg = bone
            })
        }
    }

    companion object {
        private const val KEY_PASSWORD = "bodyconnect_password"
        private const val KEY_SLOT = "bodyconnect_slot"
        private const val TS_OFFSET = 1262304000L
        private const val ALT_TS_OFFSET = 315619200

        fun javaTimeToDevice(ms: Long): Int = (((ms + 500) / 1000) - TS_OFFSET).toInt()

        fun deviceTimeToJava(s: Int): Long = 1000L * (TS_OFFSET + s.toLong())
    }
}

/**
 * Weight Gurus Smart Scale 0376 (Transtek A3, names `[01]0376B…`): password pairing, random
 * challenge, eight named user slots; IEEE-11073 weight frame (+ impedance) and append frame with
 * the scale's composition.
 */
class WeightGurusA3Driver : GattScaleDriver() {
    override val id = "weight_gurus_0376"
    override val displayName = "Weight Gurus Smart Scale 0376"
    override val brand = "Weight Gurus"
    override val capabilities = ScaleCapabilities(impedance = true, deviceComposition = true, userRegistration = true, history = true, config = listOf(ConfigField.userSlot(8)))
    override val formulaLib: FormulaLib? = null

    override fun match(adv: AdvertisementInfo): MatchConfidence? = if (NAME.matches(adv.nameOrEmpty)) MatchConfidence.HIGH else null

    override fun newProtocol(adv: AdvertisementInfo?): GattProtocol = Protocol()

    internal class Protocol : GattProtocol() {
        private val chrWeight = uuid16(0x8A24)
        private val chrAppend = uuid16(0x8A22)
        private val chrDownload = uuid16(0x8A81)
        private val chrUpload = uuid16(0x8A82)
        private var password: ByteArray? = null
        private var pending: ScaleSample? = null
        private var pendingTs: Int? = null
        private val publishedAt = mutableSetOf<Long>()
        private var slotAcked = false
        private var setupFinished = false
        private var profileSent = false
        private val freeSlots = mutableSetOf<Int>()
        private val slotNames = mutableMapOf<Int, String>()
        private var pairing = false

        private fun cmd(op: Int, payload: ByteArray = byteArrayOf()) = writeTo(SERVICE, chrDownload, byteArrayOf(op.toByte()) + payload)

        override fun onConnected() {
            setNotifyOn(SERVICE, chrUpload)
            setNotifyOn(SERVICE, chrWeight)
            setNotifyOn(SERVICE, chrAppend)
            password = settingsGetString(KEY_PASSWORD)?.takeIf { it.length == 8 && Bytes.isHex(it) }?.let { Bytes.fromHex(it) }
        }

        override fun onDisconnected() = flushPending()

        override fun onNotification(characteristic: UUID, data: ByteArray) {
            when (characteristic) {
                chrUpload -> if (data.isNotEmpty()) onEvent(data)
                chrWeight -> onWeight(data)
                chrAppend -> onAppend(data)
            }
        }

        private fun onEvent(data: ByteArray) {
            when (Bytes.u8(data, 0)) {
                0xA0 -> if (data.size >= 5) {
                    val pw = data.copyOfRange(1, 5)
                    password = pw
                    settingsPutString(KEY_PASSWORD, Bytes.toHex(pw))
                    userInfo("Báscula emparejada")
                    pairing = true
                    cmd(0x21, Bytes.toInt32Le((SecureRandom().nextInt(Int.MAX_VALUE - 1) + 1).toLong()))
                    // The scale should close the link after setup; force it if it does not.
                    schedule(PAIRING_FALLBACK_MS) { requestDisconnect() }
                }
                0xA1 -> if (data.size >= 5) {
                    val pw = password
                    if (pw == null) {
                        userInfo("Báscula no emparejada: pon la báscula en modo emparejamiento")
                        requestDisconnect()
                        return
                    }
                    val random = data.copyOfRange(1, 5)
                    cmd(0x20, ByteArray(4) { (pw[it].toInt() xor random[it].toInt()).toByte() })
                    if (!pairing) {
                        cmd(0x02, Bytes.toInt32Le(javaTimeToDevice(env.wallClock()).toLong() and 0xFFFFFFFFL))
                        writeProfile(currentSlot())
                    }
                }
                0x83 -> onSlotStatus(data)
                0xC0 -> if (pairing) finishSetup()
            }
        }

        private fun currentSlot(): Int = settingsGetString(ConfigField.KEY_USER_SLOT)?.toIntOrNull()?.takeIf { it in 1..8 }
            ?: settingsGetInt(KEY_SLOT).takeIf { it in 1..8 } ?: 1

        private fun onSlotStatus(data: ByteArray) {
            if (data.size < 2) return
            val slot = Bytes.u8(data, 1)
            if (!transtekSlotOccupied(data)) freeSlots.add(slot)
            slotNames[slot] = transtekSlotName(data, NAME_LENGTH)
            if (slot != 8 || slotAcked) return
            slotAcked = true
            val chosen = transtekSlot(
                settingsGetString(ConfigField.KEY_USER_SLOT)?.toIntOrNull(), settingsGetInt(KEY_SLOT), user.name, NAME_LENGTH, slotNames, freeSlots,
            )
            if (chosen == null) {
                userInfo(TRANSTEK_FULL)
                requestDisconnect()
                return
            }
            settingsPutInt(KEY_SLOT, chosen)
            val name = ByteArray(NAME_LENGTH) { 0x20 }
            user.name.take(NAME_LENGTH).forEachIndexed { i, c -> if (c.code in 0x20..0x7E) name[i] = c.code.toByte() }
            cmd(0x03, byteArrayOf(chosen.toByte()) + name)
            writeProfile(chosen)
            cmd(0x02, Bytes.toInt32Le(javaTimeToDevice(env.wallClock()).toLong() and 0xFFFFFFFFL))
            finishSetup()
        }

        private fun finishSetup() {
            if (setupFinished) return
            setupFinished = true
            cmd(0x22)
        }

        private fun writeProfile(slot: Int) {
            if (profileSent) return
            profileSent = true
            val height = (user.heightCm.toInt().coerceIn(1, 204) * 10) or 0xD000
            cmd(0x51, byteArrayOf(0x17, slot.toByte(), if (user.isMale) 0x01 else 0x02, user.ageAt(env.wallClock()).toByte(), height.toByte(), (height shr 8).toByte(), 0x00))
        }

        private fun onWeight(data: ByteArray) {
            if (data.size < 5) return
            flushPending()
            val flags = Bytes.u8(data, 0)
            val weight = float32(data, 1)
            if (weight <= 0f) return
            var off = 5
            var ts: Int? = null
            if (flags and 0x01 != 0) {
                if (off + 4 > data.size) return
                ts = Bytes.fromSignedInt32Le(data, off)
                off += 4
            }
            if (flags and 0x02 != 0) off += 4
            var impedance: Float? = null
            if (flags and 0x04 != 0) {
                if (off + 4 > data.size) return
                impedance = float32(data, off)
                off += 4
            }
            if (flags and 0x08 != 0) off += 1
            val hasAppend = flags and 0x10 != 0 && off < data.size && Bytes.u8(data, off) and 0x10 != 0
            val m = ScaleSample().apply {
                dateTime = ts?.let { deviceTimeToJava(it) }
                weightKg = weight
                this.impedance = impedance?.takeIf { it > 0f }
            }
            if (hasAppend && ts != null) {
                pending = m
                pendingTs = ts
            } else {
                publishIfNew(m)
            }
        }

        private fun onAppend(data: ByteArray) {
            if (data.size < 5) return
            val m = pending ?: return
            val flags = Bytes.u8(data, 0)
            if (Bytes.fromSignedInt32Le(data, 1) != pendingTs) {
                flushPending()
                return
            }
            var off = 5
            fun next(): Float? {
                if (off + 2 > data.size) return null
                return sfloat(data, off).also { off += 2 }
            }
            if (flags and 0x01 != 0) off += 1
            if (flags and 0x02 != 0) {
                if (off + 2 > data.size) return
                m.bmrKcal = Bytes.fromUnsignedInt16Le(data, off).toFloat()
                off += 2
            }
            if (flags and 0x04 != 0) m.fatPercent = next() ?: return
            if (flags and 0x08 != 0) m.waterPercent = next() ?: return
            if (flags and 0x10 != 0) m.visceralFat = next() ?: return
            if (flags and 0x20 != 0) m.musclePercent = next() ?: return
            if (flags and 0x40 != 0) {
                val bonePct = next() ?: return
                val w = m.weightKg ?: 0f
                m.boneKg = if (w <= 0f) 0f else bonePct * w / 100f
            }
            pending = null
            pendingTs = null
            publishIfNew(m)
        }

        private fun flushPending() {
            pending?.let { publishIfNew(it) }
            pending = null
            pendingTs = null
        }

        private fun publishIfNew(m: ScaleSample) {
            val at = m.dateTime ?: 0L
            if (at != 0L && !publishedAt.add(at)) return
            publish(m)
        }
    }

    companion object {
        val SERVICE: UUID = UUID.fromString("0d005750-c36b-11e3-9c1a-0800200c9a66")
        private val NAME = Regex("^[01]0376B.*")
        private const val KEY_PASSWORD = "weightgurus_password"
        private const val KEY_SLOT = "weightgurus_slot"
        private const val NAME_LENGTH = 18
        private const val PAIRING_FALLBACK_MS = 15_000L
        private const val TS_OFFSET = 1262304000L

        private fun tzOffsetSeconds(ms: Long): Int = TimeZone.getDefault().getOffset(ms) / 1000

        /** Local time, seconds since 2010-01-01. */
        fun javaTimeToDevice(ms: Long): Int = (((ms + 500) / 1000) - TS_OFFSET + tzOffsetSeconds(ms)).toInt()

        fun deviceTimeToJava(seconds: Int): Long {
            val approx = 1000L * (TS_OFFSET + seconds.toLong())
            return approx - 1000L * tzOffsetSeconds(approx)
        }

        /** 24-bit LE mantissa with a signed decimal exponent. */
        fun float32(d: ByteArray, o: Int): Float =
            if (o + 4 > d.size) 0f else (Bytes.fromUnsignedInt24Le(d, o) * 10.0.pow(d[o + 3].toDouble())).toFloat()

        /** IEEE-11073 16-bit SFLOAT. */
        fun sfloat(d: ByteArray, o: Int): Float {
            val raw = Bytes.fromUnsignedInt16Le(d, o)
            var exponent = (raw and 0xF000) shr 12
            if (exponent >= 0x8) exponent -= 0x10
            var mantissa = raw and 0x0FFF
            if (mantissa >= 0x0800) mantissa = (raw and 0x07FF) - 0x0800
            return (mantissa * 10.0.pow(exponent.toDouble())).toFloat()
        }
    }
}

/**
 * PICOOC GATT scales ("PICOOC…" except Mini Lite, "LATIN-…"; FFF0/FFF1/FFF2): time and user
 * profile on request, live weight, the scale's composition (0x32), heart rate; published once the
 * frames settle. Missing values come from the standard impedance formulas.
 */
class PicoocDriver : GattScaleDriver() {
    override val id = "picooc_gatt"
    override val displayName = "PICOOC (Latin, S1, S3…)"
    override val brand = "PICOOC"
    override val capabilities = ScaleCapabilities(impedance = true, deviceComposition = true)
    override val formulaLib = FormulaLib.STANDARD_IMPEDANCE
    override val pacing = GattPacing.CONSERVATIVE

    override fun match(adv: AdvertisementInfo): MatchConfidence? {
        val n = adv.nameOrEmpty.lowercase(Locale.ROOT)
        if (n.startsWith("picooc-l")) return null
        return if (n.startsWith("picooc") || n.startsWith("latin-")) MatchConfidence.HIGH else null
    }

    override fun newProtocol(adv: AdvertisementInfo?): GattProtocol = Protocol()

    internal class Protocol : GattProtocol() {
        private val svc = uuid16(0xFFF0)
        private val chrNotify = uuid16(0xFFF1)
        private val chrWrite = uuid16(0xFFF2)
        private var prefix = PREFIX_LATIN
        private var weightKg = 0f
        private var impedance = 0.0
        private var composition: Composition? = null
        private var published = false
        private var profilePushed = false
        private var settle: Cancellable? = null

        private fun send(payload: ByteArray) = writeTo(svc, chrWrite, payload, withResponse = false)

        override fun onConnected() {
            setNotifyOn(svc, chrNotify)
            userInfo("Súbete a la báscula")
        }

        override fun onDisconnected() {
            if (!published && weightKg > 0f) publishSession()
            settle?.cancel()
        }

        override fun onNotification(characteristic: UUID, data: ByteArray) {
            if (characteristic != chrNotify || data.isEmpty()) return
            when (val op = Bytes.u8(data, 0)) {
                OP_TIME_REQUEST_ALT -> {
                    prefix = PREFIX_MODERN
                    sendTime(OP_HANDSHAKE)
                    pushProfileOnce()
                }
                OP_HANDSHAKE -> {
                    sendTime(OP_HANDSHAKE)
                    pushProfileOnce()
                }
                OP_TIME_SYNC -> sendTime(OP_TIME_SYNC)
                OP_USER_REQUEST -> {
                    send(ack(prefix, op))
                    sendProfile()
                }
                OP_LIVE_WEIGHT_ALT, OP_LIVE_WEIGHT -> {
                    if (op == OP_LIVE_WEIGHT_ALT) prefix = PREFIX_MODERN
                    send(ack(prefix, op))
                    val f = parseLive(data) ?: return
                    if (f.weightKg in MIN_WEIGHT..MAX_WEIGHT) {
                        weightKg = f.weightKg
                        live(f.weightKg)
                    }
                    if (f.impedance > 0.0) impedance = f.impedance
                    if (f.complete) armSettle()
                }
                OP_BODY_COMPOSITION -> {
                    send(ack(prefix, op))
                    val c = parseComposition(data) ?: return
                    composition = c
                    if (c.weightKg in MIN_WEIGHT..MAX_WEIGHT) weightKg = c.weightKg
                    if (c.impedance > 0.0) impedance = c.impedance
                    armSettle()
                }
                OP_HEART_RATE -> {
                    send(ack(prefix, op))
                    armSettle()
                }
                OP_LOW_BATTERY -> userInfo("Batería de la báscula baja")
                OP_HISTORY, OP_HISTORY_DONE, OP_MULTI_FREQ_BIA, OP_BALANCE_TEST -> send(ack(prefix, op))
            }
        }

        private fun sendTime(replyOp: Int) = send(timeReply(prefix, replyOp, env.wallClock() / 1000L, if (user.unit == ScaleUnit.KG) 0 else 2))

        private fun pushProfileOnce() {
            if (profilePushed) return
            profilePushed = true
            sendProfile()
        }

        private fun sendProfile() {
            val profile = userProfile(user.isMale, user.heightCm, user.ageAt(env.wallClock()))
            schedule(PROFILE_DELAY_MS) { send(profile) }
        }

        private fun armSettle() {
            if (published || weightKg <= 0f) return
            settle?.cancel()
            settle = schedule(SETTLE_MS) { publishSession() }
        }

        private fun publishSession() {
            if (published || weightKg <= 0f) return
            published = true
            settle?.cancel()
            val c = composition
            val ohm = impedance
            publish(ScaleSample().apply {
                weightKg = this@Protocol.weightKg
                if (ohm in 100.0..1500.0) {
                    impedance = ohm.toFloat()
                    impedanceLib = FormulaLib.STANDARD_IMPEDANCE
                }
                if (c != null && c.fatPercent in 3f..75f) {
                    fatPercent = c.fatPercent
                    waterPercent = c.waterPercent.coerceIn(0f, 80f)
                    boneKg = c.boneMassKg.coerceIn(0f, 10f)
                    visceralFat = c.visceralFat.toFloat()
                    lbmKg = this@Protocol.weightKg * (1f - c.fatPercent / 100f)
                }
                c?.bodyAge?.takeIf { it in 10..99 }?.let { metabolicAge = it.toFloat() }
            })
        }
    }

    data class LiveFrame(val weightKg: Float, val impedance: Double, val complete: Boolean)

    data class Composition(val weightKg: Float, val fatPercent: Float, val waterPercent: Float, val boneMassKg: Float, val visceralFat: Int, val bodyAge: Int, val impedance: Double)

    companion object {
        const val PREFIX_LATIN = 0xF1
        const val PREFIX_MODERN = 0xA1
        const val OP_USER_REQUEST = 0x30
        const val OP_USER_PROFILE = 0x31
        const val OP_BODY_COMPOSITION = 0x32
        const val OP_TIME_SYNC = 0x35
        const val OP_HISTORY = 0x36
        const val OP_HISTORY_DONE = 0x37
        const val OP_LIVE_WEIGHT = 0x39
        const val OP_HANDSHAKE = 0x3A
        const val OP_LOW_BATTERY = 0x3B
        const val OP_HEART_RATE = 0x3C
        const val OP_MULTI_FREQ_BIA = 0x3E
        const val OP_BALANCE_TEST = 0x3F
        const val OP_TIME_REQUEST_ALT = 0x51
        const val OP_LIVE_WEIGHT_ALT = 0x52
        private const val PROFILE_DELAY_MS = 100L
        private const val SETTLE_MS = 8_000L
        private const val MIN_WEIGHT = 2.0f
        private const val MAX_WEIGHT = 250.0f

        fun ack(prefix: Int, op: Int): ByteArray = byteArrayOf(prefix.toByte(), 0x03, op.toByte())

        fun timeReply(prefix: Int, op: Int, epochSeconds: Long, unit: Int): ByteArray =
            byteArrayOf(prefix.toByte(), 0x0A, op.toByte()) + Bytes.toInt32Be(epochSeconds and 0xFFFFFFFFL) + byteArrayOf(0x00, unit.toByte(), 0x00)

        fun encodeHeight(heightCm: Float): Int = if (!heightCm.isFinite()) 0 else ((heightCm * 10f - 1000f) / 5f).roundToInt().coerceIn(0, 255)

        fun userProfile(male: Boolean, heightCm: Float, age: Int): ByteArray =
            byteArrayOf(OP_USER_PROFILE.toByte(), 0x06, 0x01, if (male) 0x01 else 0x02, encodeHeight(heightCm).toByte(), age.coerceIn(18, 255).toByte())

        fun parseLive(d: ByteArray): LiveFrame? =
            if (d.size < 16) null else LiveFrame(Bytes.fromUnsignedInt16Be(d, 6) / 20.0f, Bytes.fromUnsignedInt16Be(d, 8) / 10.0, Bytes.u8(d, 15) == 0)

        fun parseComposition(d: ByteArray): Composition? = if (d.size < 20) {
            null
        } else {
            Composition(
                weightKg = Bytes.fromUnsignedInt16Be(d, 2) / 20.0f, fatPercent = Bytes.fromUnsignedInt16Be(d, 4) / 10.0f,
                waterPercent = Bytes.fromUnsignedInt16Be(d, 6) / 10.0f, boneMassKg = Bytes.fromUnsignedInt16Be(d, 10) / 20.0f,
                visceralFat = Bytes.u8(d, 14), bodyAge = Bytes.u8(d, 15), impedance = Bytes.fromUnsignedInt16Be(d, 18).toDouble(),
            )
        }
    }
}

/**
 * Omron body-composition monitors with the WLP link (HBF-702T, KRD-703T, HBF-222T/227T/228T/230T,
 * BCM-500, VIVA): pairing key, EEPROM read of the configured user slot's 30 records.
 */
class OmronWlcDriver : GattScaleDriver() {
    override val id = "omron_wlp"
    override val displayName = "Omron HBF-702T / 22xT / BCM-500 / VIVA"
    override val brand = "Omron"
    override val capabilities = ScaleCapabilities(deviceComposition = true, history = true, config = listOf(ConfigField.userSlot(4)))
    override val formulaLib: FormulaLib? = null
    override val pacing = GattPacing.CONSERVATIVE
    override val keywords = listOf("HBF-702T", "KRD-703T", "HBF-227T", "HBF-228T", "HBF-230T", "HBF-222T", "BCM-500", "VIVA", "blesmart")

    override fun match(adv: AdvertisementInfo): MatchConfidence? = if (modelFor(adv.nameOrEmpty) != null) MatchConfidence.HIGH else null

    override fun newProtocol(adv: AdvertisementInfo?): GattProtocol = Protocol(adv?.nameOrEmpty)

    internal class Protocol(private val advertisedName: String?) : GattProtocol() {
        private enum class Phase { IDLE, UNLOCKING, ENTERING_KEY_PROGRAMMING, WRITING_KEY, STARTING_SESSION, READING_SLOT, ENDING_SESSION, FINISHED }

        private var phase = Phase.IDLE
        private var profile = OmronBodyCompositionLib.PROFILE_HBF_702T
        private val rx = arrayOfNulls<ByteArray>(CHR_RX.size)
        private var readAddress = 0
        private var readLength = 0
        private var readCursor = 0
        private var readBuffer = ByteArray(0)
        private var slot = 0
        private var pairingKey: ByteArray? = null
        private var watchdog: Cancellable? = null

        override fun onConnected() {
            val resolved = modelFor(advertisedName.orEmpty())
            if (resolved == null || !hasCharacteristic(SERVICE, CHR_UNLOCK)) {
                userInfo("Modelo Omron no reconocido")
                requestDisconnect()
                return
            }
            profile = resolved
            slot = ((settingsGetString(ConfigField.KEY_USER_SLOT)?.toIntOrNull() ?: 1) - 1).coerceIn(0, profile.userSlotCount - 1)
            setNotifyOn(SERVICE, CHR_UNLOCK)
            CHR_RX.forEach { setNotifyOn(SERVICE, it) }
            pairingKey = settingsGetString(KEY_PAIRING)?.takeIf { it.length == OmronWlpFrame.KEY_SIZE * 2 && Bytes.isHex(it) }?.let { Bytes.fromHex(it) }
            val key = pairingKey
            if (key == null) {
                userInfo("Mantén pulsado el botón de emparejamiento de la Omron")
                phase = Phase.ENTERING_KEY_PROGRAMMING
                unlockChannel(OmronWlpFrame.enterKeyProgramming())
            } else {
                phase = Phase.UNLOCKING
                unlockChannel(OmronWlpFrame.unlock(key))
            }
        }

        override fun onDisconnected() {
            watchdog?.cancel()
        }

        override fun onNotification(characteristic: UUID, data: ByteArray) {
            armWatchdog()
            if (characteristic == CHR_UNLOCK) {
                onUnlockResponse(data)
                return
            }
            val channel = CHR_RX.indexOf(characteristic)
            if (channel < 0) return
            rx[channel] = data
            val frame = assemble() ?: return
            val response = OmronWlpFrame.parseResponse(frame) ?: return
            onResponse(response)
        }

        private fun onUnlockResponse(data: ByteArray) {
            if (data.size < 2) return
            val ack = Bytes.fromUnsignedInt16Be(data, 0)
            when (phase) {
                Phase.UNLOCKING -> if (ack == OmronWlpFrame.ACK_UNLOCKED) {
                    openSession()
                } else {
                    userInfo("Mantén pulsado el botón de emparejamiento de la Omron")
                    pairingKey = null
                    phase = Phase.ENTERING_KEY_PROGRAMMING
                    unlockChannel(OmronWlpFrame.enterKeyProgramming())
                }
                Phase.ENTERING_KEY_PROGRAMMING -> if (ack == OmronWlpFrame.ACK_KEY_PROGRAMMING) {
                    val key = ByteArray(OmronWlpFrame.KEY_SIZE).also { SecureRandom().nextBytes(it) }
                    pairingKey = key
                    phase = Phase.WRITING_KEY
                    unlockChannel(OmronWlpFrame.writeKey(key))
                } else {
                    fail("La Omron no está en modo emparejamiento")
                }
                Phase.WRITING_KEY -> if (ack == OmronWlpFrame.ACK_KEY_WRITTEN) {
                    pairingKey?.let { settingsPutString(KEY_PAIRING, Bytes.toHex(it)) }
                    userInfo("Omron emparejada")
                    openSession()
                } else {
                    fail("No se pudo emparejar la Omron")
                }
                else -> Unit
            }
        }

        private fun openSession() {
            phase = Phase.STARTING_SESSION
            command(OmronWlpFrame.startTransmission())
        }

        private fun onResponse(r: OmronWlpFrame.Response) {
            when (phase) {
                Phase.STARTING_SESSION -> if (r.type != OmronWlpFrame.TYPE_START) {
                    fail("La Omron rechazó la sesión")
                } else {
                    phase = Phase.READING_SLOT
                    readEeprom(profile.recordAddress(slot, 0), profile.recordSize * profile.recordsPerSlot)
                }
                Phase.READING_SLOT -> if (r.type != OmronWlpFrame.TYPE_READ) fail("Error leyendo la memoria de la Omron") else consumeChunk(r.data)
                Phase.ENDING_SESSION -> {
                    phase = Phase.FINISHED
                    watchdog?.cancel()
                    requestDisconnect()
                }
                else -> Unit
            }
        }

        private fun readEeprom(address: Int, length: Int) {
            readAddress = address
            readLength = length
            readCursor = 0
            readBuffer = ByteArray(0)
            nextChunk()
        }

        private fun nextChunk() {
            if (readCursor >= readLength) {
                publishRecords(readBuffer)
                phase = Phase.ENDING_SESSION
                command(OmronWlpFrame.endTransmission())
                return
            }
            command(OmronWlpFrame.readEeprom(readAddress + readCursor, minOf(OmronWlpFrame.MAX_READ_BLOCK, readLength - readCursor)))
        }

        private fun consumeChunk(data: ByteArray) {
            val expected = minOf(OmronWlpFrame.MAX_READ_BLOCK, readLength - readCursor)
            if (expected <= 0) return
            val accepted = minOf(expected, data.size)
            readBuffer += data.copyOf(accepted) + ByteArray(expected - accepted) { 0xFF.toByte() }
            readCursor += expected
            nextChunk()
        }

        private fun publishRecords(raw: ByteArray) {
            val lastKey = "$KEY_LAST_PREFIX$slot"
            val known = settingsGetString(lastKey)?.toLongOrNull() ?: 0L
            val fresh = raw.toList().chunked(profile.recordSize).mapNotNull { OmronBodyCompositionLib.decodeRecord(it.toByteArray(), profile) }
                .filter { it.timestampMillis > known }.sortedBy { it.timestampMillis }
            fresh.forEach { r ->
                publish(ScaleSample().apply {
                    dateTime = r.timestampMillis
                    weightKg = r.weightKg
                    fatPercent = r.bodyFatPercent
                    musclePercent = r.skeletalMusclePercent
                    visceralFat = r.visceralFatLevel
                    bmrKcal = r.bmrKcal?.toFloat()
                    metabolicAge = r.bodyAgeYears?.takeIf { it in 10..99 }?.toFloat()
                    fromHistory = true
                })
            }
            if (fresh.isEmpty()) {
                userInfo("No hay mediciones nuevas en la Omron")
            } else {
                settingsPutString(lastKey, fresh.last().timestampMillis.toString())
                userInfo("Mediciones leídas de la Omron: ${fresh.size}")
            }
        }

        private fun command(cmd: ByteArray) {
            armWatchdog()
            OmronWlpFrame.toChannelChunks(cmd).forEachIndexed { i, chunk -> if (i < CHR_TX.size) writeTo(SERVICE, CHR_TX[i], chunk) }
        }

        private fun unlockChannel(payload: ByteArray) {
            armWatchdog()
            writeTo(SERVICE, CHR_UNLOCK, payload)
        }

        private fun assemble(): ByteArray? {
            val first = rx[0]?.takeIf { it.isNotEmpty() } ?: return null
            val length = Bytes.u8(first, 0)
            val needed = OmronWlpFrame.channelsForFrame(length)
            if (needed > rx.size) {
                rx.fill(null)
                return null
            }
            if ((0 until needed).any { rx[it] == null }) return null
            var frame = ByteArray(0)
            for (i in 0 until needed) frame += rx[i]!!
            rx.fill(null)
            return if (frame.size >= length) frame.copyOf(length) else null
        }

        private fun armWatchdog() {
            watchdog?.cancel()
            watchdog = schedule(RESPONSE_TIMEOUT_MS) {
                if (phase != Phase.FINISHED) {
                    userInfo("La Omron dejó de responder")
                    requestDisconnect()
                }
            }
        }

        private fun fail(message: String) {
            userInfo(message)
            phase = Phase.FINISHED
            watchdog?.cancel()
            requestDisconnect()
        }
    }

    companion object {
        val SERVICE: UUID = UUID.fromString("ecbe3980-c9a2-11e1-b1bd-0002a5d5c51b")
        val CHR_UNLOCK: UUID = UUID.fromString("b305b680-aee7-11e1-a730-0002a5d5c51b")
        val CHR_TX: List<UUID> = listOf(
            "db5b55e0-aee7-11e1-965e-0002a5d5c51b", "e0b8a060-aee7-11e1-92f4-0002a5d5c51b",
            "0ae12b00-aee8-11e1-a192-0002a5d5c51b", "10e1ba60-aee8-11e1-89e5-0002a5d5c51b",
        ).map(UUID::fromString)
        val CHR_RX: List<UUID> = listOf(
            "49123040-aee8-11e1-a74d-0002a5d5c51b", "4d0bf320-aee8-11e1-a0d9-0002a5d5c51b",
            "5128ce60-aee8-11e1-b84b-0002a5d5c51b", "560f1420-aee8-11e1-8184-0002a5d5c51b",
        ).map(UUID::fromString)
        private const val KEY_PAIRING = "omron_pairing_key"
        private const val KEY_LAST_PREFIX = "omron_last_record_"
        private const val RESPONSE_TIMEOUT_MS = 8_000L

        private val BY_ADVERTISED_ID: Map<Int, OmronBodyCompositionLib.Profile> = mapOf(
            0x0001_000C to OmronBodyCompositionLib.PROFILE_HBF_702T,
            0x0001_0011 to OmronBodyCompositionLib.PROFILE_HBF_702T,
            0x0001_040C to OmronBodyCompositionLib.PROFILE_HBF_702T,
            0x0001_0009 to OmronBodyCompositionLib.PROFILE_HBF_32,
            0x0001_000B to OmronBodyCompositionLib.PROFILE_HBF_32,
            0x0001_000D to OmronBodyCompositionLib.PROFILE_HBF_32,
            0x0001_0408 to OmronBodyCompositionLib.PROFILE_HBF_32,
            0x0001_0110 to OmronBodyCompositionLib.PROFILE_HBF_32_NO_BODY_AGE,
            0x0001_0208 to OmronBodyCompositionLib.PROFILE_HBF_32_NO_BODY_AGE,
        )

        private val BY_NAME: Map<String, OmronBodyCompositionLib.Profile> = mapOf(
            "hbf-702t" to OmronBodyCompositionLib.PROFILE_HBF_702T,
            "krd-703t" to OmronBodyCompositionLib.PROFILE_HBF_702T,
            "hbf-227t" to OmronBodyCompositionLib.PROFILE_HBF_32,
            "hbf-228t" to OmronBodyCompositionLib.PROFILE_HBF_32,
            "hbf-230t" to OmronBodyCompositionLib.PROFILE_HBF_32,
            "hbf-222t" to OmronBodyCompositionLib.PROFILE_HBF_32,
            "bcm-500" to OmronBodyCompositionLib.PROFILE_HBF_32_NO_BODY_AGE,
            "viva" to OmronBodyCompositionLib.PROFILE_HBF_32_NO_BODY_AGE,
        )

        /** Record layout for an advertised name (`BLEsmart_<8 hex model id>…` or the model name). */
        fun modelFor(deviceName: String): OmronBodyCompositionLib.Profile? {
            val name = deviceName.trim().lowercase(Locale.US)
            if (name.startsWith("blesmart_")) {
                val ids = name.removePrefix("blesmart_")
                return if (ids.length >= 8) BY_ADVERTISED_ID[ids.substring(0, 8).toIntOrNull(16)] else null
            }
            return BY_NAME[name]
        }
    }
}

/**
 * Keep S3 (service 00FF): eleven-step initialisation with a random ID24 token and the last
 * measurement as reference, live events, final record with 50/100 kHz impedances; composition
 * with the BestHealth dual-frequency model at capture time.
 */
class KeepS3Driver : GattScaleDriver() {
    override val id = "keep_s3"
    override val displayName = "Keep S3"
    override val brand = "Keep"
    override val capabilities = ScaleCapabilities(impedance = true, deviceComposition = true)
    override val formulaLib = FormulaLib.KEEP_S3

    override fun match(adv: AdvertisementInfo): MatchConfidence? =
        if (adv.nameOrEmpty.equals("Keep_S3", ignoreCase = true)) MatchConfidence.HIGH else null

    override fun newProtocol(adv: AdvertisementInfo?): GattProtocol = Protocol()

    internal class Protocol : GattProtocol() {
        private val svc = uuid16(0x00FF)
        private val chrNotify = uuid16(0xFF01)
        private val chrWrite = uuid16(0xFF02)
        private var step = 0
        private var token = ""
        private var published = false
        private var finishing = false
        private var latestImpedance = 0
        private var pendingFinal: KeepS3Protocol.MeasurementEvent? = null
        private var finalTimer: Cancellable? = null

        private fun request(op: Int, payload: ByteArray = byteArrayOf()) = writeTo(svc, chrWrite, KeepS3Protocol.buildRequest(op, payload))

        override fun onConnected() {
            token = settingsGetString(KEY_TOKEN)?.lowercase(Locale.ROOT)?.takeIf { KeepS3Protocol.validateToken(it) }
                ?: KeepS3Protocol.generateToken(ByteArray(12).also { SecureRandom().nextBytes(it) }).also { settingsPutString(KEY_TOKEN, it) }
            setNotifyOn(svc, chrNotify)
            step = 0
            sendStep(byteArrayOf())
        }

        override fun onDisconnected() {
            finalTimer?.cancel()
            publishPendingWithoutRecord()
        }

        private fun sendStep(responseData: ByteArray) {
            val op = KeepS3Protocol.INIT_SEQUENCE.getOrNull(step) ?: return
            val payload = when (op) {
                KeepS3Protocol.OP_SET_TIME -> if (responseData.size == 4) responseData else ByteArray(4).also { KeepS3Protocol.putU32(it, 0, env.wallClock() / 1000L) }
                KeepS3Protocol.OP_SET_UNIT -> byteArrayOf(0x00)
                KeepS3Protocol.OP_PROFILE -> KeepS3Protocol.buildProfilePayload(
                    token, KeepS3Protocol.parsePrevious(settingsGetString(KEY_PREVIOUS), user.lastWeightKg),
                    user.heightCm.roundToInt(), user.birthDate.year, user.birthDate.monthValue, user.birthDate.dayOfMonth,
                )
                KeepS3Protocol.OP_MEASUREMENT_CONTROL -> KeepS3Protocol.buildMeasurementControl(token, start = true)
                else -> byteArrayOf()
            }
            request(op, payload)
            if (op == KeepS3Protocol.OP_MEASUREMENT_CONTROL) userInfo("Súbete a la báscula")
        }

        override fun onNotification(characteristic: UUID, data: ByteArray) {
            if (characteristic != chrNotify) return
            val header = KeepS3Protocol.peekFrameHeader(data) ?: return
            when (header.type) {
                KeepS3Protocol.FRAME_RESPONSE -> {
                    val r = KeepS3Protocol.parseResponse(data) ?: return
                    if (KeepS3Protocol.INIT_SEQUENCE.getOrNull(step) != r.opcode) return
                    step++
                    sendStep(r.data)
                }
                KeepS3Protocol.FRAME_EVENT -> when (header.opcode) {
                    KeepS3Protocol.OP_MEASUREMENT_EVENT -> {
                        writeTo(svc, chrWrite, KeepS3Protocol.buildAck(header.opcode))
                        val e = KeepS3Protocol.parseMeasurementEvent(data) ?: return
                        if (e.impedanceOhm > 0) latestImpedance = e.impedanceOhm
                        if (e.stage == KeepS3Protocol.STAGE_FINAL) {
                            pendingFinal = e
                            if (finalTimer == null) finalTimer = schedule(FINAL_RECORD_WAIT_MS) { publishPendingWithoutRecord() }
                        } else if ((e.stage == 0 || e.stage == 1) && e.weightKg > 0f) {
                            live(e.weightKg)
                        }
                    }
                    KeepS3Protocol.OP_FINAL_RECORD -> {
                        writeTo(svc, chrWrite, KeepS3Protocol.buildAck(header.opcode))
                        val rec = KeepS3Protocol.parseFinalRecord(data) ?: return
                        finalTimer?.cancel()
                        val e = pendingFinal
                        publishOnce(e?.weightKg ?: rec.weightKg, e?.impedanceOhm ?: latestImpedance, rec.impedance50Ohm, rec.impedance100Ohm)
                        finishSession()
                    }
                }
            }
        }

        private fun publishPendingWithoutRecord() {
            val e = pendingFinal ?: return
            publishOnce(e.weightKg, e.impedanceOhm, null, null)
        }

        private fun publishOnce(weightKg: Float, deviceImpedance: Int, imp50: Int?, imp100: Int?) {
            if (published || !weightKg.isFinite() || weightKg <= 0f) return
            val now = env.wallClock()
            val sample = ScaleSample().apply {
                dateTime = now
                this.weightKg = weightKg
            }
            if (imp50 != null && imp100 != null) {
                sample.impedance = imp100.toFloat()
                sample.impedanceLow = imp50.toFloat()
                KeepS3BodyComposition.calculate(user.isMale, user.ageAt(now), user.heightCm.roundToInt(), weightKg, imp50, imp100)?.let { c ->
                    sample.fatPercent = c.bodyFatPercent
                    sample.waterPercent = c.waterPercent
                    sample.visceralFat = c.visceralFatLevel.toFloat()
                    sample.boneKg = c.boneKg
                    sample.lbmKg = c.fatFreeMassKg
                    sample.bmrKcal = c.basalMetabolicRateKcal.toFloat()
                    sample.proteinPercent = c.proteinPercent
                    sample.musclePercent = c.skeletalMusclePercent
                    sample.metabolicAge = c.bodyAge.takeIf { it in 10..99 }?.toFloat()
                    sample.computedWith = FormulaLib.KEEP_S3
                }
            }
            published = true
            publish(sample)
            KeepS3Protocol.serializePrevious(now / 1000L, weightKg, deviceImpedance)?.let { settingsPutString(KEY_PREVIOUS, it) }
        }

        private fun finishSession() {
            if (finishing) return
            finishing = true
            val stop = KeepS3Protocol.buildMeasurementControl(token, start = false)
            request(KeepS3Protocol.OP_MEASUREMENT_CONTROL, stop)
            request(KeepS3Protocol.OP_MEASUREMENT_CONTROL, stop)
            schedule(DISCONNECT_DELAY_MS) { requestDisconnect() }
        }
    }

    companion object {
        private const val KEY_TOKEN = "keep_s3_token"
        private const val KEY_PREVIOUS = "keep_s3_previous"
        private const val FINAL_RECORD_WAIT_MS = 2_000L
        private const val DISCONNECT_DELAY_MS = 6_000L
    }
}
