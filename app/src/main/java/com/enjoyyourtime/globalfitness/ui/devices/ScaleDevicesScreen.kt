@file:OptIn(ExperimentalMaterial3Api::class)

package com.enjoyyourtime.globalfitness.ui.devices

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.enjoyyourtime.globalfitness.scale.ConfigField
import com.enjoyyourtime.globalfitness.scale.ConfigKind
import com.enjoyyourtime.globalfitness.scale.LinkKind
import com.enjoyyourtime.globalfitness.scale.MatchConfidence
import com.enjoyyourtime.globalfitness.scale.ScaleDriver
import com.enjoyyourtime.globalfitness.scale.SessionStatus
import com.enjoyyourtime.globalfitness.ui.Fmt
import com.enjoyyourtime.globalfitness.ui.components.AppIcons
import com.enjoyyourtime.globalfitness.ui.components.SectionTitle

/** Scales screen: configured scales, nearby detected scales and the manual catalog. */
@Composable
fun ScaleDevicesScreen(
    onBack: () -> Unit,
    vm: ScaleDevicesViewModel = viewModel(factory = ScaleDevicesViewModel.Factory),
) {
    val state by vm.state.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    var catalogOpen by remember { mutableStateOf(false) }
    var binding by remember { mutableStateOf<ScaleDriver?>(null) }
    var renaming by remember { mutableStateOf<ConfiguredScaleItem?>(null) }
    var removing by remember { mutableStateOf<ConfiguredScaleItem?>(null) }

    LaunchedEffect(Unit) {
        vm.events.collect {
            snackbar.currentSnackbarData?.dismiss()
            snackbar.showSnackbar(it, withDismissAction = true)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Básculas") },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Volver") }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.padding(padding).fillMaxSize(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item { SectionTitle("Configuradas") }
            if (state.configured.isEmpty()) {
                item {
                    Text(
                        "Ninguna todavía. Súbete a tu báscula con la app abierta: si se reconoce sin dudas se " +
                            "configura sola. También puedes elegirla en el catálogo.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            items(state.configured, key = { it.scale.id }) { item ->
                ConfiguredScaleCard(
                    item = item,
                    onRename = { renaming = item },
                    onRemove = { removing = item },
                    onSaveConfig = { key, value -> vm.setConfig(item.scale.id, key, value) },
                )
            }
            item {
                FilledTonalButton(onClick = { catalogOpen = true }) {
                    Icon(Icons.Filled.Add, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text("Añadir desde el catálogo")
                }
            }

            item { SectionTitle("Detectadas cerca", Modifier.padding(top = 16.dp)) }
            if (state.detected.isEmpty()) {
                item {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(12.dp))
                        Text(
                            "Buscando básculas… súbete a la báscula para despertarla.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            items(state.detected, key = { "d-" + it.address }) { item ->
                DetectedScaleCard(item = item, onUse = { driver -> vm.useDetected(item, driver) })
            }
        }
    }

    if (catalogOpen) {
        CatalogDialog(
            query = state.catalogQuery,
            drivers = state.catalog,
            onQuery = vm::setQuery,
            onPick = { driver ->
                catalogOpen = false
                binding = driver
            },
            onDismiss = { catalogOpen = false },
        )
    }
    binding?.let { driver ->
        val candidates = state.detected.filter { d -> d.driver.id == driver.id || d.alternatives.any { it.id == driver.id } }
        BindDialog(
            driver = driver,
            candidates = candidates,
            onBind = { address ->
                vm.add(driver, address)
                binding = null
            },
            onDismiss = { binding = null },
        )
    }
    renaming?.let { item ->
        RenameDialog(
            initial = item.scale.name,
            onSave = {
                vm.rename(item.scale.id, it)
                renaming = null
            },
            onDismiss = { renaming = null },
        )
    }
    removing?.let { item ->
        AlertDialog(
            onDismissRequest = { removing = null },
            title = { Text("¿Quitar ${item.scale.name}?") },
            text = { Text("Las mediciones guardadas se conservan. No se volverá a configurar sola.") },
            confirmButton = {
                TextButton(onClick = {
                    vm.remove(item.scale.id)
                    removing = null
                }) { Text("Quitar", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { removing = null }) { Text("Cancelar") } },
        )
    }
}

@Composable
private fun ExperimentalBadge(verified: Boolean) {
    if (verified) return
    Surface(
        color = MaterialTheme.colorScheme.tertiaryContainer,
        contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
        shape = MaterialTheme.shapes.small,
    ) {
        Text("Experimental", style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp))
    }
}

@Composable
private fun ConfiguredScaleCard(
    item: ConfiguredScaleItem,
    onRename: () -> Unit,
    onRemove: () -> Unit,
    onSaveConfig: (String, String) -> String?,
) {
    val driver = item.driver
    OutlinedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(AppIcons.Scale, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(item.scale.name, style = MaterialTheme.typography.titleMedium)
                    Text(
                        driver?.let { "${it.brand} · ${it.displayName}" } ?: "Modelo desconocido (${item.scale.driverId})",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                IconButton(onClick = onRename) { Icon(Icons.Filled.Edit, contentDescription = "Renombrar") }
                IconButton(onClick = onRemove) { Icon(Icons.Filled.Delete, contentDescription = "Quitar") }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                ExperimentalBadge(driver?.verified ?: false)
                Text(
                    when (driver?.link) {
                        LinkKind.GATT -> "Conexión Bluetooth"
                        LinkKind.PASSIVE -> "Lectura de anuncios"
                        null -> ""
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(
                item.scale.address?.let { "Dispositivo $it" } ?: "Cualquier dispositivo compatible",
                style = MaterialTheme.typography.bodySmall,
                fontFamily = if (item.scale.address != null) FontFamily.Monospace else null,
            )
            Text(
                item.lastSeenMillis?.let { "Vista: ${Fmt.relative(it)}" } ?: "Aún no vista en esta sesión",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            item.session?.let { SessionLine(it.status, it.detail, it.weightKg) }
            if (item.missingConfig.isNotEmpty()) {
                Text(
                    "Falta configurar: ${item.missingConfig.joinToString()}",
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
private fun SessionLine(status: SessionStatus, detail: String?, weightKg: Double?) {
    val text = when (status) {
        SessionStatus.CONNECTING -> "Conectando…"
        SessionStatus.CONNECTED -> "Conectada, esperando datos…"
        SessionStatus.MEASURING -> "Midiendo…" + (weightKg?.let { " ${Fmt.kg(it)}" } ?: "")
        SessionStatus.SAVED -> "Guardado" + (weightKg?.let { " ${Fmt.kg(it)}" } ?: "")
        SessionStatus.DONE -> "Sesión terminada" + (detail?.let { ": $it" } ?: "")
        SessionStatus.FAILED -> "Error: " + (detail ?: "no se pudo leer la báscula")
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
private fun ConfigFieldEditor(field: ConfigField, current: String, onSave: (String) -> String?) {
    var value by remember(current) { mutableStateOf(current) }
    var error by remember(current) { mutableStateOf<String?>(null) }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        OutlinedTextField(
            value = value,
            onValueChange = { new ->
                value = when (field.kind) {
                    ConfigKind.HEX_KEY_32 -> new.filter { it.isDigit() || it.lowercaseChar() in 'a'..'f' }.take(32)
                    ConfigKind.USER_SLOT -> new.filter { it.isDigit() }.take(2)
                    ConfigKind.TEXT -> new
                }
                error = null
            },
            label = { Text(field.label + if (field.required) " *" else "") },
            supportingText = { Text(error ?: field.help) },
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
            OutlinedButton(onClick = { error = onSave(value) }) { Text("Guardar") }
        }
    }
}

@Composable
private fun DetectedScaleCard(item: DetectedScaleItem, onUse: (ScaleDriver) -> Unit) {
    OutlinedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(AppIcons.Bluetooth, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(item.driver.displayName, style = MaterialTheme.typography.titleSmall)
                    Text(
                        "${item.driver.brand} · ${item.name ?: "sin nombre"} · ${item.rssi} dBm",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(item.address, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                ExperimentalBadge(item.driver.verified)
                Text(
                    "Coincidencia " + confidenceLabel(item.confidence),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (item.alternatives.isNotEmpty()) {
                Text(
                    "También podría ser: " + item.alternatives.joinToString { it.displayName },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (item.alreadyConfigured) {
                Text("Ya configurada", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
            } else {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilledTonalButton(onClick = { onUse(item.driver) }) { Text("Usar esta") }
                    item.alternatives.take(2).forEach { alt ->
                        AssistChip(onClick = { onUse(alt) }, label = { Text(alt.displayName) })
                    }
                }
            }
        }
    }
}

private fun confidenceLabel(c: MatchConfidence): String = when (c) {
    MatchConfidence.HIGH -> "alta"
    MatchConfidence.MEDIUM -> "media"
    MatchConfidence.LOW -> "baja"
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
        title = { Text("Catálogo de básculas") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = query,
                    onValueChange = onQuery,
                    leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                    placeholder = { Text("Marca o modelo") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Text("${drivers.size} modelos", style = MaterialTheme.typography.labelSmall)
                LazyColumn(Modifier.heightIn(max = 420.dp)) {
                    items(drivers, key = { it.id }) { d ->
                        Column(
                            Modifier
                                .fillMaxWidth()
                                .clickable { onPick(d) }
                                .padding(vertical = 8.dp),
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text(d.displayName, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                                ExperimentalBadge(d.verified)
                            }
                            Text(
                                d.brand + " · " + if (d.link == LinkKind.GATT) "conexión Bluetooth" else "lectura de anuncios",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        HorizontalDivider()
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Cerrar") } },
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
        title = { Text(driver.displayName) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("¿Qué dispositivo usar?", style = MaterialTheme.typography.bodyMedium)
                candidates.forEach { c ->
                    OutlinedButton(onClick = { onBind(c.address) }, modifier = Modifier.fillMaxWidth()) {
                        Text("${c.name ?: "Sin nombre"} · ${c.address} · ${c.rssi} dBm")
                    }
                }
                if (candidates.isEmpty()) {
                    Text(
                        "No hay ninguna cerca ahora mismo. Puedes vincularla a cualquier dispositivo compatible " +
                            "y se usará la primera que se detecte.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                FilledTonalButton(onClick = { onBind(null) }, modifier = Modifier.fillMaxWidth()) {
                    Text("Cualquier dispositivo compatible")
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Cancelar") } },
    )
}

@Composable
private fun RenameDialog(initial: String, onSave: (String) -> Unit, onDismiss: () -> Unit) {
    var name by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Nombre de la báscula") },
        text = {
            OutlinedTextField(value = name, onValueChange = { name = it.take(40) }, singleLine = true, modifier = Modifier.fillMaxWidth())
        },
        confirmButton = { TextButton(onClick = { onSave(name) }, enabled = name.isNotBlank()) { Text("Guardar") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancelar") } },
    )
}
