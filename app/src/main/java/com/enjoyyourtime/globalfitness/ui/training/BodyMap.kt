package com.enjoyyourtime.globalfitness.ui.training

import android.graphics.Region
import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.asAndroidPath
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.isTraversalGroup
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.traversalIndex
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.toSize
import com.enjoyyourtime.globalfitness.R
import com.enjoyyourtime.globalfitness.training.domain.BodyShape
import com.enjoyyourtime.globalfitness.training.model.Muscle
import com.enjoyyourtime.globalfitness.ui.Fmt
import com.enjoyyourtime.globalfitness.ui.theme.AppMotion
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.math.PI
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

enum class BodySide { FRONT, BACK }

/**
 * One side of the human figure with every muscle filled with [fill] (null: the neutral body colour,
 * `MaterialTheme.colorScheme.surfaceVariant`, so "no data" legends can use that token). [selected]
 * gets a primary-coloured outline; [onMuscleClick] makes muscles tappable (the tap is tested against
 * the muscle's shape, with some tolerance around small ones). The figure keeps its own aspect ratio
 * inside [modifier]'s bounds and is centred there. Colour and selection changes animate.
 *
 * The figure never mirrors in right-to-left languages: anatomy has a fixed left and right (the
 * canvas, pointer coordinates and the placement of the accessibility nodes ignore layout direction).
 *
 * [describe] gives TalkBack each muscle's state (e.g. "12 sets, optimal"); the muscle name is added
 * by the map. With [describe] or [onMuscleClick] every visible muscle is its own accessibility node
 * (clickable when tappable, "selected" when it is [selected]), in [Muscle] order. Without either the
 * figure is one image announced with the names of the muscles that have a fill colour.
 *
 * [shape] draws the figure leaner or fuller and less or more muscular (see [BodyWarp]); null draws it
 * exactly as the artwork is, at the artwork's proportions. Shaped figures share one frame wide
 * enough for any shape, so changing the shape never moves the layout: the figure cross-fades.
 * [stateDescription] tells TalkBack what the figure shows when that can change (e.g. which moment
 * the shape is from).
 */
