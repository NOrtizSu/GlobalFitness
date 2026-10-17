package com.enjoyyourtime.globalfitness.ui.onboarding

import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalActivity
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.enjoyyourtime.globalfitness.R
import com.enjoyyourtime.globalfitness.UiText
import com.enjoyyourtime.globalfitness.ble.ScaleScanner
import com.enjoyyourtime.globalfitness.ble.ScanStatus
import com.enjoyyourtime.globalfitness.data.ProfileDraft
import com.enjoyyourtime.globalfitness.health.HcAvailability
import com.enjoyyourtime.globalfitness.ui.BleAccess
import com.enjoyyourtime.globalfitness.ui.MainViewModel
import com.enjoyyourtime.globalfitness.ui.asString
import com.enjoyyourtime.globalfitness.ui.account.AccountAction
import com.enjoyyourtime.globalfitness.ui.account.AccountViewModel
import com.enjoyyourtime.globalfitness.ui.account.ConflictCard
import com.enjoyyourtime.globalfitness.ui.components.AppIcons
import com.enjoyyourtime.globalfitness.ui.health.HealthViewModel
import com.enjoyyourtime.globalfitness.ui.profile.ProfileForm
import com.enjoyyourtime.globalfitness.ui.theme.AppMotion

enum class OnboardingStep { WELCOME, RESTORE, PROFILE, PERMISSIONS, DONE }

/**
 * First launch: welcome → optional Google sign-in to restore a cloud copy (skips the profile
 * form when a profile arrives) or "Continue without an account" → profile → permissions → done.
 * No ads are shown here.
 */
@Composable
fun OnboardingFlow(
    vm: MainViewModel,
    accountVm: AccountViewModel,
    healthVm: HealthViewModel,
    access: BleAccess,
    snackbar: SnackbarHostState,
    onConnectHealth: () -> Unit,
    onFinished: () -> Unit,
) {
    val state by vm.uiState.collectAsStateWithLifecycle()
    val user by accountVm.user.collectAsStateWithLifecycle()
    val cloud by accountVm.status.collectAsStateWithLifecycle()
    val busy by accountVm.busy.collectAsStateWithLifecycle()
    val message by accountVm.message.collectAsStateWithLifecycle()
    val scanStatus by vm.scanStatus.collectAsStateWithLifecycle()
    val health by healthVm.uiState.collectAsStateWithLifecycle()
    val activity = LocalActivity.current

    var step by rememberSaveable { mutableStateOf(OnboardingStep.WELCOME) }
    var restoreStartedAt by rememberSaveable { mutableLongStateOf(0L) }
    var nothingToRestore by rememberSaveable { mutableStateOf(false) }
    var syncRequested by rememberSaveable { mutableStateOf(false) }
    val context = LocalContext.current
    val bluetoothGranted = remember(scanStatus) { ScaleScanner.hasScanPermission(context) }

    LaunchedEffect(message) {
        message?.let {
            snackbar.showSnackbar(it.resolve(context), withDismissAction = true)
            accountVm.consumeMessage()
        }
    }
    // A profile arrived (created here or restored from the cloud): continue with the permissions.
    LaunchedEffect(state.profile != null, step) {
        if (state.profile != null && (step == OnboardingStep.WELCOME || step == OnboardingStep.RESTORE || step == OnboardingStep.PROFILE)) {
            step = OnboardingStep.PERMISSIONS
        }
    }
    // Sign-in cancelled or failed: back to the welcome page. Signed in and synced without a profile in
    // the cloud: create one. Signed in but no sync under way (the same account signed in again):
    // ask for one, since the engine only syncs by itself when the account changes.
    LaunchedEffect(step, busy, user, cloud) {
        if (step != OnboardingStep.RESTORE) return@LaunchedEffect
        // Current values, not the collected ones (those can lag a frame behind the sign-in start).
        val signedIn = accountVm.user.value != null
        val status = accountVm.status.value
        val synced = status.lastSyncAt?.let { it >= restoreStartedAt } == true && !status.syncing
        when {
            !signedIn && accountVm.busy.value == null -> step = OnboardingStep.WELCOME
            signedIn && synced && status.conflict == null && vm.currentProfile == null -> {
                nothingToRestore = true
                step = OnboardingStep.PROFILE
            }
            signedIn && !synced && !syncRequested && !status.syncing && !status.retrying && status.conflict == null -> {
                syncRequested = true
                accountVm.syncNow()
            }
        }
    }

    BackHandler(enabled = step == OnboardingStep.PROFILE || step == OnboardingStep.RESTORE) {
        nothingToRestore = false
        step = OnboardingStep.WELCOME
    }

    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
        Box(Modifier.fillMaxSize().safeDrawingPadding()) {
            AnimatedContent(
                targetState = step,
                transitionSpec = {
                    val forward = targetState.ordinal > initialState.ordinal
                    (slideInHorizontally(AppMotion.spatial()) { if (forward) it / 4 else -it / 4 } + fadeIn(AppMotion.effects())) togetherWith
                        (slideOutHorizontally(AppMotion.spatial()) { if (forward) -it / 4 else it / 4 } + fadeOut(AppMotion.effects()))
                },
                label = "onboarding",
                modifier = Modifier.fillMaxSize(),
            ) { current ->
                Page {
                    when (current) {
                        OnboardingStep.WELCOME -> Welcome(
                            onSignIn = {
                                restoreStartedAt = System.currentTimeMillis()
                                syncRequested = false
                                step = OnboardingStep.RESTORE
                                activity?.let(accountVm::signIn)
                            },
                            onSkip = {
                                nothingToRestore = false
                                step = OnboardingStep.PROFILE
                            },
                        )
                        OnboardingStep.RESTORE -> Restore(
                            signingIn = busy == AccountAction.SIGN_IN || user == null,
                            working = cloud.syncing || cloud.retrying,
                            error = cloud.error,
                            conflict = {
                                cloud.conflict?.let { ConflictCard(it, enabled = !cloud.syncing, onResolve = accountVm::resolveConflict) }
                            },
                            onRetry = accountVm::syncNow,
                            onSkip = { step = OnboardingStep.PROFILE },
                        )
                        OnboardingStep.PROFILE -> ProfileStep(nothingToRestore = nothingToRestore, onCreate = vm::createProfile)
                        OnboardingStep.PERMISSIONS -> Permissions(
                            bluetoothGranted = bluetoothGranted,
                            bluetoothAvailable = scanStatus != ScanStatus.NoBluetoothLe,
                            onBluetooth = if (access.permanentlyDenied) access.openAppSettings else access.requestPermissions,
                            healthAvailability = health.availability,
                            healthConnected = health.granted.isNotEmpty(),
                            onHealth = onConnectHealth,
                            onInstallHealth = { healthVm.openPlayStore() },
                            onContinue = { step = OnboardingStep.DONE },
                        )
                        OnboardingStep.DONE -> Done(name = state.profile?.name, onStart = onFinished)
                    }
                }
            }
            SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter))
        }
    }
}

