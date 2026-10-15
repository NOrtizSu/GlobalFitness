package com.enjoyyourtime.globalfitness.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.enjoyyourtime.globalfitness.ble.LiveScale
import com.enjoyyourtime.globalfitness.ble.ScanDebugInfo
import com.enjoyyourtime.globalfitness.ble.ScanStatus
import com.enjoyyourtime.globalfitness.data.Profile
import com.enjoyyourtime.globalfitness.data.ProfileDraft
import com.enjoyyourtime.globalfitness.ui.Fmt
import com.enjoyyourtime.globalfitness.ui.MainUiState
import com.enjoyyourtime.globalfitness.ui.components.AppIcons
import com.enjoyyourtime.globalfitness.ui.components.ProfileDot
import com.enjoyyourtime.globalfitness.ui.components.SectionTitle
import com.enjoyyourtime.globalfitness.ui.theme.TabularNumbers
import java.time.LocalDate

@Composable
fun ProfilesScreen(
    state: MainUiState,
    scanStatus: ScanStatus,
    live: LiveScale,
    debug: ScanDebugInfo?,
    onUpdate: (Long, ProfileDraft) -> Unit,
    onOpenScales: () -> Unit,
    onExport: (android.net.Uri) -> Unit,
    onImport: (android.net.Uri) -> Unit,
) {
    var editing by remember { mutableStateOf<Profile?>(null) }
    var diagnosticsOpen by rememberSaveable { mutableStateOf(false) }

    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { uri ->
        uri?.let(onExport)
    }
    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let(onImport)
    }

    LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item { SectionTitle("Perfil") }
        items(state.profiles, key = { it.id }) { profile ->
            ProfileRow(
                profile = profile,
                count = state.measurementCounts[profile.id] ?: 0,
                onEdit = { editing = profile },
            )
        }

        item { SectionTitle("Básculas", Modifier.padding(top = 8.dp)) }
        item {
            FilledTonalButton(onClick = onOpenScales) {
                Icon(AppIcons.Bluetooth, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("Básculas")
            }
        }

        item { SectionTitle("Datos", Modifier.padding(top = 8.dp)) }
        item {
            OutlinedCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        "CSV con perfil, fecha ISO, peso, impedancia y métricas calculadas (separador «;», decimales con coma). " +
                            "Al importar se omiten las mediciones duplicadas.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = { exportLauncher.launch("balance-dash-${LocalDate.now()}.csv") }) {
                            Icon(AppIcons.Upload, contentDescription = null)
                            Spacer(Modifier.width(6.dp))
                            Text("Exportar CSV")
                        }
                        OutlinedButton(onClick = {
                            importLauncher.launch(arrayOf("text/*", "application/csv", "application/vnd.ms-excel", "application/octet-stream"))
                        }) {
                            Icon(AppIcons.Download, contentDescription = null)
                            Spacer(Modifier.width(6.dp))
                            Text("Importar CSV")
                        }
                    }
                }
            }
        }

        item {
            DiagnosticsCard(
                open = diagnosticsOpen,
                onToggle = { diagnosticsOpen = !diagnosticsOpen },
                scanStatus = scanStatus,
                live = live,
                debug = debug,
            )
        }
    }

    editing?.let { p ->
        ProfileDialog(initial = p, onSubmit = { onUpdate(p.id, it); editing = null }, onDismiss = { editing = null })
    }
}

