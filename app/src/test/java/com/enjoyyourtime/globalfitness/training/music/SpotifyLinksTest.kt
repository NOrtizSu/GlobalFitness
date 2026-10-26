package com.enjoyyourtime.globalfitness.training.music

import android.content.ActivityNotFoundException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SpotifyLinksTest {
    private val id = "37i9dQZF1DX76Wlfdnj7AP"

    /** Spotify's answer for Beast Mode (2026-10-26). */
    private val beastMode = """{"html":"<iframe style=\"border-radius: 12px\" width=\"100%\" height=\"352\" title=\"Spotify Embed: Beast Mode\" """ +
        """frameborder=\"0\" allowfullscreen allow=\"autoplay; clipboard-write; encrypted-media; fullscreen; picture-in-picture\" """ +
        """loading=\"lazy\" src=\"https://open.spotify.com/embed/playlist/37i9dQZF1DX76Wlfdnj7AP?utm_source=oembed\"></iframe>",""" +
        """"iframe_url":"https://open.spotify.com/embed/playlist/37i9dQZF1DX76Wlfdnj7AP?utm_source=oembed","width":456,"height":352,""" +
        """"version":"1.0","provider_name":"Spotify","provider_url":"https://spotify.com","type":"rich","title":"Beast Mode",""" +
        """"thumbnail_url":"https://i.scdn.co/image/ab67706f00000002f367481f06b565250cc7b139","thumbnail_width":300,"thumbnail_height":300}"""

    @Test
    fun linksOpenTheAppTheWebAndTheEmbedData() {
        assertEquals("spotify:playlist:$id", SpotifyLinks.appUri(id))
        assertEquals("https://open.spotify.com/playlist/$id", SpotifyLinks.webUrl(id))
        assertEquals("https://open.spotify.com/oembed?url=https%3A%2F%2Fopen.spotify.com%2Fplaylist%2F$id", SpotifyLinks.oEmbedUrl(id))
    }

    @Test
    fun onlyPlaylistIdsAreAccepted() {
        assertTrue(SpotifyLinks.isPlaylistId(id))
        assertFalse(SpotifyLinks.isPlaylistId(""))
        assertFalse(SpotifyLinks.isPlaylistId(id.drop(1)))
        assertFalse(SpotifyLinks.isPlaylistId("$id/x"))
        assertFalse(SpotifyLinks.isPlaylistId("../../37i9dQZF1DX76Wlfd"))
        assertFalse(SpotifyLinks.isPlaylistId("37i9dQZF1DX76Wlfdnj7A "))
    }

    @Test
    fun theTitleAndCoverAreReadFromSpotifysAnswer() {
        val embed = SpotifyLinks.parseEmbed(beastMode)
        assertEquals(PlaylistEmbed("Beast Mode", "https://i.scdn.co/image/ab67706f00000002f367481f06b565250cc7b139"), embed)
        assertEquals("Yoga & Méditation \"Zen\"", SpotifyLinks.parseEmbed("""{"title":" Yoga & Méditation \"Zen\" "}""")?.title)
        assertEquals("💪 Gym", SpotifyLinks.parseEmbed("""{"title":"💪 Gym","thumbnail_url":null}""")?.title)
    }

    @Test
    fun aCoverFromAnywhereElseIsIgnored() {
        fun cover(url: String) = SpotifyLinks.parseEmbed("""{"title":"T","thumbnail_url":"$url"}""")?.coverUrl
        assertEquals("https://mosaic.scdn.co/300/abc", cover("https://mosaic.scdn.co/300/abc"))
        assertEquals("https://image-cdn-ak.spotifycdn.com/image/abc", cover("https://image-cdn-ak.spotifycdn.com/image/abc"))
        assertNull(cover("http://i.scdn.co/image/abc"))
        assertNull(cover("https://example.com/image.jpg"))
        assertNull(cover("https://scdn.co.example.com/image.jpg"))
        assertNull(cover("https://evilscdn.co/image.jpg"))
        assertNull(cover("not a url"))
    }

    @Test
    fun anythingButAnEmbedWithATitleIsRejected() {
        assertNull(SpotifyLinks.parseEmbed(""))
        assertNull(SpotifyLinks.parseEmbed("<html>Too many requests</html>"))
        assertNull(SpotifyLinks.parseEmbed("""{"title":"""))
        assertNull(SpotifyLinks.parseEmbed("""["Beast Mode"]"""))
        assertNull(SpotifyLinks.parseEmbed("""{"error":"not found"}"""))
        assertNull(SpotifyLinks.parseEmbed("""{"title":"   "}"""))
        assertNull(SpotifyLinks.parseEmbed("""{"title":42}"""))
    }

    @Test
    fun aPlaylistOpensInTheAppElseOnTheWebsite() {
        val tried = mutableListOf<String>()
        assertTrue(SpotifyLinks.open(id) { tried += it })
        assertEquals(listOf(SpotifyLinks.appUri(id)), tried)

        tried.clear()
        assertTrue(SpotifyLinks.open(id) { link -> tried += link; if (link == SpotifyLinks.appUri(id)) throw ActivityNotFoundException() })
        assertEquals(listOf(SpotifyLinks.appUri(id), SpotifyLinks.webUrl(id)), tried)

        // An app that cannot be started from here (not exported, a permission) is skipped too.
        tried.clear()
        assertTrue(SpotifyLinks.open(id) { link -> tried += link; if (link == SpotifyLinks.appUri(id)) throw SecurityException("not exported") })
        assertEquals(listOf(SpotifyLinks.appUri(id), SpotifyLinks.webUrl(id)), tried)
    }

    @Test
    fun noAppForEitherLinkIsReported() {
        assertFalse(SpotifyLinks.open(id) { throw ActivityNotFoundException() })
        assertFalse(SpotifyLinks.open(id) { link -> if (link == SpotifyLinks.appUri(id)) throw SecurityException("no") else throw ActivityNotFoundException() })
    }
}
