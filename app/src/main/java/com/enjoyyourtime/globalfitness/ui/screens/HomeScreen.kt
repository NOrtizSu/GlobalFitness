package com.enjoyyourtime.globalfitness.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.enjoyyourtime.globalfitness.ble.LiveScale
import com.enjoyyourtime.globalfitness.ble.ScanStatus
import com.enjoyyourtime.globalfitness.data.MeasurementSource
import com.enjoyyourtime.globalfitness.domain.Classifier
import com.enjoyyourtime.globalfitness.domain.Metric
import com.enjoyyourtime.globalfitness.domain.TimePoint
import com.enjoyyourtime.globalfitness.domain.Trends
import com.enjoyyourtime.globalfitness.ui.BleAccess
import com.enjoyyourtime.globalfitness.ui.Entry
import com.enjoyyourtime.globalfitness.ui.Fmt
import com.enjoyyourtime.globalfitness.ui.MainUiState
import com.enjoyyourtime.globalfitness.ui.components.AppIcons
import com.enjoyyourtime.globalfitness.ui.components.SectionTitle
import com.enjoyyourtime.globalfitness.ui.components.StatusChip
import com.enjoyyourtime.globalfitness.ui.theme.TabularNumbers
import kotlin.math.abs

private val TILE_METRICS = listOf(
    Metric.BMI, Metric.FAT_PERCENT, Metric.FAT_MASS, Metric.MUSCLE, Metric.WATER, Metric.BONE,
    Metric.VISCERAL, Metric.BMR, Metric.METABOLIC_AGE, Metric.PROTEIN, Metric.IDEAL_WEIGHT,
)

const val NO_IMPEDANCE_HINT = "Sin impedancia (¿calcetines/pies secos?)"

@Composable
fun HomeScreen(
    state: MainUiState,
    scanStatus: ScanStatus,
    live: LiveScale,
    access: BleAccess,
    onRetryScan: () -> Unit,
    onOpenMetric: (Metric) -> Unit,
) {
    val entries = state.entries
    val latest = entries.lastOrNull()
    val previous = entries.getOrNull(entries.size - 2)
    val weightTrend = remember(entries) {
        Trends.weeklyTrend(Trends.movingAverage(entries.map { TimePoint(it.measurement.timestamp, it.measurement.weightKg) }))
    }

    LazyColumn(
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item { LiveScaleCard(scanStatus, live, access, onRetryScan) }

        if (latest == null) {
            item {
                OutlinedCard(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("Aún no hay mediciones", style = MaterialTheme.typography.titleMedium)
                        Text(
                            "Súbete a la báscula descalzo y con la app abierta. La medición aparecerá aquí " +
                                "en cuanto la báscula termine de medir la impedancia.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            return@LazyColumn
        }

        item { HeroCard(latest, previous, weightTrend, onClick = { onOpenMetric(Metric.WEIGHT) }) }
        item { BodyTypeCard(latest) }
        item { SectionTitle("Composición corporal") }
        val rows = TILE_METRICS.chunked(2)
        items(rows) { row ->
            Row(Modifier.height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                row.forEach { metric ->
                    MetricTile(
                        metric = metric,
                        latest = latest,
                        entries = entries,
                        onClick = { onOpenMetric(if (metric == Metric.IDEAL_WEIGHT) Metric.WEIGHT else metric) },
                        modifier = Modifier.weight(1f).fillMaxHeight(),
                    )
                }
                if (row.size == 1) Spacer(Modifier.weight(1f))
            }
        }
        item {
            Text(
                "Estimaciones con las fórmulas de Xiaomi (ingeniería inversa). No son un diagnóstico médico.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
    }
}

@Composable
private fun HeroCard(latest: Entry, previous: Entry?, weeklyTrend: Double?, onClick: () -> Unit) {
    val m = latest.measurement
    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
    ) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                "Último peso · ${Fmt.relative(m.timestamp)}",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
            )
            Row(verticalAlignment = Alignment.Bottom) {
                Text(
                    Fmt.num(m.weightKg, 2),
                    style = MaterialTheme.typography.displayLarge.merge(TabularNumbers),
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    "kg",
                    style = MaterialTheme.typography.headlineSmall,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                    modifier = Modifier.padding(bottom = 10.dp),
                )
            }
            val deltaText = previous?.let { "${Fmt.signed(m.weightKg - it.measurement.weightKg, 2)} kg vs anterior" }
                ?: "Primera medición"
            Text(
                deltaText,
                style = MaterialTheme.typography.bodyMedium.merge(TabularNumbers),
                color = MaterialTheme.colorScheme.onPrimaryContainer,
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                val (icon, text) = when {
                    weeklyTrend == null -> AppIcons.TrendFlat to "Tendencia 7 días: faltan datos"
                    abs(weeklyTrend) < 0.1 -> AppIcons.TrendFlat to "Tendencia 7 días: estable (${Fmt.signed(weeklyTrend, 2)} kg)"
                    weeklyTrend > 0 -> AppIcons.TrendUp to "Tendencia 7 días: subiendo (${Fmt.signed(weeklyTrend, 2)} kg)"
                    else -> AppIcons.TrendDown to "Tendencia 7 días: bajando (${Fmt.signed(weeklyTrend, 2)} kg)"
                }
                Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.onPrimaryContainer, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(6.dp))
                Text(
                    text,
                    style = MaterialTheme.typography.bodyMedium.merge(TabularNumbers),
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                )
            }
            val tags = buildList {
                if (m.source == MeasurementSource.MANUAL) add("Manual")
                if (m.impedance == null) add(NO_IMPEDANCE_HINT)
            }
            if (tags.isNotEmpty()) {
                Text(
                    tags.joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                )
            }
        }
    }
}

