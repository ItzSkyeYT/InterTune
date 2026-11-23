/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.together

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.double
import kotlinx.serialization.json.int
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.buildJsonArray
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
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
        /**
         * The song is YouTube Music's version of a file on the leader's phone (PhoneSongs.Heard): the others say it may not
         * be exactly what the leader plays (his word of 22 Nov 2026). Sent only when true; builds before it ignore it.
         */
        val fromFile: Boolean = false,
    ) : TogetherMessage

    /** A follower asking the leader's clock, [askedAtMs] on its own. */
    data class Ping(val askedAtMs: Long, val to: String) : TogetherMessage

    /** The leader's answer: [hostMs] its clock as it answered. */
    data class Pong(val askedAtMs: Long, val hostMs: Long, val to: String) : TogetherMessage

    /**
     * Somebody in the room, said every [HERE_MS] so the others know who is still there. The owner's
     * also says who leads, [lead] being that phone's key: the only word on it anyone takes. And since
     * when, [leadSince] by the owner's clock: relays do not keep order, and an older word on who leads
     * is not taken after a newer one (LeadWord). Null from builds before 22 Nov 2026, taken as ever.
     * And the owner's [settings], who may do what (RoomSettings): null from anyone else, and from
     * an owner on an older build. [arranges]: this phone, given control, takes a guest's change to
     * the queue ([Arrange]), which a build before this one drops.
     */
    data class Here(
        val name: String, val owner: Boolean, val lead: String? = null, val leadSince: Long? = null,
        val settings: RoomSettings? = null, val arranges: Boolean = false,
    ) : TogetherMessage

    /**
     * A follower's song for the leader to play next, or at the end of its queue when [atEnd]. An older host
     * does not know "end" and puts it next, which is acceptable. [batch], [index] and [count] say it is one of
     * [count] songs of one action, [index] 0 being the one that plays first: relays do not keep the order of
     * messages, so a newer host collects the batch and sorts it. Older hosts (and old guests, who send none of
     * the three) go by arrival, which is why a guest still sends "next" songs last-first.
     */
    data class Suggest(
        val songId: String, val title: String, val artist: String, val thumbnail: String, val by: String,
        val atEnd: Boolean = false, val batch: String = "", val index: Int = 0, val count: Int = 1,
    ) : TogetherMessage

    /**
     * The leader's queue from the song playing on, in the order it plays, what autoplay added
     * included, and its repeat mode (Player.REPEAT_MODE_*): a follower's own queue is made this
     * one, so it plays the same song next and repeats as the leader does. It is not shown in the
     * room (his word of 17 Oct 2026: shared "so the client has an idea what will happen").
     */
    data class Queue(val songs: List<Song>, val repeat: Int, val shuffle: Boolean) : TogetherMessage

    /**
     * A guest's change to the room's queue, for the person in control to make while the owner lets everyone change it
     * (RoomSettings.everyoneArranges): [songId] moved to come right after [after], the song playing when that is null,
     * or taken out when [remove]. A build before this one drops it, so a guest makes the change only where the person in
     * control said in its Here that it takes them ([Here.arranges]).
     */
    data class Arrange(val songId: String, val after: String? = null, val remove: Boolean = false) : TogetherMessage

    data class Song(val id: String, val title: String, val artist: String, val thumbnail: String)

    /**
     * A follower's own song proposed to the leader (his ask of 17 Oct 2026), with the queue its own
     * settings made after it and where in the song it is: the leader is asked, and on a yes plays
     * them from there, and the room with it.
     */
    data class Propose(val songs: List<Song>, val positionMs: Long, val by: String) : TogetherMessage

    /**
     * The leader's Not now to the guest with key [to], for the song [songId] it proposed: the guest's Propose comes back
     * instead of saying "Proposed" for good (22 Nov 2026). A build before this one never sends it, and drops it unread as
     * a word it does not know, so it stays as it was.
     */
    data class NotNow(val to: String, val songId: String) : TogetherMessage

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

        /**
         * What a room carries of a song's picture: an address on YouTube's own picture servers, and nothing else. A song
         * from a file on the phone has the file's full path there (/storage/emulated/0/Music/<folders>/<file>, the
         * folders often named after people), and it went to everyone with the room's code (review of 0.13, 18 Nov 2026);
         * any other web address had every guest's phone fetch it, so whoever has the code could learn each guest's
         * address from a server of their own (review 2). Applied as a message is made and as one is read, and given as
         * OkHttp reads it, the reader the pictures are loaded with, so what is loaded is what was looked at.
         */
        fun thumbnailFor(uri: String?): String {
            val url = uri?.trim()?.toHttpUrlOrNull() ?: return ""
            return if (url.isHttps && PICTURE_HOSTS.any { it.matches(url.host) }) url.toString() else ""
        }

        /**
         * [message] as text to seal. The room's own messages carry the key of the phone that sends them, [sender], inside
         * the seal ("k"): see [decodeFrom].
         */
        fun encode(message: TogetherMessage, sender: String? = null): String {
            val o = encodeObject(message)
            return if (sender == null) o.toString() else JsonObject(o + ("k" to JsonPrimitive(sender))).toString()
        }

        private fun encodeObject(message: TogetherMessage): JsonObject = when (message) {
            is State -> buildJsonObject {
                put("t", "state"); put("song", message.songId); put("title", message.title); put("artist", message.artist)
                put("pos", message.positionMs); put("playing", message.playing); put("speed", message.speed); put("at", message.atMs)
                if (message.fromFile) put("file", true)
            }
            is Ping -> buildJsonObject { put("t", "ping"); put("c", message.askedAtMs); put("to", message.to) }
            is Pong -> buildJsonObject { put("t", "pong"); put("c", message.askedAtMs); put("h", message.hostMs); put("to", message.to) }
            is Here -> buildJsonObject {
                put("t", "here"); put("name", message.name); put("owner", message.owner)
                message.settings?.let { put("settings", it.toJson()) }
                if (message.arranges) put("arranges", true)
                message.lead?.let { put("lead", it) }
                message.leadSince?.let { put("since", it) }
            }
            is Suggest -> buildJsonObject {
                put("t", "suggest"); put("song", message.songId); put("title", message.title); put("artist", message.artist)
                put("thumb", thumbnailFor(message.thumbnail)); put("by", message.by)
                if (message.atEnd) put("end", true)
                if (message.batch.isNotBlank() && message.count > 1) {
                    put("batch", message.batch); put("i", message.index); put("n", message.count)
                }
            }
            is Queue -> buildJsonObject {
                put("t", "queue"); put("repeat", message.repeat); put("shuffle", message.shuffle)
                put("songs", songsJson(message.songs))
            }
            is Arrange -> buildJsonObject {
                put("t", "arrange"); put("song", message.songId)
                message.after?.let { put("after", it) }
                if (message.remove) put("remove", true)
            }
            is Propose -> buildJsonObject {
                put("t", "propose"); put("pos", message.positionMs); put("by", message.by)
                put("songs", songsJson(message.songs))
            }
            is NotNow -> buildJsonObject { put("t", "notnow"); put("to", message.to); put("song", message.songId) }
            is Kick -> buildJsonObject { put("t", "kick"); put("to", message.to) }
            is Move -> buildJsonObject { put("t", "move"); put("to", message.to); put("code", message.sealedCode) }
            End -> buildJsonObject { put("t", "end") }
        }

        private fun songsJson(songs: List<Song>) = buildJsonArray {
            songs.forEach { song ->
                addJsonObject { put("id", song.id); put("title", song.title); put("artist", song.artist); put("thumb", thumbnailFor(song.thumbnail)) }
            }
        }

        private fun songsOf(o: JsonObject) = o["songs"]!!.jsonArray.map { item ->
            val song = item.jsonObject
            fun f(key: String) = song[key]!!.jsonPrimitive.content
            Song(f("id"), f("title"), f("artist"), thumbnailFor(f("thumb")))
        }

        fun decode(text: String): TogetherMessage? = runCatching { decodeObject(Json.parseToJsonElement(text) as JsonObject) }.getOrNull()

        /**
         * The message in [text] when the phone whose key is [sender], the event's own signer, sealed it; null for anything
         * else. The seal knows only the room, so anyone who sees the room's sealed words on a public relay could sign one
         * again with a key of their own: its signature is then good and it opens. The key inside names the phone that
         * sealed it, and a copy signed by any other key is refused: a suggestion queued again, a proposal asked again, a
         * stranger listed, or taken for the owner by a guest that had not heard the owner yet (review 2, 18 Nov 2026). A
         * message with no key inside, from a build before this one, is refused too.
         */
        fun decodeFrom(text: String, sender: String): TogetherMessage? = runCatching {
            val o = Json.parseToJsonElement(text) as JsonObject
            if (o["k"]?.jsonPrimitive?.content == sender) decodeObject(o) else null
        }.getOrNull()

        private fun decodeObject(o: JsonObject): TogetherMessage? {
            fun s(key: String) = o[key]!!.jsonPrimitive.content
            fun l(key: String) = o[key]!!.jsonPrimitive.long
            val message = when (s("t")) {
                "state" -> State(
                    s("song"), s("title"), s("artist"), l("pos"), o["playing"]!!.jsonPrimitive.boolean, o["speed"]!!.jsonPrimitive.double, l("at"),
                    fromFile = o["file"]?.jsonPrimitive?.boolean == true,
                )
                "ping" -> Ping(l("c"), s("to"))
                "pong" -> Pong(l("c"), l("h"), s("to"))
                "here" -> Here(s("name"), o["owner"]!!.jsonPrimitive.boolean, o["lead"]?.jsonPrimitive?.content, o["since"]?.jsonPrimitive?.longOrNull)
                "suggest" -> Suggest(
                    s("song"), s("title"), s("artist"), thumbnailFor(s("thumb")), s("by"),
                    atEnd = o["end"]?.jsonPrimitive?.booleanOrNull == true,
                    batch = o["batch"]?.jsonPrimitive?.content.orEmpty().take(16),
                    index = o["i"]?.jsonPrimitive?.intOrNull ?: 0,
                    count = (o["n"]?.jsonPrimitive?.intOrNull ?: 1).coerceIn(1, 64),
                )
                "queue" -> Queue(
                    songs = songsOf(o),
                    repeat = o["repeat"]!!.jsonPrimitive.int,
                    shuffle = o["shuffle"]!!.jsonPrimitive.boolean,
                )
                "arrange" -> Arrange(s("song"), after = o["after"]?.jsonPrimitive?.content, remove = o["remove"]?.jsonPrimitive?.content == "true")
                "propose" -> Propose(songsOf(o), l("pos"), s("by"))
                "notnow" -> NotNow(s("to"), s("song"))
                "kick" -> Kick(s("to"))
                "move" -> Move(s("to"), s("code"))
                "end" -> End
                else -> null
            }
            // The owner's room settings, one more field of its Here (RoomSettings): none from an older build.
            return if (message is Here) message.copy(settings = RoomSettings.of(o["settings"]), arranges = o["arranges"]?.jsonPrimitive?.content == "true") else message
        }
    }
}

