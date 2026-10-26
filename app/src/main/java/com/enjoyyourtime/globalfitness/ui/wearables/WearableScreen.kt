@file:OptIn(ExperimentalMaterial3Api::class)

package com.enjoyyourtime.globalfitness.ui.wearables

import android.bluetooth.BluetoothAdapter
import android.content.Intent
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.enjoyyourtime.globalfitness.R
import com.enjoyyourtime.globalfitness.band.BandLogLine
import com.enjoyyourtime.globalfitness.band.BandPermissions
import com.enjoyyourtime.globalfitness.band.HeartRateSample
import com.enjoyyourtime.globalfitness.band.xiaomi.AlarmSpec
import com.enjoyyourtime.globalfitness.band.xiaomi.BandAlarm
import com.enjoyyourtime.globalfitness.ui.Fmt
import com.enjoyyourtime.globalfitness.ui.band.AlarmEditorDialog
import com.enjoyyourtime.globalfitness.ui.band.AlarmsCard
import com.enjoyyourtime.globalfitness.ui.band.ForwardingCard
import com.enjoyyourtime.globalfitness.ui.band.LiveHeartRateChart
import com.enjoyyourtime.globalfitness.ui.components.ExperimentalBadge
import com.enjoyyourtime.globalfitness.ui.components.InfoRow
import com.enjoyyourtime.globalfitness.ui.components.SectionTitle
import com.enjoyyourtime.globalfitness.ui.components.TopBarTitle
import com.enjoyyourtime.globalfitness.ui.theme.TabularNumbers
import com.enjoyyourtime.globalfitness.wearable.AuthKeyKind
import com.enjoyyourtime.globalfitness.wearable.ConfiguredWearable
import com.enjoyyourtime.globalfitness.wearable.VendorApps
import com.enjoyyourtime.globalfitness.wearable.WearableConnection
import com.enjoyyourtime.globalfitness.wearable.WearableDriver
import com.enjoyyourtime.globalfitness.wearable.WearableFailure
import com.enjoyyourtime.globalfitness.wearable.WearableFamily
import com.enjoyyourtime.globalfitness.wearable.WearableFeature
import com.enjoyyourtime.globalfitness.wearable.WearableState
import com.enjoyyourtime.globalfitness.wearable.xiaomi.XiaomiDriver

