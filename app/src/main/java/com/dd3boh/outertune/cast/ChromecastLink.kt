/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.cast

import android.os.Looper
import android.util.Log
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import javax.net.SocketFactory
import kotlin.concurrent.thread
import kotlin.random.Random

/**
 * A link to a Chromecast, a Google TV device, a speaker with Chromecast built in, or a group of
 * them: Cast v2 over one TLS connection ([CastChannel], [Chromecast]), with no Google code.
 *
 * Connecting opens TLS to the device ([ChromecastTls]) and a virtual connection to the device
 * itself, and reads its status. It then joins Google's Default Media Receiver if it is already
 * running, or launches it, and opens a virtual connection to it; the TV shows the cast screen from
 * then on. A song goes to that player as LOAD, and each LOAD makes a media session, which PLAY,
 * PAUSE, SEEK and STOP then name.
 *
 * The device pings every five seconds and hangs up on a sender that does not answer, so every PING
 * is answered at once from the reading thread; the link pings every [Timing.pingEveryMs] in turn
 * and gives the device up as lost when it goes quiet for [Timing.deadAfterMs]. A device reports a
 * song only when something about it changes, so while one plays or loads the link also asks after
 * it every [Timing.statusEveryMs], which keeps its position true and a stall seen.
 *
 * What the device says is taken with care:
 * - a status names its media session, and a status about a song this link sent before the current
 *   one is ignored;
 * - the answer to a LOAD carries the LOAD's requestId, and the first status about a song carries
 *   its url, which is how the song's media session becomes known; a play, pause or seek asked for
 *   before that waits for it;
 * - a song that ends is IDLE with the reason FINISHED; one that cannot play is IDLE with ERROR, or
 *   LOAD_FAILED, and the failure stays until the next load, as FCastLink and DlnaLink keep theirs;
 * - another sender loading on the same player interrupts our session or starts a newer one, and
 *   another app on the device closes our player. Either is reported the way FCastLink reports
 *   another sender: the song goes IDLE with a word saying so. The player is then not ours to stop.
 *
 * Closing stops our song and, when this link launched the player and nobody took it over, the
 * player too, so the TV goes back to what it showed before; a player that was already running, or
 * that somebody else is now using, is left alone.
 */
