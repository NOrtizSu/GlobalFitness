package com.enjoyyourtime.globalfitness.billing

/** Ownership of the "Quitar anuncios" product as last reported by Google Play. */
enum class Ownership { UNKNOWN, NOT_OWNED, PENDING, OWNED }

/** A purchase reduced to what the entitlement needs (pure, so it can be unit-tested). */
data class PurchaseInfo(
    val products: List<String>,
    val state: State,
    val acknowledged: Boolean,
) {
    enum class State { PURCHASED, PENDING, OTHER }
}

data class EntitlementState(
    val ownership: Ownership = Ownership.UNKNOWN,
    /** Last confirmed answer, cached on disk so offline users stay ad-free. */
    val cachedPremium: Boolean = false,
    /** Debug builds only: pretend the product is owned. */
    val simulated: Boolean = false,
    /** Owned but not acknowledged yet (Play refunds it after 3 days if never acknowledged). */
    val needsAcknowledge: Boolean = false,
) {
    /** Google Play is the source of truth; the cache covers the time until it answers. */
    val isPremium: Boolean
        get() = simulated || ownership == Ownership.OWNED || (ownership == Ownership.UNKNOWN && cachedPremium)
}

sealed interface EntitlementEvent {
    /** Full list from queryPurchasesAsync: authoritative, absence means not owned (refund/revoke). */
    data class PurchasesQueried(val purchases: List<PurchaseInfo>) : EntitlementEvent

    /** From the purchase listener: only the purchases that changed, absence means nothing. */
    data class PurchasesUpdated(val purchases: List<PurchaseInfo>) : EntitlementEvent

    data object Acknowledged : EntitlementEvent

    /** Play could not be queried: keep the cached answer. */
    data object QueryFailed : EntitlementEvent

    data class SimulationChanged(val enabled: Boolean) : EntitlementEvent
}

/** Pure state machine of the "remove ads" entitlement. */
object Entitlements {
    const val REMOVE_ADS = "remove_ads"

    fun reduce(state: EntitlementState, event: EntitlementEvent, productId: String = REMOVE_ADS): EntitlementState =
        when (event) {
            is EntitlementEvent.PurchasesQueried -> fromPurchases(state, event.purchases, productId, authoritative = true)
            is EntitlementEvent.PurchasesUpdated -> fromPurchases(state, event.purchases, productId, authoritative = false)
            EntitlementEvent.Acknowledged -> state.copy(needsAcknowledge = false)
            EntitlementEvent.QueryFailed -> state
            is EntitlementEvent.SimulationChanged -> state.copy(simulated = event.enabled)
        }

    private fun fromPurchases(
        state: EntitlementState,
        purchases: List<PurchaseInfo>,
        productId: String,
        authoritative: Boolean,
    ): EntitlementState {
        val relevant = purchases.filter { productId in it.products }
        val purchased = relevant.filter { it.state == PurchaseInfo.State.PURCHASED }
        val ownership = when {
            purchased.isNotEmpty() -> Ownership.OWNED
            relevant.any { it.state == PurchaseInfo.State.PENDING } -> Ownership.PENDING
            authoritative -> Ownership.NOT_OWNED
            else -> return state
        }
        return state.copy(
            ownership = ownership,
            cachedPremium = ownership == Ownership.OWNED,
            needsAcknowledge = purchased.any { !it.acknowledged },
        )
    }
}
