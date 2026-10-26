package com.enjoyyourtime.globalfitness.ui.training

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.enjoyyourtime.globalfitness.R
import com.enjoyyourtime.globalfitness.training.data.SpotifyCovers
import com.enjoyyourtime.globalfitness.training.model.LoadUnit
import com.enjoyyourtime.globalfitness.ui.Fmt
import com.enjoyyourtime.globalfitness.ui.components.SectionTitle
import com.enjoyyourtime.globalfitness.ui.training.music.musicViewModel

/** Rest timer choices (seconds; 0 = off). */
private val REST_CHOICES = listOf(0, 30, 60, 90, 120, 180)

/**
 * Load unit, rest timer, the day's music suggestions (and whether their covers come from Spotify)
 * and where saving workouts to Health Connect is configured.
 */
@Composable
fun TrainingSettingsScreen(onBack: () -> Unit) {
    val vm = trainingViewModel()
    val unit by vm.loadUnit.collectAsStateWithLifecycle()
    val rest by vm.restSeconds.collectAsStateWithLifecycle()
    val musicVm = musicViewModel()
    val music by musicVm.enabled.collectAsStateWithLifecycle()
    val covers by musicVm.spotifyCovers.collectAsStateWithLifecycle()
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
            SectionTitle(stringResource(R.string.music_title), Modifier.padding(top = 8.dp))
            SwitchRow(stringResource(R.string.music_setting), checked = music, onChange = musicVm::setEnabled)
            Text(stringResource(R.string.music_setting_text), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            // Off until the user says yes (in the question at start or here); unavailable with the suggestions off.
            SwitchRow(
                stringResource(R.string.music_covers_setting),
                checked = covers == SpotifyCovers.ALLOWED,
                enabled = music,
                onChange = musicVm::setCoversAllowed,
                modifier = Modifier.padding(top = 8.dp),
            )
            Text(
                stringResource(R.string.music_covers_setting_text),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = if (music) 1f else DISABLED_ALPHA),
            )
            SectionTitle(stringResource(R.string.training_health_connect), Modifier.padding(top = 8.dp))
            Text(stringResource(R.string.training_settings_health), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** Text disabled as Material 3 does it. */
private const val DISABLED_ALPHA = 0.38f

/** A setting that is a switch: the whole row toggles it, so TalkBack reads the title with the state. */
@Composable
private fun SwitchRow(text: String, checked: Boolean, onChange: (Boolean) -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    Row(
        modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .toggleable(value = checked, enabled = enabled, role = Role.Switch, onValueChange = onChange),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = if (enabled) 1f else DISABLED_ALPHA),
            modifier = Modifier.weight(1f),
        )
        Spacer(Modifier.width(16.dp))
        Switch(checked = checked, onCheckedChange = null, enabled = enabled)
    }
}

/** "Off", "30 s", "90 s", "2 min". */
@Composable
private fun restLabel(seconds: Int): String = when {
    seconds <= 0 -> stringResource(R.string.training_rest_off)
    seconds % 60 == 0 && seconds >= 120 -> stringResource(R.string.value_minutes, Fmt.count((seconds / 60).toLong()))
    else -> stringResource(R.string.training_seconds_value, Fmt.count(seconds.toLong()))
}