@Composable
fun BodyMap(
    side: BodySide,
    female: Boolean,
    fill: (Muscle) -> Color?,
    modifier: Modifier = Modifier,
    selected: Muscle? = null,
    onMuscleClick: ((Muscle) -> Unit)? = null,
    describe: ((Muscle) -> String?)? = null,
    shape: BodyShape? = null,
    stateDescription: String? = null,
) {
    val scheme = MaterialTheme.colorScheme
    val neutral = scheme.surfaceVariant
    // Outline around the figure and in the thin gaps between muscles: a step towards the text colour
    // from the body colour, so it separates in light and dark themes and with any dynamic palette.
    val contour = lerp(neutral, scheme.onSurfaceVariant, CONTOUR_MIX)
    // Head, hands, knees and feet recede a little behind the muscles.
    val nonMuscle = lerp(neutral, scheme.surface, 0.4f)
    val ring = scheme.primary

    // Called for every muscle in a fixed order, so the animations keep their state when the side changes.
    val fills = Muscle.entries.map { m -> animateColorAsState(fill(m) ?: neutral, AppMotion.effects(), label = "muscleFill") }
    val rings = Muscle.entries.map { m ->
        animateFloatAsState(if (m == selected) 1f else 0f, AppMotion.effects(), label = "muscleSelection")
    }

    val density = LocalDensity.current
    val contourWidth = with(density) { CONTOUR_WIDTH.toPx() }
    val ringWidth = with(density) { RING_WIDTH.toPx() }
    val touchTolerance = with(density) { TOUCH_TOLERANCE.toPx() }
    // Room for the outline and the selection ring, which are drawn outside the muscles' shapes.
    val inset = ringWidth

    val click by rememberUpdatedState(onMuscleClick)
    val tappable = onMuscleClick != null
    val figureLabel = stringResource(if (side == BodySide.FRONT) R.string.bodymap_figure_front else R.string.bodymap_figure_back)
    val separateNodes = tappable || describe != null

    val figures = rememberBodyFigures(female, shape)
    if (figures == null) {
        // The path data is parsed off the main thread the first time: an empty box of the figure's
        // proportions (the shaped figures' shared frame when shaped) keeps the layout still until it is ready.
        val aspect = if (shape == null) (if (female) FEMALE_ASPECT else MALE_ASPECT) else shapedFrame(female).aspectRatio
        Box(
            modifier.aspectRatio(aspect).clearAndSetSemantics {
                contentDescription = figureLabel
                if (stateDescription != null) this.stateDescription = stateDescription
            },
        )
        return
    }
    val figure = figures.figure(side)
    val frame = figures.frame(side)

    val a11y = if (separateNodes) {
        Modifier.semantics {
            contentDescription = figureLabel
            if (stateDescription != null) this.stateDescription = stateDescription
            isTraversalGroup = true
        }
    } else {
        val highlighted = figure.muscles.filter { fill(it) != null }.map { stringResource(it.labelRes) }
        val description = if (highlighted.isEmpty()) {
            figureLabel
        } else {
            stringResource(R.string.bodymap_figure_highlighted, figureLabel, Fmt.list(highlighted))
        }
        Modifier.clearAndSetSemantics {
            contentDescription = description
            if (stateDescription != null) this.stateDescription = stateDescription
            role = Role.Image
        }
    }

    val colors = FigureColors(
        contour = contour,
        separation = { f -> lerp(neutral, scheme.onSurfaceVariant, separationMix(f.shape)) },
        nonMuscle = nonMuscle,
        ring = ring,
        fill = { m -> fills[m.ordinal].value },
        ringAlpha = { m -> rings[m.ordinal].value },
    )
    Box(
        modifier
            .aspectRatio(figures.aspectRatio)
            .then(
                if (tappable) {
                    Modifier.pointerInput(figure, frame, inset, touchTolerance) {
                        detectTapGestures { offset ->
                            val fit = Fit(frame, size.toSize(), inset)
                            figure.muscleAt(fit.toFigure(offset), touchTolerance / fit.scale)?.let { click?.invoke(it) }
                        }
                    }
                } else {
                    Modifier
                },
            )
            .then(a11y),
    ) {
        if (shape == null) {
            FigureDrawing(figures, side, colors, contourWidth, ringWidth, inset, Modifier.matchParentSize())
        } else {
            // A new shape (another moment, or new data) fades in over the previous one.
            Crossfade(figures, Modifier.matchParentSize(), animationSpec = tween(SHAPE_FADE_MS), label = "bodyShape") { f ->
                FigureDrawing(f, side, colors, contourWidth, ringWidth, inset, Modifier.fillMaxSize())
            }
        }
        if (separateNodes) {
            val labels = figure.muscles.associateWith { m ->
                val name = stringResource(m.labelRes)
                val state = describe?.invoke(m)
                if (state.isNullOrBlank()) name else stringResource(R.string.bodymap_muscle_state, name, state)
            }
            MuscleNodes(figure, frame, inset, labels, selected, onMuscleClick)
        }
    }
}

/** Front and back side by side (the usual layout of the body map); the order follows the layout direction. */
@Composable
fun BodyMapPair(
    female: Boolean,
    fill: (Muscle) -> Color?,
    modifier: Modifier = Modifier,
    selected: Muscle? = null,
    onMuscleClick: ((Muscle) -> Unit)? = null,
    describe: ((Muscle) -> String?)? = null,
    /** "Front" / "Back" under each figure. Hidden from TalkBack: the figures announce their side. */
    showCaptions: Boolean = true,
    shape: BodyShape? = null,
    stateDescription: String? = null,
) {
    BoxWithConstraints(modifier) {
        // With a limited height (a fixed-size slot) the figure takes what the caption leaves and narrows
        // instead of being cut. Without one (a scrolling list) a weight would get no height at all, so the
        // figure simply takes the column's width and its own aspect ratio.
        val heightLimited = constraints.hasBoundedHeight
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            for (side in BodySide.entries) {
                Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                    val figure = if (heightLimited) Modifier.weight(1f, fill = false) else Modifier.fillMaxWidth()
                    BodyMap(side, female, fill, figure, selected, onMuscleClick, describe, shape, stateDescription)
                    if (showCaptions) {
                        Text(
                            stringResource(if (side == BodySide.FRONT) R.string.bodymap_front else R.string.bodymap_back),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.padding(top = 4.dp).clearAndSetSemantics { },
                        )
                    }
                }
            }
        }
    }
}

