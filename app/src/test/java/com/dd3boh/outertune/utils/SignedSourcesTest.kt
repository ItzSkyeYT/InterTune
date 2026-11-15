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
import java.util.Collections
import java.util.Properties
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlinx.coroutines.runBlocking

/**
 * Every host is asked at once and the newest document to believe wins, wherever it is (SignedSources): one that
 * fails, hangs, or serves something unsigned, forged or older counts for nothing, and the order only decides
 * between copies with the same number. With fake hosts, and with real ones on this machine for what only a real
 * connection shows: hosts that never answer, waited for together.
 */
class SignedSourcesTest {

    private val main = pair()
    private val stranger = pair()
    private val keys = listOf(key(main, spare = false))
    private val good = sign(main, payload())
    private val newer = sign(main, payload(seq = SEQ + 60))

    private fun walk(
        sources: Map<String, SignedSources.Fetched>,
        highest: Long = 0,
        asked: MutableList<String> = Collections.synchronizedList(mutableListOf()),
        lastResort: Map<String, SignedSources.Fetched> = emptyMap(),
    ) = runBlocking {
        val all = sources + lastResort
        SignedSources.best(sources.keys.toList(), { asked += it; all.getValue(it) }, lastResort.keys.toList()) {
            SignedDocument.open(it, SignedDocument.NEWS, keys, highest, emptySet(), NOW)
        }
    }

    @Test
    fun `Codeberg is asked only when the other three give nothing to believe`() {
        val asked = Collections.synchronizedList(mutableListOf<String>())
        val walk = walk(
            linkedMapOf(
                GIST to SignedSources.Fetched.Failed("HTTP 404"),
                SITE to SignedSources.Fetched.Text("<html>hotel wifi</html>"),
                WORKER to SignedSources.Fetched.Failed("SocketTimeoutException: timeout"),
            ),
            asked = asked,
            lastResort = linkedMapOf(CODEBERG to SignedSources.Fetched.Text(good)),
        )
        assertEquals(3, walk.found!!.index)
        assertEquals(setOf(GIST, SITE, WORKER, CODEBERG), asked.toSet())
        assertEquals("host 4 (codeberg.org): taken, number $SEQ", walk.notes[3])
    }

    @Test
    fun `Codeberg is never asked while one of the three has a document to believe`() {
        val asked = Collections.synchronizedList(mutableListOf<String>())
        val walk = walk(
            linkedMapOf(GIST to SignedSources.Fetched.Text(good), SITE to SignedSources.Fetched.Failed("HTTP 404")),
            asked = asked,
            lastResort = linkedMapOf(CODEBERG to SignedSources.Fetched.Text(newer)),
        )
        assertEquals(0, walk.found!!.index)
        assertEquals(setOf(GIST, SITE), asked.toSet())
        assertEquals(2, walk.notes.size)
    }

    @Test
    fun `Codeberg is asked when the three only have copies older than one taken`() {
        val walk = walk(
            linkedMapOf(GIST to SignedSources.Fetched.Text(good), SITE to SignedSources.Fetched.Text(good)),
            highest = SEQ + 30,
            lastResort = linkedMapOf(CODEBERG to SignedSources.Fetched.Text(newer)),
        )
        assertEquals(2, walk.found!!.index)
        assertEquals(SEQ + 60, walk.found!!.accepted.seq)
        assertTrue(walk.answered)
        assertEquals("host 1 (gist.githubusercontent.com): refused, older than one already taken", walk.notes[0])
    }

    @Test
    fun `every host is asked, and the newest document wins wherever it is`() {
        val asked = Collections.synchronizedList(mutableListOf<String>())
        val walk = walk(
            linkedMapOf(
                GIST to SignedSources.Fetched.Failed("HTTP 404"),
                SITE to SignedSources.Fetched.Text(good),
                WORKER to SignedSources.Fetched.Text(newer),
            ),
            asked = asked,
        )
        assertEquals(2, walk.found!!.index)
        assertEquals(SEQ + 60, walk.found!!.accepted.seq)
        assertEquals(setOf(GIST, SITE, WORKER), asked.toSet())
        assertEquals(
            listOf(
                "host 1 (gist.githubusercontent.com): HTTP 404",
                "host 2 (intertune.app): number $SEQ, older than host 3's",
                "host 3 (intertune-count.intertune.workers.dev): taken, number ${SEQ + 60}",
            ),
            walk.notes,
        )
    }

    @Test
    fun `a frozen copy on the first host never holds phones back from a newer one on the third`() {
        // Host 1 valid but older (a gist nobody can change any more), host 3 valid and newer: host 3 wins.
        val walk = walk(
            linkedMapOf(
                GIST to SignedSources.Fetched.Text(good),
                SITE to SignedSources.Fetched.Failed("HTTP 404"),
                WORKER to SignedSources.Fetched.Text(newer),
            )
        )
        assertEquals(2, walk.found!!.index)
        assertEquals(SEQ + 60, walk.found!!.accepted.seq)
        assertTrue(walk.answered)
        assertEquals("host 1 (gist.githubusercontent.com): number $SEQ, older than host 3's", walk.notes[0])
    }

    @Test
    fun `the order decides only between copies with the same number`() {
        val walk = walk(
            linkedMapOf(
                GIST to SignedSources.Fetched.Text(good),
                SITE to SignedSources.Fetched.Failed("HTTP 404"),
                WORKER to SignedSources.Fetched.Text(good),
            )
        )
        assertEquals(0, walk.found!!.index)
        assertEquals("host 3 (intertune-count.intertune.workers.dev): number $SEQ, the same as host 1's", walk.notes[2])
    }

    @Test
    fun `a forged copy and a stranger's are refused, the real one is taken`() {
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
    fun `hosts that never answer are cut off, all at once, and the one that answers is taken`() {
        LocalHost { path -> if (path.startsWith("/hangs")) null else Reply(200, good.toByteArray()) }.use { server ->
            val client = OkHttpClient.Builder().readTimeout(1, TimeUnit.SECONDS).callTimeout(3, TimeUnit.SECONDS).build()
            val started = System.nanoTime()
            val walk = runBlocking {
                SignedSources.best(
                    listOf("${server.base}/hangs-1", "${server.base}/hangs-2", "${server.base}/hangs-3", "${server.base}/polls.json"),
                    { SignedSources.fetch(it, client) },
                ) { SignedDocument.open(it, SignedDocument.NEWS, keys, 0, emptySet(), NOW) }
            }
            val seconds = (System.nanoTime() - started) / 1e9
            assertEquals(3, walk.found?.index)
            for (i in 0..2) assertTrue(walk.notes[i], walk.notes[i].startsWith("host ${i + 1} (127.0.0.1): SocketTimeoutException"))
            // Asked together: three hosts that each take a second to give up cost about one second, not three.
            assertTrue("took $seconds s", seconds < 2.5)
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
    fun `beside the gist, intertune app and the counter worker, with Codeberg as the last resort`() {
        val services = Properties().apply { File("../services.properties").reader().use { load(it) } }
        fun list(name: String) = services.getProperty(name).split(' ').filter { it.isNotBlank() }
        assertEquals(
            listOf("https://intertune.app/news/polls.json", "https://intertune-count.intertune.workers.dev/news/polls.json"),
            list("polls.mirrors"),
        )
        assertEquals(listOf(CODEBERG), list("polls.lastResort"))
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
        const val CODEBERG = "https://codeberg.org/ItzSkyeYT/intertune-news/raw/branch/main/news/polls.json"
    }
}
