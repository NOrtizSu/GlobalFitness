package com.enjoyyourtime.globalfitness.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import com.enjoyyourtime.globalfitness.domain.Metric
import com.enjoyyourtime.globalfitness.domain.Projection
import com.enjoyyourtime.globalfitness.domain.TimePoint
import com.enjoyyourtime.globalfitness.domain.Trends
import com.enjoyyourtime.globalfitness.ui.Fmt
import com.enjoyyourtime.globalfitness.ui.MainUiState
import com.enjoyyourtime.globalfitness.ui.charts.ChartBand
import com.enjoyyourtime.globalfitness.ui.charts.ChartTarget
import com.enjoyyourtime.globalfitness.ui.charts.CompositionBar
import com.enjoyyourtime.globalfitness.ui.charts.TrendChart
import com.enjoyyourtime.globalfitness.ui.components.SectionTitle
import com.enjoyyourtime.globalfitness.ui.theme.TabularNumbers
import kotlinx.coroutines.delay

enum class ChartRange(val label: String, val days: Int?) {
    M1("1M", 30),
    M3("3M", 91),
    M6("6M", 182),
    Y1("1A", 365),
    ALL("Todo", null),
}

@Composable
fun ChartsScreen(
    state: MainUiState,
    metric: Metric,
    onMetric: (Metric) -> Unit,
    range: ChartRange,
    onRange: (ChartRange) -> Unit,
) {
    val profile = state.selectedProfile ?: return
    val entries = state.entries
    // "Now" moves on: refresh on resume, when data changes and every minute while visible.
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LifecycleResumeEffect(Unit) {
        now = System.currentTimeMillis()
        onPauseOrDispose { }
    }
    LaunchedEffect(entries) {
        while (true) {
            now = System.currentTimeMillis()
            delay(60_000)
        }
    }

    val allPoints = remember(entries, metric) {
        entries.mapNotNull { e -> metric.extract(e.composition)?.let { TimePoint(e.measurement.timestamp, it) } }
    }
    val allAverage = remember(allPoints) { Trends.movingAverage(allPoints) }
    val xEnd = maxOf(now, allPoints.lastOrNull()?.t ?: now)
    val firstT = allPoints.firstOrNull()?.t ?: now
    val xStart = range.days?.let { maxOf(xEnd - it * Trends.DAY_MS, firstT) } ?: firstT
    val points = allPoints.filter { it.t >= xStart }
    val average = allAverage.filter { it.t >= xStart }
    val latestWithValue = entries.lastOrNull { metric.extract(it.composition) != null }
    val band = latestWithValue?.let { e -> metric.normalRange(e.composition) }?.let {
        ChartBand(it, if (metric == Metric.WEIGHT) "Rango normal (IMC 18,5–24,9)" else "Rango normal")
    }
    val target = if (metric == Metric.WEIGHT) {
        profile.targetWeightKg?.let { ChartTarget(it, "Objetivo ${Fmt.num(it, 1)} kg") }
    } else {
        null
    }

    val chipState = rememberLazyListState()
    LaunchedEffect(metric) {
        val index = Metric.chartMetrics.indexOf(metric)
        if (index >= 0) chipState.animateScrollToItem(index)
    }

    LazyColumn(contentPadding = PaddingValues(bottom = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            LazyRow(
                state = chipState,
                contentPadding = PaddingValues(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.padding(top = 8.dp),
            ) {
                items(Metric.chartMetrics) { m ->
                    FilterChip(selected = m == metric, onClick = { onMetric(m) }, label = { Text(m.label) })
                }
            }
        }
        item {
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
                ChartRange.entries.forEachIndexed { i, r ->
                    SegmentedButton(
                        selected = r == range,
                        onClick = { onRange(r) },
                        shape = SegmentedButtonDefaults.itemShape(i, ChartRange.entries.size),
                        icon = {},
                        label = { Text(r.label) },
                    )
                }
            }
        }
        item {
            OutlinedCard(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
                Column(Modifier.padding(16.dp)) {
                    Text(
                        Fmt.withUnit(metric.label, if (metric.unit.isEmpty()) "" else "(${metric.unit})"),
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Spacer(Modifier.height(8.dp))
                    when {
                        allPoints.isEmpty() -> EmptyChart(
                            if (metric.needsImpedance && entries.isNotEmpty()) {
                                "Esta métrica necesita impedancia. Súbete descalzo y con los pies secos."
                            } else {
                                "Todavía no hay mediciones."
                            },
                        )
                        points.isEmpty() -> EmptyChart("Sin mediciones en este periodo. Prueba con un periodo más largo.")
                        points.size < 2 -> EmptyChart(
                            "Solo hay una medición en este periodo (${Fmt.metric(metric, points[0].v)}, ${Fmt.dayMonth(points[0].t)}). " +
                                "Se necesitan al menos 2 para dibujar la tendencia.",
                        )
                        else -> TrendChart(
                            points = points,
                            average = average,
                            xStart = xStart,
                            xEnd = xEnd,
                            valueDecimals = metric.decimals,
                            unit = metric.unit,
                            metricLabel = metric.label,
                            band = band,
                            target = target,
                        )
                    }
                }
            }
        }
        if (points.isNotEmpty()) {
            item { SectionTitle("Resumen del periodo", Modifier.padding(horizontal = 16.dp)) }
            val stats = Trends.rangeStats(points)
            if (stats != null) {
                val tiles = buildList {
                    add("Actual" to Fmt.metric(metric, stats.current))
                    add("Mín" to Fmt.metric(metric, stats.min))
                    add("Máx" to Fmt.metric(metric, stats.max))
                    add("Media" to Fmt.metric(metric, stats.average))
                    add("Cambio en el periodo" to Fmt.withUnit(Fmt.signed(stats.change, metric.decimals), metric.unit))
                    if (metric == Metric.WEIGHT) {
                        val rate = Trends.slopePerWeek(allPoints, now)
                        add(
                            "Ritmo" to (
                                rate?.let { "${Fmt.signed(it, 2)} kg/semana" }
                                    ?: "Faltan datos: ≥3 mediciones que abarquen ≥7 días en los últimos 28"
                                ),
                        )
                        val current = allAverage.lastOrNull()?.v ?: stats.current
                        val projection = Trends.projection(current, profile.targetWeightKg, rate, now)
                        add(
                            "Proyección al objetivo" to when (projection) {
                                Projection.NoTarget -> "Sin objetivo definido"
                                Projection.Reached -> "Objetivo alcanzado"
                                Projection.NoTrend -> "Sin tendencia hacia el objetivo"
                                is Projection.Eta -> "~ ${Fmt.dayMonthYear(projection.epochMillis)}"
                            },
                        )
                    }
                }
                items(tiles.chunked(2)) { row ->
                    Row(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        row.forEach { (label, value) -> StatTile(label, value, Modifier.weight(1f)) }
                        if (row.size == 1) Spacer(Modifier.weight(1f))
                    }
                }
            }
        }
        val withImpedance = entries.lastOrNull { it.composition.fatMassKg != null }
        if (withImpedance != null) {
            val c = withImpedance.composition
            item {
                OutlinedCard(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text("Composición actual", style = MaterialTheme.typography.titleMedium)
                        Text(
                            "${Fmt.relative(withImpedance.measurement.timestamp)} · ${Fmt.kg(c.weightKg)}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        CompositionBar(
                            fatKg = c.fatMassKg ?: 0.0,
                            muscleKg = c.muscleKg ?: 0.0,
                            boneKg = c.boneKg ?: 0.0,
                            weightKg = c.weightKg,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun EmptyChart(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.fillMaxWidth().padding(vertical = 48.dp),
    )
}

@Composable
private fun StatTile(label: String, value: String, modifier: Modifier = Modifier) {
    OutlinedCard(modifier) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(value, style = MaterialTheme.typography.titleMedium.merge(TabularNumbers), fontWeight = FontWeight.SemiBold)
        }
    }
}
