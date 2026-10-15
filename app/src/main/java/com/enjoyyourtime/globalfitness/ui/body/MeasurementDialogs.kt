@file:OptIn(ExperimentalMaterial3Api::class)

package com.enjoyyourtime.globalfitness.ui.body

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.enjoyyourtime.globalfitness.data.Measurement
import com.enjoyyourtime.globalfitness.data.MeasurementSource
import com.enjoyyourtime.globalfitness.domain.Classifier
import com.enjoyyourtime.globalfitness.domain.Metric
import com.enjoyyourtime.globalfitness.scale.CompositionSource
import com.enjoyyourtime.globalfitness.scale.FormulaLib
import com.enjoyyourtime.globalfitness.ui.Entry
import com.enjoyyourtime.globalfitness.ui.Fmt
import com.enjoyyourtime.globalfitness.ui.components.ConfirmDialog
import com.enjoyyourtime.globalfitness.ui.components.DatePickerModal
import com.enjoyyourtime.globalfitness.ui.components.InfoRow
import com.enjoyyourtime.globalfitness.ui.components.StatusChip
import com.enjoyyourtime.globalfitness.ui.components.TimeInputDialog
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

const val NO_COMPOSITION_HINT = "Sin composición corporal: súbete descalzo y con los pies secos."

/** Where the body composition of [m] comes from, in Spanish (null for weight-only entries). */
fun compositionSourceLabel(m: Measurement): String? = when (val source = m.compositionSource) {
    CompositionSource.Device -> "Composición medida por la báscula"
    is CompositionSource.Formula -> "Composición calculada (fórmula ${source.lib.label})"
    CompositionSource.None -> null
    null -> if (m.impedance != null) "Composición calculada (fórmula ${FormulaLib.MI_SCALE.label})" else null
}

/** "Añadir peso manual": weight + date + time. */
@Composable
fun ManualEntryDialog(onSave: (Double, Long) -> Unit, onDismiss: () -> Unit) {
    val zone = ZoneId.systemDefault()
    var weightText by rememberSaveable { mutableStateOf("") }
    var epochDay by rememberSaveable { mutableLongStateOf(LocalDate.now().toEpochDay()) }
    var minuteOfDay by rememberSaveable { mutableIntStateOf(LocalTime.now().let { it.hour * 60 + it.minute }) }
    var showDate by rememberSaveable { mutableStateOf(false) }
    var showTime by rememberSaveable { mutableStateOf(false) }
    var saved by remember { mutableStateOf(false) }
    val date = LocalDate.ofEpochDay(epochDay)
    val time = LocalTime.of(minuteOfDay / 60, minuteOfDay % 60)
    val weight = Fmt.parseDecimal(weightText)
    val valid = weight != null && weight in 2.0..300.0
    val timestamp = date.atTime(time).atZone(zone).toInstant().toEpochMilli()
    val inFuture = timestamp > System.currentTimeMillis() + 60_000

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Añadir peso manual") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Solo el peso: la composición corporal necesita la báscula.", style = MaterialTheme.typography.bodyMedium)
                OutlinedTextField(
                    value = weightText,
                    onValueChange = { weightText = it.filter { ch -> ch.isDigit() || ch == ',' || ch == '.' }.take(6) },
                    label = { Text("Peso") },
                    suffix = { Text("kg") },
                    singleLine = true,
                    isError = weightText.isNotEmpty() && !valid,
                    supportingText = { if (weightText.isNotEmpty() && !valid) Text("Introduce un peso entre 2 y 300 kg") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { showDate = true }, modifier = Modifier.weight(1f)) {
                        Icon(Icons.Filled.DateRange, contentDescription = null)
                        Spacer(Modifier.width(6.dp))
                        Text(Fmt.date(date))
                    }
                    OutlinedButton(onClick = { showTime = true }) {
                        Text(String.format(Fmt.LOCALE, "%02d:%02d", time.hour, time.minute))
                    }
                }
                if (inFuture) {
                    Text("La fecha no puede estar en el futuro.", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    // A fast double tap must not save the same weight twice.
                    if (!saved && weight != null) {
                        saved = true
                        onSave(weight, timestamp)
                    }
                },
                enabled = valid && !inFuture && !saved,
            ) { Text("Guardar") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancelar") } },
    )
    if (showDate) DatePickerModal(initial = date, onPick = { epochDay = it.toEpochDay() }, onDismiss = { showDate = false }, minYear = 2000)
    if (showTime) TimeInputDialog(initial = time, onPick = { minuteOfDay = it.hour * 60 + it.minute }, onDismiss = { showTime = false })
}

/** Bottom sheet with every metric of one measurement plus the delete action. */
@Composable
fun MeasurementDetailSheet(entry: Entry, onDelete: () -> Unit, onDismiss: () -> Unit) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var confirmDelete by remember { mutableStateOf(false) }
    val m = entry.measurement
    val c = entry.composition

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp)
                .padding(bottom = 16.dp)
                .navigationBarsPadding(),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(Fmt.dateTime(m.timestamp), style = MaterialTheme.typography.headlineSmall)
            Text(
                listOfNotNull(
                    if (m.source == MeasurementSource.MANUAL) "Manual" else "Báscula",
                    compositionSourceLabel(m),
                ).joinToString(" · "),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            HorizontalDivider(Modifier.padding(vertical = 8.dp))
            InfoRow("Peso", Fmt.kg(m.weightKg))
            InfoRow("Impedancia", m.impedance?.let { "$it Ω" } ?: "—")
            Metric.entries.forEach { metric ->
                val v = metric.extract(c)
                if (metric == Metric.WEIGHT || (metric.optional && v == null)) return@forEach
                val chip = if (v != null) metric.classify(c) else null
                InfoRow(
                    metric.label,
                    v?.let { Fmt.metric(metric, it) } ?: "—",
                    trailing = chip?.let { status -> @Composable { StatusChip(status) } },
                )
            }
            Classifier.bodyType(c)?.let { InfoRow("Tipo de cuerpo", it.name) }
            if (c.fatPercent == null) {
                Text(NO_COMPOSITION_HINT, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            HorizontalDivider(Modifier.padding(vertical = 8.dp))
            OutlinedButton(onClick = { confirmDelete = true }) {
                Icon(Icons.Filled.Delete, contentDescription = null, tint = MaterialTheme.colorScheme.error)
                Spacer(Modifier.width(6.dp))
                Text("Eliminar", color = MaterialTheme.colorScheme.error)
            }
        }
    }
    if (confirmDelete) {
        ConfirmDialog(
            title = "¿Eliminar medición?",
            text = "Se borrará la medición de ${Fmt.kg(m.weightKg)} del ${Fmt.dateTime(m.timestamp)}. No se puede deshacer.",
            confirmLabel = "Eliminar",
            onConfirm = {
                confirmDelete = false
                onDelete()
            },
            onDismiss = { confirmDelete = false },
        )
    }
}
