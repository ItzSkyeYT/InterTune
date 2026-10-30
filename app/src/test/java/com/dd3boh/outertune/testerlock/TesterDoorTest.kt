/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.testerlock

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * The door's contract as the app reads it: the request, the answers, and everything else taken as a
 * door that could not check, which lets a tester in.
 */
class TesterDoorTest {

    private val install = "0123456789abcdef0123456789abcdef"
    private val loopback = InetAddress.getLoopbackAddress()
    private val sockets = mutableListOf<ServerSocket>()

    @After
    fun stop() = sockets.forEach { it.close() }

    /**
     * A door on this machine that answers [status] with [body], and keeps what it was sent. Plain
     * sockets: the JDK's own HTTP server is not on a unit test's classpath next to android.jar.
     */
    private inner class StandIn(status: Int, body: String, delayMillis: Long = 0, location: String? = null) {
        @Volatile var method: String? = null
        @Volatile var path: String? = null
        @Volatile var contentType: String? = null
        @Volatile var headers: Map<String, String> = emptyMap()
        @Volatile var sent: String? = null
        private val socket = ServerSocket(0, 8, loopback).also { sockets += it }
        val door = "http://${loopback.hostAddress}:${socket.localPort}"

        init {
            thread(isDaemon = true) {
                while (!socket.isClosed) {
                    val client = try {
                        socket.accept()
                    } catch (e: IOException) {
                        break
                    }
                    try {
                        client.use { answer(it, status, body, delayMillis, location) }
                    } catch (e: IOException) {
                        // The app gave up first: the slow door's answer has nobody to go to.
                    }
                }
            }
        }

        private fun answer(client: Socket, status: Int, body: String, delayMillis: Long, location: String?) {
            // Bytes as characters one for one, so the body's length in bytes is its length here.
            val input = client.getInputStream().bufferedReader(Charsets.ISO_8859_1)
            val request = input.readLine() ?: return
            val head = generateSequence { input.readLine()?.takeIf { it.isNotEmpty() } }
                .associate { it.substringBefore(':').lowercase() to it.substringAfter(':').trim() }
            val length = head["content-length"]?.toInt() ?: 0
            val chars = CharArray(length)
            var read = 0
            while (read < length) {
                val n = input.read(chars, read, length - read)
                if (n < 0) break
                read += n
            }
            method = request.substringBefore(' ')
            path = request.split(' ').getOrNull(1)
            contentType = head["content-type"]
            headers = head
            sent = String(chars, 0, read).toByteArray(Charsets.ISO_8859_1).decodeToString()
            if (delayMillis > 0) Thread.sleep(delayMillis)
            val bytes = body.toByteArray()
            val reply = buildString {
                append("HTTP/1.1 $status Stand-in\r\n")
                location?.let { append("Location: $it\r\n") }
                append("Content-Type: application/json\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n")
            }
            client.getOutputStream().apply {
                write(reply.toByteArray())
                write(bytes)
                flush()
            }
        }
    }

    @Test
    fun `a code goes to the door with the build and the install number, and nothing else`() {
        val standIn = StandIn(200, """{"ok":true}""")
        val answer = TesterDoor(standIn.door).ask("ABCD1234WXYZ", "0.12.5-preview.20261029.1900", 97, install)
        assertEquals(DoorAnswer.OPEN, answer)
        assertEquals("POST", standIn.method)
        assertEquals("/open", standIn.path)
        assertTrue(standIn.contentType.orEmpty(), standIn.contentType.orEmpty().startsWith("application/json"))
        val sent = Json.parseToJsonElement(standIn.sent!!).jsonObject
        assertEquals("no hardware id, no account: the number the install made for itself", setOf("code", "build", "vc", "install"), sent.keys)
        assertEquals("ABCD1234WXYZ", sent.getValue("code").jsonPrimitive.content)
        assertEquals("0.12.5-preview.20261029.1900", sent.getValue("build").jsonPrimitive.content)
        assertEquals(97, sent.getValue("vc").jsonPrimitive.int)
        assertEquals(install, sent.getValue("install").jsonPrimitive.content)
        // Nothing in the headers that names the phone: a User-Agent of the door's own, and only
        // what HTTP itself needs beside it.
        assertEquals("InterTune tester check", standIn.headers["user-agent"])
        val needed = setOf("host", "connection", "accept-encoding", "user-agent", "content-type", "content-length")
        assertTrue(standIn.headers.keys.toString(), needed.containsAll(standIn.headers.keys))
    }

