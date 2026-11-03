/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.cast

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.emptyFlow
import java.io.IOException

/** The kinds of receiver casting talks to, in the order they were built. */
enum class CastProtocol { FCAST, DLNA, CHROMECAST }

/**
 * A receiver, found on the network or typed in by address.
 *
 * [id] stays the same from one search to the next, so a list that refreshes while it is open does
 * not jump about: the FCast service name, a DLNA device's UDN, or a Chromecast's own id. [host] and
 * [port] are where the link connects: an FCast receiver's TCP port, a Chromecast's TLS port (8009,
 * or another for a speaker group), or for DLNA the host of [location], the device description that
 * says where its services are. [audioOnly] is a speaker, or a group of them, with no screen.
 */
data class CastDevice(
    val protocol: CastProtocol,
    val id: String,
    val name: String,
    val host: String,
    val port: Int,
    val location: String? = null,
    val model: String? = null,
    val audioOnly: Boolean = false,
)

/**
 * One song as a receiver is given it: where to fetch it (the cast server's url), what it is, and
 * what to show while it plays. [length] is in bytes and [durationMs] in milliseconds, each -1
 * when not known.
 */
data class CastItem(
    val mediaId: String,
    val url: String,
    val mimeType: String,
    val length: Long = -1,
    val durationMs: Long = -1,
    val title: String? = null,
    val artist: String? = null,
    val album: String? = null,
    val artworkUrl: String? = null,
)

/**
 * The playing song's synced lyrics, for a receiver with a screen for them: each line with its
 * start in the song, moved by [shiftMs] as the phone's own lines are (LyricsTiming). A blank
 * line is a pause in the singing.
 */
data class CastLyrics(val mediaId: String, val lines: List<Line>, val shiftMs: Long = 0) {
    data class Line(val startMs: Long, val text: String)
}

/**
 * One of the songs that play next, as a receiver with a screen for them lists it under "Up next":
 * what to call it, who sings it, and a small cover (a web address, or none).
 */
data class CastNext(val title: String, val artist: String? = null, val cover: String? = null)

/**
 * A Listen together room, for a receiver with a screen for it (InterTune's own): its [name], how
 * many are [count]ed in it, the first letters of some of their names ([people]), and the room's
 * invite as a QR code, a PNG data url in [qr] (QrPng), for a phone beside the TV to scan.
 */
data class CastRoom(val name: String, val count: Int, val people: List<String> = emptyList(), val qr: String? = null) {
    companion object {
        /** How many people's first letters one message carries. */
        const val MAX_PEOPLE = 6

        /** The room of [name] with [members] in it, each by name: their count and first letters. */
        fun of(name: String, members: List<String>, qr: String?): CastRoom =
            CastRoom(name, members.size, members.take(MAX_PEOPLE).map(::initialOf), qr)

        /** The first letter of [name], in capitals: a whole emoji when the name starts with one. */
        fun initialOf(name: String): String {
            val trimmed = name.trim()
            if (trimmed.isEmpty()) return "?"
            return String(Character.toChars(trimmed.codePointAt(0))).uppercase()
        }
    }
}

/** Whether the link to a receiver is up. */
enum class LinkState {
    NEW,
    CONNECTING,
    CONNECTED,

    /** It went away without being asked to: the receiver closed, stopped answering, or the network went. */
    LOST,

    /** [ReceiverLink.close] was called. */
    CLOSED,
}

/**
 * Why a link could not be made, or went away, as the app puts it to people. The links' own
 * messages stay in English, for the log; this is what the picker and the notice of a lost receiver
 * say, in the listener's language.
 */
enum class LinkProblem {
    /** Nothing answered at the receiver's address. */
    UNREACHABLE,

    /** Something answered, but not as a receiver: no description to read, or not a renderer. */
    NOT_A_RECEIVER,

    /** The receiver closed the connection. */
    HUNG_UP,

    /** The receiver stopped answering. */
    STOPPED_ANSWERING,

    /** The connection broke. */
    LOST,
}

/** What the receiver is doing with the song it was last given. */
enum class ReceiverState {
    /** Nothing loaded, stopped, or stopped on the device itself. */
    IDLE,

    /** A song was sent and the receiver has not said yet that it plays or holds it. */
    LOADING,
    PLAYING,
    PAUSED,

    /** The song ran to its end. Stays until the next load. */
    ENDED,

    /** The receiver could not play the song; [ReceiverStatus.error] says why. Stays until the next load. */
    FAILED,
}

