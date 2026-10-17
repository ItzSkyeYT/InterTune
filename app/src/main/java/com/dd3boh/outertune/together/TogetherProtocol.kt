/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.together

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.double
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put
import kotlin.math.abs

/**
 * What the phones in a room tell each other, sealed with the room's key inside Nostr events of
 * [KIND], an ephemeral kind that relays pass on and do not keep. Each phone plays the song from
 * YouTube itself; only where the host is in it travels.
 */
sealed interface TogetherMessage {
    /** The host's playback: sent on every change and every [HEARTBEAT_MS]. [atMs] is the host's clock. */
    data class State(
        val songId: String,
        val title: String,
        val artist: String,
        val positionMs: Long,
        val playing: Boolean,
        val speed: Double,
        val atMs: Long,
    ) : TogetherMessage

    /** A guest asking the host's clock, [askedAtMs] on its own. */
    data class Ping(val askedAtMs: Long, val to: String) : TogetherMessage

    /** The host's answer: [hostMs] its clock as it answered. */
    data class Pong(val askedAtMs: Long, val hostMs: Long, val to: String) : TogetherMessage

    /** Somebody in the room, said every [HERE_MS] so the others know who is still there. */
    data class Here(val name: String, val host: Boolean) : TogetherMessage

    companion object {
        const val KIND = 21430
        const val HEARTBEAT_MS = 2_000L
        const val HERE_MS = 10_000L

        fun encode(message: TogetherMessage): String = when (message) {
            is State -> buildJsonObject {
                put("t", "state"); put("song", message.songId); put("title", message.title); put("artist", message.artist)
                put("pos", message.positionMs); put("playing", message.playing); put("speed", message.speed); put("at", message.atMs)
            }
            is Ping -> buildJsonObject { put("t", "ping"); put("c", message.askedAtMs); put("to", message.to) }
            is Pong -> buildJsonObject { put("t", "pong"); put("c", message.askedAtMs); put("h", message.hostMs); put("to", message.to) }
            is Here -> buildJsonObject { put("t", "here"); put("name", message.name); put("host", message.host) }
        }.toString()

        fun decode(text: String): TogetherMessage? = runCatching {
            val o = Json.parseToJsonElement(text) as JsonObject
            fun s(key: String) = o[key]!!.jsonPrimitive.content
            fun l(key: String) = o[key]!!.jsonPrimitive.long
            when (s("t")) {
                "state" -> State(s("song"), s("title"), s("artist"), l("pos"), o["playing"]!!.jsonPrimitive.boolean, o["speed"]!!.jsonPrimitive.double, l("at"))
                "ping" -> Ping(l("c"), s("to"))
                "pong" -> Pong(l("c"), l("h"), s("to"))
                "here" -> Here(s("name"), o["host"]!!.jsonPrimitive.boolean)
                else -> null
            }
        }.getOrNull()
    }
}

/**
 * The gap between the host's clock and this phone's, from pings: the answer that came back fastest
 * says it best, since half its round trip is the most it can be off by.
 */
class ClockGap {
    var offsetMs = 0L
        private set
    var bestRoundTripMs = Long.MAX_VALUE
        private set
    val known: Boolean get() = bestRoundTripMs != Long.MAX_VALUE

    /** A pong for a ping sent at [askedAtMs], answered at [hostMs], back at [nowMs]. */
    fun heard(askedAtMs: Long, hostMs: Long, nowMs: Long) {
        val roundTrip = nowMs - askedAtMs
        // The first answer is always taken: "none yet" plus the slack would overflow.
        if (roundTrip !in 0..10_000 || (known && roundTrip > bestRoundTripMs + SLACK_MS)) return
        if (roundTrip <= bestRoundTripMs) bestRoundTripMs = roundTrip
        offsetMs = hostMs - (askedAtMs + nowMs) / 2
    }

    /** The host's clock now, by this phone's. */
    fun hostNow(nowMs: Long) = nowMs + offsetMs

    companion object {
        private const val SLACK_MS = 50L
    }
}

/** What a guest does to stay with the host. */
sealed interface Follow {
    /** In step: play at the host's speed. */
    data class Steady(val speed: Double) : Follow
    /** A little off: play a touch faster or slower until it closes. */
    data class Nudge(val speed: Double) : Follow
    /** Too far off to nudge: jump. */
    data class Seek(val positionMs: Long) : Follow

    companion object {
        /** Within this, in step. */
        const val IN_STEP_MS = 25L
        /** Past this, a seek: a nudge of 5% takes 8 s to close 400 ms. */
        const val DRIFT_MS = 400L
        const val MAX_NUDGE = 0.05
        /** The gap at which the nudge is full. */
        const val NUDGE_FULL_MS = 120.0

        /** Where the host is in the song now, by its last [state] and its clock [hostNowMs]. */
        fun target(state: TogetherMessage.State, hostNowMs: Long): Long =
            if (state.playing) state.positionMs + ((hostNowMs - state.atMs) * state.speed).toLong() else state.positionMs

        /** What to do, playing at [localMs] while the host is at [targetMs]. */
        fun decide(targetMs: Long, localMs: Long, hostSpeed: Double): Follow {
            val drift = targetMs - localMs
            return when {
                abs(drift) > DRIFT_MS -> Seek(targetMs)
                abs(drift) <= IN_STEP_MS -> Steady(hostSpeed)
                else -> Nudge(hostSpeed * (1 + MAX_NUDGE * (drift / NUDGE_FULL_MS).coerceIn(-1.0, 1.0)))
            }
        }
    }
}
