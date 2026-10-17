/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.together

import android.util.Log
import android.widget.Toast
import androidx.annotation.StringRes
import androidx.media3.common.C
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import com.dd3boh.outertune.R
import com.dd3boh.outertune.constants.PlayOrigin
import com.dd3boh.outertune.constants.TogetherCatchUpKey
import com.dd3boh.outertune.constants.TogetherDelayKey
import com.dd3boh.outertune.constants.TogetherToleranceKey
import com.dd3boh.outertune.extensions.toMediaItem
import com.dd3boh.outertune.models.MediaMetadata
import com.dd3boh.outertune.playback.MusicService
import com.dd3boh.outertune.playback.queues.ListQueue
import com.dd3boh.outertune.utils.dataStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.OkHttpClient
import kotlin.math.abs

/**
 * A listening room while it lasts, held by the [MusicService] (his yes of 17 Oct 2026 for 0.13).
 *
 * The leader says where it is in which song, on every change and every couple of seconds, and
 * what its queue plays next. Everyone else plays the same song from YouTube itself, with the
 * leader's queue as its own, measures the gap to the leader's clock from pings, and every quarter
 * of a second checks how far off it is: a little, and it plays a touch faster or slower until it
 * closes; a lot, and it jumps. Once in step it locks and lets small wobbles be (SyncLock). A
 * follower may pause; play puts it back with the leader. Messages are sealed with the room code's
 * key and signed with a key made for this session alone, then sent through Nostr relays
 * (RelayPool).
 *
 * The owner made the room. It leads until it hands the lead to someone (his word of 17 Oct: "the
 * original host stays host as in they can switch back and close the room"), and it alone can take
 * the lead back, close the room or send someone out. Sending someone out moves everyone else to a
 * new code, each told it alone (PairSeal), so the code the sent-out phone knows leads nowhere.
 */
