package com.enjoyyourtime.globalfitness.ui.today

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.enjoyyourtime.globalfitness.account.AccountUser
import com.enjoyyourtime.globalfitness.ble.GattSessionInfo
import com.enjoyyourtime.globalfitness.ble.LiveScale
import com.enjoyyourtime.globalfitness.ble.ScaleSuggestion
import com.enjoyyourtime.globalfitness.ble.ScanStatus
import com.enjoyyourtime.globalfitness.cloud.CloudSyncStatus
import com.enjoyyourtime.globalfitness.domain.Metric
import com.enjoyyourtime.globalfitness.domain.TimePoint
import com.enjoyyourtime.globalfitness.domain.Trends
import com.enjoyyourtime.globalfitness.health.HcAvailability
import com.enjoyyourtime.globalfitness.ui.BandSummary
import com.enjoyyourtime.globalfitness.ui.BleAccess
import com.enjoyyourtime.globalfitness.ui.Entry
import com.enjoyyourtime.globalfitness.ui.Fmt
import com.enjoyyourtime.globalfitness.ui.MainUiState
import com.enjoyyourtime.globalfitness.ui.body.NO_COMPOSITION_HINT
import com.enjoyyourtime.globalfitness.ui.components.AppCard
import com.enjoyyourtime.globalfitness.ui.components.AppIcons
import com.enjoyyourtime.globalfitness.ui.components.ProgressRing
import com.enjoyyourtime.globalfitness.ui.components.SectionTitle
import com.enjoyyourtime.globalfitness.ui.components.Sparkline
import com.enjoyyourtime.globalfitness.ui.components.StatusChip
import com.enjoyyourtime.globalfitness.ui.components.TabPage
import com.enjoyyourtime.globalfitness.ui.devices.ScaleSuggestionCard
import com.enjoyyourtime.globalfitness.ui.devices.bandStatusText
import com.enjoyyourtime.globalfitness.ui.health.HealthDashboard
import com.enjoyyourtime.globalfitness.ui.health.HealthFormat
import com.enjoyyourtime.globalfitness.ui.health.HealthUiState
import com.enjoyyourtime.globalfitness.ui.health.HealthViewModel
import com.enjoyyourtime.globalfitness.ui.theme.TabularNumbers
import kotlinx.coroutines.delay
import java.time.LocalDate
import kotlin.math.abs

private val HIGHLIGHTS = listOf(Metric.FAT_PERCENT, Metric.MUSCLE, Metric.WATER, Metric.VISCERAL)
private const val SLEEP_GOAL_MINUTES = 8 * 60
private const val SPARKLINE_DAYS = 30

/** Everything Hoy shows that does not come from the measurements. */
data class TodayStatus(
    val scanStatus: ScanStatus,
    val live: LiveScale,
    val sessions: Map<String, GattSessionInfo>,
    val health: HealthUiState,
    val dashboard: HealthDashboard?,
    val user: AccountUser?,
    val cloud: CloudSyncStatus,
    val band: BandSummary?,
    /** A nearby scale waiting for "¿Es tu báscula?" (same as in Dispositivos). */
    val suggestion: ScaleSuggestion? = null,
)

/**
 * "Hoy": greeting with today's rings (Health Connect), the weighing in progress, the latest
 * weight with its 30-day trend, body composition highlights and quick status chips.
 */
