// Protocol ported from openScale (GPL-3.0), https://github.com/oliexdev/openScale
package com.enjoyyourtime.globalfitness.scale.drivers

import com.enjoyyourtime.globalfitness.scale.AdvertisementInfo
import com.enjoyyourtime.globalfitness.scale.Bytes
import com.enjoyyourtime.globalfitness.scale.Cancellable
import com.enjoyyourtime.globalfitness.scale.ConfigField
import com.enjoyyourtime.globalfitness.scale.DriverNameNote
import com.enjoyyourtime.globalfitness.scale.GENERIC_BRAND
import com.enjoyyourtime.globalfitness.scale.GattProtocol
import com.enjoyyourtime.globalfitness.scale.GattScaleDriver
import com.enjoyyourtime.globalfitness.scale.MatchConfidence
import com.enjoyyourtime.globalfitness.scale.ScaleCapabilities
import com.enjoyyourtime.globalfitness.scale.ScaleNotice
import com.enjoyyourtime.globalfitness.scale.ScaleSample
import com.enjoyyourtime.globalfitness.scale.uuid16
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.Locale
import java.util.UUID
import kotlin.random.Random

/**
 * Bluetooth SIG Weight Scale / Body Composition / User Data profiles (services 181D, 181B, 181C).
 *
 * User handling is non-interactive (single-profile app): a learned or configured scale user index
 * and consent PIN are used; without them the app registers a new user on the scale (the scale
 * picks the first free slot), stores index + PIN and writes date of birth, sex and height.
 */
open class StandardWeightProfileProtocol : GattProtocol() {
    // Standard services / characteristics.
    protected val svcDeviceInfo = uuid16(0x180A)
    protected val chrManufacturer = uuid16(0x2A29)
    protected val chrModel = uuid16(0x2A24)
    protected val svcCurrentTime = uuid16(0x1805)
    protected val chrCurrentTime = uuid16(0x2A2B)
    protected val svcWeightScale = uuid16(0x181D)
    protected val chrWeightMeasurement = uuid16(0x2A9D)
    protected val svcBodyComposition = uuid16(0x181B)
    protected val chrBodyCompositionMeasurement = uuid16(0x2A9C)
    protected val svcUserData = uuid16(0x181C)
    protected val chrChangeIncrement = uuid16(0x2A99)
    protected val chrUserControlPoint = uuid16(0x2A9F)
    protected val chrDateOfBirth = uuid16(0x2A85)
    protected val chrGender = uuid16(0x2A8C)
    protected val chrHeight = uuid16(0x2A8E)
    protected val svcBattery = uuid16(0x180F)
    protected val chrBattery = uuid16(0x2A19)

    private var pending: ScaleSample? = null
    private var pendingKinds = HashSet<Kind>()
    private var flushTimer: Cancellable? = null
    private var pendingWaterMassKg: Float? = null
    private var newUserPending = false
    private var pendingConsent: Int? = null

    /** The last body composition packet announced more packets of the same measurement. */
    private var multiPacket = false
    private var continuation = false

    private enum class Kind { WEIGHT, BODY_COMPOSITION }

    /** Hook for vendors that need a "take measurement" command once the user is authorized. */
    protected open fun onRequestMeasurement() = Unit

    /** Hook to adjust a measurement right before it is published. */
    protected open fun transformBeforePublish(m: ScaleSample): ScaleSample = m

    override fun onConnected() {
        setNotifyOn(svcWeightScale, chrWeightMeasurement)
        setNotifyOn(svcBodyComposition, chrBodyCompositionMeasurement)
        setNotifyOn(svcUserData, chrChangeIncrement)
        setNotifyOn(svcUserData, chrUserControlPoint)
        setNotifyOn(svcBattery, chrBattery)
        writeTo(svcCurrentTime, chrCurrentTime, currentTimePayload(env.wallClock()))
        readFrom(svcBattery, chrBattery)

        val index = knownIndex()
        val consent = knownConsent()
        when {
            index != null && consent != null -> sendConsent(index, consent)
            index != null -> requestScaleUserConsent(index)
            else -> registerNewUser()
        }
        userInfo(ScaleNotice.Kind.STEP_ON)
    }

