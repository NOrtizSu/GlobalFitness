package com.enjoyyourtime.globalfitness.ui.devices

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.enjoyyourtime.globalfitness.R
import com.enjoyyourtime.globalfitness.UiText
import com.enjoyyourtime.globalfitness.ble.ScaleSuggestion
import com.enjoyyourtime.globalfitness.scale.ConfigField
import com.enjoyyourtime.globalfitness.scale.ConfigKind
import com.enjoyyourtime.globalfitness.scale.LinkKind
import com.enjoyyourtime.globalfitness.scale.MatchConfidence
import com.enjoyyourtime.globalfitness.scale.ScaleDriver
import com.enjoyyourtime.globalfitness.scale.SessionStatus
import com.enjoyyourtime.globalfitness.ui.Fmt
import com.enjoyyourtime.globalfitness.ui.asString
import com.enjoyyourtime.globalfitness.ui.components.AppCard
import com.enjoyyourtime.globalfitness.ui.components.AppIcons
import com.enjoyyourtime.globalfitness.ui.components.ExperimentalBadge

/** Open dialogs of the scales section. */
@Stable
class ScaleDialogs {
    var catalogOpen by mutableStateOf(false)
    var binding by mutableStateOf<ScaleDriver?>(null)
    var renaming by mutableStateOf<ConfiguredScaleItem?>(null)
    var removing by mutableStateOf<ConfiguredScaleItem?>(null)
}