@Composable
fun TodayScreen(
    state: MainUiState,
    status: TodayStatus,
    access: BleAccess,
    onRetryScan: () -> Unit,
    onConnectHealth: () -> Unit,
    onInstallHealth: () -> Unit,
    onOpenMetric: (Metric) -> Unit,
    onAddWeight: () -> Unit,
    onOpenAccount: () -> Unit,
    onOpenDevices: () -> Unit,
    onOpenActivity: () -> Unit,
    onOpenBand: () -> Unit,
    onAcceptSuggestion: () -> Unit,
    onDeclineSuggestion: () -> Unit,
) {
    // Keeps relative times and the visibility of a failed session fresh.
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(30_000)
            now = System.currentTimeMillis()
        }
    }
    val entries = state.entries
    val session = visibleSession(status.sessions, now)

    TabPage(title = "Hoy") {
        item(key = "hero") {
            HeroCard(
                name = state.profile?.name,
                health = status.health,
                dashboard = status.dashboard,
                onConnect = onConnectHealth,
                onInstall = onInstallHealth,
                onOpenActivity = onOpenActivity,
            )
        }
        val conflict = status.user != null && (status.cloud.conflict != null || status.cloud.deletionPending)
        if (conflict) {
            item(key = "conflict") {
                AppCard(
                    containerColor = MaterialTheme.colorScheme.errorContainer,
                    onClick = onOpenAccount,
                    onClickLabel = "Abrir cuenta",
                ) {
                    Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Filled.Warning, contentDescription = null, tint = MaterialTheme.colorScheme.onErrorContainer)
                        Spacer(Modifier.width(16.dp))
                        Column(Modifier.weight(1f)) {
                            Text("Copia en la nube en pausa", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onErrorContainer)
                            Text(
                                "Necesitamos que elijas qué datos conservar. Toca para decidir.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onErrorContainer,
                            )
                        }
                    }
                }
            }
        }
        item(key = "chips") {
            StatusChips(
                status = status,
                onOpenAccount = onOpenAccount,
                onOpenDevices = onOpenDevices,
                onOpenBand = onOpenBand,
            )
        }
        status.suggestion?.let { s ->
            item(key = "scale-suggestion") { ScaleSuggestionCard(s, onAccept = onAcceptSuggestion, onDecline = onDeclineSuggestion) }
        }
        if (scaleCardVisible(status.scanStatus, status.live, session)) {
            item(key = "scale") { ScaleLiveCard(status.scanStatus, status.live, session, access, onRetryScan) }
        }
        item(key = "weight") { WeightCard(entries, now, onOpen = { onOpenMetric(Metric.WEIGHT) }, onAddWeight = onAddWeight) }
        if (entries.isNotEmpty()) {
            val latest = entries.lastOrNull { it.composition.fatPercent != null }
            item(key = "composition-title") {
                Column {
                    SectionTitle("Composición corporal")
                    if (latest != null && latest != entries.last()) {
                        Text(
                            "Última medición con composición: ${Fmt.relative(latest.measurement.timestamp, now).lowercase(Fmt.LOCALE)}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(start = 4.dp),
                        )
                    }
                }
            }
            if (latest == null) {
                item(key = "composition-empty") {
                    Text(
                        NO_COMPOSITION_HINT,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 4.dp),
                    )
                }
            } else {
                HIGHLIGHTS.chunked(2).forEachIndexed { index, row ->
                    item(key = "composition-$index") {
                        Row(Modifier.height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            row.forEach { metric ->
                                HighlightTile(metric, latest, onClick = { onOpenMetric(metric) }, modifier = Modifier.weight(1f).fillMaxHeight())
                            }
                        }
                    }
                }
            }
        }
        item(key = "disclaimer") {
            Text(
                "Estimaciones de la báscula o calculadas con fórmulas publicadas. No son un diagnóstico médico.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 4.dp),
            )
        }
    }
}

@Composable
private fun HeroCard(
    name: String?,
    health: HealthUiState,
    dashboard: HealthDashboard?,
    onConnect: () -> Unit,
    onInstall: () -> Unit,
    onOpenActivity: () -> Unit,
) {
    val onContainer = MaterialTheme.colorScheme.onPrimaryContainer
    AppCard(containerColor = MaterialTheme.colorScheme.primaryContainer) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Column {
                Text(
                    name?.let { "Hola, $it" } ?: "Hola",
                    style = MaterialTheme.typography.headlineSmall,
                    color = onContainer,
                    modifier = Modifier.semantics { heading() },
                )
                Text(longDate(LocalDate.now()), style = MaterialTheme.typography.bodyMedium, color = onContainer)
            }
            val connected = health.availability == HcAvailability.AVAILABLE && health.canRead
            when {
                connected -> Rings(dashboard, onOpenActivity)
                health.availability == HcAvailability.AVAILABLE -> HeroCallToAction(
                    text = "Conecta Health Connect para ver aquí tus pasos, tu sueño y tu pulso en reposo.",
                    action = "Conectar Health Connect",
                    onClick = onConnect,
                )
                health.availability == HcAvailability.NOT_INSTALLED || health.availability == HcAvailability.UPDATE_REQUIRED ->
                    HeroCallToAction(
                        text = "Con Health Connect verás aquí tus pasos, tu sueño y tu pulso en reposo de tu pulsera o reloj.",
                        action = if (health.availability == HcAvailability.NOT_INSTALLED) "Instalar Health Connect" else "Actualizar Health Connect",
                        onClick = onInstall,
                    )
                else -> Unit
            }
        }
    }
}