@Composable
private fun Page(content: @Composable ColumnScope.() -> Unit) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        Column(
            Modifier
                .widthIn(max = 560.dp)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .imePadding()
                .padding(horizontal = 24.dp, vertical = 32.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
            content = content,
        )
    }
}

@Composable
private fun Hero(icon: ImageVector, title: String, text: String) {
    Box(
        Modifier
            .size(88.dp)
            .background(MaterialTheme.colorScheme.primaryContainer, RoundedCornerShape(28.dp)),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.onPrimaryContainer, modifier = Modifier.size(48.dp))
    }
    Text(title, style = MaterialTheme.typography.headlineMedium, modifier = Modifier.semantics { heading() })
    Text(text, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun Welcome(onSignIn: () -> Unit, onSkip: () -> Unit) {
    Hero(
        icon = AppIcons.Logo,
        title = stringResource(R.string.onboarding_welcome_title),
        text = stringResource(R.string.onboarding_welcome_text),
    )
    Surface(color = MaterialTheme.colorScheme.surfaceContainerLow, shape = MaterialTheme.shapes.large) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(AppIcons.Cloud, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(12.dp))
                Text(stringResource(R.string.onboarding_restore_question), style = MaterialTheme.typography.titleMedium)
            }
            Text(
                stringResource(R.string.onboarding_restore_text),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            FilledTonalButton(onClick = onSignIn, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                Text(stringResource(R.string.account_sign_in_google))
            }
        }
    }
    Button(onClick = onSkip, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)) { Text(stringResource(R.string.onboarding_continue_without_account)) }
}

