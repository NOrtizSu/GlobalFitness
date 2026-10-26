package com.enjoyyourtime.globalfitness.ui.training.motion

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.MotionDurationScale
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.toArgb
import com.enjoyyourtime.globalfitness.training.model.Exercise
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * A looping animation of a mannequin doing [exercise] with its equipment, its primary muscles
 * highlighted. Square, themed (light/dark/dynamic colour), never mirrored in RTL. [playing] false
 * shows the start pose (paused, or the system "remove animations" setting, which the caller
 * checks so the user can still press play). While playing, a slower or faster system animator
 * duration scale slows or speeds the loop. Draws no semantics of its own: the caller describes it.
 *
 * Each frame is computed on the fly (the pose, the equipment, then flat shapes back to front) on
 * the main thread, at most about 60 times a second, so only one animation should be on screen at
 * a time. The first time an exercise is shown, the square that fits its whole loop is worked out
 * off the main thread; until then only the background shows.
 */
@Composable
fun ExerciseAnimation(exercise: Exercise, modifier: Modifier = Modifier, playing: Boolean = true) {
    val scheme = MaterialTheme.colorScheme
    val motion = remember(exercise.id, exercise.builtIn, exercise.kind, exercise.equipment, exercise.primary, exercise.secondary) {
        MotionCatalog.forExercise(exercise)
    }
    if (motion == null) {
        Box(modifier.aspectRatio(1f).background(scheme.surfaceContainerHigh))
        return
    }
    val colors = remember(scheme) { MotionColors(scheme) }
    val view = remember(motion) { mutableStateOf(motion.fittedView) }
    LaunchedEffect(motion) {
        if (view.value == null) view.value = withContext(Dispatchers.Default) { motion.view }
    }
    val time = remember(motion) { mutableFloatStateOf(0f) }
    LaunchedEffect(motion, playing) {
        time.floatValue = 0f
        if (!playing) return@LaunchedEffect
        val scale = coroutineContext[MotionDurationScale]?.scaleFactor ?: 1f
        val speed = if (scale > 0f) 1.0 / scale else 1.0
        val start = withFrameNanos { it }
        var shown = start
        val onFrame = { now: Long ->
            // A frame sooner than MIN_FRAME_NANOS after the last one shown is skipped: 90 and 120 Hz
            // screens get 45 and 60 frames a second, evenly spaced, and a 60 Hz screen every frame.
            if (now - shown >= MIN_FRAME_NANOS) {
                shown = now
                time.floatValue = ((now - start) / 1e9 * speed % motion.duration).toFloat()
            }
        }
        while (true) withFrameNanos(onFrame)
    }
    val muscles = exercise.primary
    // Built when a frame is drawn, and only again when the time moves on (not for every redraw).
    val scene = remember(motion, muscles) { derivedStateOf { motion.scene(time.floatValue, muscles) } }
    val path = remember { Path() }
    val strokes = remember { StrokeCache() }
    Canvas(modifier.aspectRatio(1f).clipToBounds()) {
        drawRect(colors.background)
        val fitted = view.value ?: return@Canvas
        drawScene(scene.value, fitted, colors, path, strokes)
    }
}

/** Whether [exercise] has an animation (built-in exercises do; custom ones only when their muscle and equipment have a typical movement). */
fun hasAnimation(exercise: Exercise): Boolean = MotionCatalog.sourceId(exercise) != null

/** The palette as Compose colours, from the theme's roles. */
private class MotionColors(scheme: ColorScheme) {
    private val palette = MotionPalette.of(
        background = scheme.surfaceContainerHigh.toArgb(),
        onSurface = scheme.onSurface.toArgb(),
        onSurfaceVariant = scheme.onSurfaceVariant.toArgb(),
        outline = scheme.outline.toArgb(),
        primary = scheme.primary.toArgb(),
        secondary = scheme.secondary.toArgb(),
        tertiary = scheme.tertiary.toArgb(),
    )
    val background = Color(palette.background)
    val floor = Color(palette.floor)
    private val inks = Ink.entries.map { Color(palette[it]) }

    operator fun get(ink: Ink): Color = inks[ink.ordinal]
}

/**
 * Strokes kept from frame to frame instead of one per shape: one per width and cap (the halos' width
 * and the few cord widths of a motion). A new canvas size brings new widths; the oldest set is dropped.
 */
private class StrokeCache {
    private val strokes = ArrayList<Stroke>(MAX_STROKES)

    operator fun get(width: Float, cap: StrokeCap): Stroke {
        for (i in strokes.indices) {
            val s = strokes[i]
            if (s.width == width && s.cap == cap) return s
        }
        if (strokes.size == MAX_STROKES) strokes.clear()
        return Stroke(width = width, cap = cap, join = StrokeJoin.Round).also { strokes += it }
    }

    private companion object {
        const val MAX_STROKES = 8
    }
}

/** Draws [scene] scaled so [view] fills the canvas (y up in the scene, down on screen), with the floor line first. */
private fun DrawScope.drawScene(scene: Scene, view: ViewBox, colors: MotionColors, path: Path, strokes: StrokeCache) {
    val s = size.width / view.size
    fun x(v: Float) = (v - view.left) * s
    fun y(v: Float) = (view.top - v) * s
    drawLine(colors.floor, Offset(0f, y(0f)), Offset(size.width, y(0f)), strokeWidth = FLOOR_WIDTH * s, cap = StrokeCap.Round)
    for (op in scene.ops) {
        val color = colors[op.ink]
        val pts = op.points
        if (op.type == OpType.CIRCLE) {
            drawCircle(color, radius = pts[2] * s, center = Offset(x(pts[0]), y(pts[1])))
            continue
        }
        path.reset()
        path.moveTo(x(pts[0]), y(pts[1]))
        var k = 2
        while (k < pts.size) {
            path.lineTo(x(pts[k]), y(pts[k + 1]))
            k += 2
        }
        when (op.type) {
            OpType.FILL -> {
                path.close()
                drawPath(path, color)
            }
            OpType.OUTLINE -> {
                path.close()
                drawPath(path, color, style = strokes[op.width * s, StrokeCap.Butt])
            }
            else -> drawPath(path, color, style = strokes[op.width * s, StrokeCap.Round])
        }
    }
}

/** Width of the floor line, in metres of the scene. */
private const val FLOOR_WIDTH = 0.012f

/** Shortest time between two frames shown, a little under a 60 Hz frame so none of those is skipped. */
private const val MIN_FRAME_NANOS = 15_000_000L
