package com.enjoyyourtime.globalfitness.training.music

/** What a playlist is for: the day's training decides how many of each are offered (see [DailyPlaylists]). */
enum class PlaylistMood {
    /** Strength and heavy lifting: rock, metal, hip-hop and electronic power. */
    INTENSE,

    /** Running, cycling and HIIT tempo. */
    CARDIO,

    /** Stretching, yoga, recovery and cool-down. */
    CALM,

    /** All-round gym hits. */
    GENERAL,
}

/**
 * A public Spotify playlist. [name] is the title Spotify's oEmbed endpoint gave when the id was
 * checked (tools/music/verify_playlists.py): shown until the live title and cover are loaded, and
 * whenever they cannot be.
 */
data class WorkoutPlaylist(val id: String, val name: String, val mood: PlaylistMood)

/**
 * Spotify's own editorial workout playlists, available worldwide and kept up to date by Spotify
 * (no account, key or Web API is needed to show or open them). Each mood's pool is a multiple of
 * the most it gives on one day and at least three times that, so consecutive days never repeat a
 * playlist (see [DailyPlaylists]). Re-check the ids with `python tools/music/verify_playlists.py`.
 */
object WorkoutPlaylists {
    val all: List<WorkoutPlaylist> = listOf(
        // Strength: mixed genres, heaviest first.
        WorkoutPlaylist("37i9dQZF1DX76Wlfdnj7AP", "Beast Mode", PlaylistMood.INTENSE),
        WorkoutPlaylist("37i9dQZF1DXe6bgV3TmZOL", "Heavy Workout", PlaylistMood.INTENSE),
        WorkoutPlaylist("37i9dQZF1DWUVpAXiEPK8P", "Power Workout", PlaylistMood.INTENSE),
        WorkoutPlaylist("37i9dQZF1DX9oh43oAzkyx", "Beast Mode Hip-Hop", PlaylistMood.INTENSE),
        WorkoutPlaylist("37i9dQZF1DWTl4y3vgJOXW", "Locked In", PlaylistMood.INTENSE),
        WorkoutPlaylist("37i9dQZF1DXdURFimg6Blm", "Beast Mode Dance", PlaylistMood.INTENSE),
        WorkoutPlaylist("37i9dQZF1DX36TRAnIL92N", "Techno Workout", PlaylistMood.INTENSE),
        WorkoutPlaylist("37i9dQZF1DWY3PJWG3ogmJ", "Extreme Metal Workout", PlaylistMood.INTENSE),
        WorkoutPlaylist("37i9dQZF1DX0wiundViT27", "Rock Me Up", PlaylistMood.INTENSE),
        WorkoutPlaylist("37i9dQZF1DWYNSm3Z3MxiM", "Classic Rock Workout", PlaylistMood.INTENSE),
        WorkoutPlaylist("37i9dQZF1DX0BZrbvIqxCd", "Punk Rock Workout", PlaylistMood.INTENSE),
        WorkoutPlaylist("37i9dQZF1DXdc2CX1rMGAc", "Beast Mode Latin", PlaylistMood.INTENSE),
        // Cardio: running and spinning tempo.
        WorkoutPlaylist("37i9dQZF1DWSJHnPb1f0X3", "Cardio", PlaylistMood.CARDIO),
        WorkoutPlaylist("37i9dQZF1DX0hWmn8d5pRe", "Born To Run", PlaylistMood.CARDIO),
        WorkoutPlaylist("37i9dQZF1DXadOVCgGhS7j", "Fun Run", PlaylistMood.CARDIO),
        WorkoutPlaylist("37i9dQZF1DX35oM5SPECmN", "Run Wild", PlaylistMood.CARDIO),
        WorkoutPlaylist("37i9dQZF1DX32NsLKyzScr", "Power Hour", PlaylistMood.CARDIO),
        WorkoutPlaylist("37i9dQZF1DX4osfY3zybD2", "Retro Running", PlaylistMood.CARDIO),
        WorkoutPlaylist("37i9dQZF1DXbcCmWfoUaw2", "00s Pop Run", PlaylistMood.CARDIO),
        WorkoutPlaylist("37i9dQZF1DWZq91oLsHZvy", "Indie Running", PlaylistMood.CARDIO),
        WorkoutPlaylist("37i9dQZF1DX4Y1uAfxGdKJ", "Electronic Running", PlaylistMood.CARDIO),
        WorkoutPlaylist("37i9dQZF1DWVhQ5d3I6DeF", "Fast Pop Run", PlaylistMood.CARDIO),
        WorkoutPlaylist("37i9dQZF1DWT6anPZiHuxz", "Running Club", PlaylistMood.CARDIO),
        WorkoutPlaylist("37i9dQZF1DWXmQEAjlxGhi", "Gym Latino", PlaylistMood.CARDIO),
        // Calm: stretching, yoga, pilates and recovery.
        WorkoutPlaylist("37i9dQZF1DX7FiQUm1UvSn", "Stretching", PlaylistMood.CALM),
        WorkoutPlaylist("37i9dQZF1DX9uKNf5jGX6m", "Yoga & Meditation", PlaylistMood.CALM),
        WorkoutPlaylist("37i9dQZF1DX0YDqnWWzvxD", "Savasana", PlaylistMood.CALM),
        WorkoutPlaylist("37i9dQZF1DX3AqNtukWhcT", "Yoga Flow", PlaylistMood.CALM),
        WorkoutPlaylist("37i9dQZF1DXdVyc8LtLi96", "Sunrise Yoga", PlaylistMood.CALM),
        WorkoutPlaylist("37i9dQZF1DWVroJq360LRh", "Acoustic Pilates", PlaylistMood.CALM),
        WorkoutPlaylist("37i9dQZF1DXdMMpvGH77mA", "Positive Flow", PlaylistMood.CALM),
        WorkoutPlaylist("37i9dQZF1DWTofcvJ2Dvma", "Vinyasa Flow", PlaylistMood.CALM),
        WorkoutPlaylist("37i9dQZF1DWZqd5JICZI0u", "Peaceful Meditation", PlaylistMood.CALM),
        WorkoutPlaylist("37i9dQZF1DWZvpVE2NxPV2", "Hatha Yoga", PlaylistMood.CALM),
        WorkoutPlaylist("37i9dQZF1DX7R7Bjxm48PR", "Piano Yoga", PlaylistMood.CALM),
        WorkoutPlaylist("37i9dQZF1DX1tuUiirhaT3", "Meditate to the Sounds of Nature", PlaylistMood.CALM),
        // All-round gym hits.
        WorkoutPlaylist("37i9dQZF1DXdxcBWuJkbcy", "Gym Hits", PlaylistMood.GENERAL),
        WorkoutPlaylist("37i9dQZF1DX70RN3TfWWJh", "Workout", PlaylistMood.GENERAL),
        WorkoutPlaylist("37i9dQZF1DX8CwbNGNKurt", "Throwback Workout", PlaylistMood.GENERAL),
        WorkoutPlaylist("37i9dQZF1DXdMm3yYbD7IO", "90's Workout", PlaylistMood.GENERAL),
        WorkoutPlaylist("37i9dQZF1DWZY6U3N4Hq7n", "80s Workout", PlaylistMood.GENERAL),
        WorkoutPlaylist("37i9dQZF1DX5gQonLbZD9s", "Pumped Pop", PlaylistMood.GENERAL),
    )
}
