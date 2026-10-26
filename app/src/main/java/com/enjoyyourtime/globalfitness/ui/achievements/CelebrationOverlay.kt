package com.enjoyyourtime.globalfitness.ui.achievements

import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalActivity
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.enjoyyourtime.globalfitness.GlobalFitnessApp
import com.enjoyyourtime.globalfitness.R
import com.enjoyyourtime.globalfitness.ads.AdSuppression
import com.enjoyyourtime.globalfitness.training.domain.Celebration
import com.enjoyyourtime.globalfitness.training.model.LoadUnit
import com.enjoyyourtime.globalfitness.ui.Fmt
import com.enjoyyourtime.globalfitness.ui.account.SuppressInterstitials
import com.enjoyyourtime.globalfitness.ui.theme.AppMotion
import com.enjoyyourtime.globalfitness.ui.training.trainingViewModel
import kotlin.math.cos
import kotlin.random.Random

/** Most levels one card lists; the rest are counted ("+2 more"). */
private const val LISTED = 3
private const val CONFETTI_MS = 2_200
private const val CONFETTI_PIECES = 120

/**
 * The celebration of new achievement levels over [content] (the app's pages: hosted once by the app
 * root, above the pages and below dialogs): confetti, a card with the medal and the congratulation,
 * "View achievements" and "Close". Several levels reached together share one card. Levels wait
 * while something holds them (see [com.enjoyyourtime.globalfitness.ui.training.TrainingViewModel.celebration]).
 * Modal for TalkBack too: while it shows, the pages are out of the accessibility tree (accessibility
 * actions do not go through the scrim's touch handling). Interstitials and the rating card stay away
 * while it shows; closing it is a good moment to ask for a rating
 * ([com.enjoyyourtime.globalfitness.review.ReviewPrompter.onGoodMoment]).
 */
@Composable
fun CelebrationHost(onOpenAchievements: () -> Unit, content: @Composable () -> Unit) {
    val vm = trainingViewModel()
    val pending by vm.celebration.collectAsStateWithLifecycle()
    val waiting by vm.celebrationWaiting.collectAsStateWithLifecycle()
    val unit by vm.loadUnit.collectAsStateWithLifecycle()
    // The last levels shown stay on the card while it fades out after it is closed.
    var last by remember { mutableStateOf(emptyList<Celebration>()) }
    val shown = pending.ifEmpty { last }
    val visible = pending.isNotEmpty()
    // Closed once: a second tap, or Back while the card fades out, does nothing more.
    var closed by remember(pending) { mutableStateOf(false) }
    val haptic = LocalHapticFeedback.current
    val activity = LocalActivity.current
    val reviews = (LocalContext.current.applicationContext as GlobalFitnessApp).container.reviews

    // No interstitial or rating card over the celebration, nor just before it (while it waits behind a summary).
    if (waiting || visible) SuppressInterstitials(AdSuppression.CELEBRATION)

    LaunchedEffect(pending) {
        if (pending.isEmpty()) return@LaunchedEffect
        last = pending
        vm.onCelebrationShown(pending)
        haptic.performHapticFeedback(HapticFeedbackType.Confirm)
    }

    val close = { then: () -> Unit ->
        if (visible && !closed) {
            closed = true
            vm.dismissCelebration(shown)
            then()
        }
    }
    Box(Modifier.fillMaxSize()) {
        Box(Modifier.fillMaxSize().then(if (visible) Modifier.clearAndSetSemantics { } else Modifier)) { content() }
        AnimatedVisibility(visible = visible, enter = fadeIn(AppMotion.effects()), exit = fadeOut(AppMotion.effects())) {
            CelebrationOverlay(
                items = shown,
                unit = unit,
                active = visible && !closed,
                // A good moment to ask for a rating (the policy decides; not when the user goes on to the page).
                onClose = { close { activity?.let(reviews::onGoodMoment) } },
                onOpen = { close(onOpenAchievements) },
            )
        }
    }
}

@Composable
private fun CelebrationOverlay(items: List<Celebration>, unit: LoadUnit, active: Boolean, onClose: () -> Unit, onOpen: () -> Unit) {
    if (items.isEmpty()) return
    val motion = rememberMotionEnabled()
    val close by rememberUpdatedState(onClose)
    BackHandler(enabled = active, onBack = onClose)
    Box(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.scrim.copy(alpha = 0.5f))
            // A tap outside the card closes it; nothing below gets touches meanwhile (fading out included).
            .pointerInput(Unit) { detectTapGestures { close() } },
        contentAlignment = Alignment.Center,
    ) {
        CelebrationCard(items, unit, motion, onClose, onOpen)
        if (motion) Confetti(seed = items.first().key.hashCode(), modifier = Modifier.fillMaxSize())
    }
}

