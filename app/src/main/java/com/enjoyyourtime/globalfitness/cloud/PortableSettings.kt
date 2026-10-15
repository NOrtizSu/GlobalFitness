package com.enjoyyourtime.globalfitness.cloud

import java.util.Locale

/**
 * Decides what may leave the phone. Settings use an allowlist (only portable preferences); device
 * configs keep their options minus anything that looks like a secret or a device address. The Band
 * auth key, Health Connect data and everything in `band_prefs` never reach this class at all, and
 * these filters are a second line of defense if they ever did.
 */
object PortableSettings {
    const val ASSIGNMENT_TOLERANCE_KG = "assignmentToleranceKg"

    /** Settings that make sense on another phone. Device-specific ones (pinned BLE addresses...) are excluded. */
    val ALLOWED: Set<String> = setOf(ASSIGNMENT_TOLERANCE_KG)

    private val SECRET_MARKERS = listOf(
        "auth", "key", "token", "secret", "password", "pass", "pin", "mac", "address", "band", "health", "uid",
    )

    /** True for keys that must never be uploaded (secrets, BLE addresses, band or health data). */
    fun looksSensitive(key: String): Boolean {
        val k = key.lowercase(Locale.ROOT)
        return SECRET_MARKERS.any { it in k }
    }

    /** Portable settings only: allowlisted keys with simple values. */
    fun filterSettings(values: Map<String, Any?>): Map<String, Any> {
        val out = LinkedHashMap<String, Any>()
        for ((key, value) in values) {
            if (key !in ALLOWED || looksSensitive(key) || value == null) continue
            if (value is String || value is Number || value is Boolean) out[key] = value
        }
        return out
    }

    /** Per-driver scale options without secrets or addresses. */
    fun filterDeviceConfig(config: Map<String, String>): Map<String, String> =
        config.filterKeys { !looksSensitive(it) }
}