    override fun onNotification(characteristic: UUID, data: ByteArray) {
        when (characteristic) {
            chrWeightMeasurement -> parseWeight(data)?.let { handleNew(it, Kind.WEIGHT) }
            chrBodyCompositionMeasurement -> parseBodyComposition(data)?.let { handleNew(it, Kind.BODY_COMPOSITION) }
            chrUserControlPoint -> handleControlPoint(data)
            chrBattery -> if (data.isNotEmpty() && (data[0].toInt() and 0xFF) <= 10) userInfo(ScaleNotice.Kind.LOW_BATTERY)
            else -> Unit
        }
    }

    override fun onDisconnected() {
        flushPending()
    }

    // ---- User index / consent persistence ----------------------------------------------------

    /**
     * The user's index on the scale: the configured one wins; a learned index of another slot is
     * forgotten (with its PIN) as soon as the user configures a different slot.
     */
    protected fun knownIndex(): Int? {
        val configured = env.store.getString(ConfigField.KEY_USER_SLOT)?.toIntOrNull()?.takeIf { it in 0..254 }
        val learned = env.store.getInt(KEY_LEARNED_INDEX, -1).takeIf { it in 0..254 }
        if (configured != null) {
            if (learned != null && learned != configured) {
                env.store.remove(KEY_LEARNED_INDEX)
                env.store.remove(KEY_LEARNED_CONSENT)
            }
            return configured
        }
        return learned
    }

    protected fun knownConsent(): Int? =
        env.store.getString(ConfigField.KEY_PIN)?.toIntOrNull()
            ?: env.store.getInt(KEY_LEARNED_CONSENT, -1).takeIf { it >= 0 }

    private fun saveMapping(index: Int, consent: Int?) {
        env.store.putInt(KEY_LEARNED_INDEX, index)
        if (consent != null) env.store.putInt(KEY_LEARNED_CONSENT, consent)
    }

    // ---- Measurements -------------------------------------------------------------------------

    private fun handleNew(m: ScaleSample, kind: Kind) {
        val prev = pending
        // A body composition split in several packets keeps filling the same measurement.
        val continues = kind == Kind.BODY_COMPOSITION && continuation
        continuation = kind == Kind.BODY_COMPOSITION && multiPacket
        if (prev == null) {
            pending = m
            pendingKinds = hashSetOf(kind)
        } else {
            val sameTime = prev.dateTime == null || m.dateTime == null || prev.dateTime == m.dateTime
            if (continues && sameTime) {
                prev.mergeWith(m)
                if (!continuation && pendingKinds.size == Kind.entries.size && prev.hasWeight()) {
                    flushPending()
                    return
                }
            } else if (kind in pendingKinds || !sameTime) {
                // A different weighing (e.g. history records): publish the previous one first.
                flushPending()
                pending = m
                pendingKinds = hashSetOf(kind)
            } else {
                prev.mergeWith(m)
                pendingKinds += kind
                if (!continuation && pendingKinds.size == Kind.entries.size && prev.hasWeight()) {
                    flushPending()
                    return
                }
            }
        }
        flushTimer?.cancel()
        flushTimer = schedule(FLUSH_DELAY_MS) { flushPending() }
    }

    private fun flushPending() {
        flushTimer?.cancel()
        flushTimer = null
        val m = pending ?: return
        pending = null
        pendingKinds = HashSet()
        val water = pendingWaterMassKg
        pendingWaterMassKg = null
        val w = m.weightKg
        if (water != null && m.waterPercent == null && w != null && w > 0f) m.waterPercent = water / w * 100f
        if (m.hasWeight()) {
            publish(transformBeforePublish(m))
            finishSoon(5_000L) // history records keep the session alive
        }
    }

    /** True when the scale attributed the record to another user index than ours. */
    private fun foreignUser(index: Int): Boolean {
        if (index == 0xFF) return false
        val mine = knownIndex() ?: return false
        return index != mine
    }

