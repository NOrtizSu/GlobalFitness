package com.enjoyyourtime.globalfitness.ui.today

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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.enjoyyourtime.globalfitness.ble.GattSessionInfo
import com.enjoyyourtime.globalfitness.ble.LiveScale
import com.enjoyyourtime.globalfitness.ble.ScanStatus
import com.enjoyyourtime.globalfitness.scale.SessionStatus
import com.enjoyyourtime.globalfitness.ui.BleAccess
import com.enjoyyourtime.globalfitness.ui.Fmt
import com.enjoyyourtime.globalfitness.ui.components.AppCard
import com.enjoyyourtime.globalfitness.ui.theme.ChartTheme
import com.enjoyyourtime.globalfitness.ui.theme.TabularNumbers

private val BUSY_SESSION = setOf(SessionStatus.CONNECTING, SessionStatus.CONNECTED, SessionStatus.MEASURING)

/** How long a failed GATT session stays on Hoy. */
private const val FAILED_SESSION_VISIBLE_MS = 2 * 60_000L

/** The GATT session worth showing on Hoy: one in progress, else a recent failure. */
fun visibleSession(sessions: Map<String, GattSessionInfo>, now: Long): GattSessionInfo? =
    sessions.values.filter { it.status in BUSY_SESSION }.maxByOrNull { it.atMillis }
        ?: sessions.values.filter { it.status == SessionStatus.FAILED && now - it.atMillis < FAILED_SESSION_VISIBLE_MS }
            .maxByOrNull { it.atMillis }

/** True when the scale card has something to say: a weighing, a GATT session or a scanning problem. */
fun scaleCardVisible(status: ScanStatus, live: LiveScale, session: GattSessionInfo?): Boolean = when (status) {
    ScanStatus.NoBluetoothLe, ScanStatus.MissingPermission, ScanStatus.BluetoothOff, is ScanStatus.Failed -> true
    is ScanStatus.Scanning -> status.locationOff || live != LiveScale.Idle || session != null
    ScanStatus.Stopped, ScanStatus.Starting -> live != LiveScale.Idle || session != null
}

/** Weighing in progress (passive or GATT) or what prevents scanning, with the fix. */
@Composable
fun ScaleLiveCard(
    status: ScanStatus,
    live: LiveScale,
    session: GattSessionInfo?,
    access: BleAccess,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val measuring = live is LiveScale.Measuring || live is LiveScale.Stabilized || live is LiveScale.Connecting
    AppCard(
        modifier,
        containerColor = if (measuring) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            when (status) {
                ScanStatus.NoBluetoothLe -> Problem(
                    title = "Bluetooth LE no disponible",
                    text = "Este teléfono no tiene Bluetooth de baja energía, necesario para leer la báscula.",
                )
                ScanStatus.MissingPermission -> Problem(
                    title = "Falta el permiso de Bluetooth",
                    text = "Para leer tu báscula y tu pulsera, GlobalFitness necesita el permiso de dispositivos cercanos " +
                        "(en Android 11 o anterior, el de ubicación). No se usa ni guarda tu ubicación.",
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
                    tonal = true,
                )
                else -> {
                    if (live != LiveScale.Idle) LiveContent(live) else session?.let { SessionContent(it) }
                    if (status is ScanStatus.Scanning && status.locationOff) {
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
            LiveScale.Idle -> Unit
            is LiveScale.Connecting -> Busy("Conectando con ${state.scaleName}…", "No te bajes de la báscula.")
            is LiveScale.Measuring -> WeightLine("Midiendo", state.weightKg, "Quédate quieto.")
            is LiveScale.Stabilized -> WeightLine("Estable · midiendo la composición…", state.weightKg, "No te bajes todavía.")
            is LiveScale.SaveFailed -> Problem(
                title = "No se pudo guardar ${Fmt.kg(state.weightKg)}",
                text = state.message,
            )
            is LiveScale.Saved -> {
                val title = if (state.alreadyStored) "Medición ya guardada" else "Medición guardada"
                val sub = if (state.impedance != null) "con impedancia (${state.impedance} Ω)" else "sin impedancia (¿calcetines o pies secos?)"
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
private fun SessionContent(session: GattSessionInfo) {
    when (session.status) {
        SessionStatus.CONNECTING -> Busy("Conectando con ${session.scaleName}…", "No te bajes de la báscula.")
        SessionStatus.CONNECTED -> Busy("${session.scaleName}: conectada", "Esperando la medición…")
        SessionStatus.MEASURING -> session.weightKg?.let { WeightLine("Midiendo · ${session.scaleName}", it, "Quédate quieto.") }
            ?: Busy("${session.scaleName}: midiendo…", null)
        SessionStatus.FAILED -> Problem(
            title = "No se pudo leer ${session.scaleName}",
            text = session.detail ?: "Vuelve a subirte a la báscula. Si se repite, revisa su configuración en Dispositivos.",
        )
        SessionStatus.SAVED, SessionStatus.DONE -> Unit
    }
}

@Composable
private fun Busy(title: String, subtitle: String?) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }) {
        CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 3.dp)
        Spacer(Modifier.width(16.dp))
        Column {
            Text(title, style = MaterialTheme.typography.titleMedium)
            if (subtitle != null) {
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun WeightLine(title: String, weightKg: Double, subtitle: String?) {
    val animated by animateFloatAsState(weightKg.toFloat(), animationSpec = tween(250), label = "weight")
    Column(Modifier.semantics { liveRegion = LiveRegionMode.Polite }) {
        Text(title, style = MaterialTheme.typography.titleMedium)
        Text(Fmt.kg(animated.toDouble()), style = MaterialTheme.typography.displaySmall.merge(TabularNumbers))
        if (subtitle != null) {
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun Problem(
    title: String,
    text: String,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
    tonal: Boolean = false,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.Warning, contentDescription = null, tint = ChartTheme.colors.warning)
            Spacer(Modifier.width(12.dp))
            Text(title, style = MaterialTheme.typography.titleMedium)
        }
        Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (actionLabel != null && onAction != null) {
            if (tonal) OutlinedButton(onClick = onAction) { Text(actionLabel) } else Button(onClick = onAction) { Text(actionLabel) }
        }
    }
}
