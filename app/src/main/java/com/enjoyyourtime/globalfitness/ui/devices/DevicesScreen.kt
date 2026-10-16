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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.enjoyyourtime.globalfitness.R
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

/** Devices: scales, the experimental direct band mode, Health Connect and BLE diagnostics. */
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
    val context = LocalContext.current

    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) { scalesVm.keepDetectedFresh() }
    }
    LaunchedEffect(Unit) {
        scalesVm.events.collect {
            snackbar.currentSnackbarData?.dismiss()
            snackbar.showSnackbar(it.resolve(context), withDismissAction = true)
        }
    }

    TabPage(title = stringResource(R.string.nav_devices)) {
        item { SectionTitle(stringResource(R.string.devices_scales)) }
        scaleDevicesSection(
            scales, dialogs, onUse = scalesVm::useDetected, onSaveConfig = scalesVm::setConfig,
            onAcceptSuggestion = scalesVm::acceptSuggestion, onDeclineSuggestion = scalesVm::declineSuggestion,
        )

        item { SectionTitle(stringResource(R.string.devices_band)) }
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
                        bandStatus(band),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                ExperimentalBadge()
            }
            Text(
                stringResource(R.string.devices_band_text),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            FilledTonalButton(onClick = onOpen, modifier = Modifier.heightIn(min = 48.dp)) {
                Text(stringResource(R.string.devices_band_open))
            }
        }
    }
}

/** One line about the direct band mode (Today and Devices); [withMode] adds "Direct mode · ". */
@Composable
fun bandStatus(band: BandSummary?, withMode: Boolean = true): String {
    if (band == null) return stringResource(R.string.band_status_not_set_up)
    if (!band.hasKey) return stringResource(R.string.band_status_no_key)
    val state = band.state
    val connection = stringResource(
        when (state.connection) {
            BandConnectionState.DISCONNECTED -> R.string.band_status_disconnected
            BandConnectionState.CONNECTING -> R.string.band_status_connecting
            BandConnectionState.AUTHENTICATING -> R.string.band_status_authenticating
            BandConnectionState.READY -> R.string.band_status_connected
        },
    )
    val withBattery = state.batteryLevel?.takeIf { state.connection == BandConnectionState.READY }
        ?.let { stringResource(R.string.band_status_battery, connection, Fmt.percent(it.toDouble())) } ?: connection
    return if (withMode) stringResource(R.string.band_status_direct_mode, withBattery) else withBattery
}

@Composable
private fun DiagnosticsCard(
    open: Boolean,
    onToggle: () -> Unit,
    scanStatus: ScanStatus,
    live: LiveScale,
    debug: ScanDebugInfo?,
) {
    val expandedState = stringResource(if (open) R.string.diag_expanded else R.string.diag_collapsed)
    AppCard(onClick = onToggle, modifier = Modifier.padding(top = 8.dp)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.semantics { stateDescription = expandedState },
            ) {
                Text(stringResource(R.string.diag_title), style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                Icon(if (open) AppIcons.ExpandLess else AppIcons.ExpandMore, contentDescription = null)
            }
            if (!open) return@Column
            InfoRow(stringResource(R.string.diag_status), scanStatusText(scanStatus))
            InfoRow(stringResource(R.string.diag_live), liveText(live))
            if (debug == null) {
                Text(
                    stringResource(R.string.diag_no_advertisement),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                return@Column
            }
            InfoRow(stringResource(R.string.diag_received), Fmt.dayMonthYear(debug.receivedAtMillis) + ", " + Fmt.timeWithSeconds(debug.receivedAtMillis))
            InfoRow(
                stringResource(R.string.diag_address),
                if (debug.ignoredByPin) stringResource(R.string.diag_address_ignored, debug.address) else debug.address,
            )
            InfoRow(stringResource(R.string.diag_name), debug.deviceName ?: "—")
            InfoRow("RSSI", stringResource(R.string.value_dbm, Fmt.count(debug.rssi.toLong())))
            Text(stringResource(R.string.diag_frame), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(debug.rawHex, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace, modifier = Modifier.fillMaxWidth())
            val f = debug.frame
            if (f == null) {
                InfoRow(stringResource(R.string.diag_decoding), stringResource(R.string.diag_invalid_length))
            } else {
                val yes = stringResource(R.string.common_yes)
                val no = stringResource(R.string.common_no)
                InfoRow(stringResource(R.string.metric_weight), stringResource(R.string.diag_weight_raw, Fmt.kg(f.weightKg, 3), f.rawWeight.toString(), f.unit.label))
                InfoRow(stringResource(R.string.diag_stable), if (f.stabilized) yes else no)
                InfoRow(stringResource(R.string.diag_load_removed), if (f.loadRemoved) yes else no)
                InfoRow(
                    stringResource(R.string.metric_impedance),
                    if (f.hasImpedance) {
                        f.impedance?.let { stringResource(R.string.value_ohm, Fmt.count(it.toLong())) } ?: stringResource(R.string.diag_invalid)
                    } else {
                        no
                    },
                )
                InfoRow(stringResource(R.string.diag_scale_time), f.scaleTimeKey)
            }
        }
    }
}

@Composable
private fun scanStatusText(s: ScanStatus): String = when (s) {
    ScanStatus.Stopped -> stringResource(R.string.diag_scan_stopped)
    ScanStatus.NoBluetoothLe -> stringResource(R.string.chip_no_ble)
    ScanStatus.MissingPermission -> stringResource(R.string.diag_scan_permission)
    ScanStatus.BluetoothOff -> stringResource(R.string.chip_bluetooth_off)
    ScanStatus.Starting -> stringResource(R.string.diag_scan_starting)
    is ScanStatus.Scanning -> stringResource(if (s.locationOff) R.string.diag_scanning_location_off else R.string.diag_scanning)
    is ScanStatus.Failed -> stringResource(R.string.diag_scan_error, s.code, stringResource(scanErrorRes(s.code), s.code))
}

@Composable
private fun liveText(l: LiveScale): String = when (l) {
    LiveScale.Idle -> stringResource(R.string.diag_live_idle)
    is LiveScale.Connecting -> stringResource(R.string.live_connecting, l.scaleName)
    is LiveScale.Measuring -> stringResource(R.string.diag_live_measuring, Fmt.kg(l.weightKg))
    is LiveScale.Stabilized -> stringResource(R.string.diag_live_stable, Fmt.kg(l.weightKg))
    is LiveScale.Saved -> {
        val impedance = l.impedance?.let { stringResource(R.string.value_ohm, Fmt.count(it.toLong())) }
            ?: stringResource(R.string.diag_live_no_impedance)
        stringResource(R.string.diag_live_saved, Fmt.kg(l.weightKg), impedance)
    }
    is LiveScale.SaveFailed -> stringResource(R.string.live_save_failed, Fmt.kg(l.weightKg))
}
