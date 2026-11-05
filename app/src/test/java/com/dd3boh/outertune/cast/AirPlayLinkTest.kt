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
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.BufferedInputStream
import java.io.IOException
import java.io.InputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.security.KeyPairGenerator
import java.security.PrivateKey
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlin.concurrent.thread

/**
 * The link end to end against a fake AirPlay speaker on loopback, which answers RTSP as the
 * speakers do, asks about the clock, keeps every packet it is sent, unpacks the Apple Lossless
 * frames and can lose a packet on purpose to ask for it again; and a fake cast server, which
 * serves a song whose every frame says which frame it is, so what arrives can be checked to the
 * sample. The latency is a tenth of a second here, so a test takes about as long as its songs.
 */
class AirPlayLinkTest {
    private val loopback: InetAddress = InetAddress.getByName("127.0.0.1")
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val closing = mutableListOf<AutoCloseable>()

    @After
    fun tearDown() {
        closing.reversed().forEach { runCatching { it.close() } }
        scope.cancel()
    }

    private fun songServer() = CounterSongServer().also { closing += it }

    /** One audio packet as the fake speaker kept it, unpacked. */
    class Received(val seq: Int, val timestamp: Long, val marker: Boolean, val samples: ShortArray)

    /** A fake AirPlay 1 speaker. */
    private inner class FakeSpeaker(
        private val announceStatus: (attempt: Int) -> Int = { 200 },
        private val privateKey: PrivateKey? = null,
    ) : AutoCloseable {
        val server = ServerSocket(0, 5, loopback)
        val audio = DatagramSocket(0, loopback)
        val control = DatagramSocket(0, loopback)
        val timing = DatagramSocket(0, loopback)
        val requests = CopyOnWriteArrayList<Pair<String, String>>()
        val bodies = CopyOnWriteArrayList<ByteArray>()
        val packets = ConcurrentHashMap<Int, Received>()
        val syncs = CopyOnWriteArrayList<ByteArray>()
        val timingReplies = CopyOnWriteArrayList<ByteArray>()
        val resent = CopyOnWriteArrayList<Received>()

        /** Sequence numbers to lose the first time they come, and ask for again. */
        val lose: MutableSet<Int> = Collections.newSetFromMap(ConcurrentHashMap())

        @Volatile
        var senderTimingPort = 0

        @Volatile
        var senderControlPort = 0

        @Volatile
        var socket: Socket? = null

        private var announces = 0
        private var aesKey: ByteArray? = null
        private var aesIv: ByteArray? = null

        init {
            closing += this
            thread(isDaemon = true) {
                while (true) {
                    val connection = try {
                        server.accept()
                    } catch (e: IOException) {
                        return@thread
                    }
                    socket = connection
                    thread(isDaemon = true) { rtsp(connection) }
                }
            }
            thread(isDaemon = true) { receiveAudio() }
            thread(isDaemon = true) { receiveControl() }
            thread(isDaemon = true) { askAboutTheClock() }
        }

        val device: CastDevice get() = device()

        fun device(record: Map<String, String> = mapOf("et" to "0", "cn" to "0,1", "tp" to "UDP")) =
            AirPlayDevices.of("0011223344@Test speaker", "127.0.0.1", server.localPort, record)

        private fun rtsp(connection: Socket) {
            val input = BufferedInputStream(connection.getInputStream())
            val out = connection.getOutputStream()
            try {
                while (true) {
                    val head = readHead(input)
                    if (head.isEmpty()) return
                    val lines = head.lines()
                    val (method, uri) = lines.first().split(' ')
                    val headers = lines.drop(1).filter { ':' in it }.associate { it.substringBefore(':').trim().lowercase() to it.substringAfter(':').trim() }
                    val body = ByteArray(headers["content-length"]?.toInt() ?: 0)
                    var read = 0
                    while (read < body.size) read += input.read(body, read, body.size - read).also { if (it < 0) throw IOException("eof") }
                    requests += method to head
                    bodies += body
                    val cseq = headers["cseq"]
                    var status = 200
                    val extra = StringBuilder()
                    when (method) {
                        "ANNOUNCE" -> {
                            status = announceStatus(announces++)
                            val sdp = String(body)
                            Regex("""a=rsaaeskey:(\S+)""").find(sdp)?.let { key ->
                                val cipher = Cipher.getInstance("RSA/ECB/OAEPWithSHA-1AndMGF1Padding")
                                cipher.init(Cipher.DECRYPT_MODE, privateKey)
                                aesKey = cipher.doFinal(java.util.Base64.getDecoder().decode(key.groupValues[1]))
                                aesIv = java.util.Base64.getDecoder().decode(Regex("""a=aesiv:(\S+)""").find(sdp)!!.groupValues[1])
                            }
                        }
                        "SETUP" -> {
                            val transport = RtspMessages.transport(headers["transport"])
                            senderControlPort = transport["control_port"]!!.toInt()
                            senderTimingPort = transport["timing_port"]!!.toInt()
                            extra.append("Transport: RTP/AVP/UDP;unicast;mode=record;server_port=${audio.localPort};control_port=${control.localPort};timing_port=${timing.localPort}\r\n")
                            extra.append("Session: 1\r\n")
                        }
                        "RECORD" -> extra.append("Audio-Latency: 0\r\n")
                    }
                    out.write("RTSP/1.0 $status ${if (status == 200) "OK" else "Nope"}\r\nCSeq: $cseq\r\nServer: AirTunes/366.0\r\n$extra\r\n".toByteArray())
                    out.flush()
                }
            } catch (e: IOException) {
                // The link hung up.
            }
        }

        private fun receiveAudio() {
            val buffer = ByteArray(2048)
            while (!audio.isClosed) {
                val packet = DatagramPacket(buffer, buffer.size)
                try {
                    audio.receive(packet)
                } catch (e: IOException) {
                    return
                }
                val received = unpack(buffer.copyOf(packet.length), 0)
                if (lose.remove(received.seq)) {
                    val ask = byteArrayOf(0x80.toByte(), 0xD5.toByte(), 0, 1, (received.seq shr 8).toByte(), received.seq.toByte(), 0, 1)
                    control.send(DatagramPacket(ask, ask.size, loopback, senderControlPort))
                    continue
                }
                packets[received.seq] = received
            }
        }

        private fun receiveControl() {
            val buffer = ByteArray(2048)
            while (!control.isClosed) {
                val packet = DatagramPacket(buffer, buffer.size)
                try {
                    control.receive(packet)
                } catch (e: IOException) {
                    return
                }
                val bytes = buffer.copyOf(packet.length)
                when (bytes[1].toInt() and 0xFF) {
                    0xD4 -> syncs += bytes
                    0xD6 -> {
                        val again = unpack(bytes, 4)
                        resent += again
                        packets[again.seq] = again
                    }
                }
            }
        }

        private fun askAboutTheClock() {
            val buffer = ByteArray(128)
            thread(isDaemon = true) {
                while (!timing.isClosed) {
                    val packet = DatagramPacket(buffer, buffer.size)
                    try {
                        timing.receive(packet)
                    } catch (e: IOException) {
                        return@thread
                    }
                    timingReplies += buffer.copyOf(packet.length)
                }
            }
            while (!timing.isClosed) {
                if (senderTimingPort != 0) {
                    val question = ByteArray(32)
                    question[0] = 0x80.toByte()
                    question[1] = 0xD2.toByte()
                    question[3] = 7
                    AirPlayRtp.put64(question, 24, System.nanoTime())
                    runCatching { timing.send(DatagramPacket(question, 32, loopback, senderTimingPort)) }
                }
                Thread.sleep(200)
            }
        }

        /** An RTP audio packet at [at] in [bytes]: its header, and its audio decrypted and unpacked. */
        private fun unpack(bytes: ByteArray, at: Int): Received {
            val payload = bytes.copyOfRange(at + 12, bytes.size)
            aesKey?.let { key ->
                val whole = payload.size / 16 * 16
                val cipher = Cipher.getInstance("AES/CBC/NoPadding")
                cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(aesIv))
                cipher.doFinal(payload, 0, whole, payload, 0)
            }
            return Received(
                seq = AirPlayRtp.get16(bytes, at + 2),
                timestamp = AirPlayRtp.get32(bytes, at + 4),
                marker = bytes[at + 1].toInt() and 0x80 != 0,
                samples = decodeAlac(payload),
            )
        }

