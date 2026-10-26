package com.enjoyyourtime.globalfitness.training.music

import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.util.Collections
import kotlin.concurrent.thread

/** [HttpPlaylistFetcher] against a small local HTTP server. */
class HttpPlaylistFetcherTest {
    /** One canned answer per path: status line, headers and body. */
    private class Answer(val status: String, val headers: List<String> = emptyList(), val body: ByteArray = ByteArray(0))

    private val answers = HashMap<String, Answer>()

    /** Request line and headers of every request received. */
    private val requests: MutableList<List<String>> = Collections.synchronizedList(mutableListOf())

    private val server = ServerSocket(0, 8, InetAddress.getLoopbackAddress())
    private val base = "http://127.0.0.1:${server.localPort}"

    private val serving = thread(isDaemon = true) {
        while (!server.isClosed) {
            val socket = try {
                server.accept()
            } catch (e: IOException) {
                break
            }
            try {
                socket.use { s ->
                    val reader = s.getInputStream().bufferedReader(Charsets.ISO_8859_1)
                    val lines = generateSequence { reader.readLine() }.takeWhile { it.isNotEmpty() }.toList()
                    requests += lines
                    val path = lines.firstOrNull()?.split(" ")?.getOrNull(1).orEmpty()
                    val answer = answers[path] ?: Answer("404 Not Found", listOf("Content-Length: 0"))
                    val out = s.getOutputStream()
                    val head = (listOf("HTTP/1.1 ${answer.status}", "Connection: close") + answer.headers).joinToString("\r\n") + "\r\n\r\n"
                    out.write(head.toByteArray(Charsets.ISO_8859_1))
                    out.write(answer.body)
                    out.flush()
                }
            } catch (e: IOException) {
                // The client hung up early (an answer over the cap): next request.
            }
        }
    }

    private val fetcher = HttpPlaylistFetcher("Fortia/9.9.9")

    @After
    fun stop() {
        server.close()
        serving.join(2_000)
    }

    @Test
    fun aPlainAnswerIsReadWithTheAppsUserAgent() {
        val body = """{"title":"Beast Mode"}""".toByteArray()
        answers["/oembed"] = Answer("200 OK", listOf("Content-Length: ${body.size}", "Content-Type: application/json"), body)
        assertArrayEquals(body, fetcher.get("$base/oembed", 1024))
        val headers = requests.single()
        assertEquals("GET /oembed HTTP/1.1", headers.first())
        assertEquals(listOf("User-Agent: Fortia/9.9.9"), headers.filter { it.startsWith("User-Agent:", ignoreCase = true) })
    }

    @Test
    fun redirectsAreNotFollowed() {
        answers["/moved"] = Answer("302 Found", listOf("Location: $base/elsewhere", "Content-Length: 0"))
        answers["/elsewhere"] = Answer("200 OK", listOf("Content-Length: 2"), "ok".toByteArray())
        assertNull(fetcher.get("$base/moved", 1024))
        assertEquals(listOf("GET /moved HTTP/1.1"), requests.map { it.first() })
    }

    @Test
    fun anAnswerOverTheSizeCapIsDropped() {
        // Announced too big: dropped before reading.
        answers["/announced"] = Answer("200 OK", listOf("Content-Length: 2048"), ByteArray(2048))
        assertNull(fetcher.get("$base/announced", 1024))
        // Not announced (read until the connection closes): dropped once past the cap.
        answers["/streamed"] = Answer("200 OK", body = ByteArray(64 * 1024))
        assertNull(fetcher.get("$base/streamed", 1024))
        // At the cap: kept.
        answers["/exact"] = Answer("200 OK", listOf("Content-Length: 1024"), ByteArray(1024) { 7 })
        assertArrayEquals(ByteArray(1024) { 7 }, fetcher.get("$base/exact", 1024))
    }

    @Test
    fun anythingButA200IsNothing() {
        assertNull(fetcher.get("$base/missing", 1024))
        answers["/limited"] = Answer("429 Too Many Requests", listOf("Content-Length: 4"), "slow".toByteArray())
        assertNull(fetcher.get("$base/limited", 1024))
    }
}
