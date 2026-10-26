package com.enjoyyourtime.globalfitness.ui.body

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import com.enjoyyourtime.globalfitness.R
import com.enjoyyourtime.globalfitness.data.MeasurementSource
import com.enjoyyourtime.globalfitness.domain.Metric
import com.enjoyyourtime.globalfitness.domain.Projection
import com.enjoyyourtime.globalfitness.domain.TimePoint
import com.enjoyyourtime.globalfitness.domain.Trends
import com.enjoyyourtime.globalfitness.ui.Entry
import com.enjoyyourtime.globalfitness.ui.Fmt
import com.enjoyyourtime.globalfitness.ui.MainUiState
import com.enjoyyourtime.globalfitness.ui.label
import com.enjoyyourtime.globalfitness.ui.charts.ChartBand
import com.enjoyyourtime.globalfitness.ui.charts.ChartTarget
import com.enjoyyourtime.globalfitness.ui.charts.CompositionBar
import com.enjoyyourtime.globalfitness.ui.charts.TrendChart
import com.enjoyyourtime.globalfitness.ui.components.AppCard
import com.enjoyyourtime.globalfitness.ui.components.AppIcons
import com.enjoyyourtime.globalfitness.ui.components.NoticeCard
import com.enjoyyourtime.globalfitness.ui.components.SectionTitle
import com.enjoyyourtime.globalfitness.ui.components.StatusChip
import com.enjoyyourtime.globalfitness.ui.components.fullBleed
import com.enjoyyourtime.globalfitness.ui.progress.ProgressTabContent
import com.enjoyyourtime.globalfitness.ui.theme.TabularNumbers
import kotlinx.coroutines.delay
import java.time.Instant
import java.time.YearMonth
import java.time.ZoneId

enum class ChartRange(@param:StringRes val label: Int, val days: Int?) {
    M1(R.string.range_1m, 30),
    M3(R.string.range_3m, 91),
    M6(R.string.range_6m, 182),
    Y1(R.string.range_1y, 365),
    ALL(R.string.range_all, null),
}

/** Space above each top-level block (the list itself has no spacing, history rows sit 2 dp apart). */
private val BlockGap = 12.dp

/**
 * Body (the Cuerpo tab of Progreso): metric trend chart with range, period stats, projection and
 * composition, then the history grouped by month. Tapping a row opens its detail sheet (with
 * delete). [listState] is hoisted so the "Add weight manually" button can shrink while the page is
 * scrolled.
 */
