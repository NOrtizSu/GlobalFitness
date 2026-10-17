package com.enjoyyourtime.globalfitness.ui.wearables

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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.enjoyyourtime.globalfitness.R
import com.enjoyyourtime.globalfitness.scale.MatchConfidence
import com.enjoyyourtime.globalfitness.ui.Fmt
import com.enjoyyourtime.globalfitness.ui.components.AppCard
import com.enjoyyourtime.globalfitness.ui.components.AppIcons
import com.enjoyyourtime.globalfitness.ui.components.ExperimentalBadge
import com.enjoyyourtime.globalfitness.wearable.WearableDetection
import com.enjoyyourtime.globalfitness.wearable.WearableDriver
import com.enjoyyourtime.globalfitness.wearable.WearableSummary

/** Open dialogs of the wearables section. */
@Stable
class WearableDialogs {
    var catalogOpen by mutableStateOf(false)
    var binding by mutableStateOf<WearableDriver?>(null)
    var removing by mutableStateOf<WearableSummary?>(null)
}

/** Devices → Bands & watches: the suggestion, configured devices, the catalog and detected devices. */
fun LazyListScope.wearablesSection(
    state: WearablesUiState,
    dialogs: WearableDialogs,
    onOpen: (String) -> Unit,
    onUse: (WearableDriver, address: String, name: String?) -> Unit,
    onAcceptSuggestion: () -> Unit,
    onDeclineSuggestion: () -> Unit,
) {
    state.suggestion?.let { s ->
        item(key = "wearables-suggestion") { WearableSuggestionCard(s, onAccept = onAcceptSuggestion, onDecline = onDeclineSuggestion) }
    }
    if (state.configured.isEmpty()) {
        item(key = "wearables-empty") {
            Text(
                stringResource(R.string.wearables_none),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 4.dp),
            )
        }
    }
    items(state.configured, key = { "w-" + it.device.id }) { summary ->
        ConfiguredWearableCard(summary, onOpen = { onOpen(summary.device.id) }, onRemove = { dialogs.removing = summary })
    }
    item(key = "wearables-catalog") {
        FilledTonalButton(onClick = { dialogs.catalogOpen = true }, modifier = Modifier.heightIn(min = 48.dp)) {
            Icon(Icons.Filled.Add, contentDescription = null)
            Spacer(Modifier.width(8.dp))
            Text(stringResource(R.string.wearables_add_from_catalog))
        }
    }
    item(key = "wearables-detected-title") {
        Text(
            stringResource(R.string.wearables_detected),
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 4.dp, top = 8.dp),
        )
    }
    val detected = state.detected
    if (detected.isEmpty()) {
        item(key = "wearables-detected-none") {
            Text(
                stringResource(R.string.wearables_detected_none),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 4.dp),
            )
        }
    }
    items(detected, key = { "wd-" + it.address }) { item ->
        DetectedWearableCard(item, onUse = { driver -> onUse(driver, item.address, item.name) })
    }
}

/** Catalog, binding and remove dialogs of [wearablesSection]. */
@Composable
fun WearableDialogsHost(state: WearablesUiState, dialogs: WearableDialogs, vm: WearablesViewModel) {
    if (dialogs.catalogOpen) {
        WearableCatalogDialog(
            query = state.catalogQuery,
            drivers = state.catalog,
            onQuery = vm::setQuery,
            onPick = { driver ->
                dialogs.catalogOpen = false
                dialogs.binding = driver
            },
            onDismiss = { dialogs.catalogOpen = false },
        )
    }
    dialogs.binding?.let { driver ->
        WearableBindDialog(
            driver = driver,
            detected = state.detected,
            onBind = { item ->
                vm.use(driver, item.address, item.name)
                dialogs.binding = null
            },
            onDismiss = { dialogs.binding = null },
        )
    }
    dialogs.removing?.let { summary ->
        AlertDialog(
            onDismissRequest = { dialogs.removing = null },
            title = { Text(stringResource(R.string.scales_remove_title, summary.device.name)) },
            text = { Text(stringResource(R.string.wearables_remove_text)) },
            confirmButton = {
                TextButton(onClick = {
                    vm.remove(summary)
                    dialogs.removing = null
                }) { Text(stringResource(R.string.scales_remove), color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { dialogs.removing = null }) { Text(stringResource(R.string.action_cancel)) } },
        )
    }
}

/** "Is this your band or watch?": a device recognised with certainty, set up only after the user's OK. */
@Composable
fun WearableSuggestionCard(s: WearableDetection.Suggest, onAccept: () -> Unit, onDecline: () -> Unit) {
    AppCard {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(AppIcons.Watch, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.wearables_suggestion_title), style = MaterialTheme.typography.titleMedium)
                    Text("${s.driver.brand} · ${s.driver.displayName}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text("${s.name ?: stringResource(R.string.scales_no_name)} · ${s.address}", style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
                }
            }
            if (!s.driver.verified) ExperimentalBadge()
            Text(
                stringResource(if (s.driver.needsAuthKey) R.string.wearables_suggestion_text_key else R.string.wearables_suggestion_text),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilledTonalButton(onClick = onAccept) { Text(stringResource(R.string.wearables_suggestion_yes)) }
                OutlinedButton(onClick = onDecline) { Text(stringResource(R.string.scales_suggestion_no)) }
            }
        }
    }
}

