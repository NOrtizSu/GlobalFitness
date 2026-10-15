@file:OptIn(ExperimentalMaterial3Api::class)

package com.enjoyyourtime.globalfitness.ui.band

import android.bluetooth.BluetoothAdapter
import android.content.Intent
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.enjoyyourtime.globalfitness.band.BandConnectionState
import com.enjoyyourtime.globalfitness.band.BandLogLine
import com.enjoyyourtime.globalfitness.band.BandPermissions
import com.enjoyyourtime.globalfitness.band.BandState
import com.enjoyyourtime.globalfitness.band.HeartRateSample
import com.enjoyyourtime.globalfitness.band.MiFitness
import com.enjoyyourtime.globalfitness.band.SelectedBand
import com.enjoyyourtime.globalfitness.band.xiaomi.AlarmSpec
import com.enjoyyourtime.globalfitness.band.xiaomi.BandAlarm
import com.enjoyyourtime.globalfitness.band.xiaomi.BandFailure
import com.enjoyyourtime.globalfitness.ui.Fmt
import com.enjoyyourtime.globalfitness.ui.components.SectionTitle
import com.enjoyyourtime.globalfitness.ui.theme.TabularNumbers


@Composable
fun BandScreen(viewModel: BandViewModel, onBack: () -> Unit) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val heartRate by viewModel.heartRate.collectAsStateWithLifecycle()
    val log by viewModel.log.collectAsStateWithLifecycle()
    val hasKey by viewModel.hasKey.collectAsStateWithLifecycle()
    val keyInput by viewModel.keyInput.collectAsStateWithLifecycle()
    val keyError by viewModel.keyError.collectAsStateWithLifecycle()
    val selected by viewModel.selected.collectAsStateWithLifecycle()
    val candidates by viewModel.candidates.collectAsStateWithLifecycle()
    val scanning by viewModel.scanning.collectAsStateWithLifecycle()
    val syncTimeOnConnect by viewModel.syncTimeOnConnect.collectAsStateWithLifecycle()
    val forwardingEnabled by viewModel.forwardingEnabled.collectAsStateWithLifecycle()
    val allowedPackages by viewModel.allowedPackages.collectAsStateWithLifecycle()
    val seenApps by viewModel.seenApps.collectAsStateWithLifecycle()

    val context = LocalContext.current
    val snackbar = remember { SnackbarHostState() }
    var showHelp by rememberSaveable { mutableStateOf(false) }
    var editor by remember { mutableStateOf<AlarmEditorTarget?>(null) }
    var permissionsGranted by remember { mutableStateOf(BandPermissions.hasConnect(context) && BandPermissions.hasScan(context)) }
    var miFitnessPackage by remember { mutableStateOf(MiFitness.installedPackage(context)) }

    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        permissionsGranted = BandPermissions.hasConnect(context) && BandPermissions.hasScan(context)
        if (BandPermissions.hasConnect(context)) viewModel.refreshBonded()
    }
    val enableBluetooth = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {}

    LaunchedEffect(Unit) {
        viewModel.messages.collect { snackbar.showSnackbar(it, withDismissAction = true) }
    }
    LifecycleResumeEffect(Unit) {
        permissionsGranted = BandPermissions.hasConnect(context) && BandPermissions.hasScan(context)
        miFitnessPackage = MiFitness.installedPackage(context)
        if (BandPermissions.hasConnect(context)) viewModel.refreshBonded()
        onPauseOrDispose { }
    }

    if (showHelp) {
        BackHandler { showHelp = false }
        BandKeyHelpScreen(onBack = { showHelp = false })
        return
    }

    val ready = state.connection == BandConnectionState.READY
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Pulsera (modo directo)")
                        Spacer(Modifier.width(8.dp))
                        ExperimentalBadge()
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Volver") }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.padding(padding).fillMaxSize(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item { IntroCard() }
            if (!permissionsGranted) {
                item { PermissionCard(onRequest = { permissionLauncher.launch(BandPermissions.required()) }) }
            }

            item { SectionTitle("Clave de autenticación") }
            item {
                AuthKeyCard(
                    hasKey = hasKey,
                    input = keyInput,
                    error = keyError,
                    onInput = viewModel::onKeyInput,
                    onSave = viewModel::saveKey,
                    onClear = viewModel::clearKey,
                    onHelp = { showHelp = true },
                )
            }

            item { SectionTitle("Tu pulsera") }
            item {
                DevicePickerCard(
                    selected = selected,
                    candidates = candidates,
                    scanning = scanning,
                    canScan = permissionsGranted,
                    onScan = viewModel::startScan,
                    onStopScan = viewModel::stopScan,
                    onSelect = viewModel::select,
                )
            }

            item { SectionTitle("Estado") }
            item {
                StatusCard(
                    state = state,
                    selected = selected,
                    canConnect = hasKey && selected != null,
                    onConnect = viewModel::connect,
                    onDisconnect = viewModel::disconnect,
                    onRefresh = viewModel::refreshStatus,
                    onEnableBluetooth = {
                        runCatching { enableBluetooth.launch(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE)) }
                    },
                    onOpenMiFitnessSettings = miFitnessPackage?.let { pkg ->
                        fun() {
                            runCatching { context.startActivity(MiFitness.settingsIntent(pkg)) }
                        }
                    },
                )
            }

            if (ready) {
                item { SectionTitle("Acciones") }
                item {
                    ActionsCard(
                        state = state,
                        syncTimeOnConnect = syncTimeOnConnect,
                        onFind = viewModel::findDevice,
                        onSyncTime = viewModel::syncTime,
                        onSyncTimeOnConnect = viewModel::setSyncTimeOnConnect,
                        onTestNotification = viewModel::sendTestNotification,
                    )
                }
                item { LiveCard(state = state, samples = heartRate, onToggle = viewModel::setLiveHeartRate) }

                item { SectionTitle("Alarmas de la pulsera") }
                item {
                    AlarmsCard(
                        alarms = state.alarms,
                        maxAlarms = state.maxAlarms,
                        busy = state.alarmsBusy,
                        onRefresh = viewModel::refreshAlarms,
                        onAdd = { editor = AlarmEditorTarget(null, null) },
                        onEdit = { alarm: BandAlarm -> editor = AlarmEditorTarget(alarm.id, alarm.spec) },
                        onToggle = { alarm, enabled -> viewModel.saveAlarm(alarm.id, alarm.spec.copy(enabled = enabled)) },
                        onDelete = { alarm -> viewModel.deleteAlarm(alarm.id) },
                    )
                }
            }

            item { SectionTitle("Reenvío de notificaciones") }
            item {
                ForwardingCard(
                    enabled = forwardingEnabled,
                    allowed = allowedPackages,
                    seenApps = seenApps,
                    connected = ready,
                    onEnabled = viewModel::setForwardingEnabled,
                    onAllowed = viewModel::setPackageAllowed,
                )
            }

            item { DiagnosticsCard(lines = log, onClear = viewModel::clearLog) }
        }
    }

    editor?.let { target ->
        AlarmEditorDialog(
            initial = target.spec,
            isNew = target.id == null,
            onSave = { spec ->
                viewModel.saveAlarm(target.id, spec)
                editor = null
            },
            onDismiss = { editor = null },
        )
    }
}

