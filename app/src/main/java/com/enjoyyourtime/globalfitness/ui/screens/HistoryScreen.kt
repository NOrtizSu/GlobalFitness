package com.enjoyyourtime.globalfitness.ui.screens

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.enjoyyourtime.globalfitness.data.Measurement
import com.enjoyyourtime.globalfitness.data.MeasurementSource
import com.enjoyyourtime.globalfitness.ui.Entry
import com.enjoyyourtime.globalfitness.ui.Fmt
import com.enjoyyourtime.globalfitness.ui.MainUiState
import com.enjoyyourtime.globalfitness.ui.components.AppIcons
import com.enjoyyourtime.globalfitness.ui.theme.ChartTheme
import com.enjoyyourtime.globalfitness.ui.theme.TabularNumbers
import java.time.Instant
import java.time.YearMonth
import java.time.ZoneId

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun HistoryScreen(
    state: MainUiState,
    onOpen: (Measurement) -> Unit,
) {
    val zone = ZoneId.systemDefault()
    val groups = remember(state.entries) {
        state.entries.asReversed()
            .groupBy { YearMonth.from(Instant.ofEpochMilli(it.measurement.timestamp).atZone(zone)) }
            .toList()
    }

    LazyColumn(contentPadding = PaddingValues(bottom = 96.dp)) {
        if (state.entries.isEmpty()) {
            item(key = "empty") {
                Text(
                    "No hay mediciones para ${state.selectedProfile?.name ?: "este perfil"}. " +
                        "Súbete a la báscula con la app abierta o añade una medición manual.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(24.dp),
                )
            }
        }

        groups.forEach { (month, list) ->
            stickyHeader(key = "h-$month") {
                Surface(color = MaterialTheme.colorScheme.surface, modifier = Modifier.fillMaxWidth()) {
                    Text(
                        Fmt.monthYear(list.first().measurement.timestamp) + " · ${list.size}",
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    )
                }
            }
            items(list, key = { it.measurement.id }) { entry ->
                HistoryRow(entry, onClick = { onOpen(entry.measurement) })
                HorizontalDivider(Modifier.padding(start = 16.dp), color = MaterialTheme.colorScheme.outlineVariant)
            }
        }
    }
}

@Composable
private fun HistoryRow(entry: Entry, onClick: () -> Unit) {
    val m = entry.measurement
    val fat = entry.composition.fatPercent
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(Fmt.dayTime(m.timestamp), style = MaterialTheme.typography.bodyLarge)
            if (m.source == MeasurementSource.MANUAL) {
                Text("Manual", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Column(horizontalAlignment = Alignment.End) {
            Text(Fmt.kg(m.weightKg), style = MaterialTheme.typography.titleMedium.merge(TabularNumbers), fontWeight = FontWeight.SemiBold)
            Text(
                if (fat != null) "Grasa ${Fmt.num(fat, 1)} %" else "Grasa —",
                style = MaterialTheme.typography.bodySmall.merge(TabularNumbers),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.width(12.dp))
        ImpedanceIndicator(m.impedance != null)
    }
}

@Composable
private fun ImpedanceIndicator(present: Boolean) {
    val description = if (present) "Con impedancia" else "Sin impedancia"
    Icon(
        AppIcons.Bolt,
        contentDescription = null,
        tint = if (present) ChartTheme.colors.series1 else MaterialTheme.colorScheme.outlineVariant,
        modifier = Modifier
            .size(18.dp)
            .semantics { contentDescription = description },
    )
}