/** Scales: configured scales, the manual catalog and nearby detected scales. */
fun LazyListScope.scaleDevicesSection(
    state: ScaleDevicesUiState,
    dialogs: ScaleDialogs,
    onUse: (DetectedScaleItem, ScaleDriver) -> Unit,
    onSaveConfig: (scaleId: String, key: String, value: String) -> UiText?,
    onAcceptSuggestion: () -> Unit = {},
    onDeclineSuggestion: () -> Unit = {},
) {
    state.suggestion?.let { s ->
        item(key = "scales-suggestion") { ScaleSuggestionCard(s, onAccept = onAcceptSuggestion, onDecline = onDeclineSuggestion) }
    }
    if (state.configured.isEmpty()) {
        item(key = "scales-empty") {
            Text(
                stringResource(R.string.scales_none),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 4.dp),
            )
        }
    }
    items(state.configured, key = { "c-" + it.scale.id }) { item ->
        ConfiguredScaleCard(
            item = item,
            onRename = { dialogs.renaming = item },
            onRemove = { dialogs.removing = item },
            onSaveConfig = { key, value -> onSaveConfig(item.scale.id, key, value) },
        )
    }
    item(key = "scales-catalog") {
        FilledTonalButton(onClick = { dialogs.catalogOpen = true }, modifier = Modifier.heightIn(min = 48.dp)) {
            Icon(Icons.Filled.Add, contentDescription = null)
            Spacer(Modifier.width(8.dp))
            Text(stringResource(R.string.scales_add_from_catalog))
        }
    }
    item(key = "scales-detected-title") {
        Text(
            stringResource(R.string.scales_detected_nearby),
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 4.dp, top = 8.dp),
        )
    }
    if (state.detected.isEmpty()) {
        item(key = "scales-searching") {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(horizontal = 4.dp)) {
                CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                Spacer(Modifier.width(12.dp))
                Text(
                    stringResource(R.string.scales_searching),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
    items(state.detected, key = { "d-" + it.address }) { item ->
        DetectedScaleCard(item = item, onUse = { driver -> onUse(item, driver) })
    }
}

/** Catalog, binding, rename and remove dialogs of [scaleDevicesSection]. */
@Composable
fun ScaleDialogsHost(state: ScaleDevicesUiState, dialogs: ScaleDialogs, vm: ScaleDevicesViewModel) {
    if (dialogs.catalogOpen) {
        CatalogDialog(
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
        val candidates = state.detected.filter { d -> d.driver.id == driver.id || d.alternatives.any { it.id == driver.id } }
        BindDialog(
            driver = driver,
            candidates = candidates,
            onBind = { address ->
                vm.add(driver, address)
                dialogs.binding = null
            },
            onDismiss = { dialogs.binding = null },
        )
    }
    dialogs.renaming?.let { item ->
        RenameDialog(
            initial = item.scale.name,
            onSave = {
                vm.rename(item.scale.id, it)
                dialogs.renaming = null
            },
            onDismiss = { dialogs.renaming = null },
        )
    }
    dialogs.removing?.let { item ->
        AlertDialog(
            onDismissRequest = { dialogs.removing = null },
            title = { Text(stringResource(R.string.scales_remove_title, item.scale.name)) },
            text = { Text(stringResource(R.string.scales_remove_text)) },
            confirmButton = {
                TextButton(onClick = {
                    vm.remove(item.scale.id)
                    dialogs.removing = null
                }) { Text(stringResource(R.string.scales_remove), color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { dialogs.removing = null }) { Text(stringResource(R.string.action_cancel)) } },
        )
    }
}

/** "Is this your scale?": a recognised scale that needs a connection waits for the user's OK (also on Today). */
@Composable
internal fun ScaleSuggestionCard(s: ScaleSuggestion, onAccept: () -> Unit, onDecline: () -> Unit) {
    AppCard {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(AppIcons.Scale, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.scales_suggestion_title), style = MaterialTheme.typography.titleMedium)
                    Text(
                        "${brandLabel(s.brand)} · ${driverTitle(s.driverName, s.nameNote)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text("${s.deviceName ?: stringResource(R.string.scales_no_name)} · ${s.address}", style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
                }
            }
            if (!s.verified) ExperimentalBadge()
            Text(
                stringResource(R.string.scales_suggestion_text),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilledTonalButton(onClick = onAccept) { Text(stringResource(R.string.scales_suggestion_yes)) }
                OutlinedButton(onClick = onDecline) { Text(stringResource(R.string.scales_suggestion_no)) }
            }
        }
    }
}

@Composable
private fun ConfiguredScaleCard(
    item: ConfiguredScaleItem,
    onRename: () -> Unit,
    onRemove: () -> Unit,
    onSaveConfig: (String, String) -> UiText?,
) {
    val driver = item.driver
    AppCard {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(AppIcons.Scale, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(item.scale.name, style = MaterialTheme.typography.titleMedium)
                    Text(
                        driver?.let { "${it.brandLabel()} · ${it.title()}" } ?: stringResource(R.string.scales_unknown_model, item.scale.driverId),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                IconButton(onClick = onRename) { Icon(Icons.Filled.Edit, contentDescription = stringResource(R.string.scales_rename_named, item.scale.name)) }
                IconButton(onClick = onRemove) { Icon(Icons.Filled.Delete, contentDescription = stringResource(R.string.scales_remove_named, item.scale.name)) }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                if (driver?.verified != true) ExperimentalBadge()
                Text(
                    when (driver?.link) {
                        LinkKind.GATT -> stringResource(R.string.scales_link_gatt)
                        LinkKind.PASSIVE -> stringResource(R.string.scales_link_passive)
                        null -> ""
                    },
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(
                item.scale.address?.let { stringResource(R.string.scales_device_address, it) } ?: stringResource(R.string.scales_any_device_pending),
                style = MaterialTheme.typography.bodySmall,
                fontFamily = if (item.scale.address != null) FontFamily.Monospace else null,
            )
            Text(
                item.lastSeenMillis?.let { stringResource(R.string.scales_last_seen, Fmt.relative(it)) } ?: stringResource(R.string.scales_not_seen),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            item.session?.let { SessionLine(it.status, it.detail, it.weightKg) }
            if (item.missingConfig.isNotEmpty()) {
                Text(
                    stringResource(R.string.scales_missing_config, Fmt.list(item.missingConfig.map { stringResource(it) })),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            driver?.capabilities?.config?.forEach { field ->
                ConfigFieldEditor(field = field, current = item.scale.config[field.key].orEmpty(), onSave = { onSaveConfig(field.key, it) })
            }
        }
    }
}

@Composable
private fun SessionLine(status: SessionStatus, detail: com.enjoyyourtime.globalfitness.scale.ScaleNotice?, weightKg: Double?) {
    val detailText = detail?.text()
    val text = when (status) {
        SessionStatus.CONNECTING -> stringResource(R.string.scales_session_connecting)
        SessionStatus.CONNECTED -> stringResource(R.string.scales_session_connected)
        SessionStatus.MEASURING -> weightKg?.let { stringResource(R.string.scales_session_measuring_weight, Fmt.kg(it)) }
            ?: stringResource(R.string.scales_session_measuring)
        SessionStatus.SAVED -> weightKg?.let { stringResource(R.string.scales_session_saved_weight, Fmt.kg(it)) }
            ?: stringResource(R.string.scales_session_saved)
        SessionStatus.DONE -> detailText?.let { stringResource(R.string.scales_session_done_detail, it) }
            ?: stringResource(R.string.scales_session_done)
        SessionStatus.FAILED -> stringResource(R.string.scales_session_failed, detailText ?: stringResource(R.string.scales_session_read_failed))
    }
    val busy = status == SessionStatus.CONNECTING || status == SessionStatus.CONNECTED || status == SessionStatus.MEASURING
    Row(verticalAlignment = Alignment.CenterVertically) {
        if (busy) {
            CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp)
            Spacer(Modifier.width(8.dp))
        }
        Text(
            text,
            style = MaterialTheme.typography.bodySmall,
            color = if (status == SessionStatus.FAILED) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
        )
    }
}

@Composable
private fun ConfigFieldEditor(field: ConfigField, current: String, onSave: (String) -> UiText?) {
    var value by remember(current) { mutableStateOf(current) }
    var error by remember(current) { mutableStateOf<UiText?>(null) }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        OutlinedTextField(
            value = value,
            onValueChange = { new ->
                value = when (field.kind) {
                    ConfigKind.HEX_KEY_32 -> new.filter { it.isDigit() || it.lowercaseChar() in 'a'..'f' }.take(32)
                    ConfigKind.USER_SLOT -> new.filter { it.isDigit() }.take(3)
                    ConfigKind.PIN -> new.filter { it.isDigit() }.take(4)
                    ConfigKind.TEXT -> new
                }
                error = null
            },
            label = {
                val label = stringResource(field.labelRes())
                Text(if (field.required) stringResource(R.string.common_required_mark, label) else label)
            },
            supportingText = { Text(error?.asString() ?: field.help()) },
            isError = error != null,
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
            textStyle = if (field.kind == ConfigKind.HEX_KEY_32) {
                MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace)
            } else {
                MaterialTheme.typography.bodyMedium
            },
        )
        if (value != current) {
            OutlinedButton(onClick = { error = onSave(value) }) { Text(stringResource(R.string.action_save)) }
        }
    }
}

@Composable
private fun DetectedScaleCard(item: DetectedScaleItem, onUse: (ScaleDriver) -> Unit) {
    AppCard {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(AppIcons.Bluetooth, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(item.driver.title(), style = MaterialTheme.typography.titleSmall)
                    Text(
                        "${item.driver.brandLabel()} · ${item.name ?: stringResource(R.string.scales_no_name)} · " +
                            stringResource(R.string.value_dbm, Fmt.count(item.rssi.toLong())),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
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
                    stringResource(R.string.scales_could_also_be, Fmt.list(item.alternatives.map { it.title() })),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (item.alreadyConfigured) {
                Text(stringResource(R.string.scales_already_set_up), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
            } else {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilledTonalButton(onClick = { onUse(item.driver) }) { Text(stringResource(R.string.scales_use_this)) }
                    item.alternatives.take(2).forEach { alt ->
                        AssistChip(onClick = { onUse(alt) }, label = { Text(alt.title()) })
                    }
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
private fun CatalogDialog(
    query: String,
    drivers: List<ScaleDriver>,
    onQuery: (String) -> Unit,
    onPick: (ScaleDriver) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.scales_catalog_title)) },
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
                                Text(d.title(), style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                                if (!d.verified) ExperimentalBadge()
                            }
                            Text(
                                d.brandLabel() + " · " +
                                    stringResource(if (d.link == LinkKind.GATT) R.string.scales_link_gatt else R.string.scales_link_passive),
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

@Composable
private fun BindDialog(
    driver: ScaleDriver,
    candidates: List<DetectedScaleItem>,
    onBind: (String?) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(driver.title()) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.scales_bind_question), style = MaterialTheme.typography.bodyMedium)
                candidates.forEach { c ->
                    OutlinedButton(onClick = { onBind(c.address) }, modifier = Modifier.fillMaxWidth()) {
                        Text(
                            "${c.name ?: stringResource(R.string.scales_no_name)} · ${c.address} · " +
                                stringResource(R.string.value_dbm, Fmt.count(c.rssi.toLong())),
                        )
                    }
                }
                if (candidates.isEmpty()) {
                    Text(
                        stringResource(R.string.scales_bind_none_nearby),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                FilledTonalButton(onClick = { onBind(null) }, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.scales_any_device))
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}

@Composable
private fun RenameDialog(initial: String, onSave: (String) -> Unit, onDismiss: () -> Unit) {
    var name by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.scales_rename_title)) },
        text = {
            OutlinedTextField(value = name, onValueChange = { name = it.take(40) }, singleLine = true, modifier = Modifier.fillMaxWidth())
        },
        confirmButton = { TextButton(onClick = { onSave(name) }, enabled = name.isNotBlank()) { Text(stringResource(R.string.action_save)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}
