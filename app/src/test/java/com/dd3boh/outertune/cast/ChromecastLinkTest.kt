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
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.double
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.security.KeyStore
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import kotlin.concurrent.thread

/**
 * The link against a fake Chromecast on a loopback socket, which answers as the Default Media
 * Receiver and a Cast device do: the device's status, LAUNCH, STOP, SET_VOLUME and PING on its own
 * connection, and LOAD with the song's first status. Everything else a test sends by hand.
 *
 * The fake speaks plain TCP, and the link is given a TLS step that does nothing, except in the one
 * test that puts real TLS between them.
 */
class ChromecastLinkTest {
    private val loopback: InetAddress = InetAddress.getByName("127.0.0.1")
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val receivers = mutableListOf<FakeChromecast>()
    private val links = mutableListOf<ChromecastLink>()

    /** Nothing on a timer unless a test asks for it, so the messages seen are the ones it caused. */
    private val quiet = ChromecastLink.Timing(
        answerTimeoutMs = 2_000,
        launchTimeoutMs = 2_000,
        pingEveryMs = 600_000,
        deadAfterMs = 600_000,
        statusEveryMs = 600_000,
        tickMs = 50,
    )

    @After
    fun tearDown() {
        links.forEach { it.close() }
        receivers.forEach { it.close() }
        scope.cancel()
    }

    private class App(val appId: String, val sessionId: String, val transportId: String, val idleScreen: Boolean = false)

    /** What the link sent: the message, and its JSON. */
    private class Sent(val message: CastChannel.Message, val body: JsonObject) {
        val type: String? get() = body["type"]?.jsonPrimitive?.content
        val requestId: Int get() = body["requestId"]?.jsonPrimitive?.int ?: 0
        override fun toString() = "${message.namespace.substringAfterLast('.')} $type -> ${message.destinationId}"
    }

