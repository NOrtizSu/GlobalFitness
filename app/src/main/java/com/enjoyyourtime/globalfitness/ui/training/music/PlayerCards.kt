package com.enjoyyourtime.globalfitness.ui.training.music

import android.os.SystemClock
import androidx.compose.animation.Crossfade
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.enjoyyourtime.globalfitness.R
import com.enjoyyourtime.globalfitness.training.player.MediaAccessListener
import com.enjoyyourtime.globalfitness.training.player.MusicPlayer
import com.enjoyyourtime.globalfitness.training.player.PlaybackStatus
import com.enjoyyourtime.globalfitness.training.player.PlayerCommand
import com.enjoyyourtime.globalfitness.training.player.PlayerState
import com.enjoyyourtime.globalfitness.training.player.showsStrip
import com.enjoyyourtime.globalfitness.training.player.stripHidesAt
import com.enjoyyourtime.globalfitness.ui.components.AppCard
import com.enjoyyourtime.globalfitness.ui.components.LocalAppSnackbar
import com.enjoyyourtime.globalfitness.ui.training.TrainingIcons
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Covers are shown as the music app gives them: square, small rounded corners at most. */
private val CoverShape = RoundedCornerShape(4.dp)
private val CARD_COVER = MusicPlayer.LARGEST_COVER_DP.dp
private val SHEET_COVER = 48.dp
private val STRIP_COVER = 40.dp

/**
 * The Entreno tab's "Now playing" card, next to the day's playlists: the song with its cover and
 * the buttons, or the buttons alone (nothing plays, or no notification access: then also "See
 * what's playing", which explains that access first). [suggestionsShown]: the day's playlists
 * are on screen, so "nothing plays" can point to them.
 */
@Composable
fun PlayerCard(state: PlayerState, suggestionsShown: Boolean, modifier: Modifier = Modifier) {
    val vm = playerViewModel()
    var explain by rememberSaveable { mutableStateOf(false) }
    val snackbar = LocalAppSnackbar.current
    val scope = rememberCoroutineScope()
    val failed = stringResource(R.string.player_access_failed)
    AppCard(modifier) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            PlayerHeader()
            PlayerBody(state, suggestionsShown, CARD_COVER, onCommand = vm::send, onExplain = { explain = true })
        }
    }
    if (explain) {
        PlayerAccessDialog(
            onDismiss = { explain = false },
            onFailed = { scope.launch { snackbar.showSnackbar(failed, withDismissAction = true) } },
        )
    }
}

/**
 * The player at the top of the workout's music sheet, in one compact row so the day's playlists
 * stay in view below it. With a song: its cover, title and artist, play/pause and next (like the
 * strip: previous would squeeze the title on narrow phones), and its progress under them. Without:
 * "Now playing" with previous, play/pause and next, and under the title "See what's playing" (no
 * access) or, at full width below, why nothing plays. The Entreno card keeps the longer texts.
 */
@Composable
fun SheetPlayer(state: PlayerState, suggestionsShown: Boolean, onExplain: () -> Unit, modifier: Modifier = Modifier) {
    if (state == PlayerState.Off) return
    val vm = playerViewModel()
    Column(modifier.padding(horizontal = 24.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(Modifier.fillMaxWidth().heightIn(min = 64.dp), verticalAlignment = Alignment.CenterVertically) {
            if (state is PlayerState.Track) {
                NowPlayingRow(state, SHEET_COVER, compact = true, asHeading = true, modifier = Modifier.weight(1f))
            } else {
                Column(Modifier.weight(1f)) {
                    PlayerHeader()
                    if (state == PlayerState.Keys) {
                        // Under the title, past the note icon (20 dp + 8 dp).
                        TextButton(
                            onClick = onExplain,
                            contentPadding = PaddingValues(start = 28.dp, end = 8.dp),
                            modifier = Modifier.heightIn(min = 48.dp),
                        ) { Text(stringResource(R.string.player_show)) }
                    }
                }
            }
            Spacer(Modifier.width(8.dp))
            PlayerButtons(state, onCommand = vm::send, compact = true, previous = state !is PlayerState.Track)
        }
        when (state) {
            is PlayerState.Track -> TrackProgress(state)
            PlayerState.Idle -> Text(
                stringResource(if (suggestionsShown) R.string.player_idle_music else R.string.player_idle),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 28.dp),
            )
            else -> Unit
        }
    }
}

/**
 * The workout's strip with the song that plays or just paused ([stripTrack]), docked above the rest timer (never
 * over the set table): cover, title and artist open the music sheet ([onOpen]); play/pause and next
 * work in place. [insetBottom]: nothing is docked below it, so it keeps clear of the navigation bar.
 */
