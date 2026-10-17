package com.enjoyyourtime.globalfitness.cloud

import java.util.Locale

/**
 * Decides what may leave the phone. Both settings and per-scale options use allowlists, with a
 * secret-looking-key filter as a second line of defense. The Band auth key, Health Connect data,
 * scale bind keys / PINs / pairing secrets and Bluetooth addresses are never uploaded.
 */
object PortableSettings {
    /**
     * Settings that make sense on another phone. None today: the remaining app settings are
     * device-specific (permission flags, dismissed auto-configured scale addresses...).
     */
    val ALLOWED: Set<String> = emptySet()

    /** Per-scale options that are safe and useful on a new phone (which user slot on the scale is ours). */
    const val DEVICE_USER_SLOT = "user_slot"
    val DEVICE_CONFIG_ALLOWED: Set<String> = setOf(DEVICE_USER_SLOT)

    private val SECRET_MARKERS = listOf(
        "auth", "key", "token", "secret", "password", "pass", "pin", "mac", "address", "band", "health", "uid",
    )

    /** True for keys that must never be uploaded (secrets, BLE addresses, band or health data). */
    fun looksSensitive(key: String): Boolean {
        val k = key.lowercase(Locale.ROOT)
        return SECRET_MARKERS.any { it in k }
    }

    /** Portable settings only: allowlisted keys with simple values. */
    fun filterSettings(values: Map<String, Any?>, allowed: Set<String> = ALLOWED): Map<String, Any> {
        val out = LinkedHashMap<String, Any>()
        for ((key, value) in values) {
            if (key !in allowed || looksSensitive(key) || value == null) continue
            if (value is String || value is Number || value is Boolean) out[key] = value
        }
        return out
    }

    /** Per-scale options: allowlisted keys only (no bind keys, PINs, pairing secrets or addresses). */
    fun filterDeviceConfig(config: Map<String, String>): Map<String, String> =
        config.filterKeys { it in DEVICE_CONFIG_ALLOWED && !looksSensitive(it) }
}
