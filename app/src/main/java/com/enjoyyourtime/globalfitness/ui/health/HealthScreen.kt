@file:OptIn(ExperimentalMaterial3Api::class)

package com.enjoyyourtime.globalfitness.ui.health

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.enjoyyourtime.globalfitness.domain.Classification
import com.enjoyyourtime.globalfitness.health.HcAvailability
import com.enjoyyourtime.globalfitness.health.HealthDataType
import com.enjoyyourtime.globalfitness.health.HealthMappings
import com.enjoyyourtime.globalfitness.health.HealthPermissions
import com.enjoyyourtime.globalfitness.health.SleepStage
import com.enjoyyourtime.globalfitness.health.domain.HealthStats
import com.enjoyyourtime.globalfitness.health.domain.HeartRateZones
import com.enjoyyourtime.globalfitness.ui.Fmt
import com.enjoyyourtime.globalfitness.ui.components.Dot
import com.enjoyyourtime.globalfitness.ui.components.SectionTitle
import com.enjoyyourtime.globalfitness.ui.components.StatusChip
import com.enjoyyourtime.globalfitness.ui.theme.ChartTheme
import com.enjoyyourtime.globalfitness.ui.theme.TabularNumbers
import java.time.LocalDate
import java.time.ZoneId
import kotlin.math.roundToInt

private const val SLEEP_CHART_NIGHTS = 14

@Composable
fun HealthScreen(onBack: () -> Unit, vm: HealthViewModel = viewModel(factory = HealthViewModel.Factory)) {
    val state by vm.uiState.collectAsStateWithLifecycle()
    val dashboard by vm.dashboard.collectAsStateWithLifecycle()
    val detail by vm.detail.collectAsStateWithLifecycle()
    var stepsRange by rememberSaveable { mutableStateOf(DayRange.D30) }

    LifecycleResumeEffect(Unit) {
        vm.onResume()
        onPauseOrDispose { }
    }
    val permissionLauncher = rememberLauncherForActivityResult(vm.permissionContract) { vm.onPermissionsResult() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Salud · Health Connect") },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Volver") }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface),
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.padding(padding).fillMaxSize(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                ConnectionCard(
                    state = state,
                    onConnect = { permissionLauncher.launch(vm.requestedPermissions()) },
                    onOpenSettings = { vm.openHealthConnectSettings() },
                    onOpenPlayStore = { vm.openPlayStore() },
                )
            }
            val available = state.availability == HcAvailability.AVAILABLE
            if (available && state.canRead) {
                item { SyncCard(state, onSync = vm::syncNow) }
                val d = dashboard
                if (d != null) {
                    if (!d.hasData && !state.sync.running) item { NoDataCard() }
                    // Partial grants: each section is shown only when its data type is readable.
                    val readable = HealthPermissions.readableTypes(state.granted)
                    item { SectionTitle("Hoy") }
                    item { TodayGrid(d) }
                    if (HealthDataType.STEPS in readable) item { StepsCard(d, stepsRange) { stepsRange = it } }
                    if (HealthDataType.HEART_RATE in readable) item { HeartRateCard(d, onDay = vm::selectHrDay) }
                    if (HealthDataType.SLEEP in readable) item { SleepCard(d) }
                    if (HealthDataType.OXYGEN_SATURATION in readable) item { Spo2Card(d) }
                    if (HealthDataType.EXERCISE in readable) {
                        item { SectionTitle("Entrenamientos (90 días)") }
                        if (d.exercises.isEmpty()) {
                            item { EmptyText("Sin entrenamientos registrados.") }
                        } else {
                            items(d.exercises, key = { it.session.id }) { ExerciseRow(it, onClick = { vm.openExercise(it) }) }
                        }
                    }
                    item { SourcesCard(d) }
                }
            }
            if (available && state.profile != null) {
                item { SectionTitle("Báscula") }
                item {
                    ExportCard(
                        state = state,
                        onToggle = vm::setExportEnabled,
                        onExportHistory = vm::exportHistory,
                        onRequestPermissions = { permissionLauncher.launch(vm.requestedPermissions()) },
                    )
                }
            }
            item { MiFitnessHelpCard() }
        }
    }

    detail?.let { item ->
        val age = state.profile?.ageAt(System.currentTimeMillis())
        ExerciseDetailDialog(item, age, onDismiss = vm::closeExercise)
    }
}

