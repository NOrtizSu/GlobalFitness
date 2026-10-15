import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
-----
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
=====
import com.enjoyyourtime.globalfitness.account.AccountUser
-----
import com.enjoyyourtime.globalfitness.R
import com.enjoyyourtime.globalfitness.account.AccountUser
=====
import com.enjoyyourtime.globalfitness.ui.MainUiState
import com.enjoyyourtime.globalfitness.ui.body.NO_COMPOSITION_HINT
-----
import com.enjoyyourtime.globalfitness.ui.MainUiState
import com.enjoyyourtime.globalfitness.ui.label
=====
import com.enjoyyourtime.globalfitness.ui.devices.bandStatusText
-----
import com.enjoyyourtime.globalfitness.ui.devices.bandStatus
=====
    /** A nearby scale waiting for "¿Es tu báscula?" (same as in Dispositivos). */
-----
    /** A nearby scale waiting for "Is this your scale?" (same as in Devices). */
=====
 * "Hoy": greeting with today's rings
-----
 * Today: greeting with today's rings
=====
    TabPage(title = "Hoy") {
-----
    TabPage(title = stringResource(R.string.nav_today)) {
=====
                    onClickLabel = "Abrir cuenta",
-----
                    onClickLabel = stringResource(R.string.today_open_account),
=====
                            Text("Copia en la nube en pausa", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onErrorContainer)
                            Text(
                                "Necesitamos que elijas qué datos conservar. Toca para decidir.",
-----
                            Text(stringResource(R.string.today_cloud_paused_title), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onErrorContainer)
                            Text(
                                stringResource(R.string.today_cloud_paused_text),
=====
                    SectionTitle("Composición corporal")
                    if (latest != null && latest != entries.last()) {
                        Text(
                            "Última medición con composición: ${Fmt.relative(latest.measurement.timestamp, now).lowercase(Fmt.LOCALE)}",
-----
                    SectionTitle(stringResource(R.string.body_composition))
                    if (latest != null && latest != entries.last()) {
                        Text(
                            stringResource(R.string.today_last_composition, Fmt.relative(latest.measurement.timestamp, now)),
=====
                    Text(
                        NO_COMPOSITION_HINT,
-----
                    Text(
                        stringResource(R.string.body_no_composition_hint),
=====
            Text(
                "Estimaciones de la báscula o calculadas con fórmulas publicadas. No son un diagnóstico médico.",
-----
            Text(
                stringResource(R.string.common_disclaimer),
=====
                    name?.let { "Hola, $it" } ?: "Hola",
-----
                    name?.let { stringResource(R.string.today_greeting_name, it) } ?: stringResource(R.string.today_greeting),
=====
                Text(longDate(LocalDate.now()), style
-----
                Text(Fmt.longDate(LocalDate.now()), style
=====
                    text = "Conecta Health Connect para ver aquí tus pasos, tu sueño y tu pulso en reposo.",
                    action = "Conectar Health Connect",
-----
                    text = stringResource(R.string.today_hc_connect_text),
                    action = stringResource(R.string.action_connect_health_connect),
=====
                        text = "Con Health Connect verás aquí tus pasos, tu sueño y tu pulso en reposo de tu pulsera o reloj.",
                        action = if (health.availability == HcAvailability.NOT_INSTALLED) "Instalar Health Connect" else "Actualizar Health Connect",
-----
                        text = stringResource(R.string.today_hc_install_text),
                        action = stringResource(
                            if (health.availability == HcAvailability.NOT_INSTALLED) R.string.action_install_health_connect else R.string.action_update_health_connect,
                        ),
=====
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
-----
        RingColumn(
            label = stringResource(R.string.today_steps),
            caption = stringResource(R.string.today_goal_of, HealthFormat.count(HealthViewModel.STEPS_GOAL)),
            ring = {
                ProgressRing(
                    progress = steps?.let { it.toFloat() / HealthViewModel.STEPS_GOAL },
                    value = steps?.let { HealthFormat.count(it) } ?: "—",
                    unit = "",
                    color = scheme.primary,
                    trackColor = track,
                    description = steps?.let {
                        stringResource(R.string.today_steps_desc, HealthFormat.count(it), HealthFormat.count(HealthViewModel.STEPS_GOAL))
                    } ?: stringResource(R.string.today_steps_desc_none),
                )
            },
        )
        RingColumn(
            label = stringResource(R.string.today_sleep),
            caption = stringResource(R.string.today_sleep_goal),
            ring = {
                ProgressRing(
                    progress = sleep?.let { it.toFloat() / SLEEP_GOAL_MINUTES },
                    value = sleep?.let { HealthFormat.hoursMinutes(it) } ?: "—",
                    unit = if (sleep != null) stringResource(R.string.unit_hours_short) else "",
                    color = scheme.tertiary,
                    trackColor = track,
                    description = sleep?.let { stringResource(R.string.today_sleep_desc, HealthFormat.minutes(it)) }
                        ?: stringResource(R.string.today_sleep_desc_none),
                )
            },
        )
        RingColumn(
            label = stringResource(R.string.today_resting_hr),
            caption = stringResource(R.string.unit_bpm),
            ring = {
                // Scale: a full ring is 100 bpm, the upper end of the normal resting range.
                ProgressRing(
                    progress = resting?.let { it / 100f },
                    value = resting?.let { Fmt.count(it.toLong()) } ?: "—",
                    unit = "",
                    color = scheme.secondary,
                    trackColor = track,
                    description = resting?.let { pluralStringResource(R.plurals.today_resting_hr_desc, it, it) }
                        ?: stringResource(R.string.today_resting_hr_desc_none),
                )
            },
        )
    }
    if (d != null && !d.hasData) {
        Text(
            stringResource(R.string.today_hc_no_data),
=====
    ) { Text("Ver actividad") }
-----
    ) { Text(stringResource(R.string.today_view_activity)) }
=====
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
-----
        val scale = when (status.scanStatus) {
            is ScanStatus.Scanning -> R.string.chip_scale_listening
            ScanStatus.Starting -> R.string.chip_scale_starting
            ScanStatus.MissingPermission -> R.string.chip_scale_permission
            ScanStatus.BluetoothOff -> R.string.chip_bluetooth_off
            ScanStatus.NoBluetoothLe -> R.string.chip_no_ble
            ScanStatus.Stopped -> R.string.chip_scale_paused
            is ScanStatus.Failed -> R.string.chip_scale_error
        }
        StatusAssistChip(stringResource(scale), AppIcons.Bluetooth, onOpenDevices)
        if (status.user != null) {
            val cloud = status.cloud
            val text = when {
                cloud.conflict != null || cloud.deletionPending -> stringResource(R.string.chip_cloud_paused)
                cloud.syncing || cloud.deleting -> stringResource(R.string.chip_cloud_syncing)
                cloud.error != null -> stringResource(R.string.chip_cloud_error)
                cloud.pendingUpload -> stringResource(R.string.chip_cloud_pending)
                cloud.lastSyncAt != null -> stringResource(R.string.chip_cloud_synced, Fmt.relative(cloud.lastSyncAt))
                else -> stringResource(R.string.chip_cloud_connected)
            }
            StatusAssistChip(text, AppIcons.Cloud, onOpenAccount)
        }
        status.band?.let { StatusAssistChip(stringResource(R.string.chip_band, bandStatus(it, withMode = false)), AppIcons.Watch, onOpenBand) }
=====
                Text("Aún no hay mediciones", style = MaterialTheme.typography.titleMedium)
                Text(
                    "Súbete a la báscula descalzo y con la app abierta: la medición aparecerá aquí. " +
                        "Si tu báscula no es compatible, añade el peso a mano.",
-----
                Text(stringResource(R.string.body_no_measurements_title), style = MaterialTheme.typography.titleMedium)
                Text(
                    stringResource(R.string.today_no_measurements_text),
=====
                    Text("Añadir peso manual")
-----
                    Text(stringResource(R.string.action_add_manual_weight))
=====
    AppCard(onClick = onOpen, onClickLabel = "Ver evolución del peso") {
-----
    AppCard(onClick = onOpen, onClickLabel = stringResource(R.string.today_view_weight_trend)) {
=====
                Text("Peso", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
-----
                Text(stringResource(R.string.metric_weight), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
=====
                    Text("kg", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(bottom = 6.dp))
                }
                val (icon, trend) = when {
                    weekly == null -> AppIcons.TrendFlat to "Cambio semanal: faltan datos"
                    abs(weekly) < 0.1 -> AppIcons.TrendFlat to "Estable esta semana (${Fmt.signed(weekly, 2)} kg)"
                    else -> (if (weekly > 0) AppIcons.TrendUp else AppIcons.TrendDown) to "${Fmt.signed(weekly, 2)} kg en 7 días"
                }
-----
                    Text(stringResource(R.string.unit_kg), style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(bottom = 6.dp))
                }
                val (icon, trend) = when {
                    weekly == null -> AppIcons.TrendFlat to stringResource(R.string.today_weekly_change_none)
                    abs(weekly) < 0.1 -> AppIcons.TrendFlat to
                        stringResource(R.string.today_weekly_stable, stringResource(R.string.value_kg, Fmt.signed(weekly, 2)))
                    else -> (if (weekly > 0) AppIcons.TrendUp else AppIcons.TrendDown) to
                        stringResource(R.string.today_weekly_change, stringResource(R.string.value_kg, Fmt.signed(weekly, 2)))
                }
=====
                    Text("30 días", style
-----
                    Text(pluralStringResource(R.plurals.common_days, SPARKLINE_DAYS, SPARKLINE_DAYS), style
=====
    AppCard(modifier, onClick = onClick, onClickLabel = "Ver evolución") {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(metric.label, style
-----
    AppCard(modifier, onClick = onClick, onClickLabel = stringResource(R.string.common_view_trend)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(metric.label(), style
=====

private val WEEKDAYS = arrayOf("lunes", "martes", "miércoles", "jueves", "viernes", "sábado", "domingo")
private val MONTHS = arrayOf(
    "enero", "febrero", "marzo", "abril", "mayo", "junio",
    "julio", "agosto", "septiembre", "octubre", "noviembre", "diciembre",
)

/** "Miércoles, 15 de octubre". */
private fun longDate(d: LocalDate): String =
    "${WEEKDAYS[d.dayOfWeek.value - 1]}, ${d.dayOfMonth} de ${MONTHS[d.monthValue - 1]}".replaceFirstChar { it.uppercase(Fmt.LOCALE) }
-----
