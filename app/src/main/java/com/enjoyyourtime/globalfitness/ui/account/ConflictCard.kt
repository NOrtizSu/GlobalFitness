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
import androidx.compose.ui.unit.dp
import com.enjoyyourtime.globalfitness.cloud.ConflictChoice
import com.enjoyyourtime.globalfitness.cloud.ConflictKind
import com.enjoyyourtime.globalfitness.cloud.SyncConflict

/** One option of a paused sync: label, confirmation text (null = no confirmation) and the choice. */
private data class Option(val label: String, val confirm: String?, val choice: ConflictChoice, val destructive: Boolean)

/**
 * The sync is paused until the user decides. "Cancelar" only collapses the options: nothing is
 * synced (or deleted) until a choice is made.
 */
@Composable
internal fun ConflictCard(conflict: SyncConflict, enabled: Boolean, onResolve: (ConflictChoice) -> Unit) {
    var collapsed by rememberSaveable(conflict) { mutableStateOf(false) }
    var confirming by rememberSaveable(conflict) { mutableStateOf<ConflictChoice?>(null) }

    val (title, body, options) = when (conflict.kind) {
        ConflictKind.DIFFERENT_DATA -> Triple(
            "Tu copia en la nube tiene otros datos",
            "Este teléfono y tu copia en la nube tienen mediciones distintas (de otro móvil o de otro perfil). " +
                "Para no mezclar datos de personas distintas no se juntan solas: elige qué datos conservar.",
            listOf(
                Option(
                    "Restaurar la copia de la nube en este móvil",
                    "Los datos de este teléfono se sustituirán por los de la nube. Las mediciones que solo estén en este " +
                        "teléfono se borrarán.",
                    ConflictChoice.RESTORE_CLOUD,
                    destructive = true,
                ),
                Option(
                    "Sustituir la copia de la nube por los datos de este móvil",
                    "Tu copia en la nube se sustituirá por los datos de este teléfono. Las mediciones que solo estén en la " +
                        "nube se borrarán.",
                    ConflictChoice.REPLACE_CLOUD,
                    destructive = true,
                ),
            ),
        )
        ConflictKind.MASS_DELETE -> Triple(
            "Has borrado ${conflict.count} mediciones en este teléfono",
            "¿Las borramos también de tu copia en la nube?",
            listOf(
                Option("Borrar también de la nube", null, ConflictChoice.CONFIRM_DELETIONS, destructive = true),
                Option("Recuperarlas desde la nube", null, ConflictChoice.UNDO_DELETIONS, destructive = false),
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
                    "Sincronización en pausa hasta que elijas qué datos conservar.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                )
                OutlinedButton(onClick = { collapsed = false }, enabled = enabled) { Text("Elegir qué datos conservar") }
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
                    Text("Cancelar")
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
                ) { Text("Continuar") }
            },
            dismissButton = { TextButton(onClick = { confirming = null }) { Text("Cancelar") } },
        )
    }
}
