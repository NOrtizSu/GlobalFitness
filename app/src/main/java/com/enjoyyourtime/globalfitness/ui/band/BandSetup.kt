package com.enjoyyourtime.globalfitness.ui.band

import android.view.WindowManager
import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.enjoyyourtime.globalfitness.R
import com.enjoyyourtime.globalfitness.band.BandCandidate
import com.enjoyyourtime.globalfitness.band.SelectedBand
import com.enjoyyourtime.globalfitness.ui.Fmt

@Composable
fun AuthKeyCard(
    hasKey: Boolean,
    input: String,
    error: String?,
    onInput: (String) -> Unit,
    onSave: () -> Boolean,
    onClear: () -> Unit,
    onHelp: () -> Unit,
) {
    var editing by remember { mutableStateOf(false) }
    var visible by remember { mutableStateOf(false) }
    // No screenshots, screen recording or recents thumbnail while the key can be typed or shown.
    SecureWindowWhile(!hasKey || editing)
    OutlinedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (hasKey && !editing) {
                Text(stringResource(R.string.band_key_saved), style = MaterialTheme.typography.bodyMedium)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { editing = true }) { Text(stringResource(R.string.band_key_change)) }
                    TextButton(onClick = onClear) { Text(stringResource(R.string.action_delete)) }
                }
            } else {
                Text(
                    stringResource(R.string.band_key_paste),
                    style = MaterialTheme.typography.bodyMedium,
                )
                OutlinedTextField(
                    value = input,
                    onValueChange = onInput,
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text(stringResource(R.string.band_key_title)) },
                    singleLine = true,
                    isError = error != null,
                    supportingText = error?.let { { Text(it) } },
                    textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
                    visualTransformation = if (visible) VisualTransformation.None else PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Password,
                        autoCorrectEnabled = false,
                        imeAction = ImeAction.Done,
                    ),
                    keyboardActions = KeyboardActions(onDone = { if (onSave()) editing = false }),
                    trailingIcon = {
                        TextButton(onClick = { visible = !visible }) {
                            Text(stringResource(if (visible) R.string.band_log_hide else R.string.band_key_show))
                        }
                    },
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { if (onSave()) editing = false }, enabled = input.isNotBlank()) { Text(stringResource(R.string.band_key_save)) }
                    if (hasKey) TextButton(onClick = { editing = false; onInput("") }) { Text(stringResource(R.string.action_cancel)) }
                }
                Text(
                    stringResource(R.string.band_key_storage),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            TextButton(onClick = onHelp, modifier = Modifier.padding(start = 0.dp)) { Text(stringResource(R.string.band_key_help)) }
        }
    }
}

/** Sets FLAG_SECURE on the window while [active] and this composable is in the composition. */
@Composable
private fun SecureWindowWhile(active: Boolean) {
    val activity = LocalActivity.current ?: return
    DisposableEffect(activity, active) {
        if (active) activity.window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        onDispose {
            if (active) activity.window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
        }
    }
}

@Composable
fun DevicePickerCard(
    selected: SelectedBand?,
    candidates: List<BandCandidate>,
    scanning: Boolean,
    canScan: Boolean,
    onScan: () -> Unit,
    onStopScan: () -> Unit,
    onSelect: (BandCandidate) -> Unit,
) {
    OutlinedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                if (selected != null) stringResource(R.string.band_selected, selected.name) else stringResource(R.string.band_choose),
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                stringResource(R.string.band_picker_text),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (scanning) {
                    CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.band_searching), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                    TextButton(onClick = onStopScan) { Text(stringResource(R.string.band_stop)) }
                } else {
                    OutlinedButton(onClick = onScan, enabled = canScan) { Text(stringResource(R.string.band_scan_nearby)) }
                }
            }
            if (candidates.isEmpty()) {
                Text(
                    stringResource(R.string.band_none_found),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            candidates.forEachIndexed { index, candidate ->
                if (index > 0) HorizontalDivider()
                CandidateRow(candidate, selected = candidate.address == selected?.address, onSelect = { onSelect(candidate) })
            }
        }
    }
}

@Composable
private fun CandidateRow(candidate: BandCandidate, selected: Boolean, onSelect: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .selectable(selected = selected, onClick = onSelect, role = Role.RadioButton)
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = null)
        Column(Modifier.weight(1f)) {
            Text(candidate.name, style = MaterialTheme.typography.bodyLarge)
            val details = listOfNotNull(
                candidate.address,
                if (candidate.bonded) stringResource(R.string.band_bonded) else null,
                candidate.rssi?.let { stringResource(R.string.value_dbm, Fmt.count(it.toLong())) },
            )
            Text(details.joinToString(" · "), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (!candidate.isBand9) {
                Text(
                    stringResource(R.string.band_not_band9),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
    }
}
