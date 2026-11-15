/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.utils

import com.dd3boh.outertune.utils.SignedDocumentTest.Companion.NOW
import com.dd3boh.outertune.utils.SignedDocumentTest.Companion.SEQ
import com.dd3boh.outertune.utils.SignedDocumentTest.Companion.key
import com.dd3boh.outertune.utils.SignedDocumentTest.Companion.pair
import com.dd3boh.outertune.utils.SignedDocumentTest.Companion.payload
import com.dd3boh.outertune.utils.SignedDocumentTest.Companion.sign
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.net.InetAddress
import java.net.ServerSocket
import java.util.Properties
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * The hosts are asked in order and the first with a document to believe wins (SignedSources): one that fails,
 * hangs, or serves something unsigned, forged or older is passed over. With fake hosts, and with real ones on
 * this machine for what only a real connection shows: a host that never answers.
 */
class SignedSourcesTest {

    private val main = pair()
    private val stranger = pair()
    private val keys = listOf(key(main, spare = false))
    private val good = sign(main, payload())
    private val newer = sign(main, payload(seq = SEQ + 60))

    private fun walk(sources: Map<String, SignedSources.Fetched>, highest: Long = 0, asked: MutableList<String> = mutableListOf()) =
        SignedSources.first(sources.keys.toList(), { asked += it; sources.getValue(it) }) {
            SignedDocument.open(it, SignedDocument.NEWS, keys, highest, emptySet(), NOW)
        }

    @Test
    fun `the first host that fails is passed over and the next one is taken, the rest never asked`() {
        val asked = mutableListOf<String>()
        val walk = walk(
            linkedMapOf(
                GIST to SignedSources.Fetched.Failed("HTTP 404"),
                SITE to SignedSources.Fetched.Text(good),
                WORKER to SignedSources.Fetched.Text(newer),
            ),
            asked = asked,
        )
        assertEquals(1, walk.found!!.index)
        assertEquals(SEQ, walk.found!!.accepted.seq)
        assertEquals(listOf(GIST, SITE), asked)
        assertEquals(listOf("host 1 (gist.githubusercontent.com): HTTP 404", "host 2 (intertune.app): taken, number $SEQ"), walk.notes)
    }

    @Test
    fun `a forged copy is refused and the next host is asked`() {
        val forged = good.replace(SignedDocumentTest.base64(payload().toByteArray()), SignedDocumentTest.base64(payload().replace("Signed", "Forged").toByteArray()))
        val walk = walk(
            linkedMapOf(
                GIST to SignedSources.Fetched.Text(forged),
                SITE to SignedSources.Fetched.Text(sign(stranger, payload())),
                WORKER to SignedSources.Fetched.Text(good),
            )
        )
        assertEquals(2, walk.found!!.index)
        assertEquals("host 1 (gist.githubusercontent.com): refused, the signature does not match", walk.notes[0])
        assertEquals("host 2 (intertune.app): refused, signed with a key this build does not know", walk.notes[1])
    }

    @Test
    fun `an older copy on the first host does not win over a newer one further down`() {
        val walk = walk(linkedMapOf(GIST to SignedSources.Fetched.Text(good), SITE to SignedSources.Fetched.Text(newer)), highest = SEQ + 30)
        assertEquals(1, walk.found!!.index)
        assertEquals(SEQ + 60, walk.found!!.accepted.seq)
    }

    @Test
    fun `nothing to believe anywhere is said, and whether any host answered with a document`() {
        val none = walk(linkedMapOf(GIST to SignedSources.Fetched.Failed("SocketTimeoutException: timeout"), SITE to SignedSources.Fetched.Text("<html>hotel wifi</html>")))
        assertNull(none.found)
        assertFalse("a sign-in page is no answer", none.answered)
        val old = walk(linkedMapOf(GIST to SignedSources.Fetched.Text(good)), highest = SEQ + 1)
        assertNull(old.found)
        assertTrue("an older signed copy is an answer", old.answered)
        val unsigned = walk(linkedMapOf(GIST to SignedSources.Fetched.Text(SignedDocumentTest.LEGACY)))
        assertNull("what builds before 0.13 read is not believed", unsigned.found)
        assertFalse(unsigned.answered)
    }

