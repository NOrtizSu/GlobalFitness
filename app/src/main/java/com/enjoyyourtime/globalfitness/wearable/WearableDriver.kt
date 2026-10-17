package com.enjoyyourtime.globalfitness.wearable

import com.enjoyyourtime.globalfitness.scale.MatchConfidence

/** Protocol family of a wearable; families share the key wizard and most of the session code. */
enum class WearableFamily {
    /** Xiaomi / Redmi protobuf protocol (Mi Fitness devices). */
    XIAOMI,

    /** Classic Huami protocol (Mi Band 2-6, Amazfit Bip / GTS / GTR ... before Zepp OS). */
    HUAMI,

    /** Huami 2021 / Zepp OS chunked protocol (Mi Band 7, Amazfit Band 7, GTR / GTS 3 and later). */
    ZEPP_OS,

    /** Any device exposing the standard Heart Rate / Battery / Device Information services. */
    STANDARD_BLE,

    /** PineTime running InfiniTime. */
    PINETIME,

    /** Espruino Bangle.js (Nordic UART JSON protocol). */
    BANGLEJS,
}

/** How the phone talks to the device. */
enum class WearableTransport {
    /** Bluetooth Low Energy GATT services and characteristics. */
    BLE_GATT,

    /** Bluetooth Classic RFCOMM socket to the Serial Port Profile. */
    CLASSIC_SPP,

    /** Line-oriented text over the Nordic UART service (BLE). */
    BLE_UART,
}

/** Functions a driver can offer; the UI only shows what the driver declares. */
enum class WearableFeature {
    BATTERY,
    DEVICE_INFO,

    /** Worn / charging state. */
    DEVICE_STATE,
    TIME_SYNC,
    FIND_DEVICE,
    LIVE_HEART_RATE,
    LIVE_STEPS,
    NOTIFICATIONS,
    ALARMS,
}

/** What secret, if any, the user must provide before connecting. */
enum class AuthKeyKind {
    NONE,

    /** 16-byte key shown as 32 hex characters, optionally prefixed with `0x`. */
    HEX_32,

    /** Older plaintext Xiaomi devices authenticate with the numeric Xiaomi account user id. */
    NUMERIC_USER_ID,
}

/** Everything the key wizard needs to know about a driver (texts come from resources in the UI). */
data class KeyHelp(
    val kind: AuthKeyKind,
    /** Gadgetbridge's general page about where the key comes from (vendor account / server). */
    val serverDocUrl: String?,
    /** Gadgetbridge's page for this device family or model. */
    val deviceDocUrl: String?,
    /** Vendor app that owns the device ("Mi Fitness", "Zepp", "Zepp Life"); null when there is none. */
    val vendorApp: String?,
)

/**
 * A wearable model or family: how to recognise it ([match]) and how to talk to it ([createSession]).
 * Implementations are stateless; per-connection state lives in the sessions.
 */
interface WearableDriver {
    /** Stable id persisted with configured wearables and heart rate samples. Never change it. */
    val id: String
    val displayName: String
    val brand: String
    val family: WearableFamily

    /** True only for drivers tested on real hardware (UI shows "Experimental" otherwise). */
    val verified: Boolean get() = false
    val features: Set<WearableFeature>
    val transport: WearableTransport
    val authKey: AuthKeyKind
    val needsAuthKey: Boolean get() = authKey != AuthKeyKind.NONE

    /** Key wizard data; null when [needsAuthKey] is false. */
    val keyHelp: KeyHelp? get() = null

    /** Extra search terms for the catalog (model numbers, sister brands...). */
    val keywords: List<String> get() = emptyList()

    /** Gadgetbridge's public page about this device (setup notes, known issues). */
    val docUrl: String? get() = keyHelp?.deviceDocUrl

    /** Null when [device] is not from this family. Must never throw. */
    fun match(device: WearableSighting): MatchConfidence?

    /** A new, unconnected session; [WearableSession.open] connects and authenticates. */
    fun createSession(env: WearableSessionEnv): WearableSession

    fun supports(feature: WearableFeature): Boolean = feature in features
}

/** Name-based matching shared by most drivers: [pattern] on the advertised or bonded name. */
abstract class NamePatternDriver : WearableDriver {
    /** Patterns of the names this model advertises / is bonded with. */
    abstract val namePatterns: List<Regex>

    /** Confidence of a name match (HIGH for model-specific names). */
    open val nameConfidence: MatchConfidence get() = MatchConfidence.HIGH

    override fun match(device: WearableSighting): MatchConfidence? {
        val name = device.trimmedName ?: return null
        return if (namePatterns.any { it.matches(name) }) nameConfidence else null
    }
}