class ChromecastLink(
    override val device: CastDevice,
    private val scope: CoroutineScope,
    private val socketFactory: SocketFactory = SocketFactory.getDefault(),
    /** Puts TLS over the connected socket and shakes hands. Tests pass one that leaves it plain. */
    private val secure: (Socket, String, Int) -> Socket = ChromecastTls::wrap,
    private val now: () -> Long = { System.nanoTime() / 1_000_000L },
    private val timing: Timing = Timing(),
    /** How the link introduces itself when it connects. */
    private val userAgent: String = "InterTune",
) : ReceiverLink {
    data class Timing(
        val connectTimeoutMs: Int = 5_000,
        /** For the TLS handshake, and for each answer the device owes while connecting. */
        val answerTimeoutMs: Long = 5_000,
        /** For the device to open its media player, which takes older ones several seconds. */
        val launchTimeoutMs: Long = 20_000,
        val pingEveryMs: Long = 5_000,
        val deadAfterMs: Long = 15_000,
        val statusEveryMs: Long = 5_000,
        val tickMs: Long = 500,
    )

    @Volatile
    override var caps: LinkCaps = LinkCaps()
        private set

    private val _status = MutableStateFlow(ReceiverStatus())
    override val status: StateFlow<ReceiverStatus> = _status.asStateFlow()

    /** Our name on the virtual connection to the media player. */
    private val clientId = "client-${Random.nextInt(10_000, 100_000)}"
    private val requestIds = AtomicInteger(0)

    private val stateLock = Any()
    private val writeLock = Any()

    @Volatile
    private var socket: Socket? = null
    private var output: OutputStream? = null
    private var reader: Job? = null
    private var keepAlive: Job? = null

    @Volatile
    private var closed = false

    /** Why the link was lost, once it was, for whoever still waits on an answer. */
    @Volatile
    private var lostWith: CastLinkException? = null

    @Volatile
    private var lastHeardAt = 0L

    /** Opened when the reading thread ends: the device hung up, or the socket was closed. */
    private val readerDone = CountDownLatch(1)

    /** The media player songs go to: its session on the device, and the address of its connection. */
    private class Player(val sessionId: String, val transportId: String, val launchedHere: Boolean) {
        /** False once the device closed our virtual connection to it; it may still be running. */
        var connected = true
    }

    /** The song sent last, and what is known of its media session on the device. */
    private class Loaded(val number: Int, val url: String, val autoplay: Boolean) {
        /** The LOAD's requestId, 0 until it has gone. */
        var requestId = 0
        var mediaSessionId: Long? = null

        /** The media session to name in a command, once known. */
        val session: Long? get() = mediaSessionId?.takeIf { it >= 0 }

        /** Whether the listener wants it playing, as last asked, sent or not. */
        var wantPlay = autoplay
        var pendingSeekMs = -1L

        /** Stopped before its media session was known; it is stopped on the device once it is. */
        var stopWhenKnown = false

        /** The device refused or cancelled the LOAD, so no media session of it is coming. */
        var refused = false

        /** Sent, and not answered yet: the device has neither named its media session nor refused it. */
        val awaited: Boolean get() = requestId != 0 && mediaSessionId == null && !refused
    }

    /** One thing to say, worked out holding [stateLock] and said after letting go of it. */
    private class Outgoing(val source: String, val destination: String, val namespace: String, val payload: String)

    // All of these are guarded by stateLock.
    private var player: Player? = null
    private var loads = 0
    private var current: Loaded? = null

    /** The last few songs sent, so a late status about one of them is known for what it is. */
    private val recent = ArrayDeque<Loaded>()

    /** The newest media session of ours on the current player, 0 before the first. */
    private var newestSession = 0L

    /** Another sender or app took the device over since our last song, so the player is not ours to stop. */
    private var takenOver = false
    private var muted = false

    /** The requestId of the last GET_STATUS sent to the player. */
    private var lastPlayerAsk = 0

    /** Answers somebody is waiting for while connecting or launching. */
    private class Expectation(val matches: (Chromecast.Payload, CastChannel.Message) -> Boolean) {
        val answer = CompletableDeferred<Chromecast.Payload>()
    }

    private val expectations = CopyOnWriteArrayList<Expectation>()

    override suspend fun connect() {
        synchronized(stateLock) {
            check(_status.value.link == LinkState.NEW) { "A link connects once" }
            _status.update { it.copy(link = LinkState.CONNECTING) }
        }
        withContext(Dispatchers.IO) {
            try {
                open()
                val first = askDevice()
                    ?: throw CastLinkException("${device.name} did not answer as a Chromecast", problem = LinkProblem.NOT_A_RECEIVER)
                // Chromecast says nothing of what it decodes. The Default Media Receiver plays Opus
                // in WebM, AAC, MP3 and FLAC on every Cast device, but TVs with Chromecast built in
                // decode on their own chips, so it is treated as unknown: a song it fails is tried
                // once more as AAC.
                caps = LinkCaps(formats = FormatSupport.Unknown, canSetSpeed = false, hasVolume = !first.fixedVolume)
                ensurePlayer(first)
            } catch (e: IOException) {
                val failure = e as? CastLinkException ?: CastLinkException("Lost ${device.name}", e)
                lose(failure.message ?: "Lost ${device.name}", failure.problem)
                throw if (closed) CastLinkException("Closed", e) else failure
            }
            synchronized(stateLock) {
                if (closed) throw CastLinkException("Closed")
                _status.update { it.copy(link = LinkState.CONNECTED) }
            }
            Log.i(TAG, "Connected to ${device.name} (${device.host}:${device.port})")
        }
    }

    override suspend fun load(item: CastItem, startMs: Long, play: Boolean) {
        requireConnected()
        val start = startMs.coerceAtLeast(0)
        val loaded = synchronized(stateLock) {
            val number = ++loads
            // A mark per load makes each url new, so the song's first status can be told from one
            // about the same song sent before it; the cast server ignores it.
            val url = item.url + (if ('?' in item.url) "&" else "?") + "load=" + number
            Loaded(number, url, autoplay = play).also {
                current = it
                recent.addLast(it)
                while (recent.size > RECENT_LOADS) recent.removeFirst()
                _status.update { status ->
                    status.copy(
                        state = ReceiverState.LOADING,
                        load = number,
                        mediaId = item.mediaId,
                        positionMs = start,
                        durationMs = item.durationMs,
                        speed = 1f,
                        reportedAt = now(),
                        error = null,
                    )
                }
            }
        }
        withContext(Dispatchers.IO) {
            // The player is gone when another app took the device over or it was stopped on the
            // device; a song asked for after that opens it again, as casting from any sender does.
            val player = try {
                ensurePlayer()
            } catch (e: IOException) {
                // A player that would not open says nothing about the song. Reported as failed, the
                // song would pass for one the device refuses as it is: tried again as AAC, and its
                // type sent as AAC for the rest of the cast once that played. Reported as idle, it
                // would read as a stop. So the status is left as it is, and the throw fails the
                // load, once, where it was asked for.
                synchronized(stateLock) { if (current === loaded) current = null }
                throw e as? CastLinkException ?: CastLinkException("Lost ${device.name}", e)
            }
            val requestId = requestIds.incrementAndGet()
            synchronized(stateLock) {
                // Stopped, or the link closed, while the player opened.
                if (current !== loaded || closed) return@withContext
                loaded.requestId = requestId
                takenOver = false
            }
            send(Outgoing(clientId, player.transportId, Chromecast.NS_MEDIA, Chromecast.load(requestId, player.sessionId, item, loaded.url, start, play)))
        }
    }

    override suspend fun play() = playOrPause(true)

    override suspend fun pause() = playOrPause(false)

    private suspend fun playOrPause(play: Boolean) {
        requireConnected()
        val outgoing = synchronized(stateLock) {
            val loaded = current ?: return
            loaded.wantPlay = play
            val finished = _status.value.state.let { it == ReceiverState.ENDED || it == ReceiverState.FAILED }
            val id = loaded.session ?: return
            val player = player ?: return
            if (finished) return
            toPlayer(player, Chromecast.mediaCommand(if (play) Chromecast.PLAY else Chromecast.PAUSE, requestIds.incrementAndGet(), id))
        }
        sendNow(outgoing)
    }

    override suspend fun seek(positionMs: Long) {
        requireConnected()
        val position = positionMs.coerceAtLeast(0)
        val outgoing = synchronized(stateLock) {
            val loaded = current ?: return
            _status.update { it.copy(positionMs = position, reportedAt = now()) }
            val id = loaded.session
            if (id == null) {
                loaded.pendingSeekMs = position
                return
            }
            val player = player ?: return
            toPlayer(player, Chromecast.seek(requestIds.incrementAndGet(), id, position))
        }
        sendNow(outgoing)
    }

    override suspend fun stop() {
        val outgoing = synchronized(stateLock) {
            val loaded = current
            current = null
            _status.update { it.copy(state = ReceiverState.IDLE, positionMs = 0, reportedAt = now()) }
            if (loaded == null) return@synchronized null
            val id = loaded.session
            if (id == null) {
                loaded.stopWhenKnown = true
                return@synchronized null
            }
            player?.let { toPlayer(it, Chromecast.mediaCommand(Chromecast.STOP, requestIds.incrementAndGet(), id)) }
        }
        requireConnected()
        outgoing?.let { sendNow(it) }
    }

    override suspend fun setVolume(level: Float) {
        requireConnected()
        val clamped = level.coerceIn(0f, 1f)
        val unmute = synchronized(stateLock) { muted && clamped > 0f }
        _status.update { it.copy(volume = clamped) }
        sendNow(toDevice(Chromecast.setVolume(requestIds.incrementAndGet(), level = clamped)))
        // A volume set on a muted device stays silent; the listener turning it up means to hear it.
        if (unmute) sendNow(toDevice(Chromecast.setVolume(requestIds.incrementAndGet(), muted = false)))
    }

    override fun close() {
        val goodbye = mutableListOf<Outgoing>()
        val connection: Socket?
        synchronized(stateLock) {
            if (closed) return
            closed = true
            val loaded = current
            val state = _status.value.state
            current = null
            val player = player
            if (lostWith == null && output != null) {
                if (player != null) {
                    val id = loaded?.session
                    val playing = state == ReceiverState.LOADING || state == ReceiverState.PLAYING || state == ReceiverState.PAUSED
                    if (id != null && playing && !takenOver) {
                        goodbye += toPlayer(player, Chromecast.mediaCommand(Chromecast.STOP, requestIds.incrementAndGet(), id))
                    }
                    if (player.launchedHere && !takenOver) {
                        goodbye += toDevice(Chromecast.stopApp(requestIds.incrementAndGet(), player.sessionId))
                    }
                    goodbye += Outgoing(clientId, player.transportId, Chromecast.NS_CONNECTION, Chromecast.close())
                }
                goodbye += Outgoing(Chromecast.PLATFORM_SENDER, Chromecast.PLATFORM_RECEIVER, Chromecast.NS_CONNECTION, Chromecast.close())
            }
            connection = socket
            _status.update { it.copy(link = LinkState.CLOSED) }
        }
        keepAlive?.cancel()
        failWaiting(CastLinkException("Closed"))
        if (goodbye.isEmpty() || connection == null) {
            runCatching { connection?.close() }
            reader?.cancel()
            return
        }
        // Off the main thread the goodbye is said before close returns, so a cast straight back
        // to the same device, which waits for this one to close, cannot join the player just
        // before our STOP for it lands. The main thread must not touch the network, so from
        // there it is said on the thread that closes the socket.
        val onMain = runCatching { Looper.getMainLooper()?.isCurrentThread == true }.getOrDefault(false)
        if (!onMain) sayGoodbye(goodbye)
        thread(isDaemon = true, name = "ChromecastLink close") {
            if (onMain) sayGoodbye(goodbye)
            // A socket closed with the device's answer still unread goes out as a reset rather
            // than a close, and a reset can make the device throw away what it has not read yet:
            // the stops just sent. So the reading thread goes on reading until the device hangs
            // up, or for a second at most.
            readerDone.await(GOODBYE_WAIT_MS, TimeUnit.MILLISECONDS)
            runCatching { connection.close() }
            reader?.cancel()
        }
    }

    private fun sayGoodbye(goodbye: List<Outgoing>) {
        try {
            goodbye.forEach(::write)
        } catch (e: IOException) {
            Log.d(TAG, "Could not say goodbye to ${device.name}: ${e.message}")
        }
    }

    // Connecting

    private fun open() {
        val address = try {
            InetAddress.getByName(device.host)
        } catch (e: IOException) {
            throw CastLinkException("Could not reach ${device.name}", e, LinkProblem.UNREACHABLE)
        }
        if (!ChromecastTls.isLocal(address)) {
            throw CastLinkException("${device.name} is not on the local network", problem = LinkProblem.NOT_A_RECEIVER)
        }
        val plain = socketFactory.createSocket()
        try {
            plain.connect(InetSocketAddress(address, device.port), timing.connectTimeoutMs)
            plain.tcpNoDelay = true
            plain.keepAlive = true
        } catch (e: IOException) {
            runCatching { plain.close() }
            throw CastLinkException("Could not reach ${device.name}", e, LinkProblem.UNREACHABLE)
        }
        socket = plain
        val connection = try {
            plain.soTimeout = timing.answerTimeoutMs.toInt()
            secure(plain, device.host, device.port).also { it.soTimeout = 0 }
        } catch (e: IOException) {
            runCatching { plain.close() }
            throw CastLinkException("${device.name} did not answer as a Chromecast", e, LinkProblem.NOT_A_RECEIVER)
        }
        synchronized(stateLock) {
            socket = connection
            if (closed) {
                runCatching { connection.close() }
                throw CastLinkException("Closed")
            }
        }
        output = connection.getOutputStream()
        lastHeardAt = now()
        val input = connection.getInputStream()
        reader = scope.launch(Dispatchers.IO) { readLoop(input) }
        keepAlive = scope.launch(Dispatchers.IO) { keepAliveLoop() }
        send(Outgoing(Chromecast.PLATFORM_SENDER, Chromecast.PLATFORM_RECEIVER, Chromecast.NS_CONNECTION, Chromecast.connect(userAgent)))
    }

    /** The device's status, asked for, or null when it does not answer in time. */
    private suspend fun askDevice(): Chromecast.Device? {
        val requestId = requestIds.incrementAndGet()
        val answer = expect(timing.answerTimeoutMs, { payload, message ->
            message.namespace == Chromecast.NS_RECEIVER && payload.requestId == requestId && payload.type == Chromecast.RECEIVER_STATUS
        }) {
            send(toDevice(Chromecast.getStatus(requestId)))
        } ?: return null
        return Chromecast.parseDevice(answer.body)
    }

    /**
     * The media player, connected to: the one this link uses already, the Default Media Receiver
     * the device is already running (joined), or one launched now. [known] is a status just read.
     */
    private suspend fun ensurePlayer(known: Chromecast.Device? = null): Player {
        synchronized(stateLock) { player?.takeIf { it.connected }?.let { return it } }
        val status = known ?: askDevice()
            ?: throw CastLinkException("${device.name} stopped answering", problem = LinkProblem.STOPPED_ANSWERING)
        val running = runningPlayer(status)
        val app = running ?: launchPlayer()
        val player = synchronized(stateLock) {
            val before = player
            val same = before != null && before.sessionId == app.sessionId
            val launchedHere = running == null || (same && before?.launchedHere == true)
            if (!same) {
                // Media sessions are numbered afresh in a new player, so the numbers of the old
                // one's songs would be mistaken for the new one's.
                recent.retainAll { it === current }
                newestSession = 0
            }
            Player(app.sessionId!!, app.transportId!!, launchedHere).also { player = it }
        }
        Log.i(TAG, "${if (running != null) "Joined" else "Launched"} the media player on ${device.name}")
        send(Outgoing(clientId, player.transportId, Chromecast.NS_CONNECTION, Chromecast.connect(userAgent)))
        return player
    }

    private suspend fun launchPlayer(): Chromecast.App {
        val requestId = requestIds.incrementAndGet()
        val answer = expect(timing.launchTimeoutMs, { payload, message ->
            message.namespace == Chromecast.NS_RECEIVER && when {
                payload.requestId == requestId && payload.type != Chromecast.RECEIVER_STATUS -> true
                payload.type == Chromecast.RECEIVER_STATUS -> runningPlayer(Chromecast.parseDevice(payload.body)) != null
                else -> false
            }
        }) {
            send(toDevice(Chromecast.launch(requestId)))
        } ?: throw CastLinkException("${device.name} did not open its media player", problem = LinkProblem.STOPPED_ANSWERING)
        if (answer.type != Chromecast.RECEIVER_STATUS) {
            val reason = Chromecast.reasonOf(answer.body) ?: answer.type
            throw CastLinkException("${device.name} would not open its media player ($reason)", problem = LinkProblem.NOT_A_RECEIVER)
        }
        return runningPlayer(Chromecast.parseDevice(answer.body))!!
    }

    private fun runningPlayer(status: Chromecast.Device?): Chromecast.App? = status?.apps?.firstOrNull {
        it.appId == Chromecast.DEFAULT_MEDIA_RECEIVER && it.sessionId != null && it.transportId != null
    }

    /**
     * Sends through [send] and waits up to [timeoutMs] for a message that [matches], or null when
     * none comes. Throws when the link is lost or closed meanwhile.
     */
    private suspend fun expect(
        timeoutMs: Long,
        matches: (Chromecast.Payload, CastChannel.Message) -> Boolean,
        send: () -> Unit,
    ): Chromecast.Payload? {
        val expectation = Expectation(matches)
        expectations += expectation
        try {
            lostWith?.let { throw it }
            if (closed) throw CastLinkException("Closed")
            send()
            return withTimeoutOrNull(timeoutMs) { expectation.answer.await() }
        } finally {
            expectations -= expectation
        }
    }

    private fun failWaiting(reason: CastLinkException) {
        expectations.forEach { it.answer.completeExceptionally(reason) }
    }

    // Sending

    private fun requireConnected() {
        if (_status.value.link != LinkState.CONNECTED) throw CastLinkException("Not connected to ${device.name}")
    }

    private fun toDevice(payload: String) =
        Outgoing(Chromecast.PLATFORM_SENDER, Chromecast.PLATFORM_RECEIVER, Chromecast.NS_RECEIVER, payload)

    private fun toPlayer(player: Player, payload: String) = Outgoing(clientId, player.transportId, Chromecast.NS_MEDIA, payload)

    /** GET_STATUS to the player, noted so a refusal of it is not answered by asking again. Holding [stateLock]. */
    private fun askPlayer(player: Player): Outgoing {
        val requestId = requestIds.incrementAndGet()
        lastPlayerAsk = requestId
        return toPlayer(player, Chromecast.getStatus(requestId))
    }

    private suspend fun sendNow(outgoing: Outgoing) = withContext(Dispatchers.IO) {
        requireConnected()
        send(outgoing)
    }

    /** Writes one message, from whichever thread; the reading thread answers pings through here. */
    private fun send(outgoing: Outgoing) {
        try {
            write(outgoing)
        } catch (e: IOException) {
            lose("Lost ${device.name}", LinkProblem.LOST)
            throw CastLinkException("Lost ${device.name}", e)
        }
    }

    private fun write(outgoing: Outgoing) {
        val frame = CastChannel.frame(CastChannel.Message(outgoing.source, outgoing.destination, outgoing.namespace, outgoing.payload))
        synchronized(writeLock) {
            val out = output ?: throw IOException("Not connected")
            out.write(frame)
            out.flush()
        }
    }

    /** Sends each of [outgoing], leaving a failure to [send], which gives the link up. */
    private fun sendQuietly(outgoing: List<Outgoing>) {
        for (message in outgoing) {
            if (runCatching { send(message) }.isFailure) return
        }
    }

    // Receiving

    private fun readLoop(input: InputStream) {
        try {
            while (true) {
                val message = CastChannel.read(input) ?: break
                lastHeardAt = now()
                handle(message)
            }
            lose("${device.name} hung up", LinkProblem.HUNG_UP)
        } catch (e: IOException) {
            if (!closed) Log.i(TAG, "Reading from ${device.name} failed: ${e.message}")
            lose("Lost ${device.name}", LinkProblem.LOST)
        } finally {
            readerDone.countDown()
        }
    }

    private fun handle(message: CastChannel.Message) {
        // Binary payloads are device authentication only, which this sender does not ask for.
        if (message.payloadType != CastChannel.PAYLOAD_STRING) return
        val payload = Chromecast.parse(message.payloadUtf8) ?: return
        val outgoing = when (message.namespace) {
            Chromecast.NS_HEARTBEAT -> if (payload.type == Chromecast.PING) listOf(pongTo(message)) else emptyList()
            Chromecast.NS_CONNECTION -> if (payload.type == Chromecast.CLOSE) onClose(message.sourceId) else emptyList()
            Chromecast.NS_RECEIVER -> if (payload.type == Chromecast.RECEIVER_STATUS) {
                Chromecast.parseDevice(payload.body)?.let(::onDevice).orEmpty()
            } else emptyList()
            Chromecast.NS_MEDIA -> onMedia(payload)
            else -> emptyList()
        }
        sendQuietly(outgoing)
        expectations.forEach { if (!it.answer.isCompleted && it.matches(payload, message)) it.answer.complete(payload) }
    }

    private fun pongTo(ping: CastChannel.Message) = Outgoing(
        source = ping.destinationId.takeIf { it.isNotEmpty() && it != "*" } ?: Chromecast.PLATFORM_SENDER,
        destination = ping.sourceId.ifEmpty { Chromecast.PLATFORM_RECEIVER },
        namespace = Chromecast.NS_HEARTBEAT,
        payload = Chromecast.pong(),
    )

    /**
     * The device closed a virtual connection. Its own means it is done with us. The player's means
     * the player may have ended, or only dropped us: the device's status says which.
     */
    private fun onClose(source: String): List<Outgoing> {
        if (source == Chromecast.PLATFORM_RECEIVER) {
            lose("${device.name} hung up", LinkProblem.HUNG_UP)
            return emptyList()
        }
        synchronized(stateLock) {
            val player = player?.takeIf { it.transportId == source } ?: return emptyList()
            player.connected = false
        }
        return listOf(toDevice(Chromecast.getStatus(requestIds.incrementAndGet())))
    }

    private fun onDevice(status: Chromecast.Device): List<Outgoing> = synchronized(stateLock) {
        status.level?.let { level ->
            muted = status.muted == true
            _status.update { it.copy(volume = if (muted) 0f else level) }
        }
        val player = player ?: return emptyList()
        if (status.apps.any { it.sessionId == player.sessionId }) {
            if (player.connected) return emptyList()
            // Still running, but it had dropped our connection to it: connect again.
            player.connected = true
            return listOf(Outgoing(clientId, player.transportId, Chromecast.NS_CONNECTION, Chromecast.connect(userAgent)))
        }
        this.player = null
        val other = status.apps.any { !it.isIdleScreen && it.appId != null }
        songGone(if (other) takenOverReason() else "Stopped on ${device.name}")
        emptyList()
    }

    private fun onMedia(payload: Chromecast.Payload): List<Outgoing> = synchronized(stateLock) {
        val outgoing = mutableListOf<Outgoing>()
        var askAgain = false
        when (payload.type) {
            Chromecast.MEDIA_STATUS -> {
                val sessions = Chromecast.parseMedia(payload.body)
                if (sessions.isEmpty()) noMedia()
                sessions.forEach { askAgain = onMediaStatus(it, payload.requestId, outgoing) || askAgain }
            }
            Chromecast.LOAD_FAILED, Chromecast.LOAD_CANCELLED, Chromecast.INVALID_REQUEST, Chromecast.ERROR,
            Chromecast.INVALID_PLAYER_STATE -> askAgain = onMediaError(payload)
        }
        if (askAgain) player?.let { outgoing += askPlayer(it) }
        outgoing
    }

    /**
     * One media session's status, holding [stateLock]. What it calls for goes in [outgoing]; true
     * when the song's status should be asked for again, to know what this one was about.
     */
    private fun onMediaStatus(media: Chromecast.Media, requestId: Int, outgoing: MutableList<Outgoing>): Boolean {
        // The answer to a LOAD says for certain which song it is about, so it comes first.
        val loaded = recent.lastOrNull { requestId != 0 && it.requestId == requestId }
            ?: recent.firstOrNull { it.mediaSessionId == media.mediaSessionId }
            ?: recent.lastOrNull { it.mediaSessionId == null && media.contentId != null && media.contentId == it.url }
        if (loaded == null) return onOtherMedia(media, requestId)
        val learned = loaded.mediaSessionId == null
        if (learned) {
            loaded.mediaSessionId = media.mediaSessionId
            newestSession = maxOf(newestSession, media.mediaSessionId)
            // A session number is one song's. Another of ours that seemed to have it did not.
            recent.forEach { if (it !== loaded && it.mediaSessionId == media.mediaSessionId) it.mediaSessionId = NO_SESSION }
        }
        val player = player
        if (loaded !== current) {
            // A song stopped before the device said which session it made plays until told to stop.
            if (learned && loaded.stopWhenKnown && media.playerState != Chromecast.PLAYER_IDLE && player != null) {
                outgoing += toPlayer(player, Chromecast.mediaCommand(Chromecast.STOP, requestIds.incrementAndGet(), media.mediaSessionId))
            }
            return false
        }
        report(loaded, media)
        if (learned && player != null && current === loaded) {
            // What was asked for while the session was not known yet.
            if (loaded.wantPlay != loaded.autoplay) {
                val type = if (loaded.wantPlay) Chromecast.PLAY else Chromecast.PAUSE
                outgoing += toPlayer(player, Chromecast.mediaCommand(type, requestIds.incrementAndGet(), media.mediaSessionId))
            }
            if (loaded.pendingSeekMs >= 0) {
                outgoing += toPlayer(player, Chromecast.seek(requestIds.incrementAndGet(), media.mediaSessionId, loaded.pendingSeekMs))
                loaded.pendingSeekMs = -1
            }
        }
        return false
    }

    /** The song whose LOAD went out last, holding [stateLock]. */
    private fun lastSent(): Loaded? = recent.lastOrNull { it.requestId != 0 }

    /** That song, while the device has not answered its LOAD. Holding [stateLock]. */
    private fun awaitedLoad(): Loaded? = lastSent()?.takeIf { it.awaited }

    /**
     * A media session that is none of our songs', holding [stateLock]. True when it may be ours
     * after all, reported without the url that would say so, and it did not answer our own asking.
     */
    private fun onOtherMedia(media: Chromecast.Media, requestId: Int): Boolean {
        // While our last LOAD is unanswered, another session may be the one it made, reported
        // without its url, or the one it replaced, reported late. That holds for a song stopped
        // before the answer too, which is still to be stopped once its session is known. A
        // status that answers nothing (requestId 0) is not the answer to our asking, even
        // before the link has asked anything.
        if (awaitedLoad() != null) return media.contentId == null && (requestId == 0 || requestId != lastPlayerAsk)
        // Sessions are numbered in order, so one newer than all of ours is somebody else's load.
        // So is any session at all on a player where no song of ours has had one: none sent yet,
        // or the only one refused.
        if (media.mediaSessionId > newestSession) {
            takenOver = true
            songGone(takenOverReason())
        }
        return false
    }

    /** What the device said about the current song, into the status. Holding [stateLock]. */
    private fun report(loaded: Loaded, media: Chromecast.Media) {
        val status = _status.value
        if (status.load != loaded.number) return
        if (status.state == ReceiverState.ENDED || status.state == ReceiverState.FAILED) return
        val state = when (media.playerState) {
            Chromecast.PLAYER_PLAYING -> ReceiverState.PLAYING
            Chromecast.PLAYER_PAUSED -> ReceiverState.PAUSED
            Chromecast.PLAYER_BUFFERING, Chromecast.PLAYER_LOADING -> ReceiverState.LOADING
            Chromecast.PLAYER_IDLE -> when (media.idleReason) {
                Chromecast.IDLE_FINISHED -> ReceiverState.ENDED
                Chromecast.IDLE_ERROR -> ReceiverState.FAILED
                Chromecast.IDLE_CANCELLED -> return songGone("Stopped on ${device.name}")
                Chromecast.IDLE_INTERRUPTED -> {
                    takenOver = true
                    return songGone(takenOverReason())
                }
                // Idle with no reason is the player before it has opened the song.
                else -> status.state
            }
            else -> status.state
        }
        val duration = media.durationMs ?: status.durationMs
        val position = media.positionMs ?: status.positionMs
        if (state == ReceiverState.FAILED) Log.i(TAG, "${device.name} could not play song ${loaded.number}")
        _status.update {
            it.copy(
                state = state,
                positionMs = if (state == ReceiverState.ENDED && duration > 0) duration else position,
                durationMs = duration,
                speed = media.speed ?: it.speed,
                reportedAt = now(),
                error = if (state == ReceiverState.FAILED) "${device.name} could not play it" else it.error,
            )
        }
    }

    /**
     * A MEDIA_STATUS with no session in it: the player holds nothing. For a song that was playing
     * and whose end was not heard, it ended if it had got to its end, and was stopped otherwise.
     */
    private fun noMedia() {
        val loaded = current ?: return
        if (loaded.session == null) return
        val status = _status.value
        val playing = status.state == ReceiverState.PLAYING || status.state == ReceiverState.PAUSED || status.state == ReceiverState.LOADING
        if (!playing) return
        val reached = status.positionAt(now())
        if (status.durationMs > 0 && reached >= status.durationMs - END_SLACK_MS) {
            _status.update { it.copy(state = ReceiverState.ENDED, positionMs = it.durationMs, reportedAt = now()) }
        } else {
            songGone("Stopped on ${device.name}")
        }
    }

    /** A refusal, holding [stateLock]; true when the song's status should be asked for again. */
    private fun onMediaError(payload: Chromecast.Payload): Boolean {
        val reason = Chromecast.reasonOf(payload.body)
        Log.i(TAG, "${device.name} said ${payload.type}${reason?.let { " ($it)" }.orEmpty()} to request ${payload.requestId}")
        // The song whose LOAD this answers, if it does.
        val song = recent.lastOrNull { payload.requestId != 0 && it.requestId == payload.requestId }
        if (song == null) {
            // About a play, pause or seek: the session it named may be gone, and the status says.
            // Not about asking for the status itself, which would only be refused again.
            if (payload.requestId != 0 && payload.requestId == lastPlayerAsk) return false
            return payload.type != Chromecast.LOAD_FAILED && payload.type != Chromecast.LOAD_CANCELLED
        }
        song.refused = true
        if (payload.type == Chromecast.LOAD_CANCELLED && song === lastSent()) {
            // Our last LOAD gave way to another one, and not one of ours, which would have gone
            // after it. Stopped or not, the player is somebody else's now.
            takenOver = true
            songGone(takenOverReason())
            return false
        }
        if (song !== current || _status.value.state == ReceiverState.FAILED) return false
        val message = "${device.name} could not play it" + (reason?.let { " ($it)" } ?: "")
        _status.update { it.copy(state = ReceiverState.FAILED, error = message, reportedAt = now()) }
        return false
    }

    /**
     * The song is no longer ours to follow: stopped on the device, or somebody else's playing. It
     * goes IDLE, as FCastLink has it, unless it had already ended or failed. Holding [stateLock].
     */
    private fun songGone(reason: String) {
        // A song not sent yet is on its way to a player being opened for it, and none of this is about it.
        val loaded = current ?: return
        if (loaded.requestId == 0) return
        val state = _status.value.state
        if (state == ReceiverState.ENDED || state == ReceiverState.FAILED) return
        Log.i(TAG, reason)
        current = null
        _status.update { it.copy(state = ReceiverState.IDLE, error = reason, reportedAt = now()) }
    }

    private fun takenOverReason() = "Something else is playing on ${device.name}"

    // Staying connected

    private suspend fun keepAliveLoop() {
        var pingedAt = now()
        var askedAt = now()
        while (!closed) {
            delay(timing.tickMs)
            val at = now()
            if (at - lastHeardAt >= timing.deadAfterMs) {
                Log.i(TAG, "${device.name} stopped answering")
                lose("${device.name} stopped answering", LinkProblem.STOPPED_ANSWERING)
                return
            }
            val outgoing = mutableListOf<Outgoing>()
            if (at - pingedAt >= timing.pingEveryMs) {
                pingedAt = at
                outgoing += Outgoing(Chromecast.PLATFORM_SENDER, Chromecast.PLATFORM_RECEIVER, Chromecast.NS_HEARTBEAT, Chromecast.ping())
            }
            if (at - askedAt >= timing.statusEveryMs) {
                askedAt = at
                synchronized(stateLock) {
                    val state = _status.value.state
                    val player = player?.takeIf { it.connected }
                    if (current != null && player != null && (state == ReceiverState.PLAYING || state == ReceiverState.LOADING)) {
                        outgoing += askPlayer(player)
                    }
                }
            }
            sendQuietly(outgoing)
        }
    }

    private fun lose(reason: String, problem: LinkProblem) {
        synchronized(stateLock) {
            if (closed) return
            val link = _status.value.link
            if (link == LinkState.LOST || link == LinkState.CLOSED) return
            _status.update { it.copy(link = LinkState.LOST, error = reason, problem = problem) }
        }
        val failure = CastLinkException(reason, problem = problem)
        lostWith = failure
        failWaiting(failure)
        keepAlive?.cancel()
        runCatching { socket?.close() }
    }

    private companion object {
        const val TAG = "ChromecastLink"

        /** Songs remembered for telling late statuses apart; far more than can be in flight. */
        const val RECENT_LOADS = 8

        /** How near the end a song whose session vanished must have got to count as ended. */
        const val END_SLACK_MS = 5_000L

        /** A song's session number once it turned out to belong to another song: it names nothing. */
        const val NO_SESSION = -1L

        /** How long a closing link reads on after its goodbye, waiting for the device to hang up. */
        const val GOODBYE_WAIT_MS = 1_000L
    }
}
