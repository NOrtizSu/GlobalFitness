package com.enjoyyourtime.globalfitness.ui.components

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.unit.dp

/** A few Material Symbols paths, so the large icons-extended artifact is not needed. */
object AppIcons {
    private fun icon(name: String, vararg paths: String, evenOdd: Boolean = false): ImageVector =
        ImageVector.Builder(
            name = name,
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f,
        ).apply {
            for (p in paths) {
                addPath(
                    pathData = addPathNodes(p),
                    pathFillType = if (evenOdd) PathFillType.EvenOdd else PathFillType.NonZero,
                    fill = SolidColor(Color.Black),
                )
            }
        }.build()

    /** App glyph (matches the launcher icon): scale platform with a heartbeat line. */
    val Logo: ImageVector by lazy {
        ImageVector.Builder("Logo", 24.dp, 24.dp, 24f, 24f).apply {
            val ink = SolidColor(Color.Black)
            addPath(
                pathData = addPathNodes("M7,3.5h10a3.5,3.5 0 0 1 3.5,3.5v10a3.5,3.5 0 0 1 -3.5,3.5H7a3.5,3.5 0 0 1 -3.5,-3.5V7a3.5,3.5 0 0 1 3.5,-3.5z"),
                stroke = ink,
                strokeLineWidth = 1.8f,
            )
            addPath(addPathNodes("M10,6.9h4"), stroke = ink, strokeLineWidth = 1.8f, strokeLineCap = StrokeCap.Round)
            addPath(
                pathData = addPathNodes("M6.2,13.5H9l1.5,-3 2.2,6 1.8,-4.5 0.9,1.5H17.8"),
                stroke = ink,
                strokeLineWidth = 1.8f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            )
        }.build()
    }

    val Bluetooth: ImageVector by lazy {
        icon(
            "Bluetooth",
            "M17.71,7.71L12,2h-1v7.59L6.41,5 5,6.41 10.59,12 5,17.59 6.41,19 11,14.41L11,22h1l5.71,-5.71 -4.3,-4.29 4.3,-4.29zM13,5.83l1.88,1.88L13,9.59L13,5.83zM14.88,16.29L13,18.17v-3.76l1.88,1.88z",
        )
    }

    val Bolt: ImageVector by lazy {
        icon("Bolt", "M11,21h-1l1,-7H7.5c-0.88,0 -0.33,-0.75 -0.31,-0.78C8.48,10.94 10.42,7.54 13.01,3h1l-1,7h3.51c0.4,0 0.62,0.19 0.4,0.66C12.97,17.55 11,21 11,21z")
    }

    /** Bathroom scale glyph. */
    val Scale: ImageVector by lazy {
        icon(
            "Scale",
            "M6,3h12a3,3 0 0 1 3,3v12a3,3 0 0 1 -3,3H6a3,3 0 0 1 -3,-3V6a3,3 0 0 1 3,-3z" +
                "M9,5.5h6a1,1 0 0 1 1,1v1.5a1,1 0 0 1 -1,1H9a1,1 0 0 1 -1,-1V6.5a1,1 0 0 1 1,-1z" +
                "M7.5,15a1.5,2.5 0 1 0 3,0a1.5,2.5 0 1 0 -3,0z" +
                "M13.5,15a1.5,2.5 0 1 0 3,0a1.5,2.5 0 1 0 -3,0z",
            evenOdd = true,
        )
    }

    val Watch: ImageVector by lazy {
        icon(
            "Watch",
            "M20,12c0,-2.54 -1.19,-4.81 -3.04,-6.27L16,0H8l-0.95,5.73C5.19,7.19 4,9.45 4,12s1.19,4.81 3.05,6.27L8,24h8l0.96,-5.73C18.81,16.81 20,14.54 20,12zM6,12c0,-3.31 2.69,-6 6,-6s6,2.69 6,6 -2.69,6 -6,6 -6,-2.69 -6,-6z",
        )
    }

    val Cloud: ImageVector by lazy {
        icon(
            "Cloud",
            "M19.35,10.04C18.67,6.59 15.64,4 12,4 9.11,4 6.6,5.64 5.35,8.04 2.34,8.36 0,10.91 0,14c0,3.31 2.69,6 6,6h13c2.76,0 5,-2.24 5,-5 0,-2.64 -2.05,-4.78 -4.65,-4.96z",
        )
    }

    val Upload: ImageVector by lazy {
        icon("Upload", "M5,20h14v-2H5V20zM5,10h4v6h6v-6h4l-7,-7L5,10z")
    }

    val Download: ImageVector by lazy {
        icon("Download", "M19,9h-4V3H9v6H5l7,7L19,9zM5,18v2h14v-2H5z")
    }

    val ExpandMore: ImageVector by lazy {
        icon("ExpandMore", "M16.59,8.59L12,13.17 7.41,8.59 6,10l6,6 6,-6z")
    }

    val ExpandLess: ImageVector by lazy {
        icon("ExpandLess", "M12,8l-6,6 1.41,1.41L12,10.83l4.59,4.58L18,14z")
    }

    val ChevronRight: ImageVector by lazy {
        icon("ChevronRight", "M10,6L8.59,7.41 13.17,12l-4.58,4.59L10,18l6,-6z")
    }

    val TrendUp: ImageVector by lazy {
        icon("TrendUp", "M16,6l2.29,2.29 -4.88,4.88 -4,-4L2,16.59 3.41,18l6,-6 4,4 6.3,-6.29L22,12V6z")
    }

    val TrendDown: ImageVector by lazy {
        icon("TrendDown", "M16,18l2.29,-2.29 -4.88,-4.88 -4,4L2,7.41 3.41,6l6,6 4,-4 6.3,6.29L22,12v6z")
    }

    val TrendFlat: ImageVector by lazy {
        icon("TrendFlat", "M22,12l-4,-4v3H3v2h15v3z")
    }
}
