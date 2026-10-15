package com.enjoyyourtime.globalfitness.ads

/** Inputs of one "may we show an interstitial now?" decision. Times come from a monotonic clock. */
data class InterstitialState(
    val now: Long,
    val sessionStartedAt: Long,
    val lastShownAt: Long?,
    val lastMeasurementSavedAt: Long?,
    val suppressed: Boolean,
    val premium: Boolean,
    val canRequestAds: Boolean,
    val adLoaded: Boolean,
)

enum class InterstitialVerdict {
    SHOW,
    PREMIUM,
    NO_CONSENT,
    SUPPRESSED,
    SESSION_GRACE,
    AFTER_MEASUREMENT,
    FREQUENCY_CAP,
    NOT_LOADED,
}

/**
 * Frequency cap for interstitials, evaluated only on a section/tab change: never for premium users
 * or without ad consent, never while something important runs (see [AdSuppression]), never in the
 * first minute of a session, never within 30 s of saving a measurement, at most once every 4 minutes.
 */
class InterstitialPolicy(
    val minIntervalMs: Long = 4 * 60_000L,
    val sessionGraceMs: Long = 60_000L,
    val afterMeasurementMs: Long = 30_000L,
) {
    fun evaluate(s: InterstitialState): InterstitialVerdict = when {
        s.premium -> InterstitialVerdict.PREMIUM
        !s.canRequestAds -> InterstitialVerdict.NO_CONSENT
        s.suppressed -> InterstitialVerdict.SUPPRESSED
        s.now - s.sessionStartedAt < sessionGraceMs -> InterstitialVerdict.SESSION_GRACE
        s.lastMeasurementSavedAt != null && s.now - s.lastMeasurementSavedAt < afterMeasurementMs ->
            InterstitialVerdict.AFTER_MEASUREMENT
        s.lastShownAt != null && s.now - s.lastShownAt < minIntervalMs -> InterstitialVerdict.FREQUENCY_CAP
        !s.adLoaded -> InterstitialVerdict.NOT_LOADED
        else -> InterstitialVerdict.SHOW
    }

    /**
     * Delay before retrying a failed preload, or null for no automatic retry: never in the
     * background, never after "no fill" (asking again soon will not fill either), and at most
     * [maxLoadRetries] times per session with backoff. Later section changes still try again.
     */
    fun loadRetryDelay(noFill: Boolean, failures: Int, foreground: Boolean): Long? = when {
        !foreground || noFill || failures > maxLoadRetries -> null
        else -> (loadRetryBaseMs shl (failures - 1).coerceIn(0, 10)).coerceAtMost(loadRetryMaxMs)
    }

    val maxLoadRetries = 3
    val loadRetryBaseMs = 30_000L
    val loadRetryMaxMs = 5 * 60_000L
}
