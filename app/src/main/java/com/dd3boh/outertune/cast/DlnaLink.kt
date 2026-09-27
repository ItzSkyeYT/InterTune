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
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.IOException
import kotlin.math.roundToInt

/**
 * A link to a DLNA/UPnP media renderer: its description read once, then SOAP calls to its
 * AVTransport and RenderingControl, and polling to follow what it does (design.md section 2.2).
 *
 * On connecting it asks ConnectionManager what the renderer plays, which is what decides Opus or
 * AAC for it (CastFormats), and reads its volume. A song goes as SetAVTransportURI with DIDL-Lite
 * metadata, then Play. A renderer that says its transport is locked, or that it cannot change
 * track from where it is, is stopped first and asked once more, which is what several TVs need
 * while something is playing.
 *
 * Calls go one at a time, polls included: cheap renderers serve one request at a time, and a poll
 * that crossed a SetAVTransportURI has made some of them drop the song. The renderer is polled
 * every second while playing or opening a song and every five seconds otherwise, and given up as
 * lost after three polls in a row that could not reach it. A song's progress is judged by
 * [DlnaTracker].
 */
class DlnaLink(
    device: CastDevice,
    private val scope: CoroutineScope,
    private val http: LanHttp = LanHttp(),
    private val now: () -> Long = { System.nanoTime() / 1_000_000L },
    private val timing: Timing = Timing(),
) : ReceiverLink {
    data class Timing(
        val pollActiveMs: Long = 1_000,
        val pollIdleMs: Long = 5_000,
        val failuresBeforeLost: Int = 3,
        val startGraceMs: Long = 8_000,
        /** Every how many polls the volume is read, to follow the device's own remote. */
        val volumeEvery: Int = 5,
    )

    /** Filled in from the description on connecting: the renderer's own name and UDN. */
    @Volatile
    override var device: CastDevice = device
        private set

    @Volatile
    override var caps: LinkCaps = LinkCaps()
        private set

    /** The description, once read. */
    @Volatile
    var renderer: DlnaRenderer? = null
        private set

    private val _status = MutableStateFlow(ReceiverStatus())
    override val status: StateFlow<ReceiverStatus> = _status.asStateFlow()

    private val calls = Mutex()
    private val stateLock = Any()
    private val pollSoon = Channel<Unit>(Channel.CONFLATED)
    private var poller: Job? = null

    @Volatile
    private var closed = false

    private class Loaded(val number: Int, val item: CastItem, val tracker: DlnaTracker) {
        /**
         * Whether the renderer has taken the song. Until it has, a poll hears about the song
         * before, and a report of that one playing must not count as this one having played.
         */
        @Volatile
        var sent = false
    }

    private var loads = 0
    private var current: Loaded? = null

    override suspend fun connect() {
        synchronized(stateLock) {
            check(_status.value.link == LinkState.NEW) { "A link connects once" }
            _status.update { it.copy(link = LinkState.CONNECTING) }
        }
        val location = device.location ?: throw CastLinkException("${device.name} has no description to read")
        val found = try {
            val answer = withContext(Dispatchers.IO) { http.get(location) }
            if (answer.status != 200) throw CastLinkException("${device.name} did not describe itself (HTTP ${answer.status})")
            UpnpDevice.parse(answer.text, location) ?: throw CastLinkException("${device.name} is not something that plays music")
        } catch (e: IOException) {
            lose("Could not reach ${device.name}")
            throw e as? CastLinkException ?: CastLinkException("Could not reach ${device.name}", e)
        }
        renderer = found
        device = device.copy(
            id = found.udn,
            name = device.name.takeIf { it.isNotBlank() && it != device.host } ?: found.friendlyName,
            model = listOfNotNull(found.manufacturer, found.modelName).joinToString(" ").takeIf { it.isNotBlank() } ?: device.model,
        )

        // Neither of these is needed to play: a renderer that will not say gets the song as it is,
        // and its volume stays its own business.
        val formats = found.connectionManager?.let { manager ->
            runCatching { call(manager, "GetProtocolInfo") }
                .onFailure { Log.i(TAG, "${device.name} did not say what it plays: ${it.message}") }
                .getOrNull()?.get("Sink")?.let(ProtocolInfo::support)
        } ?: FormatSupport.Unknown
        val volume = readVolume()
        caps = LinkCaps(formats = formats, canSetSpeed = false, hasVolume = found.renderingControl != null)
        synchronized(stateLock) {
            if (closed) throw CastLinkException("Closed")
            _status.update { it.copy(link = LinkState.CONNECTED, volume = volume ?: it.volume) }
        }
        Log.i(TAG, "Connected to ${device.name} at $location, plays $formats")
        poller = scope.launch(Dispatchers.IO) { pollLoop() }
    }

    override suspend fun load(item: CastItem, startMs: Long, play: Boolean) {
        val avTransport = connected().avTransport
        val loaded = synchronized(stateLock) {
            val number = ++loads
            Loaded(number, item, DlnaTracker(startMs, item.durationMs, device.name, play, startGraceMs = timing.startGraceMs)).also {
                current = it
                publish(it)
            }
        }
        val args = listOf("InstanceID" to "0", "CurrentURI" to item.url, "CurrentURIMetaData" to Didl.track(item))
        try {
            try {
                call(avTransport, "SetAVTransportURI", args)
            } catch (e: UpnpFault) {
                if (e.code != Soap.TRANSPORT_LOCKED && e.code != Soap.TRANSITION_NOT_AVAILABLE) throw e
                Log.i(TAG, "${device.name} would not change song while busy; stopping it first")
                runCatching { call(avTransport, "Stop", listOf("InstanceID" to "0")) }
                call(avTransport, "SetAVTransportURI", args)
            }
            if (play) {
                call(avTransport, "Play", listOf("InstanceID" to "0", "Speed" to "1"))
                synchronized(stateLock) { loaded.tracker.playAsked(now()) }
            }
            loaded.sent = true
        } catch (e: IOException) {
            synchronized(stateLock) {
                if (current === loaded) {
                    _status.update { it.copy(state = ReceiverState.FAILED, error = e.message, reportedAt = now()) }
                }
            }
            throw e
        }
        pollSoon.trySend(Unit)
    }

    override suspend fun play() {
        val avTransport = connected().avTransport
        call(avTransport, "Play", listOf("InstanceID" to "0", "Speed" to "1"))
        synchronized(stateLock) { current?.let { it.tracker.playAsked(now()); publish(it) } }
        pollSoon.trySend(Unit)
    }

    override suspend fun pause() {
        call(connected().avTransport, "Pause", listOf("InstanceID" to "0"))
        pollSoon.trySend(Unit)
    }

    override suspend fun seek(positionMs: Long) {
        val avTransport = connected().avTransport
        // Under the call lock, so no poll can report the old position after the seek is noted.
        calls.withLock {
            val sendNow = synchronized(stateLock) {
                val loaded = current ?: return
                loaded.tracker.seekAsked(positionMs).also { publish(loaded) }
            }
            if (sendNow) rawCall(avTransport, "Seek", seekArgs(positionMs))
        }
        pollSoon.trySend(Unit)
    }

    override suspend fun stop() {
        val avTransport = connected().avTransport
        // Under the call lock too: a poll that heard PLAYING just before must not land after this.
        calls.withLock {
            synchronized(stateLock) { current?.let { it.tracker.stopAsked(); publish(it) } }
            rawCall(avTransport, "Stop", listOf("InstanceID" to "0"))
        }
        pollSoon.trySend(Unit)
    }

    override suspend fun setVolume(level: Float) {
        val control = connected().renderingControl ?: return
        val clamped = level.coerceIn(0f, 1f)
        call(control, "SetVolume", listOf("InstanceID" to "0", "Channel" to "Master", "DesiredVolume" to (clamped * 100).roundToInt().toString()))
        _status.update { it.copy(volume = clamped) }
    }

    override fun close() {
        synchronized(stateLock) {
            if (closed) return
            closed = true
            current = null
            _status.update { it.copy(link = LinkState.CLOSED) }
        }
        poller?.cancel()
        pollSoon.close()
    }

    // Calls

    private fun connected(): DlnaRenderer {
        if (_status.value.link != LinkState.CONNECTED) throw CastLinkException("Not connected to ${device.name}")
        return renderer ?: throw CastLinkException("Not connected to ${device.name}")
    }

    private suspend fun call(service: UpnpService, action: String, args: List<Pair<String, String>> = emptyList()): Map<String, String> =
        calls.withLock { rawCall(service, action, args) }

    /** One action, without taking the call lock; the caller holds it. */
    private suspend fun rawCall(service: UpnpService, action: String, args: List<Pair<String, String>> = emptyList()): Map<String, String> =
        withContext(Dispatchers.IO) {
            val body = Soap.body(service.serviceType, action, args).toByteArray(Charsets.UTF_8)
            val answer = http.post(service.controlUrl, Soap.headers(service.serviceType, action), body)
            when (val parsed = Soap.parse(answer.status, answer.text, action)) {
                is Soap.Answer.Ok -> parsed.values
                is Soap.Answer.Fault -> throw UpnpFault(action, parsed.code, parsed.description)
            }
        }

    private fun seekArgs(positionMs: Long) =
        listOf("InstanceID" to "0", "Unit" to "REL_TIME", "Target" to DlnaTime.format(positionMs))

    /** The volume from 0 to 1, or null when the renderer has no RenderingControl or will not say. */
    private suspend fun readVolume(): Float? {
        val control = renderer?.renderingControl ?: return null
        return runCatching { call(control, "GetVolume", listOf("InstanceID" to "0", "Channel" to "Master")) }
            .getOrNull()?.get("CurrentVolume")?.trim()?.toIntOrNull()?.let { (it / 100f).coerceIn(0f, 1f) }
    }

    // Following the renderer

    private suspend fun pollLoop() {
        var failures = 0
        var polls = 0
        while (!closed) {
            val active = _status.value.state.let { it == ReceiverState.PLAYING || it == ReceiverState.LOADING }
            withTimeoutOrNull(if (active) timing.pollActiveMs else timing.pollIdleMs) { pollSoon.receiveCatching() }
            if (closed) return
            try {
                poll()
                failures = 0
                if (++polls % timing.volumeEvery == 0 && caps.hasVolume) {
                    readVolume()?.let { volume -> _status.update { it.copy(volume = volume) } }
                }
            } catch (e: UpnpFault) {
                // The renderer is there and answering; it only did not like the question.
                failures = 0
                Log.d(TAG, "${device.name}: ${e.message}")
            } catch (e: IOException) {
                if (++failures >= timing.failuresBeforeLost) {
                    Log.i(TAG, "${device.name} stopped answering: ${e.message}")
                    lose("Lost ${device.name}")
                    return
                }
            }
        }
    }

    /**
     * One poll, holding the call lock from the first question to the report, so that a stop or a
     * seek made meanwhile is never overwritten by an answer given before it.
     */
    private suspend fun poll() {
        val avTransport = renderer?.avTransport ?: return
        calls.withLock {
            val loaded = synchronized(stateLock) { current }
            val transport = rawCall(avTransport, "GetTransportInfo", listOf("InstanceID" to "0"))
            val position = if (loaded != null) rawCall(avTransport, "GetPositionInfo", listOf("InstanceID" to "0")) else null
            val seek = synchronized(stateLock) {
                if (loaded == null || current !== loaded || !loaded.sent) return
                // An ended or failed song stays as it is (DlnaTracker.report), so published again at
                // every poll it only sent the same end or failure out once more, with a new time.
                // Anything else is published even when it looks the same: the time of the report is
                // what lets the playback rules believe one that came too soon after a pause or a
                // play, and what stops the position running on for a renderer that says it plays
                // but has stalled.
                val finished = loaded.tracker.isFinished
                loaded.tracker.report(
                    transportState = transport["CurrentTransportState"],
                    transportStatus = transport["CurrentTransportStatus"],
                    relTimeMs = DlnaTime.parse(position?.get("RelTime")),
                    trackDurationMs = DlnaTime.parse(position?.get("TrackDuration")),
                    at = now(),
                )
                if (!finished) publish(loaded)
                loaded.tracker.takePendingSeek()
            }
            if (seek != null && seek > 1000) {
                runCatching { rawCall(avTransport, "Seek", seekArgs(seek)) }
                    .onFailure { Log.i(TAG, "${device.name} would not seek: ${it.message}") }
            }
        }
    }

    /** Puts what [loaded]'s tracker says into the status. Called holding [stateLock]. */
    private fun publish(loaded: Loaded) {
        val view = loaded.tracker.view
        _status.update {
            it.copy(
                state = view.state,
                load = loaded.number,
                mediaId = loaded.item.mediaId,
                positionMs = view.positionMs,
                durationMs = view.durationMs,
                speed = 1f,
                reportedAt = now(),
                error = view.error,
            )
        }
    }

    private fun lose(reason: String) {
        synchronized(stateLock) {
            if (closed) return
            val link = _status.value.link
            if (link == LinkState.LOST || link == LinkState.CLOSED) return
            _status.update { it.copy(link = LinkState.LOST, error = reason) }
        }
        poller?.cancel()
    }

    private companion object {
        const val TAG = "DlnaLink"
    }
}