@Composable
private fun BodyTypeCard(latest: Entry) {
    val type = Classifier.bodyType(latest.composition)
    OutlinedCard(Modifier.fillMaxWidth()) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("Tipo de cuerpo", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (type == null) {
                    Text("No disponible", style = MaterialTheme.typography.titleMedium)
                    Text(NO_IMPEDANCE_HINT, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                } else {
                    Text(type.name, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                    Text(type.description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            if (type != null) {
                Spacer(Modifier.width(12.dp))
                BodyTypeGrid(type.index)
            }
        }
    }
}

/** 3x3 grid: rows = fat (alta, normal, baja), columns = muscle (bajo, normal, alto). */
@Composable
private fun BodyTypeGrid(index: Int) {
    val highlight = MaterialTheme.colorScheme.primary
    val idle = MaterialTheme.colorScheme.surfaceContainerHighest
    Column(
        verticalArrangement = Arrangement.spacedBy(3.dp),
        modifier = Modifier.semantics { contentDescription = "Posición en la matriz grasa/músculo" },
    ) {
        Text("más músculo →", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        for (row in 0..2) {
            Row(horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                for (col in 0..2) {
                    val active = row * 3 + col == index
                    Box(
                        Modifier
                            .size(16.dp)
                            .background(if (active) highlight else idle, RoundedCornerShape(3.dp))
                            .then(
                                if (active) Modifier.border(1.dp, MaterialTheme.colorScheme.onSurface, RoundedCornerShape(3.dp)) else Modifier,
                            ),
                    )
                }
            }
        }
        Text("↓ menos grasa", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun MetricTile(metric: Metric, latest: Entry, entries: List<Entry>, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val c = latest.composition
    val value = metric.extract(c)
    OutlinedCard(onClick = onClick, modifier = modifier.heightIn(min = 132.dp)) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(metric.label, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
            if (value == null) {
                Text("—", style = MaterialTheme.typography.titleLarge)
                Text(NO_IMPEDANCE_HINT, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                return@Column
            }
            Text(
                Fmt.metric(metric, value),
                style = MaterialTheme.typography.titleLarge.merge(TabularNumbers),
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
            )
            metric.classify(c)?.let { StatusChip(it) }
            val deltaText = if (metric == Metric.IDEAL_WEIGHT) {
                "Diferencia ${Fmt.signed(c.weightKg - value, 1)} kg"
            } else {
                val prev = entries.dropLast(1).lastOrNull { metric.extract(it.composition) != null }
                prev?.let { p ->
                    val d = value - (metric.extract(p.composition) ?: value)
                    Fmt.withUnit(Fmt.signed(d, metric.decimals), metric.unit) + " vs anterior"
                }
            }
            if (deltaText != null) {
                Text(
                    deltaText,
                    style = MaterialTheme.typography.bodySmall.merge(TabularNumbers),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                )
            }
        }
    }
}