@Composable
fun PlayerStrip(track: PlayerState.Track, onOpen: () -> Unit, insetBottom: Boolean) {
    val vm = playerViewModel()
    Surface(color = MaterialTheme.colorScheme.surfaceContainerHigh) {
        Column(if (insetBottom) Modifier.navigationBarsPadding() else Modifier) {
            TrackProgress(track)
            Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                NowPlayingRow(
                    track,
                    STRIP_COVER,
                    compact = true,
                    modifier = Modifier
                        .weight(1f)
                        .clickable(onClickLabel = stringResource(R.string.player_open), role = Role.Button, onClick = onOpen)
                        .padding(start = 16.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
                )
                MediaDirection {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        PlayPauseButton(track, onCommand = vm::send, filled = false)
                        IconButton(onClick = { vm.send(PlayerCommand.NEXT) }, enabled = track.canNext) {
                            Icon(TrainingIcons.TrackNext, contentDescription = stringResource(R.string.player_next))
                        }
                    }
                }
            }
        }
    }
}

/**
 * The song for the workout's strip now, or null: while it plays, and until two minutes after it
 * pauses ([showsStrip]), when it hides by itself.
 */
@Composable
fun stripTrack(state: PlayerState): PlayerState.Track? {
    val visible by produceState(state.showsStrip(SystemClock.elapsedRealtime()), state) {
        value = state.showsStrip(SystemClock.elapsedRealtime())
        val hidesAt = state.stripHidesAt() ?: return@produceState
        if (value) {
            delay(hidesAt - SystemClock.elapsedRealtime())
            value = false
        }
    }
    return (state as? PlayerState.Track)?.takeIf { visible }
}

/**
 * What seeing the song needs, before Android's notification access page (Google Play's prominent
 * disclosure): what is read, what for, that notifications are ignored and nothing leaves the
 * phone, and where to turn it off. Only "Continue" opens Android's page; [onFailed] when no
 * settings screen opens.
 */
@Composable
fun PlayerAccessDialog(onDismiss: () -> Unit, onFailed: () -> Unit) {
    val context = LocalContext.current
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(TrainingIcons.MusicNote, contentDescription = null) },
        title = { Text(stringResource(R.string.player_access_title)) },
        text = { Text(stringResource(R.string.player_access_text), Modifier.verticalScroll(rememberScrollState())) },
        confirmButton = {
            TextButton(onClick = {
                onDismiss()
                if (!MediaAccessListener.openSettings(context)) onFailed()
            }) { Text(stringResource(R.string.action_continue)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.player_access_not_now)) }
        },
        // A stray tap beside it is not an answer.
        properties = DialogProperties(dismissOnClickOutside = false),
    )
}

@Composable
private fun PlayerHeader(modifier: Modifier = Modifier, titleStyle: TextStyle = MaterialTheme.typography.titleMedium) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        Icon(TrainingIcons.MusicNote, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(8.dp))
        Text(stringResource(R.string.player_title), style = titleStyle, modifier = Modifier.weight(1f).semantics { heading() })
    }
}

/** The song (or why there is none), its progress, the buttons and, without access, "See what's playing". */
@Composable
private fun ColumnScope.PlayerBody(
    state: PlayerState,
    suggestionsShown: Boolean,
    coverSize: Dp,
    onCommand: (PlayerCommand) -> Unit,
    onExplain: () -> Unit,
) {
    val note = MaterialTheme.typography.bodyMedium
    val variant = MaterialTheme.colorScheme.onSurfaceVariant
    when (state) {
        is PlayerState.Track -> {
            NowPlayingRow(state, coverSize, compact = false)
            TrackProgress(state)
        }
        PlayerState.Idle -> Text(stringResource(if (suggestionsShown) R.string.player_idle_music else R.string.player_idle), style = note, color = variant)
        PlayerState.Keys -> Text(stringResource(R.string.player_keys_text), style = note, color = variant)
        PlayerState.Off -> return
    }
    PlayerButtons(state, onCommand, Modifier.fillMaxWidth())
    if (state == PlayerState.Keys) {
        TextButton(onClick = onExplain, modifier = Modifier.align(Alignment.CenterHorizontally).heightIn(min = 48.dp)) {
            Text(stringResource(R.string.player_show))
        }
    }
}

/**
 * Cover, title, artist and (not [compact]) the app, read by TalkBack as one "Now playing: title,
 * artist". Song names are often Latin text inside right-to-left layouts: they are isolated.
 */
@Composable
private fun NowPlayingRow(track: PlayerState.Track, coverSize: Dp, compact: Boolean, modifier: Modifier = Modifier, asHeading: Boolean = false) {
    val title = track.title ?: stringResource(R.string.player_untitled)
    val description = track.artist?.let { stringResource(R.string.player_now_playing, title, it) }
        ?: stringResource(R.string.player_now_playing_title, title)
    val variant = MaterialTheme.colorScheme.onSurfaceVariant
    // [asHeading]: it stands where the "Now playing" title would (the sheet), for TalkBack's heading jumps.
    Row(
        modifier.clearAndSetSemantics {
            contentDescription = description
            if (asHeading) heading()
        },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TrackCover(track.cover, Modifier.size(coverSize))
        Spacer(Modifier.width(if (compact) 12.dp else 16.dp))
        Column(Modifier.weight(1f)) {
            Text(
                isolated(title),
                style = if (compact) MaterialTheme.typography.bodyMedium else MaterialTheme.typography.titleMedium,
                maxLines = if (compact) 1 else 2,
                overflow = TextOverflow.Ellipsis,
            )
            track.artist?.let {
                Text(
                    isolated(it),
                    style = if (compact) MaterialTheme.typography.bodySmall else MaterialTheme.typography.bodyMedium,
                    color = variant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (!compact) {
                track.app?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = variant, maxLines = 1, overflow = TextOverflow.Ellipsis) }
            }
        }
    }
}

