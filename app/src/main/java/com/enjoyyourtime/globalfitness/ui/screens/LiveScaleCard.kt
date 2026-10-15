package com.enjoyyourtime.globalfitness.ui.screens

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.enjoyyourtime.globalfitness.ble.LiveScale
import com.enjoyyourtime.globalfitness.ble.ScanStatus
import com.enjoyyourtime.globalfitness.ui.BleAccess
import com.enjoyyourtime.globalfitness.ui.Fmt
import com.enjoyyourtime.globalfitness.ui.components.AppIcons
import com.enjoyyourtime.globalfitness.ui.theme.ChartTheme
import com.enjoyyourtime.globalfitness.ui.theme.TabularNumbers

/** Top card of Inicio: scanner status and the weighing in progress. */
@Composable
fun LiveScaleCard(
    status: ScanStatus,
    live: LiveScale,
    access: BleAccess,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            when (status) {
                ScanStatus.NoBluetoothLe -> Problem(
                    title = "Bluetooth LE no disponible",
                    text = "Este teléfono no tiene Bluetooth de baja energía, necesario para leer la báscula.",
                )
                ScanStatus.MissingPermission -> Problem(
                    title = "Falta el permiso de Bluetooth",
                    text = "GlobalFitness solo escucha los anuncios de la báscula Mi Scale 2 cercana. " +
                        "Necesita el permiso de dispositivos cercanos (en Android 11 o anterior, el de ubicación). " +
                        "No se usa ni guarda tu ubicación.",
                    actionLabel = if (access.permanentlyDenied) "Abrir ajustes" else "Conceder permiso",
                    onAction = if (access.permanentlyDenied) access.openAppSettings else access.requestPermissions,
                )
                ScanStatus.BluetoothOff -> Problem(
                    title = "Bluetooth desactivado",
                    text = "Activa el Bluetooth para detectar la báscula.",
                    actionLabel = "Activar Bluetooth",
                    onAction = access.enableBluetooth,
                )
                is ScanStatus.Failed -> Problem(
                    title = "Error al buscar la báscula",
                    text = status.message,
                    actionLabel = "Reintentar",
                    onAction = onRetry,
                )
                ScanStatus.Stopped -> StatusLine(icon = AppIcons.Bluetooth, title = "Escaneo en pausa", subtitle = null)
                ScanStatus.Starting -> Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(12.dp))
                    Text("Iniciando escaneo…", style = MaterialTheme.typography.titleMedium)
                }
                is ScanStatus.Scanning -> {
                    LiveContent(live)
                    if (status.locationOff) {
                        Problem(
                            title = "Ubicación desactivada",
                            text = "En Android 11 o anterior el sistema no entrega anuncios Bluetooth si la ubicación está apagada.",
                            actionLabel = "Activar ubicación",
                            onAction = access.openLocationSettings,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun LiveContent(live: LiveScale) {
    AnimatedContent(
        targetState = live,
        contentKey = { it::class },
        transitionSpec = { fadeIn(tween(200)) togetherWith fadeOut(tween(200)) },
        label = "live",
    ) { state ->
        when (state) {
            LiveScale.Idle -> Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                Spacer(Modifier.width(12.dp))
                Column {
                    Text("Buscando báscula…", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "Súbete descalzo con la app abierta.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            is LiveScale.Measuring -> WeightLine("Midiendo", state.weightKg, null)
            is LiveScale.Stabilized -> WeightLine("Estable – midiendo impedancia…", state.weightKg, "No te bajes todavía")
            is LiveScale.SaveFailed -> Problem(
                title = "No se pudo guardar ${Fmt.kg(state.weightKg)}",
                text = state.message,
            )
            is LiveScale.Saved -> {
                val title = when {
                    state.alreadyStored -> "Medición ya guardada"
                    state.profileName != null -> "Guardado ✓ para ${state.profileName}"
                    else -> "Guardado ✓ · falta asignar perfil"
                }
                val sub = if (state.impedance != null) "Con impedancia (${state.impedance} Ω)" else "Sin impedancia (¿calcetines/pies secos?)"
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.CheckCircle, contentDescription = null, tint = ChartTheme.colors.good)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.semantics { liveRegion = LiveRegionMode.Polite }) {
                        Text(title, style = MaterialTheme.typography.titleMedium)
                        Text(
                            "${Fmt.kg(state.weightKg)} · $sub",
                            style = MaterialTheme.typography.bodySmall.merge(TabularNumbers),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun WeightLine(title: String, weightKg: Double, subtitle: String?) {
    val animated by animateFloatAsState(weightKg.toFloat(), animationSpec = tween(250), label = "weight")
    Column(Modifier.semantics { liveRegion = LiveRegionMode.Polite }) {
        Text(title, style = MaterialTheme.typography.titleMedium)
        Text(
            Fmt.kg(animated.toDouble()),
            style = MaterialTheme.typography.displaySmall.merge(TabularNumbers),
            fontWeight = FontWeight.SemiBold,
        )
        if (subtitle != null) {
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun StatusLine(icon: ImageVector, title: String, subtitle: String?) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.width(12.dp))
        Column {
            Text(title, style = MaterialTheme.typography.titleMedium)
            if (subtitle != null) Text(subtitle, style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun Problem(title: String, text: String, actionLabel: String? = null, onAction: (() -> Unit)? = null) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.Warning, contentDescription = null, tint = ChartTheme.colors.warning)
            Spacer(Modifier.width(12.dp))
            Text(title, style = MaterialTheme.typography.titleMedium)
        }
        Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (actionLabel != null && onAction != null) {
            if (actionLabel == "Reintentar") {
                OutlinedButton(onClick = onAction) { Text(actionLabel) }
            } else {
                Button(onClick = onAction) { Text(actionLabel) }
            }
        }
    }
}