// ---- Connection / onboarding --------------------------------------------------------------

@Composable
private fun ConnectionCard(
    state: HealthUiState,
    onConnect: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenPlayStore: () -> Unit,
) {
    OutlinedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Health Connect", style = MaterialTheme.typography.titleMedium)
            when (state.availability) {
                HcAvailability.NOT_SUPPORTED -> BodyText(
                    "Health Connect no está disponible en este dispositivo (necesita Android 9 o superior).",
                )
                HcAvailability.NOT_INSTALLED -> {
                    BodyText("Instala Health Connect desde Google Play para traer los datos de la pulsera y guardar los de la báscula.")
                    Button(onClick = onOpenPlayStore) { Text("Instalar Health Connect") }
                }
                HcAvailability.UPDATE_REQUIRED -> {
                    BodyText("Tu versión de Health Connect es antigua. Actualízala desde Google Play para continuar.")
                    Button(onClick = onOpenPlayStore) { Text("Actualizar Health Connect") }
                }
                HcAvailability.AVAILABLE -> when {
                    state.granted.isEmpty() -> {
                        BodyText(
                            "Conecta Health Connect para ver aquí los pasos, el pulso, el sueño, el oxígeno en sangre y los " +
                                "entrenamientos de tu pulsera (Mi Fitness u otra app), y para guardar en Health Connect las " +
                                "mediciones de la báscula. Los datos se quedan en el teléfono.",
                        )
                        Button(onClick = onConnect) { Text("Conectar Health Connect") }
                    }
                    else -> {
                        StatusLine(
                            if (state.allDataGranted) "Conectado" else "Conectado con permisos parciales",
                            good = state.allDataGranted,
                        )
                        val missing = HealthPermissions.missingWearableTypes(state.granted)
                        if (missing.isNotEmpty()) {
                            BodyText("Sin permiso de lectura: " + missing.joinToString(", ") { it.label.lowercase(Fmt.LOCALE) } + ".")
                        }
                        if (!state.canExport) BodyText("Sin permiso para escribir el peso: las mediciones de la báscula no se envían.")
                        if (state.historyAvailable && !HealthPermissions.historyGranted(state.granted)) {
                            BodyText("Permite «Acceder a datos anteriores» para importar hasta 1 año (ahora: últimos 30 días).")
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            if (!state.allGranted) FilledTonalButton(onClick = onConnect) { Text("Revisar permisos") }
                            OutlinedButton(onClick = onOpenSettings) { Text("Abrir Health Connect") }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun StatusLine(text: String, good: Boolean) {
    val colors = ChartTheme.colors
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Dot(if (good) colors.good else colors.warning, size = 10.dp)
        Text(text, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun SyncCard(state: HealthUiState, onSync: () -> Unit) {
    val sync = state.sync
    OutlinedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Sincronización", style = MaterialTheme.typography.titleSmall)
                    Text(
                        when {
                            sync.running -> "Sincronizando…"
                            sync.lastSyncAt != null -> "Última: ${Fmt.relative(sync.lastSyncAt)}"
                            else -> "Todavía no se ha sincronizado"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                FilledTonalButton(onClick = onSync, enabled = !sync.running) {
                    Icon(Icons.Filled.Refresh, contentDescription = null)
                    Spacer(Modifier.width(6.dp))
                    Text("Sincronizar ahora")
                }
            }
            if (sync.running) LinearProgressIndicator(Modifier.fillMaxWidth())
            sync.error?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
        }
    }
}

@Composable
private fun NoDataCard() {
    OutlinedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Aún no hay datos de la pulsera", style = MaterialTheme.typography.titleSmall)
            BodyText(
                "Health Connect no tiene datos de actividad, pulso o sueño. Comprueba que Mi Fitness escribe en " +
                    "Health Connect (instrucciones al final) y sincroniza la pulsera en Mi Fitness.",
            )
        }
    }
}

@Composable
private fun MiFitnessHelpCard() {
    OutlinedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Conectar la Xiaomi Smart Band con Mi Fitness", style = MaterialTheme.typography.titleSmall)
            BodyText("1. En Mi Fitness: Perfil → Datos de terceros (o «Aplicaciones de terceros») → Health Connect, y activa la sincronización.")
            BodyText("2. En Health Connect → Permisos de apps → Mi Fitness: permite que escriba pasos, pulso, sueño, oxígeno en sangre y ejercicio.")
            BodyText("3. Sincroniza la pulsera en Mi Fitness y pulsa aquí «Sincronizar ahora».")
            BodyText("Sirve igual cualquier otra app de pulsera o reloj que escriba en Health Connect.")
        }
    }
}

// ---- Today ----------------------------------------------------------------------------------

@Composable
private fun TodayGrid(d: HealthDashboard) {
    val t = d.todaySummary
    val night = d.lastNight
    val quality = d.lastNightQuality
    val restingAvg = d.restingTrend.current
    val tiles = listOf<@Composable (Modifier) -> Unit>(
        { m ->
            Tile(
                "Pasos",
                t.steps?.let { HealthFormat.count(it) } ?: "—",
                d.stepsAvg7?.let { "Media 7 días: ${HealthFormat.count(it.average.roundToInt().toLong())}" },
                m,
            )
        },
        { m ->
            Tile(
                "Calorías activas",
                t.activeKcal?.let { HealthFormat.kcal(it) } ?: "—",
                t.totalKcal?.let { "Totales: ${HealthFormat.kcal(it)}" },
                m,
            )
        },
        { m -> Tile("Distancia", t.distanceM?.let { HealthFormat.km(it) } ?: "—", null, m) },
        { m ->
            Tile(
                "Pulso en reposo",
                t.restingHr?.let { HealthFormat.bpm(it) } ?: "—",
                restingAvg?.let { avg ->
                    val change = d.restingTrend.change?.let { " (${Fmt.signed(it, 0)} vs. semana anterior)" }.orEmpty()
                    "Media 7 días: ${HealthFormat.bpm(avg.average.roundToInt())}$change"
                },
                m,
            )
        },
        { m ->
            Tile(
                "Sueño de anoche",
                night?.let { HealthFormat.minutes(it.asleepMinutes) } ?: "—",
                d.sleepAvg7?.let { "Media 7 días: ${HealthFormat.minutes(it.average.roundToInt())}" },
                m,
                chip = quality?.let { Classification("${it.label} · ${it.score}/100", it.status) },
            )
        },
        { m ->
            Tile(
                "Oxígeno en sangre",
                t.spo2Avg?.let { HealthFormat.percent(it) } ?: "—",
                t.spo2Min?.let { "Mínimo: ${HealthFormat.percent(it)}" },
                m,
            )
        },
    )
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        tiles.chunked(2).forEach { row ->
            Row(Modifier.height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                row.forEach { tile -> tile(Modifier.weight(1f).fillMaxHeight()) }
            }
        }
    }
}

@Composable
private fun Tile(label: String, value: String, sub: String?, modifier: Modifier, chip: Classification? = null) {
    OutlinedCard(modifier) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(value, style = MaterialTheme.typography.titleLarge.merge(TabularNumbers), fontWeight = FontWeight.SemiBold)
            chip?.let { StatusChip(it) }
            sub?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
    }
}