/**
 * The last thing a receiver said, as one value.
 *
 * [load] counts the songs sent on this link, so a report can be told apart from one about the song
 * before, even when the same song is sent twice. [positionMs] was true at [reportedAt], on the
 * link's own clock; [positionAt] carries it on while the song plays. [volume] is from 0 to 1, or
 * null until the receiver says. ENDED and FAILED are kept until the next load, so whoever watches
 * the status sees them even when it looks late. Once [link] is LOST, [error] says why in English
 * and [problem] says it for the app to put in words.
 */
data class ReceiverStatus(
    val link: LinkState = LinkState.NEW,
    val state: ReceiverState = ReceiverState.IDLE,
    val load: Int = 0,
    val mediaId: String? = null,
    val positionMs: Long = 0,
    val durationMs: Long = -1,
    val speed: Float = 1f,
    val reportedAt: Long = 0,
    val volume: Float? = null,
    val error: String? = null,
    val problem: LinkProblem? = null,
) {
    /** The position at [now], moved on from the last report at the playing speed. */
    fun positionAt(now: Long): Long {
        if (state != ReceiverState.PLAYING) return positionMs
        val moved = positionMs + ((now - reportedAt).coerceAtLeast(0) * speed).toLong()
        return if (durationMs > 0) moved.coerceAtMost(durationMs) else moved
    }
}

/**
 * What a receiver said it can do, once connected. [formats] decides which encoding a song is
 * sent in (CastFormats).
 */
data class LinkCaps(
    val formats: FormatSupport = FormatSupport.Unknown,
    val canSetSpeed: Boolean = false,
    val hasVolume: Boolean = true,
)

/**
 * One connection to one receiver, whatever its protocol: FCastLink, DlnaLink or ChromecastLink.
 *
 * The remote player drives it and watches [status]; it knows nothing of queues, which stay the
 * app's. Every call blocks on the network, so they suspend, and each throws an [IOException] with
 * a reason a person can read when the receiver cannot be reached or refuses.
 */
interface ReceiverLink {
    val device: CastDevice

    /** Meaningful once [connect] has returned. */
    val caps: LinkCaps

    val status: StateFlow<ReceiverStatus>

    /**
     * What the receiver asks of the phone (RemoteRequest). Only InterTune's own Chromecast receiver
     * asks anything; every other link has nothing here.
     */
    val requests: Flow<RemoteRequest> get() = emptyFlow()

    suspend fun connect()

    /** Sends [item] and starts it at [startMs], playing or held paused there. */
    suspend fun load(item: CastItem, startMs: Long, play: Boolean)

    suspend fun play()

    suspend fun pause()

    suspend fun seek(positionMs: Long)

    suspend fun stop()

    /** The receiver's volume, from 0 to 1. */
    suspend fun setVolume(level: Float)

    /**
     * Shows [lyrics] on the receiver's screen, or puts them away when null. Only InterTune's own
     * Chromecast receiver has a screen for them; every other link ignores this.
     */
    suspend fun showLyrics(lyrics: CastLyrics?) {}

    /**
     * Tells the receiver's screen what the phone is called, for its idle screen to say who is
     * connected. Only InterTune's own Chromecast receiver has a screen for it; every other link
     * ignores this.
     */
    suspend fun hello(name: String) {}

    /**
     * Shows the songs that play after the current one ([next], the first few) on the receiver's
     * screen, or none. Only InterTune's own Chromecast receiver has a screen for them.
     */
    suspend fun showQueue(next: List<CastNext>) {}

    /**
     * Shows a Listen together [room] on the receiver's screen, or puts it away when null. Only
     * InterTune's own Chromecast receiver has a screen for it.
     */
    suspend fun showRoom(room: CastRoom?) {}

    /**
     * Tells the receiver's screen the cast is over, for it to say so for a moment. True when it
     * was told, so the caller gives the screen that moment before stopping (CastFarewell); false
     * for every receiver with no screen of ours, and when the link is gone.
     */
    suspend fun sayBye(): Boolean = false

    /** Hangs up. Safe to call more than once, from any thread. */
    fun close()
}

/**
 * The receiver cannot be reached, or refused, with a reason for the log, and a [problem] for the
 * words shown when it happens while connecting.
 */
open class CastLinkException(
    message: String,
    cause: Throwable? = null,
    val problem: LinkProblem = LinkProblem.LOST,
) : IOException(message, cause)
