/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.cast

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.double
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * The link against a fake receiver on a loopback socket, which speaks the frames the Android and
 * desktop receivers speak, in the order they speak them.
 */
class FCastLinkTest {
    private val loopback: InetAddress = InetAddress.getByName("127.0.0.1")
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val receivers = mutableListOf<FakeReceiver>()
    private val links = mutableListOf<FCastLink>()

    /** A clock the tests move by hand, so positions and silences are exact. */
    private var clock = 1_000_000L

    @After
    fun tearDown() {
        links.forEach { it.close() }
        receivers.forEach { it.close() }
        scope.cancel()
    }

    private inner class FakeReceiver(private val version: Long? = 3) {
        val server = ServerSocket(0, 1, loopback)
        val frames = LinkedBlockingQueue<FCast.Frame>()

        @Volatile
        var socket: Socket? = null

        @Volatile
        var answerPings = true

        init {
            receivers += this
            thread(isDaemon = true) {
                val connection = try {
                    server.accept()
                } catch (e: IOException) {
                    return@thread
                }
                socket = connection
                if (version != null) send(FCast.VERSION_MESSAGE, FCast.versionBody(version))
                try {
                    val input = connection.getInputStream()
                    while (true) {
                        val frame = FCast.read(input) ?: break
                        if (frame.opcode == FCast.PING && answerPings) send(FCast.PONG)
                        frames.put(frame)
                    }
                } catch (e: IOException) {
                    // The link hung up.
                }
            }
        }

        val device get() = CastDevice(CastProtocol.FCAST, "fcast:test", "Test TV", "127.0.0.1", server.localPort)

        fun send(opcode: Int, body: String? = null) {
            val out = socket!!.getOutputStream()
            synchronized(this) {
                out.write(FCast.encode(opcode, body))
                out.flush()
            }
        }

        /** The next frame the link sent with [opcode], skipping others. */
        fun next(opcode: Int, timeoutMs: Long = 5_000): FCast.Frame {
            val deadline = System.nanoTime() + timeoutMs * 1_000_000
            while (true) {
                val left = (deadline - System.nanoTime()) / 1_000_000
                if (left <= 0) fail("No frame $opcode from the link; got ${frames.toList()}")
                val frame = frames.poll(left, TimeUnit.MILLISECONDS) ?: continue
                if (frame.opcode == opcode) return frame
            }
        }

        fun update(state: Int, seconds: Double? = null, duration: Double? = null) {
            val fields = listOfNotNull(
                "\"generationTime\":${System.currentTimeMillis()}",
                "\"state\":$state",
                seconds?.let { "\"time\":$it" },
                duration?.let { "\"duration\":$it" },
                "\"speed\":1.0".takeIf { seconds != null },
            )
            send(FCast.PLAYBACK_UPDATE, fields.joinToString(",", "{", "}"))
        }

        fun close() {
            runCatching { socket?.close() }
            runCatching { server.close() }
        }
    }

    private fun link(
        receiver: FakeReceiver,
        timing: FCastLink.Timing = FCastLink.Timing(versionWaitMs = 1_000),
        realClock: Boolean = false,
    ): FCastLink = FCastLink(
        receiver.device,
        scope,
        FCastLink.Sender("Test phone", "InterTune", "0.11"),
        now = if (realClock) ({ System.nanoTime() / 1_000_000L }) else ({ clock }),
        timing = timing,
    ).also { links += it }

    private fun connected(version: Long? = 3, timing: FCastLink.Timing = FCastLink.Timing(versionWaitMs = 1_000)): Pair<FakeReceiver, FCastLink> {
        val receiver = FakeReceiver(version)
        val link = link(receiver, timing)
        runBlocking { link.connect() }
        return receiver to link
    }

    private fun FCastLink.await(timeoutMs: Long = 5_000, predicate: (ReceiverStatus) -> Boolean): ReceiverStatus =
        runBlocking { withTimeout(timeoutMs) { status.first(predicate) } }

    /** The receiver's first report after a load, near the start, which later reports are taken after. */
    private fun FakeReceiver.started() = update(FCast.STATE_PLAYING, 0.1, 200.0)

    private fun json(frame: FCast.Frame): JsonObject = Json.parseToJsonElement(frame.body!!).jsonObject