// ---- Steps ----------------------------------------------------------------------------------

@Composable
private fun StepsCard(d: HealthDashboard, range: DayRange, onRange: (DayRange) -> Unit) {
    val series = HealthStats.filledSeries(d.daily.mapNotNull { (date, a) -> a.steps?.let { date to it } }.toMap(), d.today, range.days)
    val withData = series.mapNotNull { it.second }
    ChartCard("Pasos diarios") {
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            DayRange.entries.forEachIndexed { i, r ->
                SegmentedButton(
                    selected = r == range,
                    onClick = { onRange(r) },
                    shape = SegmentedButtonDefaults.itemShape(i, DayRange.entries.size),
                    icon = {},
                    label = { Text(r.label) },
                )
            }
        }
        Spacer(Modifier.height(12.dp))
        if (withData.isEmpty()) {
            EmptyText("Sin pasos en este periodo.")
        } else {
            StepsBarChart(series, HealthViewModel.STEPS_GOAL)
            val reached = withData.count { it >= HealthViewModel.STEPS_GOAL }
            SmallText(
                "Media: ${HealthFormat.count(withData.average().roundToInt().toLong())} pasos/día · " +
                    "objetivo de ${HealthFormat.count(HealthViewModel.STEPS_GOAL)} cumplido $reached de ${withData.size} días" +
                    (d.stepsAvg30?.let { " · media 30 días: ${HealthFormat.count(it.average.roundToInt().toLong())}" } ?: ""),
            )
        }
    }
}

