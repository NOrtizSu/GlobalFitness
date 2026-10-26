package com.enjoyyourtime.globalfitness.ui.health

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.enjoyyourtime.globalfitness.R
import com.enjoyyourtime.globalfitness.health.HcAvailability
import com.enjoyyourtime.globalfitness.ui.asString
import com.enjoyyourtime.globalfitness.ui.theme.TabularNumbers

/**
 * "Save workouts to Health Connect" switch with its own permission request, shown under the scale
 * export switch ([HealthExportCard]) and usable on any other screen (no card of its own).
 *
 * States: Health Connect unavailable; permission missing ("Allow" requests only the exercise write
 * permission, which users who connected before this feature do not have; if the request came back
 * without it, Health Connect may no longer show the dialog, so its settings are offered too);
 * on (with the number of workouts saved) or off. Export errors are shown under it, as for the scale.
 */
@Composable
fun WorkoutExportRow(modifier: Modifier = Modifier, vm: WorkoutExportViewModel = viewModel(factory = WorkoutExportViewModel.Factory)) {
    val state by vm.uiState.collectAsStateWithLifecycle()
    var requested by rememberSaveable { mutableStateOf(false) }
    val permissions = rememberLauncherForActivityResult(vm.permissionContract) { vm.onPermissionsResult() }
    val available = state.availability == HcAvailability.AVAILABLE

    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        // The whole row is the switch, so TalkBack reads its title with its state.
        val switchable = available && state.canWrite
        Row(
            Modifier.toggleable(value = state.enabled, enabled = switchable, role = Role.Switch, onValueChange = vm::setEnabled),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                stringResource(R.string.hc_workouts_export_title),
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.weight(1f),
            )
            Switch(checked = state.enabled, onCheckedChange = null, enabled = switchable)
        }
        Supporting(stringResource(R.string.hc_workouts_export_text))
        when {
            !available -> Supporting(stringResource(R.string.hc_not_available))
            !state.canWrite -> {
                Supporting(stringResource(R.string.hc_workouts_export_missing_permission))
                if (requested) Supporting(stringResource(R.string.hc_workouts_export_denied))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilledTonalButton(
                        onClick = {
                            requested = true
                            permissions.launch(vm.missingPermissions)
                        },
                    ) { Text(stringResource(R.string.hc_workouts_export_allow)) }
                    if (requested) OutlinedButton(onClick = { vm.openHealthConnect() }) { Text(stringResource(R.string.hc_open)) }
                }
            }
            !state.enabled -> Small(stringResource(R.string.hc_workouts_export_off))
            else -> {
                val count = state.export.savedCount
                Small(
                    if (count == 0) {
                        stringResource(R.string.hc_workouts_export_count_none)
                    } else {
                        pluralStringResource(R.plurals.hc_workouts_export_count, count, count)
                    },
                )
            }
        }
        state.export.error?.let { Text(it.asString(), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
    }
}

@Composable
private fun Supporting(text: String) {
    Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun Small(text: String) {
    Text(text, style = MaterialTheme.typography.bodySmall.merge(TabularNumbers), color = MaterialTheme.colorScheme.onSurfaceVariant)
}