    @Test
    fun `every check sends the same install number, the one it is given`() {
        for (made in listOf(install, "ffffffffffffffffffffffffffffffff")) {
            val standIn = StandIn(200, """{"ok":true}""")
            TesterDoor(standIn.door).ask("ABCD1234WXYZ", "b", 1, made)
            assertEquals(made, Json.parseToJsonElement(standIn.sent!!).jsonObject.getValue("install").jsonPrimitive.content)
        }
    }

    @Test
    fun `a door given with a slash at its end is asked at the same place`() {
        val standIn = StandIn(200, """{"ok":true}""")
        assertEquals(DoorAnswer.OPEN, TesterDoor(standIn.door + "/").ask("ABCD1234WXYZ", "b", 1, install))
        assertEquals("/open", standIn.path)
    }

    @Test
    fun `the answers of the contract`() {
        val cases = listOf(
            Triple(200, """{"ok":true}""", DoorAnswer.OPEN),
            Triple(200, """{"ok":true,"claimed":true}""", DoorAnswer.CLAIMED),
            Triple(403, """{"ok":false,"why":"unknown"}""", DoorAnswer.UNKNOWN),
            Triple(403, """{"ok":false,"why":"revoked"}""", DoorAnswer.REVOKED),
            Triple(403, """{"ok":false,"why":"in_use"}""", DoorAnswer.IN_USE),
            Triple(403, """{"ok":false,"why":"outdated"}""", DoorAnswer.OUTDATED),
        )
        for ((status, body, answer) in cases) {
            assertEquals("$status $body", answer, TesterDoor.answerOf(status, body))
            assertEquals("$status $body over the network", answer, TesterDoor(StandIn(status, body).door).ask("ABCD1234WXYZ", "b", 1, install))
        }
    }

    @Test
    fun `only a json true says the code was just given to this install`() {
        // The plain yes, as every door before this one answered it, stays the plain yes.
        for (body in listOf(
            """{"ok":true}""", """{"ok":true,"claimed":false}""", """{"ok":true,"claimed":"true"}""",
            """{"ok":true,"claimed":1}""", """{"ok":true,"claimed":null}""", """{"ok":true,"claimed":[true]}""",
        )) {
            assertEquals(body, DoorAnswer.OPEN, TesterDoor.answerOf(200, body))
        }
        // And a claim is a claim of a yes: a no that carries the flag is still a no, or nothing understood.
        assertEquals(DoorAnswer.UNKNOWN, TesterDoor.answerOf(403, """{"ok":false,"why":"unknown","claimed":true}"""))
        assertEquals(DoorAnswer.COULD_NOT_CHECK, TesterDoor.answerOf(200, """{"ok":false,"claimed":true}"""))
        assertEquals(DoorAnswer.COULD_NOT_CHECK, TesterDoor.answerOf(403, """{"ok":true,"claimed":true}"""))
    }