// ---- Heart rate -----------------------------------------------------------------------------

@Composable
private fun HeartRateCard(d: HealthDashboard, onDay: (LocalDate) -> Unit) {
    ChartCard("Pulso") {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { onDay(d.hrDay.minusDays(1)) }) {
                Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, contentDescription = "Día anterior")
            }
            Text(
                HealthFormat.relativeDay(d.hrDay, d.today),
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.weight(1f),
            )
            IconButton(onClick = { onDay(d.hrDay.plusDays(1)) }, enabled = d.hrDay < d.today) {
                Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = "Día siguiente")
            }
        }
        val (start, end) = HealthStats.dayBounds(d.hrDay, ZoneId.systemDefault())
        if (d.hrDaySamples.isEmpty()) {
            EmptyText("Sin datos de pulso este día.")
        } else {
            HeartRateDayChart(d.hrDaySamples, d.hrDayResting, start, end)
            val stats = HeartRateZones.stats(d.hrDaySamples, start, end - 1)
            if (stats != null) {
                SmallText(
                    "Media ${HealthFormat.bpm(stats.avg)} · mín ${stats.min} · máx ${stats.max}" +
                        (d.hrDayResting?.let { " · en reposo $it lpm" } ?: ""),
                )
            }
        }
        Spacer(Modifier.height(16.dp))
        Text("Últimos 30 días", style = MaterialTheme.typography.titleSmall)
        Spacer(Modifier.height(8.dp))
        if (d.hrRanges.isEmpty()) EmptyText("Sin datos de pulso.") else HeartRateRangeChart(d.hrDays, d.hrRanges)
    }
}

// ---- Sleep ----------------------------------------------------------------------------------

