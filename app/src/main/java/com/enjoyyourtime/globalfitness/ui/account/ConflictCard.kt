package com.enjoyyourtime.globalfitness.ui.account

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.enjoyyourtime.globalfitness.R
import com.enjoyyourtime.globalfitness.cloud.ConflictChoice
import com.enjoyyourtime.globalfitness.cloud.ConflictKind
import com.enjoyyourtime.globalfitness.cloud.SyncConflict

/** One option of a paused sync: label, confirmation text (null = no confirmation) and the choice. */
private data class Option(val label: String, val confirm: String?, val choice: ConflictChoice, val destructive: Boolean)

/**
 * The sync is paused until the user decides. "Cancel" only collapses the options: nothing is
 * synced (or deleted) until a choice is made.
 */
@Composable
internal fun ConflictCard(conflict: SyncConflict, enabled: Boolean, onResolve: (ConflictChoice) -> Unit) {
    var collapsed by rememberSaveable(conflict) { mutableStateOf(false) }
    var confirming by rememberSaveable(conflict) { mutableStateOf<ConflictChoice?>(null) }

    val (title, body, options) = when (conflict.kind) {
        ConflictKind.DIFFERENT_DATA -> Triple(
            stringResource(R.string.conflict_different_title),
            stringResource(R.string.conflict_different_text),
            listOf(
                Option(
                    stringResource(R.string.conflict_restore_cloud),
                    stringResource(R.string.conflict_restore_cloud_confirm),
                    ConflictChoice.RESTORE_CLOUD,
                    destructive = true,
                ),
                Option(
                    stringResource(R.string.conflict_replace_cloud),
                    stringResource(R.string.conflict_replace_cloud_confirm),
                    ConflictChoice.REPLACE_CLOUD,
                    destructive = true,
                ),
            ),
        )
        ConflictKind.MASS_DELETE -> Triple(
            pluralStringResource(R.plurals.conflict_mass_delete_title, conflict.count, conflict.count),
            stringResource(R.string.conflict_mass_delete_text),
            listOf(
                Option(stringResource(R.string.conflict_delete_cloud_too), null, ConflictChoice.CONFIRM_DELETIONS, destructive = true),
                Option(stringResource(R.string.conflict_recover_from_cloud), null, ConflictChoice.UNDO_DELETIONS, destructive = false),
            ),
        )
    }

    OutlinedCard(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.outlinedCardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onErrorContainer)
            if (collapsed) {
                Text(
                    stringResource(R.string.conflict_paused),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                )
                OutlinedButton(onClick = { collapsed = false }, enabled = enabled) { Text(stringResource(R.string.conflict_choose)) }
            } else {
                Text(body, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onErrorContainer)
                for (option in options) {
                    Button(
                        onClick = { if (option.confirm == null) onResolve(option.choice) else confirming = option.choice },
                        enabled = enabled,
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text(option.label) }
                }
                TextButton(onClick = { collapsed = true }, enabled = enabled, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.action_cancel))
                }
            }
        }
    }

    options.firstOrNull { it.choice == confirming }?.let { option ->
        AlertDialog(
            onDismissRequest = { confirming = null },
            title = { Text(option.label) },
            text = { Text(option.confirm.orEmpty()) },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirming = null
                        onResolve(option.choice)
                    },
                    colors = if (option.destructive) {
                        ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)
                    } else {
                        ButtonDefaults.textButtonColors()
                    },
                ) { Text(stringResource(R.string.action_continue)) }
            },
            dismissButton = { TextButton(onClick = { confirming = null }) { Text(stringResource(R.string.action_cancel)) } },
        )
    }
}