@Composable
fun WearableScreen(viewModel: WearableViewModel, onBack: () -> Unit) {
    val device by viewModel.device.collectAsStateWithLifecycle()
    val state by viewModel.state.collectAsStateWithLifecycle()
    val heartRate by viewModel.heartRate.collectAsStateWithLifecycle()
    val log by viewModel.log.collectAsStateWithLifecycle()
    val hasKey by viewModel.hasKey.collectAsStateWithLifecycle()
    val syncTimeOnConnect by viewModel.syncTimeOnConnect.collectAsStateWithLifecycle()
    val forwardingEnabled by viewModel.forwardingEnabled.collectAsStateWithLifecycle()
    val allowedPackages by viewModel.allowedPackages.collectAsStateWithLifecycle()
    val seenApps by viewModel.seenApps.collectAsStateWithLifecycle()
    val driver = viewModel.driver

    val context = LocalContext.current
    val snackbar = remember { SnackbarHostState() }
    var showWizard by rememberSaveable { mutableStateOf(false) }
    var editor by remember { mutableStateOf<AlarmEditorTarget?>(null) }
    var permissionsGranted by remember { mutableStateOf(BandPermissions.hasConnect(context) && BandPermissions.hasScan(context)) }
    var vendorInstalled by remember { mutableStateOf(driver?.let { VendorApps.installed(context, it.family) }) }

    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        permissionsGranted = BandPermissions.hasConnect(context) && BandPermissions.hasScan(context)
        if (BandPermissions.hasConnect(context)) viewModel.refreshBonded()
    }
    val enableBluetooth = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {}

    val name = device?.name ?: driver?.displayName.orEmpty()
    LaunchedEffect(Unit) {
        viewModel.messages.collect {
            snackbar.currentSnackbarData?.dismiss()
            snackbar.showSnackbar(it.toUiText(name, driver?.vendorApp).resolve(context), withDismissAction = true)
        }
    }
    LifecycleResumeEffect(Unit) {
        permissionsGranted = BandPermissions.hasConnect(context) && BandPermissions.hasScan(context)
        vendorInstalled = driver?.let { VendorApps.installed(context, it.family) }
        onPauseOrDispose { }
    }

    if (showWizard && driver != null && device != null) {
        BackHandler {
            viewModel.clearKeyInput()
            showWizard = false
        }
        WearableKeyWizardScreen(
            viewModel = viewModel,
            driver = driver,
            onDone = { showWizard = false },
            onBack = {
                viewModel.clearKeyInput()
                showWizard = false
            },
        )
        return
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        TopBarTitle(name, Modifier.weight(1f, fill = false))
                        Spacer(Modifier.width(8.dp))
                        if (driver?.verified != true) ExperimentalBadge()
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.action_back)) }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        val current = device
        if (current == null || driver == null) {
            Column(Modifier.padding(padding).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(stringResource(R.string.wearable_failure_unknown), style = MaterialTheme.typography.bodyLarge)
                Button(onClick = onBack) { Text(stringResource(R.string.action_back)) }
            }
            return@Scaffold
        }
        val ready = state.connection == WearableConnection.READY
        LazyColumn(
            modifier = Modifier.padding(padding).fillMaxSize(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item { IntroCard(driver) }
            if (!permissionsGranted) {
                item { PermissionCard(onRequest = { permissionLauncher.launch(BandPermissions.required()) }) }
            }
            if (driver.needsAuthKey) {
                item { SectionTitle(stringResource(keyTitleRes(driver.authKey))) }
                item {
                    KeyCard(
                        driver = driver,
                        device = current,
                        hasKey = hasKey,
                        onSetUp = { showWizard = true },
                        onClear = viewModel::clearKey,
                        onDeviceId = viewModel::setXiaomiDeviceId,
                    )
                }
            }

            item { SectionTitle(stringResource(R.string.diag_status)) }
            item {
                StatusCard(
                    state = state,
                    driver = driver,
                    device = current,
                    canConnect = !driver.needsAuthKey || hasKey,
                    vendorLabel = vendorInstalled?.label,
                    onConnect = viewModel::connect,
                    onDisconnect = viewModel::disconnect,
                    onRefresh = viewModel::refreshStatus,
                    onEnableBluetooth = { runCatching { enableBluetooth.launch(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE)) } },
                    onOpenVendorSettings = vendorInstalled?.let { app -> { runCatching { context.startActivity(VendorApps.settingsIntent(app)) } } },
                )
            }

            if (ready) {
                val hasActions = listOf(WearableFeature.FIND_DEVICE, WearableFeature.TIME_SYNC, WearableFeature.NOTIFICATIONS).any(driver::supports)
                if (hasActions) {
                    item { SectionTitle(stringResource(R.string.band_actions)) }
                    item {
                        ActionsCard(
                            driver = driver,
                            state = state,
                            syncTimeOnConnect = syncTimeOnConnect,
                            onFind = viewModel::findDevice,
                            onSyncTime = viewModel::syncTime,
                            onSyncTimeOnConnect = viewModel::setSyncTimeOnConnect,
                            onTestNotification = viewModel::sendTestNotification,
                        )
                    }
                }
                if (driver.supports(WearableFeature.LIVE_HEART_RATE)) {
                    item { LiveCard(driver = driver, state = state, samples = heartRate, onToggle = viewModel::setLiveHeartRate) }
                }
                if (driver.supports(WearableFeature.ALARMS)) {
                    item { SectionTitle(stringResource(R.string.wearable_alarms_section)) }
                    item {
                        val readBack = driver.family == WearableFamily.XIAOMI || driver.family == WearableFamily.ZEPP_OS
                        AlarmsCard(
                            alarms = state.alarms,
                            maxAlarms = state.maxAlarms,
                            busy = state.alarmsBusy,
                            onRefresh = viewModel::refreshAlarms,
                            onAdd = { editor = AlarmEditorTarget(null, null) },
                            onEdit = { alarm: BandAlarm -> editor = AlarmEditorTarget(alarm.id, alarm.spec) },
                            onToggle = { alarm, enabled -> viewModel.saveAlarm(alarm.id, alarm.spec.copy(enabled = enabled)) },
                            onDelete = { alarm -> viewModel.deleteAlarm(alarm.id) },
                            emptyText = stringResource(R.string.wearable_alarms_none),
                            footerText = stringResource(if (readBack) R.string.wearable_alarms_read_back else R.string.wearable_alarms_local),
                            canRefresh = readBack,
                        )
                    }
                }
            }

            if (driver.supports(WearableFeature.NOTIFICATIONS)) {
                item { SectionTitle(stringResource(R.string.band_forwarding_title)) }
                item {
                    ForwardingCard(
                        enabled = forwardingEnabled,
                        allowed = allowedPackages,
                        seenApps = seenApps,
                        connected = ready,
                        onEnabled = viewModel::setForwardingEnabled,
                        onAllowed = viewModel::setPackageAllowed,
                        text = driver.vendorApp?.let { stringResource(R.string.wearable_forwarding_text, it) }
                            ?: stringResource(R.string.wearable_forwarding_text_plain),
                        pendingText = stringResource(R.string.wearable_forwarding_pending),
                    )
                }
            }

            item { DiagnosticsCard(lines = log, onClear = viewModel::clearLog) }
        }
    }

    editor?.let { target ->
        val smart = driver?.family == WearableFamily.XIAOMI || driver?.family == WearableFamily.ZEPP_OS
        AlarmEditorDialog(
            initial = target.spec,
            isNew = target.id == null,
            allowSmart = smart,
            onSave = { spec ->
                viewModel.saveAlarm(target.id, spec)
                editor = null
            },
            onDismiss = { editor = null },
        )
    }
}