    protected open fun parseWeight(value: ByteArray): ScaleSample? {
        if (value.size < 3) return null
        var o = 0
        val flags = Bytes.u8(value, o); o += 1
        val isKg = flags and 0x01 == 0
        val tsPresent = flags and 0x02 != 0
        val userPresent = flags and 0x04 != 0
        val m = ScaleSample()
        val raw = Bytes.fromUnsignedInt16Le(value, o); o += 2
        if (raw == SIG_UNSUCCESSFUL) return null // "measurement unsuccessful"
        m.weightKg = if (isKg) raw * 0.005f else raw * 0.01f * LB_TO_KG
        if (tsPresent && value.size >= o + 7) {
            m.dateTime = sigDateTime(value, o)
            // The clock is set on connect, so stamped records may be stored ones: keep their time.
            m.fromHistory = true
            o += 7
        }
        if (userPresent && value.size > o) {
            val index = Bytes.u8(value, o)
            if (foreignUser(index)) return null
            m.userId = index
        }
        return m
    }

    protected open fun parseBodyComposition(value: ByteArray): ScaleSample? {
        if (value.size < 4) return null
        var o = 0
        val flags = Bytes.fromUnsignedInt16Le(value, o); o += 2
        val isKg = flags and 0x0001 == 0
        val massMultiplier = if (isKg) 0.005f else 0.01f * LB_TO_KG
        val m = ScaleSample()
        val fatRaw = Bytes.fromUnsignedInt16Le(value, o); o += 2
        if (fatRaw != SIG_UNSUCCESSFUL) m.fatPercent = fatRaw * 0.1f
        fun has(bit: Int) = flags and bit != 0
        // 0xFFFF in any field means "not measured".
        fun u16(): Int? = if (value.size >= o + 2) Bytes.fromUnsignedInt16Le(value, o).also { o += 2 }.takeIf { it != SIG_UNSUCCESSFUL } else null
        multiPacket = has(MULTIPLE_PACKETS)
        if (has(0x0002) && value.size >= o + 7) {
            m.dateTime = sigDateTime(value, o)
            m.fromHistory = true
            o += 7
        }
        if (has(0x0004) && value.size > o) {
            val index = Bytes.u8(value, o); o += 1
            if (foreignUser(index)) return null
            m.userId = index
        }
        if (has(0x0008)) u16()?.let { kj -> m.bmrKcal = kj / 4.1868f } // energy in kJ
        if (has(0x0010)) u16()?.let { m.musclePercent = it * 0.1f }
        if (has(0x0020)) u16()?.let { m.muscleKg = it * massMultiplier }
        var ffm: Float? = null
        if (has(0x0040)) u16()?.let { ffm = it * massMultiplier }
        var softLean: Float? = null
        if (has(0x0080)) u16()?.let { softLean = it * massMultiplier }
        var waterMass: Float? = null
        if (has(0x0100)) u16()?.let { waterMass = it * massMultiplier }
        if (has(0x0200)) u16()?.let { if (it > 0) m.impedance = it * 0.1f }
        if (has(0x0400)) u16()?.let { m.weightKg = it * massMultiplier }
        val w = m.weightKg ?: pending?.weightKg
        val fat = m.fatPercent
        if (w != null && w > 0f) {
            waterMass?.let { m.waterPercent = it / w * 100f }
            val lbm = ffm ?: fat?.let { w - w * it / 100f }
            if (lbm != null) m.lbmKg = lbm
            val sl = softLean
            if (sl != null && lbm != null && lbm - sl > 0f) m.boneKg = lbm - sl
        } else if (waterMass != null) {
            // Weight comes in another packet: convert the water mass when publishing.
            pendingWaterMassKg = waterMass
        }
        return m
    }

    // ---- User Data Service control point ----------------------------------------------------

    private fun handleControlPoint(value: ByteArray) {
        if (value.size < 3 || Bytes.u8(value, 0) != UCP_RESPONSE) return
        val request = Bytes.u8(value, 1)
        val result = Bytes.u8(value, 2)
        when (request) {
            UCP_REGISTER_NEW_USER -> {
                if (result == UCP_SUCCESS && value.size >= 4) {
                    val index = Bytes.u8(value, 3)
                    val consent = pendingConsent ?: randomConsent()
                    saveMapping(index, consent)
                    newUserPending = true
                    pendingConsent = null
                    userInfo(ScaleNotice.Kind.USER_REGISTERED, index, consent)
                    sendConsent(index, consent)
                } else if (result == UCP_OPERATION_FAILED) {
                    userInfo(ScaleNotice.Kind.NO_FREE_USERS_SET_PIN)
                } else {
                    userInfo(ScaleNotice.Kind.USER_REGISTRATION_REFUSED, result)
                }
            }
            UCP_CONSENT -> when (result) {
                UCP_SUCCESS -> {
                    if (newUserPending) {
                        newUserPending = false
                        writeUserDataToScale()
                        userInfo(ScaleNotice.Kind.STEP_ON_FOR_REFERENCE)
                    }
                    onRequestMeasurement()
                }
                UCP_NOT_AUTHORIZED -> {
                    env.store.remove(KEY_LEARNED_CONSENT)
                    userInfo(ScaleNotice.Kind.PIN_REJECTED)
                    knownIndex()?.let { requestScaleUserConsent(it) }
                }
                else -> Unit
            }
            else -> Unit
        }
    }

