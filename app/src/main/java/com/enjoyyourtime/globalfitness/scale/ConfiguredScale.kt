package com.enjoyyourtime.globalfitness.scale

import java.net.URLDecoder
import java.net.URLEncoder

/**
 * A scale the user (or auto-detection) set up. [address] null means "any device this driver
 * recognises". [config] holds the driver settings (bind key, user slot...) plus values a driver
 * learns and persists (e.g. pairing secrets).
 */
data class ConfiguredScale(
    val id: String,
    val driverId: String,
    val address: String?,
    val name: String,
    val config: Map<String, String> = emptyMap(),
    val addedAt: Long = 0L,
    val autoConfigured: Boolean = false,
) {
    fun matchesAddress(other: String): Boolean = address == null || address.equals(other, ignoreCase = true)

    val userSlot: Int get() = config[ConfigField.KEY_USER_SLOT]?.toIntOrNull()?.coerceAtLeast(1) ?: 1
}

/**
 * Plain-text persistence of the configured scales list (one scale per line, tab separated fields,
 * URL-encoded values). Pure Kotlin so it is unit tested; unknown/short lines are skipped.
 */
object ConfiguredScaleCodec {
    private const val VERSION = "v1"

    fun encode(list: List<ConfiguredScale>): String = buildString {
        append(VERSION).append('\n')
        for (s in list) {
            val cfg = s.config.entries.joinToString("&") { (k, v) -> enc(k) + "=" + enc(v) }
            append(
                listOf(
                    enc(s.id),
                    enc(s.driverId),
                    enc(s.address.orEmpty()),
                    enc(s.name),
                    cfg,
                    s.addedAt.toString(),
                    if (s.autoConfigured) "1" else "0",
                ).joinToString("\t"),
            )
            append('\n')
        }
    }

    fun decode(text: String?): List<ConfiguredScale> {
        if (text.isNullOrBlank()) return emptyList()
        val lines = text.lines().filter { it.isNotBlank() }
        if (lines.firstOrNull() != VERSION) return emptyList()
        return lines.drop(1).mapNotNull { line ->
            val f = line.split('\t')
            if (f.size < 7) return@mapNotNull null
            val id = dec(f[0]).takeIf { it.isNotEmpty() } ?: return@mapNotNull null
            val driverId = dec(f[1]).takeIf { it.isNotEmpty() } ?: return@mapNotNull null
            val config = if (f[4].isEmpty()) {
                emptyMap()
            } else {
                f[4].split('&').mapNotNull { pair ->
                    val i = pair.indexOf('=')
                    if (i <= 0) null else dec(pair.substring(0, i)) to dec(pair.substring(i + 1))
                }.toMap()
            }
            ConfiguredScale(
                id = id,
                driverId = driverId,
                address = dec(f[2]).takeIf { it.isNotEmpty() },
                name = dec(f[3]),
                config = config,
                addedAt = f[5].toLongOrNull() ?: 0L,
                autoConfigured = f[6] == "1",
            )
        }
    }

    private fun enc(s: String): String = URLEncoder.encode(s, "UTF-8")
    private fun dec(s: String): String = runCatching { URLDecoder.decode(s, "UTF-8") }.getOrDefault(s)
}