private data class AlarmEditorTarget(val id: Int?, val spec: AlarmSpec?)

fun keyTitleRes(kind: AuthKeyKind): Int =
    if (kind == AuthKeyKind.NUMERIC_USER_ID) R.string.wearable_key_user_id_title else R.string.band_key_title

@Composable
private fun IntroCard(driver: WearableDriver) {
    val uri = LocalUriHandler.current
    OutlinedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("${driver.brand} · ${driver.displayName}", style = MaterialTheme.typography.titleSmall)
            Text(
                driver.vendorApp?.let { stringResource(R.string.wearable_intro, it) } ?: stringResource(R.string.wearable_intro_plain),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(transportLabel(driver.transport), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (!driver.verified) {
                Text(stringResource(R.string.wearable_experimental), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.tertiary)
            }
            driver.docUrl?.let { url ->
                TextButton(onClick = { runCatching { uri.openUri(url) } }) { Text(stringResource(R.string.wearable_doc_link)) }
            }
        }
    }
}

@Composable
private fun PermissionCard(onRequest: () -> Unit) {
    OutlinedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.wearable_permission_text), style = MaterialTheme.typography.bodyMedium)
            Button(onClick = onRequest) { Text(stringResource(R.string.action_grant_permission)) }
        }
    }
}

@Composable
private fun KeyCard(
    driver: WearableDriver,
    device: ConfiguredWearable,
    hasKey: Boolean,
    onSetUp: () -> Unit,
    onClear: () -> Unit,
    onDeviceId: (String) -> Unit,
) {
    OutlinedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (hasKey) {
                Text(stringResource(R.string.band_key_saved), style = MaterialTheme.typography.bodyMedium)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = onSetUp) { Text(stringResource(R.string.band_key_change)) }
                    TextButton(onClick = onClear) { Text(stringResource(R.string.action_delete)) }
                }
            } else {
                Text(
                    driver.vendorApp?.let { stringResource(R.string.wearable_key_missing, it) } ?: stringResource(R.string.wearable_failure_no_key),
                    style = MaterialTheme.typography.bodyMedium,
                )
                Button(onClick = onSetUp) { Text(stringResource(R.string.wearable_key_setup)) }
            }
            if ((driver as? XiaomiDriver)?.model?.usesDeviceId == true) {
                var value by remember(device.config) { mutableStateOf(device.config[ConfiguredWearable.CONFIG_XIAOMI_DEVICE_ID].orEmpty()) }
                OutlinedTextField(
                    value = value,
                    onValueChange = { value = it.take(64) },
                    label = { Text(stringResource(R.string.wearable_xiaomi_device_id)) },
                    supportingText = { Text(stringResource(R.string.wearable_xiaomi_device_id_help)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                if (value != device.config[ConfiguredWearable.CONFIG_XIAOMI_DEVICE_ID].orEmpty()) {
                    OutlinedButton(onClick = { onDeviceId(value) }) { Text(stringResource(R.string.action_save)) }
                }
            }
        }
    }
}