        /** Hangs up, as a speaker does when another sender takes it over. */
        fun hangUp() {
            socket?.close()
        }

        fun methods() = requests.map { it.first }

        /** Every packet kept, in order, from the [n]th marker packet on (counting from 0). */
        fun stretch(n: Int): List<Received> {
            val ordered = packets.values.sortedBy { it.timestamp }
            val starts = ordered.withIndex().filter { it.value.marker }.map { it.index }
            if (n >= starts.size) return emptyList()
            return ordered.subList(starts[n], starts.getOrElse(n + 1) { ordered.size })
        }

        override fun close() {
            server.close()
            socket?.close()
            audio.close()
            control.close()
            timing.close()
        }
    }

    /** An uncompressed Apple Lossless frame's samples, read the way an ALAC decoder reads them. */
    private fun decodeAlac(data: ByteArray): ShortArray {
        var position = 0
        fun read(count: Int): Int {
            var value = 0
            repeat(count) {
                value = (value shl 1) or ((data[position / 8].toInt() shr (7 - position % 8)) and 1)
                position++
            }
            return value
        }
        assertEquals(1, read(3))
        read(4 + 12)
        assertEquals(0, read(1))
        read(2)
        assertEquals(1, read(1))
        val samples = ShortArray(352 * 2) { read(16).toShort() }
        assertEquals(7, read(3))
        return samples
    }

    private fun readHead(input: InputStream): String = CounterSongServer.readHead(input)

    /** The song frames in [packets], in order, each the frame number its samples spell; silence is left out. */
    private fun songFrames(packets: List<Received>): List<Long> = packets.flatMap { CounterSignal.frames(it.samples) }

    private fun link(speaker: FakeSpeaker, device: CastDevice = speaker.device, rsa: java.security.PublicKey? = null) =
        AirPlayLink(
            device, scope,
            latencyFrames = 4_410, leadNanos = 20_000_000, primeFrames = 1_000, pipeFrames = 44_100, rsaKey = rsa,
        ).also { link -> closing += AutoCloseable { link.close() } }

    private fun item(server: CounterSongServer, name: String, frames: Long) = server.item(name, frames)

    private suspend fun AirPlayLink.waitFor(state: ReceiverState, load: Int = status.value.load, timeoutMs: Long = 5_000): ReceiverStatus =
        withTimeout(timeoutMs) { status.first { it.state == state && it.load == load } }

    private fun eventually(timeoutMs: Long = 5_000, what: String, check: () -> Boolean) {
        val deadline = System.nanoTime() + timeoutMs * 1_000_000
        while (!check()) {
            if (System.nanoTime() > deadline) fail("Never: $what")
            Thread.sleep(20)
        }
    }

    @Test
    fun `connecting is OPTIONS, ANNOUNCE, SETUP and RECORD, and the first packet is the one RECORD named`() = runBlocking {
        val speaker = FakeSpeaker()
        val link = link(speaker)
        link.connect()
        assertEquals(LinkState.CONNECTED, link.status.value.link)
        assertEquals(AirPlayLink.FORMATS, link.caps.formats)
        assertEquals(listOf("OPTIONS", "ANNOUNCE", "SETUP", "RECORD"), speaker.methods())
        val sdp = String(speaker.bodies[1])
        assertTrue(sdp, "a=rtpmap:96 AppleLossless\r\n" in sdp && "a=fmtp:96 352 0 16 40 10 14 2 255 0 0 44100\r\n" in sdp)
        val setup = speaker.requests[2].second
        assertTrue(setup, Regex("""Transport: RTP/AVP/UDP;unicast;interleaved=0-1;mode=record;control_port=\d+;timing_port=\d+""").containsMatchIn(setup))
        val record = speaker.requests[3].second
        val (seq, rtptime) = Regex("""RTP-Info: seq=(\d+);rtptime=(\d+)""").find(record)!!.destructured
        assertTrue(record, "Session: 1" in record && "Range: npt=0-" in record)
        eventually(what = "the first packet") { speaker.stretch(0).isNotEmpty() }
        val first = speaker.stretch(0).first()
        assertTrue(first.marker)
        assertEquals(seq.toInt(), first.seq)
        assertEquals(rtptime.toLong(), first.timestamp)
        eventually(what = "a sync packet and a clock answer") { speaker.syncs.isNotEmpty() && speaker.timingReplies.isNotEmpty() }
        val sync = speaker.syncs.first()
        assertEquals(0x90, sync[0].toInt() and 0xFF)
        assertEquals(4_410L, (AirPlayRtp.get32(sync, 16) - AirPlayRtp.get32(sync, 4)) and 0xFFFFFFFFL)
        assertEquals(0xD3, speaker.timingReplies.first()[1].toInt() and 0xFF)
    }

    @Test
    fun `a song arrives whole, every frame once and in order, then ENDED, and the next one joins it with no flush`() = runBlocking {
        val speaker = FakeSpeaker()
        val songs = songServer()
        val link = link(speaker)
        link.connect()
        link.load(item(songs, "a", 22_050), 0, play = true)
        assertEquals(ReceiverState.LOADING, link.status.value.state)
        val playing = link.waitFor(ReceiverState.PLAYING, load = 1)
        assertEquals(0L, playing.positionMs)
        link.waitFor(ReceiverState.ENDED, load = 1)
        link.load(item(songs, "b", 11_025), 0, play = true)
        link.waitFor(ReceiverState.PLAYING, load = 2)
        link.waitFor(ReceiverState.ENDED, load = 2)
        Thread.sleep(300)
        assertEquals("one stretch: no FLUSH between the songs", listOf("OPTIONS", "ANNOUNCE", "SETUP", "RECORD"), speaker.methods())
        val stretch = speaker.stretch(0)
        // Every packet of the stretch, one after another.
        for ((before, after) in stretch.zipWithNext()) {
            assertEquals((before.seq + 1) and 0xFFFF, after.seq)
            assertEquals((before.timestamp + 352) and 0xFFFFFFFFL, after.timestamp)
        }
        val frames = songFrames(stretch)
        assertEquals((0L until 22_050L).toList() + (0L until 11_025L).toList(), frames)
    }

    @Test
    fun `pause flushes the speaker and play goes on from the very frame it was heard at`() = runBlocking {
        val speaker = FakeSpeaker()
        val songs = songServer()
        val link = link(speaker)
        link.connect()
        link.load(item(songs, "long", 441_000), 0, play = true)
        link.waitFor(ReceiverState.PLAYING, load = 1)
        Thread.sleep(400)
        link.pause()
        val paused = link.status.value
        assertEquals(ReceiverState.PAUSED, paused.state)
        assertTrue("${paused.positionMs}", paused.positionMs in 250L..600L)
        val flush = speaker.requests.last { it.first == "FLUSH" }.second
        assertTrue(flush, Regex("""RTP-Info: seq=\d+;rtptime=\d+""").containsMatchIn(flush))
        link.play()
        link.waitFor(ReceiverState.PLAYING, load = 1)
        Thread.sleep(200)
        val resumed = songFrames(speaker.stretch(1))
        val pausedFrame = paused.positionMs * 44_100 / 1000
        assertTrue("resumed at ${resumed.first()}, paused at $pausedFrame", kotlin.math.abs(resumed.first() - pausedFrame) <= 44)
        // And not a frame repeated or lost from there on.
        assertEquals((resumed.first() until resumed.first() + resumed.size).toList(), resumed)
        link.pause()
        assertEquals(2, speaker.methods().count { it == "FLUSH" })
    }

    @Test
    fun `a seek flushes and the speaker gets the song from the frame asked for`() = runBlocking {
        val speaker = FakeSpeaker()
        val songs = songServer()
        val link = link(speaker)
        link.connect()
        link.load(item(songs, "long", 441_000), 0, play = true)
        link.waitFor(ReceiverState.PLAYING, load = 1)
        link.seek(5_000)
        assertEquals(ReceiverState.LOADING, link.status.value.state)
        val playing = link.waitFor(ReceiverState.PLAYING, load = 1)
        assertEquals(5_000L, playing.positionMs)
        Thread.sleep(200)
        assertEquals(220_500L, songFrames(speaker.stretch(1)).first())
        assertTrue("FLUSH" in speaker.methods())
    }

    @Test
    fun `a seek to just before the end ends the song once it is heard, not before`() = runBlocking {
        val speaker = FakeSpeaker()
        val songs = songServer()
        val link = link(speaker)
        link.connect()
        link.load(item(songs, "long", 441_000), 0, play = true)
        link.waitFor(ReceiverState.PLAYING, load = 1)
        val asked = System.nanoTime()
        // 20 ms left: all of it is sent at once, well before the speaker (100 ms behind) reaches it.
        link.seek(9_980)
        val ended = link.waitFor(ReceiverState.ENDED, load = 1)
        assertTrue("ended after ${(System.nanoTime() - asked) / 1_000_000} ms", System.nanoTime() - asked >= 100_000_000L)
        assertEquals(10_000L, ended.positionMs)
        Thread.sleep(200)
        assertEquals((440_118L until 441_000L).toList(), songFrames(speaker.stretch(1)))
    }

    @Test
    fun `a song loaded paused is PAUSED once its audio is there, and plays from there`() = runBlocking {
        val speaker = FakeSpeaker()
        val songs = songServer()
        val link = link(speaker)
        link.connect()
        link.load(item(songs, "held", 88_200), 1_000, play = false)
        val paused = link.waitFor(ReceiverState.PAUSED, load = 1)
        assertEquals(1_000L, paused.positionMs)
        link.play()
        link.waitFor(ReceiverState.PLAYING, load = 1)
        Thread.sleep(200)
        val frames = songFrames(speaker.packets.values.sortedBy { it.timestamp })
        assertEquals(44_100L, frames.first())
    }

    @Test
    fun `a lost packet is sent again when the speaker asks`() = runBlocking {
        val speaker = FakeSpeaker()
        val songs = songServer()
        val link = link(speaker)
        link.connect()
        eventually(what = "the first packet") { speaker.stretch(0).isNotEmpty() }
        val lost = (speaker.stretch(0).first().seq + 40) and 0xFFFF
        speaker.lose += lost
        link.load(item(songs, "a", 22_050), 0, play = true)
        link.waitFor(ReceiverState.ENDED, load = 1)
        eventually(what = "the resend") { speaker.resent.any { it.seq == lost } }
        assertEquals((0L until 22_050L).toList(), songFrames(speaker.stretch(0)))
    }

    @Test
    fun `a speaker that shows songs is told the title and where the song stands, as its first packet goes`() = runBlocking {
        val speaker = FakeSpeaker()
        val songs = songServer()
        val link = link(speaker, speaker.device(mapOf("et" to "0", "md" to "0,1,2")))
        link.connect()
        link.load(songs.item("a", 22_050).copy(title = "Starboy", artist = "The Weeknd", album = "Starboy"), 0, play = true)
        link.waitFor(ReceiverState.ENDED, load = 1)
        eventually(what = "the song's details") { speaker.methods().count { it == "SET_PARAMETER" } >= 2 }
        val details = speaker.requests.indexOfFirst { it.first == "SET_PARAMETER" && "application/x-dmap-tagged" in it.second }
        assertTrue(details >= 0)
        assertTrue(String(speaker.bodies[details]).let { "minm" in it && "Starboy" in it && "asar" in it && "The Weeknd" in it })
        val progress = speaker.requests.indexOfFirst { it.first == "SET_PARAMETER" && "text/parameters" in it.second }
        val (start, now, end) = Regex("""progress: (\d+)/(\d+)/(\d+)""").find(String(speaker.bodies[progress]))!!.destructured
        assertEquals(22_050L, end.toLong() - start.toLong())
        // From the first packet of the song, which is the song's start.
        assertEquals(start, now)
        val first = speaker.stretch(0).first { packet -> CounterSignal.frames(packet.samples).firstOrNull() == 0L }
        assertEquals(first.timestamp, start.toLong())
        assertTrue(speaker.requests[progress].second, "RTP-Info: rtptime=$start" in speaker.requests[progress].second)
    }

    @Test
    fun `the volume goes as decibels, and close tears the session down`() = runBlocking {
        val speaker = FakeSpeaker()
        val link = link(speaker)
        link.connect()
        link.setVolume(0.5f)
        assertEquals(0.5f, link.status.value.volume)
        val index = speaker.methods().indexOf("SET_PARAMETER")
        assertEquals("volume: -15.000000\r\n", String(speaker.bodies[index]))
        assertTrue("Content-Type: text/parameters" in speaker.requests[index].second)
        link.close()
        eventually(what = "TEARDOWN") { "TEARDOWN" in speaker.methods() }
        assertEquals(LinkState.CLOSED, link.status.value.link)
    }

    @Test
    fun `MFi speakers get auth-setup first, and one that refuses the announce without it gets it then`() = runBlocking {
        val mfi = FakeSpeaker()
        val link = link(mfi, mfi.device(mapOf("et" to "0,4")))
        link.connect()
        assertEquals(listOf("OPTIONS", "POST", "ANNOUNCE", "SETUP", "RECORD"), mfi.methods())
        assertTrue(mfi.requests[1].second.startsWith("POST /auth-setup RTSP/1.0"))
        val body = mfi.bodies[1]
        assertEquals(33, body.size)
        assertEquals(1, body[0].toInt())

        val picky = FakeSpeaker(announceStatus = { attempt -> if (attempt == 0) 403 else 200 })
        val second = link(picky, picky.device(mapOf("et" to "0")))
        second.connect()
        assertEquals(listOf("OPTIONS", "ANNOUNCE", "POST", "ANNOUNCE", "SETUP", "RECORD"), picky.methods())
    }

    @Test
    fun `a speaker that wants a password or refuses the stream is not a receiver this can cast to`() = runBlocking {
        val locked = FakeSpeaker()
        try {
            link(locked, locked.device(mapOf("pw" to "true"))).connect()
            fail("connected to a speaker that wants a password")
        } catch (e: CastLinkException) {
            assertEquals(LinkProblem.NOT_A_RECEIVER, e.problem)
        }
        val refusing = FakeSpeaker(announceStatus = { 406 })
        val link = link(refusing)
        try {
            link.connect()
            fail("connected to a speaker that refused the stream")
        } catch (e: CastLinkException) {
            assertEquals(LinkProblem.NOT_A_RECEIVER, e.problem)
        }
        assertEquals(LinkState.LOST, link.status.value.link)
    }

    @Test
    fun `RSA speakers get the AES key sealed with their key, and the audio encrypted under it`() = runBlocking {
        val keys = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
        val speaker = FakeSpeaker(privateKey = keys.private)
        val songs = songServer()
        val link = link(speaker, speaker.device(mapOf("et" to "1")), rsa = keys.public)
        link.connect()
        val announce = speaker.requests[1].second
        assertTrue(announce, Regex("""Apple-Challenge: [A-Za-z0-9+/]{22}\r?$""", RegexOption.MULTILINE).containsMatchIn(announce))
        val sdp = String(speaker.bodies[1])
        assertTrue(sdp, "a=rsaaeskey:" in sdp && "a=aesiv:" in sdp)
        link.load(item(songs, "secret", 11_025), 0, play = true)
        link.waitFor(ReceiverState.ENDED, load = 1)
        Thread.sleep(200)
        assertEquals((0L until 11_025L).toList(), songFrames(speaker.stretch(0)))
    }

    @Test
    fun `a speaker that hangs up while idle is stopped on the device, and play connects again`() = runBlocking {
        val speaker = FakeSpeaker()
        val songs = songServer()
        val link = link(speaker)
        link.connect()
        link.load(item(songs, "a", 88_200), 0, play = false)
        link.waitFor(ReceiverState.PAUSED, load = 1)
        speaker.hangUp()
        link.waitFor(ReceiverState.IDLE, load = 1)
        assertEquals(LinkState.CONNECTED, link.status.value.link)
        link.load(item(songs, "a", 88_200), 500, play = true)
        link.waitFor(ReceiverState.PLAYING, load = 2)
        assertEquals(2, speaker.methods().count { it == "ANNOUNCE" })
    }

    @Test
    fun `a speaker that hangs up while playing is lost`() = runBlocking {
        val speaker = FakeSpeaker()
        val songs = songServer()
        val link = link(speaker)
        link.connect()
        link.load(item(songs, "a", 441_000), 0, play = true)
        link.waitFor(ReceiverState.PLAYING, load = 1)
        speaker.hangUp()
        val lost = withTimeout(5_000) { link.status.first { it.link == LinkState.LOST } }
        assertEquals(LinkProblem.HUNG_UP, lost.problem)
        assertNotNull(lost.error)
    }

    @Test
    fun `stop flushes and leaves the speaker idle`() = runBlocking {
        val speaker = FakeSpeaker()
        val songs = songServer()
        val link = link(speaker)
        link.connect()
        link.load(item(songs, "a", 441_000), 0, play = true)
        link.waitFor(ReceiverState.PLAYING, load = 1)
        link.stop()
        assertEquals(ReceiverState.IDLE, link.status.value.state)
        assertTrue("FLUSH" in speaker.methods())
    }
}
