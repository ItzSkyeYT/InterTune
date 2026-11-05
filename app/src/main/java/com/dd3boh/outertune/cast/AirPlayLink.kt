/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.cast

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException
import java.math.BigInteger
import java.net.DatagramSocket
import java.net.InetSocketAddress
import java.net.Socket
import java.security.KeyFactory
import java.security.PublicKey
import java.security.SecureRandom
import java.security.spec.RSAPublicKeySpec
import java.util.zip.CRC32
import javax.crypto.Cipher
import javax.net.SocketFactory

/**
 * A link to an AirPlay speaker, AirPlay 1 (RAOP): an RTSP session for the speaker's say, and the
 * audio pushed to it in real time ([AirPlayStream]).
 *
 * A speaker here does not fetch anything, so the link fetches for it: each song comes as the cast
 * server's raw audio, L16 at 44.1 kHz in stereo, which [caps] makes the remote player choose
 * (CastFormats picks raw audio for a receiver that lists nothing else), read from the server's url
 * like any receiver would ([AirPlayFetch]) and sent on in packets as they fall due. The remote
 * player drives it exactly as it drives the others: load, play, pause, seek, stop, volume; the
 * queue stays the app's.
 *
 * Connecting is OPTIONS, the auth-setup step for speakers that list MFi-SAP, ANNOUNCE with the
 * audio's description, SETUP with the phone's control and timing ports, and RECORD, after which
 * the phone sends silence until a song is there. A pause is a FLUSH, which empties the speaker,
 * and play sends again from where the speaker was heard; a seek is the same at another place; a
 * song that follows the one before carries straight on from it, so songs join without a gap. The
 * speaker is two seconds behind the phone in all of it, which the reports allow for: a song is
 * PLAYING from the moment its first frame is heard, until then LOADING.
 *
 * The speaker reports nothing; what it plays is worked out from what was sent and when. It is
 * lost when its RTSP connection breaks while it plays, or when it stops asking about the clock.
 * A speaker that hangs up while paused or idle, as one does when another sender takes it over, is
 * taken as stopped on the device, and connected to again on the next play.
 */
