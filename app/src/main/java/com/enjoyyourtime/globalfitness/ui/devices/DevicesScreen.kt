@file:OptIn(ExperimentalMaterial3Api::class)

package com.enjoyyourtime.globalfitness.ui.devices

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalLayoutDirection
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
import kotlinx.coroutines.launch
import com.enjoyyourtime.globalfitness.R
import com.enjoyyourtime.globalfitness.ble.LiveScale
import com.enjoyyourtime.globalfitness.ble.ScanDebugInfo
import com.enjoyyourtime.globalfitness.ble.ScanStatus
import com.enjoyyourtime.globalfitness.ui.Fmt
import com.enjoyyourtime.globalfitness.ui.MainViewModel
import com.enjoyyourtime.globalfitness.ui.components.AppCard
import com.enjoyyourtime.globalfitness.ui.components.AppIcons
import com.enjoyyourtime.globalfitness.ui.components.InfoRow
import com.enjoyyourtime.globalfitness.ui.components.SectionTitle
import com.enjoyyourtime.globalfitness.ui.components.TopBarTitle
import com.enjoyyourtime.globalfitness.ui.health.HealthConnectionCard
import com.enjoyyourtime.globalfitness.ui.health.HealthViewModel
import com.enjoyyourtime.globalfitness.ui.wearables.WearableDialogs
import com.enjoyyourtime.globalfitness.ui.wearables.WearableDialogsHost
import com.enjoyyourtime.globalfitness.ui.wearables.WearablesEvent
import com.enjoyyourtime.globalfitness.ui.wearables.WearablesViewModel
import com.enjoyyourtime.globalfitness.ui.wearables.wearablesSection

/**
 * Devices (a sub-page of Perfil, also opened from Hoy's scale and band chips): scales, the
 * experimental direct band mode, Health Connect and BLE diagnostics. Its messages use the page's own
 * snackbar, which sits above the system navigation bar now that there is no bottom bar.
 */
@Composable
fun DevicesScreen(
    mainVm: MainViewModel,
    healthVm: HealthViewModel,
    onConnectHealth: () -> Unit,
    onOpenWearable: (String) -> Unit,
    onBack: () -> Unit,
    scalesVm: ScaleDevicesViewModel = viewModel(factory = ScaleDevicesViewModel.Factory),
    wearablesVm: WearablesViewModel = viewModel(factory = WearablesViewModel.Factory),
) {
    val scales by scalesVm.state.collectAsStateWithLifecycle()
    val health by healthVm.uiState.collectAsStateWithLifecycle()
    val wearables by wearablesVm.state.collectAsStateWithLifecycle()
    val scanStatus by mainVm.scanStatus.collectAsStateWithLifecycle()
    val live by mainVm.live.collectAsStateWithLifecycle()
    val debug by mainVm.debug.collectAsStateWithLifecycle()
    val dialogs = remember { ScaleDialogs() }
    val wearableDialogs = remember { WearableDialogs() }
    var diagnosticsOpen by rememberSaveable { mutableStateOf(false) }
    val snackbar = remember { SnackbarHostState() }
    val context = LocalContext.current

    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            launch { scalesVm.keepDetectedFresh() }
            launch { wearablesVm.keepDetectedFresh() }
        }
    }
    LaunchedEffect(Unit) {
        wearablesVm.events.collect { event ->
            when (event) {
                is WearablesEvent.Open -> onOpenWearable(event.deviceId)
                is WearablesEvent.Message -> {
                    snackbar.currentSnackbarData?.dismiss()
                    snackbar.showSnackbar(event.text.resolve(context), withDismissAction = true)
                }
            }
        }
    }
    LaunchedEffect(Unit) {
        scalesVm.events.collect {
            snackbar.currentSnackbarData?.dismiss()
            snackbar.showSnackbar(it.resolve(context), withDismissAction = true)
        }
    }

    val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior()
    val direction = LocalLayoutDirection.current
    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            TopAppBar(
                title = { TopBarTitle(stringResource(R.string.nav_devices)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.action_back))
                    }
                },
                scrollBehavior = scrollBehavior,
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                    scrolledContainerColor = MaterialTheme.colorScheme.surfaceContainer,
                ),
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        LazyColumn(
            contentPadding = PaddingValues(
                start = padding.calculateStartPadding(direction) + 16.dp,
                end = padding.calculateEndPadding(direction) + 16.dp,
                top = padding.calculateTopPadding() + 4.dp,
                bottom = padding.calculateBottomPadding() + 24.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.fillMaxSize().consumeWindowInsets(padding),
        ) {
            item { SectionTitle(stringResource(R.string.devices_scales)) }
            scaleDevicesSection(
                scales, dialogs, onUse = scalesVm::useDetected, onSaveConfig = scalesVm::setConfig,
                onAcceptSuggestion = scalesVm::acceptSuggestion, onDeclineSuggestion = scalesVm::declineSuggestion,
            )

            item { SectionTitle(stringResource(R.string.devices_wearables)) }
            wearablesSection(
                wearables, wearableDialogs, onOpen = onOpenWearable, onUse = wearablesVm::use,
                onAcceptSuggestion = wearablesVm::acceptSuggestion, onDeclineSuggestion = wearablesVm::declineSuggestion,
            )

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
    }

    ScaleDialogsHost(scales, dialogs, scalesVm)
    WearableDialogsHost(wearables, wearableDialogs, wearablesVm)
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
