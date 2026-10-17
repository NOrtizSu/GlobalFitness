@file:OptIn(ExperimentalMaterial3Api::class)

package com.enjoyyourtime.globalfitness.ui.band

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.enjoyyourtime.globalfitness.R

private const val GB_SERVER_PAIRING = "https://gadgetbridge.org/basics/pairing/huami-xiaomi-server/"
private const val GB_XIAOMI_DEVICES = "https://gadgetbridge.org/gadgets/wearables/xiaomi/"
private const val CLOUD_TOKENS_EXTRACTOR = "https://github.com/PiotrMachowski/Xiaomi-cloud-tokens-extractor"

/** Explains the auth key and the documented ways to obtain it. Nothing here extracts it automatically. */
@Composable
fun BandKeyHelpScreen(onBack: () -> Unit) {
    val uri = LocalUriHandler.current
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.band_help_title)) },
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
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Heading(stringResource(R.string.band_help_what_title))
            Paragraph(stringResource(R.string.band_help_what_text))

            Heading(stringResource(R.string.band_help_important_title))
            Bullet(stringResource(R.string.band_help_important_unpair))
            Bullet(stringResource(R.string.band_help_important_share))
            Bullet(stringResource(R.string.band_help_important_storage))

            Heading(stringResource(R.string.band_help_how_title))
            Paragraph(stringResource(R.string.band_help_how_text))
            Bullet(stringResource(R.string.band_help_how_logs))
            Bullet(stringResource(R.string.band_help_how_root))
            Bullet(stringResource(R.string.band_help_how_extractor))

            Heading(stringResource(R.string.band_help_enter_title))
            Paragraph(stringResource(R.string.band_help_enter_text))

            Heading(stringResource(R.string.band_help_before_title))
            Paragraph(stringResource(R.string.band_help_before_text))

            Heading(stringResource(R.string.band_help_more_title))
            TextButton(onClick = { runCatching { uri.openUri(GB_SERVER_PAIRING) } }) { Text(stringResource(R.string.band_help_link_server)) }
            TextButton(onClick = { runCatching { uri.openUri(GB_XIAOMI_DEVICES) } }) { Text(stringResource(R.string.band_help_link_devices)) }
            TextButton(onClick = { runCatching { uri.openUri(CLOUD_TOKENS_EXTRACTOR) } }) { Text(stringResource(R.string.band_help_link_extractor)) }
        }
    }
}

@Composable
private fun Heading(text: String) {
    Text(text, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 6.dp))
}

@Composable
private fun Paragraph(text: String) {
    Text(text, style = MaterialTheme.typography.bodyMedium)
}

@Composable
private fun Bullet(text: String) {
    Text("•  $text", style = MaterialTheme.typography.bodyMedium)
}
