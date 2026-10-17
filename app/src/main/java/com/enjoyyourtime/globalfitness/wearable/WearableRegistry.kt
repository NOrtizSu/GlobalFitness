package com.enjoyyourtime.globalfitness.wearable

import com.enjoyyourtime.globalfitness.scale.MatchConfidence
import com.enjoyyourtime.globalfitness.scale.ScaleRegistry
import java.util.Locale

/** One driver that recognised a device. [order] is the registry position (tie breaker). */
data class WearableMatch(val driver: WearableDriver, val confidence: MatchConfidence, val order: Int)

/**
 * All supported wearable drivers. Ranking: highest confidence first; on ties the earlier driver
 * wins (model-specific drivers are listed before family-wide and generic ones).
 */
class WearableRegistry(val drivers: List<WearableDriver>) {
    private val byId: Map<String, WearableDriver> = drivers.associateBy { it.id }

    init {
        require(byId.size == drivers.size) { "Duplicate driver ids" }
    }

    fun find(id: String?): WearableDriver? = id?.let { byId[it] }

    /** Every driver claiming [device], best first. */
    fun detect(device: WearableSighting): List<WearableMatch> =
        drivers.mapIndexedNotNull { index, driver ->
            safeMatch(driver, device)?.let { WearableMatch(driver, it, index) }
        }.sortedWith(compareByDescending<WearableMatch> { it.confidence }.thenBy { it.order })

    fun best(device: WearableSighting): WearableMatch? = detect(device).firstOrNull()

    /** Accent- and case-insensitive catalog search over name, brand, family and keywords. */
    fun search(query: String): List<WearableDriver> {
        val q = ScaleRegistry.normalize(query)
        val sorted = drivers.sortedWith(
            compareBy<WearableDriver>({ !it.verified }, { ScaleRegistry.normalize(it.brand) }, { ScaleRegistry.normalize(it.displayName) }),
        )
        if (q.isEmpty()) return sorted
        val terms = q.split(' ').filter { it.isNotEmpty() }
        return sorted.filter { d ->
            val haystack = ScaleRegistry.normalize(
                (listOf(d.displayName, d.brand, d.id, d.family.name.lowercase(Locale.ROOT).replace('_', ' ')) + d.keywords).joinToString(" "),
            )
            terms.all { it in haystack }
        }
    }

    companion object {
        /** A driver bug must never break detection: anything but a VM error counts as "no match". */
        fun safeMatch(driver: WearableDriver, device: WearableSighting): MatchConfidence? =
            try {
                driver.match(device)
            } catch (e: VirtualMachineError) {
                throw e
            } catch (e: Throwable) {
                null
            }
    }
}