    private inner class FakeChromecast(apps: List<App> = emptyList(), private val server: ServerSocket = ServerSocket(0, 1, loopback)) {
        val sent = LinkedBlockingQueue<Sent>()
        val everything = CopyOnWriteArrayList<Sent>()
        val apps = CopyOnWriteArrayList(apps)

        @Volatile
        var socket: Socket? = null

        @Volatile
        var answerPings = true

        /** Answers nothing at all, pings included, as a device that has gone. */
        @Volatile
        var silent = false

        /** Answers a LOAD with the song's first status, playing or paused as asked. */
        @Volatile
        var answerLoads = true

        @Volatile
        var refuseLaunch: String? = null

        @Volatile
        var level = 0.5

        @Volatile
        var muted = false

        @Volatile
        var controlType = "attenuation"

        /** The link's name on its connection to the player, once it connected to it. */
        @Volatile
        var client = "*"

        private var launches = 0
        private var sessions = 0L

        init {
            receivers += this
            thread(isDaemon = true) {
                val connection = try {
                    server.accept()
                } catch (e: IOException) {
                    return@thread
                }
                socket = connection
                try {
                    val input = connection.getInputStream()
                    while (true) {
                        val message = CastChannel.read(input) ?: break
                        val body = Json.parseToJsonElement(message.payloadUtf8!!).jsonObject
                        val sent = Sent(message, body)
                        everything += sent
                        this.sent.put(sent)
                        // A link that hung up meanwhile is not the end of reading what it sent.
                        if (!silent) runCatching { answer(sent) }
                        // As a device does once its own connection is closed.
                        if (sent.type == "CLOSE" && message.destinationId == Chromecast.PLATFORM_RECEIVER) break
                    }
                } catch (e: IOException) {
                    // The link hung up.
                }
                runCatching { connection.close() }
            }
        }

        val device get() = CastDevice(CastProtocol.CHROMECAST, "chromecast:test", "Test TV", "127.0.0.1", server.localPort)

        private fun answer(sent: Sent) {
            val message = sent.message
            when (message.namespace) {
                Chromecast.NS_HEARTBEAT -> if (sent.type == "PING" && answerPings) {
                    send(message.destinationId, message.sourceId, Chromecast.NS_HEARTBEAT, """{"type":"PONG"}""")
                }
                Chromecast.NS_CONNECTION -> if (sent.type == "CONNECT" && message.destinationId != Chromecast.PLATFORM_RECEIVER) {
                    client = message.sourceId
                }
                Chromecast.NS_RECEIVER -> when (sent.type) {
                    "GET_STATUS" -> deviceStatus(sent.requestId)
                    "LAUNCH" -> {
                        val refusal = refuseLaunch
                        if (refusal != null) {
                            send(Chromecast.PLATFORM_RECEIVER, message.sourceId, Chromecast.NS_RECEIVER, """{"type":"LAUNCH_ERROR","requestId":${sent.requestId},"reason":"$refusal"}""")
                        } else {
                            launches++
                            apps.removeAll { true }
                            apps += App(Chromecast.DEFAULT_MEDIA_RECEIVER, "session-$launches", "web-$launches")
                            sessions = 0
                            deviceStatus(sent.requestId)
                        }
                    }
                    "STOP" -> {
                        apps.removeAll { it.sessionId == sent.body["sessionId"]?.jsonPrimitive?.content }
                        deviceStatus(sent.requestId)
                    }
                    "SET_VOLUME" -> {
                        val volume = sent.body.getValue("volume").jsonObject
                        volume["level"]?.let { level = it.jsonPrimitive.double }
                        volume["muted"]?.let { muted = it.jsonPrimitive.boolean }
                        deviceStatus(sent.requestId)
                    }
                }
                Chromecast.NS_MEDIA -> if (sent.type == "LOAD" && answerLoads) {
                    val media = sent.body.getValue("media").jsonObject
                    val state = if (sent.body.getValue("autoplay").jsonPrimitive.boolean) "PLAYING" else "PAUSED"
                    mediaStatus(
                        ++sessions, state, sent.body.getValue("currentTime").jsonPrimitive.double,
                        contentId = media.getValue("contentId").jsonPrimitive.content,
                        duration = media["duration"]?.jsonPrimitive?.double,
                        requestId = sent.requestId,
                    )
                }
            }
        }

        fun send(source: String, destination: String, namespace: String, payload: String) {
            val out = socket!!.getOutputStream()
            synchronized(this) {
                out.write(CastChannel.frame(CastChannel.Message(source, destination, namespace, payload)))
                out.flush()
            }
        }

        fun deviceStatus(requestId: Int = 0) {
            val applications = apps.joinToString(",", "[", "]") {
                """{"appId":"${it.appId}","displayName":"${it.appId}","isIdleScreen":${it.idleScreen},"sessionId":"${it.sessionId}","transportId":"${it.transportId}","namespaces":[]}"""
            }
            val status = """{"applications":$applications,"volume":{"controlType":"$controlType","level":$level,"muted":$muted,"stepInterval":0.05}}"""
            send(Chromecast.PLATFORM_RECEIVER, "*", Chromecast.NS_RECEIVER, """{"type":"RECEIVER_STATUS","requestId":$requestId,"status":$status}""")
        }

        /** A MEDIA_STATUS about [session], from the player. */
        fun mediaStatus(
            session: Long,
            state: String,
            seconds: Double? = null,
            idleReason: String? = null,
            contentId: String? = null,
            duration: Double? = null,
            requestId: Int = 0,
        ) {
            val media = if (contentId != null) {
                val length = duration?.let { ""","duration":$it""" }.orEmpty()
                ""","media":{"contentId":"$contentId","streamType":"BUFFERED","contentType":"audio/webm"$length}"""
            } else ""
            val fields = listOfNotNull(
                """"mediaSessionId":$session""",
                """"playerState":"$state"""",
                """"playbackRate":1""",
                seconds?.let { """"currentTime":$it""" },
                idleReason?.let { """"idleReason":"$it"""" },
            ).joinToString(",")
            sendMedia("""{"type":"MEDIA_STATUS","requestId":$requestId,"status":[{$fields$media}]}""")
        }

        fun sendMedia(payload: String) = send(apps.firstOrNull()?.transportId ?: "web-1", client, Chromecast.NS_MEDIA, payload)

        /** The next message the link sent on [namespace] with [type], skipping others. */
        fun next(namespace: String, type: String, timeoutMs: Long = 5_000): Sent {
            val deadline = System.nanoTime() + timeoutMs * 1_000_000
            while (true) {
                val left = (deadline - System.nanoTime()) / 1_000_000
                if (left <= 0) fail("No $type on $namespace from the link; got ${everything.toList()}")
                val sent = sent.poll(left, TimeUnit.MILLISECONDS) ?: continue
                if (sent.message.namespace == namespace && sent.type == type) return sent
            }
        }

        /** Whether the link sends [type] on [namespace] within [ms]. */
        fun sends(namespace: String, type: String, ms: Long): Boolean {
            val deadline = System.nanoTime() + ms * 1_000_000
            while (true) {
                val left = (deadline - System.nanoTime()) / 1_000_000
                if (left <= 0) return false
                val sent = sent.poll(left, TimeUnit.MILLISECONDS) ?: continue
                if (sent.message.namespace == namespace && sent.type == type) return true
            }
        }

        fun close() {
            runCatching { socket?.close() }
            runCatching { server.close() }
        }
    }

    private fun link(receiver: FakeChromecast, timing: ChromecastLink.Timing = quiet): ChromecastLink =
        ChromecastLink(receiver.device, scope, secure = { socket, _, _ -> socket }, timing = timing).also { links += it }

    private fun connected(
        apps: List<App> = listOf(App("E8C28D3C", "backdrop", "web-0", idleScreen = true)),
        timing: ChromecastLink.Timing = quiet,
    ): Pair<FakeChromecast, ChromecastLink> {
        val receiver = FakeChromecast(apps)
        val link = link(receiver, timing)
        runBlocking { link.connect() }
        return receiver to link
    }

    private fun ChromecastLink.await(timeoutMs: Long = 5_000, predicate: (ReceiverStatus) -> Boolean): ReceiverStatus =
        runBlocking { withTimeout(timeoutMs) { status.first(predicate) } }

    private fun expectFailure(block: suspend () -> Unit): CastLinkException {
        try {
            runBlocking { block() }
        } catch (e: CastLinkException) {
            return e
        }
        fail("Expected the link to fail")
        throw AssertionError()
    }

    private val song = CastItem(
        mediaId = "vid1",
        url = "http://127.0.0.1:47800/c/tok/s/vid1.webm",
        mimeType = "audio/webm",
        length = 3_000_000,
        durationMs = 200_000,
        title = "First",
        artist = "Band",
        album = "Record",
        artworkUrl = "https://i.ytimg.com/vi/vid1/hq.jpg",
    )

    /** Loads [song] and waits for the fake's answer to make it play. */
    private fun playing(receiver: FakeChromecast, link: ChromecastLink, startMs: Long = 0): Sent {
        runBlocking { link.load(song, startMs, play = true) }
        val load = receiver.next(Chromecast.NS_MEDIA, "LOAD")
        link.await { it.state == ReceiverState.PLAYING }
        return load
    }

    // Connecting

    @Test
    fun `an idle device is asked for its status, then launches the media player, which is connected to`() {
        val (receiver, link) = connected()
        val connect = receiver.next(Chromecast.NS_CONNECTION, "CONNECT")
        assertEquals(Chromecast.PLATFORM_SENDER, connect.message.sourceId)
        assertEquals(Chromecast.PLATFORM_RECEIVER, connect.message.destinationId)
        receiver.next(Chromecast.NS_RECEIVER, "GET_STATUS")
        val launch = receiver.next(Chromecast.NS_RECEIVER, "LAUNCH")
        assertEquals("CC1AD845", launch.body.getValue("appId").jsonPrimitive.content)
        val toPlayer = receiver.next(Chromecast.NS_CONNECTION, "CONNECT")
        assertEquals("web-1", toPlayer.message.destinationId)
        assertTrue(toPlayer.message.sourceId.startsWith("client-"))

        assertEquals(LinkState.CONNECTED, link.status.value.link)
        assertEquals(0.5f, link.status.value.volume)
        assertEquals(FormatSupport.Unknown, link.caps.formats)
        assertTrue(link.caps.hasVolume)
    }

    @Test
    fun `a device already running the media player is joined, not launched again`() {
        val (receiver, link) = connected(apps = listOf(App(Chromecast.DEFAULT_MEDIA_RECEIVER, "session-9", "web-9")))
        assertEquals(Chromecast.PLATFORM_RECEIVER, receiver.next(Chromecast.NS_CONNECTION, "CONNECT").message.destinationId)
        assertEquals("web-9", receiver.next(Chromecast.NS_CONNECTION, "CONNECT").message.destinationId)
        assertTrue(receiver.everything.none { it.type == "LAUNCH" })
        runBlocking { link.load(song, 0, play = true) }
        val load = receiver.next(Chromecast.NS_MEDIA, "LOAD")
        assertEquals("web-9", load.message.destinationId)
        assertEquals("session-9", load.body.getValue("sessionId").jsonPrimitive.content)
    }

    @Test
    fun `a device that will not open the player is not a receiver`() {
        val receiver = FakeChromecast()
        receiver.refuseLaunch = "NOT_ALLOWED"
        val link = link(receiver)
        val failure = expectFailure { link.connect() }
        assertEquals(LinkProblem.NOT_A_RECEIVER, failure.problem)
        assertEquals("Test TV would not open its media player (NOT_ALLOWED)", failure.message)
        assertEquals(LinkState.LOST, link.status.value.link)
    }

    @Test
    fun `nothing listening is unreachable`() {
        val free = ServerSocket(0, 1, loopback).use { it.localPort }
        val link = ChromecastLink(CastDevice(CastProtocol.CHROMECAST, "x", "Nobody", "127.0.0.1", free), scope, secure = { s, _, _ -> s })
            .also { links += it }
        val failure = expectFailure { link.connect() }
        assertEquals("Could not reach Nobody", failure.message)
        assertEquals(LinkProblem.UNREACHABLE, failure.problem)
        assertEquals(LinkProblem.UNREACHABLE, link.status.value.problem)
    }

    @Test
    fun `an address off the local network is not even tried`() {
        val link = ChromecastLink(CastDevice(CastProtocol.CHROMECAST, "x", "Far", "8.8.8.8", 8009), scope)
            .also { links += it }
        val started = System.nanoTime()
        val failure = expectFailure { link.connect() }
        assertTrue((System.nanoTime() - started) / 1_000_000 < 1_000)
        assertEquals("Far is not on the local network", failure.message)
        assertEquals(LinkProblem.NOT_A_RECEIVER, failure.problem)
    }

    @Test
    fun `something that says nothing when asked is not a receiver`() {
        val receiver = FakeChromecast()
        receiver.silent = true
        val link = link(receiver, quiet.copy(answerTimeoutMs = 300))
        val failure = expectFailure { link.connect() }
        assertEquals(LinkProblem.NOT_A_RECEIVER, failure.problem)
    }

    @Test
    fun `a device whose volume only its remote sets has none to offer`() {
        val receiver = FakeChromecast()
        receiver.controlType = "fixed"
        val link = link(receiver)
        runBlocking { link.connect() }
        assertFalse(link.caps.hasVolume)
    }

    @Test
    fun `TLS with the device's own self-signed certificate`() {
        val keys = KeyStore.getInstance("PKCS12").apply {
            ChromecastLinkTest::class.java.getResourceAsStream("/cast/fake-chromecast.p12").use { load(it, "fakecast".toCharArray()) }
        }
        val managers = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()).apply { init(keys, "fakecast".toCharArray()) }
        val context = SSLContext.getInstance("TLS").apply { init(managers.keyManagers, null, null) }
        val receiver = FakeChromecast(server = context.serverSocketFactory.createServerSocket(0, 1, loopback))
        // The real TLS step, as the app has it.
        val link = ChromecastLink(receiver.device, scope, timing = quiet).also { links += it }
        runBlocking { link.connect() }
        assertEquals(LinkState.CONNECTED, link.status.value.link)
        receiver.next(Chromecast.NS_RECEIVER, "LAUNCH")
        playing(receiver, link)
    }