    private val song = CastItem(
        mediaId = "vid1",
        url = "http://127.0.0.1:47800/c/tok/s/vid1.webm",
        mimeType = "audio/webm",
        length = 3_000_000,
        durationMs = 200_000,
        title = "First",
        artist = "Band",
    )

    // Connecting

    @Test
    fun `the link says its version first, then Initial and asks for the end of songs`() {
        val (receiver, link) = connected()
        assertEquals(3L, FCast.parseVersion(receiver.next(FCast.VERSION_MESSAGE).body))
        val initial = json(receiver.next(FCast.INITIAL))
        assertEquals("Test phone", initial.getValue("displayName").jsonPrimitive.content)
        assertEquals("InterTune", initial.getValue("appName").jsonPrimitive.content)
        val subscribe = json(receiver.next(FCast.SUBSCRIBE_EVENT))
        assertEquals(FCast.EVENT_MEDIA_ITEM_END, subscribe.getValue("event").jsonObject.getValue("type").jsonPrimitive.int)
        assertEquals(LinkState.CONNECTED, link.status.value.link)
        assertEquals(3L, link.version)
        assertEquals(FormatSupport.Unknown, link.caps.formats)
    }

    @Test
    fun `the very first frame on the wire is Version, which the desktop receiver insists on`() {
        val (receiver, _) = connected()
        assertEquals(FCast.VERSION_MESSAGE, receiver.frames.poll(5, TimeUnit.SECONDS)!!.opcode)
    }

    @Test
    fun `a v4 receiver, the desktop one, is spoken to in v3`() {
        val (receiver, link) = connected(version = 4)
        assertEquals(3L, link.version)
        receiver.next(FCast.INITIAL)
    }

    @Test
    fun `a v2 receiver gets neither Initial nor a subscription, which it would not understand`() {
        val (receiver, link) = connected(version = 2)
        assertEquals(2L, link.version)
        runBlocking { link.load(song, 0, play = true) }
        receiver.next(FCast.PLAY)
        assertTrue(receiver.frames.none { it.opcode == FCast.INITIAL || it.opcode == FCast.SUBSCRIBE_EVENT })
        assertTrue(link.caps.canSetSpeed)
    }

    @Test
    fun `a receiver typed in by address is named as it names itself in Initial`() {
        val receiver = FakeReceiver()
        val typed = receiver.device.copy(name = "127.0.0.1")
        val link = FCastLink(typed, scope, FCastLink.Sender("Test phone", "InterTune", "0.11"), now = { clock }).also { links += it }
        // As the receivers do: Initial once the sender's Version is in.
        thread(isDaemon = true) {
            receiver.next(FCast.VERSION_MESSAGE)
            receiver.send(FCast.INITIAL, """{"displayName":"Living room TV","appName":"FCast","appVersion":"2.0"}""")
        }
        runBlocking { link.connect() }
        assertEquals("Living room TV", link.device.name)
        assertEquals(typed.id, link.device.id)
    }

    @Test
    fun `a receiver found by name keeps it, and one that never says its name still connects`() {
        val (receiver, link) = connected()
        receiver.next(FCast.VERSION_MESSAGE)
        receiver.send(FCast.INITIAL, """{"displayName":"Something else"}""")
        receiver.next(FCast.INITIAL)
        Thread.sleep(200)
        assertEquals("Test TV", link.device.name)

        val silent = FakeReceiver()
        val typed = silent.device.copy(name = "127.0.0.1")
        val quiet = FCastLink(typed, scope, FCastLink.Sender("Test phone", "InterTune", "0.11"), now = { clock }, timing = FCastLink.Timing(initialWaitMs = 200))
            .also { links += it }
        runBlocking { quiet.connect() }
        assertEquals(LinkState.CONNECTED, quiet.status.value.link)
        assertEquals("127.0.0.1", quiet.device.name)
    }

    @Test
    fun `a receiver that never says its version is taken for v2`() {
        val (_, link) = connected(version = null, timing = FCastLink.Timing(versionWaitMs = 300))
        assertEquals(2L, link.version)
        assertEquals(LinkState.CONNECTED, link.status.value.link)
    }

