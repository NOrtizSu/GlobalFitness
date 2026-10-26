package com.enjoyyourtime.globalfitness.training.music

import android.graphics.BitmapFactory
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/** A playlist's live name and cover from Spotify; null while unknown (the catalogue name and a placeholder show). */
data class PlaylistArt(val title: String? = null, val cover: ImageBitmap? = null)

/** Reads a public address for [PlaylistCovers]: the body, or null unless the answer is a 200 of at most [maxBytes]. */
fun interface PlaylistFetcher {
    @Throws(IOException::class)
    fun get(url: String, maxBytes: Int): ByteArray?
}

/**
 * [PlaylistFetcher] with the platform's HttpURLConnection. Redirects are not followed, so only the
 * hosts asked for are ever reached (open.spotify.com and Spotify's image servers, see
 * [SpotifyLinks]); the User-Agent names the app and its version instead of the phone's model.
 */
class HttpPlaylistFetcher(private val userAgent: String) : PlaylistFetcher {
    override fun get(url: String, maxBytes: Int): ByteArray? {
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.instanceFollowRedirects = false
        connection.useCaches = false
        connection.connectTimeout = TIMEOUT_MS
        connection.readTimeout = TIMEOUT_MS
        connection.setRequestProperty("User-Agent", userAgent)
        try {
            if (connection.responseCode != HttpURLConnection.HTTP_OK || connection.contentLengthLong > maxBytes) return null
            val out = ByteArrayOutputStream()
            connection.inputStream.use { input ->
                val buffer = ByteArray(8 * 1024)
                var n = input.read(buffer)
                while (n >= 0) {
                    out.write(buffer, 0, n)
                    if (out.size() > maxBytes) return null
                    n = input.read(buffer)
                }
            }
            return out.toByteArray()
        } finally {
            connection.disconnect()
        }
    }

    private companion object {
        const val TIMEOUT_MS = 10_000
    }
}

/**
 * Names and covers of the suggested playlists, from Spotify's public oEmbed endpoint and image
 * servers (no account or key), kept in [dir] (in the cache folder). Only while [allowed] (the user
 * accepted the covers and the suggestions are on): otherwise nothing is asked, and what was loaded
 * is forgotten and deleted.
 *
 * The oEmbed answer is asked again after [MAX_AGE_MS]; the cover only when Spotify names another
 * address (its image addresses name their content), so the cover file starts with the address it
 * was downloaded from. A playlist done for the day is not asked again in this process; failures
 * are silent and retried after [RETRY_MS] at most, while the cached or catalogue values stay on
 * screen. Files no suggestion asked for in [SWEEP_AGE_MS] are deleted at the first load of a run.
 * At most [MAX_KEPT] playlists are kept in memory, the most recently asked for.
 */
