@file:OptIn(ExperimentalMaterial3Api::class)

package com.enjoyyourtime.globalfitness.ui.band

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import android.text.format.DateFormat
import com.enjoyyourtime.globalfitness.R
import com.enjoyyourtime.globalfitness.band.xiaomi.AlarmRepeat
import com.enjoyyourtime.globalfitness.band.xiaomi.AlarmSpec
import com.enjoyyourtime.globalfitness.band.xiaomi.BandAlarm
import com.enjoyyourtime.globalfitness.ui.Fmt
import com.enjoyyourtime.globalfitness.ui.theme.TabularNumbers
import java.time.DayOfWeek
import java.time.LocalTime
import java.time.format.TextStyle

/** Monday = 1 ... Sunday = 64, as the band stores the weekly flags. */
private val DAYS = DayOfWeek.entries.mapIndexed { i, day -> day to (1 shl i) }

private fun dayLabel(day: DayOfWeek): String = day.getDisplayName(TextStyle.SHORT, Fmt.locale)

@Composable
fun repeatLabel(repeat: AlarmRepeat): String = when (repeat) {
    AlarmRepeat.Once -> stringResource(R.string.alarm_once)
    AlarmRepeat.Daily -> stringResource(R.string.alarm_daily)
    is AlarmRepeat.Weekly -> when (repeat.days) {
        31 -> stringResource(R.string.alarm_weekdays)
        96 -> stringResource(R.string.alarm_weekends)
        else -> DAYS.filter { repeat.days and it.second != 0 }.joinToString(" ") { dayLabel(it.first) }
    }
    is AlarmRepeat.Other -> stringResource(R.string.alarm_band_repeat)
}

private fun daysOf(repeat: AlarmRepeat): Int = when (repeat) {
    AlarmRepeat.Daily -> AlarmRepeat.ALL_DAYS
    is AlarmRepeat.Weekly -> repeat.days
    else -> 0
}

private fun timeLabel(hour: Int, minute: Int) = Fmt.time(LocalTime.of(hour, minute))

@Composable
fun AlarmsCard(
    alarms: List<BandAlarm>?,
    maxAlarms: Int?,
    busy: Boolean,
    onRefresh: () -> Unit,
    onAdd: () -> Unit,
    onEdit: (BandAlarm) -> Unit,
    onToggle: (BandAlarm, Boolean) -> Unit,
    onDelete: (BandAlarm) -> Unit,
    emptyText: String = stringResource(R.string.alarm_none),
    footerText: String = stringResource(R.string.alarm_sync_text),
    /** False for devices that cannot report their alarms (nothing to read again). */
    canRefresh: Boolean = true,
) {
    OutlinedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                val count = alarms?.size
                Text(
                    when {
                        count == null -> stringResource(R.string.alarm_saved_on_band)
                        maxAlarms != null -> pluralStringResource(R.plurals.alarm_count_of_max, maxAlarms, count, maxAlarms)
                        else -> pluralStringResource(R.plurals.alarm_count, count, count)
                    },
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.weight(1f),
                )
                if (busy) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
            }
            alarms?.forEachIndexed { index, alarm ->
                if (index > 0) HorizontalDivider()
                AlarmRow(alarm, onEdit = { onEdit(alarm) }, onToggle = { onToggle(alarm, it) }, onDelete = { onDelete(alarm) })
            }
            if (alarms != null && alarms.isEmpty()) {
                Text(emptyText, style = MaterialTheme.typography.bodySmall)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                val full = maxAlarms != null && alarms != null && alarms.size >= maxAlarms
                FilledTonalButton(onClick = onAdd, enabled = !busy && !full) { Text(stringResource(R.string.alarm_new)) }
                if (canRefresh) TextButton(onClick = onRefresh, enabled = !busy) { Text(stringResource(R.string.alarm_reload)) }
            }
            Text(
                footerText,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun AlarmRow(alarm: BandAlarm, onEdit: () -> Unit, onToggle: (Boolean) -> Unit, onDelete: () -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(
                timeLabel(alarm.spec.hour, alarm.spec.minute),
                style = MaterialTheme.typography.headlineSmall.merge(TabularNumbers),
                color = if (alarm.spec.enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
            )
            val repeat = repeatLabel(alarm.spec.repeat)
            Text(if (alarm.spec.smart) stringResource(R.string.alarm_repeat_smart, repeat) else repeat, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = alarm.spec.enabled, onCheckedChange = onToggle)
        val time = timeLabel(alarm.spec.hour, alarm.spec.minute)
        IconButton(onClick = onEdit) { Icon(Icons.Filled.Edit, contentDescription = stringResource(R.string.alarm_edit_at, time)) }
        IconButton(onClick = onDelete) { Icon(Icons.Filled.Delete, contentDescription = stringResource(R.string.alarm_delete_at, time)) }
    }
}

@Composable
fun AlarmEditorDialog(
    initial: AlarmSpec?,
    isNew: Boolean,
    onSave: (AlarmSpec) -> Unit,
    onDismiss: () -> Unit,
    /** Shows the smart wake-up option (only devices that implement it). */
    allowSmart: Boolean = true,
) {
    val start = initial ?: AlarmSpec(hour = 7, minute = 0)
    val time = rememberTimePickerState(
        initialHour = start.hour,
        initialMinute = start.minute,
        is24Hour = DateFormat.is24HourFormat(LocalContext.current),
    )
    var days by remember { mutableIntStateOf(daysOf(start.repeat)) }
    var smart by remember { mutableStateOf(start.smart) }
    var daysEdited by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(if (isNew) R.string.alarm_new else R.string.alarm_edit)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                TimePicker(state = time)
                Text(stringResource(R.string.alarm_repeat), style = MaterialTheme.typography.titleSmall)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    DAYS.forEach { (day, flag) ->
                        FilterChip(
                            selected = days and flag != 0,
                            onClick = {
                                days = days xor flag
                                daysEdited = true
                            },
                            label = { Text(dayLabel(day)) },
                        )
                    }
                }
                val repeat = if (!daysEdited && start.repeat is AlarmRepeat.Other) start.repeat else AlarmRepeat.fromDays(days)
                Text(repeatLabel(repeat), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (allowSmart) Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(stringResource(R.string.alarm_smart), style = MaterialTheme.typography.bodyMedium)
                        Text(
                            stringResource(R.string.alarm_smart_text),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Spacer(Modifier.width(8.dp))
                    Switch(checked = smart, onCheckedChange = { smart = it })
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val repeat = if (!daysEdited && start.repeat is AlarmRepeat.Other) start.repeat else AlarmRepeat.fromDays(days)
                // Saving from the editor (re)activates the alarm, as clock apps do.
                onSave(start.copy(hour = time.hour, minute = time.minute, smart = smart && allowSmart, repeat = repeat, enabled = true))
            }) { Text(stringResource(R.string.action_save)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}
