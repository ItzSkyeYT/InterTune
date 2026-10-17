/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.together

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.double
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlin.math.abs

/**
 * What the phones in a room tell each other, sealed with the room's key inside Nostr events of
 * [KIND], an ephemeral kind that relays pass on and do not keep. Each phone plays the song from
 * YouTube itself; only where the leader is in it travels.
 *
 * Two roles: the owner made the room and alone can close it, send someone out or hand the lead
 * over; the leader is the phone everyone follows. The owner leads until it hands the lead to
 * someone, and can take it back.
 */
sealed interface TogetherMessage {
    /** The leader's playback: sent on every change and every [HEARTBEAT_MS]. [atMs] is the leader's clock. */
    data class State(
        val songId: String,
        val title: String,
        val artist: String,
        val positionMs: Long,
        val playing: Boolean,
        val speed: Double,
        val atMs: Long,
    ) : TogetherMessage

    /** A follower asking the leader's clock, [askedAtMs] on its own. */
    data class Ping(val askedAtMs: Long, val to: String) : TogetherMessage

    /** The leader's answer: [hostMs] its clock as it answered. */
    data class Pong(val askedAtMs: Long, val hostMs: Long, val to: String) : TogetherMessage

    /**
     * Somebody in the room, said every [HERE_MS] so the others know who is still there. The owner's
     * also says who leads, [lead] being that phone's key: the only word on it anyone takes.
     */
    data class Here(val name: String, val owner: Boolean, val lead: String? = null) : TogetherMessage

    /** A follower's song for the leader to play next. */
    data class Suggest(val songId: String, val title: String, val artist: String, val thumbnail: String, val by: String) : TogetherMessage

    /**
     * The leader's queue from the song playing on, in the order it plays, what autoplay added
     * included, and its repeat mode (Player.REPEAT_MODE_*): a follower's own queue is made this
     * one, so it plays the same song next and repeats as the leader does. It is not shown in the
     * room (his word of 17 Oct 2026: shared "so the client has an idea what will happen").
     */
    data class Queue(val songs: List<Song>, val repeat: Int, val shuffle: Boolean) : TogetherMessage

    data class Song(val id: String, val title: String, val artist: String, val thumbnail: String)

    /** The owner sends the phone with key [to] out of the room. */
    data class Kick(val to: String) : TogetherMessage

    /** The owner moves the room to a new code, sealed for the phone with key [to] alone (PairSeal). */
    data class Move(val to: String, val sealedCode: String) : TogetherMessage

    /** The owner closes the room. */
    data object End : TogetherMessage

