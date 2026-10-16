package com.enjoyyourtime.globalfitness.wearable

import com.enjoyyourtime.globalfitness.scale.MatchConfidence
import java.util.Locale

/** A device seen recently (advertising or bonded) that at least one driver recognised. */
data class DetectedWearable(
    val address: String,
    val name: String?,
    /** Last advertisement RSSI; null when only known as a bonded device. */
    val rssi: Int?,
    val bonded: Boolean,
    val lastSeenMillis: Long,
    val matches: List<WearableMatch>,
    val firstSeenMillis: Long = lastSeenMillis,
    /** Advertisements received since [firstSeenMillis] (bonded listings do not count). */
    val adverts: Int = 0,
) {
    val best: WearableMatch get() = matches.first()
    val highMatches: List<WearableMatch> get() = matches.filter { it.confidence == MatchConfidence.HIGH }
}

/** What happened with a sighting in [WearableDetector]. */
sealed interface WearableDetection {
    data object Ignored : WearableDetection
    data object Updated : WearableDetection

    /**
     * Unambiguous, close (or bonded) and not configured yet: offer it to the user ("¿Es tu
     * pulsera?"). Wearables are never set up silently: they connect and most need a key.
     */
    data class Suggest(val driver: WearableDriver, val address: String, val name: String?) : WearableDetection
}

/**
 * Detection bookkeeping fed with every advertisement and bonded-device listing (pure, times on the
 * elapsed clock). Mirrors the scales' detector, with two differences: the only outcome is a
 * suggestion, and bonded devices skip the proximity checks (the user paired them with this phone).
 *
 * An advertising device is suggested once it has exactly one HIGH match, is close ([rssiGate]),
 * was heard [minAdverts] times over at least [settleMs], and no other fresh, unconfigured,
 * non-dismissed advertising device has a HIGH match (a neighbour's watch).
 */
class WearableDetector(
    private val registry: WearableRegistry,
    private val ttlMs: Long = 120_000L,
    private val rematchIntervalMs: Long = 2_000L,
    private val rssiGate: Int = -75,
    private val settleMs: Long = 3_000L,
    private val minAdverts: Int = 2,
    /** Bonded devices stay listed this long after the last bonded listing. */
    private val bondedTtlMs: Long = 10 * 60_000L,
) {
    private val seen = LinkedHashMap<String, DetectedWearable>()
    private val lastMatchAt = LinkedHashMap<String, Long>()
    private val decided = HashSet<String>()

    fun detected(now: Long): List<DetectedWearable> {
        prune(now)
        return seen.values.sortedWith(
            compareByDescending<DetectedWearable> { it.highMatches.isNotEmpty() }
                .thenByDescending { it.bonded }
                .thenByDescending { it.rssi ?: Int.MIN_VALUE },
        )
    }

    fun matchesFor(address: String): List<WearableMatch> = seen[key(address)]?.matches.orEmpty()

    fun onSighting(
        sighting: WearableSighting,
        now: Long,
        configuredAddresses: Set<String>,
        dismissed: Set<String>,
    ): WearableDetection {
        val key = key(sighting.address)
        val previous = seen[key]?.takeIf { now - it.lastSeenMillis <= ttlFor(it) }
        val last = lastMatchAt[key]
        val matches = if (!sighting.bonded && last != null && now - last < rematchIntervalMs) {
            previous?.matches ?: return WearableDetection.Ignored
        } else {
            lastMatchAt.remove(key)
            lastMatchAt[key] = now
            while (lastMatchAt.size > MAX_TRACKED) lastMatchAt.remove(lastMatchAt.keys.first())
            registry.detect(sighting)
        }
        if (matches.isEmpty()) return WearableDetection.Ignored

        val advertised = sighting.rssi != null
        val entry = DetectedWearable(
            address = sighting.address,
            name = sighting.trimmedName ?: previous?.name,
            rssi = sighting.rssi ?: previous?.rssi,
            bonded = sighting.bonded || previous?.bonded == true,
            lastSeenMillis = now,
            matches = matches,
            firstSeenMillis = previous?.firstSeenMillis ?: now,
            adverts = (previous?.adverts ?: 0) + if (advertised) 1 else 0,
        )
        seen[key] = entry
        prune(now)

        val configured = configuredAddresses.map { it.uppercase(Locale.ROOT) }.toSet()
        val dismissedKeys = dismissed.map { it.uppercase(Locale.ROOT) }.toSet()
        if (key in configured || key in dismissedKeys || key in decided) return WearableDetection.Updated
        val high = entry.highMatches
        if (high.size != 1) return WearableDetection.Updated
        if (!entry.bonded) {
            val rssi = entry.rssi ?: return WearableDetection.Updated
            if (rssi < rssiGate) return WearableDetection.Updated
            if (entry.adverts < minAdverts || now - entry.firstSeenMillis < settleMs) return WearableDetection.Updated
            val othersWithHigh = seen.values.any {
                it !== entry && !it.bonded && key(it.address) !in dismissedKeys && key(it.address) !in configured &&
                    it.highMatches.isNotEmpty()
            }
            if (othersWithHigh) return WearableDetection.Updated
        }
        decided += key
        return WearableDetection.Suggest(high.single().driver, sighting.address, entry.name)
    }

    /** Forgets [address] (e.g. after the user removed it) so it can be suggested again. */
    fun forget(address: String) {
        val key = key(address)
        seen.remove(key)
        lastMatchAt.remove(key)
        decided.remove(key)
    }

    private fun ttlFor(entry: DetectedWearable): Long = if (entry.bonded) maxOf(ttlMs, bondedTtlMs) else ttlMs

    private fun prune(now: Long) {
        val it = seen.entries.iterator()
        while (it.hasNext()) {
            val entry = it.next().value
            if (now - entry.lastSeenMillis > ttlFor(entry)) it.remove()
        }
    }

    private fun key(address: String) = address.uppercase(Locale.ROOT)

    private companion object {
        const val MAX_TRACKED = 512
    }
}