/**
 * A thin bar of the song played so far, moving every second while it plays and the screen is in
 * front; nothing when the app gives no duration. Decorative for TalkBack (the row says the song).
 */
@Composable
private fun TrackProgress(track: PlayerState.Track) {
    if (track.durationMs == null || track.positionMs < 0) return
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val now by produceState(SystemClock.elapsedRealtime(), track, lifecycle) {
        value = SystemClock.elapsedRealtime()
        if (track.status != PlaybackStatus.PLAYING) return@produceState
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (true) {
                value = SystemClock.elapsedRealtime()
                delay(1_000)
            }
        }
    }
    val progress = track.progressAt(now) ?: return
    MediaDirection {
        LinearProgressIndicator(
            progress = { progress },
            modifier = Modifier.fillMaxWidth().clearAndSetSemantics { },
            gapSize = 0.dp,
            drawStopIndicator = {},
        )
    }
}

/**
 * Previous, play/pause and next; previous and next are off when the app says it cannot skip.
 * [compact]: side by side with no gap and a smaller play/pause (the sheet's one-row player);
 * [previous]: false leaves previous out.
 */
@Composable
private fun PlayerButtons(
    state: PlayerState,
    onCommand: (PlayerCommand) -> Unit,
    modifier: Modifier = Modifier,
    compact: Boolean = false,
    previous: Boolean = true,
) {
    val track = state as? PlayerState.Track
    MediaDirection {
        Row(
            modifier,
            horizontalArrangement = if (compact) Arrangement.Start else Arrangement.spacedBy(16.dp, Alignment.CenterHorizontally),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (previous) {
                IconButton(onClick = { onCommand(PlayerCommand.PREVIOUS) }, enabled = track?.canPrevious ?: true) {
                    Icon(TrainingIcons.TrackPrevious, contentDescription = stringResource(R.string.player_previous))
                }
            }
            PlayPauseButton(state, onCommand, filled = true, size = if (compact) 48.dp else 56.dp)
            IconButton(onClick = { onCommand(PlayerCommand.NEXT) }, enabled = track?.canNext ?: true) {
                Icon(TrainingIcons.TrackNext, contentDescription = stringResource(R.string.player_next))
            }
        }
    }
}

/**
 * Pause while something plays, play otherwise, both in one when nothing is known (no access).
 * TalkBack hears the action and, once known, the state ("Pause, Playing").
 */
@Composable
private fun PlayPauseButton(state: PlayerState, onCommand: (PlayerCommand) -> Unit, filled: Boolean, size: Dp = 56.dp) {
    val track = state as? PlayerState.Track
    val (icon: ImageVector, label: Int) = when {
        track != null && track.status.active -> TrainingIcons.Pause to R.string.player_pause
        track != null || state == PlayerState.Idle -> Icons.Filled.PlayArrow to R.string.player_play
        else -> TrainingIcons.PlayPause to R.string.player_play_pause
    }
    val status = track?.let { stringResource(if (it.status.active) R.string.player_state_playing else R.string.player_state_paused) }
    val withState = Modifier.semantics { if (status != null) stateDescription = status }
    val content: @Composable () -> Unit = { Icon(icon, contentDescription = stringResource(label)) }
    if (filled) {
        FilledTonalIconButton(onClick = { onCommand(PlayerCommand.PLAY_PAUSE) }, modifier = withState.size(size), content = content)
    } else {
        IconButton(onClick = { onCommand(PlayerCommand.PLAY_PAUSE) }, modifier = withState, content = content)
    }
}

/** The cover unaltered (cropped to a square), or a note when the app gives none. */
@Composable
private fun TrackCover(cover: ImageBitmap?, modifier: Modifier) {
    Box(modifier.clip(CoverShape).background(MaterialTheme.colorScheme.surfaceContainerHighest), contentAlignment = Alignment.Center) {
        Crossfade(targetState = cover, label = "track-cover") { img ->
            if (img != null) {
                Image(img, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            } else {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Icon(TrainingIcons.MusicNote, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.fillMaxSize(0.5f))
                }
            }
        }
    }
}

/** Media controls and their progress keep left-to-right order in right-to-left languages, as Material asks. */
@Composable
private fun MediaDirection(content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr, content = content)
}
