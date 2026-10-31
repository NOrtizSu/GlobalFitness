package com.enjoyyourtime.globalfitness.ui.training

import android.Manifest
import android.os.Build
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.core.app.ActivityCompat
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.enjoyyourtime.globalfitness.GlobalFitnessApp
import com.enjoyyourtime.globalfitness.R
import com.enjoyyourtime.globalfitness.training.data.SpotifyCovers
import com.enjoyyourtime.globalfitness.training.model.LoadUnit
import com.enjoyyourtime.globalfitness.training.player.MediaAccessListener
import com.enjoyyourtime.globalfitness.ui.Fmt
import com.enjoyyourtime.globalfitness.ui.components.LocalAppSnackbar
import com.enjoyyourtime.globalfitness.ui.components.SectionTitle
import com.enjoyyourtime.globalfitness.ui.training.music.PlayerAccessDialog
import com.enjoyyourtime.globalfitness.ui.training.music.musicViewModel
import com.enjoyyourtime.globalfitness.ui.training.music.playerViewModel
import com.enjoyyourtime.globalfitness.ui.training.notification.WorkoutNotifications
import kotlinx.coroutines.launch

/** Rest timer choices (seconds; 0 = off). */
private val REST_CHOICES = listOf(0, 30, 60, 90, 120, 180)

/**
 * Load unit, rest timer, the workout's notification (and whether Android allows it), the day's music
 * suggestions (and whether their covers come from Spotify), the music controls (and the
 * notification access that shows the song) and where saving workouts to Health Connect is
 * configured.
 */
