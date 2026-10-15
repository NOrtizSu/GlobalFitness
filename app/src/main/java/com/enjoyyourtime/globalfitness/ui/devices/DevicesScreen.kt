package com.enjoyyourtime.globalfitness.ui.devices

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.enjoyyourtime.globalfitness.band.BandConnectionState
import com.enjoyyourtime.globalfitness.ble.LiveScale
import com.enjoyyourtime.globalfitness.ble.ScanDebugInfo
import com.enjoyyourtime.globalfitness.ble.ScanStatus
import com.enjoyyourtime.globalfitness.ui.BandSummary
import com.enjoyyourtime.globalfitness.ui.Fmt
import com.enjoyyourtime.globalfitness.ui.MainViewModel
import com.enjoyyourtime.globalfitness.ui.components.AppCard
import com.enjoyyourtime.globalfitness.ui.components.AppIcons
import com.enjoyyourtime.globalfitness.ui.components.ExperimentalBadge
import com.enjoyyourtime.globalfitness.ui.components.InfoRow
import com.enjoyyourtime.globalfitness.ui.components.LocalAppSnackbar
import com.enjoyyourtime.globalfitness.ui.components.SectionTitle
import com.enjoyyourtime.globalfitness.ui.components.TabPage
import com.enjoyyourtime.globalfitness.ui.health.HealthConnectionCard
import com.enjoyyourtime.globalfitness.ui.health.HealthViewModel

/** "Dispositivos": scales, the experimental direct band mode, Health Connect and BLE diagnostics. */
@Composable
fun DevicesScreen(
    mainVm: MainViewModel,
    healthVm: HealthViewModel,
    onConnectHealth: () -> Unit,
    onOpenBand: () -> Unit,
    scalesVm: ScaleDevicesViewModel = viewModel(factory = ScaleDevicesViewModel.Factory),
) {
    val scales by scalesVm.state.collectAsStateWithLifecycle()
    val health by healthVm.uiState.collectAsStateWithLifecycle()
    val band by mainVm.band.collectAsStateWithLifecycle()
    val scanStatus by mainVm.scanStatus.collectAsStateWithLifecycle()
    val live by mainVm.live.collectAsStateWithLifecycle()
    val debug by mainVm.debug.collectAsStateWithLifecycle()
    val dialogs = remember { ScaleDialogs() }
    var diagnosticsOpen by rememberSaveable { mutableStateOf(false) }
    val snackbar = LocalAppSnackbar.current

    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) { scalesVm.keepDetectedFresh() }
    }
    LaunchedEffect(Unit) {
        scalesVm.events.collect {
            snackbar.currentSnackbarData?.dismiss()
            snackbar.showSnackbar(it, withDismissAction = true)
        }
    }

    TabPage(title = "Dispositivos") {
        item { SectionTitle("Básculas") }
        scaleDevicesSection(
            scales, dialogs, onUse = scalesVm::useDetected, onSaveConfig = scalesVm::setConfig,
            onAcceptSuggestion = scalesVm::acceptSuggestion, onDeclineSuggestion = scalesVm::declineSuggestion,
        )

        item { SectionTitle("Pulsera") }
        item { BandCard(band, onOpenBand) }

        item { SectionTitle("Health Connect") }
        item {
            HealthConnectionCard(
                state = health,
                onConnect = onConnectHealth,
                onOpenSettings = { healthVm.openHealthConnectSettings() },
                onOpenPlayStore = { healthVm.openPlayStore() },
                showTitle = false,
            )
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

    ScaleDialogsHost(scales, dialogs, scalesVm)
}

@Composable
private fun BandCard(band: BandSummary?, onOpen: () -> Unit) {
    AppCard {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(AppIcons.Watch, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(band?.name ?: "Xiaomi Smart Band 9", style = MaterialTheme.typography.titleMedium)
                    Text(
                        bandStatusText(band),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                ExperimentalBadge()
            }
            Text(
                "Modo directo por Bluetooth, sin Mi Fitness: pulso en directo, buscar la pulsera, alarmas y " +
                    "notificaciones. Mientras está abierto ocupa la pulsera y Mi Fitness no puede sincronizar.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            FilledTonalButton(onClick = onOpen, modifier = Modifier.heightIn(min = 48.dp)) {
                Text("Abrir modo directo (experimental)")
            }
        }
    }
}

/** One line about the direct band mode (Hoy and Dispositivos). */
fun bandStatusText(band: BandSummary?): String {
    if (band == null) return "Sin configurar"
    if (!band.hasKey) return "Falta la clave de autenticación"
    val state = band.state
    val connection = when (state.connection) {
        BandConnectionState.DISCONNECTED -> "Desconectada"
        BandConnectionState.CONNECTING -> "Conectando…"
        BandConnectionState.AUTHENTICATING -> "Autenticando…"
        BandConnectionState.READY -> "Conectada"
    }
    val battery = state.batteryLevel?.takeIf { state.connection == BandConnectionState.READY }?.let { " · batería $it %" }.orEmpty()
    return "Modo directo · $connection$battery"
}

@Composable
private fun DiagnosticsCard(
    open: Boolean,
    onToggle: () -> Unit,
    scanStatus: ScanStatus,
    live: LiveScale,
    debug: ScanDebugInfo?,
) {
    AppCard(onClick = onToggle, modifier = Modifier.padding(top = 8.dp)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.semantics { stateDescription = if (open) "Desplegado" else "Plegado" },
            ) {
                Text("Diagnóstico BLE", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                Icon(if (open) AppIcons.ExpandLess else AppIcons.ExpandMore, contentDescription = null)
            }
            if (!open) return@Column
            InfoRow("Estado", scanStatusText(scanStatus))
            InfoRow("Medición en curso", liveText(live))
            if (debug == null) {
                Text(
                    "Aún no se ha recibido ningún anuncio de Mi Scale (0x181B).",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                return@Column
            }
            InfoRow("Recibido", Fmt.dateTime(debug.receivedAtMillis) + ":" + String.format(Fmt.LOCALE, "%02d", (debug.receivedAtMillis / 1000) % 60))
            InfoRow("Dirección", debug.address + if (debug.ignoredByPin) " (ignorada)" else "")
            InfoRow("Nombre", debug.deviceName ?: "—")
            InfoRow("RSSI", "${debug.rssi} dBm")
            Text("Trama", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(debug.rawHex, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace, modifier = Modifier.fillMaxWidth())
            val f = debug.frame
            if (f == null) {
                InfoRow("Decodificación", "Longitud no válida (13 bytes)")
            } else {
                InfoRow("Peso", "${Fmt.num(f.weightKg, 3)} kg (bruto ${f.rawWeight}, ${f.unit.label})")
                InfoRow("Estable", if (f.stabilized) "sí" else "no")
                InfoRow("Carga retirada", if (f.loadRemoved) "sí" else "no")
                InfoRow("Impedancia", if (f.hasImpedance) (f.impedance?.let { "$it Ω" } ?: "no válida") else "no")
                InfoRow("Hora báscula", f.scaleTimeKey)
            }
        }
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
