package com.enjoyyourtime.globalfitness.scale

import java.text.Normalizer
import java.util.Locale

/** One driver that recognised an advertisement. [order] is the registry position (tie breaker). */
data class ScaleMatch(val driver: ScaleDriver, val confidence: MatchConfidence, val order: Int)

/**
 * All supported scale drivers. Ranking: highest confidence first; on ties the earlier driver in
 * the list wins (specific drivers are listed before generic ones, as in openScale's factory).
 */
class ScaleRegistry(val drivers: List<ScaleDriver>) {
    private val byId: Map<String, ScaleDriver> = drivers.associateBy { it.id }

    init {
        require(byId.size == drivers.size) { "Duplicate driver ids" }
    }

    fun find(id: String?): ScaleDriver? = id?.let { byId[it] }

    /** Every driver claiming [adv], best first. */
    fun detect(adv: AdvertisementInfo): List<ScaleMatch> =
        drivers.mapIndexedNotNull { index, driver ->
            safeMatch(driver, adv)?.let { ScaleMatch(driver, it, index) }
        }.sortedWith(compareByDescending<ScaleMatch> { it.confidence }.thenBy { it.order })

    fun best(adv: AdvertisementInfo): ScaleMatch? = detect(adv).firstOrNull()

    /** Accent- and case-insensitive search over name, brand and keywords; empty query = all. */
    fun search(query: String): List<ScaleDriver> {
        val q = normalize(query)
        val sorted = drivers.sortedWith(compareBy<ScaleDriver>({ !it.verified }, { normalize(it.brand) }, { normalize(it.displayName) }))
        if (q.isEmpty()) return sorted
        val terms = q.split(' ').filter { it.isNotEmpty() }
        return sorted.filter { d ->
            val haystack = normalize((listOf(d.displayName, d.brand, d.id) + d.keywords).joinToString(" "))
            terms.all { it in haystack }
        }
    }

    companion object {
        /** A driver bug must never break detection: anything but a VM error counts as "no match". */
        fun safeMatch(driver: ScaleDriver, adv: AdvertisementInfo): MatchConfidence? =
            try {
                driver.match(adv)
            } catch (e: VirtualMachineError) {
                throw e
            } catch (e: Throwable) {
                null
            }

        fun normalize(s: String): String =
            Normalizer.normalize(s, Normalizer.Form.NFD)
                .replace(Regex("\\p{M}+"), "")
                .lowercase(Locale.ROOT)
                .trim()
    }
}

/** A device seen recently that at least one driver recognised (times on the elapsed clock). */
data class DetectedScale(
    val address: String,
    val name: String?,
    val rssi: Int,
    val lastSeenMillis: Long,
    val matches: List<ScaleMatch>,
    /** When this device started being seen (it is forgotten after the detector's TTL). */
    val firstSeenMillis: Long = lastSeenMillis,
    /** Advertisements received since [firstSeenMillis]. */
    val adverts: Int = 1,
) {
    val best: ScaleMatch get() = matches.first()
    val highMatches: List<ScaleMatch> get() = matches.filter { it.confidence == MatchConfidence.HIGH }
}

/** What happened with an advertisement in [ScaleDetector]. */
sealed interface DetectionResult {
    data object Ignored : DetectionResult
    data object Updated : DetectionResult

    /** No scale configured and this device is the only unambiguous match: configure it. */
    data class AutoConfigure(val driver: ScaleDriver, val address: String, val name: String?) : DetectionResult

    /**
     * Same certainty, but the driver connects to the scale (and may register a user on it): it is
     * only offered to the user ("¿Es tu báscula?"), never set up silently.
     */
    data class Suggest(val driver: ScaleDriver, val address: String, val name: String?) : DetectionResult
}