private data class AlarmEditorTarget(val id: Int?, val spec: AlarmSpec?)

@Composable
fun ExperimentalBadge() {
    Text(
        "Experimental",
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onTertiaryContainer,
        modifier = Modifier
            .background(MaterialTheme.colorScheme.tertiaryContainer, RoundedCornerShape(50))
            .padding(horizontal = 8.dp, vertical = 2.dp),
    )
}

@Composable
private fun IntroCard() {
    OutlinedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Conexión directa con la Xiaomi Smart Band 9", style = MaterialTheme.typography.titleSmall)
            Text(
                "La app habla con la pulsera por Bluetooth (como Gadgetbridge), sin pasar por Mi Fitness. " +
                    "La pulsera solo admite una conexión: mientras esta pantalla está conectada, Mi Fitness no puede sincronizar. " +
                    "Por eso la conexión se abre solo cuando pulsas «Conectar» y se cierra sola tras 2 minutos sin uso o al salir.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                "Función experimental: no se ha probado con una pulsera real.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.tertiary,
            )
        }
    }
}

@Composable
private fun PermissionCard(onRequest: () -> Unit) {
    OutlinedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                "Para buscar y conectar la pulsera hace falta el permiso «Dispositivos cercanos».",
                style = MaterialTheme.typography.bodyMedium,
            )
            Button(onClick = onRequest) { Text("Conceder permiso") }
        }
    }
}

