/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.cast

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
import java.net.InetSocketAddress
import java.net.Socket
import javax.net.SocketFactory
import kotlin.math.abs

/**
 * A link to an FCast receiver: one TCP connection, spoken in FCast v3 and down to v2 or v1 for a
 * receiver that knows no more.
 *
 * On connecting, both sides send Version; from v3 on both then send Initial, and this link asks to
 * hear MediaItemEnd, so the end of a song is an event rather than a guess. A receiver pings when it
 * has heard nothing for a few seconds and hangs up on a sender that does not answer (2.5 s on
 * Android, 3 s on desktop), so every Ping is answered at once from the reading thread, and the link
 * pings in turn and gives the receiver up as lost when it goes quiet for longer than [Timing.deadAfterMs].
 *
 * Receivers report as they please, so what they say is taken with care:
 * - a report about the song before this one can arrive after the new one is sent, so after a load
 *   the first report is taken only when it is near the start asked for (or says idle);
 * - the Android receiver reports the end of a song as paused at its full length, just before the
 *   event, so a pause at the very end that nobody asked for is taken as the end too, which is also
 *   how a v2 receiver, which has no events, is followed;
 * - it reports a failure as idle followed by PlaybackError, and the error wins.
 */
class FCastLink(
    override val device: CastDevice,
    private val scope: CoroutineScope,
    private val sender: Sender,
    private val socketFactory: SocketFactory = SocketFactory.getDefault(),
    private val now: () -> Long = { System.nanoTime() / 1_000_000L },
    private val timing: Timing = Timing(),
) : ReceiverLink {
    /** How this app introduces itself in Initial. */
    data class Sender(val displayName: String, val appName: String, val appVersion: String)

    data class Timing(
        val connectTimeoutMs: Int = 5_000,
        /** How long to wait for the receiver's Version before taking it for a v2 receiver. */
        val versionWaitMs: Long = 2_000,
        val pingAfterMs: Long = 3_000,
        val deadAfterMs: Long = 9_000,
        val tickMs: Long = 1_000,
    )

    @Volatile
    override var caps: LinkCaps = LinkCaps()
        private set

    private val _status = MutableStateFlow(ReceiverStatus())
    override val status: StateFlow<ReceiverStatus> = _status.asStateFlow()

    /** The version both sides speak, once the receiver said its own. */
    @Volatile
    var version: Long = 0
        private set

    /** What the receiver said about itself in Initial, for the picker and the logs. */
    @Volatile
    var receiverInfo: FCast.ReceiverInfo? = null
        private set

    private val stateLock = Any()
    private val writeLock = Any()

    @Volatile
    private var socket: Socket? = null
    private var output: OutputStream? = null
    private var reader: Job? = null
    private var keepAlive: Job? = null

    @Volatile
    private var closed = false

    @Volatile
    private var lastHeardAt = 0L

    @Volatile
    private var pingSentAt = -1L

    /** The song sent last, and what is known of how the receiver is getting on with it. */
    private class Loaded(val url: String, val startMs: Long, val sentAt: Long) {
        /** Whether a report about this song has been taken yet. */
        var heard = false

        /** Where the first report is expected: the start, or where a seek before it went. */
        var expectedMs = startMs
        var pauseAsked = false
    }

    private var loads = 0
    private var current: Loaded? = null
    private val versionHeard = CompletableDeferred<Long>()

    override suspend fun connect() {
        synchronized(stateLock) {
            check(_status.value.link == LinkState.NEW) { "A link connects once" }
            _status.update { it.copy(link = LinkState.CONNECTING) }
        }
        withContext(Dispatchers.IO) {
            val connection = socketFactory.createSocket()
            try {
                connection.connect(InetSocketAddress(device.host, device.port), timing.connectTimeoutMs)
                connection.tcpNoDelay = true
                connection.keepAlive = true
            } catch (e: IOException) {
                runCatching { connection.close() }
                lose("Could not reach ${device.name}")
                throw CastLinkException("Could not reach ${device.name}", e)
            }
            socket = connection
            output = connection.getOutputStream()
            lastHeardAt = now()
            send(FCast.VERSION_MESSAGE, FCast.versionBody())
            val input = connection.getInputStream()
            reader = scope.launch(Dispatchers.IO) { readLoop(input) }

            val theirs = withTimeoutOrNull(timing.versionWaitMs) { versionHeard.await() }
            if (closed || _status.value.link == LinkState.LOST) {
                throw CastLinkException("${device.name} hung up")
            }
            // A receiver that says nothing is an old one; the Android receiver itself assumes v2
            // of a sender that does the same. One that speaks a later version than ours is spoken
            // to in ours, which the desktop receiver accepts from v1 to v4.
            version = minOf(theirs ?: 2L, FCast.VERSION)
            if (version >= 3) {
                send(FCast.INITIAL, FCast.initialBody(sender.displayName, sender.appName, sender.appVersion))
                send(FCast.SUBSCRIBE_EVENT, FCast.subscribeBody(FCast.EVENT_MEDIA_ITEM_END))
            }
            caps = LinkCaps(formats = FormatSupport.Unknown, canSetSpeed = version >= 2, hasVolume = true)
            synchronized(stateLock) {
                if (closed) throw CastLinkException("Closed")
                _status.update { it.copy(link = LinkState.CONNECTED) }
            }
            Log.i(TAG, "Connected to ${device.name} (${device.host}:${device.port}), speaking v$version")
            if (version >= 2) keepAlive = scope.launch(Dispatchers.IO) { keepAliveLoop() }
        }
    }

    override suspend fun load(item: CastItem, startMs: Long, play: Boolean) {
        if (_status.value.link != LinkState.CONNECTED) throw CastLinkException("Not connected to ${device.name}")
        val loaded = synchronized(stateLock) {
            val number = ++loads
            // The Android receiver ignores a Play for the url it already holds and carries on
            // where it was, so the same song sent again (repeat one, a song queued twice, a retry)
            // would do nothing. A mark per load makes each url new; the cast server ignores it.
            val url = item.url + (if ('?' in item.url) "&" else "?") + "load=" + number
            Loaded(url, startMs.coerceAtLeast(0), now()).also {
                it.pauseAsked = !play
                current = it
                _status.update { status ->
                    status.copy(
                        state = ReceiverState.LOADING,
                        load = number,
                        mediaId = item.mediaId,
                        positionMs = it.startMs,
                        durationMs = item.durationMs,
                        speed = 1f,
                        reportedAt = now(),
                        error = null,
                    )
                }
            }
        }
        sendNow(FCast.PLAY, FCast.playBody(item, loaded.url, loaded.startMs, version))
        // FCast has no way to load paused; a pause straight after is how a sender holds a song.
        if (!play) sendNow(FCast.PAUSE)
    }

    override suspend fun play() {
        synchronized(stateLock) { current?.pauseAsked = false }
        sendNow(FCast.RESUME)
    }

    override suspend fun pause() {
        synchronized(stateLock) { current?.pauseAsked = true }
        sendNow(FCast.PAUSE)
    }

    override suspend fun seek(positionMs: Long) {
        synchronized(stateLock) {
            val loaded = current ?: return@synchronized
            loaded.expectedMs = positionMs.coerceAtLeast(0)
            _status.update { it.copy(positionMs = loaded.expectedMs, reportedAt = now()) }
        }
        sendNow(FCast.SEEK, FCast.seekBody(positionMs))
    }

    override suspend fun stop() {
        synchronized(stateLock) {
            current = null
            _status.update { it.copy(state = ReceiverState.IDLE, positionMs = 0, reportedAt = now()) }
        }
        sendNow(FCast.STOP)
    }

    override suspend fun setVolume(level: Float) {
        val clamped = level.coerceIn(0f, 1f)
        _status.update { it.copy(volume = clamped) }
        sendNow(FCast.SET_VOLUME, FCast.setVolumeBody(clamped))
    }

    /** Playback speed, which FCast receivers from v2 on can change. */
    suspend fun setSpeed(speed: Float) {
        if (version < 2) return
        sendNow(FCast.SET_SPEED, FCast.setSpeedBody(speed))
    }

    override fun close() {
        synchronized(stateLock) {
            if (closed) return
            closed = true
            current = null
            _status.update { it.copy(link = LinkState.CLOSED) }
        }
        keepAlive?.cancel()
        runCatching { socket?.close() }
        reader?.cancel()
        versionHeard.completeExceptionally(CastLinkException("Closed"))
    }

    // Sending

    private suspend fun sendNow(opcode: Int, body: String? = null) = withContext(Dispatchers.IO) {
        if (_status.value.link != LinkState.CONNECTED) throw CastLinkException("Not connected to ${device.name}")
        send(opcode, body)
    }

    /** Writes one frame, from whichever thread; the reading thread answers pings through here. */
    private fun send(opcode: Int, body: String? = null) {
        if (opcode > FCast.highestOpcode(version.takeIf { it > 0 } ?: FCast.VERSION)) return
        val frame = FCast.encode(opcode, body)
        try {
            synchronized(writeLock) {
                val out = output ?: throw IOException("Not connected")
                out.write(frame)
                out.flush()
            }
        } catch (e: IOException) {
            lose("Lost ${device.name}")
            throw CastLinkException("Lost ${device.name}", e)
        }
    }

    // Receiving

    private fun readLoop(input: InputStream) {
        try {
            while (true) {
                val frame = FCast.read(input) ?: break
                lastHeardAt = now()
                pingSentAt = -1
                handle(frame)
            }
            lose("${device.name} hung up")
        } catch (e: IOException) {
            if (!closed) Log.i(TAG, "Reading from ${device.name} failed: ${e.message}")
            lose("Lost ${device.name}")
        }
    }

    private fun handle(frame: FCast.Frame) {
        when (frame.opcode) {
            FCast.VERSION_MESSAGE -> FCast.parseVersion(frame.body)?.let { versionHeard.complete(it) }
            FCast.PING -> runCatching { send(FCast.PONG) }
            FCast.PONG -> Unit
            FCast.INITIAL -> receiverInfo = FCast.parseInitial(frame.body)
            FCast.PLAYBACK_UPDATE -> FCast.parsePlaybackUpdate(frame.body)?.let(::onPlaybackUpdate)
            FCast.VOLUME_UPDATE -> FCast.parseVolume(frame.body)?.let { volume -> _status.update { it.copy(volume = volume) } }
            FCast.PLAYBACK_ERROR -> onError(FCast.parseError(frame.body))
            FCast.EVENT -> FCast.parseEvent(frame.body)?.let(::onEvent)
            FCast.PLAY_UPDATE -> onPlayUpdate(FCast.parsePlayUpdateUrl(frame.body))
            else -> Log.d(TAG, "Ignored opcode ${frame.opcode} from ${device.name}")
        }
    }

    private fun onPlaybackUpdate(update: FCast.PlaybackUpdate) = synchronized(stateLock) {
        val loaded = current ?: return@synchronized
        val status = _status.value
        if (status.state == ReceiverState.ENDED || status.state == ReceiverState.FAILED) return@synchronized
        if (!loaded.heard) {
            val position = update.positionMs
            val early = now() - loaded.sentAt < STALE_REPORT_MS
            if (early && update.state != FCast.STATE_IDLE && position != null && abs(position - loaded.expectedMs) > STALE_REPORT_MS) {
                return@synchronized
            }
            loaded.heard = true
        }
        val duration = update.durationMs ?: status.durationMs
        val position = update.positionMs ?: status.positionMs
        val atEnd = duration > 0 && position >= duration - END_SLACK_MS
        val state = when (update.state) {
            FCast.STATE_PLAYING -> ReceiverState.PLAYING
            FCast.STATE_PAUSED -> if (atEnd && !loaded.pauseAsked) ReceiverState.ENDED else ReceiverState.PAUSED
            // Idle before the song has played is the receiver still opening it.
            FCast.STATE_IDLE -> when {
                status.state == ReceiverState.LOADING -> ReceiverState.LOADING
                atEnd && !loaded.pauseAsked -> ReceiverState.ENDED
                else -> ReceiverState.IDLE
            }
            else -> status.state
        }
        _status.update {
            it.copy(
                state = state,
                positionMs = if (state == ReceiverState.ENDED && duration > 0) duration else position,
                durationMs = duration,
                speed = update.speed ?: it.speed,
                reportedAt = now(),
            )
        }
    }

    private fun onError(message: String) = synchronized(stateLock) {
        if (current == null) return@synchronized
        Log.i(TAG, "${device.name} could not play: $message")
        _status.update { it.copy(state = ReceiverState.FAILED, error = message, reportedAt = now()) }
    }

    private fun onEvent(event: FCast.Event) = synchronized(stateLock) {
        if (event.type != FCast.EVENT_MEDIA_ITEM_END) return@synchronized
        val loaded = current ?: return@synchronized
        // An event that names another url is about a song this link has moved on from.
        if (event.url != null && event.url != loaded.url) return@synchronized
        if (_status.value.state == ReceiverState.FAILED) return@synchronized
        _status.update {
            it.copy(
                state = ReceiverState.ENDED,
                positionMs = if (it.durationMs > 0) it.durationMs else it.positionMs,
                reportedAt = now(),
            )
        }
    }

    /**
     * The receiver tells every sender what any of them plays. Our own Play comes back with our
     * url; another url means someone else took the receiver over, and the song this link sent is
     * no longer playing there.
     */
    private fun onPlayUpdate(url: String?) = synchronized(stateLock) {
        val loaded = current ?: return@synchronized
        if (url == null || url == loaded.url) return@synchronized
        Log.i(TAG, "Another sender is playing on ${device.name}")
        current = null
        _status.update { it.copy(state = ReceiverState.IDLE, error = "Something else is playing on ${device.name}", reportedAt = now()) }
    }

    private suspend fun keepAliveLoop() {
        while (!closed) {
            delay(timing.tickMs)
            val quiet = now() - lastHeardAt
            if (quiet >= timing.deadAfterMs) {
                Log.i(TAG, "${device.name} stopped answering")
                lose("${device.name} stopped answering")
                runCatching { socket?.close() }
                return
            }
            if (quiet >= timing.pingAfterMs && pingSentAt < 0) {
                pingSentAt = now()
                runCatching { send(FCast.PING) }
            }
        }
    }

    private fun lose(reason: String) {
        synchronized(stateLock) {
            if (closed) return
            val link = _status.value.link
            if (link == LinkState.LOST || link == LinkState.CLOSED) return
            _status.update { it.copy(link = LinkState.LOST, error = reason) }
        }
        versionHeard.completeExceptionally(CastLinkException(reason))
        keepAlive?.cancel()
        runCatching { socket?.close() }
    }

    private companion object {
        const val TAG = "FCastLink"

        /**
         * How far from the start asked for the first report after a load may be and still be about
         * it, and for how long after the load that test applies.
         */
        const val STALE_REPORT_MS = 10_000L

        /** How close to the end a pause nobody asked for must be to count as the end. */
        const val END_SLACK_MS = 1_500L
    }
}
