package com.enjoyyourtime.globalfitness.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.spring
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.takeOrElse
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.enjoyyourtime.globalfitness.domain.Status

// Fallback scheme for Android 11 and lower: Material "tonal spot" generated from the brand blue
// #2A78D6 (the same algorithm Android 12+ uses for wallpaper colors).
private val LightColors = lightColorScheme(
    primary = Color(0xFF3F5F90),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFD5E3FF),
    onPrimaryContainer = Color(0xFF254777),
    inversePrimary = Color(0xFFA8C8FF),
    secondary = Color(0xFF555F71),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFD9E3F8),
    onSecondaryContainer = Color(0xFF3E4758),
    tertiary = Color(0xFF6F5675),
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFF8D8FE),
    onTertiaryContainer = Color(0xFF563E5D),
    background = Color(0xFFF9F9FF),
    onBackground = Color(0xFF191C20),
    surface = Color(0xFFF9F9FF),
    onSurface = Color(0xFF191C20),
    surfaceVariant = Color(0xFFE0E2EC),
    onSurfaceVariant = Color(0xFF43474E),
    surfaceTint = Color(0xFF3F5F90),
    inverseSurface = Color(0xFF2E3035),
    inverseOnSurface = Color(0xFFF0F0F7),
    error = Color(0xFFBA1A1A),
    onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFFFDAD6),
    onErrorContainer = Color(0xFF93000A),
    outline = Color(0xFF74777F),
    outlineVariant = Color(0xFFC4C6CF),
    scrim = Color(0xFF000000),
    surfaceBright = Color(0xFFF9F9FF),
    surfaceDim = Color(0xFFD9D9E0),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFF3F3FA),
    surfaceContainer = Color(0xFFEDEDF4),
    surfaceContainerHigh = Color(0xFFE7E8EE),
    surfaceContainerHighest = Color(0xFFE1E2E9),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFFA8C8FF),
    onPrimary = Color(0xFF06305F),
    primaryContainer = Color(0xFF254777),
    onPrimaryContainer = Color(0xFFD5E3FF),
    inversePrimary = Color(0xFF3F5F90),
    secondary = Color(0xFFBDC7DC),
    onSecondary = Color(0xFF273141),
    secondaryContainer = Color(0xFF3E4758),
    onSecondaryContainer = Color(0xFFD9E3F8),
    tertiary = Color(0xFFDBBCE1),
    onTertiary = Color(0xFF3E2845),
    tertiaryContainer = Color(0xFF563E5D),
    onTertiaryContainer = Color(0xFFF8D8FE),
    background = Color(0xFF111318),
    onBackground = Color(0xFFE1E2E9),
    surface = Color(0xFF111318),
    onSurface = Color(0xFFE1E2E9),
    surfaceVariant = Color(0xFF43474E),
    onSurfaceVariant = Color(0xFFC4C6CF),
    surfaceTint = Color(0xFFA8C8FF),
    inverseSurface = Color(0xFFE1E2E9),
    inverseOnSurface = Color(0xFF2E3035),
    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005),
    errorContainer = Color(0xFF93000A),
    onErrorContainer = Color(0xFFFFDAD6),
    outline = Color(0xFF8E9099),
    outlineVariant = Color(0xFF43474E),
    scrim = Color(0xFF000000),
    surfaceBright = Color(0xFF37393E),
    surfaceDim = Color(0xFF111318),
    surfaceContainerLowest = Color(0xFF0C0E13),
    surfaceContainerLow = Color(0xFF191C20),
    surfaceContainer = Color(0xFF1D2024),
    surfaceContainerHigh = Color(0xFF282A2F),
    surfaceContainerHighest = Color(0xFF33353A),
)

/**
 * Material 3 Expressive motion tokens, emulated: material3 1.4.0 keeps MotionScheme and
 * MaterialExpressiveTheme internal, so the app's own animations use these springs (slightly bouncy
 * for movement and size, critically damped for color and alpha).
 */
object AppMotion {
    fun <T> spatial(): FiniteAnimationSpec<T> = spring(dampingRatio = 0.8f, stiffness = 380f)

    fun <T> slowSpatial(): FiniteAnimationSpec<T> = spring(dampingRatio = 0.8f, stiffness = 200f)

    fun <T> effects(): FiniteAnimationSpec<T> = spring(dampingRatio = 1f, stiffness = 1600f)
}

/** Expressive look on stable APIs: generously rounded containers. */
private val AppShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(24.dp),
    extraLarge = RoundedCornerShape(32.dp),
)

