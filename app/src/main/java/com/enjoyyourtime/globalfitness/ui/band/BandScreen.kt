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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.enjoyyourtime.globalfitness.R
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
import com.enjoyyourtime.globalfitness.ui.asString
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
        viewModel.messages.collect { snackbar.showSnackbar(it.toUiText().resolve(context), withDismissAction = true) }
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
                        Text(stringResource(R.string.band_screen_title))
                        Spacer(Modifier.width(8.dp))
                        ExperimentalBadge()
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.action_back)) }
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

            item { SectionTitle(stringResource(R.string.band_key_title)) }
            item {
                AuthKeyCard(
                    hasKey = hasKey,
                    input = keyInput,
                    error = keyError?.asString(),
                    onInput = viewModel::onKeyInput,
                    onSave = viewModel::saveKey,
                    onClear = viewModel::clearKey,
                    onHelp = { showHelp = true },
                )
            }

            item { SectionTitle(stringResource(R.string.band_your_band)) }
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

            item { SectionTitle(stringResource(R.string.diag_status)) }
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
                item { SectionTitle(stringResource(R.string.band_actions)) }
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

                item { SectionTitle(stringResource(R.string.band_alarms_section)) }
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

            item { SectionTitle(stringResource(R.string.band_forwarding_title)) }
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
        stringResource(R.string.common_experimental),
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
            Text(stringResource(R.string.band_intro_title), style = MaterialTheme.typography.titleSmall)
            Text(
                stringResource(R.string.band_intro_text),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                stringResource(R.string.band_intro_experimental),
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
                stringResource(R.string.band_permission_text),
                style = MaterialTheme.typography.bodyMedium,
            )
            Button(onClick = onRequest) { Text(stringResource(R.string.action_grant_permission)) }
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
                val battery = Fmt.percent(level.toDouble())
                InfoRow(
                    stringResource(R.string.band_battery),
                    if (state.charging == true) stringResource(R.string.band_battery_charging, battery) else battery,
                )
            }
            state.worn?.let { InfoRow(stringResource(R.string.band_worn), stringResource(if (it) R.string.common_yes else R.string.common_no)) }
            state.info?.let { info ->
                InfoRow(stringResource(R.string.band_model), info.model)
                InfoRow(stringResource(R.string.band_firmware), info.firmware)
                InfoRow(stringResource(R.string.band_serial), info.serial, mono = true)
            }
            state.lastTimeSyncMs?.let { InfoRow(stringResource(R.string.band_time_synced_at), Fmt.time(it)) }

            state.failure?.let { failure ->
                Text(
                    stringResource(if (state.failureBusy) R.string.band_failure_busy else failure.messageRes()),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            if (state.failure == BandFailure.BLUETOOTH_OFF) {
                OutlinedButton(onClick = onEnableBluetooth) { Text(stringResource(R.string.action_turn_on_bluetooth)) }
            }

            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                when (state.connection) {
                    BandConnectionState.DISCONNECTED -> Button(onClick = onConnect, enabled = canConnect) { Text(stringResource(R.string.onboarding_connect)) }
                    BandConnectionState.CONNECTING, BandConnectionState.AUTHENTICATING ->
                        OutlinedButton(onClick = onDisconnect) { Text(stringResource(R.string.action_cancel)) }
                    BandConnectionState.READY -> {
                        Button(onClick = onDisconnect) { Text(stringResource(R.string.band_disconnect)) }
                        OutlinedButton(onClick = onRefresh) { Text(stringResource(R.string.band_refresh_battery)) }
                    }
                }
                if (state.connection == BandConnectionState.DISCONNECTED && onOpenMiFitnessSettings != null) {
                    TextButton(onClick = onOpenMiFitnessSettings) { Text(stringResource(R.string.band_mi_fitness_settings)) }
                }
            }
            if (!canConnect && state.connection == BandConnectionState.DISCONNECTED) {
                Text(
                    stringResource(R.string.band_cannot_connect_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (state.releasedForMiFitness && state.connection == BandConnectionState.DISCONNECTED) {
                Text(
                    stringResource(R.string.band_released),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun connectionLabel(state: BandState): String = when (state.connection) {
    BandConnectionState.DISCONNECTED -> stringResource(R.string.band_status_disconnected)
    BandConnectionState.CONNECTING -> stringResource(R.string.band_status_connecting)
    BandConnectionState.AUTHENTICATING -> stringResource(R.string.band_status_authenticating)
    BandConnectionState.READY -> state.protocol?.let { stringResource(R.string.band_connected_protocol, it) }
        ?: stringResource(R.string.band_status_connected)
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
                    Button(onClick = { onFind(false) }) { Text(stringResource(R.string.band_stop_find)) }
                } else {
                    FilledTonalButton(onClick = { onFind(true) }) { Text(stringResource(R.string.band_find)) }
                }
                FilledTonalButton(onClick = onSyncTime) { Text(stringResource(R.string.band_sync_time)) }
                FilledTonalButton(onClick = onTestNotification) { Text(stringResource(R.string.band_test_notification)) }
            }
            if (state.finding) {
                Text(
                    stringResource(R.string.band_finding_text, stringResource(R.string.band_stop_find)),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.band_sync_time_on_connect), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
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
                Text(stringResource(R.string.band_live_heart_rate), style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                Switch(checked = state.liveActive, onCheckedChange = onToggle)
            }
            val live = state.live
            if (state.liveActive && live == null) {
                Text(stringResource(R.string.band_waiting_data), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (live != null) {
                Row(verticalAlignment = Alignment.Bottom) {
                    Text(
                        live.heartRate?.let { Fmt.count(it.toLong()) } ?: "--",
                        style = MaterialTheme.typography.displaySmall.merge(TabularNumbers),
                        fontWeight = FontWeight.SemiBold,
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(stringResource(R.string.unit_bpm), style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(bottom = 6.dp))
                }
                if (live.heartRate == null) {
                    Text(stringResource(R.string.band_measuring_hint), style = MaterialTheme.typography.bodySmall)
                }
                InfoRow(stringResource(R.string.band_steps_today), Fmt.count(live.steps.toLong()))
                InfoRow(stringResource(R.string.band_calories), stringResource(R.string.value_kcal, Fmt.count(live.calories.toLong())))
                InfoRow(stringResource(R.string.band_standing_hours), Fmt.count(live.standingHours.toLong()))
            }
            if (samples.size >= 2) LiveHeartRateChart(samples)
            Text(
                stringResource(R.string.band_live_storage_text),
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
                Text(stringResource(R.string.band_diagnostics), style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                TextButton(onClick = { open = !open }) {
                    Text(if (open) stringResource(R.string.band_log_hide) else stringResource(R.string.band_log_show, Fmt.count(lines.size.toLong())))
                }
            }
            if (open) {
                Text(
                    stringResource(R.string.band_log_text),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (lines.isEmpty()) {
                    Text(stringResource(R.string.band_log_empty), style = MaterialTheme.typography.bodySmall)
                }
                lines.takeLast(MAX_VISIBLE_LOG_LINES).asReversed().forEach { line ->
                    Text(
                        "${Fmt.timeWithSeconds(line.timeMs)}  ${line.text}",
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                    )
                }
                if (lines.isNotEmpty()) TextButton(onClick = onClear) { Text(stringResource(R.string.band_log_clear)) }
            }
        }
    }
}

private const val MAX_VISIBLE_LOG_LINES = 120
