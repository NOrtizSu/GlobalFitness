package com.enjoyyourtime.globalfitness.debug

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.media.MediaMetadata
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.SystemClock
import android.util.Log
import androidx.core.app.NotificationManagerCompat
import com.enjoyyourtime.globalfitness.band.BandNotificationListener
import com.enjoyyourtime.globalfitness.training.player.MediaAccessListener

/**
 * Debug-only test helper: a fake music player in the app's own process, so the music controls can
 * be checked (and screenshotted) on an emulator without any music app. It publishes a media
 * session with a song, a drawn cover and a moving position, answers play/pause/next/previous (from
 * the session or Android's media keys) and plays silence while "playing", which makes it the app
 * Android sends media keys to.
 *
 *     adb shell am broadcast -n com.enjoyyourtime.globalfitness/.debug.DebugMediaSessionReceiver --es action start [--ei track 1] [--ez cover false] [--ez duration false]
 *     ... --es action pause | play | next | stop
 *     ... --es action access    (logs "access music=… band=…": each listener's own access)
 *
 * Tracks: 0 a short title, 1 long Spanish title and artist (ellipsis), 2 a Latin title starting
 * with digits (right-to-left check). "stop" ends the session.
 */
class DebugMediaSessionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (val action = intent.getStringExtra("action") ?: "start") {
            "start" -> DebugPlayer.get(context).start(
                track = intent.getIntExtra("track", 0),
                withCover = intent.getBooleanExtra("cover", true),
                withDuration = intent.getBooleanExtra("duration", true),
            )
            "pause" -> DebugPlayer.current?.pause()
            "play" -> DebugPlayer.current?.play()
            "next" -> DebugPlayer.current?.skip(1)
            "stop" -> DebugPlayer.current?.stop()
            // What each of Fortia's two notification listeners is granted, as the app reads it, and the
            // package-wide answer (true when either is), for comparison.
            "access" -> Log.i(
                TAG,
                "access music=${MediaAccessListener.isGranted(context)} band=${BandNotificationListener.isAccessGranted(context)} " +
                    "package=${NotificationManagerCompat.getEnabledListenerPackages(context).contains(context.packageName)}",
            )
            else -> Log.w(TAG, "Unknown action $action: start | pause | play | next | stop | access")
        }
    }

    private companion object {
        const val TAG = "DebugMediaSession"
    }
}

/** The fake player behind [DebugMediaSessionReceiver]; lives on the main thread. */
private class DebugPlayer(context: Context) {
    private class Track(val title: String, val artist: String, val album: String, val durationMs: Long, val from: Int, val to: Int)

    private val session = MediaSession(context, "FortiaDebugPlayer")
    private var audio: AudioTrack? = null
    private var index = 0
    private var playing = false
    private var positionMs = 0L
    private var since = 0L
    private var withCover = true
    private var withDuration = true

    init {
        session.setCallback(object : MediaSession.Callback() {
            override fun onPlay() = play()

            override fun onPause() = pause()

            override fun onSkipToNext() = skip(1)

            override fun onSkipToPrevious() = skip(-1)

            override fun onStop() = stop()
        })
    }

    fun start(track: Int, withCover: Boolean, withDuration: Boolean) {
        index = track.mod(TRACKS.size)
        this.withCover = withCover
        this.withDuration = withDuration
        positionMs = START_MS
        session.isActive = true
        publishMetadata()
        play()
    }

    fun play() {
        since = SystemClock.elapsedRealtime()
        playing = true
        silence().play()
        publishState()
    }

    fun pause() {
        positionMs = position()
        playing = false
        audio?.pause()
        publishState()
    }

    fun skip(by: Int) {
        index = (index + by).mod(TRACKS.size)
        positionMs = 0L
        since = SystemClock.elapsedRealtime()
        publishMetadata()
        publishState()
    }

    fun stop() {
        playing = false
        audio?.release()
        audio = null
        session.isActive = false
        session.release()
        current = null
    }

    private fun position(): Long = if (playing) positionMs + (SystemClock.elapsedRealtime() - since) else positionMs

    private fun publishMetadata() {
        val track = TRACKS[index]
        val metadata = MediaMetadata.Builder()
            .putString(MediaMetadata.METADATA_KEY_TITLE, track.title)
            .putString(MediaMetadata.METADATA_KEY_ARTIST, track.artist)
            .putString(MediaMetadata.METADATA_KEY_ALBUM, track.album)
        if (withDuration) metadata.putLong(MediaMetadata.METADATA_KEY_DURATION, track.durationMs)
        if (withCover) metadata.putBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART, cover(track))
        session.setMetadata(metadata.build())
    }

    private fun publishState() {
        val state = if (playing) PlaybackState.STATE_PLAYING else PlaybackState.STATE_PAUSED
        session.setPlaybackState(
            PlaybackState.Builder()
                .setActions(
                    PlaybackState.ACTION_PLAY or PlaybackState.ACTION_PAUSE or PlaybackState.ACTION_PLAY_PAUSE or
                        PlaybackState.ACTION_SKIP_TO_NEXT or PlaybackState.ACTION_SKIP_TO_PREVIOUS or PlaybackState.ACTION_STOP,
                )
                .setState(state, position(), if (playing) 1f else 0f, SystemClock.elapsedRealtime())
                .build(),
        )
    }

    /** One second of silence on a loop: Android then counts this app as the one playing. */
    private fun silence(): AudioTrack = audio ?: run {
        val frames = SAMPLE_RATE
        AudioTrack.Builder()
            .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(SAMPLE_RATE)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build(),
            )
            .setTransferMode(AudioTrack.MODE_STATIC)
            .setBufferSizeInBytes(frames * 2)
            .build()
            .also { track ->
                track.write(ShortArray(frames), 0, frames)
                track.setLoopPoints(0, frames, -1)
                audio = track
            }
    }

    /** A drawn test cover (a gradient, a ring and a note): no picture from anywhere. */
    private fun cover(track: Track): Bitmap {
        val size = COVER_PX.toFloat()
        val bitmap = Bitmap.createBitmap(COVER_PX, COVER_PX, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        paint.shader = LinearGradient(0f, 0f, size, size, track.from, track.to, Shader.TileMode.CLAMP)
        canvas.drawRect(0f, 0f, size, size, paint)
        paint.shader = null
        paint.color = 0x40FFFFFF
        canvas.drawCircle(size / 2, size / 2, size * 0.36f, paint)
        paint.color = 0xFFFFFFFF.toInt()
        paint.textAlign = Paint.Align.CENTER
        paint.textSize = size * 0.42f
        canvas.drawText("♪", size / 2, size * 0.64f, paint)
        return bitmap
    }

    companion object {
        var current: DebugPlayer? = null
            private set

        fun get(context: Context): DebugPlayer = current ?: DebugPlayer(context.applicationContext).also { current = it }

        private const val START_MS = 83_000L
        private const val SAMPLE_RATE = 8_000
        private const val COVER_PX = 512

        private val TRACKS = listOf(
            Track("Power Hour", "Fortia Test Band", "Debug Sessions", 214_000L, 0xFFE65100.toInt(), 0xFF6A1B9A.toInt()),
            Track(
                "Una canción con un título bastante largo para comprobar cómo se recorta",
                "Artista de prueba con un nombre también muy largo",
                "Debug Sessions",
                187_000L,
                0xFF00695C.toInt(),
                0xFF1565C0.toInt(),
            ),
            Track("90's Workout Anthem", "The Debug Ensemble", "Debug Sessions", 243_000L, 0xFFC62828.toInt(), 0xFF283593.toInt()),
        )
    }
}
