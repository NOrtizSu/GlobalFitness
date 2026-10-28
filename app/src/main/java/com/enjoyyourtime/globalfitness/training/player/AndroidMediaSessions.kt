package com.enjoyyourtime.globalfitness.training.player

import android.content.ContentResolver
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.AudioManager
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.KeyEvent
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import com.enjoyyourtime.globalfitness.training.music.PlaylistCovers
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.roundToInt

/**
 * [MediaSessions] from Android's MediaSessionManager, on behalf of [MediaAccessListener]. Callbacks
 * arrive on the main thread. Covers are scaled to [coverPx] (the largest cover on screen).
 */
class AndroidMediaSessions(context: Context, private val coverPx: Int) : MediaSessions {
    private val app = context.applicationContext
    private val manager = app.getSystemService(MediaSessionManager::class.java)
    private val component = MediaAccessListener.component(app)
    private val main = Handler(Looper.getMainLooper())
    private val labels = ConcurrentHashMap<String, String>()

    override fun accessGranted(): Boolean = MediaAccessListener.isGranted(app)

    override fun active(): List<MediaSessionHandle> = manager.getActiveSessions(component).map(::Session)

    override fun watch(onChange: () -> Unit): AutoCloseable {
        val listener = MediaSessionManager.OnActiveSessionsChangedListener { onChange() }
        manager.addOnActiveSessionsChangedListener(listener, component, main)
        return AutoCloseable { manager.removeOnActiveSessionsChangedListener(listener) }
    }

    /**
     * One session. Its playback state is read once and then taken from its callbacks; its song
     * ([MediaMetadata], with the cover) only while it is the one shown: other apps' songs are
     * neither read nor kept.
     */
    private inner class Session(private val controller: MediaController) : MediaSessionHandle {
        override val id: Any = controller.sessionToken
        private val pkg by lazy { controller.packageName }
        private val playback = SessionStateCache(SystemClock::elapsedRealtime)

        /** The song shown; also what [cover] decodes, so the cover is of the song the card says. */
        private val song = SongCache<MediaMetadata>()

        override fun state(): SessionState = playback.get { stateOf(controller.playbackState) }

        override fun liveStatus(): PlaybackStatus = PlaybackStatus.of(controller.playbackState?.state ?: PlaybackState.STATE_NONE)

        override fun track(): TrackInfo? = song.get { controller.metadata }?.let(::trackOf)

        override fun forgetTrack() = song.forget()

        override fun watch(onChange: () -> Unit): AutoCloseable {
            val callback = object : MediaController.Callback() {
                override fun onPlaybackStateChanged(state: PlaybackState?) {
                    playback.update(stateOf(state))
                    onChange()
                }

                override fun onMetadataChanged(metadata: MediaMetadata?) {
                    // Another app's song, while it is not the one shown: not kept, not looked at.
                    if (song.update(metadata)) onChange()
                }

                override fun onSessionDestroyed() = onChange()
            }
            controller.registerCallback(callback, main)
            return AutoCloseable { controller.unregisterCallback(callback) }
        }

        override fun send(transport: Transport) {
            val controls = controller.transportControls
            when (transport) {
                Transport.PREVIOUS -> controls.skipToPrevious()
                Transport.PLAY -> controls.play()
                Transport.PAUSE -> controls.pause()
                Transport.NEXT -> controls.skipToNext()
            }
        }

        private fun stateOf(state: PlaybackState?) = SessionState(
            packageName = pkg,
            state = state?.state ?: PlaybackState.STATE_NONE,
            actions = state?.actions ?: 0L,
            positionMs = state?.position ?: PlaybackState.PLAYBACK_POSITION_UNKNOWN,
            positionUpdatedAt = state?.lastPositionUpdateTime ?: 0L,
            speed = state?.playbackSpeed ?: 0f,
        )

        override fun cover(): ImageBitmap? {
            val metadata = song.current() ?: return null
            val bitmap = BITMAP_KEYS.firstNotNullOfOrNull { metadata.getBitmap(it) }
            if (bitmap != null) return scaled(bitmap).asImageBitmap()
            val uri = artUri(metadata)?.let(Uri::parse) ?: return null
            if (!coverSourceAllowed(uri.scheme, uri.authority, pkg, ::providerPackage)) return null
            return load(uri)?.let { scaled(it).asImageBitmap() }
        }

        override fun appLabel(): String? {
            val name = labels.getOrPut(pkg) {
                try {
                    val pm = app.packageManager
                    pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
                } catch (e: PackageManager.NameNotFoundException) {
                    // Not visible to Fortia (see the manifest's <queries>): no name rather than a package name.
                    NO_NAME
                } catch (e: RuntimeException) {
                    NO_NAME
                }
            }
            // Asked once per app: a name that cannot be read is not asked again on every update.
            return name.takeUnless { it == NO_NAME }
        }
    }