    @Test
    fun `nothing listening is a readable error and a lost link`() {
        val free = ServerSocket(0, 1, loopback).use { it.localPort }
        val link = FCastLink(
            CastDevice(CastProtocol.FCAST, "x", "Nobody", "127.0.0.1", free),
            scope,
            FCastLink.Sender("a", "b", "c"),
        ).also { links += it }
        try {
            runBlocking { link.connect() }
            fail("Connected to nothing")
        } catch (e: CastLinkException) {
            assertEquals("Could not reach Nobody", e.message)
        }
        assertEquals(LinkState.LOST, link.status.value.link)
    }

    // Loading

    @Test
    fun `a load sends Play with a url made new for each load, and waits`() {
        val (receiver, link) = connected()
        runBlocking { link.load(song, 30_000, play = true) }
        val play = json(receiver.next(FCast.PLAY))
        assertEquals("audio/webm", play.getValue("container").jsonPrimitive.content)
        assertEquals(song.url + "?load=1", play.getValue("url").jsonPrimitive.content)
        assertEquals(30.0, play.getValue("time").jsonPrimitive.double, 0.0)
        assertEquals("First - Band", play.getValue("metadata").jsonObject.getValue("title").jsonPrimitive.content)
        val status = link.status.value
        assertEquals(ReceiverState.LOADING, status.state)
        assertEquals(1, status.load)
        assertEquals("vid1", status.mediaId)
        assertEquals(30_000L, status.positionMs)

        // The same song again, as repeat one does: the Android receiver would ignore the same url.
        runBlocking { link.load(song, 0, play = true) }
        assertEquals(song.url + "?load=2", json(receiver.next(FCast.PLAY)).getValue("url").jsonPrimitive.content)
        runBlocking { link.load(song.copy(url = song.url + "?x=1"), 0, play = true) }
        assertEquals(song.url + "?x=1&load=3", json(receiver.next(FCast.PLAY)).getValue("url").jsonPrimitive.content)
    }

    @Test
    fun `a song loaded paused is played and paused at once`() {
        val (receiver, link) = connected()
        runBlocking { link.load(song, 0, play = false) }
        receiver.next(FCast.PLAY)
        receiver.next(FCast.PAUSE)
    }

    // Following it

    @Test
    fun `playing updates move the status, and the position runs on between them`() {
        val (receiver, link) = connected()
        runBlocking { link.load(song, 0, play = true) }
        receiver.update(FCast.STATE_PLAYING, 1.0, 200.0)
        val status = link.await { it.state == ReceiverState.PLAYING }
        assertEquals(1_000L, status.positionMs)
        assertEquals(200_000L, status.durationMs)
        assertEquals(3_500L, status.positionAt(clock + 2_500))
        assertEquals(200_000L, status.positionAt(clock + 10_000_000))
    }

    @Test
    fun `a report about the song before is not taken for the new one`() {
        val (receiver, link) = connected()
        runBlocking { link.load(song, 0, play = true) }
        receiver.update(FCast.STATE_PLAYING, 187.0, 190.0)
        receiver.update(FCast.STATE_PLAYING, 0.5, 200.0)
        val status = link.await { it.state == ReceiverState.PLAYING }
        assertEquals(500L, status.positionMs)
    }

    @Test
    fun `a seek before the first report moves where that report is expected`() {
        val (receiver, link) = connected()
        runBlocking {
            link.load(song, 0, play = true)
            link.seek(120_000)
        }
        assertEquals(120.0, json(receiver.next(FCast.SEEK)).getValue("time").jsonPrimitive.double, 0.0)
        receiver.update(FCast.STATE_PLAYING, 120.2, 200.0)
        assertEquals(120_200L, link.await { it.state == ReceiverState.PLAYING }.positionMs)
    }

    @Test
    fun `idle while opening the song is still opening it`() {
        val (receiver, link) = connected()
        runBlocking { link.load(song, 0, play = true) }
        receiver.update(FCast.STATE_IDLE)
        receiver.update(FCast.STATE_PLAYING, 0.1, 200.0)
        link.await { it.state == ReceiverState.PLAYING }
        // Nothing in between said IDLE; the first status after LOADING was PLAYING.
    }