/**
 * Pure detection bookkeeping fed with every advertisement: keeps the "detected scales" list
 * (fresh for [ttlMs], on the elapsed clock) and decides when to auto-configure.
 *
 * A device is taken only when no scale is configured, it has exactly one HIGH-confidence match,
 * it is close ([rssiGate]), it has been heard [minAdverts] times over at least [settleMs] (not a
 * one-off passer-by), no other fresh, non-dismissed device has a HIGH match (a neighbour's scale)
 * and the user did not remove it before. Passive drivers are configured; drivers that connect
 * or register users are only suggested.
 */
class ScaleDetector(
    private val registry: ScaleRegistry,
    private val ttlMs: Long = 120_000L,
    /** Same device re-matched at most this often (matching ~70 drivers on every advertisement is wasteful). */
    private val rematchIntervalMs: Long = 2_000L,
    private val rssiGate: Int = -70,
    private val settleMs: Long = 3_000L,
    private val minAdverts: Int = 2,
) {
    private val seen = LinkedHashMap<String, DetectedScale>()
    private val lastMatchAt = LinkedHashMap<String, Long>()
    private val decided = HashSet<String>()

    fun detected(now: Long): List<DetectedScale> {
        prune(now)
        return seen.values.sortedByDescending { it.rssi }
    }

    /** The matches last computed for [address] (refreshed at most every [rematchIntervalMs]). */
    fun matchesFor(address: String): List<ScaleMatch> = seen[address.uppercase(Locale.ROOT)]?.matches.orEmpty()

    fun onAdvertisement(
        adv: AdvertisementInfo,
        now: Long,
        configured: List<ConfiguredScale>,
        dismissed: Set<String>,
    ): DetectionResult {
        val key = adv.address.uppercase(Locale.ROOT)
        // An entry older than the TTL (not pruned yet) is a new sighting.
        val previous = seen[key]?.takeIf { now - it.lastSeenMillis <= ttlMs }
        val last = lastMatchAt[key]
        val matches = if (last != null && now - last < rematchIntervalMs) {
            previous?.matches ?: return DetectionResult.Ignored
        } else {
            lastMatchAt.remove(key)
            lastMatchAt[key] = now
            while (lastMatchAt.size > MAX_TRACKED) lastMatchAt.remove(lastMatchAt.keys.first())
            registry.detect(adv)
        }
        if (matches.isEmpty()) {
            // A single advertisement without the recognisable part (e.g. no scan response) does
            // not make a known device disappear: it simply ages out after the TTL.
            return DetectionResult.Ignored
        }
        val entry = DetectedScale(
            address = adv.address,
            name = adv.name?.takeIf { it.isNotBlank() } ?: previous?.name,
            rssi = adv.rssi,
            lastSeenMillis = now,
            matches = matches,
            firstSeenMillis = previous?.firstSeenMillis ?: now,
            adverts = (previous?.adverts ?: 0) + 1,
        )
        seen[key] = entry
        prune(now)

        if (configured.isNotEmpty() || key in dismissed || key in decided) return DetectionResult.Updated
        val high = entry.highMatches
        if (high.size != 1 || adv.rssi < rssiGate) return DetectionResult.Updated
        if (entry.adverts < minAdverts || now - entry.firstSeenMillis < settleMs) return DetectionResult.Updated
        val othersWithHigh = seen.values.any {
            it !== entry && it.address.uppercase(Locale.ROOT) !in dismissed && it.highMatches.isNotEmpty()
        }
        if (othersWithHigh) return DetectionResult.Updated
        decided += key
        val driver = high.single().driver
        return if (driver is PassiveScaleDriver && !driver.capabilities.userRegistration) {
            DetectionResult.AutoConfigure(driver, adv.address, entry.name)
        } else {
            DetectionResult.Suggest(driver, adv.address, entry.name)
        }
    }

    fun forget(address: String) {
        val key = address.uppercase(Locale.ROOT)
        seen.remove(key)
        lastMatchAt.remove(key)
        decided.remove(key)
    }

    private fun prune(now: Long) {
        val it = seen.entries.iterator()
        while (it.hasNext()) {
            if (now - it.next().value.lastSeenMillis > ttlMs) it.remove()
        }
    }

    private companion object {
        const val MAX_TRACKED = 512
    }
}