    private fun trackOf(m: MediaMetadata): TrackInfo = TrackInfo.of(
        title = m.getString(MediaMetadata.METADATA_KEY_TITLE),
        displayTitle = m.getString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE),
        artist = m.getString(MediaMetadata.METADATA_KEY_ARTIST),
        albumArtist = m.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST),
        displaySubtitle = m.getString(MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE),
        durationMs = m.getLong(MediaMetadata.METADATA_KEY_DURATION),
        artKey = artKey(m),
    )

    /** The app whose content provider answers [authority], or null when there is none (or it is not visible). */
    private fun providerPackage(authority: String): String? = try {
        app.packageManager.resolveContentProvider(authority, 0)?.packageName
    } catch (e: RuntimeException) {
        null
    }

    /** The picture's size, else its address: a picture sent after the title, or a better one, loads again. */
    private fun artKey(metadata: MediaMetadata): String? =
        BITMAP_KEYS.firstNotNullOfOrNull { metadata.getBitmap(it) }?.let { "${it.width}x${it.height}" } ?: artUri(metadata)

    private fun artUri(metadata: MediaMetadata): String? =
        URI_KEYS.firstNotNullOfOrNull { key -> metadata.getString(key)?.takeIf { it.isNotBlank() } }

    /** The song's picture at the size shown, never larger (the original goes with the metadata). */
    private fun scaled(bitmap: Bitmap): Bitmap {
        val (width, height) = coverSize(bitmap.width, bitmap.height, coverPx)
        return if (width == bitmap.width && height == bitmap.height) bitmap else Bitmap.createScaledBitmap(bitmap, width, height, true)
    }

    /** A cover the music app gives by address ([coverSourceAllowed]). Unreadable or not an image → null. */
    private fun load(uri: Uri): Bitmap? {
        val resolver = app.contentResolver
        return try {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
            val options = BitmapFactory.Options().apply { inSampleSize = PlaylistCovers.sampleSize(bounds.outWidth, bounds.outHeight, coverPx) }
            resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, options) }
        } catch (e: IOException) {
            null
        } catch (e: RuntimeException) {
            // Not ours to read (SecurityException), a bad address, or another app's provider failing.
            null
        }
    }

    companion object {
        /** An app whose name Fortia cannot read (cached as such). */
        private const val NO_NAME = ""
        private val BITMAP_KEYS =listOf(MediaMetadata.METADATA_KEY_ALBUM_ART, MediaMetadata.METADATA_KEY_ART, MediaMetadata.METADATA_KEY_DISPLAY_ICON)
        private val URI_KEYS = listOf(MediaMetadata.METADATA_KEY_ALBUM_ART_URI, MediaMetadata.METADATA_KEY_ART_URI, MediaMetadata.METADATA_KEY_DISPLAY_ICON_URI)

        /**
         * The size to scale a [width]×[height] picture to so its shorter side is [maxPx] (it is
         * shown cropped to a square); pictures already that small keep their size.
         */
        fun coverSize(width: Int, height: Int, maxPx: Int): Pair<Int, Int> {
            val shorter = minOf(width, height)
            if (shorter <= maxPx || shorter <= 0) return width to height
            val scale = maxPx.toFloat() / shorter
            return (width * scale).roundToInt().coerceAtLeast(1) to (height * scale).roundToInt().coerceAtLeast(1)
        }

        /**
         * Whether a cover's address is the music app's own: a content provider of that app
         * ([providerPackage] of the authority) or a resource of that app. Nothing else is opened:
         * not other apps' providers, files or the network.
         */
        fun coverSourceAllowed(scheme: String?, authority: String?, sessionPackage: String, providerPackage: (authority: String) -> String?): Boolean =
            when (scheme) {
                ContentResolver.SCHEME_CONTENT -> authority != null && providerPackage(authority) == sessionPackage
                ContentResolver.SCHEME_ANDROID_RESOURCE -> authority == sessionPackage
                else -> false
            }

        /** Android's media keys through AudioManager: they reach the app playing, with no permission. */
        fun mediaKeys(context: Context): MediaKeys {
            val audio = context.applicationContext.getSystemService(AudioManager::class.java)
            return MediaKeys(SystemClock::uptimeMillis) { down, action, code ->
                audio.dispatchMediaKeyEvent(KeyEvent(down, SystemClock.uptimeMillis(), action, code, 0))
            }
        }
    }
}