    companion object {
        const val KIND = 21430
        const val HEARTBEAT_MS = 2_000L
        const val HERE_MS = 10_000L
        /** How many songs of the leader's queue travel, the one playing first. */
        const val QUEUE_SONGS = 30

        fun encode(message: TogetherMessage): String = when (message) {
            is State -> buildJsonObject {
                put("t", "state"); put("song", message.songId); put("title", message.title); put("artist", message.artist)
                put("pos", message.positionMs); put("playing", message.playing); put("speed", message.speed); put("at", message.atMs)
            }
            is Ping -> buildJsonObject { put("t", "ping"); put("c", message.askedAtMs); put("to", message.to) }
            is Pong -> buildJsonObject { put("t", "pong"); put("c", message.askedAtMs); put("h", message.hostMs); put("to", message.to) }
            is Here -> buildJsonObject {
                put("t", "here"); put("name", message.name); put("owner", message.owner)
                message.lead?.let { put("lead", it) }
            }
            is Suggest -> buildJsonObject {
                put("t", "suggest"); put("song", message.songId); put("title", message.title); put("artist", message.artist)
                put("thumb", message.thumbnail); put("by", message.by)
            }
            is Queue -> buildJsonObject {
                put("t", "queue"); put("repeat", message.repeat); put("shuffle", message.shuffle)
                putJsonArray("songs") {
                    message.songs.forEach { song ->
                        addJsonObject { put("id", song.id); put("title", song.title); put("artist", song.artist); put("thumb", song.thumbnail) }
                    }
                }
            }
            is Kick -> buildJsonObject { put("t", "kick"); put("to", message.to) }
            is Move -> buildJsonObject { put("t", "move"); put("to", message.to); put("code", message.sealedCode) }
            End -> buildJsonObject { put("t", "end") }
        }.toString()

        fun decode(text: String): TogetherMessage? = runCatching {
            val o = Json.parseToJsonElement(text) as JsonObject
            fun s(key: String) = o[key]!!.jsonPrimitive.content
            fun l(key: String) = o[key]!!.jsonPrimitive.long
            when (s("t")) {
                "state" -> State(s("song"), s("title"), s("artist"), l("pos"), o["playing"]!!.jsonPrimitive.boolean, o["speed"]!!.jsonPrimitive.double, l("at"))
                "ping" -> Ping(l("c"), s("to"))
                "pong" -> Pong(l("c"), l("h"), s("to"))
                "here" -> Here(s("name"), o["owner"]!!.jsonPrimitive.boolean, o["lead"]?.jsonPrimitive?.content)
                "suggest" -> Suggest(s("song"), s("title"), s("artist"), s("thumb"), s("by"))
                "queue" -> Queue(
                    songs = o["songs"]!!.jsonArray.map { item ->
                        val song = item.jsonObject
                        fun f(key: String) = song[key]!!.jsonPrimitive.content
                        Song(f("id"), f("title"), f("artist"), f("thumb"))
                    },
                    repeat = o["repeat"]!!.jsonPrimitive.int,
                    shuffle = o["shuffle"]!!.jsonPrimitive.boolean,
                )
                "kick" -> Kick(s("to"))
                "move" -> Move(s("to"), s("code"))
                "end" -> End
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

        /** What to do, playing at [localMs] while the host is at [targetMs], nudging by [maxNudge] at most. */
        fun decide(targetMs: Long, localMs: Long, hostSpeed: Double, maxNudge: Double = MAX_NUDGE): Follow {
            val drift = targetMs - localMs
            return when {
                abs(drift) > DRIFT_MS -> Seek(targetMs)
                abs(drift) <= IN_STEP_MS -> Steady(hostSpeed)
                else -> Nudge(hostSpeed * (1 + maxNudge * (drift / NUDGE_FULL_MS).coerceIn(-1.0, 1.0)))
            }
        }
    }
}

/**
 * Whose a follower's pause or play is. A pause while the room plays is the listener's: the room
 * goes on without this phone. A pause while the room is paused is the room's own. Play while
 * paused here takes this phone back to the room, playing or not.
 *
 * Decided by the room's state rather than by a window of time after the room's own commands: a
 * window long enough for a song to load (eight seconds) swallowed a pause pressed in it, which
 * the next look then undid, and a play, which left the phone playing on its own, never back.
 */
object PauseHere {
    /** What paused-here becomes after the player's [playWhenReady] changed, or null for no change. */
    fun after(pausedHere: Boolean, playWhenReady: Boolean, roomPlaying: Boolean): Boolean? = when {
        !playWhenReady && roomPlaying && !pausedHere -> true
        playWhenReady && pausedHere -> false
        else -> null
    }
}

/** Settings > Listen together as a room uses them, each held to its range, unset ones at their default. */
data class TogetherTuning(val toleranceMs: Long, val maxNudge: Double, val delayMs: Long) {
    companion object {
        const val DEFAULT_CATCH_UP_PERCENT = 5
        const val MIN_CATCH_UP_PERCENT = 1
        const val MAX_CATCH_UP_PERCENT = 5
        const val MAX_DELAY_MS = 400L

        fun of(toleranceMs: Int?, catchUpPercent: Int?, delayMs: Int?) = TogetherTuning(
            toleranceMs = (toleranceMs?.toLong() ?: SyncLock.DEFAULT_TOLERANCE_MS)
                .coerceIn(SyncLock.MIN_TOLERANCE_MS, SyncLock.MAX_TOLERANCE_MS),
            maxNudge = (catchUpPercent ?: DEFAULT_CATCH_UP_PERCENT)
                .coerceIn(MIN_CATCH_UP_PERCENT, MAX_CATCH_UP_PERCENT) / 100.0,
            delayMs = (delayMs?.toLong() ?: 0L).coerceIn(0L, MAX_DELAY_MS),
        )
    }
}

/**
 * Leaves the speed alone once a follower is in step (his word of 17 Oct 2026: "once it gets a
 * solid lock on the song sync, it stops over adjusting and only starts adjusting once the song
 * really starts drifting").
 *
 * The readings wobble by tens of ms on their own (the clock gap from pings, the leader's word
 * every two seconds), so a follower that corrected every look past [Follow.IN_STEP_MS] kept
 * speeding up and slowing down for nothing. In step for [LOCK_LOOKS] looks running, it locks: it
 * plays at the leader's speed and lets readings within [toleranceMs] pass. Only a gap past that
 * for [UNLOCK_LOOKS] looks running unlocks it, and it corrects until it is in step and locks
 * again. A gap past [Follow.DRIFT_MS] is a jump at once, locked or not: the leader seeked.
 */
class SyncLock(
    var toleranceMs: Long = DEFAULT_TOLERANCE_MS,
    var maxNudge: Double = Follow.MAX_NUDGE,
) {
    var locked = false
        private set
    private var inStep = 0
    private var off = 0

    fun decide(targetMs: Long, localMs: Long, hostSpeed: Double): Follow {
        val gap = abs(targetMs - localMs)
        if (gap > Follow.DRIFT_MS) {
            reset()
            return Follow.Seek(targetMs)
        }
        if (locked) {
            off = if (gap > toleranceMs) off + 1 else 0
            if (off < UNLOCK_LOOKS) return Follow.Steady(hostSpeed)
            locked = false
            off = 0
        }
        inStep = if (gap <= Follow.IN_STEP_MS) inStep + 1 else 0
        if (inStep >= LOCK_LOOKS) locked = true
        return Follow.decide(targetMs, localMs, hostSpeed, maxNudge)
    }

    /** After a jump, another song or another leader: the lock is earned again. */
    fun reset() {
        locked = false
        inStep = 0
        off = 0
    }

    companion object {
        /** Two seconds in step, at a look every quarter of a second. */
        const val LOCK_LOOKS = 8
        /** And a second past the tolerance before it corrects again. */
        const val UNLOCK_LOOKS = 4
        const val DEFAULT_TOLERANCE_MS = 60L
        /** Above [Follow.IN_STEP_MS], or it would lock and unlock on the same readings. */
        const val MIN_TOLERANCE_MS = 30L
        const val MAX_TOLERANCE_MS = 200L
    }
}
