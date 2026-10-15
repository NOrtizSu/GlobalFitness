@file:OptIn(ExperimentalMaterial3Api::class)

package com.enjoyyourtime.globalfitness.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.enjoyyourtime.globalfitness.ble.ScaleScanner
import com.enjoyyourtime.globalfitness.data.Profile
import com.enjoyyourtime.globalfitness.domain.Metric
import com.enjoyyourtime.globalfitness.ui.components.AppIcons
import com.enjoyyourtime.globalfitness.ui.components.ProfileDot
import com.enjoyyourtime.globalfitness.ui.screens.ChartRange
import com.enjoyyourtime.globalfitness.ui.screens.ChartsScreen
import com.enjoyyourtime.globalfitness.ui.screens.HistoryScreen
import com.enjoyyourtime.globalfitness.ui.screens.HomeScreen
import com.enjoyyourtime.globalfitness.ui.screens.ManualEntryDialog
import com.enjoyyourtime.globalfitness.ui.screens.MeasurementDetailSheet
import com.enjoyyourtime.globalfitness.ui.screens.OnboardingScreen
import com.enjoyyourtime.globalfitness.ui.screens.PendingAssignDialog
import com.enjoyyourtime.globalfitness.ui.screens.ProfilesScreen
import kotlinx.coroutines.launch

enum class Tab(val label: String) {
    HOME("Inicio"),
    CHARTS("Gráficos"),
    HISTORY("Historial"),
    PROFILES("Perfiles"),
}

private fun Tab.icon(): ImageVector = when (this) {
    Tab.HOME -> Icons.Filled.Home
    Tab.CHARTS -> AppIcons.ShowChart
    Tab.HISTORY -> AppIcons.History
    Tab.PROFILES -> Icons.Filled.Person
}

