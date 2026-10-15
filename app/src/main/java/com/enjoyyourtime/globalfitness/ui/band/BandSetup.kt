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
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.enjoyyourtime.globalfitness.band.BandCandidate
import com.enjoyyourtime.globalfitness.band.SelectedBand

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
                Text("Clave guardada en el teléfono (oculta).", style = MaterialTheme.typography.bodyMedium)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { editing = true }) { Text("Cambiar") }
                    TextButton(onClick = onClear) { Text("Borrar") }
                }
            } else {
                Text(
                    "Pega la clave de 32 caracteres hexadecimales de tu pulsera (con o sin «0x»).",
                    style = MaterialTheme.typography.bodyMedium,
                )
                OutlinedTextField(
                    value = input,
                    onValueChange = onInput,
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Clave de autenticación") },
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
                        TextButton(onClick = { visible = !visible }) { Text(if (visible) "Ocultar" else "Mostrar") }
                    },
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { if (onSave()) editing = false }, enabled = input.isNotBlank()) { Text("Guardar clave") }
                    if (hasKey) TextButton(onClick = { editing = false; onInput("") }) { Text("Cancelar") }
                }
                Text(
                    "Se guarda solo en el almacenamiento privado de la app, fuera de las copias de seguridad, y nunca aparece en el diagnóstico.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            TextButton(onClick = onHelp, modifier = Modifier.padding(start = 0.dp)) { Text("¿Qué es la clave y cómo la obtengo?") }
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
                if (selected != null) "Elegida: ${selected.name}" else "Elige tu Xiaomi Smart Band 9.",
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                "Aparecen las pulseras emparejadas con este teléfono (Mi Fitness la empareja) y las que se detecten cerca.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (scanning) {
                    CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(8.dp))
                    Text("Buscando…", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                    TextButton(onClick = onStopScan) { Text("Parar") }
                } else {
                    OutlinedButton(onClick = onScan, enabled = canScan) { Text("Buscar pulseras cercanas") }
                }
            }
            if (candidates.isEmpty()) {
                Text(
                    "No hay ninguna pulsera Xiaomi emparejada ni detectada todavía.",
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
            val details = buildList {
                add(candidate.address)
                if (candidate.bonded) add("emparejada")
                candidate.rssi?.let { add("$it dBm") }
            }
            Text(details.joinToString(" · "), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (!candidate.isBand9) {
                Text(
                    "No es una Smart Band 9: este modo solo está adaptado a ese modelo y puede no funcionar.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
    }
}