    /** No PIN known for [index]: ask the user (vendors may show the PIN on the scale display). */
    protected open fun requestScaleUserConsent(index: Int) {
        userInfo(ScaleNotice.Kind.PIN_MISSING, index)
    }

    private fun registerNewUser() {
        val consent = randomConsent()
        pendingConsent = consent
        writeTo(svcUserData, chrUserControlPoint, byteArrayOf(UCP_REGISTER_NEW_USER.toByte(), (consent and 0xFF).toByte(), ((consent shr 8) and 0xFF).toByte()))
    }

    protected fun sendConsent(index: Int, consent: Int) {
        writeTo(
            svcUserData,
            chrUserControlPoint,
            byteArrayOf(UCP_CONSENT.toByte(), (index and 0xFF).toByte(), (consent and 0xFF).toByte(), ((consent shr 8) and 0xFF).toByte()),
        )
    }

    /** Writes date of birth, sex and height of the app user (and bumps the change increment). */
    protected open fun writeUserDataToScale() {
        val dob: LocalDate = user.birthDate
        writeTo(svcUserData, chrDateOfBirth, Bytes.toInt16Le(dob.year) + byteArrayOf(dob.monthValue.toByte(), dob.dayOfMonth.toByte()))
        writeTo(svcUserData, chrGender, byteArrayOf(if (user.isMale) 0 else 1))
        writeTo(svcUserData, chrHeight, Bytes.toInt16Le(user.heightCm.toInt().coerceIn(0, 300)))
        writeTo(svcUserData, chrChangeIncrement, byteArrayOf(0x01, 0x00, 0x00, 0x00))
    }

    private fun randomConsent(): Int = Random.nextInt(0, 10_000)

    companion object {
        const val KEY_LEARNED_INDEX = "uds_index"
        const val KEY_LEARNED_CONSENT = "uds_consent"
        private const val SIG_UNSUCCESSFUL = 0xFFFF
        private const val MULTIPLE_PACKETS = 0x1000
        private const val UCP_REGISTER_NEW_USER = 0x01
        private const val UCP_CONSENT = 0x02
        private const val UCP_RESPONSE = 0x20
        private const val UCP_SUCCESS = 0x01
        private const val UCP_OPERATION_FAILED = 0x04
        private const val UCP_NOT_AUTHORIZED = 0x05
        private const val FLUSH_DELAY_MS = 3_000L
        internal const val LB_TO_KG = 0.45359237f

        /** SIG "Date Time": year LE, month, day, hours, minutes, seconds. */
        fun sigDateTime(b: ByteArray, o: Int): Long? = runCatching {
            LocalDateTime.of(Bytes.fromUnsignedInt16Le(b, o), Bytes.u8(b, o + 2), Bytes.u8(b, o + 3), Bytes.u8(b, o + 4), Bytes.u8(b, o + 5), Bytes.u8(b, o + 6))
                .atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        }.getOrNull()

        /** SIG "Current Time": Date Time + day of week (1 = Monday) + fractions + adjust reason. */
        fun currentTimePayload(nowMillis: Long): ByteArray {
            val t = java.time.Instant.ofEpochMilli(nowMillis).atZone(ZoneId.systemDefault())
            return Bytes.toInt16Le(t.year) + byteArrayOf(
                t.monthValue.toByte(), t.dayOfMonth.toByte(), t.hour.toByte(), t.minute.toByte(), t.second.toByte(),
                t.dayOfWeek.value.toByte(), 0x00, 0x00,
            )
        }
    }
}

