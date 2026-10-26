package com.enjoyyourtime.globalfitness.ads

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Lets any feature hold interstitials off while something important is on screen. Each feature
 * sets its own reason on and off; ads are suppressed while at least one reason is active.
 *
 * ```
 * container.adSuppression.set(AdSuppression.GATT_SESSION, active = true)
 * ...
 * container.adSuppression.set(AdSuppression.GATT_SESSION, active = false)
 * ```
 */
class AdSuppression {
    private val _reasons = MutableStateFlow<Set<String>>(emptySet())
    val reasons: StateFlow<Set<String>> = _reasons.asStateFlow()

    private val _adsSuppressed = MutableStateFlow(false)
    val adsSuppressed: StateFlow<Boolean> = _adsSuppressed.asStateFlow()

    fun set(reason: String, active: Boolean) {
        synchronized(this) {
            val next = if (active) _reasons.value + reason else _reasons.value - reason
            _reasons.value = next
            _adsSuppressed.value = next.isNotEmpty()
        }
    }

    companion object {
        const val SCALE_MEASUREMENT = "scale_measurement"
        const val GATT_SCALE_SESSION = "gatt_scale_session"
        const val BAND_LIVE_HEART_RATE = "band_live_heart_rate"

        /** A workout is in progress: the user is logging sets in the gym. */
        const val WORKOUT_IN_PROGRESS = "workout_in_progress"
        const val SIGN_IN = "sign_in"
        const val PURCHASE = "purchase"
        const val CONSENT = "consent"
        const val ACCOUNT_SCREEN = "account_screen"
        const val PREMIUM_SCREEN = "premium_screen"

        /** Google Play's rating card is being prepared or is on screen, and the minute after it. */
        const val REVIEW_FLOW = "review_flow"

        /** An achievement celebration is on screen (no interstitial or rating card over it). */
        const val CELEBRATION = "celebration"
    }
}