    @Test
    fun `a plain socket where TLS was expected is not a receiver`() {
        val receiver = FakeChromecast()
        val link = ChromecastLink(receiver.device, scope, timing = quiet.copy(answerTimeoutMs = 500)).also { links += it }
        val failure = expectFailure { link.connect() }
        assertEquals(LinkProblem.NOT_A_RECEIVER, failure.problem)
        assertEquals("Test TV did not answer as a Chromecast", failure.message)
    }

    // Loading

    @Test
    fun `a load sends the song as LOAD, at its place, with a url made new for each load`() {
        val (receiver, link) = connected()
        receiver.answerLoads = false
        runBlocking { link.load(song, 30_000, play = true) }
        val status = link.status.value
        assertEquals(ReceiverState.LOADING, status.state)
        assertEquals(1, status.load)
        assertEquals("vid1", status.mediaId)
        assertEquals(30_000L, status.positionMs)

        val load = receiver.next(Chromecast.NS_MEDIA, "LOAD")
        assertEquals("web-1", load.message.destinationId)
        assertEquals("session-1", load.body.getValue("sessionId").jsonPrimitive.content)
        assertEquals(30.0, load.body.getValue("currentTime").jsonPrimitive.double, 0.0)
        assertTrue(load.body.getValue("autoplay").jsonPrimitive.boolean)
        val media = load.body.getValue("media").jsonObject
        assertEquals(song.url + "?load=1", media.getValue("contentId").jsonPrimitive.content)
        assertEquals("audio/webm", media.getValue("contentType").jsonPrimitive.content)
        assertEquals("BUFFERED", media.getValue("streamType").jsonPrimitive.content)
        val metadata = media.getValue("metadata").jsonObject
        assertEquals(3, metadata.getValue("metadataType").jsonPrimitive.int)
        assertEquals("First", metadata.getValue("title").jsonPrimitive.content)
        assertEquals("Band", metadata.getValue("artist").jsonPrimitive.content)
        assertEquals("Record", metadata.getValue("albumName").jsonPrimitive.content)
        assertEquals(song.artworkUrl, metadata.getValue("images").jsonArray[0].jsonObject.getValue("url").jsonPrimitive.content)

        // The same song again, as repeat one does.
        runBlocking { link.load(song, 0, play = true) }
        assertEquals(song.url + "?load=2", receiver.next(Chromecast.NS_MEDIA, "LOAD").body.getValue("media").jsonObject.getValue("contentId").jsonPrimitive.content)
    }

