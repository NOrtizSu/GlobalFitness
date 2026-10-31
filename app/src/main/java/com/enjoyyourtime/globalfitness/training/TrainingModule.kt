package com.enjoyyourtime.globalfitness.training

import android.content.Context
import com.enjoyyourtime.globalfitness.BuildConfig
import com.enjoyyourtime.globalfitness.training.data.CelebrationStore
import com.enjoyyourtime.globalfitness.training.data.ExercisePhotos
import com.enjoyyourtime.globalfitness.training.data.SpotifyCovers
import com.enjoyyourtime.globalfitness.training.data.TrainingDatabase
import com.enjoyyourtime.globalfitness.training.data.TrainingRepository
import com.enjoyyourtime.globalfitness.training.data.TrainingSettings
import com.enjoyyourtime.globalfitness.training.live.RestTimers
import com.enjoyyourtime.globalfitness.training.live.WorkoutPausing
import com.enjoyyourtime.globalfitness.training.music.HttpPlaylistFetcher
import com.enjoyyourtime.globalfitness.training.music.PlaylistCovers
import com.enjoyyourtime.globalfitness.training.player.AndroidMediaSessions
import com.enjoyyourtime.globalfitness.training.player.MusicPlayer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import java.io.File
import kotlin.math.roundToInt

/**
 * Training feature: exercise catalog, the user's machines, workouts (with the rest timer and pauses
 * of the one in progress), their settings, the day's music and its controls.
 */
class TrainingModule(context: Context, scope: CoroutineScope) {
    val settings = TrainingSettings(context)
    val database = TrainingDatabase(context)
    val repository = TrainingRepository(database, scope)
    val photos = ExercisePhotos(context)

    /** The rest timer of the workout in progress (its screens, cards and notification show it). */
    val rest = RestTimers()

    /** Pause and resume of the workout in progress, with its rest timer. */
    val pausing = WorkoutPausing(repository, rest)

    /** Achievement levels already celebrated on this phone. */
    val celebrations = CelebrationStore(context)

    /**
     * Names and covers of the day's Spotify playlists, shared by every screen that suggests them;
     * Spotify is only asked while the suggestions are on and the user allowed the covers.
     */
    val playlistCovers = PlaylistCovers(
        dir = File(context.cacheDir, PlaylistCovers.DIR),
        scope = scope,
        allowed = combine(settings.musicSuggestions, settings.spotifyCovers, ::coversAllowed)
            .stateIn(scope, SharingStarted.Eagerly, coversAllowed(settings.musicSuggestions.value, settings.spotifyCovers.value)),
        fetcher = HttpPlaylistFetcher("Fortia/" + BuildConfig.VERSION_NAME),
        maxCoverPx = (PlaylistCovers.LARGEST_COVER_DP * context.resources.displayMetrics.density).roundToInt(),
    )

    /**
     * The music controls for whatever app plays (Android's media session and media keys, no
     * Spotify account or API); the screens collect its states only while they show them.
     */
    val player = MusicPlayer(
        sessions = AndroidMediaSessions(context, (MusicPlayer.LARGEST_COVER_DP * context.resources.displayMetrics.density).roundToInt()),
        keys = AndroidMediaSessions.mediaKeys(context),
        scope = scope,
    )

    private fun coversAllowed(music: Boolean, covers: SpotifyCovers): Boolean = music && covers == SpotifyCovers.ALLOWED
}