/** YouTube's picture servers as the app knows them: i.ytimg.com (i1 to i9 too), lh3 and yt3.googleusercontent.com, yt3.ggpht.com. */
private val PICTURE_HOSTS = listOf(
    Regex("""i\d?\.ytimg\.com"""),
    Regex("""(lh|yt)\d+\.googleusercontent\.com"""),
    Regex("""yt\d+\.ggpht\.com"""),
)

/**
 * The room's sealed words already taken, by their hash, the latest [kept]. A seal is made once (a fresh IV every time),
 * so the same sealed text again is a copy, whatever event carries it. Asked only for a word whose key inside matched its
 * signer (TogetherMessage.decodeFrom): before that, a stranger's copy arriving first could have shut out the real one.
 */
internal class TakenSeals(private val kept: Int = 2_000) {
    private val taken = LinkedHashSet<String>()

    /** True the first time [sealed] is seen. */
    fun take(sealed: String): Boolean {
        if (!taken.add(Secp256k1.sha256(sealed.toByteArray()).toHex())) return false
        if (taken.size > kept) taken.iterator().run { next(); remove() }
        return true
    }
}

/**
 * The gap between the host's clock and this phone's, from pings: the answer that came back fastest
 * says it best, since half its round trip is the most it can be off by.
 */