    @Test
    fun `the answer to a load plays the song where it was asked to start`() {
        val (receiver, link) = connected()
        playing(receiver, link, startMs = 30_000)
        val status = link.status.value
        assertEquals(30_000L, status.positionMs)
        assertEquals(200_000L, status.durationMs)
        assertEquals(1, status.load)
    }

    @Test
    fun `a song loaded paused is loaded without autoplay and held paused`() {
        val (receiver, link) = connected()
        runBlocking { link.load(song, 12_000, play = false) }
        assertFalse(receiver.next(Chromecast.NS_MEDIA, "LOAD").body.getValue("autoplay").jsonPrimitive.boolean)
        assertEquals(12_000L, link.await { it.state == ReceiverState.PAUSED }.positionMs)
    }

    // Controls

    @Test
    fun `play, pause, seek and stop name the song's media session`() {
        val (receiver, link) = connected()
        playing(receiver, link)
        runBlocking {
            link.pause()
            link.play()
            link.seek(45_500)
        }
        assertEquals(1L, receiver.next(Chromecast.NS_MEDIA, "PAUSE").body.getValue("mediaSessionId").jsonPrimitive.long)
        assertEquals(1L, receiver.next(Chromecast.NS_MEDIA, "PLAY").body.getValue("mediaSessionId").jsonPrimitive.long)
        val seek = receiver.next(Chromecast.NS_MEDIA, "SEEK")
        assertEquals(45.5, seek.body.getValue("currentTime").jsonPrimitive.double, 0.0)
        assertEquals(45_500L, link.status.value.positionMs)

        receiver.mediaStatus(1, "PAUSED", 45.5)
        assertEquals(45_500L, link.await { it.state == ReceiverState.PAUSED }.positionMs)

        runBlocking { link.stop() }
        assertEquals(1L, receiver.next(Chromecast.NS_MEDIA, "STOP").body.getValue("mediaSessionId").jsonPrimitive.long)
        assertEquals(ReceiverState.IDLE, link.status.value.state)
        // After a stop, what the device says about the song is about nothing this link holds.
        receiver.mediaStatus(1, "PLAYING", 3.0)
        receiver.mediaStatus(1, "IDLE", idleReason = "CANCELLED")
        Thread.sleep(200)
        assertEquals(ReceiverState.IDLE, link.status.value.state)
        assertNull(link.status.value.error)
    }