@Composable
private fun StatusCard(
    state: WearableState,
    driver: WearableDriver,
    device: ConfiguredWearable,
    canConnect: Boolean,
    /** Installed vendor app (for its settings button), null when none is installed. */
    vendorLabel: String?,
    onConnect: () -> Unit,
    onDisconnect: () -> Unit,
    onRefresh: () -> Unit,
    onEnableBluetooth: () -> Unit,
    onOpenVendorSettings: (() -> Unit)?,
) {
    val context = LocalContext.current
    OutlinedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (state.connection == WearableConnection.CONNECTING) {
                    CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(8.dp))
                }
                Text(connectionLabel(state.connection, state.protocol), style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            }
            Text("${device.name} · ${device.address}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)

            state.batteryLevel?.let { level ->
                val battery = Fmt.percent(level.toDouble())
                InfoRow(stringResource(R.string.band_battery), if (state.charging == true) stringResource(R.string.band_battery_charging, battery) else battery)
            }
            state.worn?.let { InfoRow(stringResource(R.string.band_worn), stringResource(if (it) R.string.common_yes else R.string.common_no)) }
            state.info?.let { info ->
                info.model?.let { InfoRow(stringResource(R.string.band_model), it) }
                info.firmware?.let { InfoRow(stringResource(R.string.band_firmware), it) }
                info.hardware?.let { InfoRow(stringResource(R.string.wearable_hardware), it) }
                info.serial?.let { InfoRow(stringResource(R.string.band_serial), it) }
            }
            state.lastTimeSyncMs?.let { InfoRow(stringResource(R.string.band_time_synced_at), Fmt.time(it)) }

            state.failure?.let { failure ->
                Text(
                    failureText(failure, state.busyElsewhere, driver.vendorApp).resolve(context),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            if (state.failure == WearableFailure.BLUETOOTH_OFF) {
                OutlinedButton(onClick = onEnableBluetooth) { Text(stringResource(R.string.action_turn_on_bluetooth)) }
            }

            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                when (state.connection) {
                    WearableConnection.DISCONNECTED -> Button(onClick = onConnect, enabled = canConnect) { Text(stringResource(R.string.onboarding_connect)) }
                    WearableConnection.CONNECTING -> OutlinedButton(onClick = onDisconnect) { Text(stringResource(R.string.action_cancel)) }
                    WearableConnection.READY -> {
                        Button(onClick = onDisconnect) { Text(stringResource(R.string.band_disconnect)) }
                        if (driver.supports(WearableFeature.BATTERY)) {
                            OutlinedButton(onClick = onRefresh) { Text(stringResource(R.string.band_refresh_battery)) }
                        }
                    }
                }
                if (state.connection == WearableConnection.DISCONNECTED && onOpenVendorSettings != null && vendorLabel != null) {
                    TextButton(onClick = onOpenVendorSettings) { Text(stringResource(R.string.wearable_vendor_settings, vendorLabel)) }
                }
            }
            if (!canConnect && state.connection == WearableConnection.DISCONNECTED) {
                Text(stringResource(R.string.wearable_cannot_connect_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            val vendor = driver.vendorApp
            if (state.releasedForVendorApp && state.connection == WearableConnection.DISCONNECTED && vendor != null) {
                Text(stringResource(R.string.wearable_released, vendor), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun ActionsCard(
    driver: WearableDriver,
    state: WearableState,
    syncTimeOnConnect: Boolean,
    onFind: (Boolean) -> Unit,
    onSyncTime: () -> Unit,
    onSyncTimeOnConnect: (Boolean) -> Unit,
    onTestNotification: () -> Unit,
) {
    OutlinedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                if (driver.supports(WearableFeature.FIND_DEVICE)) {
                    if (state.finding) {
                        Button(onClick = { onFind(false) }) { Text(stringResource(R.string.band_stop_find)) }
                    } else {
                        FilledTonalButton(onClick = { onFind(true) }) { Text(stringResource(R.string.wearable_find)) }
                    }
                }
                if (driver.supports(WearableFeature.TIME_SYNC)) {
                    FilledTonalButton(onClick = onSyncTime) { Text(stringResource(R.string.band_sync_time)) }
                }
                if (driver.supports(WearableFeature.NOTIFICATIONS)) {
                    FilledTonalButton(onClick = onTestNotification) { Text(stringResource(R.string.band_test_notification)) }
                }
            }
            if (state.finding) {
                Text(
                    stringResource(R.string.wearable_finding_text, stringResource(R.string.band_stop_find)),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (driver.supports(WearableFeature.TIME_SYNC)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.band_sync_time_on_connect), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                    Switch(checked = syncTimeOnConnect, onCheckedChange = onSyncTimeOnConnect)
                }
            }
        }
    }
}

@Composable
private fun LiveCard(driver: WearableDriver, state: WearableState, samples: List<HeartRateSample>, onToggle: (Boolean) -> Unit) {
    OutlinedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.band_live_heart_rate), style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                Switch(checked = state.liveActive, onCheckedChange = onToggle)
            }
            val live = state.live
            if (state.liveActive && live == null) {
                Text(stringResource(R.string.wearable_waiting_data), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
                    Text(stringResource(R.string.wearable_measuring_hint), style = MaterialTheme.typography.bodySmall)
                }
                live.rrIntervalsMs.lastOrNull()?.let {
                    InfoRow(stringResource(R.string.wearable_rr_interval), stringResource(R.string.value_ms, Fmt.count(it.toLong())))
                }
                live.steps?.let {
                    val label = if (driver.family == WearableFamily.BANGLEJS) R.string.wearable_steps_session else R.string.band_steps_today
                    InfoRow(stringResource(label), Fmt.count(it.toLong()))
                }
                live.calories?.let { InfoRow(stringResource(R.string.band_calories), stringResource(R.string.value_kcal, Fmt.count(it.toLong()))) }
                live.standingHours?.let { InfoRow(stringResource(R.string.band_standing_hours), Fmt.count(it.toLong())) }
            }
            if (samples.size >= 2) LiveHeartRateChart(samples)
            Text(stringResource(R.string.band_live_storage_text), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
                Text(stringResource(R.string.band_log_text), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (lines.isEmpty()) Text(stringResource(R.string.band_log_empty), style = MaterialTheme.typography.bodySmall)
                lines.takeLast(MAX_VISIBLE_LOG_LINES).asReversed().forEach { line ->
                    Text("${Fmt.timeWithSeconds(line.timeMs)}  ${line.text}", style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
                }
                if (lines.isNotEmpty()) TextButton(onClick = onClear) { Text(stringResource(R.string.band_log_clear)) }
            }
        }
    }
}

private const val MAX_VISIBLE_LOG_LINES = 120
