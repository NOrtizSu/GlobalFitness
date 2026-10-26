package com.enjoyyourtime.globalfitness.ui.training

import android.util.LruCache
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.enjoyyourtime.globalfitness.training.model.Equipment
import com.enjoyyourtime.globalfitness.training.model.Exercise
import com.enjoyyourtime.globalfitness.training.model.Muscle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.max

/**
 * Small square picture of an exercise for lists, so rows are told apart at a glance: the photo of
 * the user's machine when there is one (a photo whose file has not reached this phone yet shows the
 * figure meanwhile); else the side of the body the exercise trains, cropped around its main muscles
 * (primary ones in the primary colour, secondary ones lighter), with the equipment's glyph in a
 * corner. Exercises without muscles (cardio machines) show their activity's glyph on a tinted square.
 *
 * The figure has about 150 paths, too many to draw in every row of a scrolling list: each thumbnail
 * is drawn once into a bitmap off the main thread and kept in an LRU cache that every list shares
 * (keyed by what it shows, so exercises that train the same muscles share one). The square stays
 * empty until its bitmap is ready. Decorative for TalkBack: rows announce the exercise themselves.
 */
@Composable
fun ExerciseThumbnail(
    exercise: Exercise,
    modifier: Modifier = Modifier,
    size: Dp = 40.dp,
    vm: TrainingViewModel = trainingViewModel(),
) {
    val scheme = MaterialTheme.colorScheme
    val photoPx = with(LocalDensity.current) { (size * 2).roundToPx() }
    val photo = rememberPhoto(vm, exercise.photo, photoPx)
    val side = thumbnailSide(exercise.primary, exercise.secondary)
    val glyphOnly = photo == null && side == null
    Box(
        modifier
            .size(size)
            .clip(MaterialTheme.shapes.small)
            .background(if (glyphOnly) scheme.secondaryContainer else scheme.surfaceContainerHighest),
        contentAlignment = Alignment.Center,
    ) {
        when {
            photo != null -> Image(photo, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            side == null -> Icon(
                exercise.activityIcon,
                contentDescription = null,
                tint = scheme.onSecondaryContainer,
                modifier = Modifier.size(size * GLYPH_FRACTION),
            )
            else -> {
                val female by vm.female.collectAsStateWithLifecycle()
                BodyThumbnail(side, female, exercise.primary, exercise.secondary, size)
                EquipmentBadge(exercise.equipment, size)
            }
        }
    }
}

/**
 * The side of the figure a thumbnail shows for these muscles ([primary], or [secondary] when an
 * exercise has no primary ones); null without muscles. The side that shows more of them wins. When
 * both show as many (shoulders, or quads and glutes) the side most of them are usually seen from
 * decides — triceps, traps and calves are drawn on both sides but read best from the back — and
 * then the front.
 */
internal fun thumbnailSide(primary: Set<Muscle>, secondary: Set<Muscle> = emptySet()): BodySide? {
    val focus = primary.ifEmpty { secondary }
    if (focus.isEmpty()) return null
    val shown = BodySide.entries.associateWith { side -> focus.count { it in side.muscles() } }
    val front = shown.getValue(BodySide.FRONT)
    val back = shown.getValue(BodySide.BACK)
    if (front != back) return if (front > back) BodySide.FRONT else BodySide.BACK
    val fromBack = focus.count { it.seenFrom == BodySide.BACK }
    return if (fromBack > focus.size - fromBack) BodySide.BACK else BodySide.FRONT
}

/** The side a muscle is usually pictured from. */
private val Muscle.seenFrom: BodySide
    get() = when (this) {
        Muscle.TRICEPS, Muscle.TRAPS, Muscle.LATS, Muscle.LOWER_BACK, Muscle.GLUTES, Muscle.HAMSTRINGS, Muscle.CALVES -> BodySide.BACK
        else -> BodySide.FRONT
    }

/**
 * The square of [frame] (the figure's box, in figure units) a thumbnail shows around [focus] (the
 * bounds of its muscles): a margin around them, at least [MIN_CROP] of the figure's height so the
 * body stays recognisable, at most all of it; centred on the figure's midline (muscles come in pairs)
 * and kept inside the figure from head to feet.
 */
internal fun thumbnailCrop(frame: Rect, focus: Rect): Rect {
    val side = (max(focus.width, focus.height) * CROP_MARGIN).coerceIn(frame.height * MIN_CROP, frame.height)
    val cy = focus.center.y.coerceIn(frame.top + side / 2, frame.bottom - side / 2)
    val cx = frame.center.x
    return Rect(cx - side / 2, cy - side / 2, cx + side / 2, cy + side / 2)
}

/** Room around the muscles and the smallest crop, as a share of the figure's height. */
private const val CROP_MARGIN = 1.25f
private const val MIN_CROP = 0.58f

/** Glyph of exercises without muscles, and the equipment badge, as shares of the thumbnail. */
private const val GLYPH_FRACTION = 0.6f
private const val BADGE_FRACTION = 0.38f
private const val BADGE_GLYPH_FRACTION = 0.68f

/** Thin lines around and between the muscles (thinner than on the body map). */
private val THUMB_CONTOUR = 0.35.dp

@Composable
private fun BodyThumbnail(side: BodySide, female: Boolean, primary: Set<Muscle>, secondary: Set<Muscle>, size: Dp) {
    val scheme = MaterialTheme.colorScheme
    val density = LocalDensity.current
    val container = scheme.surfaceContainerHighest
    // Steps from the square's colour towards the text colour, so the figure reads in light and dark
    // themes and with any dynamic palette; trained muscles take the primary colour.
    val body = lerp(container, scheme.onSurfaceVariant, 0.24f)
    val palette = ThumbnailPalette(
        body = body,
        other = lerp(container, scheme.onSurfaceVariant, 0.14f),
        contour = lerp(container, scheme.onSurfaceVariant, 0.5f),
        primary = scheme.primary,
        secondary = lerp(scheme.primary, body, 0.55f),
    )
    val visible = side.muscles()
    val key = ThumbnailKey(
        side = side,
        female = female,
        primary = primary intersect visible,
        secondary = (secondary - primary) intersect visible,
        px = with(density) { size.roundToPx() },
        contourPx = with(density) { THUMB_CONTOUR.toPx() },
        palette = palette,
    )
    // The state remembers which key its bitmap is for: when the key changes (muscles edited, another
    // figure), the previous figure is never shown while the new one is drawn, not even for a frame.
    val drawn by produceState(key to ThumbnailCache.peek(key), key) {
        value = key to (ThumbnailCache.peek(key) ?: ThumbnailCache.load(key))
    }
    val bitmap = drawn.second.takeIf { drawn.first == key } ?: ThumbnailCache.peek(key)
    bitmap?.let { Image(it, contentDescription = null, modifier = Modifier.fillMaxSize()) }
}

/** The equipment's glyph on a small disc in the bottom corner (end side) of a thumbnail of [thumbSize]. */
@Composable
private fun BoxScope.EquipmentBadge(equipment: Equipment, thumbSize: Dp) {
    val badge = thumbSize * BADGE_FRACTION
    Box(
        Modifier
            .align(Alignment.BottomEnd)
            .padding(thumbSize * 0.03f)
            .size(badge)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.surface),
        contentAlignment = Alignment.Center,
    ) {
        Icon(equipment.icon, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(badge * BADGE_GLYPH_FRACTION))
    }
}

