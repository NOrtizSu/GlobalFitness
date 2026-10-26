package com.enjoyyourtime.globalfitness.ui.achievements

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.enjoyyourtime.globalfitness.training.domain.Achievement
import com.enjoyyourtime.globalfitness.training.domain.AchievementCategory
import com.enjoyyourtime.globalfitness.ui.components.AppIcons
import com.enjoyyourtime.globalfitness.ui.training.ExerciseIcons
import com.enjoyyourtime.globalfitness.ui.training.TrainingIcons
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/**
 * The medal colours, one per level: bronze, silver, gold, then emerald, sapphire, ruby, amethyst
 * and diamond for the long ladders. Fixed (not from the dynamic scheme) so "gold" means the third
 * level on every phone; they read on light and dark surfaces alike.
 */
object MedalColors {
    val tiers: List<Color> = listOf(
        Color(0xFFC47F45),
        Color(0xFFAEB7C2),
        Color(0xFFE5B32E),
        Color(0xFF2FA86B),
        Color(0xFF3C74D9),
        Color(0xFFD1415A),
        Color(0xFF8E5BD8),
        Color(0xFF63C7E8),
    )

    /** The colour of [level] (1-based) of [a]; one-level achievements are gold. */
    fun of(a: Achievement, level: Int): Color = tiers[(if (a.levels == 1) GOLD else level - 1).coerceIn(0, tiers.lastIndex)]

    private const val GOLD = 2
}

/** Glyphs drawn inside the medals that the app's icon sets do not have (Material Symbols paths). */
internal object MedalGlyphs {
    internal val paths: Map<String, String> = mapOf(
        "Flag" to "M14.4,6L14,4H5v17h2v-7h5.6l0.4,2h7V6z",
        "Flame" to "M13.5,0.67s0.74,2.65 0.74,4.8c0,2.06 -1.35,3.73 -3.41,3.73 -2.07,0 -3.63,-1.67 -3.63,-3.73l0.03,-0.36" +
            "C5.21,7.51 4,10.62 4,14c0,4.42 3.58,8 8,8s8,-3.58 8,-8C20,8.61 17.41,3.8 13.5,0.67zM11.71,19c-1.78,0 -3.22,-1.4 " +
            "-3.22,-3.14 0,-1.62 1.05,-2.76 2.81,-3.12 1.77,-0.36 3.6,-1.21 4.62,-2.58 0.39,1.29 0.59,2.65 0.59,4.04 " +
            "0,2.65 -2.15,4.8 -4.8,4.8z",
        // A pair of scales: a post on a base, the beam, and a pan hanging from each end.
        "Balance" to "M11,3h2v15h4v2H7v-2h4V3zM4,6h16v2H4zM2,14l3,-6 3,6c0,1.66 -1.34,3 -3,3s-3,-1.34 -3,-3z" +
            "M16,14l3,-6 3,6c0,1.66 -1.34,3 -3,3s-3,-1.34 -3,-3z",
    )

    private fun icon(name: String): ImageVector =
        ImageVector.Builder(name = name, defaultWidth = 24.dp, defaultHeight = 24.dp, viewportWidth = 24f, viewportHeight = 24f)
            .apply { addPath(pathData = addPathNodes(paths.getValue(name)), fill = SolidColor(Color.Black)) }
            .build()

    val Flag: ImageVector by lazy { icon("Flag") }
    val Flame: ImageVector by lazy { icon("Flame") }
    val Balance: ImageVector by lazy { icon("Balance") }
}

/** The glyph of an achievement. */
fun Achievement.glyph(): ImageVector = when (this) {
    Achievement.WORKOUTS -> TrainingIcons.FitnessCenter
    Achievement.WEEK_STREAK -> MedalGlyphs.Flame
    Achievement.BALANCED_WEEKS -> MedalGlyphs.Balance
    Achievement.EXERCISE_VARIETY -> ExerciseIcons.Other
    Achievement.CARDIO_DISTANCE -> ExerciseIcons.Run
    Achievement.VOLUME -> ExerciseIcons.Kettlebell
    Achievement.RECORDS -> TrainingIcons.Trophy
    Achievement.BENCH_PRESS, Achievement.SQUAT, Achievement.DEADLIFT, Achievement.OVERHEAD_PRESS -> ExerciseIcons.Barbell
    Achievement.PULL_UPS -> ExerciseIcons.Bodyweight
    Achievement.GOAL_REACHED -> MedalGlyphs.Flag
    Achievement.GOAL_PROGRESS -> AppIcons.Scale
    Achievement.BODY_FAT -> AppIcons.TrendDown
    Achievement.MUSCLE_MASS -> AppIcons.TrendUp
}

