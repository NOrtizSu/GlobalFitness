package com.enjoyyourtime.globalfitness.debug

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.enjoyyourtime.globalfitness.training.domain.BodyShape
import com.enjoyyourtime.globalfitness.training.model.Muscle
import com.enjoyyourtime.globalfitness.ui.theme.GlobalFitnessTheme
import com.enjoyyourtime.globalfitness.ui.training.BodyMap
import com.enjoyyourtime.globalfitness.ui.training.BodySide

/**
 * Debug-only test helper: the body figure in a 3 × 3 matrix of shapes (fatness across, muscularity
 * down, at 0, 0.5 and 1), to check the shape warp on an emulator in light and dark themes.
 * `female` picks the figure, `side` FRONT or BACK, `colored` fills a few muscles.
 *
 *     adb shell am start -n com.enjoyyourtime.globalfitness/.debug.DebugBodyShapeActivity --ez female true --es side BACK
 */
class DebugBodyShapeActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val female = intent.getBooleanExtra("female", false)
        val side = if (intent.getStringExtra("side") == "BACK") BodySide.BACK else BodySide.FRONT
        val colored = intent.getBooleanExtra("colored", false)
        val levels = listOf(0f, 0.5f, 1f)
        setContent {
            GlobalFitnessTheme {
                val scheme = MaterialTheme.colorScheme
                Column(
                    Modifier.fillMaxSize().background(scheme.surface).systemBarsPadding().padding(8.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    for (muscularity in levels) {
                        Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            for (fatness in levels) {
                                Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                                    Text("f $fatness · m $muscularity", style = MaterialTheme.typography.labelSmall, color = scheme.onSurface)
                                    BodyMap(
                                        side = side,
                                        female = female,
                                        fill = { m ->
                                            when {
                                                !colored -> null
                                                m == Muscle.CHEST || m == Muscle.LATS -> scheme.primary
                                                m == Muscle.QUADS || m == Muscle.GLUTES -> scheme.tertiary
                                                else -> null
                                            }
                                        },
                                        shape = BodyShape(fatness, muscularity),
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