private val CONTOUR_WIDTH = 1.5.dp
private val RING_WIDTH = 2.dp
private val TOUCH_TOLERANCE = 12.dp

/** Outline colour: this share of the way from the body colour to the text colour. */
private const val CONTOUR_MIX = 0.22f

private const val SHAPE_FADE_MS = 450

/**
 * The outline's share for a shaped figure: the lines between muscles stand out more on a muscular
 * body and fade on a fuller one (less definition), within limits that keep the figure readable.
 */
internal fun separationMix(shape: BodyShape?): Float {
    if (shape == null) return CONTOUR_MIX
    val fat = BodyWarp.response(shape.fatness)
    val muscle = BodyWarp.response(shape.muscularity)
    return (CONTOUR_MIX * (1f + 0.55f * muscle - 0.4f * fat)).coerceIn(0.12f, 0.36f)
}

/** Colours of one figure; fills and rings are read when drawing, so their animations only redraw. */
private class FigureColors(
    val contour: Color,
    val separation: (BodyFigures) -> Color,
    val nonMuscle: Color,
    val ring: Color,
    val fill: (Muscle) -> Color,
    val ringAlpha: (Muscle) -> Float,
)

/** Draws [side] of [figures] fitted into the box (the outline first, then the parts, then the selection ring). */
@Composable
private fun FigureDrawing(
    figures: BodyFigures,
    side: BodySide,
    colors: FigureColors,
    contourWidth: Float,
    ringWidth: Float,
    inset: Float,
    modifier: Modifier,
) {
    val figure = figures.figure(side)
    val frame = figures.frame(side)
    val separation = colors.separation(figures)
    Spacer(
        modifier.drawWithCache {
            val fit = Fit(frame, size, inset)
            val contourStroke = Stroke(width = contourWidth / fit.scale, join = StrokeJoin.Round)
            val ringStroke = Stroke(width = ringWidth / fit.scale, join = StrokeJoin.Round)
            onDrawBehind {
                withTransform({
                    translate(fit.dx, fit.dy)
                    scale(fit.scale, fit.scale, pivot = Offset.Zero)
                }) {
                    // Every part stroked first: the strokes merge into the silhouette's outline and
                    // fill the narrow gaps between muscles, which then read as thin separating lines.
                    for (part in figure.parts) drawPath(part.path, separation, style = contourStroke)
                    for (part in figure.parts) {
                        val color = when {
                            part.muscle != null -> colors.fill(part.muscle)
                            part.isHair -> colors.contour
                            else -> colors.nonMuscle
                        }
                        drawPath(part.path, color)
                    }
                    for (part in figure.parts) {
                        val alpha = part.muscle?.let(colors.ringAlpha) ?: 0f
                        if (alpha > 0f) drawPath(part.path, colors.ring, alpha = alpha, style = ringStroke)
                    }
                }
            }
        },
    )
}

/** Width / height of the figures (both sides at one scale), for the placeholder shown while they parse. */
private const val MALE_ASPECT = 0.5f
private const val FEMALE_ASPECT = 0.44f

/**
 * Invisible boxes over each muscle's bounds that carry its accessibility node, so TalkBack can
 * explore the figure. Larger muscles are placed first: where boxes overlap (abs inside the
 * obliques' box) the smaller one is on top for explore-by-touch; swiping follows [Muscle] order.
 */