/**
 * A medal: the category's shape (round for consistency, a shield for strength, a hexagon for the
 * body) in the colour of [level], with the achievement's glyph. Level 0 draws it greyed (not
 * earned yet). Decorative: the text next to it says what it is.
 */
@Composable
fun Medal(achievement: Achievement, level: Int, modifier: Modifier = Modifier, size: Dp = 56.dp) {
    val earned = level > 0
    val scheme = MaterialTheme.colorScheme
    val base = if (earned) MedalColors.of(achievement, level) else scheme.surfaceContainerHighest
    val rim = if (earned) lerp(base, Color.Black, 0.28f) else scheme.outlineVariant
    val light = if (earned) lerp(base, Color.White, 0.45f) else scheme.surfaceContainerHigh
    val glyphColor = when {
        !earned -> scheme.onSurfaceVariant.copy(alpha = 0.55f)
        base.luminance() > 0.45f -> Color(0xFF2B2416)
        else -> Color.White
    }
    val shape = achievement.category
    Box(modifier.size(size), contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) { drawMedal(shape, base, rim, light, earned) }
        Icon(achievement.glyph(), contentDescription = null, tint = glyphColor, modifier = Modifier.size(size * 0.46f))
    }
}

private fun DrawScope.drawMedal(category: AchievementCategory, base: Color, rim: Color, light: Color, earned: Boolean) {
    val outline = medalPath(category, size, inset = 0f)
    val inner = medalPath(category, size, inset = min(size.width, size.height) * 0.11f)
    val fill = if (earned) Brush.linearGradient(listOf(light, base, rim), start = Offset.Zero, end = Offset(size.width, size.height)) else SolidColor(base)
    drawPath(outline, fill)
    drawPath(outline, rim, style = Stroke(width = size.minDimension * 0.045f))
    drawPath(inner, if (earned) light.copy(alpha = 0.7f) else rim.copy(alpha = 0.6f), style = Stroke(width = size.minDimension * 0.03f))
}

/** The outline of a medal of [category] filling [size], [inset] pixels inside it. */
internal fun medalPath(category: AchievementCategory, size: Size, inset: Float): Path {
    val w = size.width - 2 * inset
    val h = size.height - 2 * inset
    val left = inset
    val top = inset
    return Path().apply {
        when (category) {
            AchievementCategory.TRAINING -> addOval(Rect(left, top, left + w, top + h))
            AchievementCategory.STRENGTH -> {
                // A shield: flat top with rounded corners, straight sides, curved to a point.
                val r = w * 0.14f
                moveTo(left + r, top)
                lineTo(left + w - r, top)
                quadraticTo(left + w, top, left + w, top + r)
                lineTo(left + w, top + h * 0.48f)
                cubicTo(left + w, top + h * 0.78f, left + w * 0.72f, top + h * 0.9f, left + w / 2, top + h)
                cubicTo(left + w * 0.28f, top + h * 0.9f, left, top + h * 0.78f, left, top + h * 0.48f)
                lineTo(left, top + r)
                quadraticTo(left, top, left + r, top)
                close()
            }
            AchievementCategory.BODY -> {
                // A hexagon with a vertex on top.
                val cx = left + w / 2
                val cy = top + h / 2
                val radius = min(w, h) / 2
                for (i in 0 until 6) {
                    val angle = -PI / 2 + i * PI / 3
                    val x = cx + radius * cos(angle).toFloat()
                    val y = cy + radius * sin(angle).toFloat()
                    if (i == 0) moveTo(x, y) else lineTo(x, y)
                }
                close()
            }
        }
    }
}

/** The medal of the achievements page and the Entreno tab card when nothing is earned yet. */
@Composable
fun TrophyBadge(modifier: Modifier = Modifier, size: Dp = 56.dp, earned: Boolean = true) {
    val base = if (earned) MedalColors.tiers[2] else MaterialTheme.colorScheme.surfaceContainerHighest
    Box(modifier.size(size), contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            drawMedal(AchievementCategory.TRAINING, base, lerp(base, Color.Black, 0.28f), lerp(base, Color.White, 0.45f), earned)
        }
        val tint = if (earned) Color(0xFF2B2416) else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.55f)
        Icon(TrainingIcons.Trophy, contentDescription = null, tint = tint, modifier = Modifier.size(size * 0.5f))
    }
}
