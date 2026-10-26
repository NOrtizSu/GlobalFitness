package com.enjoyyourtime.globalfitness.review

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.content.edit
import androidx.core.net.toUri
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import com.enjoyyourtime.globalfitness.ads.AdSuppression
import com.enjoyyourtime.globalfitness.training.model.Workout
import com.google.android.play.core.review.ReviewManagerFactory
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import java.lang.ref.WeakReference

/**
 * Asks for a Google Play rating with the In-App Review API (Google's own card, shown over the app)
 * at a good moment, when [ReviewPolicy] allows it. Google Play's policy forbids a screen or a
 * question of ours before the card, so there is none. Google decides whether the card really
 * appears (quota) and reports nothing back, so an ask is recorded when the flow is launched;
 * failures (no Play Store, offline…) are never shown. Interstitials are held off while the card is
 * prepared and shown and for a minute after it, and the card never opens over an interstitial.
 * State in its own SharedPreferences file (backed up: a restored phone keeps the asks). Main thread only.
 */
class ReviewPrompter(
    context: Context,
    private val scope: CoroutineScope,
    private val suppression: AdSuppression,
    /** True while an interstitial is on screen. */
    private val interstitialShowing: () -> Boolean,
    private val workouts: StateFlow<List<Workout>>,
    private val onboardingCompleted: StateFlow<Boolean>,
    private val policy: ReviewPolicy = ReviewPolicy(),
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val appContext = context.applicationContext
    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private var inFlight = false

    init {
        // The days of use count from the first launch with this code: for an existing user, the upgrade.
        if (!prefs.contains(KEY_FIRST_SEEN)) prefs.edit { putLong(KEY_FIRST_SEEN, clock()) }
    }

    /**
     * A good moment to ask: the user just closed an achievement celebration or a saved workout's
     * summary. Decided after a short pause, once what was closing has left the screen (and released
     * what it held off), and again when the card is ready. The [activity] is only referenced weakly
     * meanwhile (it may be destroyed while the card is requested): gone, there is no card.
     */
    fun onGoodMoment(activity: Activity) {
        if (inFlight) return
        inFlight = true
        val ref = WeakReference(activity)
        scope.launch {
            var held = false
            var launched = false
            try {
                delay(SETTLE_MS)
                if (!mayAsk(ref.get())) return@launch
                suppression.set(AdSuppression.REVIEW_FLOW, true)
                held = true
                val manager = ReviewManagerFactory.create(appContext)
                val info = manager.requestReviewFlow().await()
                val target = ref.get()
                if (target == null || !mayAsk(target)) return@launch
                record(history().asked(clock()))
                launched = true
                Log.i(TAG, "Launching the rating card")
                manager.launchReviewFlow(target, info).await()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.i(TAG, "No rating card: ${e.message}")
            } finally {
                inFlight = false
                if (held) release(afterMs = if (launched) AFTER_CARD_MS else 0L)
            }
        }
    }

    private fun mayAsk(activity: Activity?): Boolean {
        val resumed = (activity as? LifecycleOwner)?.lifecycle?.currentState?.isAtLeast(Lifecycle.State.RESUMED) == true
        val verdict = policy.evaluate(state())
        if (verdict != ReviewVerdict.ASK) Log.d(TAG, "Not asking now: $verdict")
        return policy.mayAsk(verdict, resumed)
    }

    private fun state(): ReviewState = ReviewState.of(
        now = clock(),
        history = history(),
        workouts = workouts.value,
        onboardingCompleted = onboardingCompleted.value,
        interstitialShowing = interstitialShowing(),
        suppressions = suppression.reasons.value,
    )

    private fun history() = ReviewHistory(
        firstSeenAt = prefs.getLong(KEY_FIRST_SEEN, clock()),
        lastAskedAt = if (prefs.contains(KEY_LAST_ASKED)) prefs.getLong(KEY_LAST_ASKED, 0L) else null,
        asks = prefs.getInt(KEY_ASKS, 0),
    )

    private fun record(h: ReviewHistory) = prefs.edit {
        h.lastAskedAt?.let { putLong(KEY_LAST_ASKED, it) }
        putInt(KEY_ASKS, h.asks)
    }

    private fun release(afterMs: Long) {
        if (afterMs <= 0L) {
            suppression.set(AdSuppression.REVIEW_FLOW, false)
        } else {
            // The first tab change after rating is not an interstitial.
            scope.launch {
                delay(afterMs)
                suppression.set(AdSuppression.REVIEW_FLOW, false)
            }
        }
    }

    private companion object {
        const val TAG = "Review"
        const val PREFS = "review"
        const val KEY_FIRST_SEEN = "first_seen_at"
        const val KEY_LAST_ASKED = "last_asked_at"
        const val KEY_ASKS = "asks"

        /** Lets a closing dialog or overlay leave the screen before the card is prepared. */
        const val SETTLE_MS = 700L
        const val AFTER_CARD_MS = 60_000L
    }
}

/**
 * Opens this app's Google Play page, the way the user rates it on their own (Profile → Rate):
 * the Play Store app, else the web page. In its own task, without history, so Back returns to the
 * app. False when neither can be opened (no Play Store and no browser).
 */
fun openPlayListing(context: Context): Boolean {
    val id = context.packageName
    val market = Intent(Intent.ACTION_VIEW, "market://details?id=$id".toUri()).setPackage(PLAY_STORE_PACKAGE)
    val web = Intent(Intent.ACTION_VIEW, "https://play.google.com/store/apps/details?id=$id".toUri())
    return listOf(market, web).any { intent ->
        try {
            context.startActivity(
                intent.addFlags(Intent.FLAG_ACTIVITY_NO_HISTORY or Intent.FLAG_ACTIVITY_NEW_DOCUMENT or Intent.FLAG_ACTIVITY_MULTIPLE_TASK),
            )
            true
        } catch (_: ActivityNotFoundException) {
            false
        } catch (_: SecurityException) {
            false
        }
    }
}

private const val PLAY_STORE_PACKAGE = "com.android.vending"
