@file:OptIn(ExperimentalMaterial3Api::class)

package com.enjoyyourtime.globalfitness.ui.screens

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
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SelectableDates
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimeInput
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.enjoyyourtime.globalfitness.data.Measurement
import com.enjoyyourtime.globalfitness.data.MeasurementSource
import com.enjoyyourtime.globalfitness.data.Profile
import com.enjoyyourtime.globalfitness.domain.Classifier
import com.enjoyyourtime.globalfitness.domain.Metric
import com.enjoyyourtime.globalfitness.ui.Fmt
import com.enjoyyourtime.globalfitness.ui.compositionOf
import com.enjoyyourtime.globalfitness.ui.components.ProfileDot
import com.enjoyyourtime.globalfitness.ui.components.StatusChip
import com.enjoyyourtime.globalfitness.ui.theme.TabularNumbers
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZoneOffset

/** "¿De quién es esta medición?" prompt for an ambiguous scale measurement. */
@Composable
fun PendingAssignDialog(
    measurement: Measurement,
    profiles: List<Profile>,
    onAssign: (Long) -> Unit,
    onDiscard: () -> Unit,
    onLater: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onLater,
        title = { Text("¿De quién es esta medición? ${Fmt.num(measurement.weightKg, 1)} kg") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    Fmt.relative(measurement.timestamp) +
                        if (measurement.impedance != null) " · con impedancia" else " · sin impedancia",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                profiles.forEach { p ->
                    OutlinedButton(onClick = { onAssign(p.id) }, modifier = Modifier.fillMaxWidth()) {
                        ProfileDot(p.colorIndex)
                        Spacer(Modifier.width(8.dp))
                        Text(p.name, modifier = Modifier.weight(1f))
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onLater) { Text("Más tarde") } },
        dismissButton = { TextButton(onClick = onDiscard) { Text("Descartar") } },
    )
}

@Composable
fun ConfirmDialog(
    title: String,
    text: String,
    confirmLabel: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(text) },
        confirmButton = { TextButton(onClick = onConfirm) { Text(confirmLabel, color = MaterialTheme.colorScheme.error) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancelar") } },
    )
}

/** Material 3 date picker. Dates are exchanged as [LocalDate]. */
@Composable
fun DatePickerModal(
    initial: LocalDate?,
    onPick: (LocalDate) -> Unit,
    onDismiss: () -> Unit,
    maxDate: LocalDate = LocalDate.now(),
    minYear: Int = 1900,
) {
    val maxMillis = maxDate.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
    val state = rememberDatePickerState(
        initialSelectedDateMillis = initial?.atStartOfDay(ZoneOffset.UTC)?.toInstant()?.toEpochMilli(),
        yearRange = minYear..maxDate.year,
        selectableDates = object : SelectableDates {
            override fun isSelectableDate(utcTimeMillis: Long): Boolean = utcTimeMillis <= maxMillis
            override fun isSelectableYear(year: Int): Boolean = year <= maxDate.year
        },
    )
    DatePickerDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(
                onClick = {
                    state.selectedDateMillis?.let { onPick(Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate()) }
                    onDismiss()
                },
                enabled = state.selectedDateMillis != null,
            ) { Text("Aceptar") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancelar") } },
    ) {
        DatePicker(state = state)
    }
}

@Composable
fun TimeInputDialog(initial: LocalTime, onPick: (LocalTime) -> Unit, onDismiss: () -> Unit) {
    val state = rememberTimePickerState(initialHour = initial.hour, initialMinute = initial.minute, is24Hour = true)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Hora") },
        text = { TimeInput(state = state) },
        confirmButton = {
            TextButton(onClick = {
                onPick(LocalTime.of(state.hour, state.minute))
                onDismiss()
            }) { Text("Aceptar") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancelar") } },
    )
}

/** "Añadir manual": weight + date + time, saved for the selected profile. */
@Composable
fun ManualEntryDialog(profileName: String, onSave: (Double, Long) -> Unit, onDismiss: () -> Unit) {
    val zone = ZoneId.systemDefault()
    var weightText by remember { mutableStateOf("") }
    var date by remember { mutableStateOf(LocalDate.now()) }
    var time by remember { mutableStateOf(LocalTime.now().withSecond(0).withNano(0)) }
    var showDate by remember { mutableStateOf(false) }
    var showTime by remember { mutableStateOf(false) }
    val weight = Fmt.parseDecimal(weightText)
    val valid = weight != null && weight in 2.0..300.0
    val timestamp = date.atTime(time).atZone(zone).toInstant().toEpochMilli()
    val inFuture = timestamp > System.currentTimeMillis() + 60_000

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Añadir medición manual") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Para $profileName (solo peso, sin impedancia).", style = MaterialTheme.typography.bodyMedium)
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
            TextButton(onClick = { weight?.let { onSave(it, timestamp) } }, enabled = valid && !inFuture) { Text("Guardar") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancelar") } },
    )
    if (showDate) DatePickerModal(initial = date, onPick = { date = it }, onDismiss = { showDate = false }, minYear = 2000)
    if (showTime) TimeInputDialog(initial = time, onPick = { time = it }, onDismiss = { showTime = false })
}