@Composable
private fun MuscleNodes(
    figure: BodyFigure,
    frame: Rect,
    inset: Float,
    labels: Map<Muscle, String>,
    selected: Muscle?,
    onTap: ((Muscle) -> Unit)?,
) {
    val order = remember(figure) { figure.muscles.sortedByDescending { figure.muscleBounds.getValue(it).let { r -> r.width * r.height } } }
    Layout(
        content = {
            for (m in order) {
                key(m) {
                    Spacer(
                        Modifier.semantics {
                            contentDescription = labels.getValue(m)
                            traversalIndex = m.ordinal.toFloat()
                            this.selected = m == selected
                            if (onTap != null) {
                                role = Role.Button
                                onClick {
                                    onTap(m)
                                    true
                                }
                            }
                        },
                    )
                }
            }
        },
    ) { measurables, constraints ->
        val fit = Fit(frame, Size(constraints.maxWidth.toFloat(), constraints.maxHeight.toFloat()), inset)
        val rects = order.map { fit.toScreen(figure.muscleBounds.getValue(it)) }
        val placeables = measurables.mapIndexed { i, measurable ->
            val r = rects[i]
            measurable.measure(Constraints.fixed(r.width.roundToInt().coerceAtLeast(1), r.height.roundToInt().coerceAtLeast(1)))
        }
        layout(constraints.maxWidth, constraints.maxHeight) {
            // place, not placeRelative: the figure is not mirrored in right-to-left layouts.
            placeables.forEachIndexed { i, p -> p.place(rects[i].left.roundToInt(), rects[i].top.roundToInt()) }
        }
    }
}

/** Maps figure units to pixels: [frame] scaled to fit [size] minus [inset] on every side, centred. */
private class Fit(frame: Rect, size: Size, inset: Float) {
    val scale = min((size.width - 2 * inset) / frame.width, (size.height - 2 * inset) / frame.height).coerceAtLeast(MIN_SCALE)
    val dx = size.width / 2 - frame.center.x * scale
    val dy = size.height / 2 - frame.center.y * scale

    fun toFigure(p: Offset) = Offset((p.x - dx) / scale, (p.y - dy) / scale)

    fun toScreen(r: Rect) = Rect(r.left * scale + dx, r.top * scale + dy, r.right * scale + dx, r.bottom * scale + dy)

    private companion object {
        const val MIN_SCALE = 1e-4f
    }
}

internal class DrawnPart(val muscle: Muscle?, val isHair: Boolean, val path: Path)

/**
 * One figure ready to draw and tap: the path data parsed once into Compose paths (never per frame)
 * and the tap regions. Built off the main thread (see [BodyFigureCache]); read-only afterwards.
 */
internal class BodyFigure(val parts: List<DrawnPart>) {
    val muscles: Set<Muscle> = Muscle.entries.filterTo(LinkedHashSet()) { m -> parts.any { it.muscle == m } }
    val bounds: Rect = parts.map { it.path.getBounds() }.reduce(::unionOf)
    val muscleBounds: Map<Muscle, Rect> = muscles.associateWith { m ->
        parts.filter { it.muscle == m }.map { it.path.getBounds() }.reduce(::unionOf)
    }

    /** Muscle shapes as regions in figure units, topmost first (built with the figure, not on the first tap). */
    private val regions: List<Pair<Muscle, Region>> =
        parts.asReversed().mapNotNull { part ->
            val muscle = part.muscle ?: return@mapNotNull null
            val b = part.path.getBounds()
            val clip = Region(floor(b.left).toInt() - 1, floor(b.top).toInt() - 1, ceil(b.right).toInt() + 1, ceil(b.bottom).toInt() + 1)
            muscle to Region().apply { setPath(part.path.asAndroidPath(), clip) }
        }

    /**
     * The muscle under [p], else the one most of a ring of probes around [p] lands on, trying rings
     * up to [tolerance] (figure units) away: a finger in the gap next to a small muscle still gets it.
     */
    fun muscleAt(p: Offset, tolerance: Float): Muscle? {
        hit(p.x, p.y)?.let { return it }
        for (ring in 1..PROBE_RINGS) {
            val r = tolerance * ring / PROBE_RINGS
            val votes = IntArray(Muscle.entries.size)
            for (i in 0 until PROBES_PER_RING) {
                val a = 2 * PI * i / PROBES_PER_RING
                hit(p.x + r * cos(a).toFloat(), p.y + r * sin(a).toFloat())?.let { votes[it.ordinal]++ }
            }
            val best = votes.indices.maxBy { votes[it] }
            if (votes[best] > 0) return Muscle.entries[best]
        }
        return null
    }