class TogetherSession(
    private val service: MusicService,
    val code: RoomCode,
    /** This phone made the room. */
    val owner: Boolean,
    val relays: List<String>,
    val name: String,
) {
    data class Status(
        val relaysConnected: Int = 0,
        val others: Int = 0,
        /** A follower's last gap to the leader, in ms: positive behind, negative ahead. Null before it knows. */
        val driftMs: Long? = null,
        /** The leader's song, "title · artist". */
        val hostSong: String? = null,
        /** The id of the song the leader plays, to tell whether this phone plays the same one. */
        val hostSongId: String? = null,
        /** A follower before the leader's first word. */
        val waitingForHost: Boolean = true,
        /** The follower paused, picked another song or seeked: it plays on its own until [catchUp]. */
        val ownWay: Boolean = false,
        /** The leader's last song from someone else, "title, by whom". */
        val lastSuggestion: String? = null,
        /** The others heard in the room lately, the owner first. */
        val members: List<Member> = emptyList(),
        /** Whether this phone leads. */
        val leading: Boolean = false,
        /** Who leads, by name, when it is someone else. */
        val leaderName: String? = null,
        /** A follower in step and locked: it leaves the speed alone within [toleranceMs]. */
        val locked: Boolean = false,
        val toleranceMs: Long = SyncLock.DEFAULT_TOLERANCE_MS,
        /** A follower paused by its listener: the room plays on, and play brings it back. */
        val pausedHere: Boolean = false,
    )

    /** Someone else in the room: [key] is their key for this session, the name their phone's. */
    data class Member(val key: String, val name: String, val owner: Boolean, val leader: Boolean) {
        val id: String get() = key.take(8)
    }

    /** How many relays the room was asked to use; [Status.relaysConnected] says how many answer. */
    val relayCount: Int get() = relays.size

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val secret = Secp256k1.newSecretKey()
    private val me = Secp256k1.publicKey(secret).toHex()
    private val pool = RelayPool(
        urls = relays,
        filter = buildJsonObject {
            put("kinds", JsonArray(listOf(JsonPrimitive(TogetherMessage.KIND))))
            put("#t", JsonArray(listOf(JsonPrimitive(code.tag))))
            put("since", System.currentTimeMillis() / 1000 - 30)
        },
        client = OkHttpClient(),
        scope = scope,
    )
    private var gap = ClockGap()
    /** Who made the room: this phone, or the first that said so in its Here. */
    private var ownerKey: String? = if (owner) me else null
    /** Who everyone follows: the owner's word, or before it, whoever sends the playback. */
    private var leaderKey: String? = if (owner) me else null
    private val leading: Boolean get() = leaderKey == me
    private var lastState: TogetherMessage.State? = null
    private var roomQueue: TogetherMessage.Queue? = null
    private var settleUntil = 0L
    private var lastSentState = 0L
    private var pingsToLeader = 0
    private val heard = mutableMapOf<String, Long>()
    /** What each one said of themselves in their Here: their name and whether they made the room. */
    private val said = mutableMapOf<String, Pair<String, Boolean>>()
    /** Whom the owner has answered at once, so a newcomer learns who leads without waiting. */
    private val greeted = mutableSetOf<String>()
    /** Sent out by the owner: their messages are not read. */
    private val sentOut = mutableSetOf<String>()
    private val speedBefore = service.exoPlayer.playbackParameters.speed
    private val repeatBefore = service.exoPlayer.repeatMode
    private var repeatTaken = false
    private var queueJob: Job? = null
    private val lock = SyncLock()
    /** How late this phone's sound comes out (Settings > Listen together). */
    private var delayMs = 0L
    private var pausedHere = false
    /** The room's queue this phone's own was last made to match, so it is compared once per word. */
    private var matchedQueue: TogetherMessage.Queue? = null
    private var currentId: String? = service.exoPlayer.currentMediaItem?.mediaId
    /** The song this phone moved on from by itself at its end, and when. */
    private var autoAdvancedFrom: String? = null
    private var autoAdvancedAt = 0L
    /** The leader's song this phone waits for the leader's queue for, and since when. */
    private var waitingFor: String? = null
    private var waitingSince = 0L

    private val _status = MutableStateFlow(Status(waitingForHost = !owner, leading = owner))
    val status: StateFlow<Status> = _status

    /** Whether this phone follows someone now: its player's repeat mode is then the room's, not its own choice. */
    val following: Boolean get() = !leading

    private val listener = object : Player.Listener {
        // A follower's own pause, song or seek, as against the session's own commands (mine()).
        override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
            if (!leading) listenerPlayed(playWhenReady)
        }

        override fun onMediaItemTransition(mediaItem: androidx.media3.common.MediaItem?, reason: Int) {
            val from = currentId
            currentId = mediaItem?.mediaId
            if (leading) return
            if (reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO) {
                // On to the next song by itself, as the leader does at the same moment; the
                // leader's word that it did comes a moment later.
                autoAdvancedFrom = from
                autoAdvancedAt = System.currentTimeMillis()
            } else if (reason != Player.MEDIA_ITEM_TRANSITION_REASON_REPEAT) {
                listenerChose("another song")
            }
        }

        override fun onPositionDiscontinuity(oldPosition: Player.PositionInfo, newPosition: Player.PositionInfo, reason: Int) {
            if (!leading && reason == Player.DISCONTINUITY_REASON_SEEK) listenerChose("a seek")
        }

        override fun onEvents(player: Player, events: Player.Events) {
            if (!leading) return
            if (events.containsAny(
                    Player.EVENT_IS_PLAYING_CHANGED, Player.EVENT_MEDIA_ITEM_TRANSITION,
                    Player.EVENT_POSITION_DISCONTINUITY, Player.EVENT_PLAYBACK_PARAMETERS_CHANGED,
                )
            ) sendState()
            if (events.containsAny(
                    Player.EVENT_TIMELINE_CHANGED, Player.EVENT_MEDIA_ITEM_TRANSITION,
                    Player.EVENT_REPEAT_MODE_CHANGED, Player.EVENT_SHUFFLE_MODE_ENABLED_CHANGED,
                )
            ) queueSoon()
        }
    }

    fun start() {
        Log.i(TAG, "Room ${code.pretty}: ${if (owner) "made here" else "joining"} through ${relays.size} relays")
        pool.start()
        service.exoPlayer.addListener(listener)
        scope.launch { pool.connected.collect { n -> _status.value = _status.value.copy(relaysConnected = n) } }
        scope.launch { pool.events.collect { onEvent(it) } }
        // Settings > Listen together, taken as they change, a room under way included.
        scope.launch {
            service.dataStore.data.collect { prefs ->
                val tuning = TogetherTuning.of(prefs[TogetherToleranceKey], prefs[TogetherCatchUpKey], prefs[TogetherDelayKey])
                lock.toleranceMs = tuning.toleranceMs
                lock.maxNudge = tuning.maxNudge
                delayMs = tuning.delayMs
                _status.value = _status.value.copy(toleranceMs = tuning.toleranceMs)
            }
        }
        scope.launch {
            while (isActive) {
                sayHere()
                delay(TogetherMessage.HERE_MS)
            }
        }
        // The leader's beat: where it is every couple of seconds, and its queue now and then.
        scope.launch {
            var beats = 0
            while (isActive) {
                if (leading) {
                    if (System.currentTimeMillis() - lastSentState >= TogetherMessage.HEARTBEAT_MS - 50) sendState()
                    if (++beats % QUEUE_EVERY_BEATS == 0) sendQueue()
                }
                delay(TogetherMessage.HEARTBEAT_MS)
            }
        }
        // A follower's pings: every second at first, so the clock gap is known within moments.
        scope.launch {
            while (isActive) {
                val to = leaderKey
                if (!leading && to != null) {
                    send(TogetherMessage.Ping(System.currentTimeMillis(), to))
                    pingsToLeader++
                }
                delay(if (pingsToLeader < 10) 1_000L else 15_000L)
            }
        }
        scope.launch {
            while (isActive) {
                if (!leading) follow()
                delay(SYNC_TICK_MS)
            }
        }
        publish()
    }

    fun stop() {
        service.exoPlayer.removeListener(listener)
        speed(service.exoPlayer, speedBefore.toDouble())
        if (repeatTaken) service.exoPlayer.repeatMode = repeatBefore
        pool.stop()
        scope.cancel()
        Log.i(TAG, "Left room ${code.pretty}")
    }

    private fun sayHere() = send(TogetherMessage.Here(name, owner, lead = if (owner) leaderKey else null))

    private fun sendState() {
        val player = service.exoPlayer
        val item = player.currentMediaItem ?: return
        lastSentState = System.currentTimeMillis()
        val metadata = item.mediaMetadata
        send(
            TogetherMessage.State(
                songId = item.mediaId,
                title = metadata.title?.toString().orEmpty(),
                artist = metadata.artist?.toString().orEmpty(),
                // Where its sound is, not its player: a leader on Bluetooth is that much behind
                // what its player says, and the followers aim at what is heard.
                positionMs = player.currentPosition - delayMs,
                playing = player.isPlaying || (player.playWhenReady && player.playbackState == Player.STATE_BUFFERING),
                speed = player.playbackParameters.speed.toDouble(),
                atMs = lastSentState,
            )
        )
    }

    /** The queue goes out once the player is done changing it: autoplay adds songs a few at a time. */
    private fun queueSoon() {
        publish()
        queueJob?.cancel()
        queueJob = scope.launch {
            delay(QUEUE_SETTLE_MS)
            sendQueue()
        }
    }

    private fun sendQueue() {
        val player = service.exoPlayer
        send(TogetherMessage.Queue(ownQueue(), player.repeatMode, player.shuffleModeEnabled))
    }

    /** This phone's queue from the song playing on, in the order it plays. */
    private fun ownQueue(): List<TogetherMessage.Song> {
        val player = service.exoPlayer
        val timeline = player.currentTimeline
        if (timeline.isEmpty) return emptyList()
        val window = Timeline.Window()
        val songs = mutableListOf<TogetherMessage.Song>()
        var index = player.currentMediaItemIndex
        while (index != C.INDEX_UNSET && songs.size < TogetherMessage.QUEUE_SONGS) {
            val item = timeline.getWindow(index, window).mediaItem
            val metadata = item.mediaMetadata
            songs += TogetherMessage.Song(
                id = item.mediaId,
                title = metadata.title?.toString().orEmpty(),
                artist = metadata.artist?.toString().orEmpty(),
                thumbnail = metadata.artworkUri?.toString().orEmpty(),
            )
            index = timeline.getNextWindowIndex(index, Player.REPEAT_MODE_OFF, player.shuffleModeEnabled)
        }
        return songs
    }

    private fun send(message: TogetherMessage) {
        scope.launch {
            val event = withContext(Dispatchers.Default) {
                NostrEvent.signed(secret, TogetherMessage.KIND, listOf(listOf("t", code.tag)), code.seal(TogetherMessage.encode(message)))
            }
            pool.publish(event)
        }
    }

    private fun onEvent(event: NostrEvent) {
        val from = event.pubkey
        if (from == me || event.kind != TogetherMessage.KIND || from in sentOut) return
        val message = code.open(event.content)?.let(TogetherMessage::decode) ?: return
        val now = System.currentTimeMillis()
        // A newcomer gets the leader's queue at once rather than at its next round.
        if (from !in heard && leading) queueSoon()
        heard[from] = now
        heard.entries.removeAll { now - it.value > MEMBER_TIMEOUT_MS }
        if (message is TogetherMessage.Here) {
            said[from] = message.name to message.owner
            if (message.owner && ownerKey == null) {
                ownerKey = from
                Log.i(TAG, "The room was made by ${from.take(8)}")
            }
            if (from == ownerKey && message.lead != null && message.lead != leaderKey) lead(message.lead)
            // A newcomer hears at once who leads, instead of at the owner's next Here.
            if (owner && greeted.add(from)) sayHere()
        }
        said.keys.retainAll(heard.keys)
        when (message) {
            is TogetherMessage.Ping -> if (leading && message.to == me) {
                send(TogetherMessage.Pong(message.askedAtMs, System.currentTimeMillis(), from))
            }
            is TogetherMessage.Pong -> if (!leading && message.to == me && from == leaderKey) {
                gap.heard(message.askedAtMs, message.hostMs, System.currentTimeMillis())
            }
            is TogetherMessage.State -> if (!leading) {
                // Before the owner's word on who leads, whoever sends the playback does.
                if (leaderKey == null) lead(from)
                if (from == leaderKey) {
                    lastState = message
                    lastStateAt = now
                    _status.value = _status.value.copy(
                        hostSong = listOf(message.title.ifBlank { message.songId }, message.artist).filter { it.isNotBlank() }.joinToString(" · "),
                        hostSongId = message.songId,
                        waitingForHost = false,
                    )
                }
            }
            is TogetherMessage.Queue -> if (!leading && from == leaderKey) {
                roomQueue = message
                if (!ownWay) {
                    takeRepeat(message.repeat)
                    matchQueue()
                }
            }
            is TogetherMessage.Suggest -> if (leading) {
                val song = MediaMetadata(
                    id = message.songId,
                    title = message.title,
                    artists = listOf(MediaMetadata.Artist(id = null, name = message.artist)),
                    duration = -1,
                    thumbnailUrl = message.thumbnail.ifBlank { null },
                    genre = null,
                )
                service.enqueueNext(listOf(song.toMediaItem()))
                Log.i(TAG, "${message.by} suggested ${message.songId}: next in the queue")
                _status.value = _status.value.copy(lastSuggestion = "${message.title}, ${message.by}")
                Toast.makeText(service, service.getString(R.string.together_from_guest, message.by, message.title), Toast.LENGTH_LONG).show()
            }
            is TogetherMessage.Kick -> if (from == ownerKey && message.to == me) {
                Log.i(TAG, "Sent out of the room by its owner")
                closed(R.string.together_kicked)
                return
            }
            is TogetherMessage.Move -> if (from == ownerKey && message.to == me) {
                val next = PairSeal.open(secret, from, message.sealedCode)?.let(RoomCode::parse)
                if (next != null && next != code) {
                    Log.i(TAG, "The owner moved the room to a new code")
                    service.moveTogether(next)
                    return
                }
            }
            TogetherMessage.End -> if (from == ownerKey) {
                Log.i(TAG, "The owner closed the room")
                closed(R.string.together_closed)
                return
            }
            is TogetherMessage.Here -> Unit
        }
        publish()
    }

    /** Who leads now is the phone with [key]: everyone follows it from here on, this phone included unless it is the one. */
    private fun lead(key: String) {
        if (key == leaderKey && lastState != null) return
        Log.i(TAG, "The lead is with ${if (key == me) "this phone" else key.take(8)}")
        leaderKey = key
        gap = ClockGap()
        pingsToLeader = 0
        lastState = null
        loadingSong = null
        matchedQueue = null
        lock.reset()
        if (key == me) {
            // Its own playing is the room's now: no more nudging, and its own repeat mode.
            ownWay = false
            pausedHere = false
            speed(service.exoPlayer, speedBefore.toDouble())
            _status.value = _status.value.copy(driftMs = null, ownWay = false, waitingForHost = false, locked = false, pausedHere = false)
            sendState()
            sendQueue()
        } else {
            _status.value = _status.value.copy(driftMs = null, waitingForHost = true, locked = false)
        }
        publish()
    }

    private fun publish() {
        val members = heard.keys.map { key ->
            val (name, saidOwner) = said[key] ?: ("" to false)
            Member(key, name, owner = saidOwner || key == ownerKey, leader = key == leaderKey)
        }.sortedWith(compareByDescending<Member> { it.owner }.thenByDescending { it.leader })
        _status.value = _status.value.copy(
            others = heard.size,
            members = members,
            leading = leading,
            leaderName = leaderKey?.takeIf { it != me }?.let { said[it]?.first }?.ifBlank { null },
        )
    }

    /** The owner hands the lead to [member]; it stays the owner. */
    fun handOver(member: Member) {
        if (!owner || member.key == me || member.key !in heard) return
        lead(member.key)
        sayHere()
    }

    /** The owner takes the lead back. */
    fun takeBack() {
        if (!owner || leading) return
        lead(me)
        sayHere()
    }

    /**
     * The owner sends [member] out: their phone leaves, and everyone else moves to a new code that
     * each is told alone, so the old one leads nowhere. The lead comes back to the owner with it.
     */
    fun kick(member: Member) {
        if (!owner || member.key == me) return
        sentOut += member.key
        send(TogetherMessage.Kick(member.key))
        val next = RoomCode.generate()
        heard.keys.filter { it != member.key && it !in sentOut }.forEach { key ->
            PairSeal.seal(secret, key, next.raw)?.let { send(TogetherMessage.Move(key, it)) }
        }
        Log.i(TAG, "Sent ${member.id} out; the room moves to a new code")
        Toast.makeText(service, service.getString(R.string.together_removed, member.name.ifBlank { member.id }), Toast.LENGTH_LONG).show()
        // Long enough for the messages to leave before this session closes with the old code.
        scope.launch {
            delay(MOVE_AFTER_MS)
            service.moveTogether(next)
        }
    }

    /** Leave; the owner's leaving closes the room for everyone. */
    fun leave() {
        if (!owner) {
            service.stopTogether()
            return
        }
        send(TogetherMessage.End)
        scope.launch {
            delay(END_AFTER_MS)
            service.stopTogether()
        }
    }

    private fun closed(@StringRes why: Int) {
        Toast.makeText(service, why, Toast.LENGTH_LONG).show()
        service.stopTogether()
    }

    /** A follower's song for the leader to play next. */
    fun suggest(song: MediaMetadata) {
        send(TogetherMessage.Suggest(song.id, song.title, song.artists.joinToString { it.name }, song.thumbnailUrl.orEmpty(), name))
        Log.i(TAG, "Suggested ${song.id} to the room")
    }

    /** Back with the leader after going one's own way: the next look puts the leader's song and place back. */
    fun catchUp() {
        ownWay = false
        pausedHere = false
        matchedQueue = null
        lock.reset()
        roomQueue?.let { takeRepeat(it.repeat) }
        _status.value = _status.value.copy(ownWay = false, pausedHere = false, locked = false)
        Log.i(TAG, "Catching up with the leader")
    }

    /**
     * The listener did something to the player that the room did not: from then on this phone
     * plays on its own, the leader's music no longer forced on it, until Catch up. Not a pause:
     * see [listenerPlayed].
     */
    private fun listenerChose(what: String) {
        if (lastState == null || ownWay || System.currentTimeMillis() < ownCommandUntil) return
        ownWay = true
        pausedHere = false
        lock.reset()
        speed(service.exoPlayer, speedBefore.toDouble())
        _status.value = _status.value.copy(ownWay = true, driftMs = null, locked = false, pausedHere = false)
        Log.i(TAG, "The listener chose $what: playing on their own until Catch up")
    }

    /**
     * A pause or a play on a follower. A pause is the listener's to make (his word of 17 Oct 2026:
     * "pausing still should exist on the client side"): the room plays on without this phone, and
     * play puts it back where the leader is by then. Whose it is comes from the room's state, see
     * [PauseHere]. It used to count as going one's own way, so play went on from the old spot and
     * stayed there until Catch up.
     */
    private fun listenerPlayed(playWhenReady: Boolean) {
        val room = lastState ?: return
        if (ownWay) return
        pausedHere = PauseHere.after(pausedHere, playWhenReady, room.playing) ?: return
        lock.reset()
        if (pausedHere) speed(service.exoPlayer, speedBefore.toDouble())
        _status.value = _status.value.copy(pausedHere = pausedHere, driftMs = null, locked = false)
        Log.i(TAG, if (pausedHere) "Paused here; the room plays on" else "Playing again: back to where the leader is")
    }

    /** What the session does to the player next is its own, not the listener's, for [forMs]. */
    private fun mine(forMs: Long = OWN_COMMAND_MS) {
        ownCommandUntil = System.currentTimeMillis() + forMs
    }

    /** The leader's repeat mode on this phone's player too, so that a song repeats here as it does there. */
    private fun takeRepeat(mode: Int) {
        val player = service.exoPlayer
        if (player.repeatMode == mode) return
        repeatTaken = true
        player.repeatMode = mode
        Log.i(TAG, "Repeat mode from the room: $mode")
    }

    /** A follower's quarter-second look: the leader's song, playing or not, and where in it. */
    private fun follow() {
        val state = lastState ?: return
        // A leader silent this long has left: the follower plays on by itself and says so.
        if (System.currentTimeMillis() - lastStateAt > HOST_TIMEOUT_MS) {
            // The owner takes back a lead it handed to a phone that has gone; anyone else waits
            // for whoever leads next.
            if (owner) {
                Log.i(TAG, "No word from the leader for ${HOST_TIMEOUT_MS / 1000} s: the owner leads again")
                lead(me)
                sayHere()
                return
            }
            Log.i(TAG, "No word from the leader for ${HOST_TIMEOUT_MS / 1000} s: playing on alone")
            lastState = null
            leaderKey = null
            speed(service.exoPlayer, speedBefore.toDouble())
            _status.value = _status.value.copy(waitingForHost = true, driftMs = null)
            return
        }
        val player = service.exoPlayer
        // Marked paused here while its player plays: the play was missed, and it is back with the room.
        if (pausedHere && player.playWhenReady) listenerPlayed(true)
        // Paused here by the listener: the room plays on, and play brings this phone back to it.
        if (ownWay || pausedHere) return
        val now = System.currentTimeMillis()
        if (player.currentMediaItem?.mediaId != state.songId) {
            toLeadersSong(player, state, now)
            return
        }
        loadingSong = null
        waitingFor = null
        if (player.playbackState != Player.STATE_READY && player.playbackState != Player.STATE_BUFFERING) return
        if (roomQueue !== matchedQueue) matchQueue()
        if (player.playWhenReady != state.playing) {
            mine()
            player.playWhenReady = state.playing
        }
        if (now < settleUntil || player.playbackState != Player.STATE_READY) return
        // Before the first pong, the clocks are taken as equal: close enough to start, and a pong
        // within a second puts it right. The leader's own delay is out of its word already; this
        // phone's is added, so that what comes out of each is in step.
        val target = Follow.target(state, gap.hostNow(now)) + delayMs
        // Past the end the leader is moving on or starting the song again, and its word that it
        // did is on the way: a jump there would only land at the end.
        val length = player.duration
        if (length != C.TIME_UNSET && target >= length) return
        val local = player.currentPosition
        if (now - lastLoggedDrift >= 2_000) {
            lastLoggedDrift = now
            Log.i(TAG, "drift ${target - local} ms, speed ${"%.3f".format(player.playbackParameters.speed)}, ${if (lock.locked) "locked" else "not locked"} (tolerance ${lock.toleranceMs} ms), clock gap ${gap.offsetMs} ms (round trip ${if (gap.known) gap.bestRoundTripMs else -1} ms)")
        }
        if (!state.playing) {
            // Paused, both stand on the leader's spot, so that they start again together: the
            // pause reaches a follower a moment late, and it had played on that far (235 ms on
            // the emulators, 17 Oct 2026).
            if (abs(target - local) > Follow.IN_STEP_MS) seek(player, target)
            _status.value = _status.value.copy(driftMs = target - local, locked = false)
            return
        }
        when (val decision = lock.decide(target, local, state.speed)) {
            is Follow.Seek -> seek(player, decision.positionMs)
            is Follow.Nudge -> speed(player, decision.speed)
            is Follow.Steady -> speed(player, decision.speed)
        }
        _status.value = _status.value.copy(driftMs = target - local, locked = lock.locked)
    }

    /** The leader plays [state]'s song and this phone does not, yet. */
    private fun toLeadersSong(player: Player, state: TogetherMessage.State, now: Long) {
        // It moved on by itself at the end of a song, as the leader does at the same moment, and
        // the leader's word that it did is a moment behind: going back would cut the new song.
        if (state.songId == autoAdvancedFrom && now - autoAdvancedAt < AUTO_AHEAD_MS) return
        if (loadingSong == state.songId) return
        // In this phone's queue already, its queue being the leader's: go to it there.
        indexOf(player, state.songId)?.let { index ->
            Log.i(TAG, "The leader plays ${state.songId}: in the queue here, going to it")
            loadingSong = state.songId
            matchedQueue = null
            lock.reset()
            mine(SONG_SWITCH_MS)
            player.seekTo(index, 0)
            return
        }
        // Else with the leader's queue from that song on, which it sends a moment after a change,
        // so it is waited for that long. Without it, the song alone, and the queue comes after.
        // Never a radio of this phone's own: that played other songs next than the leader did,
        // more of them, and nothing ever put it right (his find of 17 Oct 2026).
        val queue = roomQueue?.songs?.takeIf { it.firstOrNull()?.id == state.songId }
        if (queue == null) {
            if (waitingFor != state.songId) {
                waitingFor = state.songId
                waitingSince = now
            }
            if (now - waitingSince < QUEUE_WAIT_MS) return
        }
        loadingSong = state.songId
        matchedQueue = null
        lock.reset()
        Log.i(TAG, "The leader plays ${state.songId}: playing it here, ${if (queue != null) "with its queue" else "the queue to follow"}")
        // A queue resolves for a moment before its song arrives: all of that is the room's doing.
        // And the leader chose it, not this listener: learning must not read it as their pick.
        mine(SONG_SWITCH_MS)
        val songs = queue?.map(::metadataOf) ?: listOf(
            MediaMetadata(
                id = state.songId,
                title = state.title,
                artists = listOf(MediaMetadata.Artist(id = null, name = state.artist)),
                duration = -1,
                thumbnailUrl = "https://i.ytimg.com/vi/${state.songId}/hqdefault.jpg",
                genre = null,
            )
        )
        service.playQueue(ListQueue(items = songs), playWhenReady = state.playing, replace = true, origin = PlayOrigin.EXTERNAL)
    }

    /**
     * This phone's queue made the leader's, from the song both play on, so that the same song
     * comes next here as there (his find of 17 Oct 2026: a follower's queue held more songs than
     * the leader's). The song playing goes on through it: the queue board keeps a song playing
     * when the queue that replaces it starts with that song.
     */
    private fun matchQueue() {
        val room = roomQueue ?: return
        val player = service.exoPlayer
        val current = player.currentMediaItem?.mediaId ?: return
        if (room.songs.firstOrNull()?.id != current) return
        matchedQueue = room
        val wanted = room.songs.drop(1).map { it.id }
        val here = upcoming(player)
        if (here == wanted) return
        Log.i(TAG, "The room's queue here: ${wanted.size} songs after this one, in place of ${here.size}")
        // The song goes on through the swap, so this is short: long enough for the queue to load,
        // not so long that a skip of the listener's own is taken for the room's.
        mine(QUEUE_SWAP_MS)
        service.playQueue(
            ListQueue(items = room.songs.map(::metadataOf)),
            playWhenReady = player.playWhenReady,
            replace = true,
            origin = PlayOrigin.EXTERNAL,
        )
    }

    /** What this phone plays after the song playing, in order, as far as the room's queue reaches. */
    private fun upcoming(player: Player): List<String> {
        val timeline = player.currentTimeline
        if (timeline.isEmpty) return emptyList()
        val window = Timeline.Window()
        val ids = mutableListOf<String>()
        var index = timeline.getNextWindowIndex(player.currentMediaItemIndex, Player.REPEAT_MODE_OFF, player.shuffleModeEnabled)
        while (index != C.INDEX_UNSET && ids.size < TogetherMessage.QUEUE_SONGS) {
            ids += timeline.getWindow(index, window).mediaItem.mediaId
            index = timeline.getNextWindowIndex(index, Player.REPEAT_MODE_OFF, player.shuffleModeEnabled)
        }
        return ids
    }

    /** Where [songId] is in this phone's player: after the song playing if it is there twice. */
    private fun indexOf(player: Player, songId: String): Int? {
        val timeline = player.currentTimeline
        val window = Timeline.Window()
        val at = (0 until timeline.windowCount).filter { timeline.getWindow(it, window).mediaItem.mediaId == songId }
        return at.firstOrNull { it > player.currentMediaItemIndex } ?: at.firstOrNull()
    }

    private fun metadataOf(song: TogetherMessage.Song) = MediaMetadata(
        id = song.id,
        title = song.title,
        artists = listOf(MediaMetadata.Artist(id = null, name = song.artist)),
        duration = -1,
        thumbnailUrl = song.thumbnail.ifBlank { null },
        genre = null,
    )

    private var loadingSong: String? = null
    private var lastLoggedDrift = 0L
    private var lastStateAt = 0L
    private var ownWay = false
    private var ownCommandUntil = 0L

    private fun seek(player: Player, positionMs: Long) {
        mine()
        lock.reset()
        // A leader on Bluetooth near the start of a song says a spot before it.
        player.seekTo(positionMs.coerceAtLeast(0))
        settleUntil = System.currentTimeMillis() + SETTLE_MS
    }

    private fun speed(player: Player, speed: Double) {
        if (abs(player.playbackParameters.speed - speed) < 0.001) return
        player.playbackParameters = PlaybackParameters(speed.toFloat(), player.playbackParameters.pitch)
    }

    companion object {
        private const val TAG = "Together"
        private const val SYNC_TICK_MS = 250L
        /** After a seek, time for the player to land before the next look. */
        private const val SETTLE_MS = 500L
        private const val MEMBER_TIMEOUT_MS = 45_000L
        private const val HOST_TIMEOUT_MS = 25_000L
        /** How long after a seek or a pause of the session's own the player's answer is still its doing. */
        private const val OWN_COMMAND_MS = 600L
        /** And after it asks for the leader's song: the queue resolves first. */
        private const val SONG_SWITCH_MS = 8_000L
        /** How long a follower that moved on by itself waits for the leader's word that it did too. */
        private const val AUTO_AHEAD_MS = 3_000L
        /** How long a follower waits for the leader's queue to come with a new song. */
        private const val QUEUE_WAIT_MS = 1_500L
        /** And how long after its queue is swapped for the leader's under the song playing. */
        private const val QUEUE_SWAP_MS = 2_000L
        /** The leader's queue goes out this long after its last change, and every this many beats. */
        private const val QUEUE_SETTLE_MS = 600L
        private const val QUEUE_EVERY_BEATS = 15
        /** The owner leaves an old room this long after telling everyone where the new one is. */
        private const val MOVE_AFTER_MS = 1_500L
        private const val END_AFTER_MS = 800L

        /** Public Nostr relays, as many as OpenTune sends to: a room lasts while any one answers. */
        val PUBLIC_RELAYS = listOf(
            "wss://relay.damus.io",
            "wss://nos.lol",
            "wss://relay.primal.net",
            "wss://relay.snort.social",
            "wss://nostr.mom",
            "wss://relay.nostr.net",
            "wss://offchain.pub",
            "wss://nostr-pub.wellorder.net",
        )
    }
}
