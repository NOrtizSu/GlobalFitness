package com.enjoyyourtime.globalfitness.ads

import org.junit.Assert.assertEquals
import org.junit.Test

class InterstitialPolicyTest {
    private val policy = InterstitialPolicy()
    private val second = 1_000L
    private val minute = 60 * second

    /** Fake monotonic clock + the state the controller keeps. */
    private class Session(var now: Long = 0L) {
        var sessionStartedAt = 0L
        var lastShownAt: Long? = null
        var lastMeasurementSavedAt: Long? = null
        var suppressed = false
        var premium = false
        var consent = true
        var loaded = true

        fun state() = InterstitialState(now, sessionStartedAt, lastShownAt, lastMeasurementSavedAt, suppressed, premium, consent, loaded)
    }

    private fun Session.tabChange(): InterstitialVerdict =
        policy.evaluate(state()).also { if (it == InterstitialVerdict.SHOW) lastShownAt = now }

    @Test
    fun neverInTheFirstMinuteOfASession() {
        val s = Session(now = 59 * second)
        assertEquals(InterstitialVerdict.SESSION_GRACE, s.tabChange())
        s.now = 60 * second
        assertEquals(InterstitialVerdict.SHOW, s.tabChange())
    }

    @Test
    fun atMostOnceEveryFourMinutes() {
        val s = Session(now = 2 * minute)
        assertEquals(InterstitialVerdict.SHOW, s.tabChange())
        s.now += 3 * minute + 59 * second
        assertEquals(InterstitialVerdict.FREQUENCY_CAP, s.tabChange())
        s.now += 1 * second
        assertEquals(InterstitialVerdict.SHOW, s.tabChange())
    }

    @Test
    fun notWithin30SecondsOfSavingAMeasurement() {
        val s = Session(now = 5 * minute)
        s.lastMeasurementSavedAt = s.now - 29 * second
        assertEquals(InterstitialVerdict.AFTER_MEASUREMENT, s.tabChange())
        s.now += 1 * second
        assertEquals(InterstitialVerdict.SHOW, s.tabChange())
    }

    @Test
    fun suppressedWhileSomethingImportantRuns() {
        val s = Session(now = 5 * minute).apply { suppressed = true }
        assertEquals(InterstitialVerdict.SUPPRESSED, s.tabChange())
    }

    @Test
    fun neverForPremiumOrWithoutConsent() {
        assertEquals(InterstitialVerdict.PREMIUM, Session(now = 5 * minute).apply { premium = true }.tabChange())
        assertEquals(InterstitialVerdict.NO_CONSENT, Session(now = 5 * minute).apply { consent = false }.tabChange())
    }

    @Test
    fun needsAPreloadedAd() {
        assertEquals(InterstitialVerdict.NOT_LOADED, Session(now = 5 * minute).apply { loaded = false }.tabChange())
    }

    @Test
    fun newSessionRestartsTheGracePeriod() {
        val s = Session(now = 10 * minute)
        s.sessionStartedAt = s.now - 10 * second // app came back to the foreground
        assertEquals(InterstitialVerdict.SESSION_GRACE, s.tabChange())
    }

    @Test
    fun premiumWinsOverEveryOtherReason() {
        val s = Session(now = 0).apply {
            premium = true
            consent = false
            suppressed = true
            loaded = false
        }
        assertEquals(InterstitialVerdict.PREMIUM, s.tabChange())
    }
}
