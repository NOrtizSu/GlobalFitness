@file:OptIn(ExperimentalMaterial3Api::class)

package com.enjoyyourtime.globalfitness.ui.training.music

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.annotation.StringRes
import androidx.compose.animation.Crossfade
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.enjoyyourtime.globalfitness.R
import com.enjoyyourtime.globalfitness.training.music.PlaylistArt
import com.enjoyyourtime.globalfitness.training.music.PlaylistCovers
import com.enjoyyourtime.globalfitness.training.music.SpotifyLinks
import com.enjoyyourtime.globalfitness.training.music.TrainingDay
import com.enjoyyourtime.globalfitness.training.music.WorkoutPlaylist
import com.enjoyyourtime.globalfitness.ui.components.AppCard
import com.enjoyyourtime.globalfitness.ui.components.LocalAppSnackbar
import com.enjoyyourtime.globalfitness.ui.training.TrainingIcons
import kotlinx.coroutines.launch

/** Spotify allows small rounded corners on covers, nothing else: the picture is shown as it is. */
private val CoverShape = RoundedCornerShape(4.dp)
private val TILE = PlaylistCovers.LARGEST_COVER_DP.dp

/** Hoy's covers never shrink below a touch target: on narrow screens the row scrolls. */
private val MIN_COVER = 48.dp
private val COVER_GAP = 8.dp

/**
 * Hoy's compact card: the five covers in a row, each opening its playlist in Spotify, under the
 * reason for the choice.
 */
@Composable
fun TodayMusicCard(suggestions: MusicSuggestions, modifier: Modifier = Modifier) {
    val art = playlistArt(suggestions)
    val open = rememberPlaylistOpener()
    val openLabel = stringResource(R.string.music_open)
    AppCard(modifier) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            MusicHeader(suggestions.day)
            SpotifyAttribution()
            // Five equal squares fill the card (48 dp or more on phones 360 dp wide and up); never smaller.
            BoxWithConstraints(Modifier.fillMaxWidth()) {
                val count = suggestions.playlists.size.coerceAtLeast(1)
                val side = maxOf(MIN_COVER, (maxWidth - COVER_GAP * (count - 1)) / count)
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(COVER_GAP)) {
                    suggestions.playlists.forEach { p ->
                        val name = art[p.id]?.title ?: p.name
                        PlaylistCover(
                            art[p.id]?.cover,
                            Modifier
                                .size(side)
                                .clip(CoverShape)
                                .clickable(onClickLabel = openLabel, role = Role.Button) { open(p.id) }
                                .semantics { contentDescription = name },
                        )
                    }
                }
            }
        }
    }
}

/** The Entreno tab's card: covers with their names in a row that scrolls. */
@Composable
fun TrainingMusicCard(suggestions: MusicSuggestions, modifier: Modifier = Modifier) {
    val art = playlistArt(suggestions)
    val open = rememberPlaylistOpener()
    AppCard(modifier) {
        Column(Modifier.padding(vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            MusicHeader(suggestions.day, Modifier.padding(horizontal = 16.dp))
            SpotifyAttribution(Modifier.padding(horizontal = 16.dp))
            LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                items(suggestions.playlists, key = { it.id }) { p ->
                    PlaylistTile(p, art[p.id], onClick = { open(p.id) })
                }
            }
        }
    }
}

/**
 * The workout's sheet (from the music button of its top bar): the five, one per row. When no app
 * can open a playlist, [onOpenFailed] is called (the sheet's host closes it and says so).
 */
@Composable
fun MusicSheet(suggestions: MusicSuggestions, onDismiss: () -> Unit, onOpenFailed: () -> Unit) {
    val context = LocalContext.current
    val art = playlistArt(suggestions)
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        LazyColumn(Modifier.navigationBarsPadding(), contentPadding = PaddingValues(bottom = 16.dp)) {
            item(key = "header") {
                MusicHeader(
                    suggestions.day,
                    Modifier.padding(start = 24.dp, end = 24.dp, bottom = 8.dp),
                    titleStyle = MaterialTheme.typography.titleLarge,
                )
            }
            // 12 dp of clear space around Spotify's icon: 8 + 4 above, 4 + the row's 8 below.
            item(key = "attribution") { SpotifyAttribution(Modifier.padding(horizontal = 24.dp, vertical = 4.dp)) }
            items(suggestions.playlists, key = { it.id }) { p ->
                SheetRow(p, art[p.id], onClick = { if (!openPlaylist(context, p.id)) onOpenFailed() })
            }
        }
    }
}

/** Opens the playlist in any installed Spotify app, else on Spotify's website; false when no app can. */
internal fun openPlaylist(context: Context, id: String): Boolean {
    val referrer = Uri.parse("android-app://" + context.packageName)
    return SpotifyLinks.open(id) { link ->
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(link)).putExtra(Intent.EXTRA_REFERRER, referrer))
    }
}

/** Opens a playlist by id from a card, with a message in the app's snackbar when no app can. */
@Composable
private fun rememberPlaylistOpener(): (String) -> Unit {
    val context = LocalContext.current
    val snackbar = LocalAppSnackbar.current
    val scope = rememberCoroutineScope()
    val failed = stringResource(R.string.music_open_failed)
    return remember(context, snackbar, scope, failed) {
        { id -> if (!openPlaylist(context, id)) scope.launch { snackbar.showSnackbar(failed, withDismissAction = true) } }
    }
}