    @Test
    fun `what is asked before the device answers the load waits for the song's session`() {
        val (receiver, link) = connected()
        receiver.answerLoads = false
        runBlocking {
            link.load(song, 0, play = true)
            link.pause()
            link.seek(60_000)
        }
        val load = receiver.next(Chromecast.NS_MEDIA, "LOAD")
        assertFalse(receiver.sends(Chromecast.NS_MEDIA, "PAUSE", 300))
        receiver.mediaStatus(7, "PLAYING", 0.0, contentId = song.url + "?load=1", duration = 200.0, requestId = load.requestId)
        assertEquals(7L, receiver.next(Chromecast.NS_MEDIA, "PAUSE").body.getValue("mediaSessionId").jsonPrimitive.long)
        val seek = receiver.next(Chromecast.NS_MEDIA, "SEEK")
        assertEquals(7L, seek.body.getValue("mediaSessionId").jsonPrimitive.long)
        assertEquals(60.0, seek.body.getValue("currentTime").jsonPrimitive.double, 0.0)
    }

    @Test
    fun `a song's first status is known by its url when it answers no request of ours`() {
        val (receiver, link) = connected()
        receiver.answerLoads = false
        runBlocking { link.load(song, 0, play = true) }
        receiver.next(Chromecast.NS_MEDIA, "LOAD")
        receiver.mediaStatus(4, "BUFFERING", 0.0, contentId = song.url + "?load=1")
        receiver.mediaStatus(4, "PLAYING", 0.5)
        assertEquals(500L, link.await { it.state == ReceiverState.PLAYING }.positionMs)
        runBlocking { link.pause() }
        assertEquals(4L, receiver.next(Chromecast.NS_MEDIA, "PAUSE").body.getValue("mediaSessionId").jsonPrimitive.long)
    }

    @Test
    fun `a stop before the device answered the load stops that song once it does`() {
        val (receiver, link) = connected()
        receiver.answerLoads = false
        runBlocking { link.load(song, 0, play = true) }
        val load = receiver.next(Chromecast.NS_MEDIA, "LOAD")
        runBlocking { link.stop() }
        assertFalse(receiver.sends(Chromecast.NS_MEDIA, "STOP", 200))
        receiver.mediaStatus(3, "PLAYING", 0.0, contentId = song.url + "?load=1", requestId = load.requestId)
        assertEquals(3L, receiver.next(Chromecast.NS_MEDIA, "STOP").body.getValue("mediaSessionId").jsonPrimitive.long)
        assertEquals(ReceiverState.IDLE, link.status.value.state)
    }

    @Test
    fun `volume follows the device, a mute reads as silence, and a volume set unmutes`() {
        val (receiver, link) = connected()
        receiver.level = 0.3
        receiver.deviceStatus()
        assertEquals(0.3f, link.await { it.volume == 0.3f }.volume)
        receiver.muted = true
        receiver.deviceStatus()
        link.await { it.volume == 0f }

        runBlocking { link.setVolume(0.6f) }
        val level = receiver.next(Chromecast.NS_RECEIVER, "SET_VOLUME")
        assertEquals(0.6, level.body.getValue("volume").jsonObject.getValue("level").jsonPrimitive.double, 0.0)
        val unmute = receiver.next(Chromecast.NS_RECEIVER, "SET_VOLUME")
        assertFalse(unmute.body.getValue("volume").jsonObject.getValue("muted").jsonPrimitive.boolean)
        assertEquals(0.6f, link.await { it.volume == 0.6f }.volume)
    }

    // Following the song

