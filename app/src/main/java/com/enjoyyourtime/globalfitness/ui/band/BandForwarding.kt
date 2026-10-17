package com.enjoyyourtime.globalfitness.ui.band

import android.content.Intent
import android.os.Build
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import com.enjoyyourtime.globalfitness.R
import com.enjoyyourtime.globalfitness.band.BandNotificationListener
import com.enjoyyourtime.globalfitness.band.SeenApp

@Composable
fun ForwardingCard(
    enabled: Boolean,
    allowed: Set<String>,
    seenApps: List<SeenApp>,
    connected: Boolean,
    onEnabled: (Boolean) -> Unit,
    onAllowed: (String, Boolean) -> Unit,
    text: String = stringResource(R.string.forwarding_text),
    pendingText: String = stringResource(R.string.forwarding_active_pending),
) {
    val context = LocalContext.current
    var accessGranted by remember { mutableStateOf(BandNotificationListener.isAccessGranted(context)) }
    LifecycleResumeEffect(Unit) {
        accessGranted = BandNotificationListener.isAccessGranted(context)
        onPauseOrDispose { }
    }

    OutlinedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.forwarding_switch), style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                Switch(checked = enabled, onCheckedChange = onEnabled)
            }
            Text(
                text,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (!accessGranted) {
                Text(
                    stringResource(R.string.forwarding_access_needed),
                    style = MaterialTheme.typography.bodyMedium,
                )
                OutlinedButton(onClick = {
                    val intent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                        Intent(Settings.ACTION_NOTIFICATION_LISTENER_DETAIL_SETTINGS)
                            .putExtra(
                                Settings.EXTRA_NOTIFICATION_LISTENER_COMPONENT_NAME,
                                BandNotificationListener.component(context).flattenToString(),
                            )
                    } else {
                        Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
                    }
                    runCatching { context.startActivity(intent) }
                        .onFailure { runCatching { context.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) } }
                }) { Text(stringResource(R.string.forwarding_grant_access)) }
            } else if (enabled && !connected) {
                Text(
                    pendingText,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            if (accessGranted) {
                Text(stringResource(R.string.forwarding_apps), style = MaterialTheme.typography.titleSmall)
                if (seenApps.isEmpty()) {
                    Text(
                        stringResource(R.string.forwarding_apps_empty),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                seenApps.forEach { app ->
                    val checked = app.packageName in allowed
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .toggleable(value = checked, role = Role.Checkbox, onValueChange = { onAllowed(app.packageName, it) }),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Checkbox(checked = checked, onCheckedChange = null)
                        Column(Modifier.weight(1f).padding(start = 8.dp)) {
                            Text(app.label, style = MaterialTheme.typography.bodyMedium)
                            Text(app.packageName, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
        }
    }
}
