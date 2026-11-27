/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.together

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.Toast
import androidx.annotation.StringRes
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.datastore.preferences.core.edit
import androidx.media3.common.C
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import com.dd3boh.outertune.R
import com.dd3boh.outertune.constants.PlayOrigin
import com.dd3boh.outertune.constants.TogetherCatchUpKey
import com.dd3boh.outertune.constants.TogetherDelayKey
import com.dd3boh.outertune.constants.TogetherEveryoneArrangesKey
import com.dd3boh.outertune.constants.TogetherEveryoneControlsKey
import com.dd3boh.outertune.constants.TogetherEveryoneSeeksKey
import com.dd3boh.outertune.constants.TogetherToleranceKey
import com.dd3boh.outertune.extensions.metadata
import com.dd3boh.outertune.constants.Unreleased
import com.dd3boh.outertune.extensions.toMediaItem
import com.dd3boh.outertune.migration.ImportSearch
import com.dd3boh.outertune.playback.downloadManager.SongFileIds
import com.dd3boh.outertune.models.MediaMetadata
import com.dd3boh.outertune.playback.MusicService
import com.dd3boh.outertune.playback.RoomAsks
import com.dd3boh.outertune.playback.Varispeed
import com.dd3boh.outertune.playback.queues.ListQueue
import com.dd3boh.outertune.utils.dataStore
import com.dd3boh.outertune.utils.ReportTrail
import com.dd3boh.outertune.utils.Throttle
import com.zionhuang.innertube.YouTube
import com.dd3boh.outertune.utils.Toaster
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.OkHttpClient
import kotlin.math.abs
import java.io.File

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
    /** This phone made the room, or was handed it and hosts it again after a restart: see [owner]. */
    owner: Boolean,
    val relays: List<String>,
    val name: String,
    /**
     * The key this phone signs as in the room. A new one for a new room; the one it had for a room that comes back, so
     * that a host that returns is the same owner to the guests (RoomMemory).
     */
    identity: ByteArray = Secp256k1.newSecretKey(),
    /**
     * For a guest's room that comes back: when it last heard the host before this session, or came if it never did, as
     * RoomMemory kept it. The host's absence goes on counting from then. 0 for a room joined now.
     */
    hostHeardAt: Long = 0L,
    /**
     * Who was in control as this phone last knew it, for a room that comes back (RoomMemory): a host's room comes back
     * with control where it was, not taken from the guest who had it by the host's own restart, and nobody is told of a
     * change that is none (ControlNews). Null for a room made or joined now.
     */
    lead: String? = null,
    /** Whose each song was in the room before it moved to this code (WhoAdded.carried); null for a room made or joined now. */
    carried: WhoAdded.Carried? = null,
    /**
     * How this phone listened in the room before it came back as this session (RoomWay): moved to a new code with it
     * (MusicService.moveTogether), or joined again after the service ended (rejoinSavedRoom). Null for a room made or
     * joined now, which a guest follows and plays with.
     */
    way: RoomWay? = null,
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
        /** On its own while this phone casts (GuestCast): back with the room by itself as the cast ends. */
        val ownWay: Boolean = false,
        /** A guest while nobody plays for the room (host away past the timeout): the sheet says so and offers Leave. */
        val nobodyPlays: Boolean = false,
        /** The owner's room settings, who may do what: its own on the owner, else as last heard; null before, or from an older build. */
        val settings: RoomSettings? = null,
        /** The person in control takes a guest's change to the queue (Arrange): its Here says so, a build before this one's does not. */
        val leaderArranges: Boolean = false,
        /** The person in control takes a guest's moves (Control): its Here says so since 27 Nov 2026, an older build's does not. */
        val leaderControls: Boolean = false,
        /** The room's shuffle as the person in control last said it (Queue): what a guest's shuffle button shows (RoomListen). */
        val roomShuffle: Boolean? = null,
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
        /** A follower paused by the phone itself (a call, headphones out): the room plays on, and play brings it back. */
        val pausedHere: Boolean = false,
        /** A guest on its own: its song is proposed to the leader and waits for an answer. */
        val proposed: Boolean = false,
        /** The leader: guests' songs and the queues after them, each waiting for Play it or Not now, the oldest first (ProposalLine). */
        val proposals: List<Proposal> = emptyList(),
        /** The host's song fails on this phone (YouTube refuses it here): it waits on it, and takes the next one by itself. */
        val songUnavailable: Boolean = false,
        /** Behind the room on a line that can't keep up: what comes in never reaches where the room is (DataWait). */
        val tooSlow: Boolean = false,
        /**
         * Following: the one in control plays a song from its files that isn't on YouTube Music, which nothing here can
         * play (PhoneSongs). This phone waits for the room's next song and says why.
         */
        val songOnlyThere: Boolean = false,
        /** In control: this phone plays a song from its files that isn't on YouTube Music, and the others hear nothing. */
        val songOnlyHere: Boolean = false,
        /** Following: the room's song is YouTube Music's version of a file on the phone in control (PhoneSongs.Heard). */
        val songMatched: Boolean = false,
        /** A guest that has heard nothing of the host for a while: it says the host is away (HostAbsence). */
        val hostAway: Boolean = false,
        /** This phone casts. Following, it then follows nothing until the cast ends (GuestCast). */
        val casting: Boolean = false,
        /** On its own way for its cast alone: it catches up by itself when the cast ends. */
        val catchUpAfterCast: Boolean = false,
        /** This phone holds InterTune's developer key (DevKey), and says so in its Here with a proof (DevBadge). */
        val dev: Boolean = false,
        /**
         * What the host's Leave offers (RoomLeaveDialog), as of the room's last look: a phone that went quiet stops being
         * offered while the dialog is open, where a choice read once kept naming it (review of 0.13, 26 Nov 2026).
         */
        val leaveChoice: RoomLeaveChoice = RoomLeaveChoice(heir = null, olderOnly = false),
        /** The host leaving: the name of the phone it hands the room to, until that phone says it hosts it (RoomHandOn). */
        val handingTo: String? = null,
    )

    /**
     * A guest's own song and the queue after it, proposed to this phone as the leader: see [propose]. [from] is the
     * guest's key in the room, so that its newer proposal takes the place of its older one; never shown.
     */
    data class Proposal(val songs: List<TogetherMessage.Song>, val positionMs: Long, val by: String, val receivedAt: Long, val from: String = "")

    /**
     * Someone else in the room: [key] is their key for this session, the name their phone's. [dev]: their Here proved
     * it is InterTune's developer's phone (DevBadge), checked here against the developer key.
     */
    data class Member(val key: String, val name: String, val owner: Boolean, val leader: Boolean, val dev: Boolean = false) {
        val id: String get() = key.take(8)
    }

    /** How many relays the room was asked to use; [Status.relaysConnected] says how many answer. */
    val relayCount: Int get() = relays.size

    /**
     * Nothing that goes wrong in a room takes the app down (his word of 21 Nov 2026: "anything bad that happens in the
     * rooms shows an error or just does something"): a failure in any of the room's work ends this phone's part in the
     * room with a line, and goes into the log and Report a problem's steps ([failed]).
     */
    private val failure = CoroutineExceptionHandler { _, e -> failed(e) }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main + failure)
    private val secret = identity
    private val me = Secp256k1.publicKey(secret).toHex()

    /** The key as text, for RoomMemory. */
    val identityHex: String get() = secret.toHex()

    /** Who put each song of the room's queue in: only this phone's own teach its recommendations (WhoAdded). */
    private val whoAdded = WhoAdded(me).also { added -> carried?.let(added::takeOver) }

    /**
     * Whether the song [songId], playing here now, teaches this phone's recommendations (MusicService.noteTransition): not
     * when someone else in the room put it in the queue. Main thread.
     */
    fun learnsFrom(songId: String): Boolean = whoAdded.learns(songId)

    /** Whose each song was, for the room this one moves to (MusicService.moveTogether). */
    fun carriedSongs(): WhoAdded.Carried = whoAdded.carried()

    private val startedAt = System.currentTimeMillis()

    /** Where a guest counts the host's absence from until it hears it: see [hostHeardAt]. */
    private val hostSince = if (hostHeardAt > 0) hostHeardAt else startedAt

    /**
     * A guest that joins now, not a room that comes back: it is in only once someone answers ([JoinCheck]). A guest who
     * joined a room its host had just closed heard nothing live and waited on a ghost of the host (21 Nov 2026).
     */
    private val joiningNow = !owner && hostHeardAt == 0L

    /** When the room first answered this guest live, the host or whoever leads; 0 before. */
    private var answeredAt = 0L

    /** Since when this guest has looked for the room with a relay answering: the wait for an answer counts from here. */
    private var lookingSince = startedAt

    /** Who said they made the room and who said it is over, before this phone knew who made it too. */
    private val ends = RoomEnds()

    /** Every relay went down since one last answered: said once when one is back. */
    private var wentDark = false

    /** When the owner was last heard, 0 before it is. */
    private var ownerHeardAt = 0L
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
    private var leaderKey: String? = if (owner) lead ?: me else null
    private val leading: Boolean get() = leaderKey == me

    /** When the lead last moved, by this phone's clock: the owner says it with its word on who leads (LeadWord). */
    private var leadGivenAt = startedAt

    /** Who is in control as this phone's person was last told: see [inControl]. */
    private val news = ControlNews(before = if (owner) leaderKey else lead)

    /** The owner's newest word on who leads, so that an older one is not taken after it. */
    private var leadWord = LeadWord()

    /**
     * When a relay last answered again after none did, or this phone woke from a sleep: the owner counts a leader's
     * silence from then too (LeaderGone).
     */
    private var hearingSince = startedAt

    /** The room's last look: one long after it is a phone that slept ([LeaderGone.ASLEEP_MS]). */
    private var lastLookAt = startedAt

    /** When the owner last told a phone that played for the room without control who has it: see [answerStray]. */
    private var strayAnsweredAt = 0L
    private var lastState: TogetherMessage.State? = null
    private val hostLine = HostLine()
    private var roomQueue: TogetherMessage.Queue? = null
    private var settleUntil = 0L
    private var lastSentState = 0L
    private var pingsToLeader = 0
    private val heard = RoomPresence(MEMBER_TIMEOUT_MS)
    /** What each one said of themselves in their Here: their name and whether they made the room. */
    private val said = mutableMapOf<String, Pair<String, Boolean>>()
    /** Whom the owner has answered at once, so a newcomer learns who leads without waiting. */
    private val greeted = mutableSetOf<String>()
    /** Sent out by the owner, or a host that left and handed the room on (handedOn): their messages are not read. */
    private val sentOut = mutableSetOf<String>()
    /** Who proved in their Here to be InterTune's developer (DevBadge). */
    private val devs = RoomDevs()
    /** This phone's proof that it is InterTune's developer's, said in its Here: only while it holds the key (DevKey). */
    private var devProof: String? = null
    /** The sealed words taken, so that a copy is not taken twice (TakenSeals). */
    private val seals = TakenSeals()
    /** Wakes the room's look before its time is up: what needs one soon (lookEveryMs). */
    private val wake = Channel<Unit>(Channel.CONFLATED)
    private val speedBefore = service.exoPlayer.playbackParameters.speed
    private val repeatBefore = service.exoPlayer.repeatMode
    private var repeatTaken = false
    private var queueJob: Job? = null
    private val lock = SyncLock()

    /** How the room has gone lately, logged once a minute for Report a problem. */
    private val health = RoomHealth()

    /** How late this phone's sound comes out (Settings > Listen together). */
    private var delayMs = 0L

    /**
     * How this phone came back to the room, when it follows someone: paused here or on its own way as it was, so that
     * nothing plays aloud by itself and its own music is not replaced (RoomWay). Null for one that leads, and for a room
     * made or joined now.
     */
    private val cameBackAs = way.takeIf { !leading }

    /**
     * Paused here by the phone itself while the room plays (a call, headphones taken out, another app's music): Play takes
     * it to where the room is. A pause by its listener is a mute since 26 Nov 2026 ([listenerPlayed], RoomListen).
     */
    private var pausedHere = cameBackAs == RoomWay.PAUSED_HERE || cameBackAs == RoomWay.OWN_WAY

    /** This phone's sound off while it stays in step with the room (RoomListen): MusicService.roomMuted, kept with the room's way. */
    private var muted: Boolean
        get() = service.roomMuted.value
        set(value) {
            service.roomMuted.value = value
        }

    init {
        // A room that comes back keeps the mute; one made or joined now starts with the sound on.
        muted = cameBackAs == RoomWay.MUTED
    }
    /** A cast on this phone, as the last look saw it: see [castLook]. */
    private var casting = false
    private val guestCast = GuestCast()
    /** The host's song this phone cannot play: see [holdsFailed]. */
    private val refusal = HostSongRefusal()
    /** The room's songs this phone gave their ask (AskedFor): see follow. */
    private val roomAsks = RoomAsks()
    /** Songs from files here, as the others hear them while this phone is in control: see [PhoneSongs]. */
    private val phoneSongs = PhoneSongs(
        scope = scope,
        search = ImportSearch::songs,
        answered = ::phoneSongAnswered,
        mayAsk = { !Throttle.isBlocked },
        // The id a file from YouTube carries: YouTube Music's song for it, signed out (get_queue sends no account).
        songById = { id -> YouTube.queue(listOf(id)).getOrThrow().firstOrNull { it.id == id } },
        idInTags = { path -> File(path).let { file -> file.inputStream().buffered().use { SongFileIds.read(file.name, it) } } },
    )
    /** The room's queue this phone's own was last made to match, so it is compared once per word. */
    private var matchedQueue: TogetherMessage.Queue? = null
    private var currentId: String? = service.exoPlayer.currentMediaItem?.mediaId
    /** The song this phone moved on from by itself at its end, and when. */
    private var autoAdvancedFrom: String? = null
    private var autoAdvancedAt = 0L

    /** The last song this phone played to its end and moved on from by itself, and where its copy of it ended. */
    private var endedSong: String? = null
    private var endedAtMs = 0L
    /** The leader's song this phone waits for the leader's queue for, and since when. */
    private var waitingFor: String? = null
    private var waitingSince = 0L
    /** The song this phone proposed to the leader, until the leader plays it or this phone moves on. */
    private var proposedSongId: String? = null

    /** The proposals' notification while InterTune is not in front, and the receiver of its buttons (ProposalNotice). */
    private val notice = ProposalNotice(service)
    private var noticeRegistered = false
    private val noticeAnswer = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            // Only the one it showed: another may be the one asked by now.
            val first = _status.value.proposals.firstOrNull()
            if (first == null || intent.getLongExtra(ProposalNotice.EXTRA_AT, -1L) != first.receivedAt) {
                syncNotice()
                return
            }
            when (intent.getStringExtra(ProposalNotice.EXTRA_ANSWER)) {
                ProposalNotice.PLAY -> acceptProposal()
                ProposalNotice.NOT_NOW -> dismissProposal()
            }
        }
    }

    // A host's room that comes back with control elsewhere (lead) waits for that phone, as any follower does: paused here or
    // on its own way as it came back (cameBackAs).
    private val _status = MutableStateFlow(
        Status(
            waitingForHost = !leading, leading = leading, settings = if (owner) RoomSettings() else null,
            pausedHere = pausedHere,
        )
    )
    val status: StateFlow<Status> = _status

    /** Whether this phone follows someone now: its player's repeat mode is then the room's, not its own choice. */
    val following: Boolean get() = !leading

    /**
     * This phone hosts the room: it made it, or its host handed it on to this phone as it left (RoomHandOn, becomeHost). A
     * state, so that the room's sheet, which reads it as it draws, changes with it.
     */
    var owner by mutableStateOf(owner)
        private set

    private val listener = object : Player.Listener {
        // A follower's own pause, song or seek, as against the session's own commands (mine()) and a cast's taking the
        // music from this player and giving it back (MusicService.handOverTo, takeBack), which are never the listener's.
        // A command given by hand is the listener's at once, the session's window or not (byHand).
        override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
            if (!leading && !service.castOn) listenerPlayed(playWhenReady)
            wake.trySend(Unit)
        }

        override fun onMediaItemTransition(mediaItem: androidx.media3.common.MediaItem?, reason: Int) {
            val from = currentId
            currentId = mediaItem?.mediaId
            if (leading) return
            if (service.castOn) {
                // The cast emptied this player, or filled it again: the next look sees to it (castLook).
                wake.trySend(Unit)
                return
            }
            if (proposedSongId != null && ownWay && mediaItem?.mediaId != proposedSongId) {
                // On its own to another song: what was proposed is no longer what it plays.
                proposedSongId = null
                _status.value = _status.value.copy(proposed = false)
            }
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
            // At the end of a song, by itself: how long this phone's copy of it was (see toLeadersSong).
            if (reason == Player.DISCONTINUITY_REASON_AUTO_TRANSITION) {
                endedSong = oldPosition.mediaItem?.mediaId
                endedAtMs = oldPosition.positionMs
            }
            if (!leading && reason == Player.DISCONTINUITY_REASON_SEEK && !service.castOn) {
                // In the room's song while only the one in control moves in it (RoomSettings): held, see seekHeldHere.
                if (oldPosition.mediaItemIndex == newPosition.mediaItemIndex && seekHeld(_status.value)) {
                    seekHeldHere(abs(newPosition.positionMs - oldPosition.positionMs))
                } else if (oldPosition.mediaItemIndex == newPosition.mediaItemIndex) {
                    movedInSong(newPosition.positionMs)
                } else listenerChose("a seek")
            }
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
        // Never the code: a report can be posted in public, and the code lets anyone in.
        Log.i(TAG, "A room ${if (owner) "made here" else "joined"}, through ${relays.size} relays")
        val how = when {
            owner -> ReportTrail.Room.MADE
            joiningNow -> ReportTrail.Room.JOINED
            else -> ReportTrail.Room.BACK
        }
        ReportTrail.thisRun.room(how)
        // A guest sees that it is joining, and then that it is in (answered) or not (nobodyAnswered): the invite's Join
        // closed and nothing said what was going on (his word of 21 Nov 2026).
        if (joiningNow) Toaster.show(service, R.string.together_joining)
        pool.start()
        service.exoPlayer.addListener(listener)
        // The proposal notification's buttons, for as long as the room lasts. One left from before a restart answers nothing.
        noticeRegistered = runCatching {
            ContextCompat.registerReceiver(service, noticeAnswer, IntentFilter(ProposalNotice.action(service)), ContextCompat.RECEIVER_NOT_EXPORTED)
        }.onFailure { Log.w(TAG, "Could not listen for the proposal notification's buttons", it) }.isSuccess
        notice.hide()
        scope.launch {
            var before = 0
            pool.connected.collect { n ->
                _status.value = _status.value.copy(relaysConnected = n)
                // Every relay down, and back: the room can neither hear nor be heard meanwhile.
                if (n == 0 && before > 0) {
                    Log.w(TAG, "No relay answering: the room hears nothing until one is back")
                    ReportTrail.thisRun.room(ReportTrail.Room.NO_RELAY)
                } else if (n > 0 && before == 0 && wentDark) {
                    Log.i(TAG, "A relay answers again")
                    ReportTrail.thisRun.room(ReportTrail.Room.RELAY_BACK)
                }
                if (n == 0 && before > 0) wentDark = true else if (n > 0) wentDark = false
                // The wait for an answer counts only while a relay answers: no network is no empty room.
                if (n == 0) lookingSince = System.currentTimeMillis()
                // Nor is it the leader's silence: the owner hears the room again from now (LeaderGone).
                if (n > 0 && before == 0) hearingSince = System.currentTimeMillis()
                // A relay that has just come up hears this phone at once. The Here said as the
                // room opened went nowhere, no relay being up yet, and the leader answers a
                // newcomer only once it hears it: a guest waited for the leader's next beat.
                if (n > before) sayHere()
                before = n
            }
        }
        scope.launch { pool.events.collect { onIncoming(it) } }
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
        // A phone of InterTune's developer's, holding the developer key (DevKey), says so in its Here with a proof made
        // for this room and this phone's key in it (DevBadge): from as soon as the key is there, or put in. Nothing for
        // anyone else.
        scope.launch {
            DevKey.flow(service).collect { key ->
                val proof = key?.let { withContext(Dispatchers.Default) { DevBadge.prove(it, code.tag, me) } }
                if ((proof == null) == (devProof == null)) return@collect
                devProof = proof
                _status.value = _status.value.copy(dev = proof != null)
                sayHere()
            }
        }
        // The owner's room settings, set once and kept for every room it makes, as its name in rooms is (his word of
        // 22 Nov 2026), in Settings > Listen together or the room's sheet, the same keys (RoomSettings): said in its Here
        // as the room opens, and at once as they change.
        if (owner) scope.launch {
            service.dataStore.data.collect { prefs ->
                ownSettings(RoomSettings(everyoneControls = RoomSettings.kept(prefs[TogetherEveryoneControlsKey], prefs[TogetherEveryoneSeeksKey], prefs[TogetherEveryoneArrangesKey])))
            }
        }
        scope.launch {
            while (isActive) {
                // A room never outlives the service that holds it. One made on a service that has stopped has nothing
                // that will stop it, and it went on saying it was the host to a screen that was no longer there.
                if (service.isDestroyed) {
                    Log.w(TAG, "The room's service has stopped: the room stops with it")
                    stop()
                    break
                }
                // A guest's room is alive while its host is heard, not while the guest is on: one whose host closed it
                // while this phone was off came back at every start (HostAbsence.aliveAt).
                HostAbsence.aliveAt(owner, System.currentTimeMillis(), ownerHeardAt)?.let(service::roomAlive)
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
                castLook()
                if (!leading) follow()
                watchHost()
                watchLeader()
                lookAlone()
                watchMembers()
                keepWay()
                // Four looks a second while the room plays; one every two seconds while it is paused there and here, or
                // while this phone leads. A word from the leader, a play or a pause here, a new lead or Catch up wakes it.
                val every = lookEveryMs(leading, lastState?.playing, service.exoPlayer.playWhenReady)
                withTimeoutOrNull(every) { wake.receive() }
            }
        }
        // How the room has gone lately, once a minute, for Report a problem: see RoomHealth.
        scope.launch {
            while (isActive) {
                delay(RoomHealth.EVERY_MS)
                val now = _status.value
                Log.i(TAG, "room: " + health.line(
                    owner = owner, leading = leading, others = now.others, relaysAnswering = now.relaysConnected,
                    relays = relays.size, roundTripMs = if (gap.known) gap.bestRoundTripMs else null,
                    waitingForLeader = now.waitingForHost, ownWay = now.ownWay, pausedHere = now.pausedHere,
                ))
            }
        }
        publish()
    }

    fun stop() {
        // A service that has stopped has let its player go: nothing of the room's is put back on it.
        if (!service.isDestroyed) {
            service.exoPlayer.removeListener(listener)
            ownSpeed()
            if (repeatTaken) service.exoPlayer.repeatMode = repeatBefore
        }
        pool.stop()
        notice.hide()
        if (noticeRegistered) runCatching { service.unregisterReceiver(noticeAnswer) }
        noticeRegistered = false
        scope.cancel()
        Log.i(TAG, "Left the room")
    }

    /**
     * This phone's end of the room, said to the others first when it made it: for a room that another one replaces, and
     * so that nobody is left waiting on a host that chose to go. The word is made and handed to the relays here and
     * now, not through [send], whose coroutine the stop would cancel; the sockets close after what was handed to them,
     * so it leaves before they do. A room that is only paused with its service says nothing: it comes back.
     */
    fun closeNow() {
        if (owner) {
            // Not while it hands the room on (RoomHandOn): the room is the next host's then, which most often has it
            // already, and a close would end it for those still waiting for that host's word.
            if (handOn.handing == null) {
                ReportTrail.thisRun.room(ReportTrail.Room.CLOSED_HERE)
                runCatching {
                    pool.publish(
                        NostrEvent.signed(secret, TogetherMessage.KIND, listOf(listOf("t", code.tag)), code.seal(TogetherMessage.encode(TogetherMessage.End, sender = me)))
                    )
                }.onFailure { Log.w(TAG, "The end of the room could not be sent", it) }
            }
        } else {
            // A guest that goes to another room leaves this one for good: it says so (Bye).
            sayBye()
        }
        stop()
    }

    /**
     * A guest's goodbye as it leaves the room for good (TogetherMessage.Bye): off the others' list at once, and never handed
     * the room by a host leaving just after it. Handed to the relays here and now, as the close is (closeNow).
     */
    private fun sayBye() {
        runCatching {
            pool.publish(
                NostrEvent.signed(secret, TogetherMessage.KIND, listOf(listOf("t", code.tag)), code.seal(TogetherMessage.encode(TogetherMessage.Bye, sender = me)))
            )
        }.onFailure { Log.w(TAG, "The goodbye could not be sent", it) }
    }

    /**
     * A guest looks at whether the host is about. Not heard for [HostAbsence.AWAY_MS] it is away: the guest says so, in
     * place of who has control or that it waits, and goes back to normal as the host is heard again. Away for
     * [HostAbsence.GONE_MS] the room is over for the guest, with a line. Only while the host is the one who leads, or
     * nobody does (the silence itself takes the lead from whoever had it, see follow): a guest that was given the lead
     * is followed, and the host's silence is no matter then.
     */
    private fun watchHost() {
        if (owner) return
        val now = System.currentTimeMillis()
        // A guest that joined now is in once the room answers; until then it looks for it, and gives up when nobody
        // answers while a relay is up (JoinCheck): a closed room, a host whose phone is off, or a wrong code.
        if (joiningNow && answeredAt == 0L) {
            if (JoinCheck.nobody(now, lookingSince, _status.value.relaysConnected)) {
                nobodyAnswered(now - lookingSince)
                return
            }
        }
        // Away once not heard for a while in this session: a phone that has just come back has not heard it yet.
        val away = (leaderKey == null || leaderKey == ownerKey) && HostAbsence.away(now, maxOf(ownerHeardAt, startedAt))
        if (away != _status.value.hostAway) {
            Log.i(TAG, if (away) "The host is away" else "The host is back")
            ReportTrail.thisRun.room(if (away) ReportTrail.Room.HOST_AWAY else ReportTrail.Room.HOST_BACK)
            _status.value = _status.value.copy(hostAway = away)
        }
        // Gone counted from the host's last word, before this session too for a room that comes back (hostSince).
        if (HostAbsence.gone(now, HostAbsence.lastSign(ownerHeardAt, hostSince))) {
            ReportTrail.thisRun.room(ReportTrail.Room.HOST_GONE)
            closed(R.string.together_host_gone)
        }
    }

    private fun sayHere() = send(
        // Leaving, its word handing the room on in its place, said again until that phone takes the room (RoomHandOn).
        handOn.handing?.let(::handOnWord) ?: TogetherMessage.Here(
            name, owner, lead = if (owner) leaderKey else null, leadSince = if (owner) leadGivenAt else null,
            settings = if (owner) _status.value.settings else null, arranges = true, dev = devProof, takesRoom = true,
            controls = true,
        )
    )

    /**
     * The owner takes control back from the phone it gave it to once that phone has gone (LeaderGone), and everyone is
     * told. A look that comes long after the one before is this phone waking from a sleep: what it did not hear
     * meanwhile is its own silence, not the leader's.
     */
    private fun watchLeader() {
        val now = System.currentTimeMillis()
        if (now - lastLookAt > LeaderGone.ASLEEP_MS) hearingSince = now
        lastLookAt = now
        // Not while it hands the room on as it leaves: control is the next host's then (RoomHandOn).
        if (!owner || leading || handOn.handing != null) return
        val key = leaderKey ?: return
        if (!LeaderGone.now(now, leadGivenAt, heard[key] ?: 0L, hearingSince, _status.value.relaysConnected)) return
        // By name when it was heard: a host's room that comes back may never hear the phone it had given control to.
        val gone = said[key]?.first?.ifBlank { null }
        Log.i(TAG, "Nothing from the leader for ${LeaderGone.QUIET_MS / 1000} s with a relay up: the owner leads again")
        lead(me)
        sayHere()
        inControl(me, wentQuiet = gone)
    }

    /**
     * Who is still in the room, at every look: a phone not heard for [MEMBER_TIMEOUT_MS] has gone and comes off the list,
     * whether or not anyone else speaks (RoomPresence). Only a word coming in took anyone off before, and the last guest
     * to leave stayed on its host's list for good.
     */
    private fun watchMembers() {
        // Who the host's Leave would hand the room to, as of now: a phone passes out of it by going quiet alone.
        val choice = leaveChoice()
        if (choice != _status.value.leaveChoice) _status.value = _status.value.copy(leaveChoice = choice)
        if (!heard.dropQuiet(System.currentTimeMillis())) return
        said.keys.retainAll(heard.keys)
        handOn.keep(heard.keys)
        Log.i(TAG, "Someone in the room went quiet for ${MEMBER_TIMEOUT_MS / 1000} s: off the list")
        publish()
    }

    /**
     * Who is in control now, by the owner's word, or for the owner by its own doing: this phone's person is told once, as
     * it changes (ControlNews), and the room is remembered with it for when it comes back (RoomMemory). [wentQuiet] names
     * the phone the owner took control back from because it had gone.
     */
    private fun inControl(key: String, wentQuiet: String? = null) {
        if (news.told != key) service.roomLead(code, key)
        if (!news.now(key)) return
        val text = when {
            key != me -> service.getString(R.string.together_control_theirs, nameOf(key))
            wentQuiet != null -> service.getString(R.string.together_control_back, wentQuiet)
            else -> service.getString(R.string.together_control_yours)
        }
        Log.i(TAG, "Told that ${if (key == me) "this phone" else key.take(8)} is in control now")
        Toaster.show(service, text, Toast.LENGTH_LONG)
    }

    /** The name [key] said in the room, or "Someone" before it is heard. */
    private fun nameOf(key: String): String = said[key]?.first?.ifBlank { null } ?: service.getString(R.string.together_someone)

    /** The host sees a newcomer come in by the name they chose, and InterTune's developer as such ([dev], DevBadge). */
    private fun joinedLine(name: String, dev: Boolean) {
        val who = name.ifBlank { service.getString(R.string.together_someone) }
        Toaster.show(service, service.getString(if (dev) R.string.together_dev_joined else R.string.together_someone_joined, who))
    }

    /**
     * A phone played for the room without control: one that missed the owner's word (its connection lost and back, or a
     * word dropped on the way). The owner says at once who has it, as it does for a newcomer, rather than at its next
     * Here ten seconds on, while a guest that had lost the leader may follow that phone. Once a beat at most.
     */
    private fun answerStray(now: Long) {
        if (now - strayAnsweredAt < TogetherMessage.HEARTBEAT_MS) return
        strayAnsweredAt = now
        Log.i(TAG, "A phone without control played for the room: told who has it")
        sayHere()
    }

    private fun sendState() {
        val player = service.exoPlayer
        val item = player.currentMediaItem ?: return
        // A song from a file here goes as the others can hear it, its match on YouTube Music or a made-up id, never as
        // itself, and only once it is known which: see PhoneSongs.
        val fromFile = if (fromThisPhone(item.metadata?.isLocal, item.mediaId)) {
            phoneSongs.forRoom(item.mediaId, item.metadata, lookUp = true) ?: return
        } else null
        onlyHere(fromFile is PhoneSongs.OnlyHere)
        lastSentState = System.currentTimeMillis()
        val metadata = item.mediaMetadata
        send(
            TogetherMessage.State(
                songId = fromFile?.id ?: item.mediaId,
                title = fromFile?.title ?: metadata.title?.toString().orEmpty(),
                artist = fromFile?.artist ?: metadata.artist?.toString().orEmpty(),
                // Where its sound is, not its player: a leader on Bluetooth is that much behind
                // what its player says, and the followers aim at what is heard.
                positionMs = player.currentPosition - delayMs,
                playing = player.isPlaying || (player.playWhenReady && player.playbackState == Player.STATE_BUFFERING),
                speed = player.playbackParameters.speed.toDouble(),
                atMs = lastSentState,
                fromFile = fromFile is PhoneSongs.Heard,
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

    /**
     * This phone's queue from the song playing on, in the order it plays. A song from a file on this phone never goes as
     * itself: no other phone can play it, and an untagged file's name was its title, shown in every guest's queue (review
     * 2, 18 Nov 2026). It goes as the same recording on YouTube Music once one is found by its tags, and is left out while
     * it is looked for or when none is (PhoneSongs); the next few are looked for while this phone is in control.
     */
    private fun ownQueue(): List<TogetherMessage.Song> {
        val player = service.exoPlayer
        val timeline = player.currentTimeline
        if (timeline.isEmpty) return emptyList()
        val window = Timeline.Window()
        val songs = mutableListOf<TogetherMessage.Song>()
        var index = player.currentMediaItemIndex
        var ahead = 0
        while (index != C.INDEX_UNSET && songs.size < TogetherMessage.QUEUE_SONGS) {
            val item = timeline.getWindow(index, window).mediaItem
            if (!fromThisPhone(item.metadata?.isLocal, item.mediaId)) {
                val metadata = item.mediaMetadata
                songs += TogetherMessage.Song(
                    id = item.mediaId,
                    title = metadata.title?.toString().orEmpty(),
                    artist = metadata.artist?.toString().orEmpty(),
                    // Never a file's path: a song from the phone has it as its picture (TogetherMessage.thumbnailFor).
                    thumbnail = TogetherMessage.thumbnailFor(metadata.artworkUri?.toString()),
                    // Whoever put it in when that is not this phone, so that it teaches only theirs (WhoAdded).
                    by = whoAdded.sent(item.mediaId),
                )
            } else {
                val heard = phoneSongs.forRoom(item.mediaId, item.metadata, lookUp = leading && ahead++ < PhoneSongs.AHEAD)
                if (heard is PhoneSongs.Heard) songs += heard.song.copy(by = whoAdded.sent(heard.song.id))
            }
            index = timeline.getNextWindowIndex(index, Player.REPEAT_MODE_OFF, player.shuffleModeEnabled)
        }
        return songs
    }

    /**
     * A song from a file here has its answer (PhoneSongs): the room hears at once of the one playing, which waited for it,
     * and of those to come with the queue, once none is left to look for.
     */
    private fun phoneSongAnswered(id: String, more: Boolean) {
        if (!leading) return
        if (service.exoPlayer.currentMediaItem?.mediaId == id) {
            sendState()
            sendQueue()
        } else if (!more) {
            queueSoon()
        }
    }

    /**
     * In control, whether the song playing is one from a file here that the others can't hear: said in the room's sheet,
     * and once as it starts while others are in the room, so that whoever is in control knows why they hear nothing (Niko
     * played the file in place of YouTube Music's song, and learnt it from the others, 22 Nov 2026).
     */
    private fun onlyHere(only: Boolean) {
        if (only == _status.value.songOnlyHere) return
        _status.value = _status.value.copy(songOnlyHere = only)
        if (only && heard.isNotEmpty()) Toaster.show(service, R.string.together_song_only_here, Toast.LENGTH_LONG)
    }

    private fun send(message: TogetherMessage) {
        scope.launch {
            val event = withContext(Dispatchers.Default) {
                NostrEvent.signed(secret, TogetherMessage.KIND, listOf(listOf("t", code.tag)), code.seal(TogetherMessage.encode(message, sender = me)))
            }
            pool.publish(event)
        }
    }

    /**
     * A word from the relays. One that cannot be taken is said in the log and Report a problem's steps and passed over:
     * never the end of the room, nor of the app.
     */
    private fun onIncoming(incoming: Incoming) {
        try {
            if (incoming.live) onEvent(incoming.event) else fromBefore(incoming.event)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "A word in the room could not be taken: ${e.javaClass.simpleName}", e)
            ReportTrail.thisRun.room(ReportTrail.Room.BAD_WORD)
        }
    }

    /**
     * A word a relay kept from before this phone came ([Incoming.live] false): it says nothing of who is in the room now,
     * so nobody is heard, followed or counted as an answer from it. Only who said they made the room and who said it is
     * over are taken, so that a room closed before this phone came ends at once, the close and the host's last words in
     * whatever order the relays hand them on (21 Nov 2026).
     */
    private fun fromBefore(event: NostrEvent) {
        val from = event.pubkey
        if (from == me || event.kind != TogetherMessage.KIND || from in sentOut) return
        val message = code.open(event.content)?.let { TogetherMessage.decodeFrom(it, from) } ?: return
        // A host's word as it left, handing the room on, holds as its close does: a phone whose connection was down at
        // that moment reads it in what the relays kept (RoomHandOn).
        if (message is TogetherMessage.Here && message.host != null) handOnHeard(from, message.host, message.leadSince)
        val over = when {
            message is TogetherMessage.Here && message.owner -> ends.owner(from)
            message == TogetherMessage.End -> from == ownerKey || ends.end(from)
            else -> false
        }
        if (over) endedBefore()
    }

    /** The room was closed before this phone came, or while it was away: ended here at once, with the line it deserves. */
    private fun endedBefore() {
        Log.i(TAG, "The room was closed before this phone heard it live")
        ReportTrail.thisRun.room(ReportTrail.Room.CLOSED_BY_HOST)
        closed(R.string.together_closed)
    }

    private fun onEvent(event: NostrEvent) {
        val from = event.pubkey
        if (from == me || event.kind != TogetherMessage.KIND || from in sentOut) return
        // Only a word its signer sealed: anyone who sees the room's sealed words on a public relay could sign one again
        // with a key of their own (TogetherMessage.decodeFrom).
        val message = code.open(event.content)?.let { TogetherMessage.decodeFrom(it, from) } ?: return
        // And each sealed word once, whatever event carries it again.
        if (!seals.take(event.content)) return
        val now = System.currentTimeMillis()
        // Someone who left for good said so: off the list at once, and nothing more of theirs is read.
        if (message == TogetherMessage.Bye) {
            left(from)
            return
        }
        // A newcomer gets where the leader is and its queue at once, not at the next beat two
        // seconds on: a guest who joined waited seconds for the song (his ask of 26 Oct 2026). The
        // queue goes again once the player is done with it, as after any change.
        if (from !in heard && leading) {
            sendQueue()
            sendState()
            queueSoon()
        }
        heard[from] = now
        heard.dropQuiet(now)
        handOn.heard(from, now)
        if (message is TogetherMessage.Here) {
            val newcomer = from !in said
            said[from] = message.name to message.owner
            handOn.said(from, message.takesRoom)
            // The host sees each newcomer come in, by the name they chose (his word of 21 Nov 2026: no sign on the S25U
            // that the Pixel was joining). Once: a guest heard again after a while away comes in again.
            // InterTune's developer's phone, by the proof in its Here (DevBadge): checked off the main thread, once a proof;
            // the host's line for a newcomer waits for the check to name him so.
            val joined = owner && newcomer && !message.owner
            val proof = message.dev
            if (devs.due(from, proof) && proof != null) {
                scope.launch {
                    val ok = withContext(Dispatchers.Default) { DevBadge.verifies(proof, code.tag, from) }
                    devs.answer(from, proof, ok)
                    Log.i(TAG, if (ok) "${from.take(8)} is InterTune's developer" else "${from.take(8)} sent a developer proof that fails")
                    publish()
                    if (joined) joinedLine(message.name, ok)
                }
            } else if (joined) {
                joinedLine(message.name, devs.proven(from))
            }
            if (message.owner && ownerKey == null) {
                ownerKey = from
                Log.i(TAG, "The room was made by ${from.take(8)}")
            }
            // The one who made the room had already said it is over (its close came first, before this phone knew it).
            if (message.owner && ends.owner(from)) {
                endedBefore()
                return
            }
            // Leaving, this phone handed the room to the one that now says it hosts it: done, and this phone is out.
            if (message.owner && from == handOn.handing?.to) {
                handedAway(from)
                return
            }
            // The host is leaving and hands the room on: whoever it named hosts it, and is in control, once that phone
            // says it hosts it (RoomHandOn), whichever of the two words comes first. Before the owner's word on who leads,
            // which is then the new host's to say.
            if (message.host != null) handOnHeard(from, message.host, message.leadSince)
            else if (message.owner && from != ownerKey) settleHost()
            // The owner's word on who leads, never an older one after a newer one (LeadWord); a change is told here once.
            // Not in a word that hands the room on: who leads then is for the new host to say, once it hosts the room,
            // and until then the room plays on as it was.
            if (from == ownerKey && message.host == null && message.lead != null && leadWord.take(message.leadSince)) {
                if (message.lead != leaderKey) lead(message.lead)
                inControl(message.lead)
            }
            // A newcomer hears at once who leads, instead of at the owner's next Here.
            if (owner && greeted.add(from)) sayHere()
            // The owner's room settings, from its own word alone (RoomSettings): none from an owner on an older build.
            if (!owner && from == ownerKey && message.settings != _status.value.settings) {
                _status.value = _status.value.copy(settings = message.settings)
                Log.i(TAG, "The owner's room settings: ${message.settings ?: "none said"}")
            }
            // Whether this phone, given control, takes a guest's change to the queue (Arrange): an older build drops it.
            if (message.arranges) arrangers += from else arrangers -= from
            // And a guest's moves while the host lets everyone control the music (Control): a build before 27 Nov drops them.
            if (message.controls) controllers += from else controllers -= from
        }
        said.keys.retainAll(heard.keys)
        handOn.keep(heard.keys)
        if (from == ownerKey) ownerHeardAt = now
        if (owner && message is TogetherMessage.State && from != leaderKey) answerStray(now)
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
                // A word with an older clock than the newest is a late one: the relays do not keep order.
                if (from == leaderKey && hostLine.hear(message)) {
                    lastState = message
                    lastStateAt = now
                    wake.trySend(Unit)
                    // The leader said yes to this phone's song: back with the room, where it already is.
                    if (ownWay && message.songId == proposedSongId) {
                        Log.i(TAG, "The leader plays the song proposed from here: back with the room")
                        proposedSongId = null
                        catchUp()
                    }
                    // A song from the files of the one in control that isn't on YouTube Music: never shown by its made-up id,
                    // and said once as it starts, unless this phone plays on its own (holdForPhoneSong).
                    val onlyThere = fromThisPhone(null, message.songId)
                    if (onlyThere && !_status.value.songOnlyThere && !ownWay) {
                        val who = said[from]?.first?.ifBlank { null } ?: service.getString(R.string.together_someone)
                        Toaster.show(service, service.getString(R.string.together_song_only_there, who), Toast.LENGTH_LONG)
                    }
                    _status.value = _status.value.copy(
                        hostSong = listOf(message.title.ifBlank { if (onlyThere) "" else message.songId }, message.artist)
                            .filter { it.isNotBlank() }.joinToString(" · ").ifEmpty { null },
                        hostSongId = message.songId,
                        waitingForHost = false,
                        songOnlyThere = onlyThere,
                        songMatched = message.fromFile && !onlyThere,
                    )
                }
            }
            is TogetherMessage.Queue -> if (!leading) {
                // Before the owner's word on who leads, whoever sends the playback does, its queue
                // as much as where it is: a newcomer is sent both at once, the queue often first,
                // and it was thrown away, so the guest waited for it to come again.
                if (leaderKey == null) lead(from)
                if (from == leaderKey) {
                    roomQueue = message
                    if (_status.value.roomShuffle != message.shuffle) _status.value = _status.value.copy(roomShuffle = message.shuffle)
                    // A song that waits for this queue (toLeadersSong) is taken at once, not at the
                    // next look, two seconds away in a paused room.
                    wake.trySend(Unit)
                    if (!ownWay) {
                        takeRepeat(message.repeat)
                        matchQueue()
                    }
                }
            }
            // A guest's move or removal in the room's queue, while the owner lets everyone change it (RoomSettings).
            is TogetherMessage.Arrange -> if (leading && _status.value.settings?.everyoneControls == true) arranged(message, from)
            // A guest's play, pause, skip, seek, shuffle, repeat or song, while the host lets everyone control the music.
            is TogetherMessage.Control -> if (leading && message.to == me && _status.value.settings?.everyoneControls == true) controlled(message, from)
            is TogetherMessage.Propose -> if (leading && message.songs.isNotEmpty()) {
                // It waits for Play it or Not now, after any other guest's (ProposalLine).
                val proposal = Proposal(message.songs, message.positionMs, message.by, now, from)
                _status.value = _status.value.copy(proposals = ProposalLine.add(_status.value.proposals, proposal))
                // And as a notification while InterTune is not in front.
                syncNotice()
                // By key, never by the name they chose: see RoomHealth.
                Log.i(TAG, "${from.take(8)} proposed ${message.songs.first().id} and ${message.songs.size - 1} after it")
            }
            is TogetherMessage.NotNow -> if (!leading && message.to == me && from == leaderKey && message.songId == proposedSongId) {
                // The one in control said Not now: Propose is back, for this song later or another one.
                proposedSongId = null
                _status.value = _status.value.copy(proposed = false)
                val who = said[from]?.first?.ifBlank { null } ?: service.getString(R.string.together_someone)
                Toaster.show(service, service.getString(R.string.together_proposal_not_now, who))
                Log.i(TAG, "The leader said not now to ${message.songId}")
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
                if (message.batch.isBlank() || message.count <= 1) {
                    putSuggested(listOf(song), message.atEnd, message.by, from)
                } else {
                    // One action's songs, sorted by their place whatever order the relays brought them in.
                    val key = "$from/${message.batch}"
                    val first = suggestions.isNew(key)
                    val whole = suggestions.add(key, message.index, message.count, song)
                    if (whole != null) {
                        putSuggested(whole, message.atEnd, message.by, from)
                    } else if (first) {
                        scope.launch {
                            delay(SUGGEST_WAIT_MS)
                            suggestions.flush(key)?.let { putSuggested(it, message.atEnd, message.by, from) }
                        }
                    }
                }
            }
            is TogetherMessage.Kick -> if (from == ownerKey && message.to == me) {
                Log.i(TAG, "Sent out of the room by its owner")
                ReportTrail.thisRun.room(ReportTrail.Room.SENT_OUT)
                closed(R.string.together_kicked)
                return
            }
            is TogetherMessage.Move -> if (from == ownerKey && message.to == me) {
                val next = PairSeal.open(secret, from, message.sealedCode)?.let(RoomCode::parse)
                if (next != null && next != code) {
                    Log.i(TAG, "The owner moved the room to a new code")
                    ReportTrail.thisRun.room(ReportTrail.Room.MOVED)
                    // The owner is in control in the new room: said here, where the names are known (kick).
                    inControl(from)
                    service.moveTogether(next)
                    return
                }
            }
            TogetherMessage.End -> if (from == ownerKey || ends.end(from)) {
                Log.i(TAG, "The owner closed the room")
                ReportTrail.thisRun.room(ReportTrail.Room.CLOSED_BY_HOST)
                closed(R.string.together_closed)
                return
            }
            is TogetherMessage.Here, TogetherMessage.Bye -> Unit
        }
        // The room answered: a live word from whoever made it or whoever leads it. Only then is a guest that joined now
        // in the room (JoinCheck).
        if (answeredAt == 0L && !owner && (from == ownerKey || from == leaderKey)) answered(now)
        publish()
    }

    /** The room's first live answer to this guest: see [joiningNow]. */
    private fun answered(now: Long) {
        answeredAt = now
        if (joiningNow) Toaster.show(service, R.string.together_youre_in)
        Log.i(TAG, "The room answered after ${now - startedAt} ms")
        ReportTrail.thisRun.roomAnswered(now - startedAt)
    }

    /** Nobody answered a guest that joined now: the room was closed, its host's phone is off, or the code is wrong. */
    private fun nobodyAnswered(lookedMs: Long) {
        Log.i(TAG, "Nobody answered in the room in ${lookedMs / 1000} s with a relay up: closed, the host away, or a wrong code")
        ReportTrail.thisRun.room(ReportTrail.Room.NOBODY)
        closed(R.string.together_nobody)
    }

    /**
     * Something in the room's own work failed ([failure]): this phone leaves the room with a line, rather than going on
     * half working or taking the app down, and the failure goes into the log and Report a problem's steps.
     */
    private fun failed(e: Throwable) {
        if (e is CancellationException) return
        Log.e(TAG, "The room failed here and was left: ${e.javaClass.simpleName}", e)
        ReportTrail.thisRun.room(ReportTrail.Room.FAILED)
        // Off the coroutine that failed: leaving cancels the scope it ran in.
        Handler(Looper.getMainLooper()).post {
            if (service.together.value === this) closed(R.string.together_failed)
        }
    }

    /** Who leads now is the phone with [key]: everyone follows it from here on, this phone included unless it is the one. */
    private fun lead(key: String) {
        if (key == leaderKey && lastState != null) return
        Log.i(TAG, "The lead is with ${if (key == me) "this phone" else key.take(8)}")
        if (key != leaderKey) proposalsAfterLeadMoved()
        leaderKey = key
        // Always later than the last one, so a clock set back a little never makes the owner's new word look old (LeadWord).
        leadGivenAt = maxOf(System.currentTimeMillis(), leadGivenAt + 1)
        gap = ClockGap()
        pingsToLeader = 0
        lastState = null
        hostLine.clear()
        loadingSong = null
        matchedQueue = null
        lock.reset()
        if (key == me) {
            // Its own playing is the room's now: no more nudging, and its own repeat mode.
            ownWay = false
            pausedHere = false
            ownSpeed()
            _status.value = _status.value.copy(driftMs = null, ownWay = false, waitingForHost = false, locked = false, pausedHere = false)
            sendState()
            sendQueue()
        } else {
            _status.value = _status.value.copy(driftMs = null, waitingForHost = true, locked = false)
        }
        publish()
        wake.trySend(Unit)
    }

    private fun publish() {
        val members = heard.keys.map { key ->
            val (name, saidOwner) = said[key] ?: ("" to false)
            Member(key, name, owner = saidOwner || key == ownerKey, leader = key == leaderKey, dev = devs.proven(key))
        }.sortedWith(compareByDescending<Member> { it.owner }.thenByDescending { it.leader })
        _status.value = _status.value.copy(
            others = heard.size,
            members = members,
            leading = leading,
            leaderName = leaderKey?.takeIf { it != me }?.let { said[it]?.first }?.ifBlank { null },
            leaderArranges = leaderKey.let { it != null && (it == me || it in arrangers) },
            leaderControls = leaderKey.let { it != null && (it == me || it in controllers) },
            leaveChoice = leaveChoice(),
        )
    }

    /** The owner hands the lead to [member]; it stays the owner. Everyone is told, the owner too (inControl). Not while it hands the room on. */
    fun handOver(member: Member) {
        if (!owner || handOn.handing != null || member.key == me || member.key !in heard || !handOn.canLead(member.key)) return
        lead(member.key)
        sayHere()
        inControl(member.key)
    }

    /** The owner takes the lead back, and everyone is told. Not while it hands the room on. */
    fun takeBack() {
        if (!owner || leading || handOn.handing != null) return
        lead(me)
        sayHere()
        inControl(me)
    }

    /** Who said in their Here that, given control, they take a guest's change to the queue (Arrange). */
    private val arrangers = mutableSetOf<String>()

    /** Who said in their Here that, given control, they take a guest's moves (Control): see [guestControls]. */
    private val controllers = mutableSetOf<String>()

    /** The order a guest's moves are taken in, by when the guest made them: see [ControlOrder]. */
    private val controlOrder = ControlOrder()

    /**
     * A guest that may change the room's queue ([guestArranges]) moved [songId] in its own copy, to come right after
     * [after], the song playing when null: the person in control moves it there too (arranged), and its next queue
     * brings it to everyone, this guest included.
     */
    fun arrangeMoved(songId: String, after: String?) {
        if (!guestArranges(_status.value)) return
        send(TogetherMessage.Arrange(songId, after = after))
        Log.i(TAG, "Moved $songId in the room's queue")
    }

    /** A guest that may change the room's queue took [songId] out of its own copy: the person in control takes it out too. */
    fun arrangeRemoved(songId: String) {
        if (!guestArranges(_status.value)) return
        send(TogetherMessage.Arrange(songId, remove = true))
        Log.i(TAG, "Took $songId out of the room's queue")
    }

    /**
     * A guest's change to the room's queue, made by the person in control as its own drag or swipe in the queue sheet is
     * (Queue.kt), the queue board and the player alike, so that its next queue brings it to everyone (queueSoon). Never
     * the song playing, which a guest does not move or take out (RoomArrange); a song or a place not in the queue here
     * changes nothing.
     */
    private fun arranged(message: TogetherMessage.Arrange, from: String) {
        val player = service.player
        val current = player.currentMediaItemIndex
        val ids = (0 until player.mediaItemCount).map { player.getMediaItemAt(it).mediaId }
        val at = RoomArrange.placeOf(ids, message.songId, current) ?: return
        if (message.remove) {
            if (!service.queueBoard.removeCurrentQueueSong(at)) return
            player.removeMediaItem(at)
        } else {
            val to = RoomArrange.moveTo(ids, at, message.after, current) ?: return
            if (to == at) return
            service.queueBoard.moveSong(at, to)
            player.moveMediaItem(at, to)
            // Where the guest put it, the adaptive queue leaves it, as after a drag here.
            service.noteMovedByHand(message.songId)
        }
        // By key, never by the name they chose: see RoomHealth.
        Log.i(TAG, "${from.take(8)} ${if (message.remove) "took out" else "moved"} ${message.songId} in the queue")
        val by = said[from]?.first?.ifBlank { null } ?: service.getString(R.string.together_someone)
        Toaster.show(service, service.getString(R.string.together_arranged, by))
    }

    /** The owner's room settings are [settings] now: everyone hears them at once, a newcomer as it comes. */
    private fun ownSettings(settings: RoomSettings) {
        if (settings == _status.value.settings) return
        _status.value = _status.value.copy(settings = settings)
        Log.i(TAG, "Room settings: $settings")
        sayHere()
    }

    /**
     * A move in the room's song while only the person in control may move in it ([seekHeld]): this phone stays with the
     * room, where before it went its own way, and the next look puts it back where the room is. The seek bar does not
     * move at all then (RoomOnBar.kt); this is for the other ways in: the notification, a watch, a lyric line, the seek
     * buttons. [movedMs] is how far it went: the app's own seeks in place, as an effect is switched on, say nothing.
     */
    private fun seekHeldHere(movedMs: Long) {
        if (System.currentTimeMillis() < ownCommandUntil) return
        lock.reset()
        wake.trySend(Unit)
        if (movedMs < HELD_SAID_MS) return
        Log.i(TAG, "A move in the song held: back to where the room is")
        Toaster.show(service, seekHeldLine(service, _status.value))
    }

    /**
     * The owner sends [member] out: their phone leaves, and everyone else moves to a new code that
     * each is told alone, so the old one leads nowhere. The lead comes back to the owner with it.
     */
    fun kick(member: Member) {
        if (!owner || member.key == me || handOn.handing != null) return
        sentOut += member.key
        send(TogetherMessage.Kick(member.key))
        val next = RoomCode.generate()
        heard.keys.filter { it != member.key && it !in sentOut }.forEach { key ->
            PairSeal.seal(secret, key, next.raw)?.let { send(TogetherMessage.Move(key, it)) }
        }
        Log.i(TAG, "Sent ${member.id} out; the room moves to a new code")
        // The new room starts with the owner in control (new keys for everyone): said with the rest when someone else had it.
        val removed = if (leading) R.string.together_removed else R.string.together_removed_control
        Toaster.show(service, service.getString(removed, member.name.ifBlank { member.id }), Toast.LENGTH_LONG)
        // Long enough for the messages to leave before this session closes with the old code.
        scope.launch {
            delay(MOVE_AFTER_MS)
            service.moveTogether(next)
        }
    }

    /**
     * Leave; the owner's leaving this way closes the room for everyone (or it hands the room on: [handOnAndLeave]). A guest
     * says goodbye first (Bye): it said nothing before, and a host leaving just after it could hand the room to it.
     */
    fun leave() {
        if (!owner) {
            sayBye()
            service.stopTogether()
            return
        }
        send(TogetherMessage.End)
        scope.launch {
            delay(END_AFTER_MS)
            service.stopTogether()
        }
    }

    /** Who keeps track of who could be handed the room, and to whom it was handed: see [RoomHandOn]. */
    private val handOn = RoomHandOn()

    /** Who gets the room if this phone, its host, leaves now (RoomHandOn): null for nobody, and for a guest. */
    private fun heirKey(): String? = if (owner) handOn.heir(me, leaderKey, heard, System.currentTimeMillis()) else null

    /**
     * What the host's Leave offers (RoomLeaveDialog): who would get the room, or why nobody can. Closing alone without the
     * flag. Kept in the status at every look (Status.leaveChoice), so that the dialog says it as it is now.
     */
    fun leaveChoice(): RoomLeaveChoice {
        if (!Unreleased.ROOM_HAND_ON) return RoomLeaveChoice(heir = null, olderOnly = false)
        val heir = heirKey()
        return RoomLeaveChoice(
            heir = heir?.let(::nameOf),
            olderOnly = heir == null && owner && handOn.olderOnly(me, heard, System.currentTimeMillis()),
            heirKey = heir,
        )
    }

    /**
     * The host leaves and the room goes on (his find of 23 Nov 2026: "there's no way to leave the room without closing
     * it"). The room goes to whoever gets it (RoomHandOn), who hosts it from then on and is in control: [to], the phone the
     * Leave named, while it still gets it. The word is the host's own Here, naming that phone as the one in control, which
     * every build takes from its host, and as the host now, which builds from 24 Nov read, newer than its last word on who
     * leads ([handOnWord]). Said now and every couple of seconds until that phone says it hosts the room, and then this
     * phone is out of it (handedAway): said once, it was lost on a phone whose connection dropped or that was restarting,
     * and the room was left with a host that never hosted (review of 0.13, 26 Nov 2026). False when nobody here can take
     * it, or when the Leave named a phone that no longer gets it: the Leave then says who does (Status.leaveChoice).
     */
    fun handOnAndLeave(to: String? = null): Boolean {
        if (!Unreleased.ROOM_HAND_ON) return false
        if (handOn.handing != null) return true
        val heir = heirKey() ?: return false
        if (to != null && to != heir) return false
        handTo(heir)
        return true
    }

    /** The host's word handing the room to [handing]'s phone as it leaves: see [handOnAndLeave]. */
    private fun handOnWord(handing: RoomHandOn.Handing) = TogetherMessage.Here(
        name, owner = true, lead = handing.to, leadSince = handing.since,
        settings = _status.value.settings, arranges = true, dev = devProof, takesRoom = true, host = handing.to,
        controls = true,
    )

    /** Says the word again while this phone hands the room on, and passes over a phone that does not take it: see [handTo]. */
    private var handingJob: Job? = null

    /**
     * This phone, the host, hands the room to [to] as it leaves: its word now and every couple of seconds (sayHere) until
     * that phone says it hosts it (handedAway). Not taken within [RoomHandOn.ANSWER_MS]: the next one ([handOnNext]).
     */
    private fun handTo(to: String) {
        val handing = handOn.handTo(to, System.currentTimeMillis(), after = leadGivenAt)
        // By key, never by the name they chose: see RoomHealth.
        Log.i(TAG, "Handing the room to ${to.take(8)}, until it says it hosts it")
        _status.value = _status.value.copy(handingTo = nameOf(to))
        // This phone is on its way out: a service that stops meanwhile never brings it back as the room's host.
        service.roomLeaving(code)
        handingJob?.cancel()
        handingJob = scope.launch {
            while (handOn.handing === handing) {
                sayHere()
                delay(TogetherMessage.HEARTBEAT_MS)
                if (handOn.handing === handing && handOn.unanswered(System.currentTimeMillis())) {
                    Log.i(TAG, "${to.take(8)} did not take the room in ${RoomHandOn.ANSWER_MS / 1000} s")
                    handOnNext()
                }
            }
        }
    }

    /**
     * The phone this one handed the room to did not take it, or left: the next one gets it, and with nobody left the room
     * is closed for everyone, as the host's other way out does. It is never left with a host that isn't there.
     */
    private fun handOnNext() {
        handOn.passOver()
        heirKey()?.let { next ->
            handTo(next)
            return
        }
        Log.i(TAG, "Nobody took the room as its host left: closed for everyone")
        ReportTrail.thisRun.room(ReportTrail.Room.NOBODY_TOOK)
        _status.value = _status.value.copy(handingTo = null)
        Toaster.show(service, R.string.together_nobody_took, Toast.LENGTH_LONG)
        leave()
    }

    /** The phone this one, leaving, handed the room to says it hosts it now: the hand-on is done, and this phone is out. */
    private fun handedAway(to: String) {
        handingJob?.cancel()
        // By key, never by the name they chose: see RoomHealth.
        Log.i(TAG, "Left the room: ${to.take(8)} hosts it now")
        ReportTrail.thisRun.room(ReportTrail.Room.HANDED_ON_HERE)
        Toaster.show(service, service.getString(R.string.together_hosts_now, nameOf(to)), Toast.LENGTH_LONG)
        service.stopTogether()
    }

    /**
     * [key] said it left the room for good (TogetherMessage.Bye): off the list at once, and nothing more of it is read (its
     * key went with the room). A host leaving in the 20 s after it went could hand the room to it (review of 0.13, 26 Nov
     * 2026); a host handing the room to it as it went hands it to the next one at once.
     */
    private fun left(key: String) {
        Log.i(TAG, "${key.take(8)} left the room")
        sentOut += key
        heard.remove(key)
        said.remove(key)
        handOn.keep(heard.keys)
        if (handOn.handing?.to == key) handOnNext()
        publish()
    }

    /** Whether this phone, as host, may give [member] control (RoomHandOn.canLead). */
    fun canGiveControl(member: Member): Boolean = handOn.canLead(member.key)

    /**
     * [from] said, in its word given at [since], that the room is [to]'s now (RoomHandOn): taken once [from] is the host,
     * in whatever order, and once [to] says it hosts the room ([settleHost]).
     */
    private fun handOnHeard(from: String, to: String, since: Long?) {
        handOn.handed(from, to, since)
        settleHost()
    }

    /**
     * Whose the room is now, by the words handing it on and who says they host it (RoomHandOn.hostAfter). A host that
     * leaves is still this phone's host until the phone it named says it hosts the room: when that one never does, the
     * host's next word, for another phone or the close, is still taken. Taken at once by the phone named.
     */
    private fun settleHost() {
        val was = ownerKey ?: return
        val host = handOn.hostAfter(was, me) { key -> said[key]?.second == true }
        if (host != was) handedOn(was, host)
    }

    /**
     * The host, [was], left and handed the room to [host] (RoomHandOn): that phone hosts it from now on and is in control,
     * and its word is the one this phone takes, on its own clock. The one that left is gone for good with its key (it
     * forgot the room), so nothing more of it is read. Everyone is told once, the change of control in the same line.
     */
    private fun handedOn(was: String, host: String) {
        Log.i(TAG, "The host left and handed the room to ${if (host == me) "this phone" else host.take(8)}")
        ownerKey = host
        ownerHeardAt = System.currentTimeMillis()
        leadWord = LeadWord()
        sentOut += was
        heard.remove(was)
        said.remove(was)
        // Told with the host's line, not as a change of control of its own (ControlNews).
        news.now(host)
        service.roomLead(code, host)
        if (host == me) {
            becomeHost()
        } else {
            ReportTrail.thisRun.room(ReportTrail.Room.HANDED_ON)
            if (host != leaderKey) lead(host)
            Toaster.show(service, service.getString(R.string.together_hosts_now, nameOf(host)), Toast.LENGTH_LONG)
        }
        publish()
    }

    /**
     * The host handed the room to this phone as it left (RoomHandOn): this phone hosts it from now on, with all a host has
     * (its room settings, Choose the music, giving control, sending someone out, closing the room or handing it on), and
     * is in control. It comes back to it as its host after a restart (RoomMemory.hosted).
     */
    private fun becomeHost() {
        ReportTrail.thisRun.room(ReportTrail.Room.HANDED_HERE)
        owner = true
        handOn.handedHere = true
        ownerKey = me
        _status.value = _status.value.copy(hostAway = false)
        backWithRoom()
        if (!leading) lead(me)
        // Its own room settings from now on, kept in its settings as for a room made here (start), said at once as they change.
        scope.launch {
            service.dataStore.data.collect { prefs ->
                ownSettings(RoomSettings(everyoneControls = RoomSettings.kept(prefs[TogetherEveryoneControlsKey], prefs[TogetherEveryoneSeeksKey], prefs[TogetherEveryoneArrangesKey])))
            }
        }
        sayHere()
        service.roomHosted(code)
        Toaster.show(service, R.string.together_host_yours, Toast.LENGTH_LONG)
    }

    /**
     * Handed the room while paused here or on its own way: back where the room is first, so that the others carry on with
     * the room's music and not this phone's pause or song (review of 0.13, 26 Nov 2026: in control at once, it sent its
     * player as it stood, and the room paused or changed song). The room's song when this phone's queue holds it; a song of
     * its own in place of the room's stays, for everyone now, as when the host gives control to a phone on its own way.
     */
    private fun backWithRoom() {
        val room = lastState ?: return
        if (leading || (!ownWay && !pausedHere)) return
        val player = service.exoPlayer
        val index = if (player.currentMediaItem?.mediaId == room.songId) player.currentMediaItemIndex else indexOf(player, room.songId)
        if (index == null) return
        Log.i(TAG, "Handed the room while ${if (ownWay) "on its own way" else "paused here"}: back where the room is first")
        mine(SONG_SWITCH_MS)
        player.seekTo(index, (leaderAt(room, System.currentTimeMillis()) + delayMs).coerceAtLeast(0))
        player.playWhenReady = room.playing
    }

    private fun closed(@StringRes why: Int) {
        Toaster.show(service, why, Toast.LENGTH_LONG)
        service.stopTogether()
    }

    /** A follower's song for the leader to play next. */
    fun suggest(song: MediaMetadata) = suggestAll(listOf(song), atEnd = false)

    /**
     * A follower's songs for the leader: in [songs]' order after the song playing, or at the end of its queue when
     * [atEnd]. One batch, each message with its place, so a newer host sorts them; an older host takes them as they
     * arrive (and puts an "end" next), which is why the "next" ones are sent last-first (RoomPlayNext.sendOrder).
     */
    fun suggestAll(songs: List<MediaMetadata>, atEnd: Boolean) {
        // This phone's own songs: they teach it as ever when the room plays them (WhoAdded).
        whoAdded.mine(songs.map { it.id })
        val batch = java.util.UUID.randomUUID().toString().take(8)
        RoomPlayNext.sendOrder(songs, atEnd).forEach { (i, song) ->
            send(
                TogetherMessage.Suggest(
                    song.id, song.title, song.artists.joinToString { it.name },
                    TogetherMessage.thumbnailFor(song.thumbnailUrl), name,
                    atEnd = atEnd, batch = if (songs.size > 1) batch else "", index = i, count = songs.size,
                )
            )
        }
        Log.i(TAG, "Suggested ${songs.size} songs to the room (${if (atEnd) "end" else "next"})")
    }

    private val suggestions = RoomPlayNext.Collector<MediaMetadata>()

    /**
     * The leader puts what a guest added in its queue, in one go, and says so once. [by] is the name the guest chose, [from]
     * its key in the room: the guest's songs teach this phone nothing (WhoAdded).
     */
    private fun putSuggested(songs: List<MediaMetadata>, atEnd: Boolean, by: String, from: String) {
        if (songs.isEmpty()) return
        songs.forEach { whoAdded.put(it.id, from) }
        val items = songs.map { it.toMediaItem() }
        if (atEnd) service.enqueueEnd(items) else service.enqueueNext(items)
        Log.i(TAG, "A guest added ${songs.size} songs: ${if (atEnd) "end" else "next"} in the queue")
        _status.value = _status.value.copy(lastSuggestion = "${songs.first().title}, $by")
        val text = when {
            songs.size > 1 -> service.resources.getQuantityString(R.plurals.together_from_guest_many, songs.size, by, songs.size)
            atEnd -> service.getString(R.string.together_from_guest_end, by, songs.first().title)
            else -> service.getString(R.string.together_from_guest, by, songs.first().title)
        }
        Toaster.show(service, text, Toast.LENGTH_LONG)
    }

    /**
     * A guest playing on its own proposes what it plays to the leader (his ask of 17 Oct 2026): the
     * song, where it is in it, and the queue after it as this phone's own settings made it.
     */
    fun propose() {
        if (leading || !ownWay) return
        // Its own song first, or nothing: a song from a file on this phone is left out (ownQueue), and the leader could
        // not play it anyway. The sheet does not offer it then (canPropose).
        val songs = ownQueue().takeIf { it.isNotEmpty() && it.first().id == service.exoPlayer.currentMediaItem?.mediaId } ?: return
        proposedSongId = songs.first().id
        // The song this phone asks the room for is its own when the room plays it (WhoAdded).
        whoAdded.mine(listOf(songs.first().id))
        send(TogetherMessage.Propose(songs, service.exoPlayer.currentPosition, name))
        _status.value = _status.value.copy(proposed = true)
        Log.i(TAG, "Proposed ${songs.first().id} and ${songs.size - 1} after it to the leader")
    }

    /**
     * The leader's yes: the guest's song plays now, from where the guest is by now after a quick answer, so the guest
     * hears no jump when it comes back, and the room follows as it follows any song. The room's own queue goes on after
     * it. The songs after it in the guest's queue, which every proposal carries (an old guest's too), come only when the
     * one in control chose them, [withQueue]: the room was handed all of them unasked (#testers, 22 Nov 2026: "did it
     * send the whole queue?").
     */
    fun acceptProposal(withQueue: Boolean = false) {
        val proposal = _status.value.proposals.firstOrNull() ?: return
        _status.value = _status.value.copy(proposals = ProposalLine.without(_status.value.proposals, proposal))
        syncNotice()
        if (!leading) return
        playNow(ProposalLine.toPlay(proposal, withQueue), proposal.positionMs, proposal.receivedAt, proposal.from)
    }

    /**
     * [proposed] played now by the one in control, from [positionMs] in its first song as asked at [receivedAt]: a
     * proposal's yes, or a guest's songs where the host lets everyone control the music (controlled). [from]: the guest.
     */
    private fun playNow(proposed: List<TogetherMessage.Song>, positionMs: Long, receivedAt: Long, from: String) {
        // The guest's, or whoever its word names: a proposal someone made teaches this phone nothing (WhoAdded).
        whoAdded.came(proposed, from.ifEmpty { WhoAdded.SOMEONE })
        val songs = proposed.map(::metadataOf)
        val player = service.exoPlayer
        val playedBefore = player.currentMediaItem != null
        // Next in the queue, then straight to it: the room's queue stays after it. A player that holds nothing (a room back
        // after a restart, its saved queue not loaded until play is pressed) plays them as a queue of their own: put next
        // in a queue it had not loaded, they never played, and the guest stayed "Proposed" (review of 0.13, 26 Nov 2026).
        if (playedBefore) service.enqueueNext(songs.map { it.toMediaItem() })
        else service.playQueue(ListQueue(title = songs.first().title, items = songs), origin = PlayOrigin.EXTERNAL)
        Log.i(TAG, "Playing the song proposed, ${songs.first().id}, and ${songs.size - 1} after it")
        scope.launch {
            // The queue takes the songs a moment after it is asked to.
            val first = songs.first().id
            val index = withTimeoutOrNull(PROPOSAL_LOAD_MS) {
                var found: Int? = null
                while (found == null) {
                    found = proposedSongIndex(player, first, playedBefore)
                    if (found == null) delay(100)
                }
                found
            } ?: return@launch
            // From the top after a long wait for the answer (ProposalLine.startAt).
            player.seekTo(index, ProposalLine.startAt(positionMs, System.currentTimeMillis() - receivedAt))
            player.playWhenReady = true
        }
    }

    /** Where the song put in for a yes is: next after the song playing, or playing in a player that held nothing. */
    private fun proposedSongIndex(player: Player, id: String, playedBefore: Boolean): Int? {
        if (!playedBefore) return player.currentMediaItemIndex.takeIf { player.currentMediaItem?.mediaId == id }
        val next = player.nextMediaItemIndex
        return next.takeIf { it != C.INDEX_UNSET && player.getMediaItemAt(it).mediaId == id }
    }

    /**
     * The leader's no, or its putting it off: the guest plays on its own as before, and is told, so that its Propose comes
     * back (NotNow; a build before this one does not know the word and stays as it was). The next one waiting is asked.
     */
    fun dismissProposal() {
        val proposal = _status.value.proposals.firstOrNull() ?: return
        _status.value = _status.value.copy(proposals = ProposalLine.without(_status.value.proposals, proposal))
        if (leading && proposal.from.isNotEmpty()) send(TogetherMessage.NotNow(proposal.from, proposal.songs.first().id))
        syncNotice()
    }

    /** The proposal notification follows the line, for this phone while it is in control (ProposalLine.noticeStep). */
    private fun syncNotice() {
        val first = _status.value.proposals.firstOrNull()?.takeIf { leading }
        when (ProposalLine.noticeStep(first, notice.showing, notice.inFront())) {
            ProposalLine.NoticeStep.HIDE -> notice.hide()
            ProposalLine.NoticeStep.KEEP -> Unit
            ProposalLine.NoticeStep.SHOW -> if (first != null) notice.show(first)
        }
    }

    /**
     * Control moved to another phone. Proposals are for whoever is in control: the songs proposed to this phone can't be
     * played from here any more, and the one this phone proposed went to someone no longer in control, so Propose comes
     * back for the new one.
     */
    private fun proposalsAfterLeadMoved() {
        proposedSongId = null
        _status.value = _status.value.copy(proposals = emptyList(), proposed = false)
        syncNotice()
    }

    /**
     * Back with the leader after going one's own way: the next look puts the leader's song and place back. While this
     * phone casts, once the cast ends (GuestCast): nothing of the room's goes to a receiver.
     */
    fun catchUp() {
        if (!guestCast.catchUpNow(casting = !leading && service.castOn)) {
            _status.value = _status.value.copy(catchUpAfterCast = true)
            Log.i(TAG, "Catching up with the leader once the cast ends")
            return
        }
        ownWay = false
        pausedHere = false
        matchedQueue = null
        // The leader's song asked for afresh: one asked for before and never got was waited for at every look (CatchUp).
        loadingSong = null
        dataWait.reset()
        heldFor = null
        lock.reset()
        roomQueue?.let { takeRepeat(it.repeat) }
        // Stopped on an error while on its own, on the room's song: started again, or the room says it can't play here.
        val player = service.exoPlayer
        if (CatchUp.restarts(player.playbackState, player.currentMediaItem?.mediaId, lastState?.songId)) {
            Log.i(TAG, "Stopped on the leader's song: starting it again")
            player.prepare()
        }
        _status.value = _status.value.copy(ownWay = false, pausedHere = false, locked = false, proposed = false, catchUpAfterCast = false, tooSlow = false)
        Log.i(TAG, "Catching up with the leader")
        wake.trySend(Unit)
    }

    /**
     * A cast on this phone, at each look (GuestCast): a follower whose cast has begun goes its own way until it ends, and
     * one that went its own way for the cast alone catches up once the music is back on the phone. Back paused while the
     * room plays (a receiver lost or idle, or a cast stopped while paused there), it is paused here, as after a pause of
     * its own: play takes it to where the room is, and the phone does not start playing by itself (a cast that is lost
     * comes back paused on purpose, CastSession).
     */
    private fun castLook() {
        val now = service.castOn
        if (now != casting) {
            casting = now
            Log.i(TAG, if (now) "A cast from this phone has begun" else "The cast from this phone has ended")
        }
        when (guestCast.look(casting, following = !leading, ownWay = ownWay)) {
            GuestCast.Step.OWN_WAY -> {
                ownWay = true
                pausedHere = false
                lock.reset()
                ownSpeed()
                _status.value = _status.value.copy(ownWay = true, driftMs = null, locked = false, pausedHere = false)
                Log.i(TAG, "Casting: playing on its own until the cast ends")
            }
            GuestCast.Step.CATCH_UP -> {
                Log.i(TAG, "The cast has ended: back with the room")
                catchUp()
                val room = lastState
                if (room != null && PauseHere.after(pausedHere = false, playWhenReady = service.exoPlayer.playWhenReady, roomPlaying = room.playing) == true) {
                    pausedHere = true
                    _status.value = _status.value.copy(pausedHere = true)
                    Log.i(TAG, "Back from the cast paused: paused here; the room plays on")
                }
            }
            GuestCast.Step.NONE -> Unit
        }
        val status = _status.value
        if (status.casting != casting || status.catchUpAfterCast != guestCast.catchUpAfter) {
            _status.value = status.copy(casting = casting, catchUpAfterCast = guestCast.catchUpAfter)
        }
    }

    /**
     * The listener did something to the player that the room did not: another song, or a seek to another one (his word of
     * 26 Nov 2026, Spotify's Jam: guests listen, nobody goes their own way in a room). A guest the host lets control the
     * music asks it of the one in control, who makes it for everyone ([askRoom]); a guest who listens is put back where the
     * room is at the next look (follow) and told who controls the music. [pressed]: given by hand ([byHand]), not a change
     * the session may have made itself.
     */
    private fun listenerChose(what: String, pressed: Boolean = handTurn) {
        // A button pressed here is the listener's whatever the room did a moment before (byHand): only what the listener
        // did not ask for here falls in the room's window, the player's own moves made by the room (mine).
        if (!pressed && System.currentTimeMillis() < ownCommandUntil) return
        if (ownWay || lastState == null) return
        val player = service.exoPlayer
        // Only what the listener asked for goes to the room, never the app's own move (a song its content filter passes
        // over, one gone from YouTube): review of 27 Nov 2026.
        if (pressed && guestControls(_status.value)) {
            val song = player.currentMediaItem?.mediaId ?: return
            val to = leaderKey ?: return
            askRoom(TogetherMessage.Control(to, songId = song, positionMs = player.currentPosition.coerceAtLeast(0))) { it.songId == song }
            Log.i(TAG, "The listener chose $what: asked of the one in control")
            return
        }
        lock.reset()
        wake.trySend(Unit)
        sayHeld()
        Log.i(TAG, "The listener chose $what: held, back where the room is")
    }

    /**
     * A seek in the song by the listener, where the host lets everyone control the music ([guestControls]): asked of the one
     * in control, for everyone. A guest who listens has its seeks held before this (seekHeldHere).
     */
    private fun movedInSong(positionMs: Long) {
        if (!handTurn || ownWay || lastState == null || !guestControls(_status.value)) return
        val song = service.exoPlayer.currentMediaItem?.mediaId ?: return
        val to = leaderKey ?: return
        askRoom(TogetherMessage.Control(to, songId = song, positionMs = positionMs.coerceAtLeast(0))) { state ->
            state.songId == song && abs(state.positionMs - positionMs) < ASKED_NEAR_MS
        }
        Log.i(TAG, "A seek asked of the one in control")
    }

    /**
     * Sends [message], a move of this guest's, to the one in control, and holds this phone's player as the guest left it
     * until the room's word says it is made ([answered]), for [ASK_HOLD_MS] at most (follow). The room's earlier word
     * would otherwise put it back for a moment, and the guest hear its own skip undone and done again.
     */
    private fun askRoom(message: TogetherMessage.Control, answered: (TogetherMessage.State) -> Boolean) {
        send(message.copy(at = System.currentTimeMillis()))
        asked = answered
        askedUntil = System.currentTimeMillis() + ASK_HOLD_MS
        lock.reset()
    }

    /** A guest's move asked of the one in control, until the room's word says it is made: see [askRoom]. */
    private var asked: ((TogetherMessage.State) -> Boolean)? = null
    private var askedUntil = 0L

    /** When this phone last said who controls the music after a move of its own was held: see [sayHeld]. */
    private var heldSaidAt = 0L

    /** Who controls the music, said after a move of the listener's was held, once in a while (a notification's Next, a watch). */
    private fun sayHeld() {
        val now = System.currentTimeMillis()
        if (now - heldSaidAt < HELD_SAY_EVERY_MS) return
        heldSaidAt = now
        Toaster.show(service, controlHeldLine(service, _status.value))
    }

    /** Whether this phone only listens in the room now: see [listensOnly]. */
    val listens: Boolean get() = listensOnly(_status.value)

    /** Whether this phone, a guest, controls the music with everyone now: see [guestControls]. */
    val controls: Boolean get() = guestControls(_status.value)

    /**
     * Mute or unmute this phone, a guest who listens (RoomListen): its player plays on in step with the room either way,
     * so the sound comes back where the room is. Kept with the room's way, so that a room that comes back is muted still.
     */
    fun toggleMute() {
        if (leading) return
        muted = !muted
        Log.i(TAG, if (muted) "Muted here; in step with the room" else "Unmuted here")
        keepWay()
    }

    /** No mute where there is no mute button: in control, or the host lets everyone control the music. */
    private fun lookMute() {
        if (muted && !listensOnly(_status.value)) {
            muted = false
            Log.i(TAG, "Unmuted: this phone controls the music now")
        }
    }

    /**
     * Songs started by this guest, where the host lets everyone control the music ([guestControls]): a song tapped, or a
     * list or a radio from its first song, played now for everyone, as the one in control plays a proposal's yes.
     */
    fun playForRoom(songs: List<MediaMetadata>) {
        if (!guestControls(_status.value) || songs.isEmpty()) return
        val to = leaderKey ?: return
        val first = songs.first().id
        whoAdded.mine(songs.map { it.id })
        val list = songs.take(TogetherMessage.QUEUE_SONGS).map {
            TogetherMessage.Song(it.id, it.title, it.artists.joinToString { a -> a.name }, TogetherMessage.thumbnailFor(it.thumbnailUrl))
        }
        askRoom(TogetherMessage.Control(to, songs = list, positionMs = 0L)) { it.songId == first }
        Log.i(TAG, "Asked the one in control to play ${list.size} songs, $first first")
    }

    /** Shuffle or repeat changed by a guest who controls the music with everyone: made by the one in control. */
    fun askOrder(shuffle: Boolean? = null, repeat: Int? = null) {
        if (!guestControls(_status.value)) return
        val to = leaderKey ?: return
        send(TogetherMessage.Control(to, shuffle = shuffle, repeat = repeat, at = System.currentTimeMillis()))
        Log.i(TAG, "Asked the one in control for shuffle $shuffle, repeat $repeat")
    }

    /**
     * The one in control makes a guest's move for everyone, the host letting everyone control the music: play or pause, a
     * song of the queue or a place in it, shuffle and repeat, or songs to play now (as a proposal's yes, [playNow]).
     */
    private fun controlled(message: TogetherMessage.Control, from: String) {
        if (from in sentOut) return
        if (!controlOrder.take(from, message.at)) {
            Log.i(TAG, "${from.take(8)}'s older move came late: left")
            return
        }
        val player = service.exoPlayer
        message.shuffle?.let { if (player.shuffleModeEnabled != it) player.shuffleModeEnabled = it }
        message.repeat?.let { if (player.repeatMode != it) player.repeatMode = it }
        if (message.songs.isNotEmpty()) {
            playNow(message.songs, message.positionMs ?: 0L, System.currentTimeMillis(), from)
        } else message.songId?.let { song ->
            val index = if (player.currentMediaItem?.mediaId == song) player.currentMediaItemIndex else indexOf(player, song)
            if (index != null) player.seekTo(index, (message.positionMs ?: 0L).coerceAtLeast(0))
        }
        message.playing?.let { if (player.playWhenReady != it) player.playWhenReady = it }
        Log.i(TAG, "${from.take(8)} moved the music for everyone")
    }

    /**
     * What the player does from now to the end of this turn of the main loop is the listener's, by hand (26 Nov 2026): a
     * seek, a skip or a list asked of the app's player (MusicService.byHand: the screens, the notification, a headset, a
     * car, a watch). Theirs at once, however the room had moved this phone a moment before: a Next, a song tapped or a
     * seek in the seconds after the room moved it was taken for the room's own move and undone ([mine]'s window). The
     * player tells its listeners of a command within it, in this same turn. Main thread.
     */
    fun byHand() {
        if (handTurn) return
        handTurn = true
        // Ended before anything else the main thread does next, the room's own next look among it.
        Handler(Looper.getMainLooper()).postAtFrontOfQueue { handTurn = false }
    }

    /**
     * A pause or a play on a follower. The listener's own, by hand (the screens through PlayerConnection, the media
     * session through SessionPlayer: [byHand]), with control for everyone: the room plays or pauses ([askRoom]). A
     * guest who listens never gets here with a pause of its own: the screens' button and the session's pause are a mute
     * before it reaches the player (togglePlayPause, [askedPause]). Any other pause, the app's or the phone's (a swipe
     * from recents, the mini player swiped away, Recognise, a stall, an error, the sleep timer, a call, headphones out),
     * is a pause here while the room plays on: Play takes it to where the room is ([PauseHere]), and a Play of the
     * listener's own takes the mute with it (RoomListen.playUnmutes, review of 27 Nov 2026).
     */
    private fun listenerPlayed(playWhenReady: Boolean) {
        val room = lastState ?: return
        if (ownWay) return
        // The room's song is one from the files of the one in control, which nothing here can play (holdForPhoneSong): a
        // pause or a play here changes nothing of the room's.
        if (fromThisPhone(null, room.songId)) return
        if (handTurn && !pausedHere && playWhenReady != room.playing) {
            if (guestControls(_status.value)) {
                val to = leaderKey ?: return
                askRoom(TogetherMessage.Control(to, playing = playWhenReady)) { it.playing == playWhenReady }
                Log.i(TAG, if (playWhenReady) "Play asked of the one in control" else "Pause asked of the one in control")
                return
            }
            // A play while the room is paused: the next look pauses it again, with the room.
            if (playWhenReady) sayHeld()
        }
        val was = pausedHere
        pausedHere = PauseHere.after(pausedHere, playWhenReady, room.playing) ?: return
        if (RoomListen.playUnmutes(was, pausedHere, byHand = handTurn, muted = muted)) {
            muted = false
            Log.i(TAG, "Played again by hand after a pause here: the mute goes with it")
        }
        lock.reset()
        if (pausedHere) ownSpeed()
        _status.value = _status.value.copy(pausedHere = pausedHere, driftMs = null, locked = false)
        Log.i(TAG, if (pausedHere) "Paused here; the room plays on" else "Playing again: back to where the leader is")
    }

    /**
     * A pause asked through the media session (SessionPlayer.pause: the notification, a headset, a watch, a car), before
     * it reaches the player. A guest who listens mutes in its place, its player playing on in step (RoomListen.pauseMutes):
     * true, the pause goes no further. Anyone else pauses as asked, by hand ([byHand]): false.
     */
    fun askedPause(): Boolean {
        if (!leading && !service.castOn && RoomListen.pauseMutes(listensOnly(_status.value), lastState?.playing, pausedHere)) {
            toggleMute()
            return true
        }
        byHand()
        return false
    }

    /** At each look: whether nobody plays for the room ([nobodyPlays]), said in the status, and the mute where it has no button. */
    private fun lookAlone() {
        lookMute()
        // Nobody plays for the room: since 26 Nov 2026 a guest does not go its own way (RoomListen). Its player plays out what
        // it has of the room's queue, its controls a guest's still, and the room's sheet says so and offers Leave.
        val alone = nobodyPlays(System.currentTimeMillis())
        if (alone != _status.value.nobodyPlays) _status.value = _status.value.copy(nobodyPlays = alone)
    }

    /**
     * Nobody plays for the room as this phone knows it: whoever was in control has been quiet as long as follow waits for
     * it (HOST_TIMEOUT_MS), or, nobody heard playing in this session (a room that came back), the host is away. Not for a
     * guest that joined now and was never answered: that one is not in the room yet (JoinCheck).
     */
    private fun nobodyPlays(now: Long): Boolean {
        if (leading || lastState != null) return false
        if (lastStateAt > 0) return now - lastStateAt > HOST_TIMEOUT_MS
        return _status.value.hostAway && (!joiningNow || answeredAt > 0)
    }

    /**
     * How this phone listens in the room now, kept so that the room comes back with it (RoomWay): on its own way only as
     * paused here by the phone, muted, playing with it, or following it while it is paused.
     */
    fun way(): RoomWay = RoomWay.of(
        pausedHere = pausedHere,
        muted = muted,
        playing = service.exoPlayer.playWhenReady,
    )

    /** The way kept with the room as it changes (MusicService.roomWay), at the room's look; the service's end keeps it too. */
    private fun keepWay() {
        if (leading) return
        val now = way()
        if (now == keptWay) return
        keptWay = now
        service.roomWay(code, now)
    }

    /** What the session does to the player next is its own, not the listener's, for [forMs]. */
    private fun mine(forMs: Long = OWN_COMMAND_MS) {
        // Never shorter than a window already given: a jump just after a song change is part of it.
        ownCommandUntil = maxOf(ownCommandUntil, System.currentTimeMillis() + forMs)
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
            Log.i(TAG, "No playback from the leader for ${HOST_TIMEOUT_MS / 1000} s: playing on alone")
            lastState = null
            hostLine.clear()
            // Anyone else waits for whoever leads next. The owner keeps the one it gave control to and waits for its
            // playback: it takes control back only from a phone that has gone (watchLeader), never from one that is
            // there and plays nothing for a while.
            if (!owner) leaderKey = null
            ownSpeed()
            _status.value = _status.value.copy(waitingForHost = true, driftMs = null)
            return
        }
        // While this phone casts, nothing of the room's goes to a player (castLook): the phone's own is empty, and each of
        // the host's songs went to the receiver from its start.
        if (casting) return
        val player = service.exoPlayer
        // The host's next song: whatever failed before is behind.
        refusal.songIs(state.songId)
        if (_status.value.songUnavailable && refusal.refusedId == null) _status.value = _status.value.copy(songUnavailable = false)
        if (dataWait.songIs(state.songId) || (_status.value.tooSlow && !dataWait.tooSlow)) _status.value = _status.value.copy(tooSlow = false)
        // Marked paused here while its player plays: the play was missed, and it is back with the room.
        if (pausedHere && player.playWhenReady) listenerPlayed(true)
        // A move of this guest's asked of the one in control (askRoom): its player stays as the guest left it until the
        // room's word says it is made, or for ASK_HOLD_MS, so that the room's older word does not undo it meanwhile.
        asked?.let { answered ->
            if (System.currentTimeMillis() < askedUntil && !answered(state)) return
            asked = null
        }
        // Paused here by the listener: the room plays on, and play brings this phone back to it.
        if (ownWay || pausedHere) return
        // A song from the files of the one in control that isn't on YouTube Music: nothing here can play it.
        if (fromThisPhone(null, state.songId)) {
            holdForPhoneSong(player, state)
            return
        }
        heldFor = null
        // The room's song, and the one after it that the player reads ahead and moves on to by
        // itself as the leader does, are plays somebody started: each asked of YouTube once even
        // if this phone set it aside as gone, before anything here moves to it (RoomAsks).
        roomAsks.now(state.songId, roomQueue?.songs?.takeIf { it.firstOrNull()?.id == state.songId }?.getOrNull(1)?.id)
            .forEach(service::askedFor)
        val now = System.currentTimeMillis()
        if (player.currentMediaItem?.mediaId != state.songId) {
            toLeadersSong(player, state, now)
            return
        }
        loadingSong = null
        waitingFor = null
        if (refusal.staysOn(state.songId)) {
            // Failed here: stays on the host's song, not playing, one more try later (see HostSongRefusal).
            if (player.playbackState == Player.STATE_IDLE && refusal.retryDue(state.songId, now)) {
                Log.i(TAG, "The leader's ${state.songId} failed here: one more try")
                mine(SONG_SWITCH_MS)
                player.prepare()
            }
            if (player.playbackState != Player.STATE_READY) return
        }
        if (player.playbackState == Player.STATE_READY && refusal.refusedId != null) {
            refusal.played()
            _status.value = _status.value.copy(songUnavailable = false)
        }
        // Ended here on the leader's song while the leader is still in it (back in the room after this phone played it
        // to its end, or the leader went back in it): an ended player never plays again by itself, so a seek to the
        // leader's spot wakes it; near the end the leader's next song is on the way (Follow.wake).
        if (player.playbackState == Player.STATE_ENDED) {
            val spot = Follow.wake(leaderAt(state, now) + delayMs, player.duration.takeIf { it != C.TIME_UNSET }) ?: return
            Log.i(TAG, "Ended here while the leader is ${spot / 1000} s in: back to the leader's spot")
            seek(player, spot)
            if (player.playWhenReady != state.playing) player.playWhenReady = state.playing
            return
        }
        if (player.playbackState != Player.STATE_READY && player.playbackState != Player.STATE_BUFFERING) return
        if (arriving) arrive(player, state, now)
        if (roomQueue !== matchedQueue) matchQueue()
        if (player.playWhenReady != state.playing) {
            mine()
            player.playWhenReady = state.playing
        }
        if (now < settleUntil || player.playbackState != Player.STATE_READY) return
        // Before the first pong, where the leader is is a guess (leaderAt), and a pong within a
        // second puts it right. The leader's own delay is out of its word already; this phone's
        // is added, so that what comes out of each is in step.
        val target = leaderAt(state, now) + delayMs
        // Past the end the leader is moving on or starting the song again, and its word that it
        // did is on the way: a jump there would only land at the end.
        val length = player.duration
        if (length != C.TIME_UNSET && target >= length) return
        val local = player.currentPosition
        if (now - lastLoggedDrift >= 2_000) {
            lastLoggedDrift = now
            // Every two seconds: under a tag of its own, which a report leaves out for the line a minute.
            Log.i(SYNC_TAG, "drift ${target - local} ms, speed ${"%.3f".format(player.playbackParameters.speed)}, ${if (lock.locked) "locked" else "not locked"} (tolerance ${lock.toleranceMs} ms), clock gap ${gap.offsetMs} ms (round trip ${if (gap.known) gap.bestRoundTripMs else -1} ms)")
        }
        if (!state.playing) {
            // Paused, both stand on the leader's spot, so that they start again together: the
            // pause reaches a follower a moment late, and it had played on that far (235 ms on
            // the emulators, 17 Oct 2026).
            if (abs(target - local) > Follow.IN_STEP_MS) seek(player, target)
            // On the leader's spot it is in step, however far behind its line kept it while the room played: "too slow to
            // keep up" no longer holds, and stayed on after the pause (review of 0.13, 26 Nov 2026).
            if (dataWait.tooSlow) {
                Log.i(TAG, "On the leader's spot while the room is paused: no longer too slow")
                dataWait.reset()
            }
            _status.value = _status.value.copy(driftMs = target - local, locked = false, tooSlow = false)
            return
        }
        health.drift(target - local)
        when (val decision = lock.decide(target, local, state.speed, now)) {
            is Follow.Seek -> if (dataWait.jumps(decision.positionMs, local, player.bufferedPosition, now)) {
                waitingForData = false
                seek(player, decision.positionMs)
            } else {
                // Behind, and the leader's spot not come in yet: playing on while it does.
                if (!waitingForData) Log.i(TAG, "${(target - local) / 1000} s behind the leader: playing on until the song has come in that far")
                waitingForData = true
                speed(player, state.speed)
            }
            is Follow.Nudge -> {
                waitingForData = false
                speed(player, decision.speed)
            }
            is Follow.Steady -> {
                waitingForData = false
                speed(player, decision.speed)
            }
        }
        if (dataWait.tooSlow != _status.value.tooSlow) {
            Log.i(TAG, if (dataWait.tooSlow) "Too slow a line to keep up with the leader: playing on behind" else "The song has come in as far as the leader")
        }
        _status.value = _status.value.copy(driftMs = target - local, locked = lock.locked, tooSlow = dataWait.tooSlow)
    }

    /**
     * The player failed on [songId] and the service asks whether the room has it: true when it is
     * the host's song and this phone follows it, which then stays on it, nothing skipped and nothing
     * asked in a loop (issue #187: the player skipped the song it could not play, the room sent it
     * back, and round it went 287 times). False for anything else, which the service handles as ever.
     */
    fun holdsFailed(songId: String?): Boolean {
        val state = lastState ?: return false
        if (leading || ownWay || pausedHere || songId == null || songId != state.songId) return false
        refusal.songIs(state.songId)
        refusal.failed(songId, System.currentTimeMillis())
        // Nothing the session does next is the listener's.
        mine(SONG_SWITCH_MS)
        loadingSong = null
        _status.value = _status.value.copy(songUnavailable = true, driftMs = null, locked = false)
        Log.i(TAG, "The leader's $songId cannot be played here: staying on it until the leader's next song")
        return true
    }

    /** The room's song this phone stopped for, as nothing here can play it: see [holdForPhoneSong]. */
    private var heldFor: String? = null

    /**
     * The one in control plays a song from its files that isn't on YouTube Music (PhoneSongs): nothing here can play it,
     * and its made-up id is never asked for (asked for, it failed with no word, Ti-Die's "that song isnt working", 22 Nov
     * 2026). This phone stops, once and by the room's doing, the sheet says why, and the room's next song plays here as
     * any other. A play pressed meanwhile is the listener's: it plays on what it had.
     */
    private fun holdForPhoneSong(player: Player, state: TogetherMessage.State) {
        loadingSong = null
        waitingFor = null
        if (heldFor == state.songId) return
        heldFor = state.songId
        lock.reset()
        ownSpeed()
        _status.value = _status.value.copy(driftMs = null, locked = false)
        Log.i(TAG, "The leader plays a song from its phone that isn't on YouTube Music: nothing to play here until its next one")
        if (player.playWhenReady) {
            mine()
            player.playWhenReady = false
        }
    }

    /** Behind the leader past what has come in, playing on until it has: see Follow.jumpNow. */
    private var waitingForData = false

    /** How long it waits for that, at most: see DataWait. */
    private val dataWait = DataWait()

    /** The leader plays [state]'s song and this phone does not, yet. */
    private fun toLeadersSong(player: Player, state: TogetherMessage.State, now: Long) {
        // It moved on by itself at the end of a song, as the leader does at the same moment, and
        // the leader's word that it did is a moment behind: going back would cut the new song.
        if (state.songId == autoAdvancedFrom && now - autoAdvancedAt < AUTO_AHEAD_MS) return
        // Nor while the leader is where this phone's copy of that song had ended, or nearly: the leader's file can be a few
        // seconds longer than the YouTube Music version played here (PhoneSongs), and going back landed past its end, cut
        // the next song off and started it again, at the end of every such song (review of 0.13, 26 Nov 2026).
        if (state.songId == autoAdvancedFrom && state.songId == endedSong && endedAtMs > 0 &&
            leaderAt(state, now) + delayMs >= endedAtMs - Follow.END_GAP_MS
        ) return
        if (loadingSong == state.songId) return
        // The leader's song, which teaches this phone nothing unless it put it in itself (WhoAdded).
        whoAdded.playing(state.songId, leaderKey ?: WhoAdded.SOMEONE)
        // In this phone's queue already, its queue being the leader's: go to it there.
        indexOf(player, state.songId)?.let { index ->
            Log.i(TAG, "The leader plays ${state.songId}: in the queue here, going to it")
            loadingSong = state.songId
            matchedQueue = null
            lock.reset()
            mine(SONG_SWITCH_MS)
            // Straight to the leader's spot: from 0:00 the song was fetched twice, its start and
            // then the leader's spot, which on a slow line took seconds (his ask of 26 Oct 2026).
            // Not arriving: the spot is asked for already, and a second jump while it loads
            // would only throw that load away.
            arriving = false
            player.seekTo(index, (leaderAt(state, now) + delayMs).coerceAtLeast(0))
            return
        }
        // Else with the leader's queue from that song on, which it sends a moment after a change,
        // so it is waited for that long. Without it, the song alone, and the queue comes after.
        // Never a radio of this phone's own: that played other songs next than the leader did,
        // more of them, and nothing ever put it right (his find of 17 Oct 2026).
        val queue = roomQueue?.songs?.takeIf { it.firstOrNull()?.id == state.songId }
        // A song from the host's phone is never first in its queue (ownQueue leaves those out), so
        // no queue is waited for: waiting played the next song's start here (review 3, 18 Nov 2026).
        if (queue == null && !fromThisPhone(null, state.songId)) {
            if (waitingFor != state.songId) {
                waitingFor = state.songId
                waitingSince = now
            }
            if (now - waitingSince < QUEUE_WAIT_MS) return
        }
        loadingSong = state.songId
        matchedQueue = null
        lock.reset()
        // A queue starts its song at 0:00: follow sends it to the leader's spot while it loads.
        arriving = true
        Log.i(TAG, "The leader plays ${state.songId}: playing it here, ${if (queue != null) "with its queue" else "the queue to follow"}")
        // A queue resolves for a moment before its song arrives: all of that is the room's doing.
        // And the leader chose it, not this listener: learning must not read it as their pick.
        mine(SONG_SWITCH_MS)
        queue?.let { whoAdded.came(it, leaderKey ?: WhoAdded.SOMEONE) }
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
        // The room's songs, each whoever's its word says, in this phone's queue now: only its own teach it (WhoAdded).
        whoAdded.came(room.songs, leaderKey ?: WhoAdded.SOMEONE)
        val wanted = room.songs.drop(1).map { it.id }
        val here = upcoming(player)
        if (here == wanted) return
        Log.i(TAG, "The room's queue here: ${wanted.size} songs after this one, in place of ${here.size}")
        // The song goes on through the swap, so this is short: long enough for the queue to load,
        // not so long that a skip of the listener's own is taken for the room's.
        mine(QUEUE_SWAP_MS)
        service.playQueue(
            ListQueue(items = room.songs.map(::metadataOf)),
            // As the room wants it when the swap lands, not as it was when asked: a guest that caught up from a pause of
            // its own was paused again by it, and stayed paused here (CatchUp).
            playWhenReady = CatchUp.swapPlays(pausedHere, lastState?.playing, player.playWhenReady),
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

    /** The leader's song was asked for here and has not been ready yet: see [arrive]. */
    private var arriving = false

    private var lastLoggedDrift = 0L
    private var lastStateAt = 0L
    /** On its own way while this phone casts (GuestCast), back with the room by itself as the cast ends: never by its listener's doing. */
    private var ownWay = false

    private var ownCommandUntil = 0L

    /** A command given by hand in this turn of the main loop: see [byHand]. */
    private var handTurn = false

    /** The way last kept with the room: see [keepWay]. The one it starts with is kept as the room is (startTogether). */
    private var keptWay = this.way()

    /**
     * Where the leader is now in [state]'s song: by the leader's clock once the gap to it is known,
     * until then as Follow.guess has it. A guest that joins asks for the song before any pong is
     * back, so this spot is the one it fetches.
     */
    private fun leaderAt(state: TogetherMessage.State, now: Long): Long =
        if (gap.known) hostLine.target(state, gap.hostNow(now)) else Follow.guess(state, lastStateAt, now)

    /**
     * The leader's song has just come and is still loading here: if it starts well before the
     * leader's spot, off to that spot at once, before its start is fetched for nothing. Waiting
     * until it was ready fetched the song twice, from 0:00 and again from the leader's spot, which
     * on a slow line took seconds (his ask of 26 Oct 2026). Once ready, follow takes over.
     */
    private fun arrive(player: Player, state: TogetherMessage.State, now: Long) {
        if (player.playbackState == Player.STATE_READY) {
            arriving = false
            return
        }
        val target = leaderAt(state, now) + delayMs
        val spot = Follow.arrival(target, player.currentPosition, player.duration.takeIf { it != C.TIME_UNSET }) ?: return
        arriving = false
        Log.i(TAG, "Off to the leader's spot while the song loads, ${spot / 1000} s in")
        seek(player, spot)
    }

    private fun seek(player: Player, positionMs: Long) {
        mine()
        lock.sought()
        // A leader on Bluetooth near the start of a song says a spot before it.
        player.seekTo(positionMs.coerceAtLeast(0))
        settleUntil = System.currentTimeMillis() + SETTLE_MS
    }

    /** Varispeed on (Settings > Player and audio): this phone's own speed came with its pitch. */
    private fun linked() = Unreleased.VARISPEED && service.varispeedOn

    /**
     * The room's speed on this phone. With Varispeed on, the listener's pitch only went with their own speed, so
     * the room's speed plays in the song's own key and the nudges never move it: a guest at x1.25 heard the
     * host's songs four semitones high. Otherwise a pitch set by hand stays, as it always has.
     */
    private fun speed(player: Player, speed: Double) {
        val pitch = if (linked()) 1f else player.playbackParameters.pitch
        if (abs(player.playbackParameters.speed - speed) < 0.001 && player.playbackParameters.pitch == pitch) return
        player.playbackParameters = PlaybackParameters(speed.toFloat(), pitch)
    }

    /** This phone's own speed back, as before the room: with Varispeed on, with its pitch linked again. */
    private fun ownSpeed() {
        val player = service.exoPlayer
        if (!linked()) return speed(player, speedBefore.toDouble())
        val own = Varispeed.parameters(speedBefore, 0, linked = true)
        if (player.playbackParameters != own) player.playbackParameters = own
    }

    companion object {
        private const val TAG = "Together"

        /** How long a host waits for the rest of a guest's batch of songs before using what came. */
        private const val SUGGEST_WAIT_MS = 2500L

        /** The follower's look every two seconds: in the phone's log, not in a report. */
        private const val SYNC_TAG = "TogetherSync"
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
        /** How long the leader waits for a proposed song to load before it puts it at the guest's spot. */
        private const val PROPOSAL_LOAD_MS = 8_000L
        /** The leader's queue goes out this long after its last change, and every this many beats. */
        private const val QUEUE_SETTLE_MS = 600L
        private const val QUEUE_EVERY_BEATS = 15
        /** The owner leaves an old room this long after telling everyone where the new one is. */
        private const val MOVE_AFTER_MS = 1_500L
        /** A held move in the song this far or more says why it went back (seekHeldHere). */
        private const val HELD_SAID_MS = 1_000L

        /** How long a guest's move asked of the one in control holds its player as the guest left it at most (askRoom). */
        private const val ASK_HOLD_MS = 4_000L

        /** A seek asked of the one in control is made once the room's word is this near the place asked (movedInSong). */
        private const val ASKED_NEAR_MS = 3_000L

        /** Who controls the music is said this seldom at most after a move held (sayHeld). */
        private const val HELD_SAY_EVERY_MS = 5_000L
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

/** A local song's id: "LS" and eight more (SongEntity.generateSongId), never a YouTube id's eleven characters. */
private val LOCAL_SONG_ID = Regex("LS[A-Za-z0-9]{8}")

/**
 * Whether a song in the player is from a file on this phone: by what the app knows of it ([isLocal], from the item's
 * metadata), or failing that by its id. No other phone can play it, so a room never carries it in a queue.
 */
internal fun fromThisPhone(isLocal: Boolean?, mediaId: String): Boolean = isLocal == true || LOCAL_SONG_ID.matches(mediaId)

/** The room's look while it plays: four times a second, for a follower's sync. */
private const val LOOK_MS = 250L

/** And while it is paused there and here, or this phone leads. */
private const val IDLE_LOOK_MS = 2_000L

/**
 * How long a room waits between its looks (TogetherSession's follow and watchHost): a quarter of a second while the
 * room plays; two seconds while it is paused both there and here, or while this phone leads, which has nothing to
 * follow. Four main-thread wake-ups a second went on for a paused room left open (performance review, 18 Nov 2026).
 * What needs a look sooner wakes it: a word from the leader, a play or a pause here, a new lead, Catch up.
 */
internal fun lookEveryMs(leading: Boolean, roomPlaying: Boolean?, playingHere: Boolean): Long =
    if (leading || (roomPlaying != true && !playingHere)) IDLE_LOOK_MS else LOOK_MS