@Composable
fun BodyScreen(
    state: MainUiState,
    metric: Metric,
    onMetric: (Metric) -> Unit,
    range: ChartRange,
    onRange: (ChartRange) -> Unit,
    onDelete: (Long) -> Unit,
    listState: LazyListState,
) {
    val profile = state.profile ?: return
    val entries = state.entries
    var detailId by rememberSaveable { mutableLongStateOf(-1L) }

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

    // Optional metrics (skeletal muscle) only when some measurement has them, or while selected.
    val chipMetrics = remember(entries, metric) {
        Metric.chartMetrics.filter { m -> !m.optional || m == metric || entries.any { m.extract(it.composition) != null } }
    }
    val chipState = rememberLazyListState()
    LaunchedEffect(metric) {
        val index = chipMetrics.indexOf(metric)
        if (index >= 0) chipState.animateScrollToItem(index)
    }
    val zone = ZoneId.systemDefault()
    val months = remember(entries) {
        entries.asReversed()
            .groupBy { YearMonth.from(Instant.ofEpochMilli(it.measurement.timestamp).atZone(zone)) }
            .toList()
    }

    ProgressTabContent(listState, topPadding = 0.dp, bottomPadding = 104.dp, itemSpacing = 0.dp) {
        if (entries.isEmpty()) {
            block("empty") {
                NoticeCard(
                    title = stringResource(R.string.body_no_measurements_title),
                    text = stringResource(R.string.body_empty_text),
                    icon = AppIcons.Scale,
                )
            }
            return@ProgressTabContent
        }
        block("metrics") {
            LazyRow(
                state = chipState,
                contentPadding = PaddingValues(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fullBleed(16.dp),
            ) {
                items(chipMetrics) { m ->
                    FilterChip(selected = m == metric, onClick = { onMetric(m) }, label = { Text(m.label()) })
                }
            }
        }
        block("range") {
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                ChartRange.entries.forEachIndexed { i, r ->
                    SegmentedButton(
                        selected = r == range,
                        onClick = { onRange(r) },
                        shape = SegmentedButtonDefaults.itemShape(i, ChartRange.entries.size),
                        icon = {},
                        label = { Text(stringResource(r.label)) },
                    )
                }
            }
        }
        block("chart") {
            ChartCard(
                metric = metric,
                latest = latestWithValue,
                entries = entries,
                points = points,
                average = average,
                allPoints = allPoints,
                xStart = xStart,
                xEnd = xEnd,
                targetWeightKg = profile.targetWeightKg,
            )
        }
        val stats = Trends.rangeStats(points)
        if (stats != null) {
            block("stats-title") { SectionTitle(stringResource(R.string.body_period_summary)) }
            val rate = if (metric == Metric.WEIGHT) Trends.slopePerWeek(allPoints, now) else null
            val tiles = listOfNotNull(
                Fmt.string(R.string.body_stat_current) to Fmt.metric(metric, stats.current),
                Fmt.string(R.string.body_stat_change) to Fmt.withUnit(metric, Fmt.signed(stats.change, metric.decimals), stats.change),
                Fmt.string(R.string.body_stat_min) to Fmt.metric(metric, stats.min),
                Fmt.string(R.string.body_stat_max) to Fmt.metric(metric, stats.max),
                Fmt.string(R.string.body_stat_average) to Fmt.metric(metric, stats.average),
                if (metric == Metric.WEIGHT) {
                    Fmt.string(R.string.body_stat_rate) to (
                        rate?.let { Fmt.string(R.string.body_rate_value, Fmt.string(R.string.value_kg, Fmt.signed(it, 2))) }
                            ?: Fmt.string(R.string.common_not_enough_data)
                        )
                } else {
                    null
                },
                if (metric == Metric.WEIGHT) {
                    val current = allAverage.lastOrNull()?.v ?: stats.current
                    Fmt.string(R.string.body_stat_projection) to when (val p = Trends.projection(current, profile.targetWeightKg, rate, now)) {
                        Projection.NoTarget -> Fmt.string(R.string.body_projection_no_goal)
                        Projection.Reached -> Fmt.string(R.string.body_projection_reached)
                        Projection.NoTrend -> Fmt.string(R.string.body_projection_no_trend)
                        is Projection.Eta -> Fmt.string(R.string.body_projection_eta, Fmt.dayMonthYear(p.epochMillis))
                    }
                } else {
                    null
                },
            )
            tiles.chunked(2).forEachIndexed { index, row ->
                block("stats-$index", gap = if (index == 0) 4.dp else 8.dp) {
                    Row(Modifier.height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        row.forEach { (label, value) -> StatTile(label, value, Modifier.weight(1f).fillMaxHeight()) }
                        if (row.size == 1) Spacer(Modifier.weight(1f))
                    }
                }
            }
            if (metric == Metric.WEIGHT && rate == null) {
                block("rate-hint", gap = 6.dp) {
                    Text(
                        stringResource(R.string.body_rate_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 4.dp),
                    )
                }
            }
        }
        val withComposition = entries.lastOrNull { it.composition.fatMassKg != null }
        if (withComposition != null) {
            block("composition") { CompositionCard(withComposition) }
        }

        block("history-title") { SectionTitle(stringResource(R.string.body_history)) }
        months.forEach { (month, list) ->
            item(key = "m-$month") {
                Text(
                    Fmt.monthYear(list.first().measurement.timestamp) + " · " + Fmt.count(list.size.toLong()),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier
                        .padding(start = 4.dp, top = 12.dp, bottom = 6.dp)
                        .semantics { heading() },
                )
            }
            itemsIndexed(list, key = { _, e -> e.measurement.id }) { index, entry ->
                HistoryRow(entry, index, list.size, onClick = { detailId = entry.measurement.id })
            }
        }
        block("disclaimer") {
            Text(
                stringResource(R.string.common_disclaimer),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 4.dp),
            )
        }
    }

    entries.firstOrNull { it.measurement.id == detailId }?.let { entry ->
        MeasurementDetailSheet(
            entry = entry,
            onDelete = {
                onDelete(entry.measurement.id)
                detailId = -1L
            },
            onDismiss = { detailId = -1L },
        )
    }
}

private fun LazyListScope.block(key: String, gap: Dp = BlockGap, content: @Composable () -> Unit) {
    item(key = key) { Box(Modifier.padding(top = gap)) { content() } }
}

