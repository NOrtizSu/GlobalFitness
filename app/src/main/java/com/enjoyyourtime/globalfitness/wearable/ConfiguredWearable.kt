package com.enjoyyourtime.globalfitness.wearable

import java.net.URLDecoder
import java.net.URLEncoder
import java.util.Locale

/**
 * A wearable the user set up (from a suggestion, the detected list or the catalog). The auth key is
 * NOT part of it: keys live in [WearableKeyStore] (no-backup storage), this is plain settings.
 * [config] holds non-secret driver settings.
 */
data class ConfiguredWearable(
    val id: String,
    val driverId: String,
    val address: String,
    val name: String,
    val config: Map<String, String> = emptyMap(),
    val addedAt: Long = 0L,
    /** Set up from a detection suggestion (vs. picked by hand). */
    val fromSuggestion: Boolean = false,
) {
    fun matchesAddress(other: String): Boolean = address.equals(other, ignoreCase = true)

    val addressKey: String get() = address.uppercase(Locale.ROOT)

    companion object {
        /** Wear OS Xiaomi watches need the companion device id in the auth nonce (Gadgetbridge PREF_XIAOMI_DEVICE_ID). */
        const val CONFIG_XIAOMI_DEVICE_ID = "xiaomi_device_id"

        /** "1" (default) / "0": send the phone's time on every connection. */
        const val CONFIG_SYNC_TIME = "sync_time_on_connect"
    }
}

/**
 * Plain-text persistence of the configured wearables (one per line, tab separated, URL-encoded
 * values). Unknown or short lines are skipped.
 */
object ConfiguredWearableCodec {
    private const val VERSION = "w1"

    fun encode(list: List<ConfiguredWearable>): String = buildString {
        append(VERSION).append('\n')
        for (w in list) {
            val cfg = w.config.entries.joinToString("&") { (k, v) -> enc(k) + "=" + enc(v) }
            append(
                listOf(
                    enc(w.id),
                    enc(w.driverId),
                    enc(w.address),
                    enc(w.name),
                    cfg,
                    w.addedAt.toString(),
                    if (w.fromSuggestion) "1" else "0",
                ).joinToString("\t"),
            )
            append('\n')
        }
    }

    fun decode(text: String?): List<ConfiguredWearable> {
        if (text.isNullOrBlank()) return emptyList()
        val lines = text.lines().filter { it.isNotBlank() }
        if (lines.firstOrNull() != VERSION) return emptyList()
        return lines.drop(1).mapNotNull { line ->
            val f = line.split('\t')
            if (f.size < 7) return@mapNotNull null
            val id = dec(f[0]).takeIf { it.isNotEmpty() } ?: return@mapNotNull null
            val driverId = dec(f[1]).takeIf { it.isNotEmpty() } ?: return@mapNotNull null
            val address = dec(f[2]).takeIf { it.isNotEmpty() } ?: return@mapNotNull null
            val config = if (f[4].isEmpty()) {
                emptyMap()
            } else {
                f[4].split('&').mapNotNull { pair ->
                    val i = pair.indexOf('=')
                    if (i <= 0) null else dec(pair.substring(0, i)) to dec(pair.substring(i + 1))
                }.toMap()
            }
            ConfiguredWearable(
                id = id,
                driverId = driverId,
                address = address,
                name = dec(f[3]),
                config = config,
                addedAt = f[5].toLongOrNull() ?: 0L,
                fromSuggestion = f[6] == "1",
            )
        }
    }

    private fun enc(s: String): String = URLEncoder.encode(s, "UTF-8")
    private fun dec(s: String): String = runCatching { URLDecoder.decode(s, "UTF-8") }.getOrDefault(s)
}