private val STANDARD_CONFIG = listOf(ConfigField.userSlot(maxSlot = 254, minSlot = 0), ConfigField.pin())

/** Any scale exposing the SIG Weight Scale / Body Composition / User Data services. */
class StandardWeightProfileDriver : GattScaleDriver() {
    override val id = "sig_weight_scale"
    override val displayName = "Weight Scale"
    override val nameNote = DriverNameNote.STANDARD_PROFILE
    override val brand = GENERIC_BRAND
    override val capabilities = ScaleCapabilities(deviceComposition = true, userRegistration = true, history = true, config = STANDARD_CONFIG)
    override val keywords = listOf("SIG", "181D", "181B", "Weight Scale Profile", "Body Composition Service", "standard", "estándar", "Bluetooth")

    override fun match(adv: AdvertisementInfo): MatchConfidence? =
        if (adv.serviceUuids.any { it == SVC_WEIGHT || it == SVC_BODY_COMP || it == SVC_USER_DATA }) MatchConfidence.MEDIUM else null

    override fun newProtocol(adv: AdvertisementInfo?): GattProtocol = StandardWeightProfileProtocol()

    companion object {
        val SVC_WEIGHT: UUID = uuid16(0x181D)
        val SVC_BODY_COMP: UUID = uuid16(0x181B)
        val SVC_USER_DATA: UUID = uuid16(0x181C)
    }
}

/** A&D UC-352BLE: plain SIG weight scale. */
class AndUC352Driver : GattScaleDriver() {
    override val id = "and_uc352ble"
    override val displayName = "A&D UC-352BLE"
    override val brand = "A&D"
    override val capabilities = ScaleCapabilities(userRegistration = true, history = true, config = STANDARD_CONFIG)
    override val keywords = listOf("UC-352", "AND")

    override fun match(adv: AdvertisementInfo): MatchConfidence? =
        if (adv.upperName.startsWith("A&D_UC-352BLE_")) MatchConfidence.HIGH else null

    override fun newProtocol(adv: AdvertisementInfo?): GattProtocol = StandardWeightProfileProtocol()
}

/** Beurer BF450: SIG profiles plus a vendor service (FFFF) for impedance and measurement start. */
class BeurerBF450Driver : GattScaleDriver() {
    override val id = "beurer_bf450"
    override val displayName = "Beurer BF450"
    override val brand = "Beurer"
    override val capabilities = ScaleCapabilities(impedance = true, deviceComposition = true, userRegistration = true, config = STANDARD_CONFIG)

    override fun match(adv: AdvertisementInfo): MatchConfidence? =
        if ("bf450" in adv.nameOrEmpty.lowercase(Locale.ROOT)) MatchConfidence.HIGH else null

    override fun newProtocol(adv: AdvertisementInfo?): GattProtocol = Protocol()

    private class Protocol : StandardWeightProfileProtocol() {
        private val svc = uuid16(0xFFFF)
        private val chrActivity = uuid16(0xFFF2)
        private val chrTakeMeasurement = uuid16(0xFFF4)
        private val chrLiveWeight = uuid16(0xFFF0)
        private val chrExtra = uuid16(0xFFF6)
        private var lastImpedance = 0f
        private var bodyComp: FloatArray? = null

        override fun onConnected() {
            super.onConnected()
            setNotifyOn(svc, chrLiveWeight)
            setNotifyOn(svc, chrTakeMeasurement)
            setNotifyOn(svc, chrExtra)
        }

        override fun writeUserDataToScale() {
            super.writeUserDataToScale()
            writeTo(svc, chrActivity, byteArrayOf((user.activityLevel.ordinal + 1).coerceIn(1, 5).toByte()))
        }

        override fun onRequestMeasurement() {
            writeTo(svc, chrTakeMeasurement, byteArrayOf(0x00))
        }

        override fun onNotification(characteristic: UUID, data: ByteArray) {
            when (characteristic) {
                chrLiveWeight -> if (data.size >= 5) live(Bytes.u8(data, 3) * 0.5f)
                chrExtra -> if (data.size >= 16) lastImpedance = Bytes.fromUnsignedInt16Le(data, 5).toFloat()
                chrBodyCompositionMeasurement -> {
                    readVendorBodyComposition(data)
                    super.onNotification(characteristic, data)
                }
                else -> super.onNotification(characteristic, data)
            }
        }