@Composable
private fun ChartCard(
    metric: Metric,
    latest: Entry?,
    entries: List<Entry>,
    points: List<TimePoint>,
    average: List<TimePoint>,
    allPoints: List<TimePoint>,
    xStart: Long,
    xEnd: Long,
    targetWeightKg: Double?,
) {
    val bandLabel = if (metric == Metric.WEIGHT) {
        stringResource(R.string.body_normal_range_bmi, Fmt.num(18.5, 1), Fmt.num(24.9, 1))
    } else {
        stringResource(R.string.body_normal_range)
    }
    val band = latest?.let { e -> metric.normalRange(e.composition) }?.let { ChartBand(it, bandLabel) }
    val goalLabel = targetWeightKg?.let { stringResource(R.string.body_goal_line, Fmt.kg(it, 1)) }
    val target = if (metric == Metric.WEIGHT) targetWeightKg?.let { ChartTarget(it, goalLabel.orEmpty()) } else null
    val metricLabel = metric.label()
    AppCard {
        Column(Modifier.padding(16.dp)) {
            Text(
                Fmt.unit(metric)?.let { stringResource(R.string.body_chart_title_unit, metricLabel, it) } ?: metricLabel,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            val value = latest?.let { metric.extract(it.composition) }
            if (latest != null && value != null) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(Fmt.metric(metric, value), style = MaterialTheme.typography.headlineMedium.merge(TabularNumbers))
                    metric.classify(latest.composition)?.let { StatusChip(it) }
                }
                Text(
                    Fmt.relative(latest.measurement.timestamp),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.height(12.dp))
            when {
                allPoints.isEmpty() -> EmptyChart(
                    stringResource(
                        if (metric.needsImpedance && entries.isNotEmpty()) R.string.body_chart_needs_composition else R.string.body_chart_no_measurements,
                    ),
                )
                points.isEmpty() -> EmptyChart(stringResource(R.string.body_chart_none_in_period))
                points.size < 2 -> EmptyChart(
                    stringResource(R.string.body_chart_single, Fmt.metric(metric, points[0].v), Fmt.dayMonth(points[0].t)),
                )
                else -> TrendChart(
                    points = points,
                    average = average,
                    xStart = xStart,
                    xEnd = xEnd,
                    formatValue = { Fmt.metric(metric, it) },
                    metricLabel = metricLabel,
                    band = band,
                    target = target,
                )
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
        modifier = Modifier.fillMaxWidth().padding(vertical = 40.dp),
    )
}

@Composable
private fun StatTile(label: String, value: String, modifier: Modifier = Modifier) {
    AppCard(modifier, containerColor = MaterialTheme.colorScheme.surfaceContainer) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(value, style = MaterialTheme.typography.titleMedium.merge(TabularNumbers))
        }
    }
}

@Composable
private fun CompositionCard(entry: Entry) {
    val c = entry.composition
    AppCard {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(stringResource(R.string.body_current_composition), style = MaterialTheme.typography.titleMedium)
            Text(
                listOfNotNull(
                    "${Fmt.relative(entry.measurement.timestamp)} · ${Fmt.kg(c.weightKg)}",
                    compositionSourceLabel(entry.measurement),
                ).joinToString("\n"),
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

/** One measurement in a month group; the group reads as one rounded block (2 dp gaps). */
@Composable
private fun HistoryRow(entry: Entry, index: Int, count: Int, onClick: () -> Unit) {
    val m = entry.measurement
    val fat = entry.composition.fatPercent
    val outer = 20.dp
    val inner = 4.dp
    val shape = RoundedCornerShape(
        topStart = if (index == 0) outer else inner,
        topEnd = if (index == 0) outer else inner,
        bottomStart = if (index == count - 1) outer else inner,
        bottomEnd = if (index == count - 1) outer else inner,
    )
    Surface(
        onClick = onClick,
        shape = shape,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        modifier = Modifier.fillMaxWidth().padding(top = if (index == 0) 0.dp else 2.dp),
    ) {
        Row(Modifier.padding(horizontal = 16.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(Fmt.dayTime(m.timestamp), style = MaterialTheme.typography.bodyLarge)
                Text(
                    stringResource(if (m.source == MeasurementSource.MANUAL) R.string.source_manual else R.string.source_scale),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Column(horizontalAlignment = Alignment.End) {
                Text(Fmt.kg(m.weightKg), style = MaterialTheme.typography.titleMedium.merge(TabularNumbers))
                Text(
                    if (fat != null) stringResource(R.string.body_row_fat, Fmt.percent(fat, 1)) else stringResource(R.string.body_row_no_composition),
                    style = MaterialTheme.typography.bodySmall.merge(TabularNumbers),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.width(12.dp))
            val hasComposition = fat != null
            Icon(
                AppIcons.Bolt,
                contentDescription = null,
                tint = if (hasComposition) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
                modifier = Modifier.size(18.dp),
            )
        }
    }
}