/** Bottom sheet with every metric of one measurement plus reassign / delete actions. */
@Composable
fun MeasurementDetailSheet(
    measurement: Measurement,
    profile: Profile?,
    profiles: List<Profile>,
    onAssign: (Long) -> Unit,
    onDelete: () -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var confirmDelete by remember { mutableStateOf(false) }
    var menuOpen by remember { mutableStateOf(false) }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .padding(bottom = 16.dp)
                .navigationBarsPadding(),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(Fmt.dateTime(measurement.timestamp), style = MaterialTheme.typography.titleLarge)
            val origin = if (measurement.source == MeasurementSource.MANUAL) "Manual" else "Báscula"
            val owner = profile?.name ?: "Sin asignar"
            Text("$origin · $owner", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            HorizontalDivider(Modifier.padding(vertical = 4.dp))
            DetailRow("Peso", Fmt.kg(measurement.weightKg), null)
            DetailRow("Impedancia", measurement.impedance?.let { "$it Ω" } ?: "—", null)
            if (profile != null) {
                val c = profile.compositionOf(measurement)
                Metric.entries.filter { it != Metric.WEIGHT }.forEach { metric ->
                    val v = metric.extract(c)
                    DetailRow(
                        metric.label,
                        v?.let { Fmt.metric(metric, it) } ?: "—",
                        if (v != null) metric.classify(c) else null,
                    )
                }
                Classifier.bodyType(c)?.let { DetailRow("Tipo de cuerpo", it.name, null) }
                if (measurement.impedance == null) {
                    Text(NO_IMPEDANCE_HINT, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            } else {
                Text(
                    "Asigna la medición a un perfil para ver la composición corporal.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            HorizontalDivider(Modifier.padding(vertical = 4.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                val others = profiles.filter { it.id != profile?.id }
                Column {
                    OutlinedButton(onClick = { menuOpen = true }, enabled = others.isNotEmpty()) {
                        Icon(Icons.Filled.Person, contentDescription = null)
                        Spacer(Modifier.width(6.dp))
                        Text(if (profile == null) "Asignar a…" else "Reasignar a…")
                    }
                    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                        others.forEach { p ->
                            DropdownMenuItem(
                                text = { Text(p.name) },
                                leadingIcon = { ProfileDot(p.colorIndex) },
                                onClick = {
                                    menuOpen = false
                                    onAssign(p.id)
                                },
                            )
                        }
                    }
                }
                OutlinedButton(onClick = { confirmDelete = true }) {
                    Icon(Icons.Filled.Delete, contentDescription = null, tint = MaterialTheme.colorScheme.error)
                    Spacer(Modifier.width(6.dp))
                    Text("Eliminar", color = MaterialTheme.colorScheme.error)
                }
            }
        }
    }
    if (confirmDelete) {
        ConfirmDialog(
            title = "¿Eliminar medición?",
            text = "Se borrará la medición de ${Fmt.kg(measurement.weightKg)} del ${Fmt.dateTime(measurement.timestamp)}. No se puede deshacer.",
            confirmLabel = "Eliminar",
            onConfirm = {
                confirmDelete = false
                onDelete()
            },
            onDismiss = { confirmDelete = false },
        )
    }
}

@Composable
private fun DetailRow(label: String, value: String, classification: com.enjoyyourtime.globalfitness.domain.Classification?) {
    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        if (classification != null) {
            StatusChip(classification)
            Spacer(Modifier.width(8.dp))
        }
        Text(value, style = MaterialTheme.typography.bodyMedium.merge(TabularNumbers), fontWeight = FontWeight.SemiBold)
    }
}
