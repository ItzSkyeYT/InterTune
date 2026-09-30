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
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

/**
 * The link against a fake renderer on a loopback port: a description, SOAP answers built as real
 * renderers build them, and a transport state the tests set by hand.
 */
class DlnaLinkTest {
    private val loopback: InetAddress = InetAddress.getByName("127.0.0.1")
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val renderers = mutableListOf<FakeRenderer>()
    private val links = mutableListOf<DlnaLink>()

    @After
    fun tearDown() {
        links.forEach { it.close() }
        renderers.forEach { it.close() }
        scope.cancel()
    }

    private class Call(val action: String, val args: Map<String, String>)

    private inner class FakeRenderer {
        val server = ServerSocket(0, 16, loopback)
        val calls = CopyOnWriteArrayList<Call>()
        val faults = ConcurrentHashMap<String, MutableList<Int>>()

        @Volatile
        var state = "NO_MEDIA_PRESENT"

        @Volatile
        var transportStatus = "OK"

        @Volatile
        var relTime = "0:00:00"

        @Volatile
        var trackDuration = "0:00:00"

        @Volatile
        var sink = "http-get:*:audio/mpeg:*,http-get:*:audio/mp4:DLNA.ORG_PN=AAC_ISO_320,http-get:*:audio/flac:*"

        @Volatile
        var descriptionStatus = 200

        @Volatile
        var protocolInfoBroken = false

        private val inFlight = AtomicInteger()
        val mostAtOnce = AtomicInteger()

        init {
            renderers += this
            thread(isDaemon = true) {
                while (true) {
                    val socket = try {
                        server.accept()
                    } catch (e: IOException) {
                        return@thread
                    }
                    thread(isDaemon = true) { socket.use { serve(it) } }
                }
            }
        }

        val location get() = "http://127.0.0.1:${server.localPort}/desc.xml"

        fun calls(action: String) = calls.filter { it.action == action }

        private fun serve(socket: Socket) {
            val now = inFlight.incrementAndGet()
            mostAtOnce.accumulateAndGet(now) { a, b -> maxOf(a, b) }
            var answered = false
            try {
                val input = socket.getInputStream()
                val head = (CastHttp.readHead(input) as? HeadRead.Head)?.text ?: return
                val request = CastHttp.parseRequest(head) ?: return
                val length = request.header("content-length")?.toInt() ?: 0
                val body = String(input.readNBytes(length), Charsets.UTF_8)
                val (status, answer) = when {
                    request.method == "GET" && request.target == "/desc.xml" -> descriptionStatus to description()
                    request.method == "POST" -> act(request.header("soapaction")!!.trim('"').substringAfter('#'), body)
                    else -> 404 to "no"
                }
                val bytes = answer.toByteArray(Charsets.UTF_8)
                // Counted as done before the answer goes: the link cannot ask again until it has it.
                inFlight.decrementAndGet()
                answered = true
                socket.getOutputStream().write(
                    ("HTTP/1.1 $status X\r\nContent-Type: text/xml; charset=\"utf-8\"\r\nContent-Length: ${bytes.size}\r\n" +
                        "Connection: close\r\n\r\n").toByteArray() + bytes,
                )
            } finally {
                if (!answered) inFlight.decrementAndGet()
            }
        }

        private fun description() = """<?xml version="1.0"?><root xmlns="urn:schemas-upnp-org:device-1-0"><device>
            <deviceType>urn:schemas-upnp-org:device:MediaRenderer:1</deviceType>
            <friendlyName>Kitchen speaker</friendlyName><manufacturer>Acme</manufacturer><modelName>S1</modelName>
            <UDN>uuid:kitchen</UDN><serviceList>
            <service><serviceType>urn:schemas-upnp-org:service:AVTransport:1</serviceType><controlURL>/ctl/avt</controlURL></service>
            <service><serviceType>urn:schemas-upnp-org:service:RenderingControl:1</serviceType><controlURL>/ctl/rc</controlURL></service>
            <service><serviceType>urn:schemas-upnp-org:service:ConnectionManager:1</serviceType><controlURL>/ctl/cm</controlURL></service>
            </serviceList></device></root>"""

        private fun act(action: String, body: String): Pair<Int, String> {
            val call = UpnpXml.find(UpnpXml.parse(body)!!, action)!!
            val args = UpnpXml.children(call).associate { UpnpXml.localName(it) to it.textContent }
            calls += Call(action, args)
            faults[action]?.let { codes ->
                synchronized(codes) {
                    if (codes.isNotEmpty()) {
                        val code = codes.removeAt(0)
                        return 500 to fault(code)
                    }
                }
            }
            val values: List<Pair<String, String>> = when (action) {
                "GetProtocolInfo" -> if (protocolInfoBroken) return 500 to "oops" else listOf("Source" to "", "Sink" to sink)
                "GetVolume" -> listOf("CurrentVolume" to "40")
                "GetTransportInfo" -> listOf("CurrentTransportState" to state, "CurrentTransportStatus" to transportStatus, "CurrentSpeed" to "1")
                "GetPositionInfo" -> listOf("Track" to "1", "TrackDuration" to trackDuration, "RelTime" to relTime, "TrackURI" to "")
                "SetAVTransportURI" -> {
                    state = "STOPPED"
                    emptyList()
                }
                "Play" -> {
                    state = "TRANSITIONING"
                    emptyList()
                }
                "Stop" -> {
                    state = "STOPPED"
                    emptyList()
                }
                else -> emptyList()
            }
            val service = if (action.contains("Volume")) "RenderingControl" else if (action == "GetProtocolInfo") "ConnectionManager" else "AVTransport"
            val inner = values.joinToString("") { (name, value) -> "<$name>${UpnpXml.escape(value)}</$name>" }
            return 200 to """<?xml version="1.0"?><s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/"><s:Body>""" +
                """<u:${action}Response xmlns:u="urn:schemas-upnp-org:service:$service:1">$inner</u:${action}Response></s:Body></s:Envelope>"""
        }

        private fun fault(code: Int) = """<?xml version="1.0"?><s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/"><s:Body><s:Fault>
            <faultcode>s:Client</faultcode><faultstring>UPnPError</faultstring><detail><UPnPError xmlns="urn:schemas-upnp-org:control-1-0">
            <errorCode>$code</errorCode><errorDescription>Refused</errorDescription></UPnPError></detail></s:Fault></s:Body></s:Envelope>"""

        fun failNext(action: String, code: Int) {
            faults.getOrPut(action) { mutableListOf() }.let { synchronized(it) { it += code } }
        }

        fun close() = runCatching { server.close() }
    }

