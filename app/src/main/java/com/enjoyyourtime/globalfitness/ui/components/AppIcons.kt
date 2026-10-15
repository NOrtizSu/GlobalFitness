package com.enjoyyourtime.globalfitness.ui.components

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.SolidColor
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

    val ShowChart: ImageVector by lazy {
        icon("ShowChart", "M3.5,18.49l6,-6.01 4,4L22,6.92l-1.41,-1.41 -7.09,7.97 -4,-4L2,16.99z")
    }

    val History: ImageVector by lazy {
        icon(
            "History",
            "M13,3c-4.97,0 -9,4.03 -9,9L1,12l3.89,3.89 0.07,0.14L9,12L6,12c0,-3.87 3.13,-7 7,-7s7,3.13 7,7 -3.13,7 -7,7c-1.93,0 -3.68,-0.79 -4.94,-2.06l-1.42,1.42C8.27,19.99 10.51,21 13,21c4.97,0 9,-4.03 9,-9s-4.03,-9 -9,-9z",
            "M12,8v5l4.28,2.54 0.72,-1.21 -3.5,-2.08L13.5,8z",
        )
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

    /** Bathroom scale glyph (matches the launcher icon). */
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

    val Tune: ImageVector by lazy {
        icon(
            "Tune",
            "M3,17v2h6v-2L3,17zM3,5v2h10L13,5L3,5zM13,21v-2h8v-2h-8v-2h-2v6h2zM7,9v2L3,11v2h4v2h2L9,9L7,9zM21,13v-2L11,11v2h10zM15,9h2L17,7h4L21,5h-4L17,3h-2v6z",
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