    private fun hit(x: Float, y: Float): Muscle? {
        val ix = x.roundToInt()
        val iy = y.roundToInt()
        return regions.firstOrNull { it.second.contains(ix, iy) }?.first
    }

    companion object {
        private const val PROBE_RINGS = 3
        private const val PROBES_PER_RING = 16

        /** The artwork as drawn upstream (no shape). */
        fun of(art: List<ArtPart>) = BodyFigure(
            art.map { part ->
                val path = Path()
                for (d in part.paths) path.addPath(PathParser().parsePathString(d).toPath())
                DrawnPart(part.muscle, part.isHair, path)
            },
        )
    }
}

/**
 * Both sides of one sex. They share one frame size (widest side, full height of both) so front and
 * back are drawn at the same scale with feet and heads aligned when shown side by side. [shape] is
 * the shape they are drawn in (null: the artwork as is).
 */
internal class BodyFigures(
    private val front: BodyFigure,
    private val back: BodyFigure,
    private val frontCenter: Float,
    private val backCenter: Float,
    private val width: Float,
    private val top: Float,
    private val bottom: Float,
    val shape: BodyShape?,
) {
    /** The artwork as drawn upstream, framed by its own bounds. */
    constructor(frontArt: List<ArtPart>, backArt: List<ArtPart>) : this(BodyFigure.of(frontArt), BodyFigure.of(backArt))

    private constructor(front: BodyFigure, back: BodyFigure) : this(
        front = front,
        back = back,
        frontCenter = front.bounds.center.x,
        backCenter = back.bounds.center.x,
        width = max(front.bounds.width, back.bounds.width),
        top = min(front.bounds.top, back.bounds.top),
        bottom = max(front.bounds.bottom, back.bounds.bottom),
        shape = null,
    )

    val aspectRatio: Float = width / (bottom - top)

    fun figure(side: BodySide): BodyFigure = if (side == BodySide.FRONT) front else back

    /** The crop box of [side] in its own coordinates (each side is centred on its own midline). */
    fun frame(side: BodySide): Rect {
        val cx = if (side == BodySide.FRONT) frontCenter else backCenter
        return Rect(cx - width / 2, top, cx + width / 2, bottom)
    }
}

/**
 * The frame every shaped figure of one sex shares, in artwork units: half its width about each
 * side's midline, its top and its bottom. It holds every shape of both sides (the widest are the
 * corners of the shape square), rounded outwards, so switching shapes keeps the figure's size and place.
 * Measured once from the artwork and the warp and checked by a test: working it out when the app
 * started took ten warps of the whole figure, the largest piece of the app's own work in a cold
 * start (about a second on a slow device, competing with the first frames for the processor).
 */
internal class ShapedFrame(val halfWidth: Float, val top: Float, val bottom: Float) {
    val aspectRatio: Float get() = 2 * halfWidth / (bottom - top)
}

internal val SHAPED_MALE_FRAME = ShapedFrame(halfWidth = 340f, top = 96f, bottom = 1343f)
internal val SHAPED_FEMALE_FRAME = ShapedFrame(halfWidth = 347f, top = -1f, bottom = 1429f)

internal fun shapedFrame(female: Boolean) = if (female) SHAPED_FEMALE_FRAME else SHAPED_MALE_FRAME

/**
 * Both sides of one sex parsed into contours once, with their measured proportions, to be warped
 * into any [BodyShape], every shape drawn in the sex's [ShapedFrame].
 */
internal class BodyOutlines(private val female: Boolean) {
    private val parts = BodySide.entries.associateWith { parseParts(bodyArt(it, female)) }
    private val proportions = parts.mapValues { (_, p) -> BodyProportions.measure(p) }
    private val frame = shapedFrame(female)

