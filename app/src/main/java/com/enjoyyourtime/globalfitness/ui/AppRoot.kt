package com.enjoyyourtime.globalfitness.ui

import androidx.activity.compose.LocalActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.enjoyyourtime.globalfitness.domain.Metric
import com.enjoyyourtime.globalfitness.ui.account.AccountScreen
import com.enjoyyourtime.globalfitness.ui.account.AccountViewModel
import com.enjoyyourtime.globalfitness.ui.ads.AdBanner
import com.enjoyyourtime.globalfitness.ui.band.BandActivity
import com.enjoyyourtime.globalfitness.ui.body.BodyScreen
import com.enjoyyourtime.globalfitness.ui.body.ChartRange
import com.enjoyyourtime.globalfitness.ui.body.ManualEntryDialog
import com.enjoyyourtime.globalfitness.ui.components.LocalAppSnackbar
import com.enjoyyourtime.globalfitness.ui.devices.DevicesScreen
import com.enjoyyourtime.globalfitness.ui.health.ActivityScreen
import com.enjoyyourtime.globalfitness.ui.health.HealthViewModel
import com.enjoyyourtime.globalfitness.ui.navigation.Routes
import com.enjoyyourtime.globalfitness.ui.navigation.TopLevel
import com.enjoyyourtime.globalfitness.ui.onboarding.OnboardingFlow
import com.enjoyyourtime.globalfitness.ui.premium.RemoveAdsScreen
import com.enjoyyourtime.globalfitness.ui.profile.OpenSourceScreen
import com.enjoyyourtime.globalfitness.ui.profile.PrivacyPolicyScreen
import com.enjoyyourtime.globalfitness.ui.profile.ProfileScreen
import com.enjoyyourtime.globalfitness.ui.theme.AppMotion
import com.enjoyyourtime.globalfitness.ui.today.TodayScreen
import com.enjoyyourtime.globalfitness.ui.today.TodayStatus

