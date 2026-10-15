package com.enjoyyourtime.globalfitness.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import com.enjoyyourtime.globalfitness.domain.Status

private val LightColors = lightColorScheme(
    primary = Color(0xFF2368C4),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFD6E3FF),
    onPrimaryContainer = Color(0xFF001B3E),
    inversePrimary = Color(0xFFA8C8FF),
    secondary = Color(0xFF555F71),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFD9E3F8),
    onSecondaryContainer = Color(0xFF121C2B),
    tertiary = Color(0xFF6E5676),
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFF7D8FF),
    onTertiaryContainer = Color(0xFF27132F),
    background = Color(0xFFFCFCFB),
    onBackground = Color(0xFF1B1B1A),
    surface = Color(0xFFFCFCFB),
    onSurface = Color(0xFF1B1B1A),
    surfaceVariant = Color(0xFFE6E5DF),
    onSurfaceVariant = Color(0xFF4A4945),
    surfaceTint = Color(0xFF2368C4),
    inverseSurface = Color(0xFF30302E),
    inverseOnSurface = Color(0xFFF3F2EE),
    error = Color(0xFFBA1A1A),
    onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFFFDAD6),
    onErrorContainer = Color(0xFF410002),
    outline = Color(0xFF7A7973),
    outlineVariant = Color(0xFFCBCAC3),
    scrim = Color(0xFF000000),
    surfaceBright = Color(0xFFFCFCFB),
    surfaceDim = Color(0xFFDCDCD8),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFF6F6F3),
    surfaceContainer = Color(0xFFF0F0ED),
    surfaceContainerHigh = Color(0xFFEAEAE6),
    surfaceContainerHighest = Color(0xFFE4E4E0),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFFA8C8FF),
    onPrimary = Color(0xFF003062),
    primaryContainer = Color(0xFF1F4F95),
    onPrimaryContainer = Color(0xFFD6E3FF),
    inversePrimary = Color(0xFF2368C4),
    secondary = Color(0xFFBDC7DC),
    onSecondary = Color(0xFF273141),
    secondaryContainer = Color(0xFF3E4759),
    onSecondaryContainer = Color(0xFFD9E3F8),
    tertiary = Color(0xFFDABDE2),
    onTertiary = Color(0xFF3D2946),
    tertiaryContainer = Color(0xFF553F5D),
    onTertiaryContainer = Color(0xFFF7D8FF),
    background = Color(0xFF1A1A19),
    onBackground = Color(0xFFE6E5E1),
    surface = Color(0xFF1A1A19),
    onSurface = Color(0xFFE6E5E1),
    surfaceVariant = Color(0xFF3A3A37),
    onSurfaceVariant = Color(0xFFC9C8C0),
    surfaceTint = Color(0xFFA8C8FF),
    inverseSurface = Color(0xFFE6E5E1),
    inverseOnSurface = Color(0xFF30302E),
    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005),
    errorContainer = Color(0xFF93000A),
    onErrorContainer = Color(0xFFFFDAD6),
    outline = Color(0xFF93928B),
    outlineVariant = Color(0xFF45453F),
    scrim = Color(0xFF000000),
    surfaceBright = Color(0xFF3A3A38),
    surfaceDim = Color(0xFF1A1A19),
    surfaceContainerLowest = Color(0xFF141413),
    surfaceContainerLow = Color(0xFF1F1F1E),
    surfaceContainer = Color(0xFF242423),
    surfaceContainerHigh = Color(0xFF2C2C2A),
    surfaceContainerHighest = Color(0xFF363634),
)

/** Data-visualisation tokens (separate from the M3 scheme). */
@Immutable
data class ChartColors(
    val series1: Color,
    val series2: Color,
    val series3: Color,
    val gridline: Color,
    val axis: Color,
    val mutedInk: Color,
    val ink: Color,
    val surface: Color,
    val good: Color,
    val warning: Color,
    val serious: Color,
    val critical: Color,
    val profileColors: List<Color>,
) {
    fun status(status: Status): Color = when (status) {
        Status.GOOD -> good
        Status.WARNING -> warning
        Status.SERIOUS -> serious
        Status.CRITICAL -> critical
    }

    fun profile(colorIndex: Int): Color = profileColors[Math.floorMod(colorIndex, profileColors.size)]
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
    gridline = Color(0xFFE1E0D9),
    axis = Color(0xFFC3C2B7),
    mutedInk = MutedInk,
    ink = Color(0xFF1B1B1A),
    surface = Color(0xFFFCFCFB),
    good = StatusGood,
    warning = StatusWarning,
    serious = StatusSerious,
    critical = StatusCritical,
    profileColors = listOf(
        Color(0xFF2A78D6), // blue
        Color(0xFFEB6834), // orange
        Color(0xFF1BAF7A), // aqua
        Color(0xFFEDA100), // yellow
        Color(0xFFE87BA4), // magenta
        Color(0xFF008300), // green
        Color(0xFF4A3AA7), // violet
        Color(0xFFE34948), // red
    ),
)

private val DarkChartColors = ChartColors(
    series1 = Color(0xFF3987E5),
    series2 = Color(0xFFD95926),
    series3 = Color(0xFF199E70),
    gridline = Color(0xFF2C2C2A),
    axis = Color(0xFF383835),
    mutedInk = MutedInk,
    ink = Color(0xFFE6E5E1),
    surface = Color(0xFF1A1A19),
    good = StatusGood,
    warning = StatusWarning,
    serious = StatusSerious,
    critical = StatusCritical,
    profileColors = listOf(
        Color(0xFF3987E5),
        Color(0xFFD95926),
        Color(0xFF199E70),
        Color(0xFFC98500),
        Color(0xFFD55181),
        Color(0xFF008300),
        Color(0xFF9085E9),
        Color(0xFFE66767),
    ),
)

val LocalChartColors = staticCompositionLocalOf { LightChartColors }

/** Tabular figures so numbers do not jitter while updating. */
val TabularNumbers = TextStyle(fontFeatureSettings = "tnum")

@Composable
fun GlobalFitnessTheme(darkTheme: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    val colors = if (darkTheme) DarkColors else LightColors
    val chartColors = if (darkTheme) DarkChartColors else LightChartColors
    CompositionLocalProvider(LocalChartColors provides chartColors) {
        MaterialTheme(colorScheme = colors, typography = Typography(), content = content)
    }
}

object ChartTheme {
    val colors: ChartColors
        @Composable get() = LocalChartColors.current
}