@Composable
fun TrainingSettingsScreen(onBack: () -> Unit) {
    val vm = trainingViewModel()
    val unit by vm.loadUnit.collectAsStateWithLifecycle()
    val rest by vm.restSeconds.collectAsStateWithLifecycle()
    val musicVm = musicViewModel()
    val music by musicVm.enabled.collectAsStateWithLifecycle()
    val covers by musicVm.spotifyCovers.collectAsStateWithLifecycle()
    val playerVm = playerViewModel()
    val controls by playerVm.controlsEnabled.collectAsStateWithLifecycle()
    val access by playerVm.accessGranted.collectAsStateWithLifecycle()
    // Back from Android's settings, where the access may have changed.
    LifecycleResumeEffect(playerVm) {
        playerVm.recheckAccess()
        onPauseOrDispose { }
    }
    var explainAccess by rememberSaveable { mutableStateOf(false) }
    val context = LocalContext.current
    val snackbar = LocalAppSnackbar.current
    val scope = rememberCoroutineScope()
    val accessFailed = stringResource(R.string.player_access_failed)
    // A value set elsewhere (not one of the choices) still shows as selected.
    val choices = (REST_CHOICES + rest).distinct().sorted()

    TrainingSubPage(title = stringResource(R.string.training_settings_title), onBack = onBack) { padding ->
        Column(
            Modifier
                .subPageContent(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            SectionTitle(stringResource(R.string.training_settings_unit))
            Text(stringResource(R.string.training_settings_unit_text), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                LoadUnit.entries.forEachIndexed { i, u ->
                    SegmentedButton(
                        selected = u == unit,
                        onClick = { vm.setLoadUnit(u) },
                        shape = SegmentedButtonDefaults.itemShape(i, LoadUnit.entries.size),
                        label = { Text(stringResource(if (u == LoadUnit.KG) R.string.training_unit_kg_long else R.string.training_unit_lb_long)) },
                    )
                }
            }
            SectionTitle(stringResource(R.string.training_settings_rest), Modifier.padding(top = 8.dp))
            Text(stringResource(R.string.training_settings_rest_text), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                choices.forEach { s ->
                    FilterChip(selected = s == rest, onClick = { vm.setRestSeconds(s) }, label = { Text(restLabel(s)) })
                }
            }
            SectionTitle(stringResource(R.string.workout_notification_title), Modifier.padding(top = 8.dp))
            WorkoutNotificationSetting(vm)
            SectionTitle(stringResource(R.string.music_title), Modifier.padding(top = 8.dp))
            SwitchRow(stringResource(R.string.music_setting), checked = music, onChange = musicVm::setEnabled)
            Text(stringResource(R.string.music_setting_text), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            // Off until the user says yes (in the question at start or here); unavailable with the suggestions off.
            SwitchRow(
                stringResource(R.string.music_covers_setting),
                checked = covers == SpotifyCovers.ALLOWED,
                enabled = music,
                onChange = musicVm::setCoversAllowed,
                modifier = Modifier.padding(top = 8.dp),
            )
            Text(
                stringResource(R.string.music_covers_setting_text),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = if (music) 1f else DISABLED_ALPHA),
            )
            SwitchRow(
                stringResource(R.string.player_setting),
                checked = controls,
                onChange = playerVm::setControls,
                modifier = Modifier.padding(top = 8.dp),
            )
            Text(stringResource(R.string.player_setting_text), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            // Turning access on is offered with the controls on; turning it off, always.
            AccessRow(
                granted = access,
                enabled = controls || access,
                onClick = {
                    if (!access) {
                        explainAccess = true
                    } else if (!MediaAccessListener.openSettings(context)) {
                        scope.launch { snackbar.showSnackbar(accessFailed, withDismissAction = true) }
                    }
                },
            )
            SectionTitle(stringResource(R.string.training_health_connect), Modifier.padding(top = 8.dp))
            Text(stringResource(R.string.training_settings_health), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
    if (explainAccess) {
        PlayerAccessDialog(
            onDismiss = { explainAccess = false },
            onFailed = { scope.launch { snackbar.showSnackbar(accessFailed, withDismissAction = true) } },
        )
    }
}

/** Text disabled as Material 3 does it. */
private const val DISABLED_ALPHA = 0.38f

/**
 * "Workout notification" (on by default) and, while Android does not let the app post it, a row
 * to allow it: Android's own dialog while it may still be shown (Android 13+: before it was
 * refused twice), else the app's notification settings. Turning the switch on offers the same.
 */
@Composable
private fun WorkoutNotificationSetting(vm: TrainingViewModel) {
    val context = LocalContext.current
    val activity = LocalActivity.current
    val notifications = (context.applicationContext as GlobalFitnessApp).container.workoutNotifications
    val enabled by vm.workoutNotification.collectAsStateWithLifecycle()
    var allowed by remember { mutableStateOf(notifications.allowed()) }
    // Back from Android's settings (or its dialog), where it may have changed.
    LifecycleResumeEffect(notifications) {
        allowed = notifications.allowed()
        onPauseOrDispose { }
    }
    val request = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        allowed = notifications.allowed()
        notifications.refresh()
    }
    val snackbar = LocalAppSnackbar.current
    val scope = rememberCoroutineScope()
    val failed = stringResource(R.string.workout_notification_settings_failed)
    val allow = {
        val dialog = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !notifications.permissionGranted() &&
            (!notifications.permissionAsked || activity?.let { ActivityCompat.shouldShowRequestPermissionRationale(it, Manifest.permission.POST_NOTIFICATIONS) } == true)
        if (dialog) {
            notifications.markPermissionAsked()
            request.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else if (!WorkoutNotifications.openSettings(context)) {
            scope.launch { snackbar.showSnackbar(failed, withDismissAction = true) }
        }
    }
    SwitchRow(
        stringResource(R.string.workout_notification_setting),
        checked = enabled,
        onChange = { on ->
            vm.setWorkoutNotification(on)
            if (on && !allowed) allow()
        },
    )
    Text(stringResource(R.string.workout_notification_setting_text), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    if (enabled && !allowed) {
        // Never asked yet (Android 13+): nothing was refused, so a plain offer instead of the warning.
        val neverAsked = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !notifications.permissionGranted() && !notifications.permissionAsked
        if (neverAsked) {
            TextButton(onClick = allow, modifier = Modifier.heightIn(min = 48.dp)) {
                Text(stringResource(R.string.workout_notification_allow))
            }
        } else {
            Text(
                stringResource(R.string.workout_notification_blocked),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp)
                    .clickable(role = Role.Button, onClick = allow)
                    .padding(vertical = 12.dp),
            )
        }
    }
}

/**
 * "See what's playing" and whether notification access is on: off, it explains the access first;
 * on, it opens Android's page to turn it off.
 */
@Composable
private fun AccessRow(granted: Boolean, enabled: Boolean, onClick: () -> Unit) {
    val alpha = if (enabled) 1f else DISABLED_ALPHA
    Column(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(vertical = 8.dp),
    ) {
        Text(stringResource(R.string.player_show), style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface.copy(alpha = alpha))
        Text(
            stringResource(if (granted) R.string.player_access_on else R.string.player_access_off),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = alpha),
        )
    }
}

/** A setting that is a switch: the whole row toggles it, so TalkBack reads the title with the state. */
@Composable
private fun SwitchRow(text: String, checked: Boolean, onChange: (Boolean) -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    Row(
        modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .toggleable(value = checked, enabled = enabled, role = Role.Switch, onValueChange = onChange),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = if (enabled) 1f else DISABLED_ALPHA),
            modifier = Modifier.weight(1f),
        )
        Spacer(Modifier.width(16.dp))
        Switch(checked = checked, onCheckedChange = null, enabled = enabled)
    }
}

/** "Off", "30 s", "90 s", "2 min". */
@Composable
private fun restLabel(seconds: Int): String = when {
    seconds <= 0 -> stringResource(R.string.training_rest_off)
    seconds % 60 == 0 && seconds >= 120 -> stringResource(R.string.value_minutes, Fmt.count((seconds / 60).toLong()))
    else -> stringResource(R.string.training_seconds_value, Fmt.count(seconds.toLong()))
}