    @Test
    fun `paused at the full length without being asked is the end, as the Android receiver reports it`() {
        val (receiver, link) = connected()
        runBlocking { link.load(song, 0, play = true) }
        receiver.started()
        receiver.update(FCast.STATE_PLAYING, 199.0, 200.0)
        link.await { it.state == ReceiverState.PLAYING && it.positionMs == 199_000L }
        receiver.update(FCast.STATE_PAUSED, 200.0, 200.0)
        val ended = link.await { it.state == ReceiverState.ENDED }
        assertEquals(200_000L, ended.positionMs)
        assertEquals(1, ended.load)

        // The event after it changes nothing, and neither does a late update.
        receiver.send(FCast.EVENT, """{"generationTime":1,"event":{"type":1,"item":{"url":"${song.url}?load=1"}}}""")
        receiver.update(FCast.STATE_PLAYING, 3.0, 200.0)
        receiver.send(FCast.PING)
        receiver.next(FCast.PONG)
        assertEquals(ReceiverState.ENDED, link.status.value.state)

        runBlocking { link.load(song, 0, play = true) }
        assertEquals(ReceiverState.LOADING, link.status.value.state)
        assertEquals(2, link.status.value.load)
    }

    @Test
    fun `paused near the end because it was asked to is only paused`() {
        val (receiver, link) = connected()
        runBlocking { link.load(song, 0, play = true) }
        receiver.started()
        link.await { it.state == ReceiverState.PLAYING }
        runBlocking { link.pause() }
        receiver.next(FCast.PAUSE)
        receiver.update(FCast.STATE_PAUSED, 199.5, 200.0)
        assertEquals(199_500L, link.await { it.state == ReceiverState.PAUSED }.positionMs)
        runBlocking { link.play() }
        receiver.next(FCast.RESUME)
    }

    @Test
    fun `the MediaItemEnd event ends the song it names, and only that one`() {
        val (receiver, link) = connected()
        runBlocking { link.load(song, 0, play = true) }
        receiver.started()
        receiver.update(FCast.STATE_PLAYING, 100.0, 200.0)
        link.await { it.state == ReceiverState.PLAYING && it.positionMs == 100_000L }
        receiver.send(FCast.EVENT, """{"generationTime":1,"event":{"type":1,"item":{"url":"http://elsewhere/x"}}}""")
        receiver.send(FCast.EVENT, """{"generationTime":2,"event":{"type":0,"item":{"url":"${song.url}?load=1"}}}""")
        receiver.send(FCast.PING)
        receiver.next(FCast.PONG)
        assertEquals(ReceiverState.PLAYING, link.status.value.state)
        receiver.send(FCast.EVENT, """{"generationTime":3,"event":{"type":1,"item":{"url":"${song.url}?load=1"}}}""")
        assertEquals(200_000L, link.await { it.state == ReceiverState.ENDED }.positionMs)
    }

    @Test
    fun `a v2 receiver's end is read from its updates alone`() {
        val (receiver, link) = connected(version = 2)
        runBlocking { link.load(song, 0, play = true) }
        receiver.started()
        receiver.update(FCast.STATE_PLAYING, 198.0, 200.0)
        link.await { it.state == ReceiverState.PLAYING && it.positionMs == 198_000L }
        receiver.update(FCast.STATE_IDLE, 199.2, 200.0)
        link.await { it.state == ReceiverState.ENDED }
    }

    @Test
    fun `idle then PlaybackError fails the song with the receiver's words, until the next load`() {
        val (receiver, link) = connected()
        runBlocking { link.load(song, 0, play = true) }
        receiver.send(FCast.PLAYBACK_UPDATE, """{"generationTime":1,"state":0,"time":0.0,"duration":0.0,"speed":0.0}""")
        receiver.send(FCast.PLAYBACK_ERROR, """{"message":"Source error: 503"}""")
        val failed = link.await { it.state == ReceiverState.FAILED }
        assertEquals("Source error: 503", failed.error)
        receiver.update(FCast.STATE_PLAYING, 1.0, 200.0)
        receiver.send(FCast.PING)
        receiver.next(FCast.PONG)
        assertEquals(ReceiverState.FAILED, link.status.value.state)
        runBlocking { link.load(song, 0, play = true) }
        assertNull(link.status.value.error)
    }