@Composable
private fun StatusCard(
    state: BandState,
    selected: SelectedBand?,
    canConnect: Boolean,
    onConnect: () -> Unit,
    onDisconnect: () -> Unit,
    onRefresh: () -> Unit,
    onEnableBluetooth: () -> Unit,
    /** Null when no Mi Fitness variant is installed. */
    onOpenMiFitnessSettings: (() -> Unit)?,
) {
    OutlinedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                val busy = state.connection == BandConnectionState.CONNECTING || state.connection == BandConnectionState.AUTHENTICATING
                if (busy) {
                    CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(8.dp))
                }
                Text(connectionLabel(state), style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            }
            selected?.let {
                Text("${it.name} · ${it.address}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }

            state.batteryLevel?.let { level ->
                val charging = when (state.charging) {
                    true -> " · cargando"
                    else -> ""
                }
                InfoRow("Batería", "$level %$charging")
            }
            state.worn?.let { InfoRow("En la muñeca", if (it) "Sí" else "No") }
            state.info?.let { info ->
                InfoRow("Modelo", info.model)
                InfoRow("Firmware", info.firmware)
                InfoRow("N.º de serie", info.serial, mono = true)
            }
            state.lastTimeSyncMs?.let { InfoRow("Hora sincronizada", Fmt.time(it)) }

            state.failureMessage?.let { message ->
                Text(message, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
            }
            if (state.failure == BandFailure.BLUETOOTH_OFF) {
                OutlinedButton(onClick = onEnableBluetooth) { Text("Activar Bluetooth") }
            }

            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                when (state.connection) {
                    BandConnectionState.DISCONNECTED -> Button(onClick = onConnect, enabled = canConnect) { Text("Conectar") }
                    BandConnectionState.CONNECTING, BandConnectionState.AUTHENTICATING ->
                        OutlinedButton(onClick = onDisconnect) { Text("Cancelar") }
                    BandConnectionState.READY -> {
                        Button(onClick = onDisconnect) { Text("Desconectar") }
                        OutlinedButton(onClick = onRefresh) { Text("Actualizar batería") }
                    }
                }
                if (state.connection == BandConnectionState.DISCONNECTED && onOpenMiFitnessSettings != null) {
                    TextButton(onClick = onOpenMiFitnessSettings) { Text("Ajustes de Mi Fitness") }
                }
            }
            if (!canConnect && state.connection == BandConnectionState.DISCONNECTED) {
                Text(
                    "Guarda la clave y elige tu pulsera para poder conectar.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (state.releasedForMiFitness && state.connection == BandConnectionState.DISCONNECTED) {
                Text(
                    "La pulsera vuelve a estar libre. Si detuviste Mi Fitness, ábrelo de nuevo para que siga sincronizando con Health Connect.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

private fun connectionLabel(state: BandState): String = when (state.connection) {
    BandConnectionState.DISCONNECTED -> "Desconectada"
    BandConnectionState.CONNECTING -> "Conectando…"
    BandConnectionState.AUTHENTICATING -> "Autenticando…"
    BandConnectionState.READY -> "Conectada" + (state.protocol?.let { " ($it)" } ?: "")
}

@Composable
private fun InfoRow(label: String, value: String, mono: Boolean = false) {
    Row(Modifier.fillMaxWidth()) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
        Text(
            value,
            style = MaterialTheme.typography.bodyMedium.merge(TabularNumbers),
            fontFamily = if (mono) FontFamily.Monospace else null,
        )
    }
}

@Composable
private fun ActionsCard(
    state: BandState,
    syncTimeOnConnect: Boolean,
    onFind: (Boolean) -> Unit,
    onSyncTime: () -> Unit,
    onSyncTimeOnConnect: (Boolean) -> Unit,
    onTestNotification: () -> Unit,
) {
    OutlinedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                if (state.finding) {
                    Button(onClick = { onFind(false) }) { Text("Parar búsqueda") }
                } else {
                    FilledTonalButton(onClick = { onFind(true) }) { Text("Buscar pulsera") }
                }
                FilledTonalButton(onClick = onSyncTime) { Text("Sincronizar hora") }
                FilledTonalButton(onClick = onTestNotification) { Text("Notificación de prueba") }
            }
            if (state.finding) {
                Text(
                    "La pulsera está vibrando. Púlsala para indicar que la encontraste o pulsa «Parar búsqueda».",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Sincronizar la hora al conectar", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                Switch(checked = syncTimeOnConnect, onCheckedChange = onSyncTimeOnConnect)
            }
        }
    }
}

@Composable
private fun LiveCard(state: BandState, samples: List<HeartRateSample>, onToggle: (Boolean) -> Unit) {
    OutlinedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Pulso en vivo", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                Switch(checked = state.liveActive, onCheckedChange = onToggle)
            }
            val live = state.live
            if (state.liveActive && live == null) {
                Text("Esperando datos de la pulsera…", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (live != null) {
                Row(verticalAlignment = Alignment.Bottom) {
                    Text(
                        live.heartRate?.toString() ?: "--",
                        style = MaterialTheme.typography.displaySmall.merge(TabularNumbers),
                        fontWeight = FontWeight.SemiBold,
                    )
                    Spacer(Modifier.width(6.dp))
                    Text("lpm", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(bottom = 6.dp))
                }
                if (live.heartRate == null) {
                    Text("Midiendo… mantén la pulsera ajustada a la muñeca.", style = MaterialTheme.typography.bodySmall)
                }
                InfoRow("Pasos de hoy", live.steps.toString())
                InfoRow("Calorías", "${live.calories} kcal")
                InfoRow("Horas de pie", live.standingHours.toString())
            }
            if (samples.size >= 2) LiveHeartRateChart(samples)
            Text(
                "Las lecturas se guardan en el teléfono (band.db) para poder combinarlas más adelante. " +
                    "Mientras el pulso en vivo está activo y ves esta pantalla, la conexión no se cierra por inactividad.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun DiagnosticsCard(lines: List<BandLogLine>, onClear: () -> Unit) {
    var open by rememberSaveable { mutableStateOf(false) }
    OutlinedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Diagnóstico", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                TextButton(onClick = { open = !open }) { Text(if (open) "Ocultar" else "Mostrar (${lines.size})") }
            }
            if (open) {
                Text(
                    "Registro de la conexión. Nunca incluye la clave de autenticación.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (lines.isEmpty()) {
                    Text("Sin entradas todavía.", style = MaterialTheme.typography.bodySmall)
                }
                lines.takeLast(MAX_VISIBLE_LOG_LINES).asReversed().forEach { line ->
                    Text(
                        "${Fmt.time(line.timeMs)}:${secondsOf(line.timeMs)}  ${line.text}",
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                    )
                }
                if (lines.isNotEmpty()) TextButton(onClick = onClear) { Text("Borrar registro") }
            }
        }
    }
}

private const val MAX_VISIBLE_LOG_LINES = 120

private fun secondsOf(ms: Long): String = "%02d".format(Fmt.LOCALE, (ms / 1000) % 60)