@Composable
private fun HeroCallToAction(text: String, action: String, onClick: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(text, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onPrimaryContainer)
        Button(onClick = onClick, modifier = Modifier.heightIn(min = 48.dp)) { Text(action) }
    }
}

@Composable
private fun Rings(d: HealthDashboard?, onOpenActivity: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val track = scheme.onPrimaryContainer.copy(alpha = 0.14f)
    val steps = d?.todaySummary?.steps
    val sleep = d?.lastNight?.asleepMinutes
    val resting = d?.todaySummary?.restingHr ?: d?.restingTrend?.current?.average?.let { Math.round(it).toInt() }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        RingColumn(
            label = "Pasos",
            caption = "de ${HealthFormat.count(HealthViewModel.STEPS_GOAL)}",
            ring = {
                ProgressRing(
                    progress = steps?.let { it.toFloat() / HealthViewModel.STEPS_GOAL },
                    value = steps?.let { HealthFormat.count(it) } ?: "—",
                    unit = "",
                    color = scheme.primary,
                    trackColor = track,
                    description = steps?.let { "Pasos hoy: ${HealthFormat.count(it)} de ${HealthFormat.count(HealthViewModel.STEPS_GOAL)}" }
                        ?: "Pasos hoy: sin datos",
                )
            },
        )
        RingColumn(
            label = "Sueño",
            caption = "de 8 h",
            ring = {
                ProgressRing(
                    progress = sleep?.let { it.toFloat() / SLEEP_GOAL_MINUTES },
                    value = sleep?.let { "${it / 60}:${String.format(Fmt.LOCALE, "%02d", it % 60)}" } ?: "—",
                    unit = if (sleep != null) "h" else "",
                    color = scheme.tertiary,
                    trackColor = track,
                    description = sleep?.let { "Sueño de anoche: ${HealthFormat.minutes(it)} de 8 horas" } ?: "Sueño de anoche: sin datos",
                )
            },
        )
        RingColumn(
            label = "Pulso reposo",
            caption = "lpm",
            ring = {
                // Scale: a full ring is 100 lpm, the upper end of the normal resting range.
                ProgressRing(
                    progress = resting?.let { it / 100f },
                    value = resting?.toString() ?: "—",
                    unit = "",
                    color = scheme.secondary,
                    trackColor = track,
                    description = resting?.let { "Pulso en reposo: $it latidos por minuto" } ?: "Pulso en reposo: sin datos",
                )
            },
        )
    }
    if (d != null && !d.hasData) {
        Text(
            "Health Connect todavía no tiene datos de tu pulsera o reloj.",
            style = MaterialTheme.typography.bodySmall,
            color = scheme.onPrimaryContainer,
        )
    }
    FilledTonalButton(
        onClick = onOpenActivity,
        colors = ButtonDefaults.filledTonalButtonColors(
            containerColor = scheme.surfaceContainerLowest,
            contentColor = scheme.primary,
        ),
        modifier = Modifier.heightIn(min = 48.dp),
    ) { Text("Ver actividad") }
}

@Composable
private fun RingColumn(label: String, caption: String, ring: @Composable () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        ring()
        Text(label, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onPrimaryContainer, textAlign = TextAlign.Center)
        Text(caption, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onPrimaryContainer)
    }
}

@Composable
private fun StatusChips(
    status: TodayStatus,
    onOpenAccount: () -> Unit,
    onOpenDevices: () -> Unit,
    onOpenBand: () -> Unit,
) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        val scale = when (status.scanStatus) {
            is ScanStatus.Scanning -> "Báscula: escuchando"
            ScanStatus.Starting -> "Báscula: iniciando"
            ScanStatus.MissingPermission -> "Báscula: falta permiso"
            ScanStatus.BluetoothOff -> "Bluetooth apagado"
            ScanStatus.NoBluetoothLe -> "Sin Bluetooth LE"
            ScanStatus.Stopped -> "Báscula: en pausa"
            is ScanStatus.Failed -> "Báscula: error"
        }
        StatusAssistChip(scale, AppIcons.Bluetooth, onOpenDevices)
        if (status.user != null) {
            val cloud = status.cloud
            val text = when {
                cloud.conflict != null || cloud.deletionPending -> "Nube: en pausa"
                cloud.syncing || cloud.deleting -> "Nube: sincronizando…"
                cloud.error != null -> "Nube: error"
                cloud.pendingUpload -> "Nube: pendiente de subir"
                cloud.lastSyncAt != null -> "Nube: ${Fmt.relative(cloud.lastSyncAt).lowercase(Fmt.LOCALE)}"
                else -> "Nube: conectada"
            }
            StatusAssistChip(text, AppIcons.Cloud, onOpenAccount)
        }
        status.band?.let { StatusAssistChip("Pulsera: ${bandStatusText(it).removePrefix("Modo directo · ").lowercase(Fmt.LOCALE)}", AppIcons.Watch, onOpenBand) }
    }
}