class PlaylistCovers(
    private val dir: File,
    private val scope: CoroutineScope,
    private val allowed: StateFlow<Boolean>,
    private val fetcher: PlaylistFetcher,
    /** Pixels of the largest cover on screen: pictures are decoded at most about twice as big. */
    maxCoverPx: Int,
    private val io: CoroutineDispatcher = Dispatchers.IO,
    private val clock: () -> Long = System::currentTimeMillis,
    private val decode: (bytes: ByteArray, offset: Int, length: Int) -> ImageBitmap? = { bytes, offset, length ->
        decodeCover(bytes, offset, length, maxCoverPx)
    },
) {
    private val _art = MutableStateFlow<Map<String, PlaylistArt>>(emptyMap())

    /** What is known so far, by playlist id. */
    val art: StateFlow<Map<String, PlaylistArt>> = _art.asStateFlow()

    // Main thread only (request(), the allowed collector and the loads resume on [scope], the app
    // scope on Main), except [generation], also read on [io] before writing a file.
    @Volatile private var generation = 0
    private var loads = SupervisorJob(scope.coroutineContext[Job])
    private val wanted = LinkedHashSet<String>()
    private val loading = HashSet<String>()
    private val done = HashSet<String>()
    private val tried = HashMap<String, Long>()
    private var swept = false

    private val disk = Mutex()
    private val network = Semaphore(MAX_DOWNLOADS)

    init {
        // Allowed (again): the playlists on screen load at once, not at the next resume.
        scope.launch { allowed.collect { on -> if (on) request(wanted.toList()) else forget() } }
    }

    /** Loads what [ids] still miss: the cached copy at once, then Spotify's when it is missing or old. */
    fun request(ids: Collection<String>) {
        val valid = ids.filter(SpotifyLinks::isPlaylistId)
        keep(valid)
        if (!allowed.value) return
        val now = clock()
        val g = generation
        for (id in valid) {
            if (id in loading || id in done) continue
            if (tried[id]?.let { now - it < RETRY_MS } == true) continue
            loading += id
            scope.launch(loads) {
                try {
                    load(id, g)
                } finally {
                    if (generation == g) loading -= id
                }
            }
        }
    }

    /** [ids] become the most recently asked for; older ones beyond [MAX_KEPT] leave memory (not the disk). */
    private fun keep(ids: List<String>) {
        for (id in ids) {
            wanted -= id
            wanted += id
        }
        while (wanted.size > MAX_KEPT) {
            val old = wanted.first()
            wanted -= old
            done -= old
            tried -= old
            _art.update { it - old }
        }
    }

    /** Stops every load, empties the memory and deletes the folder (the user turned the covers off). */
    private fun forget() {
        generation++
        loads.cancel()
        loads = SupervisorJob(scope.coroutineContext[Job])
        loading.clear()
        done.clear()
        tried.clear()
        swept = false
        _art.value = emptyMap()
        scope.launch { withContext(io) { disk.withLock { dir.deleteRecursively() } } }
    }

    private suspend fun load(id: String, g: Int) {
        val sweep = !swept
        swept = true
        val cached = withContext(io) {
            if (sweep) stored(g) { sweep() }
            readCache(id)
        }
        publish(id, g, cached.embed?.title, cached.cover)
        if (cached.fresh && cached.coverCurrent) {
            done += id
            return
        }
        tried[id] = clock()
        val fetched = network.withPermit { withContext(io) { refresh(id, cached, g) } } ?: return
        publish(id, g, fetched.title, fetched.cover)
        if (fetched.complete) done += id
    }

    private fun publish(id: String, g: Int, title: String?, cover: ImageBitmap?) {
        if (generation != g || id !in wanted || (title == null && cover == null)) return
        _art.update { map ->
            val old = map[id]
            map + (id to PlaylistArt(title ?: old?.title, cover ?: old?.cover))
        }
    }

    private class Cached(val embed: PlaylistEmbed?, val fresh: Boolean, val cover: ImageBitmap?, val coverUrl: String?) {
        /** The cover on file is the one Spotify names (or Spotify names none). */
        val coverCurrent: Boolean
            get() = embed != null && (embed.coverUrl == null || (cover != null && coverUrl == embed.coverUrl))
    }

    private class Fetched(val title: String, val cover: ImageBitmap?, val complete: Boolean)

    private fun json(id: String) = File(dir, "$id.json")

    private fun image(id: String) = File(dir, "$id.img")

    private fun readCache(id: String): Cached {
        val json = json(id)
        val embed = readBytes(json)?.let { SpotifyLinks.parseEmbed(it.toString(Charsets.UTF_8)) }
        val fresh = embed != null && clock() - json.lastModified() < MAX_AGE_MS
        val bytes = readBytes(image(id))
        val split = bytes?.indexOf(NEWLINE) ?: -1
        if (bytes == null || split <= 0) return Cached(embed, fresh, null, null)
        val cover = decode(bytes, split + 1, bytes.size - split - 1)
        return Cached(embed, fresh, cover, String(bytes, 0, split, Charsets.UTF_8))
    }

    /**
     * Asks Spotify for what [cached] misses: the oEmbed answer when it is old, then the cover when
     * the one on file is not the one named. Null when nothing new could be read.
     */
    private suspend fun refresh(id: String, cached: Cached, g: Int): Fetched? = try {
        var embed = cached.embed
        var fresh = cached.fresh
        if (!fresh) {
            val bytes = fetcher.get(SpotifyLinks.oEmbedUrl(id), MAX_JSON_BYTES)
            val parsed = bytes?.let { SpotifyLinks.parseEmbed(it.toString(Charsets.UTF_8)) }
            if (bytes != null && parsed != null && stored(g) { write(json(id), bytes) }) {
                embed = parsed
                fresh = true
            }
        }
        embed?.let { e ->
            val url = e.coverUrl
            var cover = cached.cover
            var current = url == null || (cover != null && cached.coverUrl == url)
            if (url != null && !current) {
                val data = fetcher.get(url, MAX_IMAGE_BYTES)
                val decoded = data?.let { decode(it, 0, it.size) }
                if (data != null && decoded != null && stored(g) { write(image(id), (url + "\n").toByteArray(Charsets.UTF_8), data) }) {
                    cover = decoded
                    current = true
                }
            } else if (url != null && fresh && !cached.fresh) {
                // Still the same picture: kept from the sweep as long as its playlist is suggested.
                stored(g) { image(id).setLastModified(clock()) }
            }
            Fetched(e.title, cover, complete = fresh && current)
        }
    } catch (e: IOException) {
        null
    }

    /** Runs a change of the folder unless the covers were turned off since the load started. */
    private suspend fun stored(g: Int, change: () -> Unit): Boolean = disk.withLock {
        if (generation != g) return@withLock false
        try {
            dir.mkdirs()
            change()
            true
        } catch (e: IOException) {
            false
        }
    }

    /** Drops the files of playlists no suggestion asked for in [SWEEP_AGE_MS]. */
    private fun sweep() {
        val cutoff = clock() - SWEEP_AGE_MS
        dir.listFiles()?.forEach { if (it.lastModified() < cutoff) it.delete() }
    }

    private fun write(file: File, vararg parts: ByteArray) {
        val tmp = File(file.path + ".tmp")
        tmp.outputStream().use { out -> parts.forEach(out::write) }
        if (!tmp.renameTo(file)) {
            tmp.copyTo(file, overwrite = true)
            tmp.delete()
        }
    }

    private fun readBytes(file: File): ByteArray? = try {
        if (file.isFile) file.readBytes() else null
    } catch (e: IOException) {
        null
    }

    companion object {
        const val DIR = "spotify_playlists"

        /** The largest cover on screen (the Entreno tab's tiles), in dp. */
        const val LARGEST_COVER_DP = 112
        const val MAX_AGE_MS = 7L * 24 * 3_600_000
        const val RETRY_MS = 10L * 60_000
        const val SWEEP_AGE_MS = 30L * 24 * 3_600_000
        const val MAX_KEPT = 10
        const val MAX_JSON_BYTES = 64 * 1024
        const val MAX_IMAGE_BYTES = 1024 * 1024
        private const val MAX_DOWNLOADS = 2
        private const val NEWLINE = '\n'.code.toByte()

        /**
         * The largest power of two to divide a [width]×[height] picture by so its shorter side stays
         * at least [maxPx] (what the screen shows at most): the result is under twice that.
         */
        fun sampleSize(width: Int, height: Int, maxPx: Int): Int {
            var sample = 1
            while (minOf(width, height) / (sample * 2) >= maxPx) sample *= 2
            return sample
        }

        /** Reads the picture's size first, then decodes it scaled down by [sampleSize]. */
        fun decodeCover(bytes: ByteArray, offset: Int, length: Int, maxPx: Int): ImageBitmap? {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, offset, length, bounds)
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
            val options = BitmapFactory.Options().apply { inSampleSize = sampleSize(bounds.outWidth, bounds.outHeight, maxPx.coerceAtLeast(1)) }
            return BitmapFactory.decodeByteArray(bytes, offset, length, options)?.asImageBitmap()
        }
    }
}