    @Test
    fun `the log names a host, never its address`() {
        assertEquals("gist.githubusercontent.com", SignedSources.host("$GIST?x=1"))
        assertEquals("not an address", SignedSources.host("javascript:alert(1)"))
        val walk = walk(linkedMapOf(GIST to SignedSources.Fetched.Failed("HTTP 404")))
        assertTrue(walk.notes.none { "ItzSkyeYT" in it || "polls.json" in it })
    }

    @Test
    fun `a host that never answers is cut off, and the next one is asked`() {
        LocalHost { path -> if (path == "/hangs") null else Reply(200, good.toByteArray()) }.use { server ->
            val client = OkHttpClient.Builder().readTimeout(1, TimeUnit.SECONDS).callTimeout(3, TimeUnit.SECONDS).build()
            val started = System.nanoTime()
            val walk = SignedSources.first(listOf("${server.base}/hangs", "${server.base}/polls.json"), { SignedSources.fetch(it, client) }) {
                SignedDocument.open(it, SignedDocument.NEWS, keys, 0, emptySet(), NOW)
            }
            val seconds = (System.nanoTime() - started) / 1e9
            assertEquals(1, walk.found?.index)
            assertTrue(walk.notes[0], walk.notes[0].startsWith("host 1 (127.0.0.1): SocketTimeoutException"))
            assertTrue("took $seconds s", seconds < 5)
        }
    }

    @Test
    fun `an error status, and a file larger than a document, are failures`() {
        LocalHost { path -> if (path == "/missing") Reply(404, "no".toByteArray()) else Reply(200, ByteArray(4096) { 'a'.code.toByte() }) }.use { server ->
            assertEquals(SignedSources.Fetched.Failed("HTTP 404"), SignedSources.fetch("${server.base}/missing"))
            assertEquals(SignedSources.Fetched.Failed("too large"), SignedSources.fetch("${server.base}/big", most = 1024))
            assertEquals(4096, (SignedSources.fetch("${server.base}/big", most = 4096) as SignedSources.Fetched.Text).body.length)
        }
        assertTrue(SignedSources.fetch("not an address") is SignedSources.Fetched.Failed)
    }

    @Test
    fun `the hosts after the gist are intertune app, the counter worker and Codeberg, in that order`() {
        val services = Properties().apply { File("../services.properties").reader().use { load(it) } }
        val mirrors = services.getProperty("polls.mirrors").split(' ').filter { it.isNotBlank() }
        assertEquals(
            listOf(
                "https://intertune.app/news/polls.json",
                "https://intertune-count.intertune.workers.dev/news/polls.json",
                "https://codeberg.org/ItzSkyeYT/intertune-news/raw/branch/main/news/polls.json",
            ),
            mirrors,
        )
        assertTrue(services.getProperty("polls.gistUrl").startsWith("https://gist.githubusercontent.com/"))
    }

    // ---- a host on this machine ----------------------------------------------------------------------------

    data class Reply(val code: Int, val body: ByteArray)

    /** A small HTTP server on 127.0.0.1: [answer] gives the reply for a path, or null to never answer. */
    class LocalHost(private val answer: (String) -> Reply?) : AutoCloseable {
        private val socket = ServerSocket(0, 16, InetAddress.getLoopbackAddress())
        val base = "http://127.0.0.1:${socket.localPort}"

        init {
            thread(isDaemon = true) {
                while (!socket.isClosed) {
                    val client = runCatching { socket.accept() }.getOrNull() ?: break
                    thread(isDaemon = true) {
                        client.use { c ->
                            val reader = c.getInputStream().bufferedReader()
                            val path = reader.readLine()?.split(' ')?.getOrNull(1) ?: return@use
                            while (reader.readLine()?.isNotEmpty() == true) Unit
                            val reply = answer(path)
                            if (reply == null) {
                                Thread.sleep(6_000)
                                return@use
                            }
                            c.getOutputStream().run {
                                write("HTTP/1.1 ${reply.code} X\r\nContent-Length: ${reply.body.size}\r\nConnection: close\r\n\r\n".toByteArray())
                                write(reply.body)
                                flush()
                            }
                        }
                    }
                }
            }
        }

        override fun close() = socket.close()
    }

    private companion object {
        const val GIST = "https://gist.githubusercontent.com/ItzSkyeYT/0123456789abcdef0123/raw/polls.json"
        const val SITE = "https://intertune.app/news/polls.json"
        const val WORKER = "https://intertune-count.intertune.workers.dev/news/polls.json"
    }
}