    @Test
    fun `a song that plays to its end ends, and stays ended until the next load`() {
        val (receiver, link) = connected()
        playing(receiver, link)
        receiver.mediaStatus(1, "PLAYING", 199.0)
        link.await { it.positionMs == 199_000L }
        receiver.mediaStatus(1, "IDLE", idleReason = "FINISHED")
        val ended = link.await { it.state == ReceiverState.ENDED }
        assertEquals(200_000L, ended.positionMs)
        assertEquals(1, ended.load)

        receiver.mediaStatus(1, "PLAYING", 3.0)
        receiver.send(Chromecast.PLATFORM_RECEIVER, Chromecast.PLATFORM_SENDER, Chromecast.NS_HEARTBEAT, """{"type":"PING"}""")
        receiver.next(Chromecast.NS_HEARTBEAT, "PONG")
        assertEquals(ReceiverState.ENDED, link.status.value.state)

        runBlocking { link.load(song, 0, play = true) }
        assertEquals(2, link.status.value.load)
        receiver.next(Chromecast.NS_MEDIA, "LOAD")
        assertEquals(2, link.await { it.state == ReceiverState.PLAYING }.load)
    }

    @Test
    fun `a report about the song before is not taken for the new one`() {
        val (receiver, link) = connected()
        playing(receiver, link)
        receiver.answerLoads = false
        runBlocking { link.load(song, 0, play = true) }
        val second = receiver.next(Chromecast.NS_MEDIA, "LOAD")
        receiver.mediaStatus(1, "PLAYING", 187.0)
        receiver.mediaStatus(1, "IDLE", idleReason = "INTERRUPTED")
        receiver.send(Chromecast.PLATFORM_RECEIVER, Chromecast.PLATFORM_SENDER, Chromecast.NS_HEARTBEAT, """{"type":"PING"}""")
        receiver.next(Chromecast.NS_HEARTBEAT, "PONG")
        assertEquals(ReceiverState.LOADING, link.status.value.state)
        assertEquals(2, link.status.value.load)

        receiver.mediaStatus(2, "PLAYING", 0.2, contentId = song.url + "?load=2", requestId = second.requestId)
        assertEquals(200L, link.await { it.state == ReceiverState.PLAYING }.positionMs)
    }

    @Test
    fun `a load the device refuses fails with its reason, until the next load`() {
        val (receiver, link) = connected()
        receiver.answerLoads = false
        runBlocking { link.load(song, 0, play = true) }
        val load = receiver.next(Chromecast.NS_MEDIA, "LOAD")
        receiver.sendMedia("""{"type":"LOAD_FAILED","requestId":${load.requestId},"detailedErrorCode":104}""")
        val failed = link.await { it.state == ReceiverState.FAILED }
        assertEquals("Test TV could not play it (error 104)", failed.error)
        assertEquals(1, failed.load)
        receiver.mediaStatus(1, "PLAYING", 1.0, contentId = song.url + "?load=1")
        receiver.send(Chromecast.PLATFORM_RECEIVER, Chromecast.PLATFORM_SENDER, Chromecast.NS_HEARTBEAT, """{"type":"PING"}""")
        receiver.next(Chromecast.NS_HEARTBEAT, "PONG")
        assertEquals(ReceiverState.FAILED, link.status.value.state)

        receiver.answerLoads = true
        runBlocking { link.load(song, 0, play = true) }
        assertNull(link.status.value.error)
        link.await { it.state == ReceiverState.PLAYING }
    }

    @Test
    fun `a song the player cannot decode fails`() {
        val (receiver, link) = connected()
        playing(receiver, link)
        receiver.mediaStatus(1, "IDLE", idleReason = "ERROR")
        assertEquals("Test TV could not play it", link.await { it.state == ReceiverState.FAILED }.error)
    }

    @Test
    fun `a refused request that is not a load asks for the song's status`() {
        val (receiver, link) = connected()
        playing(receiver, link)
        receiver.sendMedia("""{"type":"INVALID_REQUEST","requestId":99,"reason":"INVALID_MEDIA_SESSION_ID"}""")
        val ask = receiver.next(Chromecast.NS_MEDIA, "GET_STATUS")
        // And a refusal of that question is not asked again.
        receiver.sendMedia("""{"type":"INVALID_REQUEST","requestId":${ask.requestId}}""")
        assertFalse(receiver.sends(Chromecast.NS_MEDIA, "GET_STATUS", 300))
        assertEquals(ReceiverState.PLAYING, link.status.value.state)
    }

    @Test
    fun `the song's session gone without a word ends it near its end, and stops it otherwise`() {
        val (receiver, link) = connected()
        playing(receiver, link)
        receiver.mediaStatus(1, "PLAYING", 198.5)
        link.await { it.positionMs == 198_500L }
        receiver.sendMedia("""{"type":"MEDIA_STATUS","requestId":0,"status":[]}""")
        assertEquals(200_000L, link.await { it.state == ReceiverState.ENDED }.positionMs)

        runBlocking { link.load(song, 0, play = true) }
        receiver.next(Chromecast.NS_MEDIA, "LOAD")
        link.await { it.state == ReceiverState.PLAYING && it.load == 2 }
        receiver.sendMedia("""{"type":"MEDIA_STATUS","requestId":0,"status":[]}""")
        assertEquals("Stopped on Test TV", link.await { it.state == ReceiverState.IDLE }.error)
    }