private data class ThumbnailPalette(val body: Color, val other: Color, val contour: Color, val primary: Color, val secondary: Color)

/** Everything a thumbnail bitmap depends on (muscles already limited to the ones [side] shows). */
private data class ThumbnailKey(
    val side: BodySide,
    val female: Boolean,
    val primary: Set<Muscle>,
    val secondary: Set<Muscle>,
    val px: Int,
    val contourPx: Float,
    val palette: ThumbnailPalette,
)

/** Thumbnail bitmaps, drawn one at a time on a background thread. */
private object ThumbnailCache {
    private val bitmaps = object : LruCache<ThumbnailKey, ImageBitmap>(CACHE_BYTES) {
        override fun sizeOf(key: ThumbnailKey, value: ImageBitmap): Int = value.width * value.height * 4
    }

    private val drawing = Dispatchers.Default.limitedParallelism(1)

    fun peek(key: ThumbnailKey): ImageBitmap? = bitmaps.get(key)

    suspend fun load(key: ThumbnailKey): ImageBitmap {
        val figures = BodyFigureCache.load(key.female)
        // Kept even when the row has left meanwhile: scrolling back shows it at once.
        return withContext(drawing) { peek(key) ?: draw(figures, key).also { bitmaps.put(key, it) } }
    }

    private fun draw(figures: BodyFigures, key: ThumbnailKey): ImageBitmap {
        val figure = figures.figure(key.side)
        val focus = key.primary.ifEmpty { key.secondary }
        val focusBounds = focus.mapNotNull { figure.muscleBounds[it] }.reduceOrNull(::unionOf) ?: figure.bounds
        val crop = thumbnailCrop(figures.frame(key.side), focusBounds)
        val scale = key.px / crop.width
        val p = key.palette
        val bitmap = ImageBitmap(key.px, key.px)
        val size = Size(key.px.toFloat(), key.px.toFloat())
        CanvasDrawScope().draw(Density(1f), LayoutDirection.Ltr, Canvas(bitmap), size) {
            withTransform({
                translate(-crop.left * scale, -crop.top * scale)
                scale(scale, scale, pivot = Offset.Zero)
            }) {
                // As on the body map: every part stroked first, so the outline and the gaps between
                // muscles read as thin lines.
                val contour = Stroke(width = key.contourPx / scale, join = StrokeJoin.Round)
                for (part in figure.parts) drawPath(part.path, p.contour, style = contour)
                for (part in figure.parts) {
                    val color = when {
                        part.muscle == null -> if (part.isHair) p.contour else p.other
                        part.muscle in key.primary -> p.primary
                        part.muscle in key.secondary -> p.secondary
                        else -> p.body
                    }
                    drawPath(part.path, color)
                }
            }
        }
        return bitmap
    }

    /** About 120 thumbnails of 44 dp at 3× density. */
    private const val CACHE_BYTES = 8 * 1024 * 1024
}

private fun unionOf(a: Rect, b: Rect) = Rect(minOf(a.left, b.left), minOf(a.top, b.top), maxOf(a.right, b.right), maxOf(a.bottom, b.bottom))