        /** The BF450 packs fat %, muscle %, muscle mass and water mass at fixed offsets. */
        private fun readVendorBodyComposition(data: ByteArray) {
            if (data.size < 14) return
            val fat = Bytes.fromUnsignedInt16Le(data, 2)
            if (fat == 0) {
                bodyComp = null // weight only (no foot contact)
                return
            }
            bodyComp = floatArrayOf(
                fat * 0.1f,
                Bytes.fromUnsignedInt16Le(data, 6) * 0.1f,
                Bytes.fromUnsignedInt16Le(data, 8) * 0.1f,
                Bytes.fromUnsignedInt16Le(data, 10) * 0.1f,
            )
        }

        override fun transformBeforePublish(m: ScaleSample): ScaleSample {
            val bc = bodyComp ?: return m
            val w = m.weightKg?.takeIf { it > 0f } ?: return m
            // Fixed vendor offsets (unverified against the flags): never override SIG-parsed values.
            if (m.fatPercent == null) m.fatPercent = bc[0]
            if (m.musclePercent == null) m.musclePercent = bc[1]
            if (m.waterPercent == null) m.waterPercent = bc[3] / w * 100f
            if (lastImpedance > 0f && m.impedance == null) m.impedance = lastImpedance
            bodyComp = null
            return m
        }
    }
}

/** Sanitas SBF72/SBF73, Beurer BF915: SIG profiles plus vendor user list/PIN display. */
class SanitasSbf72Driver : GattScaleDriver() {
    override val id = "sanitas_sbf72"
    override val displayName = "Sanitas SBF72 / SBF73 · Beurer BF915"
    override val brand = "Sanitas / Beurer"
    override val capabilities = ScaleCapabilities(deviceComposition = true, userRegistration = true, config = STANDARD_CONFIG)
    override val keywords = listOf("SBF72", "SBF73", "BF915", "Sanitas", "Beurer")

    override fun match(adv: AdvertisementInfo): MatchConfidence? {
        val n = adv.nameOrEmpty.lowercase(Locale.ROOT)
        return if ("sbf72" in n || "sbf73" in n || "bf915" in n) MatchConfidence.HIGH else null
    }

    override fun newProtocol(adv: AdvertisementInfo?): GattProtocol = Protocol()

    private class Protocol : StandardWeightProfileProtocol() {
        private val svc = UUID.fromString("0000ffff-0000-1000-8000-00805f9b34fb")
        private val chrUserList = UUID.fromString("00000001-0000-1000-8000-00805f9b34fb")
        private val chrInitials = UUID.fromString("00000002-0000-1000-8000-00805f9b34fb")
        private val chrActivity = UUID.fromString("00000004-0000-1000-8000-00805f9b34fb")
        private val chrTakeMeasurement = UUID.fromString("00000006-0000-1000-8000-00805f9b34fb")

        /** Asks the scale to show the user's PIN on its display (pin index = user index + 16). */
        override fun requestScaleUserConsent(index: Int) {
            writeTo(svc, chrUserList, byteArrayOf((index + 16).toByte()))
            userInfo(ScaleNotice.Kind.PIN_SHOWN_ON_SCALE, index)
        }

        override fun onRequestMeasurement() {
            writeTo(svc, chrTakeMeasurement, byteArrayOf(0x00))
        }

        override fun writeUserDataToScale() {
            writeTo(svc, chrActivity, byteArrayOf((user.activityLevel.ordinal + 1).toByte()))
            initials(user.name)?.let { writeTo(svc, chrInitials, it.encodeToByteArray()) }
            super.writeUserDataToScale()
        }
    }
}

/** Beurer BF105/720, BF1000, BF950 (Sanitas SBF76/77), BF500, BF600/BF850: SIG profiles + vendor service. */
class StandardBeurerSanitasDriver : GattScaleDriver() {
    override val id = "beurer_bf_standard"
    override val displayName = "Beurer BF105/720/1000/950/500/600/850 · Sanitas SBF76/77"
    override val brand = "Beurer / Sanitas"
    override val capabilities = ScaleCapabilities(deviceComposition = true, userRegistration = true, config = STANDARD_CONFIG)
    override val keywords = listOf("BF105", "BF720", "BF1000", "BF950", "SBF76", "SBF77", "BF500", "BF600", "BF850")

