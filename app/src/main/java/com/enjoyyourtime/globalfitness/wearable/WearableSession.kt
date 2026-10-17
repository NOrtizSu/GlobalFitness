package com.enjoyyourtime.globalfitness.wearable

import com.enjoyyourtime.globalfitness.band.xiaomi.AlarmSpec
import com.enjoyyourtime.globalfitness.band.xiaomi.BandAlarm
import com.enjoyyourtime.globalfitness.band.xiaomi.BandLink
import com.enjoyyourtime.globalfitness.band.xiaomi.BandNotification
import com.enjoyyourtime.globalfitness.band.xiaomi.CompanionInfo
import com.enjoyyourtime.globalfitness.scale.DriverStore
import com.enjoyyourtime.globalfitness.scale.GattLink
import java.util.TimeZone
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow

/**
 * Why a connection attempt, a session or a request failed. The UI maps each value to a string
 * resource, so nothing here carries user-facing text.
 */
enum class WearableFailure {
    BLUETOOTH_UNAVAILABLE,
    BLUETOOTH_OFF,
    MISSING_PERMISSION,
    NO_KEY,
    NO_DEVICE,
    UNKNOWN_DRIVER,
    NOT_FOUND,
    CONNECT_FAILED,

    /** Connected, but the device does not expose the services this driver needs. */
    UNSUPPORTED_DEVICE,
    NO_RESPONSE,
    WRONG_KEY,
    AUTH_REJECTED,
    LINK_LOST,
    REQUEST_TIMEOUT,

    /** The driver does not implement this function. */
    NOT_SUPPORTED,

    /** Every alarm slot of the device is in use: one must be deleted first. */
    ALARM_SLOTS_FULL,
    CLOSED,
}

/** [message] is diagnostic text (English, never shown as is and never containing secrets). */
class WearableException(
    val failure: WearableFailure,
    message: String = failure.name,
    cause: Throwable? = null,
) : Exception(message, cause)

data class WearableDeviceInfo(
    val model: String? = null,
    val firmware: String? = null,
    val serial: String? = null,
    val hardware: String? = null,
    val manufacturer: String? = null,
)

/** Something the device reported; the controller folds these into its state. */
sealed interface WearableEvent {
    data class Battery(val level: Int?, val charging: Boolean? = null) : WearableEvent

    /** Null fields are unknown / unchanged. */
    data class DeviceState(val worn: Boolean? = null, val charging: Boolean? = null, val batteryLevel: Int? = null) :
        WearableEvent

    data class Info(val info: WearableDeviceInfo) : WearableEvent

    /**
     * One live heart rate reading. 0 (or a few beats) means "still measuring" on most devices;
     * [rrIntervalsMs] are beat-to-beat intervals when the device reports them.
     */
    data class HeartRate(
        val bpm: Int,
        val rrIntervalsMs: List<Int> = emptyList(),
        val sensorContact: Boolean? = null,
        val energyExpendedKj: Int? = null,
    ) : WearableEvent

    /**
     * Live activity totals for today; null fields are not reported by the device. Devices that only
     * report steps taken since their previous report use [stepsIncrement] instead of [steps].
     */
    data class Activity(
        val steps: Int? = null,
        val calories: Int? = null,
        val distanceMeters: Int? = null,
        val standingHours: Int? = null,
        val stepsIncrement: Int? = null,
    ) : WearableEvent

    /** The user pressed "found it" on the device (find device stopped there). */
    data object FindStopped : WearableEvent

    /** The device asks the phone to ring. */
    data object FindPhone : WearableEvent

    data class Alarms(val alarms: List<BandAlarm>, val maxAlarms: Int?) : WearableEvent
}

/** Timeouts shared by every session type (milliseconds). */
data class WearableTimeouts(
    val connect: Long = 20_000,
    val auth: Long = 10_000,
    val request: Long = 6_000,
    /** Pause in a request/response protocol before an unanswered optional step is skipped. */
    val optionalStep: Long = 3_000,
)

/** Opens the platform links; replaced by fakes in tests. */
interface WearableLinkFactory {
    /** An unconnected GATT link (the session connects it). */
    fun gatt(address: String): GattLink

    /** A connected RFCOMM byte stream to the Serial Port Profile; throws [WearableException]. */
    suspend fun spp(address: String): BandLink
}

/** Everything a session needs from the app. */
class WearableSessionEnv(
    val address: String,
    val name: String,
    /** The user-provided key text (hex or numeric user id), already validated; null when not needed. */
    val authKey: String?,
    /** Non-secret per-device settings (e.g. the Wear OS device id of a Xiaomi watch). */
    val config: Map<String, String> = emptyMap(),
    /** Small persisted per-device state (alarm slots of devices that cannot report them...). */
    val store: DriverStore,
    val companion: CompanionInfo,
    /** Parent scope; sessions run on their own single-threaded child scope. */
    val scope: CoroutineScope,
    val links: WearableLinkFactory,
    /** Diagnostic lines; never given key material. */
    val log: (String) -> Unit = {},
    val clock: () -> Long = System::currentTimeMillis,
    val timeouts: WearableTimeouts = WearableTimeouts(),
)

/** Alarms as a driver reports them, with the device limit when known. */
data class WearableAlarms(val alarms: List<BandAlarm>, val maxAlarms: Int?)

/**
 * One on-demand connection to a wearable. [open] connects and authenticates; afterwards the
 * functions declared by the driver may be called (others throw [WearableFailure.NOT_SUPPORTED]).
 * Everything the device reports, solicited or not, arrives on [events] (single collector).
 */
interface WearableSession {
    /** Short protocol label for diagnostics ("SPP v2", "BLE v1", "Huami"...). */
    val label: String
    val events: Flow<WearableEvent>
    val isOpen: Boolean

    /** Connects and authenticates; throws [WearableException] (the session is closed then). */
    suspend fun open()

    /** Suspends until the session ends and returns why ([WearableFailure.CLOSED] after [close]). */
    suspend fun awaitClosed(): WearableException

    /** Ends the session; safe from any thread, repeatedly. */
    fun close()

    /** Asks for battery / device state (and device info when [includeInfo]); answers arrive as events. */
    suspend fun refreshStatus(includeInfo: Boolean)

    suspend fun setTime(nowMillis: Long, timeZone: TimeZone, is24Hour: Boolean)

    suspend fun findDevice(start: Boolean)

    suspend fun setLiveHeartRate(enabled: Boolean)

    suspend fun sendNotification(notification: BandNotification)

    suspend fun dismissNotification(id: Int, packageName: String)

    suspend fun loadAlarms(): WearableAlarms

    /** Creates ([id] null) or edits an alarm. */
    suspend fun saveAlarm(id: Int?, spec: AlarmSpec)

    suspend fun deleteAlarm(id: Int)
}
