package com.enjoyyourtime.globalfitness.ui.ads

import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.enjoyyourtime.globalfitness.BuildConfig
import com.enjoyyourtime.globalfitness.GlobalFitnessApp
import com.google.android.gms.ads.AdListener
import com.google.android.gms.ads.AdRequest
import com.google.android.gms.ads.AdSize
import com.google.android.gms.ads.AdView
import com.google.android.gms.ads.LoadAdError

/**
 * Anchored adaptive banner sized to the container width. Takes no space until an ad has loaded,
 * and renders nothing for premium users, without ad consent, or when loading fails.
 * Never place it on the sign-in, purchase or consent screens.
 */
@Composable
fun AdBanner(modifier: Modifier = Modifier) {
    val ads = (LocalContext.current.applicationContext as GlobalFitnessApp).container.ads
    val allowed by ads.bannerAllowed.collectAsStateWithLifecycle()
    if (!allowed) return
    BoxWithConstraints(modifier.fillMaxWidth()) {
        val widthDp = maxWidth.value.toInt()
        if (widthDp > 0) {
            key(widthDp) { AnchoredBanner(widthDp) }
        }
    }
}

private enum class BannerState { LOADING, LOADED, FAILED }

@Composable
private fun AnchoredBanner(widthDp: Int) {
    val context = LocalContext.current
    val adSize = remember(widthDp) { AdSize.getLargeAnchoredAdaptiveBannerAdSize(context, widthDp) }
    var state by remember { mutableStateOf(BannerState.LOADING) }
    var adView by remember { mutableStateOf<AdView?>(null) }
    if (state == BannerState.FAILED) return

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, adView) {
        val view = adView
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> view?.resume()
                Lifecycle.Event.ON_PAUSE -> view?.pause()
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    AndroidView(
        modifier = Modifier
            .fillMaxWidth()
            .height(if (state == BannerState.LOADED) adSize.height.dp else 0.dp),
        factory = { ctx ->
            AdView(ctx).apply {
                setAdSize(adSize)
                adUnitId = BuildConfig.ADMOB_BANNER_ID
                adListener = object : AdListener() {
                    override fun onAdLoaded() {
                        state = BannerState.LOADED
                    }

                    override fun onAdFailedToLoad(error: LoadAdError) {
                        state = BannerState.FAILED
                    }
                }
                // No targeting, keywords or custom data: health and body data never reach ad requests.
                loadAd(AdRequest.Builder().build())
                adView = this
            }
        },
        onRelease = { it.destroy() },
    )
}