@Composable
fun AppRoot(vm: MainViewModel = viewModel(factory = MainViewModel.Factory)) {
    val state by vm.uiState.collectAsStateWithLifecycle()
    val scanStatus by vm.scanStatus.collectAsStateWithLifecycle()
    val live by vm.live.collectAsStateWithLifecycle()
    val debug by vm.debug.collectAsStateWithLifecycle()
    val prompt by vm.pendingPrompt.collectAsStateWithLifecycle()
    val loadError by vm.loadError.collectAsStateWithLifecycle()
    val lastScaleAddress by vm.lastScaleAddress.collectAsStateWithLifecycle()
    val rationaleSeen by vm.permissionRationaleSeen.collectAsStateWithLifecycle()

    val context = LocalContext.current
    val haptic = LocalHapticFeedback.current
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val access = rememberBleAccess(
        rationaleSeen = rationaleSeen,
        onRationaleSeen = vm::markPermissionRationaleSeen,
        onChanged = vm::refreshScanner,
    )

    LaunchedEffect(Unit) {
        vm.events.collect { event ->
            when (event) {
                is UiEvent.Message -> {
                    if (event.haptic) haptic.performHapticFeedback(HapticFeedbackType.Confirm)
                    scope.launch {
                        snackbar.currentSnackbarData?.dismiss()
                        snackbar.showSnackbar(event.text, withDismissAction = true, duration = SnackbarDuration.Short)
                    }
                }
            }
        }
    }

    if (!state.loaded) {
        Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
            val error = loadError
            if (error == null) {
                CircularProgressIndicator()
            } else {
                Text(
                    "No se pudo abrir la base de datos: $error",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
        return
    }
    if (state.profiles.isEmpty()) {
        OnboardingScreen(onCreate = vm::createProfile)
        return
    }

    // Ask for Bluetooth permissions once, right after onboarding / on first launch.
    var permissionAsked by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        if (!permissionAsked && !ScaleScanner.hasScanPermission(context)) {
            permissionAsked = true
            access.requestPermissions()
        }
    }

    var tab by rememberSaveable { mutableStateOf(Tab.HOME) }
    var chartMetric by rememberSaveable { mutableStateOf(Metric.WEIGHT) }
    var chartRange by rememberSaveable { mutableStateOf(ChartRange.M3) }
    var showManual by rememberSaveable { mutableStateOf(false) }
    var detailId by rememberSaveable { mutableLongStateOf(-1L) }
    var assignId by rememberSaveable { mutableLongStateOf(-1L) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    val selected = state.selectedProfile
                    if (selected != null) ProfileSelector(state.profiles, selected, vm::selectProfile)
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface),
            )
        },
        bottomBar = {
            NavigationBar {
                Tab.entries.forEach { t ->
                    NavigationBarItem(
                        selected = tab == t,
                        onClick = { tab = t },
                        icon = { Icon(t.icon(), contentDescription = null) },
                        label = { Text(t.label) },
                    )
                }
            }
        },
        floatingActionButton = {
            if (tab == Tab.HISTORY && state.selectedProfile != null) {
                ExtendedFloatingActionButton(
                    onClick = { showManual = true },
                    icon = { Icon(Icons.Filled.Add, contentDescription = null) },
                    text = { Text("Añadir manual") },
                )
            }
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Box(Modifier.padding(padding).fillMaxSize()) {
            when (tab) {
                Tab.HOME -> HomeScreen(
                    state = state,
                    scanStatus = scanStatus,
                    live = live,
                    access = access,
                    onRetryScan = vm::refreshScanner,
                    onOpenMetric = { metric ->
                        chartMetric = metric
                        tab = Tab.CHARTS
                    },
                )
                Tab.CHARTS -> ChartsScreen(
                    state = state,
                    metric = chartMetric,
                    onMetric = { chartMetric = it },
                    range = chartRange,
                    onRange = { chartRange = it },
                )
                Tab.HISTORY -> HistoryScreen(
                    state = state,
                    onOpen = { detailId = it.id },
                    onAssignPending = { assignId = it.id },
                )
                Tab.PROFILES -> ProfilesScreen(
                    state = state,
                    scanStatus = scanStatus,
                    live = live,
                    debug = debug,
                    lastScaleAddress = lastScaleAddress,
                    onCreate = vm::createProfile,
                    onUpdate = vm::updateProfile,
                    onDelete = vm::deleteProfile,
                    onTolerance = vm::setTolerance,
                    onPin = vm::pinScale,
                    onForget = vm::forgetScale,
                    onExport = { vm.exportCsv(context.contentResolver, it) },
                    onImport = { vm.importCsv(context.contentResolver, it) },
                )
            }
        }
    }

    val detail = state.entries.firstOrNull { it.measurement.id == detailId }?.measurement
        ?: state.pending.firstOrNull { it.id == detailId }
    if (detail != null) {
        MeasurementDetailSheet(
            measurement = detail,
            profile = state.profiles.firstOrNull { it.id == detail.profileId },
            profiles = state.profiles,
            onAssign = { profileId ->
                vm.assign(detail.id, profileId)
                detailId = -1L
            },
            onDelete = {
                vm.deleteMeasurement(detail.id)
                detailId = -1L
            },
            onDismiss = { detailId = -1L },
        )
    }

    if (showManual) {
        state.selectedProfile?.let { profile ->
            ManualEntryDialog(
                profileName = profile.name,
                onSave = { weight, timestamp ->
                    vm.addManual(weight, timestamp)
                    showManual = false
                },
                onDismiss = { showManual = false },
            )
        }
    }

    state.pending.firstOrNull { it.id == assignId }?.let { m ->
        PendingAssignDialog(
            measurement = m,
            profiles = state.profiles,
            onAssign = {
                vm.assign(m.id, it)
                assignId = -1L
            },
            onDiscard = {
                vm.deleteMeasurement(m.id)
                assignId = -1L
            },
            onLater = { assignId = -1L },
        )
    }

    prompt?.takeIf { it.id != assignId }?.let { m ->
        PendingAssignDialog(
            measurement = m,
            profiles = state.profiles,
            onAssign = { vm.assign(m.id, it) },
            onDiscard = { vm.deleteMeasurement(m.id) },
            onLater = vm::dismissPrompt,
        )
    }
}

@Composable
private fun ProfileSelector(profiles: List<Profile>, selected: Profile, onSelect: (Long) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        Row(
            modifier = Modifier
                .clip(RoundedCornerShape(50))
                .clickable(onClickLabel = "Cambiar perfil") { expanded = true }
                .padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ProfileDot(selected.colorIndex)
            Spacer(Modifier.width(10.dp))
            Text(selected.name, style = MaterialTheme.typography.titleLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Icon(Icons.Filled.ArrowDropDown, contentDescription = null)
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            profiles.forEach { p ->
                DropdownMenuItem(
                    text = { Text(p.name) },
                    leadingIcon = { ProfileDot(p.colorIndex) },
                    trailingIcon = { if (p.id == selected.id) Icon(Icons.Filled.Check, contentDescription = "Seleccionado") },
                    onClick = {
                        onSelect(p.id)
                        expanded = false
                    },
                )
            }
        }
    }
}