    private val fast = DlnaLink.Timing(pollActiveMs = 40, pollIdleMs = 80, failuresBeforeLost = 3, startGraceMs = 8_000, volumeEvery = 1000)

    private fun link(renderer: FakeRenderer, name: String = "127.0.0.1"): DlnaLink = DlnaLink(
        CastDevice(CastProtocol.DLNA, "location:${renderer.location}", name, "127.0.0.1", renderer.server.localPort, renderer.location),
        scope,
        LanHttp(answerTimeoutMs = 3_000),
        timing = fast,
    ).also { links += it }

    private fun connected(): Pair<FakeRenderer, DlnaLink> {
        val renderer = FakeRenderer()
        val link = link(renderer)
        runBlocking { link.connect() }
        return renderer to link
    }

    private fun DlnaLink.await(timeoutMs: Long = 5_000, predicate: (ReceiverStatus) -> Boolean): ReceiverStatus =
        runBlocking { withTimeout(timeoutMs) { status.first(predicate) } }

    private val song = CastItem(
        mediaId = "vid1",
        url = "http://127.0.0.1:47800/c/tok/s/vid1.m4a",
        mimeType = "audio/mp4",
        length = 3_000_000,
        durationMs = 200_000,
        title = "Tom & Jerry",
        artist = "Band",
    )

    @Test
    fun `connecting reads the description, what the renderer plays and its volume`() {
        val (renderer, link) = connected()
        assertEquals(LinkState.CONNECTED, link.status.value.link)
        assertEquals(0.4f, link.status.value.volume)
        assertEquals(FormatSupport.Listed(setOf("audio/mpeg", "audio/mp4", "audio/flac")), link.caps.formats)
        assertTrue(link.caps.hasVolume)
        // Added by address, it had only its host for a name; now it has its own.
        assertEquals("Kitchen speaker", link.device.name)
        assertEquals("uuid:kitchen", link.device.id)
        assertEquals("Acme S1", link.device.model)
        assertEquals(1, renderer.calls("GetProtocolInfo").size)
    }

