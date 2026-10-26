package com.enjoyyourtime.globalfitness.training

import android.content.Context
import com.enjoyyourtime.globalfitness.BuildConfig
import com.enjoyyourtime.globalfitness.training.data.CelebrationStore
import com.enjoyyourtime.globalfitness.training.data.ExercisePhotos
import com.enjoyyourtime.globalfitness.training.data.SpotifyCovers
import com.enjoyyourtime.globalfitness.training.data.TrainingDatabase
import com.enjoyyourtime.globalfitness.training.data.TrainingRepository
import com.enjoyyourtime.globalfitness.training.data.TrainingSettings
import com.enjoyyourtime.globalfitness.training.music.HttpPlaylistFetcher
import com.enjoyyourtime.globalfitness.training.music.PlaylistCovers
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import java.io.File
import kotlin.math.roundToInt

/** Training feature: exercise catalog, the user's machines, workouts, their settings and the day's music. */
class TrainingModule(context: Context, scope: CoroutineScope) {
    val settings = TrainingSettings(context)
    val database = TrainingDatabase(context)
    val repository = TrainingRepository(database, scope)
    val photos = ExercisePhotos(context)

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

    private fun coversAllowed(music: Boolean, covers: SpotifyCovers): Boolean = music && covers == SpotifyCovers.ALLOWED
}
