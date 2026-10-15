package com.enjoyyourtime.globalfitness.scale

import java.util.UUID

/** How sure a driver is that an advertisement belongs to its scale family. */
enum class MatchConfidence {
    /** Generic fingerprint shared by several unrelated scales (only a hint). */
    LOW,

    /** Plausible, e.g. a generic name such as "Weight Scale". */
    MEDIUM,

    /** Specific name or payload signature: safe to auto-configure. */
    HIGH,
}

enum class LinkKind {
    /** Reads the advertisements only (no connection). */
    PASSIVE,

    /** Connects over GATT when the scale advertises (stepped on). */
    GATT,
}

enum class ConfigKind { HEX_KEY_32, USER_SLOT, TEXT }

/** One per-driver setting the user can (or must) fill in. */
data class ConfigField(
    val key: String,
    val label: String,
    val help: String,
    val kind: ConfigKind,
    val required: Boolean = false,
    /** For [ConfigKind.USER_SLOT]: highest slot number. */
    val maxSlot: Int = 8,
) {
    /** Spanish error message, or null when [value] is acceptable. */
    fun validate(value: String?): String? {
        val v = value?.trim().orEmpty()
        if (v.isEmpty()) return if (required) "Obligatorio" else null
        return when (kind) {
            ConfigKind.HEX_KEY_32 ->
                if (v.length == 32 && Bytes.isHex(v)) null else "Deben ser 32 caracteres hexadecimales"
            ConfigKind.USER_SLOT ->
                if (v.toIntOrNull() in 1..maxSlot) null else "Usuario entre 1 y $maxSlot"
            ConfigKind.TEXT -> null
        }
    }

    companion object {
        const val KEY_BINDKEY = "bindkey"
        const val KEY_USER_SLOT = "user_slot"

        fun userSlot(maxSlot: Int = 8) = ConfigField(
            key = KEY_USER_SLOT,
            label = "Usuario en la báscula",
            help = "Posición de usuario que la app registra/usa en la báscula (1 = primera).",
            kind = ConfigKind.USER_SLOT,
            maxSlot = maxSlot,
        )
    }
}

data class ScaleCapabilities(
    val weight: Boolean = true,
    /** Reports bio-impedance (composition computed by the app with the driver's formula). */
    val impedance: Boolean = false,
    /** Reports composition values computed by the scale itself. */
    val deviceComposition: Boolean = false,
    /** Needs the app user registered on the scale (height/age/sex in a user slot). */
    val userRegistration: Boolean = false,
    /** Can download stored (offline) weighings. */
    val history: Boolean = false,
    /** Settings shown/required for this driver (bind key, user slot, ...). */
    val config: List<ConfigField> = emptyList(),
) {
    val needsConfig: Boolean get() = config.any { it.required }
}

/**
 * A scale family: how to recognise it ([match]) and how to read it (passive or GATT).
 * Implementations are stateless; per-device state lives in sessions/protocol objects.
 */
sealed interface ScaleDriver {
    /** Stable id persisted with configured scales and measurements. Never change it. */
    val id: String
    val displayName: String
    val brand: String

    /** True only for drivers tested on real hardware (UI shows "Experimental" otherwise). */
    val verified: Boolean get() = false
    val capabilities: ScaleCapabilities
    val link: LinkKind

    /** Extra search terms for the manual catalog (model numbers, sister brands...). */
    val keywords: List<String> get() = emptyList()

    /** Impedance formula used when the scale reports impedance without composition. */
    val formulaLib: FormulaLib? get() = null

    /** Null when the advertisement is not from this family. Must never throw. */
    fun match(adv: AdvertisementInfo): MatchConfidence?
}

/** What the live card shows while a weighing is in progress. */
sealed interface LiveState {
    data object Idle : LiveState
    data class Measuring(val weightKg: Double) : LiveState

    /** Weight is stable; the scale is still measuring impedance. */
    data class Stabilized(val weightKg: Double) : LiveState

    /** The last weighing was committed (the scale keeps re-broadcasting it). */
    data object Done : LiveState
}

/** Passive (advertisement-only) driver. */
abstract class PassiveScaleDriver : ScaleDriver {
    final override val link: LinkKind get() = LinkKind.PASSIVE

    /** One session per physical device; it may keep state across advertisements. */
    abstract fun newSession(env: DriverEnv): PassiveSession
}

interface PassiveSession {
    /** Decodes one advertisement of this device; returns the finished readings (usually 0 or 1). */
    fun parseAdvertisement(adv: AdvertisementInfo, env: DriverEnv): List<ScaleReading>

    /** Live state after the last call. */
    val live: LiveState get() = LiveState.Idle

    /** True while [tick] may produce something (time-based commits). */
    val needsTick: Boolean get() = false

    /** Called about once per second while [needsTick]. */
    fun tick(env: DriverEnv): List<ScaleReading> = emptyList()
}

/**
 * Convenience base for stateless/simple broadcast protocols: implement [parse], which returns a
 * finished sample (or null) and may update [live]. Re-broadcasts of the same weighing are
 * suppressed here (same weight within [rebroadcastWindowMs]).
 */
