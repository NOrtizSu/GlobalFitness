package com.enjoyyourtime.globalfitness.ads

import android.app.Activity
import android.app.Application
import android.os.Bundle
import android.util.Log
import com.enjoyyourtime.globalfitness.R
import com.enjoyyourtime.globalfitness.UiText
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.ProcessLifecycleOwner
import com.enjoyyourtime.globalfitness.BuildConfig
import com.enjoyyourtime.globalfitness.MainActivity
import com.enjoyyourtime.globalfitness.billing.BillingRepository
import com.google.android.gms.ads.MobileAds
import com.google.android.ump.ConsentInformation
import com.google.android.ump.ConsentRequestParameters
import com.google.android.ump.UserMessagingPlatform
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.lang.ref.WeakReference
import java.util.concurrent.atomic.AtomicBoolean

/**
 * AdMob with Google's UMP consent (GDPR): the consent form runs when [MainActivity] resumes once
 * the onboarding is over (never over the welcome pages), the Mobile Ads SDK is initialized only
 * after the onboarding and once [ConsentInformation.canRequestAds] is true, and nothing is
 * requested for premium users or before Google Play has answered whether the user is premium
 * (see [BillingRepository.entitlementKnown], which has its own short timeout).
 */
class AdsManager(
    private val application: Application,
    private val scope: CoroutineScope,
    private val premium: StateFlow<Boolean>,
    /** False until the "remove ads" ownership is known (or a short timeout passed). */
    entitlementKnown: StateFlow<Boolean>,
    val suppression: AdSuppression,
    /** Emits when a measurement was saved (no interstitial in the next 30 s). */
    measurementSaved: Flow<Unit>,
    /** True while a scale weighing or a GATT scale session is in progress. */
    scaleBusy: Flow<Boolean>,
    /** False while the first-launch onboarding is on screen: no consent form, SDK or ads yet. */
    private val onboardingCompleted: StateFlow<Boolean>,
) {
    /** Premium, or not known yet: no ads in both cases. */
    private val isPremium: StateFlow<Boolean> = combine(premium, entitlementKnown) { p, known -> p || !known }
        .stateIn(scope, SharingStarted.Eagerly, premium.value || !entitlementKnown.value)

    private val consentInformation = UserMessagingPlatform.getConsentInformation(application)
    private val initStarted = AtomicBoolean(false)
    private var consentInFlight = false
    private var consentUpdatedThisProcess = false

    /** The resumed [MainActivity], if any (main thread only). */
    private var resumedMain: WeakReference<MainActivity>? = null

    private val _canRequestAds = MutableStateFlow(consentInformation.canRequestAds())
    val canRequestAds: StateFlow<Boolean> = _canRequestAds.asStateFlow()

    private val _privacyOptionsRequired = MutableStateFlow(privacyOptionsRequiredNow())

    /** Show an "Opciones de privacidad de anuncios" entry when true. */
    val privacyOptionsRequired: StateFlow<Boolean> = _privacyOptionsRequired.asStateFlow()

    private val _startupConsentDone = MutableStateFlow(false)

    /**
     * The start's consent step is over in this run: Google's form was closed or not needed, or the
     * user is premium and there is no form. The app's own one-time questions of the start wait for
     * it, so they never cover the form (offline, it waits for a later start).
     */
    val startupConsentDone: StateFlow<Boolean> = _startupConsentDone.asStateFlow()

    private val mobileAdsReady = MutableStateFlow(false)

    /** Consent obtained and SDK initialized. */
    val adsReady: StateFlow<Boolean> = combine(_canRequestAds, mobileAdsReady) { can, ready -> can && ready }
        .stateIn(scope, SharingStarted.Eagerly, false)

    /** A banner may be requested now. */
    val bannerAllowed: StateFlow<Boolean> = combine(adsReady, isPremium) { ready, premium -> ready && !premium }
        .stateIn(scope, SharingStarted.Eagerly, false)

    val interstitials = InterstitialController(
        context = application,
        scope = scope,
        adUnitId = BuildConfig.ADMOB_INTERSTITIAL_ID,
        isPremium = isPremium,
        adsReady = adsReady,
        suppression = suppression,
    )

    init {
        application.registerActivityLifecycleCallbacks(
            object : Application.ActivityLifecycleCallbacks {
                override fun onActivityResumed(activity: Activity) {
                    if (activity !is MainActivity) return
                    resumedMain = WeakReference(activity)
                    maybeGatherConsent()
                }

                override fun onActivityPaused(activity: Activity) {
                    if (resumedMain?.get() === activity) resumedMain = null
                }

                override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
                override fun onActivityStarted(activity: Activity) = Unit
                override fun onActivityStopped(activity: Activity) = Unit
                override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
                override fun onActivityDestroyed(activity: Activity) = Unit
            },
        )
        ProcessLifecycleOwner.get().lifecycle.addObserver(
            LifecycleEventObserver { _, event ->
                when (event) {
                    Lifecycle.Event.ON_START -> interstitials.onSessionStart()
                    Lifecycle.Event.ON_STOP -> interstitials.onSessionStop()
                    else -> Unit
                }
            },
        )
        // The onboarding just ended while the app is on screen: ask for consent now.
        scope.launch { onboardingCompleted.collect { done -> if (done) maybeGatherConsent() } }
        // Premium known while the app is on screen: no form, the step is over.
        scope.launch { premium.collect { p -> if (p) maybeGatherConsent() } }
        scope.launch {
            combine(_canRequestAds, isPremium, onboardingCompleted) { can, noAds, done -> can && !noAds && done }.first { it }
            initializeMobileAds()
        }
        scope.launch {
            adsReady.collect { ready -> if (ready) interstitials.preload() }
        }
        scope.launch {
            isPremium.collect { premium -> if (premium) interstitials.clear() }
        }
        scope.launch { measurementSaved.collect { interstitials.noteMeasurementSaved() } }
        scope.launch { scaleBusy.collect { suppression.set(AdSuppression.SCALE_MEASUREMENT, it) } }
    }

    /** Re-opens the consent choices ("Ad privacy options"). */
    fun showPrivacyOptions(activity: Activity, onDone: (error: UiText?) -> Unit) {
        UserMessagingPlatform.showPrivacyOptionsForm(activity) { formError ->
            refreshConsentState()
            onDone(formError?.let { UiText.res(R.string.ads_privacy_options_failed, it.message.orEmpty()) })
        }
    }

    /**
     * Only on the app's own screen (never over ad, sign-in or permission activities) and only
     * after the onboarding, so the consent form never covers the welcome pages. Premium users have
     * no form: their step is over at once (unless a form started before Google Play answered).
     */
    private fun maybeGatherConsent() {
        val activity = resumedMain?.get() ?: return
        if (!onboardingCompleted.value) return
        if (premium.value) {
            if (!consentInFlight) _startupConsentDone.value = true
            return
        }
        gatherConsent(activity)
    }

    /** UMP: refresh the consent status on every launch and show the form when required. */
    private fun gatherConsent(activity: Activity) {
        if (consentInFlight || consentUpdatedThisProcess) return
        consentInFlight = true
        suppression.set(AdSuppression.CONSENT, true)
        val params = ConsentRequestParameters.Builder().build()
        consentInformation.requestConsentInfoUpdate(
            activity,
            params,
            {
                UserMessagingPlatform.loadAndShowConsentFormIfRequired(activity) { formError ->
                    if (formError != null) Log.w(TAG, "Consent form: ${formError.message}")
                    consentUpdatedThisProcess = true
                    finishConsent()
                    _startupConsentDone.value = true
                }
            },
            { requestError ->
                // Offline or misconfigured: keep the previous decision and try again on the next resume.
                Log.w(TAG, "Consent info update failed: ${requestError.message}")
                finishConsent()
            },
        )
    }

    private fun finishConsent() {
        consentInFlight = false
        suppression.set(AdSuppression.CONSENT, false)
        refreshConsentState()
    }

    private fun refreshConsentState() {
        _canRequestAds.value = consentInformation.canRequestAds()
        _privacyOptionsRequired.value = privacyOptionsRequiredNow()
    }

    private fun privacyOptionsRequiredNow(): Boolean =
        consentInformation.privacyOptionsRequirementStatus == ConsentInformation.PrivacyOptionsRequirementStatus.REQUIRED

    private suspend fun initializeMobileAds() {
        if (!initStarted.compareAndSet(false, true)) return
        withContext(Dispatchers.IO) {
            MobileAds.initialize(application) {
                scope.launch { mobileAdsReady.value = true }
            }
        }
    }

    private companion object {
        const val TAG = "Ads"
    }
}