class AirPlayLink(
    device: CastDevice,
    private val scope: CoroutineScope,
    private val sockets: SocketFactory = SocketFactory.getDefault(),
    /** Binds a UDP socket to the Wi-Fi network before it is used (Network.bindSocket in the app). */
    private val bindUdp: (DatagramSocket) -> Unit = {},
    private val userAgent: String = "InterTune",
    private val latencyFrames: Int = AirPlay.LATENCY_FRAMES,
    private val clock: AirPlayClock = AirPlayClock(),
    private val pipeFrames: Int = PIPE_FRAMES,
    private val primeFrames: Int = PRIME_FRAMES,
    /** The AirPort Express's key, for speakers that take only RSA; a test passes its own. */
    private val rsaKey: PublicKey? = null,
    /** Logs a checksum of every second of audio sent, for the fake receiver to compare (debug builds). */
    private val audit: Boolean = false,
    /** A song's cover as a speaker with a screen takes it: its type and bytes, or null. Blocking. */
    private val covers: (url: String) -> Pair<String, ByteArray>? = AirPlayCovers::fetch,
    private val leadNanos: Long = AirPlayStream.LEAD_NANOS,
) : ReceiverLink {
    @Volatile
    override var device: CastDevice = device
        private set

    @Volatile
    override var caps: LinkCaps = LinkCaps()
        private set

    private val _status = MutableStateFlow(ReceiverStatus())
    override val status: StateFlow<ReceiverStatus> = _status.asStateFlow()

    private val random = SecureRandom()
    private val state = Any()

    @Volatile
    private var closed = false

    @Volatile
    private var session: Session? = null
    private var watchdog: Job? = null

    /** The song the speaker holds, or is being given. Guarded by [state]. */
    private var current: Song? = null
    private var loads = 0

    /** The session frame from which the next stretch may start: no earlier than the last FLUSH. */
    private var flushedAt = Long.MIN_VALUE

    /** When the last song's last frame was sent, on the clock; the stretch stops a little after it is heard. */
    private var endedAt = 0L

    private inner class Song(val number: Int, val item: CastItem, val totalFrames: Long) {
        var pipe = PcmPipe(pipeFrames)
        var fetch: AirPlayFetch? = null

        /** The song frame the next frame taken from the pipe is, and where the fetch under way started. */
        var nextFrame = 0L
        var openedAt = 0L
        var playing = false
        var primed = false

        /** Every frame has been sent. ENDED is said then, or once the song is heard if it has not been yet. */
        var done = false
        var endPending = false
        var failed = false

        /** The cover, fetched once for the song, for a speaker that shows it (md 1). */
        @Volatile
        var cover: Pair<String, ByteArray>? = null

        @Volatile
        var coverTried = false

        /** The last frame the speaker was heard to play, and when, for a pause or a gap. */
        var heardFrame = -1L
        var heardAt = 0L

        fun open(frame: Long) {
            fetch?.close()
            pipe.close()
            pipe = PcmPipe(pipeFrames)
            nextFrame = frame
            openedAt = frame
            primed = false
            done = false
            endPending = false
            failed = false
            heardFrame = -1
            fetch = AirPlayFetch(item.url, frame, totalFrames, pipe, sockets, "AirPlay-fetch-$number").start()
        }

        fun release() {
            fetch?.close()
            fetch = null
            pipe.close()
        }
    }

    /** One RTSP session and its stream: made on connecting, and again after the speaker hung up while idle. */
    private class Session(val rtsp: AirPlayRtsp, val stream: AirPlayStream) {
        @Volatile
        var hungUp = false

        fun close() {
            stream.close()
            rtsp.close()
        }
    }

    override suspend fun connect() {
        synchronized(state) {
            check(_status.value.link == LinkState.NEW) { "A link connects once" }
            _status.update { it.copy(link = LinkState.CONNECTING) }
        }
        try {
            session = withContext(Dispatchers.IO) { open() }
        } catch (e: IOException) {
            val failure = e as? CastLinkException ?: CastLinkException("Could not reach ${device.name}: ${e.message}", e, LinkProblem.UNREACHABLE)
            lose(failure.message ?: "Could not reach ${device.name}", failure.problem)
            throw failure
        }
        caps = LinkCaps(formats = FORMATS, canSetSpeed = false, hasVolume = true)
        synchronized(state) {
            if (closed) {
                session?.close()
                throw CastLinkException("Closed")
            }
            _status.update { it.copy(link = LinkState.CONNECTED) }
        }
        Log.i(TAG, "Connected to ${device.name} at ${device.host}:${device.port}")
        watchdog = scope.launch(Dispatchers.IO) { watch() }
    }

    /** OPTIONS, auth-setup where wanted, ANNOUNCE, SETUP and RECORD; the session, sending silence. */
    private fun open(): Session {
        val plan = when (val plan = AirPlayDevices.txtOf(device).plan()) {
            is AirPlayPlan.Ok -> plan
            is AirPlayPlan.No -> throw CastLinkException("${device.name}: ${plan.reason}", problem = LinkProblem.NOT_A_RECEIVER)
        }
        val socket: Socket = sockets.createSocket()
        try {
            socket.connect(InetSocketAddress(device.host, device.port), CONNECT_TIMEOUT_MS)
            socket.tcpNoDelay = true
        } catch (e: IOException) {
            runCatching { socket.close() }
            throw CastLinkException("Could not reach ${device.name}: ${e.message}", e, LinkProblem.UNREACHABLE)
        }
        var opened: Session? = null
        // A hang-up during the handshake fails the request under way, which is all it needs.
        val rtsp = AirPlayRtsp(socket, userAgent, onClosed = { cause -> opened?.let { hungUp(it, cause) } })
        val aes = if (plan.crypto == AirPlayCrypto.RSA) AesKey(random) else null
        val stream = try {
            AirPlayStream(
                clock = clock,
                feed = ::fill,
                events = events,
                codec = plan.codec,
                aes = aes?.let { AirPlayAes(it.key, it.iv) },
                latencyFrames = latencyFrames,
                leadNanos = leadNanos,
                bind = bindUdp,
                rtpStart = random.nextInt().toLong(),
                ssrc = random.nextInt(),
                firstSeq = random.nextInt(0x10000),
                sentAudio = if (audit) Audit()::sent else null,
            )
        } catch (e: IOException) {
            rtsp.close()
            throw CastLinkException("No network for ${device.name}: ${e.message}", e, LinkProblem.UNREACHABLE)
        }
        val made = Session(rtsp, stream)
        try {
            handshake(rtsp, stream, plan, aes)
        } catch (e: Exception) {
            made.close()
            throw when (e) {
                is CastLinkException -> e
                is IOException -> CastLinkException("${device.name} did not take the stream: ${e.message}", e, LinkProblem.NOT_A_RECEIVER)
                else -> CastLinkException("${device.name} did not take the stream: ${e.message}", null, LinkProblem.NOT_A_RECEIVER)
            }
        }
        opened = made
        synchronized(state) { endedAt = clock.nanos() }
        return made
    }

    private fun handshake(rtsp: AirPlayRtsp, stream: AirPlayStream, plan: AirPlayPlan.Ok, aes: AesKey?) {
        val options = rtsp.exchange("OPTIONS", "*")
        if (options.status == 401) throw CastLinkException("${device.name} asks for a password", problem = LinkProblem.NOT_A_RECEIVER)
        var authSetupDone = false
        fun authSetup() {
            authSetupDone = true
            val body = ByteArray(33).also { random.nextBytes(it); it[0] = 1 }
            val answer = rtsp.exchange("POST", "/auth-setup", body = body, contentType = "application/octet-stream")
            Log.i(TAG, "${device.name}: auth-setup answered $answer")
        }
        if (plan.authSetup) authSetup()
        val rsaLines = aes?.let { sealed(it) }
        val sdp = AirPlaySdp.announce(rtsp.sessionId, rtsp.local, rtsp.remote, plan.codec, rsaLines?.first, rsaLines?.second)
        val challenge = if (aes != null) listOf("Apple-Challenge" to base64(ByteArray(16).also(random::nextBytes))) else emptyList()
        var announced = rtsp.exchange("ANNOUNCE", headers = challenge, body = sdp.toByteArray(Charsets.UTF_8), contentType = "application/sdp")
        if (announced.status == 403 && !authSetupDone) {
            // Some speakers want the MFi step without listing MFi-SAP; once is worth a try.
            authSetup()
            announced = rtsp.exchange("ANNOUNCE", headers = challenge, body = sdp.toByteArray(Charsets.UTF_8), contentType = "application/sdp")
        }
        when (announced.status) {
            200 -> Unit
            401 -> throw CastLinkException("${device.name} asks for a password", problem = LinkProblem.NOT_A_RECEIVER)
            453 -> throw CastLinkException("${device.name} is busy playing from somewhere else", problem = LinkProblem.HUNG_UP)
            else -> throw RtspRefused("ANNOUNCE", announced.status, announced.reason)
        }
        val transport = "RTP/AVP/UDP;unicast;interleaved=0-1;mode=record;control_port=${stream.controlPort};timing_port=${stream.timingPort}"
        val setup = rtsp.request("SETUP", headers = listOf("Transport" to transport))
        val ports = RtspMessages.transport(setup.header("transport"))
        val serverPort = ports["server_port"]?.toIntOrNull() ?: throw IOException("SETUP gave no server port: ${setup.header("transport")}")
        val controlPort = ports["control_port"]?.toIntOrNull() ?: serverPort + 1
        rtsp.session = setup.header("session")?.substringBefore(';')?.trim() ?: "1"
        latencyOf(setup)?.let { stream.outputLatencyFrames = it }
        stream.connect(rtsp.remote, serverPort, controlPort)
        // RECORD names the first packet; the stretch starts exactly there.
        val first = stream.frameAt(clock.nanos())
        val record = rtsp.request(
            "RECORD",
            headers = listOf("Range" to "npt=0-", "RTP-Info" to "seq=${stream.nextSeq};rtptime=${stream.rtpTime(first)}"),
        )
        latencyOf(record)?.let { stream.outputLatencyFrames = it }
        stream.start(at = first)
        Log.i(
            TAG,
            "${device.name}: ${plan.codec}, ${plan.crypto}${if (authSetupDone) ", auth-setup" else ""}; server port $serverPort, " +
                "control $controlPort, its delay ${stream.outputLatencyFrames} frames",
        )
    }

    /** The speaker's own delay (Audio-Latency, in frames), which some say in answer to SETUP and others to RECORD. */
    private fun latencyOf(answer: RtspResponse): Int? = answer.header("audio-latency")?.toIntOrNull()?.takeIf { it in 0..AirPlay.RATE }

    /** The AES key sealed for the speaker with RSA-OAEP, and the IV, both base64 without padding. */
    private fun sealed(aes: AesKey): Pair<String, String> {
        val key = rsaKey ?: AIRPORT_KEY
        val cipher = Cipher.getInstance("RSA/ECB/OAEPWithSHA-1AndMGF1Padding")
        cipher.init(Cipher.ENCRYPT_MODE, key)
        return base64(cipher.doFinal(aes.key)) to base64(aes.iv)
    }

    private class AesKey(random: SecureRandom) {
        val key = ByteArray(16).also(random::nextBytes)
        val iv = ByteArray(16).also(random::nextBytes)
    }

    /** The session, connected again first if the speaker hung up while idle. */
    private fun live(): Session {
        val now = session ?: throw CastLinkException("Not connected to ${device.name}")
        if (!now.hungUp) return now
        Log.i(TAG, "${device.name} hung up while idle; connecting again")
        now.close()
        val fresh = open()
        session = fresh
        return fresh
    }

    override suspend fun load(item: CastItem, startMs: Long, play: Boolean) {
        connected()
        val totalFrames = when {
            item.length > 0 -> item.length / AirPlayFetch.FRAME_BYTES
            item.durationMs > 0 -> item.durationMs * AirPlay.RATE / 1000
            else -> -1L
        }
        val startFrame = (startMs.coerceAtLeast(0) * AirPlay.RATE / 1000).let { if (totalFrames > 0) it.coerceAtMost(totalFrames) else it }
        val song: Song
        val before: Song?
        synchronized(state) {
            before = current
            song = Song(++loads, item, totalFrames)
            current = song
            _status.update {
                it.copy(
                    state = ReceiverState.LOADING, load = song.number, mediaId = item.mediaId,
                    positionMs = startMs, durationMs = item.durationMs, speed = 1f, reportedAt = nowMs(), error = null,
                )
            }
        }
        withContext(Dispatchers.IO) {
            // A song that takes over from one still playing empties the speaker first, or the last
            // two seconds of the old one would still be heard. One that follows a song that played
            // to its end carries straight on in the same stream, after its last two seconds, and so
            // does one after silence.
            val interrupts = before != null && before.playing && !before.done && !before.failed
            before?.release()
            val session = live()
            if (interrupts && session.stream.isRunning) flush(session)
            synchronized(state) {
                if (current !== song) return@withContext
                song.open(startFrame)
                song.playing = play
            }
            if (play && !session.stream.isRunning) session.stream.start(flushedAt)
        }
        Log.i(TAG, "Load ${song.number}: ${item.mediaId} from $startMs ms (frame $startFrame of $totalFrames), ${if (play) "playing" else "paused"}")
        if (!play) scope.launch(Dispatchers.IO) { reportPausedWhenReady(song, startMs) }
    }

    /** A song loaded paused: PAUSED once its audio comes, FAILED when it cannot. */
    private suspend fun reportPausedWhenReady(song: Song, positionMs: Long) {
        while (scope.isActive) {
            val ready = synchronized(state) {
                if (current !== song || song.playing) return
                when {
                    song.pipe.failure != null -> {
                        failed(song, song.pipe.failure?.message ?: "The song could not be read")
                        return
                    }
                    song.pipe.available >= primeFrames || song.pipe.finished -> true
                    else -> false
                }
            }
            if (ready) {
                synchronized(state) {
                    if (current === song && !song.playing) report(song, ReceiverState.PAUSED, positionMs)
                }
                return
            }
            delay(50)
        }
    }

    override suspend fun play() {
        connected()
        withContext(Dispatchers.IO) {
            val session = live()
            val song = synchronized(state) {
                val song = current ?: return@withContext
                if (song.playing || song.done) return@withContext
                song.playing = true
                report(song, ReceiverState.LOADING, positionOf(song.openedAt))
                song
            }
            if (!session.stream.isRunning) session.stream.start(flushedAt)
            Log.i(TAG, "Play ${song.number} from frame ${song.nextFrame}")
        }
    }

    override suspend fun pause() {
        connected()
        withContext(Dispatchers.IO) {
            val session = live()
            val song = synchronized(state) { current } ?: return@withContext
            val heard = session.stream.position(clock.nanos())
            if (session.stream.isRunning) flush(session)
            synchronized(state) {
                if (current !== song) return@withContext
                // Where the speaker was: the frame it played as it was emptied, or, if this song had
                // not been heard yet, where it was to start.
                val frame = heard?.takeIf { it.first == song.number }?.second ?: song.openedAt
                song.playing = false
                if (!song.done) song.open(frame)
                report(song, ReceiverState.PAUSED, positionOf(frame))
                Log.i(TAG, "Pause ${song.number} at frame $frame")
            }
        }
    }

    override suspend fun seek(positionMs: Long) {
        connected()
        withContext(Dispatchers.IO) {
            val session = live()
            val song = synchronized(state) { current } ?: return@withContext
            val frame = (positionMs.coerceAtLeast(0) * AirPlay.RATE / 1000).let { if (song.totalFrames > 0) it.coerceAtMost(song.totalFrames) else it }
            val playing = synchronized(state) { song.playing }
            if (playing && session.stream.isRunning) flush(session)
            synchronized(state) {
                if (current !== song) return@withContext
                song.open(frame)
                report(song, if (song.playing) ReceiverState.LOADING else ReceiverState.PAUSED, positionOf(frame))
            }
            if (playing) session.stream.start(flushedAt)
            Log.i(TAG, "Seek ${song.number} to frame $frame")
        }
    }

    override suspend fun stop() {
        if (_status.value.link != LinkState.CONNECTED) return
        withContext(Dispatchers.IO) {
            val session = session
            val heard = session?.stream?.position(clock.nanos())
            val song = synchronized(state) {
                current.also { current = null }
            }
            song?.release()
            if (session != null && !session.hungUp && session.stream.isRunning) runCatching { flush(session) }
            val frame = song?.let { s -> heard?.takeIf { it.first == s.number }?.second ?: s.openedAt }
            synchronized(state) {
                _status.update { it.copy(state = ReceiverState.IDLE, positionMs = frame?.let(::positionOf) ?: it.positionMs, reportedAt = nowMs()) }
            }
        }
    }

    override suspend fun setVolume(level: Float) {
        connected()
        val clamped = level.coerceIn(0f, 1f)
        withContext(Dispatchers.IO) {
            val session = live()
            session.rtsp.request("SET_PARAMETER", body = AirPlaySdp.volumeBody(AirPlaySdp.volumeDb(clamped)).toByteArray(), contentType = "text/parameters")
        }
        _status.update { it.copy(volume = clamped) }
    }

    override fun close() {
        val session: Session?
        synchronized(state) {
            if (closed) return
            closed = true
            current?.release()
            current = null
            session = this.session
            this.session = null
            _status.update { it.copy(link = LinkState.CLOSED) }
        }
        watchdog?.cancel()
        if (session != null) {
            session.stream.close()
            // TEARDOWN tells the speaker it is free at once rather than when it gives up waiting.
            Thread({
                if (!session.hungUp) runCatching { session.rtsp.exchange("TEARDOWN") }
                session.rtsp.close()
            }, "AirPlay-teardown").apply { isDaemon = true; start() }
        }
    }

    // Sending

    /** Empties the speaker: the stretch stops, and FLUSH names the packet that would have come next. */
    private fun flush(session: Session) {
        val (seq, frame) = session.stream.stop()
        synchronized(state) { flushedAt = frame }
        val answer = session.rtsp.exchange("FLUSH", headers = listOf("RTP-Info" to "seq=$seq;rtptime=${session.stream.rtpTime(frame)}"))
        // A speaker that will not flush still stops when the packets do, two seconds later; a
        // pause or a seek goes on regardless.
        if (answer.status != 200) Log.i(TAG, "${device.name} answered FLUSH with $answer")
    }

    /** One packet's audio, on the sender's thread: the song's next frames, or silence while it loads, waits or has ended. */
    private fun fill(samples: ShortArray, packet: AirPlayPacket) {
        packet.silence()
        var ended = false
        var failure: String? = null
        val song: Song
        synchronized(state) {
            song = current ?: return
            if (!song.playing || song.done || song.failed) return
            val pipe = song.pipe
            if (!song.primed) {
                if (pipe.available >= primeFrames || pipe.finished) song.primed = true else return
            }
            val given = pipe.take(samples, AirPlay.FRAMES_PER_PACKET)
            if (given == 0) {
                when {
                    pipe.drained -> ended = true
                    pipe.failure != null -> failure = pipe.failure?.message ?: "The song could not be read"
                    // Starved: wait for a little in hand before going on, rather than stuttering.
                    else -> song.primed = false
                }
            } else {
                packet.load = song.number
                packet.songFrame = song.nextFrame
                packet.frames = given
                if (song.nextFrame == song.openedAt) tell(song, packet.at, song.nextFrame)
                song.nextFrame += given
                if (given < AirPlay.FRAMES_PER_PACKET || pipe.drained) ended = true
            }
            if (ended) {
                song.done = true
                endedAt = clock.nanos()
                // Said as soon as the last frame is sent, so that the next song can follow it with
                // no gap; the speaker still has its last two seconds to play. A song with less than
                // that left (a seek near its end) ends only once it has been heard to start.
                if (song.heardFrame >= 0) report(song, ReceiverState.ENDED, positionOf(song.endFrame())) else song.endPending = true
            }
        }
        failure?.let { failed(song, it) }
        if (ended) Log.i(TAG, "Sent the last of ${song.number} (${song.item.mediaId})")
    }

    /**
     * The song's title, artist and album, and where it stands, for a speaker that shows them (its
     * TXT's md): sent as the first packet of a song, or of a stretch of it, goes out, for when that
     * packet is played. Off the sender's thread.
     */
    private fun tell(song: Song, at: Long, songFrame: Long) {
        val wanted = AirPlayDevices.txtOf(device).metadata
        if (wanted.none { it in 0..2 }) return
        val session = session ?: return
        scope.launch(Dispatchers.IO) {
            val stream = session.stream
            val rtpInfo = listOf("RTP-Info" to "rtptime=${stream.rtpTime(at)}")
            runCatching {
                if (0 in wanted) {
                    val item = song.item
                    session.rtsp.request("SET_PARAMETER", headers = rtpInfo, body = AirPlayMetadata.daap(item.title, item.artist, item.album), contentType = "application/x-dmap-tagged")
                }
                if (2 in wanted && song.totalFrames > 0) {
                    val start = at - songFrame
                    val body = AirPlayMetadata.progress(stream.rtpTime(start), stream.rtpTime(at), stream.rtpTime(start + song.totalFrames))
                    session.rtsp.request("SET_PARAMETER", headers = rtpInfo, body = body.toByteArray(), contentType = "text/parameters")
                }
                if (1 in wanted) {
                    val url = song.item.artworkUrl
                    if (!song.coverTried && url != null) {
                        song.coverTried = true
                        song.cover = covers(url)
                    }
                    song.cover?.let { (type, bytes) ->
                        session.rtsp.request("SET_PARAMETER", headers = rtpInfo, body = bytes, contentType = type)
                    }
                }
            }.onFailure { Log.i(TAG, "${device.name} did not take the song's details: ${it.message}") }
        }
    }

    private val events = object : AirPlayStream.Events {
        override fun heard(load: Int, songFrame: Long, atNanos: Long) {
            synchronized(state) {
                val song = current?.takeIf { it.number == load } ?: return
                song.heardFrame = songFrame
                song.heardAt = atNanos
                if (song.endPending) {
                    song.endPending = false
                    report(song, ReceiverState.ENDED, positionOf(song.endFrame()))
                    return
                }
                // A song that has ended still plays out its last two seconds; that is not news.
                if (!song.playing || song.done) return
                _status.update {
                    it.copy(state = ReceiverState.PLAYING, positionMs = positionOf(songFrame), reportedAt = atNanos / 1_000_000L)
                }
            }
            Log.i(TAG, "Heard ${load} from frame $songFrame")
        }

        override fun gap(load: Int, songFrame: Long, atNanos: Long) {
            synchronized(state) {
                val song = current?.takeIf { it.number == load && it.playing && !it.done } ?: return
                val frame = song.heardFrame + (atNanos - song.heardAt) * AirPlay.RATE / 1_000_000_000L
                _status.update { it.copy(state = ReceiverState.LOADING, positionMs = positionOf(frame), reportedAt = atNanos / 1_000_000L) }
            }
            Log.i(TAG, "The speaker ran out of $load and waits")
        }

        override fun failed(cause: IOException) {
            lose("Could not send to ${device.name}: ${cause.message}", LinkProblem.LOST)
        }
    }

    /** The song's last frame: its length, or where the audio ran out when that was not known. */
    private fun Song.endFrame(): Long = totalFrames.takeIf { it > 0 } ?: nextFrame

    /** Holding [state]. */
    private fun report(song: Song, state: ReceiverState, positionMs: Long) {
        if (current !== song) return
        _status.update {
            it.copy(state = state, load = song.number, mediaId = song.item.mediaId, positionMs = positionMs, durationMs = song.item.durationMs, reportedAt = nowMs())
        }
    }

    private fun failed(song: Song, message: String) {
        synchronized(state) {
            if (current !== song || song.failed) return
            song.failed = true
            _status.update { it.copy(state = ReceiverState.FAILED, error = message, reportedAt = nowMs()) }
        }
        Log.w(TAG, "Song ${song.number} failed: $message")
    }

    // Watching

    /** Every second: a stretch that has nothing left to play stops; a speaker that went quiet is lost; the session is kept alive. */
    private suspend fun watch() {
        var lastOptions = clock.nanos()
        var keepAliveFailures = 0
        while (!closed) {
            delay(1_000)
            val session = session ?: continue
            if (session.hungUp) continue
            val now = clock.nanos()
            val stream = session.stream
            val idle = synchronized(state) {
                val song = current
                (song == null || song.done || song.failed || !song.playing) && now - endedAt > (latencyFrames.toLong() * 1_000_000_000L / AirPlay.RATE) + IDLE_STOP_NANOS
            }
            if (idle && stream.isRunning) {
                // Its last song has been heard to the end, and nothing came after: no silence for ever.
                Log.i(TAG, "${device.name}: nothing more to play; stopping the stream")
                runCatching { flush(session) }
            }
            if (stream.isRunning && stream.timingRequests > 0 && now - stream.lastTimingRequestAt > QUIET_LIMIT_NANOS) {
                lose("${device.name} stopped asking about the clock", LinkProblem.STOPPED_ANSWERING)
                return
            }
            if (now - lastOptions > KEEP_ALIVE_NANOS) {
                lastOptions = now
                try {
                    session.rtsp.exchange("OPTIONS", "*")
                    keepAliveFailures = 0
                } catch (e: IOException) {
                    Log.i(TAG, "${device.name}: keep-alive failed: ${e.message}")
                    // A speaker switched off mid-song leaves the connection open and silent: two
                    // keep-alives in a row with no answer is a speaker that is gone.
                    if (++keepAliveFailures >= 2 && !session.hungUp && this.session === session) {
                        lose("${device.name} stopped answering", LinkProblem.STOPPED_ANSWERING)
                        return
                    }
                }
            }
        }
    }

    /** The speaker closed the RTSP connection: lost while it plays, stopped on the device otherwise. */
    private fun hungUp(session: Session, cause: IOException?) {
        if (closed || this.session !== session) return
        session.hungUp = true
        session.stream.stop()
        val playing = synchronized(state) { current?.let { it.playing && !it.done } == true }
        if (playing) {
            lose("${device.name} ended the connection" + (cause?.message?.let { ": $it" } ?: ""), LinkProblem.HUNG_UP)
            return
        }
        Log.i(TAG, "${device.name} hung up while idle")
        synchronized(state) {
            current?.release()
            current = null
            if (_status.value.link == LinkState.CONNECTED) _status.update { it.copy(state = ReceiverState.IDLE, reportedAt = nowMs()) }
        }
    }

    private fun lose(reason: String, problem: LinkProblem) {
        val session: Session?
        synchronized(state) {
            if (closed) return
            val link = _status.value.link
            if (link == LinkState.LOST || link == LinkState.CLOSED) return
            _status.update { it.copy(link = LinkState.LOST, error = reason, problem = problem) }
            current?.release()
            current = null
            session = this.session
        }
        Log.i(TAG, "Lost ${device.name}: $reason")
        watchdog?.cancel()
        session?.stream?.stop()
    }

    private fun connected() {
        if (_status.value.link != LinkState.CONNECTED) throw CastLinkException("Not connected to ${device.name}")
    }

    private fun nowMs() = clock.nanos() / 1_000_000L

    private fun positionOf(frame: Long): Long = frame * 1000 / AirPlay.RATE

    /** Writes a checksum of every 125 packets sent (about a second), counted from the start of each stretch. */
    private inner class Audit {
        private val crc = CRC32()
        private val bytes = ByteArray(AirPlay.FRAMES_PER_PACKET * AirPlay.CHANNELS * 2)
        private var stretchStart = -1L
        private var lastRtp = -1L
        private var packets = 0
        private var block = 0

        fun sent(rtpTime: Long, samples: ShortArray, frames: Int, first: Boolean) {
            if (first || lastRtp < 0 || ((rtpTime - lastRtp) and 0xFFFFFFFFL) != AirPlay.FRAMES_PER_PACKET.toLong()) {
                // A new stretch: the marker packet the receiver will see too.
                stretchStart = rtpTime
                packets = 0
                block = 0
                crc.reset()
            }
            lastRtp = rtpTime
            for (index in 0 until frames * AirPlay.CHANNELS) {
                val sample = samples[index].toInt()
                bytes[index * 2] = sample.toByte()
                bytes[index * 2 + 1] = (sample shr 8).toByte()
            }
            crc.update(bytes, 0, frames * AirPlay.CHANNELS * 2)
            if (++packets == AUDIT_PACKETS) {
                Log.i(AUDIT_TAG, "stretch $stretchStart block $block crc ${java.lang.Long.toHexString(crc.value)}")
                block++
                packets = 0
                crc.reset()
            }
        }
    }

    companion object {
        private const val TAG = "AirPlayLink"
        const val AUDIT_TAG = "AirPlayAudit"
        const val AUDIT_PACKETS = 125

        /** What the remote player is told this receiver plays: raw audio at AirPlay's rate, in stereo, and nothing else. */
        val FORMATS = FormatSupport.Listed(
            emptySet(),
            listOf(PcmOffer("audio/L16;rate=${AirPlay.RATE};channels=${AirPlay.CHANNELS}", AirPlay.RATE, AirPlay.CHANNELS)),
        )

        /** Decoded audio held ahead of the sender: six seconds. */
        const val PIPE_FRAMES = 6 * AirPlay.RATE

        /** Audio in hand before a song starts or goes on after running dry: a quarter of a second. */
        const val PRIME_FRAMES = AirPlay.RATE / 4

        private const val CONNECT_TIMEOUT_MS = 5_000
        private const val IDLE_STOP_NANOS = 3_000_000_000L
        private const val QUIET_LIMIT_NANOS = 20_000_000_000L
        private const val KEEP_ALIVE_NANOS = 30_000_000_000L

        /**
         * The AirPort Express's public key (2048-bit RSA, exponent 65537), which every AirPlay
         * sender has used since iTunes to seal the audio key for speakers that take only RSA.
         */
        private val AIRPORT_KEY: PublicKey by lazy {
            val modulus = BigInteger(
                1,
                (
                    "e7d744f2a2e2788b6c1f55a08eb70544a8fa7945aa8be6c62ce5f51cbdd4dc6842fe3d1083dd2edec1bfd4252dc02e6f" +
                        "398bdf0e6148ea84855e2e442da6d62664f674a1f304929ade4f6893ef2df6e711a8c77a0d91c9d980822e50d12922af" +
                        "ea40ea9f0e14c0f76938c5f3882fc0323dd9fe55155f51bb5921c201629fd73352d5e2efaabf9ba048d7b813a2b6767f" +
                        "6c3ccf1eb4ce673d037b0d2ea30c5fffeb06f8d08adde409571a9c689fef10728855dd8cfb9a8bef5c8943ef3b5faa15" +
                        "dde698beddf3599603eb3e6f61372bb628f6559f599a78bf500687aa7f4976c0562d412956f8989e18a6355bd8159782" +
                        "5e0fc875343ec782117625cdbf98447b"
                    ).chunked(2).map { it.toInt(16).toByte() }.toByteArray(),
            )
            KeyFactory.getInstance("RSA").generatePublic(RSAPublicKeySpec(modulus, BigInteger.valueOf(65537)))
        }

        private const val BASE64 = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"

        /** Base64 without the padding, as AirPlay's SDP and headers carry it (android.util.Base64 is not there in unit tests). */
        fun base64(data: ByteArray): String = buildString {
            var index = 0
            while (index + 2 < data.size) {
                val value = (data[index].toInt() and 0xFF shl 16) or (data[index + 1].toInt() and 0xFF shl 8) or (data[index + 2].toInt() and 0xFF)
                append(BASE64[value shr 18 and 63]).append(BASE64[value shr 12 and 63]).append(BASE64[value shr 6 and 63]).append(BASE64[value and 63])
                index += 3
            }
            val left = data.size - index
            if (left > 0) {
                val value = (data[index].toInt() and 0xFF shl 16) or (if (left > 1) data[index + 1].toInt() and 0xFF shl 8 else 0)
                append(BASE64[value shr 18 and 63]).append(BASE64[value shr 12 and 63])
                if (left > 1) append(BASE64[value shr 6 and 63])
            }
        }
    }
}
