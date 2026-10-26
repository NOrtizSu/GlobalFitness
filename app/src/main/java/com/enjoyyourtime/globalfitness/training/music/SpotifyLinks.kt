package com.enjoyyourtime.globalfitness.training.music

import android.content.ActivityNotFoundException
import com.enjoyyourtime.globalfitness.wearable.banglejs.MiniJson
import java.net.URI
import java.net.URISyntaxException
import java.net.URLEncoder

/** A playlist's name and cover as Spotify's oEmbed endpoint gives them. */
data class PlaylistEmbed(val title: String, val coverUrl: String?)

/**
 * Links to public Spotify playlists and their oEmbed data: no account, key or Web API (Spotify keeps
 * that for large partners). Any installed Spotify app opens [appUri]; [webUrl] opens the web player
 * (which offers the app) in the browser.
 */
object SpotifyLinks {
    private val PLAYLIST_ID = Regex("[A-Za-z0-9]{22}")

    /** Covers are only loaded from Spotify's image servers, over HTTPS (as the privacy policy says). */
    private val COVER_HOSTS = listOf("scdn.co", "spotifycdn.com")

    fun isPlaylistId(id: String): Boolean = PLAYLIST_ID.matches(id)

    fun appUri(id: String): String = "spotify:playlist:$id"

    fun webUrl(id: String): String = "https://open.spotify.com/playlist/$id"

    /**
     * Opens the playlist with [start]: in any installed Spotify app, else on Spotify's website (which
     * offers the app). False when neither link could be opened: [start] threw for both because no
     * app handles it or the one that does cannot be started from here.
     */
    fun open(id: String, start: (link: String) -> Unit): Boolean {
        for (link in listOf(appUri(id), webUrl(id))) {
            try {
                start(link)
                return true
            } catch (e: ActivityNotFoundException) {
                // Next link.
            } catch (e: SecurityException) {
                // Next link.
            }
        }
        return false
    }

    fun oEmbedUrl(id: String): String = "https://open.spotify.com/oembed?url=" + URLEncoder.encode(webUrl(id), "UTF-8")

    /** The title (required) and the cover of an oEmbed answer; null when it is not one. */
    fun parseEmbed(json: String): PlaylistEmbed? {
        val root = try {
            MiniJson.parse(json)
        } catch (e: IllegalArgumentException) {
            return null
        } as? Map<*, *> ?: return null
        val title = (root["title"] as? String)?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        return PlaylistEmbed(title, (root["thumbnail_url"] as? String)?.takeIf(::isCoverUrl))
    }

    fun isCoverUrl(url: String): Boolean {
        val uri = try {
            URI(url)
        } catch (e: URISyntaxException) {
            return false
        }
        val host = uri.host?.lowercase() ?: return false
        return uri.scheme == "https" && COVER_HOSTS.any { host == it || host.endsWith(".$it") }
    }
}