@Composable
private fun ConfiguredWearableCard(summary: WearableSummary, onOpen: () -> Unit, onRemove: () -> Unit) {
    val driver = summary.driver
    AppCard {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(AppIcons.Watch, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(summary.device.name, style = MaterialTheme.typography.titleMedium)
                    Text(
                        driver?.let { "${it.brand} · ${it.displayName}" } ?: stringResource(R.string.wearable_status_unknown_model),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                IconButton(onClick = onRemove) {
                    Icon(Icons.Filled.Delete, contentDescription = stringResource(R.string.scales_remove_named, summary.device.name))
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                if (driver?.verified != true) ExperimentalBadge()
                Text(
                    wearableStatus(summary),
                    style = MaterialTheme.typography.bodySmall,
                    color = if (summary.needsKey) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(summary.device.address, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
            FilledTonalButton(onClick = onOpen, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.wearables_open)) }
        }
    }
}

@Composable
private fun DetectedWearableCard(item: DetectedWearableItem, onUse: (WearableDriver) -> Unit) {
    AppCard {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(AppIcons.Bluetooth, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(item.driver.displayName, style = MaterialTheme.typography.titleSmall)
                    val details = listOfNotNull(
                        item.driver.brand,
                        item.name ?: stringResource(R.string.scales_no_name),
                        if (item.bonded) stringResource(R.string.wearables_paired) else null,
                        item.rssi?.let { stringResource(R.string.value_dbm, Fmt.count(it.toLong())) },
                    )
                    Text(details.joinToString(" · "), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(item.address, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                if (!item.driver.verified) ExperimentalBadge()
                Text(
                    stringResource(confidenceLabel(item.confidence)),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (item.alternatives.isNotEmpty()) {
                Text(
                    stringResource(R.string.scales_could_also_be, Fmt.list(item.alternatives.map { it.displayName })),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (item.alreadyConfigured) {
                Text(stringResource(R.string.wearables_already_set_up), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
            } else {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilledTonalButton(onClick = { onUse(item.driver) }) { Text(stringResource(R.string.wearables_use_this)) }
                    item.alternatives.take(2).forEach { alt -> AssistChip(onClick = { onUse(alt) }, label = { Text(alt.displayName) }) }
                }
            }
        }
    }
}

private fun confidenceLabel(c: MatchConfidence): Int = when (c) {
    MatchConfidence.HIGH -> R.string.scales_match_high
    MatchConfidence.MEDIUM -> R.string.scales_match_medium
    MatchConfidence.LOW -> R.string.scales_match_low
}

@Composable
private fun WearableCatalogDialog(
    query: String,
    drivers: List<WearableDriver>,
    onQuery: (String) -> Unit,
    onPick: (WearableDriver) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.wearables_catalog_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = query,
                    onValueChange = onQuery,
                    leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                    placeholder = { Text(stringResource(R.string.scales_catalog_search)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(pluralStringResource(R.plurals.scales_catalog_count, drivers.size, drivers.size), style = MaterialTheme.typography.labelMedium)
                LazyColumn(Modifier.heightIn(max = 420.dp)) {
                    items(drivers, key = { it.id }) { d ->
                        Column(
                            Modifier
                                .fillMaxWidth()
                                .heightIn(min = 48.dp)
                                .clickable(onClickLabel = stringResource(R.string.scales_catalog_choose)) { onPick(d) }
                                .padding(vertical = 8.dp),
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text(d.displayName, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                                if (!d.verified) ExperimentalBadge()
                            }
                            val keyText = d.vendorApp?.takeIf { d.needsAuthKey }?.let { stringResource(R.string.wearables_needs_key, it) }
                                ?: stringResource(R.string.wearables_no_key_needed)
                            Text(
                                listOf(d.brand, transportLabel(d.transport), keyText).joinToString(" · "),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        HorizontalDivider()
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_close)) } },
    )
}

/** Which detected / paired device is the chosen model (devices this driver recognises first). */
@Composable
private fun WearableBindDialog(
    driver: WearableDriver,
    detected: List<DetectedWearableItem>,
    onBind: (DetectedWearableItem) -> Unit,
    onDismiss: () -> Unit,
) {
    val matching = detected.filter { d -> d.driver.id == driver.id || d.alternatives.any { it.id == driver.id } }
    val others = detected - matching.toSet()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(driver.displayName) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.wearables_bind_question), style = MaterialTheme.typography.bodyMedium)
                matching.forEach { c -> CandidateButton(c, onBind) }
                if (matching.isEmpty()) {
                    Text(
                        stringResource(R.string.wearables_bind_none),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (others.isNotEmpty()) {
                    Text(stringResource(R.string.wearables_bind_others), style = MaterialTheme.typography.labelMedium)
                    others.forEach { c -> CandidateButton(c, onBind) }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}

@Composable
private fun CandidateButton(c: DetectedWearableItem, onBind: (DetectedWearableItem) -> Unit) {
    OutlinedButton(onClick = { onBind(c) }, modifier = Modifier.fillMaxWidth(), enabled = !c.alreadyConfigured) {
        Text(
            listOfNotNull(
                c.name ?: stringResource(R.string.scales_no_name),
                c.address,
                if (c.bonded) stringResource(R.string.wearables_paired) else null,
            ).joinToString(" · "),
        )
    }
}
