package com.enjoyyourtime.globalfitness.ui.navigation

import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Room that a sub-page's own bottom bar (a workout's rest timer, a picker button) takes at the
 * bottom of the screen. The app-wide snackbar ("Measurement saved" while a workout is open) is
 * lifted above it instead of covering it. Several pages can be on screen during a transition: the
 * tallest bar counts.
 */
@Stable
class SnackbarClearance {
    private val heights = mutableStateMapOf<Any, Dp>()

    val bottom: Dp get() = heights.values.maxOrNull() ?: 0.dp

    internal fun set(owner: Any, height: Dp) {
        heights[owner] = height
    }

    internal fun clear(owner: Any) {
        heights.remove(owner)
    }
}

/** Provided by the main scaffold; a default instance (nobody reads it) outside of it. */
val LocalSnackbarClearance = staticCompositionLocalOf { SnackbarClearance() }

/**
 * Wraps a sub-page's bottom bar so the app-wide snackbar appears above it while the page is shown
 * (e.g. `bottomBar = { ReserveAppSnackbarSpace { RestTimerBar() } }`).
 */
@Composable
fun ReserveAppSnackbarSpace(content: @Composable () -> Unit) {
    val clearance = LocalSnackbarClearance.current
    val density = LocalDensity.current
    val owner = remember { Any() }
    DisposableEffect(clearance, owner) { onDispose { clearance.clear(owner) } }
    Box(Modifier.onSizeChanged { clearance.set(owner, with(density) { it.height.toDp() }) }) { content() }
}
