package com.enjoyyourtime.globalfitness.ui.training

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.enjoyyourtime.globalfitness.R
import com.enjoyyourtime.globalfitness.training.model.LoadUnit
import com.enjoyyourtime.globalfitness.ui.Fmt
import com.enjoyyourtime.globalfitness.ui.components.SectionTitle

/** Rest timer choices (seconds; 0 = off). */
private val REST_CHOICES = listOf(0, 30, 60, 90, 120, 180)

/** Load unit, rest timer and where saving workouts to Health Connect is configured. */
@Composable
fun TrainingSettingsScreen(onBack: () -> Unit) {
    val vm = trainingViewModel()
    val unit by vm.loadUnit.collectAsStateWithLifecycle()
    val rest by vm.restSeconds.collectAsStateWithLifecycle()
    // A value set elsewhere (not one of the choices) still shows as selected.
    val choices = (REST_CHOICES + rest).distinct().sorted()

    TrainingSubPage(title = stringResource(R.string.training_settings_title), onBack = onBack) { padding ->
        Column(
            Modifier
                .subPageContent(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            SectionTitle(stringResource(R.string.training_settings_unit))
            Text(stringResource(R.string.training_settings_unit_text), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                LoadUnit.entries.forEachIndexed { i, u ->
                    SegmentedButton(
                        selected = u == unit,
                        onClick = { vm.setLoadUnit(u) },
                        shape = SegmentedButtonDefaults.itemShape(i, LoadUnit.entries.size),
                        label = { Text(stringResource(if (u == LoadUnit.KG) R.string.training_unit_kg_long else R.string.training_unit_lb_long)) },
                    )
                }
            }
            SectionTitle(stringResource(R.string.training_settings_rest), Modifier.padding(top = 8.dp))
            Text(stringResource(R.string.training_settings_rest_text), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                choices.forEach { s ->
                    FilterChip(selected = s == rest, onClick = { vm.setRestSeconds(s) }, label = { Text(restLabel(s)) })
                }
            }
            SectionTitle(stringResource(R.string.training_health_connect), Modifier.padding(top = 8.dp))
            Text(stringResource(R.string.training_settings_health), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** "Off", "30 s", "90 s", "2 min". */
@Composable
private fun restLabel(seconds: Int): String = when {
    seconds <= 0 -> stringResource(R.string.training_rest_off)
    seconds % 60 == 0 && seconds >= 120 -> stringResource(R.string.value_minutes, Fmt.count((seconds / 60).toLong()))
    else -> stringResource(R.string.training_seconds_value, Fmt.count(seconds.toLong()))
}