    @Test
    fun `the status is asked for while a song plays, and not once it is paused`() {
        val (receiver, link) = connected(timing = quiet.copy(statusEveryMs = 150))
        playing(receiver, link)
        receiver.next(Chromecast.NS_MEDIA, "GET_STATUS", timeoutMs = 2_000)
        receiver.mediaStatus(1, "PAUSED", 5.0)
        link.await { it.state == ReceiverState.PAUSED }
        Thread.sleep(100)
        receiver.sent.clear()
        assertFalse(receiver.sends(Chromecast.NS_MEDIA, "GET_STATUS", 500))
    }

    // Somebody else

    @Test
    fun `another sender loading on the player takes it over, and the player is then not ours to stop`() {
        val (receiver, link) = connected()
        playing(receiver, link)
        receiver.mediaStatus(2, "PLAYING", 0.0, contentId = "http://other/video.mp4")
        val taken = link.await { it.state == ReceiverState.IDLE }
        assertEquals("Something else is playing on Test TV", taken.error)

        link.close()
        receiver.next(Chromecast.NS_CONNECTION, "CLOSE")
        receiver.next(Chromecast.NS_CONNECTION, "CLOSE")
        assertTrue(receiver.everything.none { it.type == "STOP" })
    }

    @Test
    fun `our song interrupted by another load is taken over too`() {
        val (receiver, link) = connected()
        playing(receiver, link)
        receiver.mediaStatus(1, "IDLE", idleReason = "INTERRUPTED")
        assertEquals("Something else is playing on Test TV", link.await { it.state == ReceiverState.IDLE }.error)
    }

    @Test
    fun `our load cancelled by another is taken over`() {
        val (receiver, link) = connected()
        receiver.answerLoads = false
        runBlocking { link.load(song, 0, play = true) }
        val load = receiver.next(Chromecast.NS_MEDIA, "LOAD")
        receiver.sendMedia("""{"type":"LOAD_CANCELLED","requestId":${load.requestId}}""")
        assertEquals("Something else is playing on Test TV", link.await { it.state == ReceiverState.IDLE }.error)
    }

    @Test
    fun `another app on the device ends the song, and the next song opens the player again`() {
        val (receiver, link) = connected()
        playing(receiver, link)
        receiver.apps.removeAll { true }
        receiver.apps += App("233637DE", "youtube-1", "web-77")
        receiver.deviceStatus()
        receiver.send("web-1", receiver.client, Chromecast.NS_CONNECTION, """{"type":"CLOSE"}""")
        assertEquals("Something else is playing on Test TV", link.await { it.state == ReceiverState.IDLE }.error)

        runBlocking { link.load(song, 0, play = true) }
        receiver.next(Chromecast.NS_RECEIVER, "LAUNCH")
        assertEquals("web-2", receiver.next(Chromecast.NS_MEDIA, "LOAD").message.destinationId)
        assertEquals(2, link.await { it.state == ReceiverState.PLAYING }.load)
    }

    @Test
    fun `the player stopped on the device stops the song`() {
        val (receiver, link) = connected()
        playing(receiver, link)
        receiver.apps.removeAll { true }
        receiver.deviceStatus()
        assertEquals("Stopped on Test TV", link.await { it.state == ReceiverState.IDLE }.error)
    }

    @Test
    fun `the player dropping our connection while it still runs is connected to again`() {
        val (receiver, link) = connected()
        playing(receiver, link)
        receiver.sent.clear()
        receiver.send("web-1", receiver.client, Chromecast.NS_CONNECTION, """{"type":"CLOSE"}""")
        receiver.next(Chromecast.NS_RECEIVER, "GET_STATUS")
        assertEquals("web-1", receiver.next(Chromecast.NS_CONNECTION, "CONNECT").message.destinationId)
        assertEquals(ReceiverState.PLAYING, link.status.value.state)
    }

    // Staying connected

    @Test
    fun `the device's pings are answered at once`() {
        val (receiver, _) = connected()
        receiver.send(Chromecast.PLATFORM_RECEIVER, Chromecast.PLATFORM_SENDER, Chromecast.NS_HEARTBEAT, """{"type":"PING"}""")
        val pong = receiver.next(Chromecast.NS_HEARTBEAT, "PONG", timeoutMs = 1_000)
        assertEquals(Chromecast.PLATFORM_SENDER, pong.message.sourceId)
        assertEquals(Chromecast.PLATFORM_RECEIVER, pong.message.destinationId)
    }

    @Test
    fun `the link pings the device, and one that answers stays connected`() {
        val (receiver, link) = connected(timing = quiet.copy(pingEveryMs = 150, deadAfterMs = 1_000))
        receiver.next(Chromecast.NS_HEARTBEAT, "PING", timeoutMs = 2_000)
        receiver.next(Chromecast.NS_HEARTBEAT, "PING", timeoutMs = 2_000)
        Thread.sleep(1_200)
        assertEquals(LinkState.CONNECTED, link.status.value.link)
    }