@Composable
private fun SleepCard(d: HealthDashboard) {
    ChartCard("Sueño") {
        val night = d.lastNight
        if (night == null) {
            EmptyText("Sin datos de sueño de anoche.")
        } else {
            Text(
                "Anoche · ${Fmt.time(night.start)}–${Fmt.time(night.end)}",
                style = MaterialTheme.typography.titleSmall,
            )
            Spacer(Modifier.height(4.dp))
            d.lastNightQuality?.let { q ->
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    StatusChip(Classification("${q.label} · ${q.score}/100", q.status))
                    Text(
                        "Dormido ${HealthFormat.minutes(night.asleepMinutes)}",
                        style = MaterialTheme.typography.bodyMedium.merge(TabularNumbers),
                    )
                }
                val restorative = q.restorativeShare?.let { "profundo + REM ${Fmt.num(it * 100, 0)} % (meta ≥ 40 %)" }
                SmallText(
                    "Calidad = duración (objetivo 7–9 h, hasta 60 puntos) + sueño reparador (profundo + REM, hasta 40). " +
                        (restorative?.replaceFirstChar { it.uppercase(Fmt.LOCALE) }?.plus(".") ?: "Sin fases: solo cuenta la duración."),
                )
            }
            Spacer(Modifier.height(8.dp))
            if (night.hasStages || night.awakeMinutes > 0) Hypnogram(night)
            val parts = SleepStage.entries.filter { night.minutesOf(it) > 0 }
                .joinToString(" · ") { "${it.label} ${HealthFormat.minutes(night.minutesOf(it))}" }
            SmallText(parts + (night.efficiencyPercent?.let { " · eficiencia ${Fmt.num(it, 0)} %" } ?: ""))
        }
        Spacer(Modifier.height(16.dp))
        Text("Últimas $SLEEP_CHART_NIGHTS noches", style = MaterialTheme.typography.titleSmall)
        Spacer(Modifier.height(8.dp))
        val nights = HealthStats.filledSeries(d.nights, d.today, SLEEP_CHART_NIGHTS)
        if (nights.all { it.second == null }) {
            EmptyText("Sin datos de sueño.")
        } else {
            SleepStackedChart(nights)
            d.sleepAvg7?.let { SmallText("Media 7 noches: ${HealthFormat.minutes(it.average.roundToInt())} dormido") }
        }
    }
}

// ---- SpO2 -----------------------------------------------------------------------------------

@Composable
private fun Spo2Card(d: HealthDashboard) {
    ChartCard("Oxígeno en sangre (14 días)") {
        if (d.spo2.isEmpty()) {
            EmptyText("Sin lecturas de SpO₂.")
        } else {
            Spo2Chart(d.spo2, d.spo2Start, d.spo2End)
            val values = d.spo2.map { it.value }
            SmallText("Media ${HealthFormat.percent(values.average())} · mínimo ${HealthFormat.percent(values.min())} · ${values.size} lecturas")
        }
    }
}

// ---- Exercise -------------------------------------------------------------------------------

@Composable
private fun ExerciseRow(item: ExerciseItem, onClick: () -> Unit) {
    val e = item.session
    OutlinedCard(Modifier.fillMaxWidth().clickable(onClickLabel = "Ver detalles", onClick = onClick)) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(item.name, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                Text(
                    HealthFormat.minutes(((e.end - e.start) / 60_000L).toInt()),
                    style = MaterialTheme.typography.titleSmall.merge(TabularNumbers),
                )
            }
            val parts = buildList {
                add(Fmt.relative(e.start))
                e.activeKcal?.let { add(HealthFormat.kcal(it)) }
                e.distanceM?.takeIf { it > 0 }?.let { add(HealthFormat.km(it)) }
                item.heartRate?.let { add("${it.avg} lpm de media") }
            }
            SmallText(parts.joinToString(" · "))
            SmallText("Fuente: ${item.source}")
        }
    }
}