class ClockGap {
    /** The gap now: the middle of the last [KEPT] answers taken, so that one lopsided trip moves it little. */
    var offsetMs = 0L
        private set
    var bestRoundTripMs = Long.MAX_VALUE
        private set
    val known: Boolean get() = bestRoundTripMs != Long.MAX_VALUE
    private val taken = ArrayDeque<Long>()

    /** A pong for a ping sent at [askedAtMs], answered at [hostMs], back at [nowMs]. */
    fun heard(askedAtMs: Long, hostMs: Long, nowMs: Long) {
        val roundTrip = nowMs - askedAtMs
        // The first answer is always taken: "none yet" plus the slack would overflow.
        if (roundTrip !in 0..10_000 || (known && roundTrip > bestRoundTripMs + SLACK_MS)) return
        if (roundTrip <= bestRoundTripMs) bestRoundTripMs = roundTrip
        taken.addLast(hostMs - (askedAtMs + nowMs) / 2)
        if (taken.size > KEPT) taken.removeFirst()
        val sorted = taken.sorted()
        offsetMs = sorted[sorted.size / 2]
    }

    /** The host's clock now, by this phone's. */
    fun hostNow(nowMs: Long) = nowMs + offsetMs

    companion object {
        private const val SLACK_MS = 50L
        /** Answers kept for the middle: five, the first ten of them a second apart. */
        private const val KEPT = 5
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

        /**
         * Where the host is now by its last [state], heard at [heardAtMs], before the gap between
         * the clocks is known, all by this phone's clock: by the host's clock as if the two agreed
         * when its word took a believable time to come ([TRIP_MS] at most), else by when the word
         * came, which is behind by its trip alone. Taken as agreeing whatever they said, a phone
         * whose clock was off went to a spot off by as much (14 s on an emulator).
         */
        fun guess(state: TogetherMessage.State, heardAtMs: Long, nowMs: Long): Long = when {
            heardAtMs - state.atMs in 0..TRIP_MS -> target(state, nowMs)
            state.playing -> state.positionMs + ((nowMs - heardAtMs) * state.speed).toLong()
            else -> state.positionMs
        }

        /** The longest a word of the host's is believed to take to come, through the relays. */
        const val TRIP_MS = 2_000L

        /**
         * Where a guest whose song is still loading at [positionMs] goes, or null to let it load
         * there: the host's spot [targetMs] when that is more than [DRIFT_MS] on, unless it lies at
         * or past the song's end [durationMs] (null while not known), where the host is moving on.
         */
        fun arrival(targetMs: Long, positionMs: Long, durationMs: Long?): Long? = when {
            targetMs - positionMs <= DRIFT_MS -> null
            durationMs != null && targetMs >= durationMs -> null
            else -> targetMs
        }

        /**
         * Where a guest whose player has ended on the host's song goes, or null to wait there: the
         * host's spot [targetMs], unless it lies within [END_GAP_MS] of the song's end [durationMs]
         * (null while not known), where the host is about to move on and its word for the next song
         * is on the way. An ended player never plays again by itself: a guest that rejoined after
         * playing the song to its end stayed silent under "Following the host" (19 Nov 2026).
         */
        fun wake(targetMs: Long, durationMs: Long?): Long? = when {
            durationMs != null && targetMs > durationMs - END_GAP_MS -> null
            else -> targetMs.coerceAtLeast(0)
        }

        /** How near its end the host's spot leaves a guest whose player has ended waiting for the next song. */
        const val END_GAP_MS = 3_000L

        /**
         * Whether a guest too far from the host to nudge, playing at [localMs] with the song come
         * in up to [bufferedMs], jumps to the host's spot [targetMs] now: back, or into what has
         * come in, at once; past it, only once that is more than [WAIT_FOR_DATA_MS] away. A jump
         * past what has come in asks for the song again from there, and on a slow line it landed
         * as far behind as that took and jumped again, for good: a guest on a 32 KB/s line was
         * never in step in 75 s, silent most of it (28 Oct 2026). Until then it plays on, behind,
         * while what has come in catches up with the host.
         */
        fun jumpNow(targetMs: Long, localMs: Long, bufferedMs: Long): Boolean =
            targetMs < localMs || targetMs + LANDING_MS <= bufferedMs || targetMs - bufferedMs > WAIT_FOR_DATA_MS

        /** Room to play on after a jump into what has come in, so that it does not land on its edge. */
        const val LANDING_MS = 500L

        /** How far past what has come in a guest still waits for it, rather than asking for the song there. */
        const val WAIT_FOR_DATA_MS = 10_000L

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

/**
 * A guest that casts (his pick of 20 Nov 2026 for 0.13, "the small step"). The room's corrections are made for the
 * phone's own player, which a cast empties: they put each of the host's songs on the receiver from its start, and
 * fought the cast all the while (review of 0.13, 18 Nov 2026). So while a cast is on, a guest follows nothing, as one
 * that went its own way. One that went its own way for the cast alone catches up by itself once the cast ends; one that
 * had gone its own way before stays on it. Catch up asked for during a cast waits for its end. The one who leads is
 * left as it was: its side of a cast comes later.
 */
class GuestCast {
    enum class Step {
        NONE,

        /** The cast has begun: on its own way until it ends. */
        OWN_WAY,

        /** The cast it went its own way for has ended: back with the room, as Catch up brings it. */
        CATCH_UP,
    }

    /** On its own way for the cast alone: it catches up by itself when the cast ends. */
    var catchUpAfter = false
        private set

    /** A look of the room's: this phone [casting] or not, [following] someone or leading, on its [ownWay] or not. */
    fun look(casting: Boolean, following: Boolean, ownWay: Boolean): Step = when {
        // The one who leads follows nobody, and has nothing to catch up with.
        !following -> {
            catchUpAfter = false
            Step.NONE
        }
        casting && !ownWay -> {
            catchUpAfter = true
            Step.OWN_WAY
        }
        !casting && catchUpAfter -> {
            catchUpAfter = false
            Step.CATCH_UP
        }
        else -> Step.NONE
    }

    /** Catch up, asked for while this phone is [casting] or not: true to catch up now, false to wait for the cast's end. */
    fun catchUpNow(casting: Boolean): Boolean {
        catchUpAfter = casting
        return !casting
    }
}

/**
 * What opening an invite does when this phone may be in that room already (his find of 15 Nov
 * 2026: the host opened its own link and was two in the room). The same room is opened on its
 * screen; any other is asked about, as before.
 */
object InviteAnswer {
    enum class Answer { ASK, OPEN_ROOM }

    fun forInvite(invite: RoomCode, current: RoomCode?): Answer =
        if (current != null && current == invite) Answer.OPEN_ROOM else Answer.ASK
}

/**
 * The host's song, which this phone cannot play (issue #187: YouTube refused it to a guest on every
 * client). The guest stays on it, not playing, with a line saying so, and picks up again at the
 * host's next song. One more try a while after the first failure (a stream can come back), none
 * after the second, so that nothing is asked every second, and no skipping away: the room only
 * sent it back.
 */
class HostSongRefusal {
    /** The host's song this phone failed on, null when it plays or has not failed. */
    var refusedId: String? = null
        private set
    private var hostId: String? = null
    private var failures = 0
    private var firstFailedAt = 0L

    /** The host's song is [id] now: a different one than before starts afresh. */
    fun songIs(id: String) {
        if (id == hostId) return
        hostId = id
        refusedId = null
        failures = 0
    }

    /** The player failed on [id]. Only the host's song counts. */
    fun failed(id: String, nowMs: Long) {
        if (id != hostId) return
        refusedId = id
        failures++
        if (failures == 1) firstFailedAt = nowMs
    }

    /** The song played after all: the next failure is a first one again. */
    fun played() {
        refusedId = null
        failures = 0
    }

    /** Whether the player is to stay on [id], failed and left alone: it is the host's and it failed. */
    fun staysOn(id: String): Boolean = id == hostId && refusedId == id

    /** Whether the one more try is due: once, [RETRY_AFTER_MS] after the first failure. */
    fun retryDue(id: String, nowMs: Long): Boolean =
        staysOn(id) && failures == 1 && nowMs - firstFailedAt >= RETRY_AFTER_MS

    companion object {
        const val RETRY_AFTER_MS = 20_000L
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
 * Where the host is now, from its last few words rather than from the last one. Each state is a
 * reading of the host's player (its position wobbles by tens of ms, a word comes late by whatever
 * the relays took), and the guest that aimed at each new one in turn sped up and slowed down
 * every two seconds for nothing: on 26 Oct 2026 through public relays its speed was a different
 * one at every look, 0.96 to 1.03, a flush of the audio chain each time. Playing at one speed,
 * every state says the same line, "position minus the host's clock"; the middle of the last
 * [KEPT] of them is that line, and one odd word barely moves it. A word with an older clock than
 * the newest is a late one and is ignored. A change of song, of play or pause, of speed, or a
 * jump past [JUMP_MS] is the host's doing, and starts the line again.
 */
class HostLine {
    private val lines = ArrayDeque<Double>()
    private var newest: TogetherMessage.State? = null

    /** Takes [state]; false when it is older than the newest one and was ignored. */
    fun hear(state: TogetherMessage.State): Boolean {
        val last = newest
        if (last != null && state.atMs < last.atMs) return false
        val line = intercept(state)
        val changed = last == null || last.songId != state.songId || last.playing != state.playing ||
            last.speed != state.speed || (lines.isNotEmpty() && abs(line - middle()) > JUMP_MS)
        if (changed) lines.clear()
        lines.addLast(line)
        if (lines.size > KEPT) lines.removeFirst()
        newest = state
        return true
    }

    fun clear() {
        lines.clear()
        newest = null
    }

    /** Where the host is at its clock [hostNowMs], by [state] (the newest heard) and those before it. */
    fun target(state: TogetherMessage.State, hostNowMs: Long): Long =
        if (!state.playing || lines.isEmpty() || state !== newest) Follow.target(state, hostNowMs)
        else (middle() + hostNowMs * state.speed).toLong()

    private fun intercept(state: TogetherMessage.State) = state.positionMs - state.atMs * state.speed

    /** The mean of what lies between the lowest and the highest, once there are three or more. */
    private fun middle(): Double {
        val sorted = lines.sorted()
        return if (sorted.size >= 3) sorted.subList(1, sorted.size - 1).average() else sorted.average()
    }

    companion object {
        const val KEPT = 7
        const val JUMP_MS = 300.0
    }
}

/**
 * Decides when a follower corrects, and keeps corrections rare (his word of 14 Nov 2026: "the song
 * progress syncing between clients is too aggressive"). Every change of speed drains and flushes
 * the audio chain (DefaultAudioSink.setupAudioProcessors), which resets the gain, the equalizer
 * and the spatial stages and cuts the stretcher's buffer: a click each time. So:
 *
 *  - It acts on the middle of the last [LOOKS] readings, never on one. A reading is the host's
 *    word and the guest's player, and each wobbles.
 *  - In step within [toleranceMs], it plays at the host's speed and does nothing.
 *  - Past it, it sets one speed that closes the gap in about [CLOSE_MS], held for exactly the
 *    time that takes, then back to the host's speed: two changes, never a chase. The readings
 *    are taken again from nothing after it, [AFTER_MS] later.
 *  - Past [SEEK_MS], two looks running, it jumps, and not again within [SEEK_AGAIN_MS] unless
 *    the gap is past [FAR_MS]; until then it nudges.
 */
class SyncLock(
    var toleranceMs: Long = DEFAULT_TOLERANCE_MS,
    var maxNudge: Double = Follow.MAX_NUDGE,
) {
    /** In step: the speed is the host's and the readings are inside the tolerance. */
    var locked = false
        private set
    private val recent = ArrayDeque<Long>()
    private var correctUntil = 0L
    private var correctSpeed = 1.0
    private var holdOffUntil = 0L
    private var farLooks = 0
    private var lastSeekAt = -SEEK_AGAIN_MS

    fun decide(targetMs: Long, localMs: Long, hostSpeed: Double, nowMs: Long = System.currentTimeMillis()): Follow {
        val drift = targetMs - localMs
        // Far off: a jump, once the look after says so as well.
        if (abs(drift) > SEEK_MS) {
            farLooks++
            val again = nowMs - lastSeekAt < SEEK_AGAIN_MS && abs(drift) <= FAR_MS
            if (farLooks >= 2 && !again) return Follow.Seek(targetMs)
        } else {
            farLooks = 0
        }
        // Mid-correction: the speed is held for the time it takes, whatever the readings say.
        if (correctUntil != 0L) {
            if (nowMs < correctUntil) return Follow.Nudge(correctSpeed)
            correctUntil = 0L
            recent.clear()
            holdOffUntil = nowMs + AFTER_MS
            return Follow.Steady(hostSpeed)
        }
        recent.addLast(drift)
        if (recent.size > LOOKS) recent.removeFirst()
        if (nowMs < holdOffUntil || recent.size < MIN_LOOKS) return Follow.Steady(hostSpeed)
        val estimate = middle(recent)
        if (abs(estimate) <= toleranceMs && abs(drift) <= SEEK_MS) {
            locked = true
            return Follow.Steady(hostSpeed)
        }
        locked = false
        val rate = (estimate / CLOSE_MS).coerceIn(-maxNudge, maxNudge)
        val duration = (abs(estimate) / abs(rate)).toLong().coerceAtLeast(MIN_HOLD_MS)
        correctUntil = nowMs + duration
        correctSpeed = hostSpeed * (1 + rate)
        return Follow.Nudge(correctSpeed)
    }

    /** After another song, another leader or a pause: the lock is earned again. */
    fun reset() {
        locked = false
        recent.clear()
        correctUntil = 0L
        holdOffUntil = 0L
        farLooks = 0
    }

    /** After a jump of this phone's own at [nowMs]. */
    fun sought(nowMs: Long = System.currentTimeMillis()) {
        reset()
        lastSeekAt = nowMs
    }

    /** The middle of [readings]: of two middles, their mean. */
    private fun middle(readings: Collection<Long>): Double {
        val sorted = readings.sorted()
        val half = sorted.size / 2
        return if (sorted.size % 2 == 1) sorted[half].toDouble() else (sorted[half - 1] + sorted[half]) / 2.0
    }

    companion object {
        /** Two seconds of readings, a look every quarter of a second. */
        const val LOOKS = 8
        /** Before this many it does not act: a second and a half. */
        const val MIN_LOOKS = 6
        /** The gap is closed in about this long, if the cap on the speed allows. */
        const val CLOSE_MS = 3_000.0
        const val MIN_HOLD_MS = 1_000L
        /** After a correction: time for the song to show where it landed before the readings count. */
        const val AFTER_MS = 3_000L
        /** Past this a jump, a nudge of 5% taking 12 s to close it. */
        const val SEEK_MS = 600L
        const val SEEK_AGAIN_MS = 5_000L
        const val FAR_MS = 5_000L
        /** About what two phones in one room cannot tell apart. */
        const val DEFAULT_TOLERANCE_MS = 60L
        /** Above [Follow.IN_STEP_MS], or it would lock and unlock on the same readings. */
        const val MIN_TOLERANCE_MS = 30L
        const val MAX_TOLERANCE_MS = 200L
    }
}