/** Emphasized display / headline / title weights over the default type scale. */
private val AppTypography = Typography().let { base ->
    base.copy(
        displayLarge = base.displayLarge.copy(fontWeight = FontWeight.SemiBold, letterSpacing = (-0.5).sp),
        displayMedium = base.displayMedium.copy(fontWeight = FontWeight.SemiBold, letterSpacing = (-0.25).sp),
        displaySmall = base.displaySmall.copy(fontWeight = FontWeight.SemiBold),
        headlineLarge = base.headlineLarge.copy(fontWeight = FontWeight.SemiBold),
        headlineMedium = base.headlineMedium.copy(fontWeight = FontWeight.SemiBold),
        headlineSmall = base.headlineSmall.copy(fontWeight = FontWeight.SemiBold),
        titleLarge = base.titleLarge.copy(fontWeight = FontWeight.SemiBold),
        titleMedium = base.titleMedium.copy(fontWeight = FontWeight.SemiBold),
        titleSmall = base.titleSmall.copy(fontWeight = FontWeight.SemiBold),
        labelLarge = base.labelLarge.copy(fontWeight = FontWeight.SemiBold),
    )
}

/**
 * Data-visualisation tokens: a fixed palette, independent of the (dynamic) Material scheme so
 * series and status colors mean the same on every phone. Chart text uses [ink] / [mutedInk].
 */
@Immutable
data class ChartColors(
    val series1: Color,
    val series2: Color,
    val series3: Color,
    /** Fourth categorical color (deep sleep). */
    val series4: Color,
    val gridline: Color,
    val axis: Color,
    val mutedInk: Color,
    val ink: Color,
    val good: Color,
    val warning: Color,
    val serious: Color,
    val critical: Color,
) {
    fun status(status: Status): Color = when (status) {
        Status.GOOD -> good
        Status.WARNING -> warning
        Status.SERIOUS -> serious
        Status.CRITICAL -> critical
    }
}

private val StatusGood = Color(0xFF0CA30C)
private val StatusWarning = Color(0xFFFAB219)
private val StatusSerious = Color(0xFFEC835A)
private val StatusCritical = Color(0xFFD03B3B)
private val MutedInk = Color(0xFF898781)

private val LightChartColors = ChartColors(
    series1 = Color(0xFF2A78D6),
    series2 = Color(0xFFEB6834),
    series3 = Color(0xFF1BAF7A),
    series4 = Color(0xFF4A3AA7),
    gridline = Color(0xFFE1E0D9),
    axis = Color(0xFFC3C2B7),
    mutedInk = MutedInk,
    ink = Color(0xFF1B1B1A),
    good = StatusGood,
    warning = StatusWarning,
    serious = StatusSerious,
    critical = StatusCritical,
)

private val DarkChartColors = ChartColors(
    series1 = Color(0xFF3987E5),
    series2 = Color(0xFFD95926),
    series3 = Color(0xFF199E70),
    series4 = Color(0xFF9085E9),
    gridline = Color(0xFF2C2C2A),
    axis = Color(0xFF383835),
    mutedInk = MutedInk,
    ink = Color(0xFFE6E5E1),
    good = StatusGood,
    warning = StatusWarning,
    serious = StatusSerious,
    critical = StatusCritical,
)

val LocalChartColors = staticCompositionLocalOf { LightChartColors }

/** Container color behind a chart (point halos and label chips match it); unspecified = surface. */
val LocalChartSurface = compositionLocalOf { Color.Unspecified }

/** Tabular figures so numbers do not jitter while updating. */
val TabularNumbers = TextStyle(fontFeatureSettings = "tnum")

/**
 * Material You: wallpaper-based dynamic color on Android 12+, the blue fallback scheme before;
 * light / dark follow the system. Charts keep [ChartColors] regardless of the scheme.
 */
@Composable
fun GlobalFitnessTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = true,
    content: @Composable () -> Unit,
) {
    val context = LocalContext.current
    val colors = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        darkTheme -> DarkColors
        else -> LightColors
    }
    val chartColors = if (darkTheme) DarkChartColors else LightChartColors
    CompositionLocalProvider(LocalChartColors provides chartColors) {
        MaterialTheme(colorScheme = colors, shapes = AppShapes, typography = AppTypography, content = content)
    }
}

object ChartTheme {
    val colors: ChartColors
        @Composable get() = LocalChartColors.current

    val surface: Color
        @Composable get() = LocalChartSurface.current.takeOrElse { MaterialTheme.colorScheme.surface }
}