    @Test
    fun `anything else is a door that could not check`() {
        val others = listOf(
            // The app checks a code's shape before sending it: a door that finds it bad is our mistake.
            400 to """{"ok":false,"why":"bad"}""",
            // What the door answers before it knows /open.
            405 to "",
            405 to """{"ok":false}""",
            500 to """{"ok":false}""",
            502 to "<html>Bad gateway</html>",
            503 to "",
            429 to """{"ok":false,"why":"slow down"}""",
            404 to "Not found",
            200 to """{"ok":false}""",
            200 to """{"ok":"true"}""",
            200 to "ok",
            200 to "",
            200 to "[true]",
            403 to """{"ok":false,"why":"expired"}""",
            403 to """{"ok":true,"why":"unknown"}""",
            // The new refusals are 403s of the same shape and nothing else: in another shape they are not understood.
            200 to """{"ok":false,"why":"in_use"}""",
            200 to """{"ok":false,"why":"outdated"}""",
            400 to """{"ok":false,"why":"outdated"}""",
            401 to """{"ok":false,"why":"in_use"}""",
            403 to """{"ok":true,"why":"outdated"}""",
            403 to """{"ok":true,"why":"in_use"}""",
            403 to """{"ok":false,"why":"In_Use"}""",
            403 to """{"ok":false,"why":"in use"}""",
            403 to """{"ok":false,"why":"OUTDATED"}""",
            403 to """{"ok":false,"why":true}""",
            403 to "Forbidden",
            400 to """{"ok":false,"why":"unknown"}""",
            401 to """{"ok":false,"why":"revoked"}""",
        )
        for ((status, body) in others) {
            assertEquals("$status $body", DoorAnswer.COULD_NOT_CHECK, TesterDoor.answerOf(status, body))
        }
        assertEquals(DoorAnswer.COULD_NOT_CHECK, TesterDoor.answerOf(200, null))
        // Other fields beside the contract's are no reason to refuse an answer.
        assertEquals(DoorAnswer.OPEN, TesterDoor.answerOf(200, """{"ok":true,"note":"hi"}"""))
        for ((status, body) in listOf(503 to "", 400 to """{"ok":false,"why":"bad"}""", 405 to "")) {
            assertEquals("$status over the network", DoorAnswer.COULD_NOT_CHECK, TesterDoor(StandIn(status, body).door).ask("ABCD1234WXYZ", "b", 1, install))
        }
    }

    @Test
    fun `no door to reach is a door that could not check`() {
        val closed = ServerSocket(0, 1, loopback).use { it.localPort }
        assertEquals(DoorAnswer.COULD_NOT_CHECK, TesterDoor("http://${loopback.hostAddress}:$closed").ask("ABCD1234WXYZ", "b", 1, install))
        assertEquals(DoorAnswer.COULD_NOT_CHECK, TesterDoor("not a door").ask("ABCD1234WXYZ", "b", 1, install))
    }

    @Test
    fun `a door that is too slow is a door that could not check`() {
        val slow = StandIn(200, """{"ok":true}""", delayMillis = 1500)
        val impatient = OkHttpClient.Builder().callTimeout(300, TimeUnit.MILLISECONDS).build()
        assertEquals(DoorAnswer.COULD_NOT_CHECK, TesterDoor(slow.door, impatient).ask("ABCD1234WXYZ", "b", 1, install))
    }

    @Test
    fun `a redirect is not an answer`() {
        val elsewhere = StandIn(200, """{"ok":true}""")
        val moved = StandIn(302, "", location = elsewhere.door + "/open")
        assertEquals(DoorAnswer.COULD_NOT_CHECK, TesterDoor(moved.door).ask("ABCD1234WXYZ", "b", 1, install))
        assertEquals("the other address was never asked", null, elsewhere.method)
    }

    @Test
    fun `the door's own client gives up after ten seconds and follows no redirect`() {
        assertEquals(10_000, TesterDoor.CLIENT.callTimeoutMillis)
        assertFalse(TesterDoor.CLIENT.followRedirects)
        assertFalse(TesterDoor.CLIENT.followSslRedirects)
        assertTrue("no interceptors of the app's", TesterDoor.CLIENT.interceptors.isEmpty())
        assertTrue(TesterDoor.CLIENT.networkInterceptors.isEmpty())
    }
}