@Composable
private fun CelebrationCard(items: List<Celebration>, unit: LoadUnit, motion: Boolean, onClose: () -> Unit, onOpen: () -> Unit) {
    val first = items.first()
    val announce = stringResource(R.string.celebration_announce)
    val pop = remember(first.key) { Animatable(if (motion) 0.6f else 1f) }
    LaunchedEffect(first.key) {
        if (motion) pop.animateTo(1f, spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessLow))
    }
    // TalkBack announces the pane, then reads the congratulation from its title on.
    val titleFocus = remember { FocusRequester() }
    LaunchedEffect(first.key) { titleFocus.requestFocus() }
    Surface(
        shape = MaterialTheme.shapes.extraLarge,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        tonalElevation = 6.dp,
        shadowElevation = 12.dp,
        modifier = Modifier
            .safeDrawingPadding()
            .padding(24.dp)
            .widthIn(max = 420.dp)
            .fillMaxWidth()
            // Taps on the card are its own (they must not close it).
            .pointerInput(Unit) { detectTapGestures { } }
            .semantics { paneTitle = announce },
    ) {
        Column(
            Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 24.dp, vertical = 28.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Medal(
                first.achievement,
                first.level,
                size = 104.dp,
                modifier = Modifier.graphicsLayer {
                    scaleX = pop.value
                    scaleY = pop.value
                },
            )
            Text(
                stringResource(R.string.celebration_title),
                style = MaterialTheme.typography.headlineSmall,
                textAlign = TextAlign.Center,
                modifier = Modifier.focusRequester(titleFocus).focusable().semantics { heading() },
            )
            if (items.size == 1) {
                LevelLine(first, Modifier.fillMaxWidth(), center = true)
                Text(
                    AchievementTexts.done(first, unit),
                    style = MaterialTheme.typography.bodyLarge,
                    textAlign = TextAlign.Center,
                )
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth().padding(top = 4.dp)) {
                    items.take(LISTED).forEach { c ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Medal(c.achievement, c.level, size = 44.dp)
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                LevelLine(c)
                                Text(AchievementTexts.done(c, unit), style = MaterialTheme.typography.bodyMedium)
                            }
                        }
                    }
                    if (items.size > LISTED) {
                        val more = items.size - LISTED
                        Text(
                            Fmt.plural(R.plurals.celebration_more, more, Fmt.count(more.toLong())),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            ) {
                TextButton(onClick = onClose, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.action_close)) }
                Button(onClick = onOpen, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.celebration_view)) }
            }
        }
    }
}

/** "Workouts · Level 3". */
@Composable
private fun LevelLine(c: Celebration, modifier: Modifier = Modifier, center: Boolean = false) {
    Text(
        stringResource(R.string.celebration_level, stringResource(AchievementTexts.title(c.achievement)), Fmt.count(c.level.toLong())),
        style = MaterialTheme.typography.titleMedium,
        color = MaterialTheme.colorScheme.primary,
        textAlign = if (center) TextAlign.Center else TextAlign.Start,
        modifier = modifier,
    )
}

/**
 * False when the user turned animations off (developer options or the accessibility setting
 * "Remove animations", both set the animator duration scale to 0): no confetti, no bounce.
 */
@Composable
private fun rememberMotionEnabled(): Boolean {
    val resolver = LocalContext.current.contentResolver
    return remember(resolver) { Settings.Global.getFloat(resolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) > 0f }
}

/** One piece of confetti: start (share of the width / height), speed (heights per second), spin. */
private class Piece(
    val x0: Float,
    val y0: Float,
    val vx: Float,
    val vy: Float,
    val angle: Float,
    val spin: Float,
    val flip: Float,
    val width: Float,
    val height: Float,
    val color: Color,
    val round: Boolean,
)

/**
 * A burst of confetti from above the card that falls off the screen in [CONFETTI_MS] (slower with
 * a larger animator duration scale). Drawn only: it never takes touches.
 */
@Composable
private fun Confetti(seed: Int, modifier: Modifier = Modifier) {
    val palette = MedalColors.tiers + listOf(MaterialTheme.colorScheme.primary, MaterialTheme.colorScheme.tertiary)
    val pieces = remember(seed) {
        val random = Random(seed)
        List(CONFETTI_PIECES) {
            Piece(
                x0 = 0.5f + random.between(-0.12f, 0.12f),
                y0 = 0.32f + random.between(-0.04f, 0.04f),
                vx = random.between(-0.55f, 0.55f),
                vy = random.between(-1.35f, -0.55f),
                angle = random.between(0f, 360f),
                spin = random.between(-540f, 540f),
                flip = random.between(4f, 12f),
                width = random.between(7f, 13f),
                height = random.between(4f, 7f),
                color = palette[random.nextInt(palette.size)],
                round = random.nextInt(4) == 0,
            )
        }
    }
    val progress = remember(seed) { Animatable(0f) }
    LaunchedEffect(seed) { progress.animateTo(1f, tween(CONFETTI_MS, easing = LinearEasing)) }
    Canvas(modifier) {
        val t = progress.value
        if (t >= 1f) return@Canvas
        val seconds = t * CONFETTI_MS / 1000f
        val h = size.height
        val alpha = if (t < 0.7f) 1f else (1f - (t - 0.7f) / 0.3f)
        val px = density
        for (p in pieces) {
            val x = size.width * p.x0 + p.vx * h * seconds
            val y = h * p.y0 + p.vy * h * seconds + 0.5f * GRAVITY * h * seconds * seconds
            val w = p.width * px
            val ph = p.height * px
            rotate(p.angle + p.spin * seconds, pivot = Offset(x, y)) {
                // A paper strip seen turning: its width shrinks and grows.
                scale(scaleX = cos(p.flip * seconds), scaleY = 1f, pivot = Offset(x, y)) {
                    if (p.round) {
                        drawCircle(p.color, radius = ph, center = Offset(x, y), alpha = alpha)
                    } else {
                        drawRect(p.color, topLeft = Offset(x - w / 2, y - ph / 2), size = Size(w, ph), alpha = alpha)
                    }
                }
            }
        }
    }
}

/** Heights per second², so the burst rises a little and falls off the screen in about two seconds. */
private const val GRAVITY = 1.9f

private fun Random.between(from: Float, until: Float): Float = from + nextFloat() * (until - from)