    @Test
    fun `a device that goes silent is lost`() {
        val (receiver, link) = connected(timing = quiet.copy(pingEveryMs = 100, deadAfterMs = 600))
        receiver.silent = true
        val lost = link.await(3_000) { it.link == LinkState.LOST }
        assertEquals("Test TV stopped answering", lost.error)
        assertEquals(LinkProblem.STOPPED_ANSWERING, lost.problem)
    }

    @Test
    fun `the device closing its own connection hangs up, and commands then say so`() {
        val (receiver, link) = connected()
        receiver.send(Chromecast.PLATFORM_RECEIVER, Chromecast.PLATFORM_SENDER, Chromecast.NS_CONNECTION, """{"type":"CLOSE"}""")
        val lost = link.await { it.link == LinkState.LOST }
        assertEquals(LinkProblem.HUNG_UP, lost.problem)
        assertEquals("Not connected to Test TV", expectFailure { link.play() }.message)
    }

    @Test
    fun `the device dropping the connection hangs up`() {
        val (receiver, link) = connected()
        receiver.socket!!.close()
        assertEquals(LinkProblem.HUNG_UP, link.await { it.link == LinkState.LOST }.problem)
    }

    // Closing

    @Test
    fun `closing stops our song and the player it launched, and hangs up`() {
        val (receiver, link) = connected()
        playing(receiver, link)
        link.close()
        assertEquals(LinkState.CLOSED, link.status.value.link)
        assertEquals(1L, receiver.next(Chromecast.NS_MEDIA, "STOP").body.getValue("mediaSessionId").jsonPrimitive.long)
        assertEquals("session-1", receiver.next(Chromecast.NS_RECEIVER, "STOP").body.getValue("sessionId").jsonPrimitive.content)
        assertEquals("web-1", receiver.next(Chromecast.NS_CONNECTION, "CLOSE").message.destinationId)
        assertEquals(Chromecast.PLATFORM_RECEIVER, receiver.next(Chromecast.NS_CONNECTION, "CLOSE").message.destinationId)
        link.close()
    }

    /** A connected socket whose every written byte is kept, for what was said before a call returned. */
    private class Recording(private val inner: Socket) : Socket() {
        private val kept = ByteArrayOutputStream()

        val written: ByteArray get() = synchronized(kept) { kept.toByteArray() }

        override fun getInputStream(): InputStream = inner.getInputStream()

        override fun getOutputStream(): OutputStream = object : OutputStream() {
            private val out = inner.getOutputStream()

            override fun write(b: Int) = write(byteArrayOf(b.toByte()), 0, 1)

            override fun write(b: ByteArray, off: Int, len: Int) {
                synchronized(kept) { kept.write(b, off, len) }
                out.write(b, off, len)
            }

            override fun flush() = out.flush()
        }

        override fun setSoTimeout(timeout: Int) {
            inner.soTimeout = timeout
        }

        override fun close() = inner.close()
    }

    @Test
    fun `closing off the main thread has said its goodbye by the time it returns`() {
        val receiver = FakeChromecast(listOf(App("E8C28D3C", "backdrop", "web-0", idleScreen = true)))
        var socket: Recording? = null
        val link = ChromecastLink(receiver.device, scope, secure = { plain, _, _ -> Recording(plain).also { socket = it } }, timing = quiet)
            .also { links += it }
        runBlocking { link.connect() }
        playing(receiver, link)
        link.close()
        val said = ByteArrayInputStream(socket!!.written).let { input ->
            generateSequence { CastChannel.read(input) }.map { it.namespace to Chromecast.parse(it.payloadUtf8)!!.type }.toList()
        }
        assertEquals(
            listOf(
                Chromecast.NS_MEDIA to "STOP",
                Chromecast.NS_RECEIVER to "STOP",
                Chromecast.NS_CONNECTION to "CLOSE",
                Chromecast.NS_CONNECTION to "CLOSE",
            ),
            said.takeLast(4),
        )
    }

    @Test
    fun `closing leaves a player it joined running`() {
        val (receiver, link) = connected(apps = listOf(App(Chromecast.DEFAULT_MEDIA_RECEIVER, "session-9", "web-9")))
        playing(receiver, link)
        link.close()
        receiver.next(Chromecast.NS_MEDIA, "STOP")
        receiver.next(Chromecast.NS_CONNECTION, "CLOSE")
        receiver.next(Chromecast.NS_CONNECTION, "CLOSE")
        assertTrue(receiver.everything.none { it.message.namespace == Chromecast.NS_RECEIVER && it.type == "STOP" })
    }

    @Test
    fun `closing after a stop and an end sends no second stop for the song`() {
        val (receiver, link) = connected()
        playing(receiver, link)
        receiver.mediaStatus(1, "IDLE", idleReason = "FINISHED")
        link.await { it.state == ReceiverState.ENDED }
        link.close()
        receiver.next(Chromecast.NS_RECEIVER, "STOP")
        receiver.next(Chromecast.NS_CONNECTION, "CLOSE")
        receiver.next(Chromecast.NS_CONNECTION, "CLOSE")
        assertTrue(receiver.everything.none { it.message.namespace == Chromecast.NS_MEDIA && it.type == "STOP" })
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