/** Names and covers known so far; asks for the missing ones on every resume (back online, a new day). */
@Composable
private fun playlistArt(suggestions: MusicSuggestions): Map<String, PlaylistArt> {
    val vm = musicViewModel()
    val ids = remember(suggestions) { suggestions.playlists.map { it.id } }
    LifecycleResumeEffect(ids) {
        vm.loadArt(ids)
        onPauseOrDispose { }
    }
    val art by vm.art.collectAsStateWithLifecycle()
    return art
}

@StringRes
private fun reason(day: TrainingDay): Int = when (day) {
    TrainingDay.STRENGTH -> R.string.music_reason_strength
    TrainingDay.LEGS -> R.string.music_reason_legs
    TrainingDay.CARDIO -> R.string.music_reason_cardio
    TrainingDay.TRAINING_DAY -> R.string.music_reason_training_day
    TrainingDay.RECOVERY -> R.string.music_reason_recovery
    TrainingDay.REST -> R.string.music_reason_rest
}

/** Note and title (wrapped when long, never cut), with the reason for the choice under them. */
@Composable
private fun MusicHeader(day: TrainingDay, modifier: Modifier = Modifier, titleStyle: TextStyle = MaterialTheme.typography.titleMedium) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(TrainingIcons.MusicNote, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(8.dp))
            Text(stringResource(R.string.music_title), style = titleStyle, modifier = Modifier.weight(1f).semantics { heading() })
        }
        Text(stringResource(reason(day)), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/**
 * "Spotify playlists" after Spotify's icon, as Spotify's guidelines ask for its content: the icon
 * unaltered, at least 21 px, half its height of clear space around it (12 dp here and from the
 * caller's spacing), and monochrome since the cards are neither pure white nor black (black on
 * light, white on dark; see docs/third-party/spotify/README.md).
 */
@Composable
private fun SpotifyAttribution(modifier: Modifier = Modifier) {
    val dark = MaterialTheme.colorScheme.surface.luminance() < 0.5f
    Row(modifier.semantics(mergeDescendants = true) { }, verticalAlignment = Alignment.CenterVertically) {
        Image(painterResource(if (dark) R.drawable.ic_spotify_white else R.drawable.ic_spotify_black), contentDescription = null)
        Spacer(Modifier.width(12.dp))
        Text(stringResource(R.string.music_attribution), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun PlaylistTile(p: WorkoutPlaylist, art: PlaylistArt?, onClick: () -> Unit) {
    Column(
        Modifier
            .width(TILE)
            .clip(MaterialTheme.shapes.small)
            .clickable(onClickLabel = stringResource(R.string.music_open), role = Role.Button, onClick = onClick),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        PlaylistCover(art?.cover, Modifier.size(TILE).clip(CoverShape))
        Text(isolated(art?.title ?: p.name), style = MaterialTheme.typography.titleSmall, minLines = 2, maxLines = 2, overflow = TextOverflow.Ellipsis)
        OpenLabel(Modifier.padding(bottom = 4.dp))
    }
}

@Composable
private fun SheetRow(p: WorkoutPlaylist, art: PlaylistArt?, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClickLabel = stringResource(R.string.music_open), role = Role.Button, onClick = onClick)
            .heightIn(min = 72.dp)
            .padding(horizontal = 24.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        PlaylistCover(art?.cover, Modifier.size(56.dp).clip(CoverShape))
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            Text(isolated(art?.title ?: p.name), style = MaterialTheme.typography.bodyLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
            OpenLabel()
        }
    }
}

/**
 * "Open in Spotify" under a playlist's name, on two lines when a language needs them (the Entreno
 * tiles are narrow); TalkBack already hears it as the click action.
 */
@Composable
private fun OpenLabel(modifier: Modifier = Modifier) {
    val primary = MaterialTheme.colorScheme.primary
    Row(modifier.clearAndSetSemantics { }, verticalAlignment = Alignment.Top) {
        // Centred on the first line (labelMedium: 16 sp lines).
        Icon(TrainingIcons.OpenInNew, contentDescription = null, tint = primary, modifier = Modifier.padding(top = 1.dp).size(14.dp))
        Spacer(Modifier.width(4.dp))
        Text(stringResource(R.string.music_open), style = MaterialTheme.typography.labelMedium, color = primary, maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}

/** The cover as Spotify gives it (square, cropped to nothing), or a note while it loads, offline or without consent. */
@Composable
private fun PlaylistCover(cover: ImageBitmap?, modifier: Modifier) {
    Box(modifier.background(MaterialTheme.colorScheme.surfaceContainerHighest), contentAlignment = Alignment.Center) {
        Crossfade(targetState = cover, label = "cover") { img ->
            if (img != null) {
                Image(img, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            } else {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Icon(
                        TrainingIcons.MusicNote,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.fillMaxSize(0.4f),
                    )
                }
            }
        }
    }
}

/**
 * Spotify's playlist names are Latin text ("90's Workout"). Inside an Arabic layout the bidi algorithm would move
 * the leading digits and the apostrophe to the wrong end; a first-strong isolate keeps the name in its own direction
 * while the line still starts at the layout's start edge.
 */
internal fun isolated(name: String): String = "\u2068$name\u2069"
