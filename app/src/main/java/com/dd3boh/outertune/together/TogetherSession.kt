/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.together

import android.util.Log
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import android.widget.Toast
import com.dd3boh.outertune.R
import com.dd3boh.outertune.extensions.toMediaItem
import com.dd3boh.outertune.models.MediaMetadata
import com.dd3boh.outertune.playback.MusicService
import com.dd3boh.outertune.playback.queues.YouTubeQueue
import com.zionhuang.innertube.models.WatchEndpoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
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
import com.dd3boh.outertune.constants.PlayOrigin

/**
 * A listening room while it lasts, held by the [MusicService] (his yes of 17 Oct 2026 for 0.13,
 * steps 1 to 3; this is step 1: a room that follows its host).
 *
 * The host says where it is in which song, on every change and every couple of seconds. A guest
 * plays the same song from YouTube itself, measures the gap to the host's clock from pings, and
 * every quarter of a second checks how far off it is: a little, and it plays a touch faster or
 * slower until it closes; a lot, and it jumps. Messages are sealed with the room code's key and
 * signed with a key made for this session alone, then sent through Nostr relays (RelayPool).
 */
class TogetherSession(
    private val service: MusicService,
    val code: RoomCode,
    val host: Boolean,
    private val relays: List<String>,
    private val name: String,
) {
    data class Status(
        val relaysConnected: Int = 0,
        val others: Int = 0,
        /** The guest's last gap to the host, in ms: positive behind, negative ahead. Null before it knows. */
        val driftMs: Long? = null,
        val hostSong: String? = null,
        /** The id of the song the host plays, to tell whether this phone plays the same one. */
        val hostSongId: String? = null,
        val waitingForHost: Boolean = true,
        /** The guest paused, picked another song or seeked: it plays on its own until [catchUp]. */
        val ownWay: Boolean = false,
        /** The host's last song from a guest, "title, by whom". */
        val lastSuggestion: String? = null,
        /** The others heard in the room lately, by name, the host first. */
        val members: List<Member> = emptyList(),
    )

    /** Someone else in the room: [id] is the start of their key for this session, the name their phone's. */
    data class Member(val id: String, val name: String, val host: Boolean)

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
    private val gap = ClockGap()
    private var hostKey: String? = null
    private var lastState: TogetherMessage.State? = null
    private var settleUntil = 0L
    private var lastSentState = 0L
    private val heard = mutableMapOf<String, Long>()
    private val names = mutableMapOf<String, Member>()
    private val speedBefore = service.exoPlayer.playbackParameters.speed

    private val _status = MutableStateFlow(Status(waitingForHost = !host))
    val status: StateFlow<Status> = _status

    private val listener = object : Player.Listener {
        // A guest's own pause, song or seek, as against the session's own commands (mine()).
        override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
            if (!host) listenerChose("a pause or a play")
        }

        override fun onMediaItemTransition(mediaItem: androidx.media3.common.MediaItem?, reason: Int) {
            if (!host && reason != Player.MEDIA_ITEM_TRANSITION_REASON_AUTO && reason != Player.MEDIA_ITEM_TRANSITION_REASON_REPEAT) {
                listenerChose("another song")
            }
        }

        override fun onPositionDiscontinuity(oldPosition: Player.PositionInfo, newPosition: Player.PositionInfo, reason: Int) {
            if (!host && reason == Player.DISCONTINUITY_REASON_SEEK) listenerChose("a seek")
        }

        override fun onEvents(player: Player, events: Player.Events) {
            if (host && (events.contains(Player.EVENT_IS_PLAYING_CHANGED) ||
                    events.contains(Player.EVENT_MEDIA_ITEM_TRANSITION) ||
                    events.contains(Player.EVENT_POSITION_DISCONTINUITY) ||
                    events.contains(Player.EVENT_PLAYBACK_PARAMETERS_CHANGED))
            ) sendState()
        }
    }

    fun start() {
        Log.i(TAG, "Room ${code.pretty}: ${if (host) "hosting" else "joining"} through ${relays.size} relays")
        pool.start()
        service.exoPlayer.addListener(listener)
        scope.launch { pool.connected.collect { n -> _status.value = _status.value.copy(relaysConnected = n) } }
        scope.launch { pool.events.collect { onEvent(it) } }
        scope.launch {
            while (isActive) {
                send(TogetherMessage.Here(name, host))
                delay(TogetherMessage.HERE_MS)
            }
        }
        if (host) {
            scope.launch {
                while (isActive) {
                    if (System.currentTimeMillis() - lastSentState >= TogetherMessage.HEARTBEAT_MS - 50) sendState()
                    delay(TogetherMessage.HEARTBEAT_MS)
                }
            }
        } else {
            // Pings: every second at first, so the clock gap is known within moments of joining.
            scope.launch {
                var sent = 0
                while (isActive) {
                    hostKey?.let { send(TogetherMessage.Ping(System.currentTimeMillis(), it)) }
                    delay(if (++sent < 10) 1_000L else 15_000L)
                }
            }
            scope.launch {
                while (isActive) {
                    follow()
                    delay(SYNC_TICK_MS)
                }
            }
        }
    }

    fun stop() {
        service.exoPlayer.removeListener(listener)
        if (!host) service.exoPlayer.playbackParameters = PlaybackParameters(speedBefore, service.exoPlayer.playbackParameters.pitch)
        pool.stop()
        scope.cancel()
        Log.i(TAG, "Left room ${code.pretty}")
    }

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
                positionMs = player.currentPosition,
                playing = player.isPlaying || (player.playWhenReady && player.playbackState == Player.STATE_BUFFERING),
                speed = player.playbackParameters.speed.toDouble(),
                atMs = lastSentState,
            )
        )
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
        if (event.pubkey == me || event.kind != TogetherMessage.KIND) return
        val message = code.open(event.content)?.let(TogetherMessage::decode) ?: return
        heard[event.pubkey] = System.currentTimeMillis()
        val now = System.currentTimeMillis()
        heard.entries.removeAll { now - it.value > MEMBER_TIMEOUT_MS }
        if (message is TogetherMessage.Here) names[event.pubkey] = Member(event.pubkey.take(8), message.name, message.host)
        names.keys.retainAll(heard.keys)
        _status.value = _status.value.copy(
            others = heard.size,
            members = heard.keys.map { names[it] ?: Member(it.take(8), "", host = it == hostKey) }.sortedByDescending { it.host },
        )
        when (message) {
            is TogetherMessage.Ping -> if (host && message.to == me) {
                send(TogetherMessage.Pong(message.askedAtMs, System.currentTimeMillis(), event.pubkey))
            }
            is TogetherMessage.Pong -> if (!host && message.to == me) {
                gap.heard(message.askedAtMs, message.hostMs, System.currentTimeMillis())
            }
            is TogetherMessage.State -> if (!host) {
                if (hostKey == null) Log.i(TAG, "Following ${event.pubkey.take(8)}")
                hostKey = event.pubkey
                lastState = message
                lastStateAt = System.currentTimeMillis()
                _status.value = _status.value.copy(
                    hostSong = listOf(message.title.ifBlank { message.songId }, message.artist).filter { it.isNotBlank() }.joinToString(" · "),
                    hostSongId = message.songId,
                    waitingForHost = false,
                )
            }
            is TogetherMessage.Here -> if (!host && message.host && hostKey == null) hostKey = event.pubkey
            is TogetherMessage.Suggest -> if (host) {
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
        }
    }

    /** A guest's song for the host to play next. */
    fun suggest(song: MediaMetadata) {
        send(TogetherMessage.Suggest(song.id, song.title, song.artists.joinToString { it.name }, song.thumbnailUrl.orEmpty(), name))
        Log.i(TAG, "Suggested ${song.id} to the room")
    }

    /** Back with the host after going one's own way: the next look puts the host's song and place back. */
    fun catchUp() {
        ownWay = false
        _status.value = _status.value.copy(ownWay = false)
        Log.i(TAG, "Catching up with the host")
    }

    /**
     * The listener did something to the player that the room did not: from then on this phone
     * plays on its own, the host's music no longer forced on it, until Catch up.
     */
    private fun listenerChose(what: String) {
        if (lastState == null || ownWay || System.currentTimeMillis() < ownCommandUntil) return
        ownWay = true
        speed(service.exoPlayer, speedBefore.toDouble())
        _status.value = _status.value.copy(ownWay = true, driftMs = null)
        Log.i(TAG, "The listener chose $what: playing on their own until Catch up")
    }

    /** What the session does to the player next is its own, not the listener's, for [forMs]. */
    private fun mine(forMs: Long = OWN_COMMAND_MS) {
        ownCommandUntil = System.currentTimeMillis() + forMs
    }

    /** A guest's quarter-second look: the host's song, playing or not, and where in it. */
    private fun follow() {
        val state = lastState ?: return
        // A host silent this long has left: the guest plays on by itself and says so.
        if (System.currentTimeMillis() - lastStateAt > HOST_TIMEOUT_MS) {
            Log.i(TAG, "No word from the host for ${HOST_TIMEOUT_MS / 1000} s: playing on alone")
            lastState = null
            hostKey = null
            speed(service.exoPlayer, speedBefore.toDouble())
            _status.value = _status.value.copy(waitingForHost = true, driftMs = null)
            return
        }
        if (ownWay) return
        val player = service.exoPlayer
        if (player.currentMediaItem?.mediaId != state.songId) {
            if (loadingSong != state.songId) {
                loadingSong = state.songId
                Log.i(TAG, "The host plays ${state.songId}: playing it here")
                // A queue resolves for a moment before its song arrives: all of that is the room's doing.
                mine(SONG_SWITCH_MS)
                // The host chose it, not this listener: learning must not read it as their pick.
                service.playQueue(YouTubeQueue(WatchEndpoint(videoId = state.songId)), replace = true, origin = PlayOrigin.EXTERNAL)
            }
            return
        }
        loadingSong = null
        if (player.playbackState != Player.STATE_READY && player.playbackState != Player.STATE_BUFFERING) return
        if (player.playWhenReady != state.playing) {
            mine()
            player.playWhenReady = state.playing
        }
        val now = System.currentTimeMillis()
        if (now < settleUntil || player.playbackState != Player.STATE_READY) return
        // Before the first pong, the clocks are taken as equal: close enough to start, and a pong
        // within a second puts it right.
        val target = Follow.target(state, gap.hostNow(now))
        val local = player.currentPosition
        _status.value = _status.value.copy(driftMs = target - local)
        if (now - lastLoggedDrift >= 2_000) {
            lastLoggedDrift = now
            Log.i(TAG, "drift ${target - local} ms, speed ${"%.3f".format(player.playbackParameters.speed)}, clock gap ${gap.offsetMs} ms (round trip ${if (gap.known) gap.bestRoundTripMs else -1} ms)")
        }
        if (!state.playing) {
            // Paused, both stand on the host's spot, so that they start again together: the pause
            // reaches a guest a moment late, and it had played on that far (235 ms on the
            // emulators, 17 Oct 2026).
            if (abs(target - local) > Follow.IN_STEP_MS) seek(player, target)
            return
        }
        when (val decision = Follow.decide(target, local, state.speed)) {
            is Follow.Seek -> seek(player, decision.positionMs)
            is Follow.Nudge -> speed(player, decision.speed)
            is Follow.Steady -> speed(player, decision.speed)
        }
    }

    private var loadingSong: String? = null
    private var lastLoggedDrift = 0L
    private var lastStateAt = 0L
    private var ownWay = false
    private var ownCommandUntil = 0L

    private fun seek(player: Player, positionMs: Long) {
        mine()
        player.seekTo(positionMs)
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
        /** And after it asks for the host's song: the queue resolves first. */
        private const val SONG_SWITCH_MS = 8_000L

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