abstract class SimplePassiveSession(
    private val formulaLib: FormulaLib?,
    private val rebroadcastWindowMs: Long = 60_000L,
) : PassiveSession {
    private var lastGrams: Long? = null
    private var lastAt: Long = 0L
    override var live: LiveState = LiveState.Idle
        protected set

    protected abstract fun parse(adv: AdvertisementInfo, env: DriverEnv): ScaleSample?

    override fun parseAdvertisement(adv: AdvertisementInfo, env: DriverEnv): List<ScaleReading> {
        val sample = parse(adv, env) ?: return emptyList()
        val now = env.elapsedClock()
        val reading = sample.toReading(env, formulaLib, if (adv.receivedAtMillis > 0) adv.receivedAtMillis else env.wallClock())
            ?: return emptyList()
        val grams = ScaleReading.grams(reading.weightKg)
        val last = lastGrams
        if (last != null && kotlin.math.abs(last - grams) <= 50 && now - lastAt <= rebroadcastWindowMs) {
            lastAt = now
            return emptyList()
        }
        lastGrams = grams
        lastAt = now
        live = LiveState.Done
        return listOf(reading)
    }

    /** Reports a live (not yet final) weight for the home card. */
    protected fun liveWeight(weightKg: Float) {
        if (weightKg.isFinite() && weightKg > 0f) live = LiveState.Measuring(weightKg.toDouble())
    }
}

fun interface Cancellable {
    fun cancel()
}

/**
 * Operations a GATT protocol can request. Calls are queued and executed in order by the session
 * runner (each waits for its callback), so protocols just call them in protocol order.
 */
interface GattIo {
    /** Enables notifications or indications (whichever the characteristic supports). */
    fun setNotifyOn(service: UUID, characteristic: UUID)
    fun write(service: UUID, characteristic: UUID, payload: ByteArray, withResponse: Boolean = true)

    /** Reads a characteristic; the value is delivered to [GattProtocol.onNotification]. */
    fun read(service: UUID, characteristic: UUID)
    fun hasCharacteristic(service: UUID, characteristic: UUID): Boolean

    /** Ends the session once the queued operations are done. */
    fun disconnect()

    /** Runs [action] on the protocol thread after [delayMs]. */
    fun schedule(delayMs: Long, action: () -> Unit): Cancellable
    fun publish(reading: ScaleReading)
    fun live(weightKg: Double)
    fun message(text: String)
}

/** Status of a GATT session, for the devices screen. */
enum class SessionStatus { CONNECTING, CONNECTED, MEASURING, SAVED, DONE, FAILED }

sealed interface ScaleEvent {
    data class Status(val status: SessionStatus, val detail: String? = null) : ScaleEvent
    data class Live(val weightKg: Double) : ScaleEvent
    data class Reading(val reading: ScaleReading) : ScaleEvent
    data class Message(val text: String) : ScaleEvent
}

/**
 * Per-connection protocol state machine, in the style of openScale's handlers: the runner calls
 * [onConnected] once services are discovered and forwards every notification/indication (and
 * read result) to [onNotification]. Everything runs on one coroutine, so no locking is needed.
 */
abstract class GattProtocol {
    private var ioRef: GattIo? = null
    private var envRef: DriverEnv? = null

    protected val io: GattIo get() = checkNotNull(ioRef) { "Protocol not attached" }
    protected val env: DriverEnv get() = checkNotNull(envRef) { "Protocol not attached" }
    protected val user: ScaleContext get() = env.ctx

    /** Impedance library of the driver (copied in by the driver when creating the protocol). */
    var formulaLib: FormulaLib? = null

    fun attach(io: GattIo, env: DriverEnv) {
        ioRef = io
        envRef = env
    }

    open fun onConnected() = Unit
    open fun onNotification(characteristic: UUID, data: ByteArray) = Unit
    open fun onDisconnected() = Unit

    protected fun setNotifyOn(service: UUID, characteristic: UUID) = io.setNotifyOn(service, characteristic)

    protected fun writeTo(service: UUID, characteristic: UUID, payload: ByteArray, withResponse: Boolean = true) =
        io.write(service, characteristic, payload, withResponse)

    protected fun readFrom(service: UUID, characteristic: UUID) = io.read(service, characteristic)

    protected fun hasCharacteristic(service: UUID, characteristic: UUID): Boolean =
        io.hasCharacteristic(service, characteristic)

    protected fun requestDisconnect() = io.disconnect()

    protected fun schedule(delayMs: Long, action: () -> Unit): Cancellable = io.schedule(delayMs, action)

    /** Converts and publishes a finished weighing. Returns false when it had no usable weight. */
    protected fun publish(sample: ScaleSample): Boolean {
        val reading = sample.toReading(env, formulaLib) ?: return false
        io.publish(reading)
        return true
    }

    protected fun live(weightKg: Float) {
        if (weightKg.isFinite() && weightKg > 0f) io.live(weightKg.toDouble())
    }

    protected fun userInfo(text: String) = io.message(text)

    protected fun settingsGetInt(key: String, default: Int = -1): Int = env.store.getInt(key, default)
    protected fun settingsPutInt(key: String, value: Int) = env.store.putInt(key, value)
    protected fun settingsGetString(key: String): String? = env.store.getString(key)
    protected fun settingsPutString(key: String, value: String) = env.store.putString(key, value)
    protected fun settingsRemove(key: String) = env.store.remove(key)
}

/** GATT driver: connects when a configured scale advertises and runs a [GattProtocol]. */
abstract class GattScaleDriver : ScaleDriver {
    final override val link: LinkKind get() = LinkKind.GATT

    /** [adv] is the advertisement that triggered the connection (variant detection). */
    abstract fun newProtocol(adv: AdvertisementInfo?): GattProtocol

    /** Creates the protocol with the driver's formula library set. */
    fun createProtocol(adv: AdvertisementInfo?): GattProtocol = newProtocol(adv).also { it.formulaLib = formulaLib }
}