    fun figures(shape: BodyShape): BodyFigures {
        val figure = BodySide.entries.associateWith { side ->
            val sideParts = parts.getValue(side)
            val warped = BodyWarp.of(shape, proportions.getValue(side), female).apply(sideParts)
            BodyFigure(sideParts.zip(warped) { part, contours -> DrawnPart(part.art.muscle, part.art.isHair, pathOf(contours)) })
        }
        return BodyFigures(
            front = figure.getValue(BodySide.FRONT),
            back = figure.getValue(BodySide.BACK),
            frontCenter = proportions.getValue(BodySide.FRONT).centerX,
            backCenter = proportions.getValue(BodySide.BACK).centerX,
            width = 2 * frame.halfWidth,
            top = frame.top,
            bottom = frame.bottom,
            shape = shape,
        )
    }
}

/**
 * The parsed figures of each sex, built once on [Dispatchers.Default]: about 50 KB of path data and
 * the tap regions are too much work for the main thread's first frame. Shaped figures are warped
 * there too, for shapes rounded to [BodyShape.quantised] steps, and only the last few are kept (the
 * current shape and the one it is compared with).
 */
internal object BodyFigureCache {
    @Volatile
    private var male: BodyFigures? = null

    @Volatile
    private var female: BodyFigures? = null
    private val outlines = arrayOfNulls<BodyOutlines>(2)
    private val shaped = object : LinkedHashMap<Pair<Boolean, BodyShape>, BodyFigures>(MAX_SHAPED, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Pair<Boolean, BodyShape>, BodyFigures>?) = size > MAX_SHAPED
    }
    private val building = Mutex()

    /** [shape] null: the artwork as drawn; else the figure warped to that shape (rounded). */
    fun peek(female: Boolean, shape: BodyShape? = null): BodyFigures? =
        if (shape == null) {
            if (female) this.female else male
        } else {
            synchronized(shaped) { shaped[female to shape.quantised()] }
        }

    suspend fun load(female: Boolean, shape: BodyShape? = null): BodyFigures = peek(female, shape) ?: withContext(Dispatchers.Default) {
        building.withLock {
            peek(female, shape) ?: build(female, shape)
        }
    }

    /** Builds the outlines [female]'s shaped figures are warped from: the costly part of the first one. */
    suspend fun loadOutlines(female: Boolean) {
        withContext(Dispatchers.Default) { building.withLock { outlinesOf(female) } }
    }

    private fun build(female: Boolean, shape: BodyShape?): BodyFigures {
        if (shape == null) {
            return BodyFigures(bodyArt(BodySide.FRONT, female), bodyArt(BodySide.BACK, female)).also {
                if (female) this.female = it else male = it
            }
        }
        val key = shape.quantised()
        return outlinesOf(female).figures(key).also { synchronized(shaped) { shaped[female to key] = it } }
    }

    /** Only while holding [building]. */
    private fun outlinesOf(female: Boolean): BodyOutlines {
        val index = if (female) 1 else 0
        return outlines[index] ?: BodyOutlines(female).also { outlines[index] = it }
    }

    /** Now and three months ago, with room for a few more while the data or the profile changes. */
    private const val MAX_SHAPED = 6
}

/**
 * The figures of one sex in [shape]: at once when already built, else the last ones shown (another
 * shape, until the new one is ready) or null until the first background build ends.
 */
@Composable
private fun rememberBodyFigures(female: Boolean, shape: BodyShape?): BodyFigures? {
    val key = shape?.quantised()
    val state = remember(female, key == null) { mutableStateOf(BodyFigureCache.peek(female, key)) }
    LaunchedEffect(female, key) {
        state.value = BodyFigureCache.load(female, key)
    }
    return BodyFigureCache.peek(female, key) ?: state.value
}

/** Parses the figure of [female] ahead of its first body map (e.g. when the training data loads). */
internal suspend fun preloadBodyMap(female: Boolean) {
    BodyFigureCache.load(female)
}

/**
 * Builds the outlines [female]'s shaped figures are warped from ahead of the first one, so it only
 * needs its own warp: for people who train, whose figure Hoy and Progreso shape.
 */
internal suspend fun preloadShapedBodyMap(female: Boolean) {
    BodyFigureCache.loadOutlines(female)
}

private fun unionOf(a: Rect, b: Rect) = Rect(min(a.left, b.left), min(a.top, b.top), max(a.right, b.right), max(a.bottom, b.bottom))