    @Test
    fun `a renderer that will not say what it plays is still connected, and sent songs as they are`() {
        val renderer = FakeRenderer().apply { protocolInfoBroken = true }
        val link = link(renderer, name = "Speaker")
        runBlocking { link.connect() }
        assertEquals(FormatSupport.Unknown, link.caps.formats)
        assertEquals("Speaker", link.device.name)
    }

    @Test
    fun `a description that cannot be had is a readable error`() {
        val renderer = FakeRenderer().apply { descriptionStatus = 404 }
        val link = link(renderer, name = "Speaker")
        try {
            runBlocking { link.connect() }
            fail("Connected without a description")
        } catch (e: CastLinkException) {
            assertEquals("Speaker did not describe itself (HTTP 404)", e.message)
            assertEquals(LinkProblem.NOT_A_RECEIVER, e.problem)
        }
        assertEquals(LinkState.LOST, link.status.value.link)
        assertEquals(LinkProblem.NOT_A_RECEIVER, link.status.value.problem)
    }

    @Test
    fun `a renderer that cannot be reached says so`() {
        val renderer = FakeRenderer().apply { close() }
        val link = link(renderer, name = "Speaker")
        try {
            runBlocking { link.connect() }
            fail("Connected to nothing")
        } catch (e: CastLinkException) {
            assertEquals("Could not reach Speaker", e.message)
            assertEquals(LinkProblem.UNREACHABLE, e.problem)
        }
        assertEquals(LinkProblem.UNREACHABLE, link.status.value.problem)
    }

    @Test
    fun `a load sets the uri with its metadata, then plays, and the polls follow it to the end`() {
        val (renderer, link) = connected()
        runBlocking { link.load(song, 0, play = true) }
        val set = renderer.calls("SetAVTransportURI").single()
        assertEquals("0", set.args["InstanceID"])
        assertEquals(song.url, set.args["CurrentURI"])
        assertEquals(Didl.track(song), set.args["CurrentURIMetaData"])
        assertEquals("1", renderer.calls("Play").single().args["Speed"])
        assertTrue(renderer.calls.indexOfFirst { it.action == "Play" } > renderer.calls.indexOfFirst { it.action == "SetAVTransportURI" })

        renderer.trackDuration = "0:03:20"
        renderer.relTime = "0:03:17"
        renderer.state = "PLAYING"
        val playing = link.await { it.state == ReceiverState.PLAYING }
        assertEquals(197_000L, playing.positionMs)
        assertEquals(200_000L, playing.durationMs)
        assertEquals("vid1", playing.mediaId)

        renderer.relTime = "0:00:00"
        renderer.state = "STOPPED"
        val ended = link.await { it.state == ReceiverState.ENDED }
        assertEquals(1, ended.load)
    }

    @Test
    fun `a renderer locked while playing is stopped first and asked again`() {
        val (renderer, link) = connected()
        renderer.failNext("SetAVTransportURI", Soap.TRANSPORT_LOCKED)
        runBlocking { link.load(song, 0, play = true) }
        assertEquals(listOf("SetAVTransportURI", "Stop", "SetAVTransportURI", "Play"), renderer.calls.map { it.action }.filter { !it.startsWith("Get") })
    }

    @Test
    fun `a song the renderer refuses fails, with its reason`() {
        val (renderer, link) = connected()
        renderer.failNext("SetAVTransportURI", 714)
        try {
            runBlocking { link.load(song, 0, play = true) }
            fail("Loaded a refused song")
        } catch (e: UpnpFault) {
            assertEquals(714, e.code)
        }
        assertEquals(ReceiverState.FAILED, link.status.value.state)
        assertEquals("SetAVTransportURI was refused (714, Refused)", link.status.value.error)
        assertTrue(renderer.calls("Play").isEmpty())
    }

