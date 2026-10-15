package com.enjoyyourtime.globalfitness.ads

import android.app.Activity
import android.content.Context
import android.os.SystemClock
import android.util.Log
import com.enjoyyourtime.globalfitness.cloud.RetryPolicy
import com.google.android.gms.ads.AdError
import com.google.android.gms.ads.AdRequest
import com.google.android.gms.ads.FullScreenContentCallback
import com.google.android.gms.ads.LoadAdError
import com.google.android.gms.ads.interstitial.InterstitialAd
import com.google.android.gms.ads.interstitial.InterstitialAdLoadCallback
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * Preloads one interstitial and shows it only on a section/tab change when [InterstitialPolicy]
 * allows it. Main thread only. Ad requests never carry health or body data (no targeting at all).
 */
class InterstitialController(
    private val context: Context,
    private val scope: CoroutineScope,
    private val adUnitId: String,
    private val isPremium: StateFlow<Boolean>,
    /** Consent obtained and the Mobile Ads SDK initialized. */
    private val adsReady: StateFlow<Boolean>,
    private val suppression: AdSuppression,
    private val policy: InterstitialPolicy = InterstitialPolicy(),
    private val clock: () -> Long = SystemClock::elapsedRealtime,
) {
    private val loadRetry = RetryPolicy(baseDelayMs = 30_000, maxDelayMs = 10 * 60_000L, maxAttempts = Int.MAX_VALUE)
    private var ad: InterstitialAd? = null
    private var loadedAt = 0L
    private var loading = false
    private var showing = false
    private var failures = 0
    private var retryJob: Job? = null

    private var sessionStartedAt = clock()
    private var lastShownAt: Long? = null
    private var lastMeasurementSavedAt: Long? = null

    /** The app came to the foreground: no interstitial during the first minute. */
    fun onSessionStart() {
        sessionStartedAt = clock()
    }

    fun noteMeasurementSaved() {
        lastMeasurementSavedAt = clock()
    }

    /**
     * Call on every section/tab change. Shows the preloaded interstitial if the frequency policy
     * allows it; returns true when one was shown.
     */
    fun onSectionChanged(activity: Activity): Boolean {
        if (showing) return false
        if (ad != null && clock() - loadedAt > AD_EXPIRY_MS) ad = null
        val verdict = policy.evaluate(
            InterstitialState(
                now = clock(),
                sessionStartedAt = sessionStartedAt,
                lastShownAt = lastShownAt,
                lastMeasurementSavedAt = lastMeasurementSavedAt,
                suppressed = suppression.adsSuppressed.value,
                premium = isPremium.value,
                canRequestAds = adsReady.value,
                adLoaded = ad != null,
            ),
        )
        if (verdict == InterstitialVerdict.NOT_LOADED) preload()
        if (verdict != InterstitialVerdict.SHOW) return false
        val current = ad ?: return false
        ad = null
        showing = true
        current.fullScreenContentCallback = object : FullScreenContentCallback() {
            override fun onAdDismissedFullScreenContent() {
                showing = false
                preload()
            }

            override fun onAdFailedToShowFullScreenContent(error: AdError) {
                Log.w(TAG, "Interstitial failed to show: ${error.message}")
                showing = false
                preload()
            }
        }
        lastShownAt = clock()
        current.show(activity)
        return true
    }

    /** Loads the next interstitial if allowed and none is ready. */
    fun preload() {
        if (ad != null || loading || isPremium.value || !adsReady.value) return
        loading = true
        retryJob?.cancel()
        InterstitialAd.load(
            context,
            adUnitId,
            AdRequest.Builder().build(),
            object : InterstitialAdLoadCallback() {
                override fun onAdLoaded(loaded: InterstitialAd) {
                    loading = false
                    failures = 0
                    ad = loaded
                    loadedAt = clock()
                }

                override fun onAdFailedToLoad(error: LoadAdError) {
                    loading = false
                    failures++
                    Log.i(TAG, "Interstitial not loaded (${error.code}): ${error.message}")
                    retryJob = scope.launch {
                        delay(loadRetry.delayFor(failures))
                        preload()
                    }
                }
            },
        )
    }

    /** Drops the preloaded ad (e.g. the user became premium). */
    fun clear() {
        ad = null
        retryJob?.cancel()
    }

    private companion object {
        const val TAG = "Interstitial"

        /** Loaded interstitials expire after one hour. */
        const val AD_EXPIRY_MS = 55 * 60_000L
    }
}