@Composable
private fun ProfileRow(profile: Profile, count: Int, onEdit: () -> Unit) {
    OutlinedCard(Modifier.fillMaxWidth()) {
        Row(Modifier.padding(start = 16.dp, top = 12.dp, bottom = 12.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            ProfileDot(profile.colorIndex, size = 16.dp)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(profile.name, style = MaterialTheme.typography.titleMedium)
                val age = profile.ageAt(System.currentTimeMillis())
                val parts = buildList {
                    add(profile.sex.label)
                    add("$age años")
                    add("${Fmt.num(profile.heightCm, if (profile.heightCm % 1.0 == 0.0) 0 else 1)} cm")
                    profile.targetWeightKg?.let { add("objetivo ${Fmt.num(it, 1)} kg") }
                }
                Text(parts.joinToString(" · "), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(
                    if (count == 1) "1 medición" else "$count mediciones",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            IconButton(onClick = onEdit) { Icon(Icons.Filled.Edit, contentDescription = "Editar ${profile.name}") }
        }
    }
}

@Composable
private fun DiagnosticsCard(
    open: Boolean,
    onToggle: () -> Unit,
    scanStatus: ScanStatus,
    live: LiveScale,
    debug: ScanDebugInfo?,
) {
    OutlinedCard(onClick = onToggle, modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Diagnóstico BLE", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                Icon(
                    if (open) AppIcons.ExpandLess else AppIcons.ExpandMore,
                    contentDescription = if (open) "Contraer" else "Expandir",
                )
            }
            if (!open) return@Column
            DiagRow("Estado", scanStatusText(scanStatus))
            DiagRow("Medición en curso", liveText(live))
            if (debug == null) {
                Text(
                    "Aún no se ha recibido ningún anuncio 0x181B.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                return@Column
            }
            DiagRow("Recibido", Fmt.dateTime(debug.receivedAtMillis) + ":" + String.format(Fmt.LOCALE, "%02d", (debug.receivedAtMillis / 1000) % 60))
            DiagRow("Dirección", debug.address + if (debug.ignoredByPin) " (ignorada)" else "")
            DiagRow("Nombre", debug.deviceName ?: "—")
            DiagRow("RSSI", "${debug.rssi} dBm")
            DiagRow("Trama", debug.rawHex, mono = true)
            val f = debug.frame
            if (f == null) {
                DiagRow("Decodificación", "Longitud no válida (se esperan 13 bytes)")
            } else {
                DiagRow("Peso", "${Fmt.num(f.weightKg, 3)} kg (bruto ${f.rawWeight}, unidad ${f.unit.label})")
                DiagRow("Estable", if (f.stabilized) "sí" else "no")
                DiagRow("Carga retirada", if (f.loadRemoved) "sí" else "no")
                DiagRow("Impedancia", if (f.hasImpedance) (f.impedance?.let { "$it Ω" } ?: "no válida") else "no")
                DiagRow("Hora báscula", f.scaleTimeKey)
            }
        }
    }
}

@Composable
private fun DiagRow(label: String, value: String, mono: Boolean = false) {
    Row {
        Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.width(128.dp))
        Text(
            value,
            style = MaterialTheme.typography.bodySmall.merge(TabularNumbers),
            fontFamily = if (mono) FontFamily.Monospace else null,
            fontWeight = if (mono) FontWeight.Medium else null,
        )
    }
}

private fun scanStatusText(s: ScanStatus): String = when (s) {
    ScanStatus.Stopped -> "Detenido"
    ScanStatus.NoBluetoothLe -> "Sin Bluetooth LE"
    ScanStatus.MissingPermission -> "Falta permiso"
    ScanStatus.BluetoothOff -> "Bluetooth apagado"
    ScanStatus.Starting -> "Iniciando escaneo…"
    is ScanStatus.Scanning -> if (s.locationOff) "Escaneando (ubicación apagada)" else "Escaneando"
    is ScanStatus.Failed -> "Error ${s.code}: ${s.message}"
}

private fun liveText(l: LiveScale): String = when (l) {
    LiveScale.Idle -> "En espera"
    is LiveScale.Connecting -> "Conectando con ${l.scaleName}"
    is LiveScale.Measuring -> "Midiendo ${Fmt.kg(l.weightKg)}"
    is LiveScale.Stabilized -> "Estable ${Fmt.kg(l.weightKg)}, esperando impedancia"
    is LiveScale.Saved -> "Guardada ${Fmt.kg(l.weightKg)}" + (l.impedance?.let { " · $it Ω" } ?: " · sin impedancia")
    is LiveScale.SaveFailed -> "Error al guardar ${Fmt.kg(l.weightKg)}"
}
