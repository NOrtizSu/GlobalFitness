package com.enjoyyourtime.globalfitness.billing

import com.enjoyyourtime.globalfitness.billing.EntitlementEvent.Acknowledged
import com.enjoyyourtime.globalfitness.billing.EntitlementEvent.PurchasesQueried
import com.enjoyyourtime.globalfitness.billing.EntitlementEvent.PurchasesUpdated
import com.enjoyyourtime.globalfitness.billing.EntitlementEvent.QueryFailed
import com.enjoyyourtime.globalfitness.billing.EntitlementEvent.SimulationChanged
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EntitlementsTest {
    private val removeAds = listOf(Entitlements.REMOVE_ADS)

    private fun purchased(acknowledged: Boolean = true) = PurchaseInfo(removeAds, PurchaseInfo.State.PURCHASED, acknowledged)
    private fun pending() = PurchaseInfo(removeAds, PurchaseInfo.State.PENDING, false)

    private fun EntitlementState.on(event: EntitlementEvent) = Entitlements.reduce(this, event)

    @Test
    fun startsFromTheCacheSoOfflineUsersStayAdFree() {
        assertTrue(EntitlementState(cachedPremium = true).isPremium)
        assertFalse(EntitlementState(cachedPremium = false).isPremium)
        assertTrue(EntitlementState(cachedPremium = true).on(QueryFailed).isPremium)
    }

    @Test
    fun purchaseMakesPremiumAndNeedsAcknowledge() {
        val s = EntitlementState().on(PurchasesUpdated(listOf(purchased(acknowledged = false))))
        assertEquals(Ownership.OWNED, s.ownership)
        assertTrue(s.isPremium)
        assertTrue(s.cachedPremium)
        assertTrue(s.needsAcknowledge)

        val acked = s.on(Acknowledged)
        assertFalse(acked.needsAcknowledge)
        assertTrue(acked.isPremium)
    }

    @Test
    fun pendingPurchaseIsNotPremiumYet() {
        val s = EntitlementState().on(PurchasesUpdated(listOf(pending())))
        assertEquals(Ownership.PENDING, s.ownership)
        assertFalse(s.isPremium)

        val completed = s.on(PurchasesUpdated(listOf(purchased(acknowledged = false))))
        assertTrue(completed.isPremium)
    }

    @Test
    fun restoreFindsAnExistingPurchase() {
        val s = EntitlementState().on(PurchasesQueried(listOf(purchased())))
        assertEquals(Ownership.OWNED, s.ownership)
        assertFalse(s.needsAcknowledge)
    }

    @Test
    fun authoritativeQueryWithoutThePurchaseRevokesIt() {
        // Refunded / revoked: Play no longer reports it.
        val s = EntitlementState(ownership = Ownership.OWNED, cachedPremium = true).on(PurchasesQueried(emptyList()))
        assertEquals(Ownership.NOT_OWNED, s.ownership)
        assertFalse(s.isPremium)
        assertFalse(s.cachedPremium)
    }

    @Test
    fun listenerUpdateWithoutOurProductChangesNothing() {
        val owned = EntitlementState(ownership = Ownership.OWNED, cachedPremium = true)
        assertEquals(owned, owned.on(PurchasesUpdated(emptyList())))
        val other = PurchaseInfo(listOf("something_else"), PurchaseInfo.State.PURCHASED, true)
        assertEquals(owned, owned.on(PurchasesUpdated(listOf(other))))
    }

    @Test
    fun otherProductsDoNotGrantPremium() {
        val other = PurchaseInfo(listOf("something_else"), PurchaseInfo.State.PURCHASED, true)
        val s = EntitlementState().on(PurchasesQueried(listOf(other)))
        assertEquals(Ownership.NOT_OWNED, s.ownership)
        assertFalse(s.isPremium)
    }

    @Test
    fun simulationOverridesForDebugTesting() {
        val s = EntitlementState().on(SimulationChanged(true))
        assertTrue(s.isPremium)
        assertEquals(Ownership.UNKNOWN, s.ownership)
        assertFalse(s.on(SimulationChanged(false)).isPremium)
    }
}