    @Test
    fun `a renderer that stops without playing fails the song once the grace is over`() {
        val renderer = FakeRenderer()
        val link = DlnaLink(
            CastDevice(CastProtocol.DLNA, "x", "TV", "127.0.0.1", renderer.server.localPort, renderer.location),
            scope, LanHttp(answerTimeoutMs = 3_000), timing = fast.copy(startGraceMs = 300),
        ).also { links += it }
        runBlocking {
            link.connect()
            link.load(song, 0, play = true)
        }
        renderer.state = "STOPPED"
        val failed = link.await { it.state == ReceiverState.FAILED }
        assertEquals("TV stopped without playing it", failed.error)
    }

    @Test
    fun `a failed song is reported once, not again at every poll`() {
        val renderer = FakeRenderer()
        val link = DlnaLink(
            CastDevice(CastProtocol.DLNA, "x", "TV", "127.0.0.1", renderer.server.localPort, renderer.location),
            scope, LanHttp(answerTimeoutMs = 3_000), timing = fast.copy(startGraceMs = 300),
        ).also { links += it }
        runBlocking {
            link.connect()
            link.load(song, 0, play = true)
        }
        renderer.state = "STOPPED"
        val failed = link.await { it.state == ReceiverState.FAILED }
        // Four more polls, 80 ms apart once the song is no longer active.
        val polled = renderer.calls("GetTransportInfo").size
        val deadline = System.currentTimeMillis() + 5_000
        while (renderer.calls("GetTransportInfo").size < polled + 4 && System.currentTimeMillis() < deadline) Thread.sleep(20)
        assertTrue(renderer.calls("GetTransportInfo").size >= polled + 4)
        // The same report, at the same time: nothing new went out to whoever acts on failures.
        assertEquals(failed, link.status.value)
    }

    @Test
    fun `a start past 0 is sought once the renderer plays`() {
        val (renderer, link) = connected()
        runBlocking { link.load(song, 90_000, play = true) }
        assertTrue(renderer.calls("Seek").isEmpty())
        renderer.relTime = "0:00:01"
        renderer.state = "PLAYING"
        val deadline = System.currentTimeMillis() + 5_000
        while (renderer.calls("Seek").isEmpty() && System.currentTimeMillis() < deadline) Thread.sleep(20)
        val seek = renderer.calls("Seek").single()
        assertEquals("REL_TIME", seek.args["Unit"])
        assertEquals("0:01:30", seek.args["Target"])
    }

    @Test
    fun `pause, seek, volume and stop are the actions they should be`() {
        val (renderer, link) = connected()
        runBlocking { link.load(song, 0, play = true) }
        renderer.state = "PLAYING"
        renderer.relTime = "0:00:05"
        link.await { it.state == ReceiverState.PLAYING }
        runBlocking {
            link.pause()
            link.seek(61_900)
            link.setVolume(0.555f)
            link.stop()
        }
        assertEquals("0", renderer.calls("Pause").single().args["InstanceID"])
        assertEquals("0:01:01", renderer.calls("Seek").single().args["Target"])
        val volume = renderer.calls("SetVolume").single().args
        assertEquals("Master", volume["Channel"])
        assertEquals("56", volume["DesiredVolume"])
        assertEquals(0.555f, link.status.value.volume)
        renderer.calls("Stop").single()
        assertEquals(ReceiverState.IDLE, link.status.value.state)
    }

    @Test
    fun `calls never overlap, polls included`() {
        val (renderer, link) = connected()
        runBlocking {
            link.load(song, 0, play = true)
            repeat(10) {
                link.pause()
                link.play()
            }
        }
        assertEquals(1, renderer.mostAtOnce.get())
    }

    @Test
    fun `a renderer that stops answering is lost after three polls`() {
        val (renderer, link) = connected()
        runBlocking { link.load(song, 0, play = true) }
        renderer.close()
        val lost = link.await { it.link == LinkState.LOST }
        assertEquals("Lost Kitchen speaker", lost.error)
        assertEquals(LinkProblem.LOST, lost.problem)
        try {
            runBlocking { link.play() }
            fail("Played on a lost renderer")
        } catch (e: CastLinkException) {
            // Expected.
        }
    }
}
