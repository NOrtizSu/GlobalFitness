package com.enjoyyourtime.globalfitness.ui.profile

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.enjoyyourtime.globalfitness.R
import com.enjoyyourtime.globalfitness.data.Profile
import com.enjoyyourtime.globalfitness.data.ProfileDraft
import com.enjoyyourtime.globalfitness.data.Sex
import com.enjoyyourtime.globalfitness.ui.Fmt
import com.enjoyyourtime.globalfitness.ui.labelRes
import com.enjoyyourtime.globalfitness.ui.components.DatePickerModal
import java.time.LocalDate
import java.time.Period

/** Profile form, shared by the onboarding and the edit dialog. */
@Composable
fun ProfileForm(
    initial: Profile?,
    submitLabel: String,
    onSubmit: (ProfileDraft) -> Unit,
    onCancel: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    var name by rememberSaveable { mutableStateOf(initial?.name.orEmpty()) }
    var sex by rememberSaveable { mutableStateOf(initial?.sex ?: Sex.MALE) }
    var birthEpochDay by rememberSaveable { mutableStateOf(initial?.birthDate?.toEpochDay()) }
    var heightText by rememberSaveable { mutableStateOf(initial?.heightCm?.let { Fmt.num(it, if (it % 1.0 == 0.0) 0 else 1) }.orEmpty()) }
    var targetText by rememberSaveable { mutableStateOf(initial?.targetWeightKg?.let { Fmt.num(it, 1) }.orEmpty()) }
    var showDatePicker by rememberSaveable { mutableStateOf(false) }
    var attempted by rememberSaveable { mutableStateOf(false) }

    val birthDate = birthEpochDay?.let { LocalDate.ofEpochDay(it) }
    val height = Fmt.parseDecimal(heightText)
    val target = Fmt.parseDecimal(targetText)
    val nameOk = name.isNotBlank()
    val heightOk = height != null && height in 80.0..250.0
    val targetOk = targetText.isBlank() || (target != null && target in 20.0..300.0)
    val birthOk = birthDate != null && Period.between(birthDate, LocalDate.now()).years in 5..120
    val draft = if (birthDate != null && height != null && nameOk && heightOk && targetOk && birthOk) {
        ProfileDraft(
            name = name.trim(),
            sex = sex,
            birthDate = birthDate,
            heightCm = height,
            targetWeightKg = if (targetText.isBlank()) null else target,
        )
    } else {
        null
    }

    Column(modifier, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        OutlinedTextField(
            value = name,
            onValueChange = { name = it.take(30) },
            label = { Text(stringResource(R.string.profile_name)) },
            singleLine = true,
            isError = attempted && !nameOk,
            supportingText = { if (attempted && !nameOk) Text(stringResource(R.string.profile_name_error)) },
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words, imeAction = ImeAction.Next),
            modifier = Modifier.fillMaxWidth(),
        )
        Text(stringResource(R.string.profile_sex), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            Sex.entries.forEachIndexed { i, s ->
                SegmentedButton(
                    selected = sex == s,
                    onClick = { sex = s },
                    shape = SegmentedButtonDefaults.itemShape(i, Sex.entries.size),
                    label = { Text(stringResource(s.labelRes)) },
                )
            }
        }
        Text(stringResource(R.string.profile_birth_date), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        OutlinedButton(onClick = { showDatePicker = true }, modifier = Modifier.fillMaxWidth()) {
            Icon(Icons.Filled.DateRange, contentDescription = null)
            Spacer(Modifier.width(8.dp))
            Text(
                birthDate?.let {
                    val years = Period.between(it, LocalDate.now()).years
                    stringResource(R.string.profile_birth_date_age, Fmt.date(it), pluralStringResource(R.plurals.value_years, years, Fmt.count(years.toLong())))
                } ?: stringResource(R.string.profile_pick_date),
                modifier = Modifier.weight(1f),
            )
        }
        if (attempted && !birthOk) {
            Text(stringResource(R.string.profile_birth_date_error), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        }
        OutlinedTextField(
            value = heightText,
            onValueChange = { heightText = it.filter { ch -> ch.isDigit() || ch == ',' || ch == '.' || ch == '٫' }.take(5) },
            label = { Text(stringResource(R.string.profile_height)) },
            suffix = { Text(stringResource(R.string.unit_cm)) },
            singleLine = true,
            isError = attempted && !heightOk,
            supportingText = {
                if (attempted && !heightOk) Text(stringResource(R.string.profile_height_error, Fmt.count(80), Fmt.count(250)))
            },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal, imeAction = ImeAction.Next),
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = targetText,
            onValueChange = { targetText = it.filter { ch -> ch.isDigit() || ch == ',' || ch == '.' || ch == '٫' }.take(5) },
            label = { Text(stringResource(R.string.profile_target_weight)) },
            suffix = { Text(stringResource(R.string.unit_kg)) },
            singleLine = true,
            isError = !targetOk,
            supportingText = {
                if (!targetOk) Text(stringResource(R.string.profile_target_weight_error, Fmt.count(20), Fmt.count(300)))
            },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal, imeAction = ImeAction.Done),
            modifier = Modifier.fillMaxWidth(),
        )
        val submit = {
            attempted = true
            draft?.let(onSubmit)
        }
        if (onCancel == null) {
            Button(onClick = { submit() }, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)) { Text(submitLabel) }
        } else {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)) {
                TextButton(onClick = onCancel) { Text(stringResource(R.string.action_cancel)) }
                Button(onClick = { submit() }) { Text(submitLabel) }
            }
        }
    }

    if (showDatePicker) {
        DatePickerModal(
            initial = birthDate ?: LocalDate.of(1990, 1, 1),
            onPick = { birthEpochDay = it.toEpochDay() },
            onDismiss = { showDatePicker = false },
        )
    }
}

@Composable
fun ProfileDialog(initial: Profile?, onSubmit: (ProfileDraft) -> Unit, onDismiss: () -> Unit) {
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(
            shape = MaterialTheme.shapes.extraLarge,
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            modifier = Modifier
                .padding(16.dp)
                .widthIn(max = 520.dp)
                .imePadding(),
        ) {
            Column(
                Modifier
                    .verticalScroll(rememberScrollState())
                    .padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Text(
                    stringResource(if (initial == null) R.string.profile_new else R.string.profile_edit),
                    style = MaterialTheme.typography.headlineSmall,
                )
                ProfileForm(
                    initial = initial,
                    submitLabel = stringResource(R.string.action_save),
                    onSubmit = onSubmit,
                    onCancel = onDismiss,
                )
            }
        }
    }
}
