@file:OptIn(ExperimentalMaterial3Api::class)

package com.enjoyyourtime.globalfitness.ui.wearables

import android.content.ClipboardManager
import android.content.Context
import android.view.WindowManager
import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.enjoyyourtime.globalfitness.R
import com.enjoyyourtime.globalfitness.ui.asString
import com.enjoyyourtime.globalfitness.wearable.AuthKeyKind
import com.enjoyyourtime.globalfitness.wearable.WearableDriver
import com.enjoyyourtime.globalfitness.wearable.WearableFamily

/**
 * Guided key setup for one device: what the key is, where it comes from (the vendor app / account,
 * with Gadgetbridge's public guides), what invalidates it, then paste (from the clipboard), check
 * and save. The app never signs in to a vendor account nor fetches the key itself. The window is
 * FLAG_SECURE (no screenshots, recordings or recents thumbnail) while this screen is shown.
 */
@Composable
fun WearableKeyWizardScreen(viewModel: WearableViewModel, driver: WearableDriver, onDone: () -> Unit, onBack: () -> Unit) {
    val input by viewModel.keyInput.collectAsStateWithLifecycle()
    val error by viewModel.keyError.collectAsStateWithLifecycle()
    val uri = LocalUriHandler.current
    val context = LocalContext.current
    val help = driver.keyHelp
    val vendor = driver.vendorApp.orEmpty()
    val userId = driver.authKey == AuthKeyKind.NUMERIC_USER_ID
    var visible by remember { mutableStateOf(false) }
    SecureWindow()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(keyTitleRes(driver.authKey))) },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.action_back)) }
                },
            )
        },
    ) { padding ->
        Column(
            Modifier
                .padding(padding)
                .fillMaxSize()
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(driver.displayName, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)

            Step(stringResource(R.string.wearable_wizard_step_what))
            Paragraph(
                if (userId) stringResource(R.string.wearable_wizard_what_user_id, vendor) else stringResource(R.string.wearable_wizard_what_hex, vendor),
            )

            Step(stringResource(R.string.wearable_wizard_step_where))
            Paragraph(stringResource(R.string.wearable_wizard_where, vendor))
            if (driver.family == WearableFamily.XIAOMI) {
                // Methods Gadgetbridge documents for Mi Fitness; nothing here reads the files for the user.
                Paragraph(stringResource(R.string.band_help_how_text))
                Bullet(stringResource(R.string.band_help_how_logs))
                Bullet(stringResource(R.string.band_help_how_root))
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                help?.serverDocUrl?.let { url ->
                    TextButton(onClick = { runCatching { uri.openUri(url) } }) { Text(stringResource(R.string.band_help_link_server)) }
                }
                help?.deviceDocUrl?.let { url ->
                    TextButton(onClick = { runCatching { uri.openUri(url) } }) { Text(stringResource(R.string.wearable_wizard_link_device, driver.displayName)) }
                }
            }

            Step(stringResource(R.string.wearable_wizard_step_important))
            Bullet(stringResource(R.string.wearable_wizard_unpair, vendor))
            Bullet(stringResource(R.string.wearable_wizard_share))
            Bullet(stringResource(R.string.band_help_important_storage))

            Step(stringResource(R.string.wearable_wizard_step_enter))
            Paragraph(stringResource(if (userId) R.string.wearable_key_paste_user_id else R.string.wearable_key_paste_hex))
            OutlinedTextField(
                value = input,
                onValueChange = viewModel::onKeyInput,
                modifier = Modifier.fillMaxWidth(),
                label = { Text(stringResource(keyTitleRes(driver.authKey))) },
                singleLine = true,
                isError = error != null,
                supportingText = error?.let { { Text(it.asString()) } },
                textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
                visualTransformation = if (visible) VisualTransformation.None else PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(
                    keyboardType = if (userId) KeyboardType.NumberPassword else KeyboardType.Password,
                    autoCorrectEnabled = false,
                    imeAction = ImeAction.Done,
                ),
                keyboardActions = KeyboardActions(onDone = { if (viewModel.saveKey()) onDone() }),
                trailingIcon = {
                    TextButton(onClick = { visible = !visible }) {
                        Text(stringResource(if (visible) R.string.band_log_hide else R.string.band_key_show))
                    }
                },
            )
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                OutlinedButton(onClick = { viewModel.paste(readClipboard(context)) }) { Text(stringResource(R.string.wearable_wizard_paste)) }
                Button(onClick = { if (viewModel.saveKey()) onDone() }, enabled = input.isNotBlank()) { Text(stringResource(R.string.band_key_save)) }
            }
        }
    }
}

/** The clipboard's text, read only when the user taps "Paste" (never kept or logged). */
private fun readClipboard(context: Context): String? = try {
    val clipboard = context.getSystemService(ClipboardManager::class.java)
    clipboard?.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(context)?.toString()
} catch (e: SecurityException) {
    null
}

/** FLAG_SECURE on the window while this composable is in the composition. */
@Composable
private fun SecureWindow() {
    val activity = LocalActivity.current ?: return
    DisposableEffect(activity) {
        activity.window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        onDispose { activity.window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE) }
    }
}

@Composable
private fun Step(text: String) {
    Text(text, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 6.dp).semantics { heading() })
}

@Composable
private fun Paragraph(text: String) {
    Text(text, style = MaterialTheme.typography.bodyMedium)
}

@Composable
private fun Bullet(text: String) {
    Text("•  $text", style = MaterialTheme.typography.bodyMedium)
}
