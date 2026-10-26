package com.enjoyyourtime.globalfitness.ui.training

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.enjoyyourtime.globalfitness.R
import com.enjoyyourtime.globalfitness.ui.Fmt
import com.enjoyyourtime.globalfitness.ui.components.ConfirmDialog
import com.enjoyyourtime.globalfitness.ui.components.NoticeCard
import java.time.YearMonth
import java.time.ZoneId

/** Every workout, newest first, grouped by month. Tap opens it; long-press offers to delete it. */
@Composable
fun HistoryScreen(onBack: () -> Unit, onOpenWorkout: (String) -> Unit) {
    val vm = trainingViewModel()
    val history by vm.history.collectAsStateWithLifecycle()
    val exercises by vm.exercises.collectAsStateWithLifecycle()
    val unit by vm.loadUnit.collectAsStateWithLifecycle()
    var menuFor by remember { mutableStateOf<String?>(null) }
    var deleteId by rememberSaveable { mutableStateOf<String?>(null) }
    val zone = ZoneId.systemDefault()

    TrainingSubPage(title = stringResource(R.string.training_history_title), onBack = onBack) { padding ->
        val list = history
        when {
            list == null -> Box(Modifier.subPageContent(padding), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            list.isEmpty() -> Box(Modifier.subPageContent(padding).padding(16.dp)) {
                NoticeCard(
                    title = stringResource(R.string.training_history_empty_title),
                    text = stringResource(R.string.training_history_empty_text),
                    icon = TrainingIcons.History,
                )
            }
            else -> {
                val months = remember(list) { list.groupBy { YearMonth.from(TrainingSummaries.dayOf(it.startedAt, zone)) }.toList() }
                val actions = stringResource(R.string.training_workout_actions)
                LazyColumn(
                    Modifier.subPageContent(padding),
                    contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 24.dp),
                ) {
                    months.forEach { (month, workouts) ->
                        item(key = "m-$month") {
                            Text(
                                Fmt.monthYear(workouts.first().startedAt) + " · " +
                                    Fmt.plural(R.plurals.training_workouts_count, workouts.size, Fmt.count(workouts.size.toLong())),
                                style = MaterialTheme.typography.titleSmall,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier
                                    .padding(start = 4.dp, top = 16.dp, bottom = 6.dp)
                                    .semantics { heading() },
                            )
                        }
                        itemsIndexed(workouts, key = { _, s -> s.id }) { index, s ->
                            Box {
                                WorkoutRow(
                                    summary = s,
                                    title = workoutTitle(s, exercises),
                                    subtitle = listOfNotNull(
                                        Fmt.dayTime(s.startedAt),
                                        s.durationMs?.let(::durationText) ?: stringResource(R.string.training_in_progress),
                                    ).joinToString(" · "),
                                    unit = unit,
                                    index = index,
                                    count = workouts.size,
                                    onClick = { onOpenWorkout(s.id) },
                                    onLongClick = { menuFor = s.id },
                                    onLongClickLabel = actions,
                                )
                                DropdownMenu(expanded = menuFor == s.id, onDismissRequest = { menuFor = null }) {
                                    DropdownMenuItem(
                                        text = { Text(stringResource(R.string.action_delete)) },
                                        leadingIcon = { Icon(Icons.Filled.Delete, contentDescription = null) },
                                        onClick = {
                                            menuFor = null
                                            deleteId = s.id
                                        },
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    val toDelete = deleteId?.let { id -> history?.firstOrNull { it.id == id } }
    if (toDelete != null) {
        ConfirmDialog(
            title = stringResource(R.string.training_delete_title),
            text = stringResource(R.string.training_delete_text, Fmt.dateTime(toDelete.startedAt)),
            confirmLabel = stringResource(R.string.action_delete),
            onConfirm = {
                vm.deleteWorkout(toDelete.id)
                deleteId = null
            },
            onDismiss = { deleteId = null },
        )
    }
}
