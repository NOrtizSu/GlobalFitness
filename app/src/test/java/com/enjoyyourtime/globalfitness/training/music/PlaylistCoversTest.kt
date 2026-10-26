package com.enjoyyourtime.globalfitness.training.music

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.ImageBitmapConfig
import androidx.compose.ui.graphics.colorspace.ColorSpace
import androidx.compose.ui.graphics.colorspace.ColorSpaces
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException

@OptIn(ExperimentalCoroutinesApi::class)
class PlaylistCoversTest {
    @get:Rule
    val folder = TemporaryFolder()

    private val id = "37i9dQZF1DX76Wlfdnj7AP"
    private val other = "37i9dQZF1DXdxcBWuJkbcy"
    private val coverA = "https://i.scdn.co/image/aaa"
    private val coverB = "https://i.scdn.co/image/bbb"

    /** Spotify as the tests want it: the answer for each address (null: not a 200), and what was asked. */
    private inner class FakeSpotify : PlaylistFetcher {
        val answers = HashMap<String, ByteArray?>()
        val asked = mutableListOf<String>()
        var offline = false

        fun embed(playlist: String, title: String, cover: String?) {
            val thumbnail = cover?.let { ""","thumbnail_url":"$it"""" }.orEmpty()
            answers[SpotifyLinks.oEmbedUrl(playlist)] = """{"title":"$title"$thumbnail}""".toByteArray()
        }

        fun image(url: String, content: String?) {
            answers[url] = content?.toByteArray()
        }

        override fun get(url: String, maxBytes: Int): ByteArray? {
            asked += url
            if (offline) throw IOException("offline")
            return answers[url]?.takeIf { it.size <= maxBytes }
        }
    }

    /** A decoded cover: the test images are short texts. */
    private class Picture(val content: String) : ImageBitmap {
        override val width = 300
        override val height = 300
        override val colorSpace: ColorSpace = ColorSpaces.Srgb
        override val hasAlpha = false
        override val config = ImageBitmapConfig.Argb8888

        override fun readPixels(buffer: IntArray, startX: Int, startY: Int, width: Int, height: Int, bufferOffset: Int, stride: Int) = Unit

        override fun prepareToDraw() = Unit
    }

    private val spotify = FakeSpotify()
    private val allowed = MutableStateFlow(true)
    private val dir by lazy { File(folder.root, PlaylistCovers.DIR) }

    /** Added to the real clock (the cache files have real modification times). */
    private var later = 0L

    /**
     * A new run of the app: nothing in memory, the cache folder as the last one left it. It works in
     * the background scope, so runCurrent() (not advanceUntilIdle()) runs its loads.
     */
    private fun TestScope.covers() = PlaylistCovers(
        dir = dir,
        scope = backgroundScope,
        allowed = allowed,
        fetcher = spotify,
        maxCoverPx = 300,
        io = StandardTestDispatcher(testScheduler),
        clock = { System.currentTimeMillis() + later },
        decode = { bytes, offset, length -> String(bytes, offset, length).takeIf { it.startsWith("img") }?.let(::Picture) },
    )

    private fun PlaylistCovers.cover(playlist: String = id) = (art.value[playlist]?.cover as? Picture)?.content

    private fun age(file: File, days: Int) = file.setLastModified(System.currentTimeMillis() - days * 86_400_000L)

    @Test
    fun nothingIsAskedUntilTheUserAllowsTheCovers() = runTest {
        allowed.value = false
        spotify.embed(id, "Beast Mode", coverA)
        spotify.image(coverA, "img A")
        val covers = covers()
        covers.request(listOf(id))
        runCurrent()
        assertTrue(spotify.asked.isEmpty())
        assertTrue(covers.art.value.isEmpty())
        // Allowed (the question at start): the playlists on screen load at once.
        allowed.value = true
        runCurrent()
        assertEquals(listOf(SpotifyLinks.oEmbedUrl(id), coverA), spotify.asked)
        assertEquals("Beast Mode", covers.art.value[id]?.title)
        assertEquals("img A", covers.cover())
    }

    @Test
    fun turningTheCoversOffForgetsAndDeletesThem() = runTest {
        spotify.embed(id, "Beast Mode", coverA)
        spotify.image(coverA, "img A")
        val covers = covers()
        covers.request(listOf(id))
        runCurrent()
        assertTrue(File(dir, "$id.img").isFile)
        allowed.value = false
        runCurrent()
        assertTrue(covers.art.value.isEmpty())
        assertFalse(dir.exists())
        spotify.asked.clear()
        covers.request(listOf(id))
        runCurrent()
        assertTrue(spotify.asked.isEmpty())
    }

    @Test
    fun aFreshCacheIsNotAskedAgain() = runTest {
        spotify.embed(id, "Beast Mode", coverA)
        spotify.image(coverA, "img A")
        covers().request(listOf(id))
        runCurrent()
        spotify.asked.clear()
        val next = covers()
        next.request(listOf(id))
        runCurrent()
        assertTrue(spotify.asked.isEmpty())
        assertEquals("Beast Mode", next.art.value[id]?.title)
        assertEquals("img A", next.cover())
    }

    @Test
    fun anOldAnswerIsAskedAgainButTheSameCoverIsNotDownloaded() = runTest {
        spotify.embed(id, "Beast Mode", coverA)
        spotify.image(coverA, "img A")
        covers().request(listOf(id))
        runCurrent()
        age(File(dir, "$id.json"), days = 8)
        age(File(dir, "$id.img"), days = 8)
        spotify.asked.clear()
        spotify.embed(id, "Beast Mode 2", coverA)
        val next = covers()
        next.request(listOf(id))
        runCurrent()
        assertEquals(listOf(SpotifyLinks.oEmbedUrl(id)), spotify.asked)
        assertEquals("Beast Mode 2", next.art.value[id]?.title)
        assertEquals("img A", next.cover())
        // The picture is still in use: touched, so the sweep keeps it.
        assertTrue(System.currentTimeMillis() - File(dir, "$id.img").lastModified() < 86_400_000L)
    }

    @Test
    fun aNewCoverIsDownloadedEvenWhenTheFirstTryFailed() = runTest {
        spotify.embed(id, "Beast Mode", coverA)
        spotify.image(coverA, "img A")
        covers().request(listOf(id))
        runCurrent()
        // A week later Spotify names another cover, which cannot be downloaded this time.
        age(File(dir, "$id.json"), days = 8)
        spotify.embed(id, "Beast Mode", coverB)
        spotify.image(coverB, null)
        val second = covers()
        second.request(listOf(id))
        runCurrent()
        assertEquals("img A", second.cover())
        // The next run has a fresh answer naming B and the picture of A: B is downloaded, not kept as A.
        spotify.asked.clear()
        spotify.image(coverB, "img B")
        val third = covers()
        third.request(listOf(id))
        runCurrent()
        assertEquals(listOf(coverB), spotify.asked)
        assertEquals("img B", third.cover())
        assertTrue(String(File(dir, "$id.img").readBytes()).startsWith("$coverB\n"))
    }

    @Test
    fun anAnswerWithoutACoverIsDoneForTheWeek() = runTest {
        spotify.embed(id, "Beast Mode", cover = null)
        val covers = covers()
        covers.request(listOf(id))
        runCurrent()
        assertEquals("Beast Mode", covers.art.value[id]?.title)
        assertNull(covers.art.value[id]?.cover)
        spotify.asked.clear()
        later = PlaylistCovers.RETRY_MS + 1
        covers.request(listOf(id))
        covers().request(listOf(id))
        runCurrent()
        assertTrue(spotify.asked.isEmpty())
    }

    @Test
    fun failuresAreRetriedAfterTenMinutesAtMost() = runTest {
        spotify.offline = true
        val covers = covers()
        covers.request(listOf(id))
        runCurrent()
        assertEquals(1, spotify.asked.size)
        covers.request(listOf(id))
        runCurrent()
        assertEquals(1, spotify.asked.size)
        spotify.offline = false
        spotify.embed(id, "Beast Mode", coverA)
        spotify.image(coverA, "img A")
        later = PlaylistCovers.RETRY_MS + 1
        covers.request(listOf(id))
        runCurrent()
        assertEquals("img A", covers.cover())
    }

    @Test
    fun anAnswerTooBigOrNotAPictureIsIgnored() = runTest {
        spotify.embed(id, "Beast Mode", coverA)
        spotify.answers[coverA] = ByteArray(PlaylistCovers.MAX_IMAGE_BYTES + 1)
        spotify.embed(other, "Gym Hits", coverB)
        spotify.image(coverB, "not a picture")
        val covers = covers()
        covers.request(listOf(id, other))
        runCurrent()
        assertEquals("Beast Mode", covers.art.value[id]?.title)
        assertNull(covers.cover(id))
        assertNull(covers.cover(other))
        assertFalse(File(dir, "$id.img").exists())
        assertFalse(File(dir, "$other.img").exists())
    }

    @Test
    fun theSweepDropsWhatNoSuggestionAskedForInAMonth() = runTest {
        dir.mkdirs()
        val old = File(dir, "$other.json").apply { writeText("{}") }
        val oldImage = File(dir, "$other.img").apply { writeText("x") }
        val recent = File(dir, "37i9dQZF1DX70RN3TfWWJh.json").apply { writeText("{}") }
        age(old, days = 31)
        age(oldImage, days = 31)
        age(recent, days = 20)
        spotify.embed(id, "Beast Mode", coverA)
        spotify.image(coverA, "img A")
        covers().request(listOf(id))
        runCurrent()
        assertFalse(old.exists())
        assertFalse(oldImage.exists())
        assertTrue(recent.exists())
        assertTrue(File(dir, "$id.json").exists())
    }

    @Test
    fun onlyTheLastTenPlaylistsStayInMemory() = runTest {
        val ids = WorkoutPlaylists.all.take(15).map { it.id }
        for (p in ids) {
            spotify.embed(p, "Name $p", "https://i.scdn.co/image/$p")
            spotify.image("https://i.scdn.co/image/$p", "img $p")
        }
        val covers = covers()
        ids.chunked(5).forEach { day ->
            covers.request(day)
            runCurrent()
        }
        assertEquals(ids.takeLast(PlaylistCovers.MAX_KEPT).toSet(), covers.art.value.keys)
    }

    @Test
    fun picturesAreDecodedAtMostAboutTwiceTheLargestTile() {
        assertEquals(1, PlaylistCovers.sampleSize(300, 300, 294))
        assertEquals(2, PlaylistCovers.sampleSize(640, 640, 294))
        assertEquals(1, PlaylistCovers.sampleSize(640, 640, 336))
        assertEquals(4, PlaylistCovers.sampleSize(3000, 2000, 336))
        for (side in listOf(100, 336, 337, 671, 672, 1000, 2400, 6000)) {
            val sample = PlaylistCovers.sampleSize(side, side, 336)
            assertTrue("$side / $sample", side / sample < 2 * 336 || sample == 1)
            assertTrue("$side / $sample", side / sample >= 336 || sample == 1)
        }
    }
}