    @Test
    fun `another sender playing on the receiver takes it over`() {
        val (receiver, link) = connected()
        runBlocking { link.load(song, 0, play = true) }
        receiver.send(FCast.PLAY_UPDATE, """{"generationTime":1,"playData":{"container":"audio/webm","url":"${song.url}?load=1"}}""")
        receiver.update(FCast.STATE_PLAYING, 0.2, 200.0)
        link.await { it.state == ReceiverState.PLAYING }
        receiver.send(FCast.PLAY_UPDATE, """{"generationTime":2,"playData":{"container":"video/mp4","url":"http://other/video.mp4"}}""")
        val taken = link.await { it.state == ReceiverState.IDLE }
        assertEquals("Something else is playing on Test TV", taken.error)
    }

    // Controls

    @Test
    fun `pause, resume, seek, stop and volume go out as their frames`() {
        val (receiver, link) = connected()
        runBlocking {
            link.load(song, 0, play = true)
            link.pause()
            link.play()
            link.seek(45_500)
            link.setVolume(1.7f)
            link.stop()
        }
        receiver.next(FCast.PAUSE)
        receiver.next(FCast.RESUME)
        assertEquals(45.5, json(receiver.next(FCast.SEEK)).getValue("time").jsonPrimitive.double, 0.0)
        assertEquals(1.0, json(receiver.next(FCast.SET_VOLUME)).getValue("volume").jsonPrimitive.double, 0.0)
        receiver.next(FCast.STOP)
        assertEquals(ReceiverState.IDLE, link.status.value.state)
        assertEquals(1f, link.status.value.volume)

        // After a stop the receiver's reports are about nothing this link sent.
        receiver.update(FCast.STATE_PLAYING, 3.0, 200.0)
        receiver.send(FCast.PING)
        receiver.next(FCast.PONG)
        assertEquals(ReceiverState.IDLE, link.status.value.state)
    }

    @Test
    fun `the receiver's own volume changes are followed`() {
        val (receiver, link) = connected()
        receiver.send(FCast.VOLUME_UPDATE, """{"generationTime":1,"volume":0.3}""")
        assertEquals(0.3f, link.await { it.volume != null }.volume)
    }

    // Staying connected

    @Test
    fun `a ping from the receiver is answered at once`() {
        val (receiver, _) = connected()
        receiver.send(FCast.PING)
        receiver.next(FCast.PONG, timeoutMs = 1_000)
    }

    @Test
    fun `a quiet receiver is pinged, and one that answers stays connected`() {
        val receiver = FakeReceiver()
        val link = link(receiver, FCastLink.Timing(versionWaitMs = 1_000, pingAfterMs = 200, deadAfterMs = 1_500, tickMs = 50), realClock = true)
        runBlocking { link.connect() }
        receiver.next(FCast.PING, timeoutMs = 2_000)
        receiver.next(FCast.PING, timeoutMs = 2_000)
        assertEquals(LinkState.CONNECTED, link.status.value.link)
    }

    @Test
    fun `a receiver that stops answering is lost`() {
        val receiver = FakeReceiver()
        receiver.answerPings = false
        val link = link(receiver, FCastLink.Timing(versionWaitMs = 1_000, pingAfterMs = 100, deadAfterMs = 600, tickMs = 50), realClock = true)
        runBlocking { link.connect() }
        receiver.next(FCast.PING, timeoutMs = 2_000)
        val lost = link.await(3_000) { it.link == LinkState.LOST }
        assertEquals("Test TV stopped answering", lost.error)
    }

    @Test
    fun `a receiver that hangs up loses the link, and commands then say so`() {
        val (receiver, link) = connected()
        receiver.socket!!.close()
        link.await { it.link == LinkState.LOST }
        try {
            runBlocking { link.play() }
            fail("Sent to a receiver that hung up")
        } catch (e: CastLinkException) {
            assertEquals("Not connected to Test TV", e.message)
        }
    }

    @Test
    fun `close is final and is not taken for a loss`() {
        val (_, link) = connected()
        link.close()
        link.close()
        Thread.sleep(200)
        assertEquals(LinkState.CLOSED, link.status.value.link)
    }
}