/** Single-activity root: loading → onboarding (first launch) → the five sections. */
@Composable
fun AppRoot() {
    // Activity-scoped: shared by every section.
    val vm: MainViewModel = viewModel(factory = MainViewModel.Factory)
    val healthVm: HealthViewModel = viewModel(factory = HealthViewModel.Factory)
    val state by vm.uiState.collectAsStateWithLifecycle()
    val loadError by vm.loadError.collectAsStateWithLifecycle()
    val rationaleSeen by vm.permissionRationaleSeen.collectAsStateWithLifecycle()

    val haptic = LocalHapticFeedback.current
    val snackbar = remember { SnackbarHostState() }
    val access = rememberBleAccess(
        rationaleSeen = rationaleSeen,
        onRationaleSeen = vm::markPermissionRationaleSeen,
        onChanged = vm::refreshScanner,
    )
    val healthPermissions = rememberLauncherForActivityResult(healthVm.permissionContract) { healthVm.onPermissionsResult() }
    val connectHealth = { healthPermissions.launch(healthVm.requestedPermissions()) }

    LaunchedEffect(Unit) {
        vm.events.collect { event ->
            when (event) {
                is UiEvent.Message -> {
                    if (event.haptic) haptic.performHapticFeedback(HapticFeedbackType.Confirm)
                    snackbar.currentSnackbarData?.dismiss()
                    snackbar.showSnackbar(event.text, withDismissAction = true, duration = SnackbarDuration.Short)
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

    // Decided once the data is loaded: a first launch walks through the onboarding to the end.
    var onboarding by rememberSaveable { mutableStateOf<Boolean?>(null) }
    if (onboarding == null) onboarding = state.profile == null
    if (onboarding == true || state.profile == null) {
        val accountVm: AccountViewModel = viewModel(factory = AccountViewModel.Factory)
        OnboardingFlow(
            vm = vm,
            accountVm = accountVm,
            healthVm = healthVm,
            access = access,
            snackbar = snackbar,
            onConnectHealth = connectHealth,
            onFinished = { onboarding = false },
        )
        return
    }

    CompositionLocalProvider(LocalAppSnackbar provides snackbar) {
        MainScaffold(vm, healthVm, access, snackbar, connectHealth)
    }
}

@Composable
private fun MainScaffold(
    vm: MainViewModel,
    healthVm: HealthViewModel,
    access: BleAccess,
    snackbar: SnackbarHostState,
    connectHealth: () -> Unit,
) {
    val navController = rememberNavController()
    val backStack by navController.currentBackStackEntryAsState()
    val route = backStack?.destination?.route
    val section = TopLevel.of(route)
    val context = LocalContext.current
    val activity = LocalActivity.current

    var bodyMetric by rememberSaveable { mutableStateOf(Metric.WEIGHT) }
    var bodyRange by rememberSaveable { mutableStateOf(ChartRange.M3) }
    var showManual by rememberSaveable { mutableStateOf(false) }

    // Interstitial opportunity on every section change (not on the first one, not on sub-pages).
    var lastSection by rememberSaveable { mutableStateOf<String?>(null) }
    LaunchedEffect(section) {
        val current = section?.route ?: return@LaunchedEffect
        val previous = lastSection
        lastSection = current
        if (previous != null && previous != current) activity?.let(vm::onSectionChanged)
    }

    val openBand = { context.startActivity(BandActivity.intent(context)) }

    Scaffold(
        contentWindowInsets = WindowInsets(0),
        bottomBar = {
            AnimatedVisibility(visible = section != null, enter = expandVertically() + fadeIn(), exit = shrinkVertically() + fadeOut()) {
                Column {
                    AdBanner()
                    NavigationBar {
                        TopLevel.entries.forEach { item ->
                            val selected = item == section
                            NavigationBarItem(
                                selected = selected,
                                onClick = { navController.navigateToSection(item) },
                                icon = { Icon(if (selected) item.selectedIcon else item.icon, contentDescription = null) },
                                label = { Text(item.label, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                            )
                        }
                    }
                }
            }
        },
        floatingActionButton = {
            if (section == TopLevel.BODY) {
                ExtendedFloatingActionButton(
                    onClick = { showManual = true },
                    icon = { Icon(Icons.Filled.Add, contentDescription = null) },
                    text = { Text("Añadir peso manual") },
                )
            }
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        NavHost(
            navController = navController,
            startDestination = TopLevel.TODAY.route,
            modifier = Modifier.padding(padding).consumeWindowInsets(padding),
            enterTransition = { fadeIn(AppMotion.effects()) },
            exitTransition = { fadeOut(AppMotion.effects()) },
        ) {
            composable(TopLevel.TODAY.route) {
                val live by vm.live.collectAsStateWithLifecycle()
                val sessions by vm.sessions.collectAsStateWithLifecycle()
                val scanStatus by vm.scanStatus.collectAsStateWithLifecycle()
                val health by healthVm.uiState.collectAsStateWithLifecycle()
                val dashboard by healthVm.dashboard.collectAsStateWithLifecycle()
                val user by vm.accountUser.collectAsStateWithLifecycle()
                val cloud by vm.cloudStatus.collectAsStateWithLifecycle()
                val band by vm.band.collectAsStateWithLifecycle()
                val state by vm.uiState.collectAsStateWithLifecycle()
                TodayScreen(
                    state = state,
                    status = TodayStatus(scanStatus, live, sessions, health, dashboard, user, cloud, band),
                    access = access,
                    onRetryScan = vm::refreshScanner,
                    onConnectHealth = connectHealth,
                    onInstallHealth = { healthVm.openPlayStore() },
                    onOpenMetric = { metric ->
                        bodyMetric = metric
                        navController.navigateToSection(TopLevel.BODY)
                    },
                    onAddWeight = { showManual = true },
                    onOpenAccount = { navController.navigate(Routes.ACCOUNT) },
                    onOpenDevices = { navController.navigateToSection(TopLevel.DEVICES) },
                    onOpenActivity = { navController.navigateToSection(TopLevel.ACTIVITY) },
                    onOpenBand = openBand,
                )
            }
            composable(TopLevel.BODY.route) {
                val state by vm.uiState.collectAsStateWithLifecycle()
                BodyScreen(
                    state = state,
                    metric = bodyMetric,
                    onMetric = { bodyMetric = it },
                    range = bodyRange,
                    onRange = { bodyRange = it },
                    onDelete = vm::deleteMeasurement,
                )
            }
            composable(TopLevel.ACTIVITY.route) {
                ActivityScreen(healthVm, onConnect = connectHealth)
            }
            composable(TopLevel.DEVICES.route) {
                DevicesScreen(vm, healthVm, onConnectHealth = connectHealth, onOpenBand = openBand)
            }
            composable(TopLevel.PROFILE.route) {
                ProfileScreen(
                    vm = vm,
                    healthVm = healthVm,
                    onConnectHealth = connectHealth,
                    onOpenAccount = { navController.navigate(Routes.ACCOUNT) },
                    onOpenRemoveAds = { navController.navigate(Routes.REMOVE_ADS) },
                    onOpenPrivacyPolicy = { navController.navigate(Routes.PRIVACY_POLICY) },
                    onOpenLicenses = { navController.navigate(Routes.LICENSES) },
                )
            }
            subPage(Routes.ACCOUNT) { AccountScreen(onBack = { navController.popBackStack() }) }
            subPage(Routes.REMOVE_ADS) { RemoveAdsScreen(onBack = { navController.popBackStack() }) }
            subPage(Routes.PRIVACY_POLICY) { PrivacyPolicyScreen(onBack = { navController.popBackStack() }) }
            subPage(Routes.LICENSES) { OpenSourceScreen(onBack = { navController.popBackStack() }) }
        }
    }

    if (showManual) {
        ManualEntryDialog(
            onSave = { weight, timestamp ->
                vm.addManual(weight, timestamp)
                showManual = false
            },
            onDismiss = { showManual = false },
        )
    }
}

/** Sub-pages slide in from the end and back out (also with the predictive back gesture). */
private fun NavGraphBuilder.subPage(route: String, content: @Composable () -> Unit) {
    composable(
        route,
        enterTransition = { slideInHorizontally(AppMotion.spatial()) { it / 3 } + fadeIn(AppMotion.effects()) },
        exitTransition = { fadeOut(AppMotion.effects()) },
        popEnterTransition = { fadeIn(AppMotion.effects()) },
        popExitTransition = { slideOutHorizontally(AppMotion.spatial()) { it / 3 } + fadeOut(AppMotion.effects()) },
    ) { content() }
}

/** Bottom-bar navigation: one copy of each section, state saved and restored when switching. */
private fun NavHostController.navigateToSection(section: TopLevel) {
    navigate(section.route) {
        popUpTo(graph.findStartDestination().id) { saveState = true }
        launchSingleTop = true
        restoreState = true
    }
}