@Composable
private fun Restore(
    signingIn: Boolean,
    working: Boolean,
    error: UiText?,
    conflict: @Composable () -> Unit,
    onRetry: () -> Unit,
    onSkip: () -> Unit,
) {
    Hero(
        icon = AppIcons.Cloud,
        title = stringResource(if (signingIn) R.string.onboarding_signing_in else R.string.onboarding_restoring),
        text = stringResource(if (signingIn) R.string.onboarding_signing_in_text else R.string.onboarding_restoring_text),
    )
    if (signingIn || working) CircularProgressIndicator(Modifier.size(40.dp))
    if (error != null) Text(error.asString(), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
    if (!signingIn && !working) {
        OutlinedButton(onClick = onRetry, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.action_retry)) }
    }
    conflict()
    TextButton(onClick = onSkip, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.onboarding_continue_without_restoring)) }
}

@Composable
private fun ProfileStep(nothingToRestore: Boolean, onCreate: (ProfileDraft) -> Unit) {
    Text(stringResource(R.string.onboarding_profile_title), style = MaterialTheme.typography.headlineMedium, modifier = Modifier.semantics { heading() })
    Text(
        stringResource(if (nothingToRestore) R.string.onboarding_profile_text_no_backup else R.string.onboarding_profile_text),
        style = MaterialTheme.typography.bodyLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    ProfileForm(initial = null, submitLabel = stringResource(R.string.action_continue), onSubmit = onCreate, onCancel = null)
}

@Composable
private fun Permissions(
    bluetoothGranted: Boolean,
    bluetoothAvailable: Boolean,
    onBluetooth: () -> Unit,
    healthAvailability: HcAvailability,
    healthConnected: Boolean,
    onHealth: () -> Unit,
    onInstallHealth: () -> Unit,
    onContinue: () -> Unit,
) {
    Text(stringResource(R.string.onboarding_permissions_title), style = MaterialTheme.typography.headlineMedium, modifier = Modifier.semantics { heading() })
    Text(
        stringResource(R.string.onboarding_permissions_text),
        style = MaterialTheme.typography.bodyLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    PermissionCard(
        icon = AppIcons.Bluetooth,
        title = stringResource(R.string.onboarding_bluetooth_title),
        text = stringResource(R.string.onboarding_bluetooth_text),
        done = bluetoothAvailable && bluetoothGranted,
        action = if (bluetoothAvailable) stringResource(R.string.onboarding_allow) else null,
        onAction = onBluetooth,
    )
    PermissionCard(
        icon = Icons.Filled.Favorite,
        title = stringResource(R.string.onboarding_hc_title),
        text = stringResource(R.string.onboarding_hc_text),
        done = healthConnected,
        action = when (healthAvailability) {
            HcAvailability.AVAILABLE -> stringResource(R.string.onboarding_connect)
            HcAvailability.NOT_INSTALLED -> stringResource(R.string.onboarding_install)
            HcAvailability.UPDATE_REQUIRED -> stringResource(R.string.onboarding_update)
            HcAvailability.NOT_SUPPORTED -> null
        },
        onAction = if (healthAvailability == HcAvailability.AVAILABLE) onHealth else onInstallHealth,
    )
    Button(onClick = onContinue, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)) { Text(stringResource(R.string.action_continue)) }
}

@Composable
private fun PermissionCard(
    icon: ImageVector,
    title: String,
    text: String,
    done: Boolean,
    action: String?,
    onAction: () -> Unit,
) {
    Surface(color = MaterialTheme.colorScheme.surfaceContainerLow, shape = MaterialTheme.shapes.large) {
        Row(Modifier.fillMaxWidth().padding(20.dp), verticalAlignment = Alignment.Top) {
            Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(title, style = MaterialTheme.typography.titleMedium)
                Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                when {
                    done -> Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Filled.CheckCircle, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(R.string.onboarding_granted), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                    }
                    action != null -> FilledTonalButton(onClick = onAction, modifier = Modifier.heightIn(min = 48.dp)) { Text(action) }
                    else -> Text(stringResource(R.string.onboarding_not_available), style = MaterialTheme.typography.labelLarge)
                }
            }
        }
    }
}

@Composable
private fun Done(name: String?, onStart: () -> Unit) {
    Hero(
        icon = Icons.Filled.CheckCircle,
        title = name?.let { stringResource(R.string.onboarding_done_title_name, it) } ?: stringResource(R.string.onboarding_done_title),
        text = stringResource(R.string.onboarding_done_text),
    )
    Button(onClick = onStart, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)) { Text(stringResource(R.string.onboarding_start)) }
}