@Composable
private fun StatusAssistChip(text: String, icon: ImageVector, onClick: () -> Unit) {
    AssistChip(
        onClick = onClick,
        label = { Text(text, maxLines = 1) },
        leadingIcon = { Icon(icon, contentDescription = null, modifier = Modifier.size(AssistChipDefaults.IconSize)) },
    )
}

@Composable
private fun WeightCard(entries: List<Entry>, now: Long, onOpen: () -> Unit, onAddWeight: () -> Unit) {
    val latest = entries.lastOrNull()
    if (latest == null) {
        AppCard {
            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Aún no hay mediciones", style = MaterialTheme.typography.titleMedium)
                Text(
                    "Súbete a la báscula descalzo y con la app abierta: la medición aparecerá aquí. " +
                        "Si tu báscula no es compatible, añade el peso a mano.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                FilledTonalButton(onClick = onAddWeight, modifier = Modifier.heightIn(min = 48.dp)) {
                    Icon(Icons.Filled.Add, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text("Añadir peso manual")
                }
            }
        }
        return
    }
    val m = latest.measurement
    val points = remember(entries) { entries.map { TimePoint(it.measurement.timestamp, it.measurement.weightKg) } }
    val weekly = remember(points) { Trends.weeklyTrend(Trends.movingAverage(points)) }
    val recent = points.filter { it.t >= now - SPARKLINE_DAYS * Trends.DAY_MS }.map { it.v }
    AppCard(onClick = onOpen, onClickLabel = "Ver evolución del peso") {
        Row(Modifier.padding(20.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text("Peso", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Row(verticalAlignment = Alignment.Bottom) {
                    Text(Fmt.num(m.weightKg, 2), style = MaterialTheme.typography.displaySmall.merge(TabularNumbers))
                    Spacer(Modifier.width(4.dp))
                    Text("kg", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(bottom = 6.dp))
                }
                val (icon, trend) = when {
                    weekly == null -> AppIcons.TrendFlat to "Cambio semanal: faltan datos"
                    abs(weekly) < 0.1 -> AppIcons.TrendFlat to "Estable esta semana (${Fmt.signed(weekly, 2)} kg)"
                    else -> (if (weekly > 0) AppIcons.TrendUp else AppIcons.TrendDown) to "${Fmt.signed(weekly, 2)} kg en 7 días"
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(icon, contentDescription = null, modifier = Modifier.size(18.dp), tint = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.width(6.dp))
                    Text(trend, style = MaterialTheme.typography.bodyMedium.merge(TabularNumbers))
                }
                Text(
                    Fmt.relative(m.timestamp, now),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (recent.size >= 2) {
                Spacer(Modifier.width(16.dp))
                Column(horizontalAlignment = Alignment.End) {
                    Sparkline(recent, MaterialTheme.colorScheme.primary, Modifier.width(112.dp).height(56.dp))
                    Text("30 días", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

@Composable
private fun HighlightTile(metric: Metric, latest: Entry, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val c = latest.composition
    val value = metric.extract(c)
    AppCard(modifier, onClick = onClick, onClickLabel = "Ver evolución") {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(metric.label, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
            Text(value?.let { Fmt.metric(metric, it) } ?: "—", style = MaterialTheme.typography.titleLarge.merge(TabularNumbers), maxLines = 1)
            if (value != null) metric.classify(c)?.let { StatusChip(it) }
        }
    }
}

private val WEEKDAYS = arrayOf("lunes", "martes", "miércoles", "jueves", "viernes", "sábado", "domingo")
private val MONTHS = arrayOf(
    "enero", "febrero", "marzo", "abril", "mayo", "junio",
    "julio", "agosto", "septiembre", "octubre", "noviembre", "diciembre",
)

/** "Miércoles, 15 de octubre". */
private fun longDate(d: LocalDate): String =
    "${WEEKDAYS[d.dayOfWeek.value - 1]}, ${d.dayOfMonth} de ${MONTHS[d.monthValue - 1]}".replaceFirstChar { it.uppercase(Fmt.LOCALE) }