@Composable
private fun ExerciseDetailDialog(item: ExerciseItem, age: Int?, onDismiss: () -> Unit) {
    val e = item.session
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(item.name) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                DetailRow("Inicio", Fmt.dateTime(e.start))
                DetailRow("Fin", Fmt.dateTime(e.end))
                DetailRow("Duración", HealthFormat.minutes(((e.end - e.start) / 60_000L).toInt()))
                e.title?.takeIf { it.isNotBlank() && it != item.name }?.let { DetailRow("Título", it) }
                DetailRow("Tipo", HealthMappings.exerciseName(e.type))
                DetailRow("Calorías activas", e.activeKcal?.let { HealthFormat.kcal(it) } ?: "—")
                DetailRow("Distancia", e.distanceM?.takeIf { it > 0 }?.let { HealthFormat.km(it) } ?: "—")
                DetailRow("Pulso medio", item.heartRate?.let { HealthFormat.bpm(it.avg) } ?: "—")
                DetailRow("Pulso máximo", item.heartRate?.let { HealthFormat.bpm(it.max) } ?: "—")
                DetailRow("Fuente", item.source)
                if (age != null && item.zoneMs.any { it > 0 }) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "Zonas de pulso (máx. estimado ${HeartRateZones.maxHeartRate(age)} lpm = 220 − $age)",
                        style = MaterialTheme.typography.titleSmall,
                    )
                    val zones = HeartRateZones.zones(age)
                    val total = item.zoneMs.sum().coerceAtLeast(1L)
                    item.zoneMs.forEachIndexed { index, ms ->
                        val label = if (index == 0) {
                            "${HeartRateZones.REST_LABEL} (< ${zones.first().minBpm})"
                        } else {
                            val z = zones[index - 1]
                            "Z$index ${z.label} (${z.minBpm}${z.maxBpm?.let { "–${it - 1}" } ?: "+"})"
                        }
                        DetailRow(label, "${HealthFormat.minutes((ms / 60_000L).toInt())} · ${Fmt.num(ms * 100.0 / total, 0)} %")
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Cerrar") } },
    )
}

@Composable
private fun DetailRow(label: String, value: String) {
    Row {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
        Text(value, style = MaterialTheme.typography.bodyMedium.merge(TabularNumbers))
    }
}

// ---- Sources and export -------------------------------------------------------------------

@Composable
private fun SourcesCard(d: HealthDashboard) {
    if (d.sources.isEmpty() && d.externalWeight == null) return
    OutlinedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("Fuentes", style = MaterialTheme.typography.titleSmall)
            if (d.sources.isNotEmpty()) BodyText(d.sources.joinToString(", "))
            d.externalWeight?.let { w ->
                BodyText("Último peso de otra app: ${Fmt.kg(w.value, 1)} (${d.externalWeightSource}, ${Fmt.dayMonth(w.time)})")
            }
            SmallText("Los totales diarios los calcula Health Connect sin duplicar datos de varias apps.")
        }
    }
}

@Composable
private fun ExportCard(state: HealthUiState, onToggle: (Boolean) -> Unit, onExportHistory: () -> Unit, onRequestPermissions: () -> Unit) {
    val export = state.export
    OutlinedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "Enviar mediciones de la báscula a Health Connect",
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.weight(1f),
                )
                Switch(checked = state.exportEnabled, onCheckedChange = onToggle, enabled = state.canExport)
            }
            BodyText(
                "Cada medición nueva de ${state.profile?.name ?: "tu perfil"} se guarda como peso, grasa corporal, masa ósea, " +
                    "masa magra, metabolismo basal y agua corporal (los valores que tenga). Si borras o desasignas una " +
                    "medición, también se borra de Health Connect.",
            )
            if (!state.canExport) {
                BodyText("Falta el permiso para escribir el peso en Health Connect.")
                FilledTonalButton(onClick = onRequestPermissions) { Text("Conceder permiso") }
            } else {
                SmallText(
                    when (export.exportedCount) {
                        0 -> "Ninguna medición en Health Connect todavía."
                        1 -> "1 medición en Health Connect."
                        else -> "${export.exportedCount} mediciones en Health Connect."
                    },
                )
                OutlinedButton(onClick = onExportHistory, enabled = !export.running) { Text("Exportar historial a Health Connect") }
                if (export.running) LinearProgressIndicator(Modifier.fillMaxWidth())
            }
            export.message?.let { SmallText(it) }
            export.error?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
        }
    }
}

// ---- Small building blocks ----------------------------------------------------------------

@Composable
private fun ChartCard(title: String, content: @Composable () -> Unit) {
    OutlinedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(8.dp))
            content()
        }
    }
}

@Composable
private fun BodyText(text: String) {
    Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun SmallText(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodySmall.merge(TabularNumbers),
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 4.dp),
    )
}

@Composable
private fun EmptyText(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.fillMaxWidth().padding(vertical = 24.dp),
    )
}