    internal enum class Model { BF105, BF1000, BF950, BF500, BF600 }

    internal class VendorProfile(
        val service: UUID,
        val chrActivity: UUID?,
        val chrTakeMeasurement: UUID,
        val chrInitials: UUID?,
        val chrTargetWeight: UUID?,
    )

    override fun match(adv: AdvertisementInfo): MatchConfidence? = if (modelOf(adv.nameOrEmpty) != null) MatchConfidence.HIGH else null

    override fun newProtocol(adv: AdvertisementInfo?): GattProtocol = Protocol(modelOf(adv?.nameOrEmpty.orEmpty()) ?: Model.BF105)

    internal class Protocol(private val model: Model) : StandardWeightProfileProtocol() {
        private val profile = profileFor(model)
        private val bf1000Visceral = uuid16(0x0009)
        private var pendingVisceral = 0f

        override fun onConnected() {
            super.onConnected()
            if (model == Model.BF1000) {
                setNotifyOn(profile.service, uuid16(0x0006))
                setNotifyOn(profile.service, bf1000Visceral)
                setNotifyOn(profile.service, uuid16(0x000A))
            }
        }

        override fun onNotification(characteristic: UUID, data: ByteArray) {
            if (model == Model.BF1000 && characteristic == bf1000Visceral) {
                // Packet 0x7E: visceral fat (0.1) followed by segmental fat (not stored).
                if (data.size >= 12 && Bytes.u8(data, 0) == 0x7E) {
                    val v = Bytes.u8(data, 1) * 0.1f
                    if (v > 0f) pendingVisceral = v
                }
                return
            }
            super.onNotification(characteristic, data)
        }

        override fun transformBeforePublish(m: ScaleSample): ScaleSample {
            if (model == Model.BF1000 && m.visceralFat == null && pendingVisceral > 0f) m.visceralFat = pendingVisceral
            pendingVisceral = 0f
            return m
        }

        override fun onRequestMeasurement() {
            writeTo(profile.service, profile.chrTakeMeasurement, byteArrayOf(0x00))
        }

        override fun writeUserDataToScale() {
            super.writeUserDataToScale()
            profile.chrActivity?.let {
                writeTo(profile.service, it, byteArrayOf((user.activityLevel.ordinal + 1).coerceIn(1, 5).toByte()))
            }
            profile.chrInitials?.let { chr -> initials(user.name)?.let { writeTo(profile.service, chr, it.encodeToByteArray()) } }
            profile.chrTargetWeight?.let { chr ->
                val goal = user.goalWeightKg?.toInt() ?: return@let
                writeTo(profile.service, chr, Bytes.toInt16Be(goal))
            }
        }
    }

    internal companion object {
        fun modelOf(name: String): Model? {
            val n = name.lowercase(Locale.ROOT)
            return when {
                "bf105" in n || "bf720" in n -> Model.BF105
                "bf1000" in n -> Model.BF1000
                "bf950" in n || "sbf77" in n || "sbf76" in n -> Model.BF950
                "bf500" in n -> Model.BF500
                "bf600" in n || "bf850" in n -> Model.BF600
                else -> null
            }
        }

        fun profileFor(m: Model) = when (m) {
            Model.BF105, Model.BF1000 -> VendorProfile(uuid16(0xFFFF), uuid16(0x0004), uuid16(0x0006), uuid16(0x0002), uuid16(0x0003))
            Model.BF950 -> VendorProfile(uuid16(0xFFFF), uuid16(0x0004), uuid16(0x0006), uuid16(0x0002), null)
            Model.BF500 -> VendorProfile(uuid16(0xFFFF), uuid16(0xFFF2), uuid16(0xFFF4), null, null)
            Model.BF600 -> VendorProfile(uuid16(0xFFF0), uuid16(0xFFF3), uuid16(0xFFF4), uuid16(0xFFF6), null)
        }
    }
}

/** Up to three upper-case letters/digits of the user's name, for scales that show initials. */
internal fun initials(name: String): String? =
    name.uppercase(Locale.ROOT).replace(Regex("[^A-Z0-9]"), "").take(3).takeIf { it.isNotEmpty() }
