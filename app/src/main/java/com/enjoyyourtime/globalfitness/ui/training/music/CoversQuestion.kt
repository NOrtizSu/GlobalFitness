package com.enjoyyourtime.globalfitness.ui.training.music

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.enjoyyourtime.globalfitness.R

/**
 * The one-time question about loading the playlists' covers from Spotify, once the start's ad
 * consent is over (see [MusicViewModel.askCovers]) and only on a main section ([onMainSection]
 * false on sub-pages such as a workout). "Not now" and Back keep the covers off; both answers can
 * be changed in the training settings.
 */
@Composable
fun SpotifyCoversQuestion(onMainSection: Boolean) {
    val vm = musicViewModel()
    val ask by vm.askCovers.collectAsStateWithLifecycle()
    if (!onMainSection || !ask) return
    AlertDialog(
        onDismissRequest = { vm.setCoversAllowed(false) },
        title = { Text(stringResource(R.string.music_covers_title)) },
        text = { Text(stringResource(R.string.music_covers_text)) },
        confirmButton = {
            TextButton(onClick = { vm.setCoversAllowed(true) }) { Text(stringResource(R.string.music_covers_allow)) }
        },
        dismissButton = {
            TextButton(onClick = { vm.setCoversAllowed(false) }) { Text(stringResource(R.string.music_covers_not_now)) }
        },
        // A stray tap beside it is not an answer.
        properties = DialogProperties(dismissOnClickOutside = false),
    )
}
