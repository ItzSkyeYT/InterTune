/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.playback

import android.content.Context
import android.net.ConnectivityManager
import android.net.wifi.WifiManager
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import android.util.Log
import android.widget.Toast
import androidx.core.content.getSystemService
import androidx.datastore.preferences.core.edit
import androidx.media3.common.Player
import com.dd3boh.outertune.BuildConfig
import com.dd3boh.outertune.R
import com.dd3boh.outertune.cast.CastDevice
import com.dd3boh.outertune.cast.CastFarewell
import com.dd3boh.outertune.cast.CastLinkException
import com.dd3boh.outertune.cast.CastLinks
import com.dd3boh.outertune.cast.CastNetwork
import com.dd3boh.outertune.cast.CastNext
import com.dd3boh.outertune.cast.CastRoom
import com.dd3boh.outertune.cast.CastRoomFeed
import com.dd3boh.outertune.cast.CastServer
import com.dd3boh.outertune.cast.FCastLink
import com.dd3boh.outertune.cast.IdleCountdown
import com.dd3boh.outertune.cast.LinkProblem
import com.dd3boh.outertune.cast.PhoneName
import com.dd3boh.outertune.cast.ReceiverLink
import com.dd3boh.outertune.cast.ReceiverStatus
import com.dd3boh.outertune.cast.RecentReceivers
import com.dd3boh.outertune.constants.CastRecentKey
import com.dd3boh.outertune.constants.LyricsTimingKey
import com.dd3boh.outertune.constants.ShowLyricsKey
import com.dd3boh.outertune.constants.Unreleased
import com.dd3boh.outertune.lyrics.LyricsForCast
import com.dd3boh.outertune.lyrics.LyricsLookup
import com.dd3boh.outertune.lyrics.LyricsTiming
import com.dd3boh.outertune.utils.dataStore
import com.dd3boh.outertune.utils.get
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.updateAndGet
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import com.dd3boh.outertune.utils.Toaster

/**
 * Casting from start to finish, one receiver at a time: the server, the link, handing playback to
 * the receiver, and handing it back.
 *
 * [castTo] starts the cast server, connects to the receiver, and only once it answers moves
 * playback there (MusicService.handOverTo), so a receiver that cannot be reached leaves the music
 * playing on the phone. [stop] brings it back at the receiver's song and position, playing if it
 * was. A receiver that goes away brings it back paused, rather than playing out of the phone's own
 * speaker wherever the phone is, with a word to say why.
 *
 * While casting, the phone stays awake and on Wi-Fi: the receiver fetches every song from the
 * server here, whenever it likes, and ExoPlayer's own locks are idle because it is not playing.
 * Only exists with Unreleased.CASTING on. Main thread.
 */
class CastSession(
    private val service: MusicService,
    private val connectivityManager: ConnectivityManager,
) {
    sealed interface State {
        data object Off : State
        data class Connecting(val device: CastDevice) : State
        data class Casting(val device: CastDevice) : State
    }

    private val _state = MutableStateFlow<State>(State.Off)
    val state: StateFlow<State> = _state.asStateFlow()

    /**
     * Whether the picker is on screen. Kept here and not in the button: the player draws the cast
     * button in one place upright and in another on its side, and a picker remembered by the
     * button closed when the phone was turned.
     */
    val pickerOpen = MutableStateFlow(false)

    /** What went wrong last, for the picker to say, until the next attempt. */
    private val _problem = MutableStateFlow<String?>(null)
    val problem: StateFlow<String?> = _problem.asStateFlow()

    private val _added = MutableStateFlow<List<CastDevice>>(emptyList())

    /**
     * Receivers typed in by address, the latest first, for as long as the app runs. Nothing finds
     * them on the network (that is why they were typed), and the picker looks for receivers afresh
     * each time it opens, so without this the address had to be typed again for every cast.
     */
    val added: StateFlow<List<CastDevice>> = _added.asStateFlow()

    /** Keeps [device], typed in by address, for the picker to list again. */
    fun rememberAdded(device: CastDevice) {
        _added.update { list -> (listOf(device) + list.filterNot { it.sameAs(device) }).take(MAX_ADDED) }
    }

    private fun CastDevice.sameAs(other: CastDevice) = protocol == other.protocol && id == other.id

    /**
     * The receivers seen or cast to before, the latest first, kept from one launch to the next
     * (see RecentReceivers): the picker lists them as it opens and asks each one directly, so a
     * receiver that does not answer the network's search is still there at once.
     */
    private val _recent = MutableStateFlow(RecentReceivers.decode(service.dataStore.get(CastRecentKey, "")))
    val recent: StateFlow<List<CastDevice>> = _recent.asStateFlow()

    private fun rememberRecent(device: CastDevice) = keep(_recent.updateAndGet { RecentReceivers.remember(it, device) })

    /** Receivers the picker's search has found, kept with those cast to. */
    fun rememberSeen(found: List<CastDevice>) {
        if (found.isEmpty()) return
        val before = _recent.value
        val after = _recent.updateAndGet { RecentReceivers.seen(it, found) }
        if (after != before) keep(after)
    }

    private fun keep(list: List<CastDevice>) {
        scope.launch(Dispatchers.IO) {
            runCatching { service.dataStore.edit { it[CastRecentKey] = RecentReceivers.encode(list) } }
                .onFailure { Log.w(TAG, "The receivers seen could not be kept", it) }
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    /** Never cancelled, so a receiver is still told to stop after the session is over. */
    private val closing = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private var connecting: Job? = null

    /** Receivers still being told to stop by casts that have ended, and the jobs doing it. Main thread. */
    private val stopping = mutableListOf<Pair<CastDevice, Job>>()
    private var keepAwake: Job? = null
    private var link: ReceiverLink? = null
    private var server: CastServer? = null
    private var remote: RemotePlayer? = null
    private val locks = StayAwake(service)

    private val idle = IdleCountdown(
        IdleCountdown.TIMEOUT_MS,
        schedule = { delayMs, action ->
            val job = scope.launch {
                delay(delayMs)
                action()
            }
            ({ job.cancel() })
        },
        onTimeout = ::idledOut,
    )
    private val idleWatch = object : Player.Listener {
        override fun onEvents(player: Player, events: Player.Events) {
            idle.update(player.playWhenReady, player.playbackState)
        }
    }

    /** Connects to [device] and plays the queue there. Casting to another receiver first comes back to the phone. */
    fun castTo(device: CastDevice) {
        check(Unreleased.CASTING) { "Casting is off" }
        when (val now = _state.value) {
            is State.Connecting -> if (now.device.id == device.id) return
            is State.Casting -> if (now.device.id == device.id) return
            State.Off -> Unit
        }
        // From one receiver straight to another, the phone holds the song paused in between rather
        // than playing it out loud for the second or two the new one takes to answer.
        val carryOn = remote?.playWhenReady
        end(playHere = if (carryOn != null) false else null)
        _problem.value = null
        _state.value = State.Connecting(device)
        connecting = scope.launch {
            // Here rather than off the main thread: binding a socket takes no time, and a start that
            // could still be under way when the attempt is given up could leave a server behind.
            val server = service.startCastServer()
            if (server == null) {
                gaveUp(service.getString(R.string.cast_needs_wifi))
                return@launch
            }
            val sender = FCastLink.Sender(Build.MODEL, service.getString(R.string.app_name), BuildConfig.VERSION_NAME)
            val link = CastLinks.create(device, scope, sender, CastNetwork.wifi(connectivityManager)?.network)
            try {
                // Straight back to a receiver just left, its stop must not land after the new song.
                stopping.filter { (left, _) -> left.protocol == device.protocol && left.host == device.host }
                    .forEach { (_, job) -> job.join() }
                link.connect()
            } catch (e: CancellationException) {
                // Given up on by end(), which also stops the server, or by a newer castTo, which
                // may already be using it.
                link.close()
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Could not connect to ${device.name}", e)
                link.close()
                service.stopCastServer(server)
                gaveUp(say((e as? CastLinkException)?.problem ?: LinkProblem.UNREACHABLE, link.device.name))
                return@launch
            }
            // InterTune's own receiver's idle screen says who is connected while the first song loads.
            tell("The phone's name") { link.hello(phoneName()) }
            val remote = service.handOverTo(link, server, play = carryOn, onLost = ::lost)
            if (remote == null) {
                link.close()
                service.stopCastServer(server)
                gaveUp(null)
                return@launch
            }
            this@CastSession.link = link
            this@CastSession.server = server
            this@CastSession.remote = remote
            // A cast starts quiet, never louder than the receiver was (QUIET_START).
            if (link.caps.hasVolume && remote.deviceVolume > QUIET_START) {
                remote.setDeviceVolume(QUIET_START, 0)
            }
            remote.addListener(idleWatch)
            // A cast started while paused loads paused, and is counted from now.
            idle.update(remote.playWhenReady, remote.playbackState)
            connecting = null
            // A receiver typed in by address is listed from now on as it named itself on
            // connecting; a DLNA renderer also changes its id to its own, which discovery uses too.
            _added.update { list -> list.map { if (it.sameAs(device)) link.device else it }.distinctBy { it.protocol to it.id } }
            locks.hold()
            keepAwake = scope.launch {
                // The wake lock has a time limit, so a cast of any length renews it.
                while (true) {
                    delay(StayAwake.RENEW_MS)
                    locks.hold()
                }
            }
            _state.value = State.Casting(link.device)
            rememberRecent(link.device)
            Log.i(TAG, "Casting to ${link.device.name}")
            lyricsJob = scope.launch { followLyrics(link) }
            queueJob = scope.launch { followQueue(link, remote) }
            roomJob = scope.launch { followRoom(link) }
        }
    }

    private var lyricsJob: Job? = null
    private var queueJob: Job? = null
    private var roomJob: Job? = null

    /** What the TV's idle screen calls this phone: the name set in its system settings, else its model. */
    private fun phoneName(): String {
        val named = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N_MR1) {
            runCatching { Settings.Global.getString(service.contentResolver, Settings.Global.DEVICE_NAME) }.getOrNull()
        } else null
        return PhoneName.of(named, Build.MODEL)
    }

    /**
     * Sends something to a receiver's own screens, and logs it when that fails: they are only
     * nice to have, so a failure never stops the cast. The link does its writing off the main
     * thread (ChromecastLink), where this is called from.
     */
    private suspend fun tell(what: String, send: suspend () -> Unit) {
        try {
            send()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "$what could not be sent", e)
        }
    }

    /**
     * The songs that play next, on the TV's "Up next" shelf: read again whenever the queue, the
     * song or the repeat mode changes, and sent when what it lists differs. Only InterTune's own
     * receiver shows it (ReceiverLink.showQueue).
     */
    private suspend fun followQueue(link: ReceiverLink, remote: RemotePlayer) {
        val changes = setOf(
            Player.EVENT_TIMELINE_CHANGED,
            Player.EVENT_MEDIA_ITEM_TRANSITION,
            Player.EVENT_REPEAT_MODE_CHANGED,
            Player.EVENT_SHUFFLE_MODE_ENABLED_CHANGED,
        )
        callbackFlow<List<CastNext>> {
            val listener = object : Player.Listener {
                override fun onEvents(player: Player, events: Player.Events) {
                    if (changes.any { events.contains(it) }) trySend(player.castUpNext())
                }
            }
            remote.addListener(listener)
            trySend(remote.castUpNext())
            awaitClose { remote.removeListener(listener) }
        }.distinctUntilChanged().collectLatest { next ->
            Log.i(TAG, "Up next for ${link.device.name}: ${next.joinToString { it.title }.ifEmpty { "nothing" }}")
            tell("The queue") { link.showQueue(next) }
        }
    }

    /**
     * The Listen together room this phone is in, on the TV (CastRoomFeed): sent when there is one,
     * changed, and put away when it closes. Only InterTune's own receiver shows it (ReceiverLink.showRoom).
     */
    private suspend fun followRoom(link: ReceiverLink) {
        var shown: CastRoom? = null
        CastRoomFeed.room.collectLatest { room ->
            if (room == shown) return@collectLatest
            shown = room
            Log.i(TAG, "Room for ${link.device.name}: ${room?.let { "${it.name}, ${it.count} in it" } ?: "none"}")
            tell("The room") { link.showRoom(room) }
        }
    }

    /**
     * The playing song's lyrics on the TV while the player's lyrics are open (asked for on 3 Nov 2026):
     * the lyrics button's setting, the song's synced lines and its timing shift, sent again
     * whenever one of them changes. Only InterTune's own receiver shows them (ReceiverLink.showLyrics).
     */
    private suspend fun followLyrics(link: ReceiverLink) {
        val open = service.dataStore.data.map { it[ShowLyricsKey] ?: false }.distinctUntilChanged()
        val timing = service.dataStore.data.map { it[LyricsTimingKey] ?: "" }.distinctUntilChanged()
        val lyrics = LyricsLookup.bySong(service.currentMediaMetadata) { song -> song.id to service.lyricsHelper.getLyrics(song) }
        combine(open, lyrics, timing) { on, (id, lines), saved ->
            if (on) LyricsForCast.of(id, lines, LyricsTiming.shiftOf(saved, id)) else null
        }.distinctUntilChanged().collectLatest { shown ->
            Log.i(TAG, "Lyrics for ${link.device.name}: ${shown?.let { "${it.lines.size} lines of ${it.mediaId}" } ?: "none"}")
            runCatching { link.showLyrics(shown) }.onFailure { Log.w(TAG, "The lyrics could not be sent", it) }
        }
    }

    private fun gaveUp(problem: String?) {
        connecting = null
        _problem.value = problem
        _state.value = State.Off
    }

    /** Back to the phone, at the receiver's song and position, playing if it was, or as [play] says. */
    fun stop(play: Boolean? = null) = end(playHere = play)

    /** Back to the phone, paused, with everything closed; from onDestroy. */
    fun release() {
        end(playHere = false)
        scope.cancel()
    }

    /** Nothing played for [IdleCountdown.TIMEOUT_MS]: back to the phone, paused, and the phone may sleep again. */
    private fun idledOut() {
        val remote = remote ?: return
        val minutes = IdleCountdown.TIMEOUT_MS / 60_000
        Log.i(TAG, "Nothing played on ${remote.device.name} for $minutes min, ending the cast")
        end(playHere = false)
        Toaster.show(service, service.getString(R.string.cast_ended_idle, minutes), Toast.LENGTH_LONG)
    }

    private fun lost(status: ReceiverStatus) {
        val remote = remote ?: return
        Log.i(TAG, "Lost the receiver: ${status.error}")
        val reason = say(status.problem ?: LinkProblem.LOST, remote.device.name)
        end(playHere = false)
        _problem.value = reason
        Toaster.show(service, service.getString(R.string.cast_lost, reason), Toast.LENGTH_LONG)
    }

    /** What went wrong with the receiver called [name], in the listener's language. */
    private fun say(problem: LinkProblem, name: String): String = service.getString(
        when (problem) {
            LinkProblem.UNREACHABLE -> R.string.cast_could_not_reach
            LinkProblem.NOT_A_RECEIVER -> R.string.cast_not_a_receiver
            LinkProblem.HUNG_UP -> R.string.cast_hung_up
            LinkProblem.STOPPED_ANSWERING -> R.string.cast_stopped_answering
            LinkProblem.LOST -> R.string.cast_lost_connection
        },
        name,
    )

    /** [playHere] null keeps playing on the phone if the receiver was playing. */
    private fun end(playHere: Boolean?) {
        connecting?.let {
            it.cancel()
            // The attempt's server, which nothing uses yet.
            service.stopCastServer()
        }
        connecting = null
        keepAwake?.cancel()
        keepAwake = null
        lyricsJob?.cancel()
        lyricsJob = null
        queueJob?.cancel()
        queueJob = null
        roomJob?.cancel()
        roomJob = null
        val remote = remote
        val link = link
        val server = server
        // Before the hand back, whose changes to the receiver's player are not a pause to count.
        remote?.removeListener(idleWatch)
        idle.stop()
        this.remote = null
        this.link = null
        this.server = null
        if (remote != null) service.takeBack(remote, play = playHere ?: remote.playWhenReady)
        if (link != null) {
            // Told to stop before the link closes, off the main thread. Not for ever, since a
            // receiver that is gone would hold the close up, but long enough for the stop to wait
            // out a DLNA poll already under way: a renderer slow to answer took more than the 3 s
            // this once allowed, the stop was dropped, and it played on after the cast had ended.
            val job = closing.launch {
                // InterTune's own receiver first says "Back on your phone" for a moment, its song held
                // quiet since the phone plays on already. Any other receiver is stopped at once.
                CastFarewell.to(link)
                withTimeoutOrNull(STOP_TIMEOUT_MS) { runCatching { link.stop() } }
                link.close()
            }
            stopping.removeAll { it.second.isCompleted }
            stopping += link.device to job
        }
        if (server != null) service.stopCastServer(server)
        locks.release()
        _state.value = State.Off
    }

    /**
     * The phone's part of staying reachable while casting: the CPU on, and Wi-Fi at full power, the
     * same kind of lock ExoPlayer takes while it plays from the network.
     */
    private class StayAwake(context: Context) {
        private val wake = context.getSystemService<PowerManager>()
            ?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "InterTune:cast")
            ?.apply { setReferenceCounted(false) }

        @Suppress("DEPRECATION") // FULL_LOW_LATENCY is API 29; this is what ExoPlayer uses too.
        private val wifi = context.applicationContext.getSystemService<WifiManager>()
            ?.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "InterTune:cast")
            ?.apply { setReferenceCounted(false) }

        /** Takes both, or renews them; the wake lock ends by itself after [HOLD_MS] if never renewed. */
        fun hold() {
            wake?.acquire(HOLD_MS)
            wifi?.takeIf { !it.isHeld }?.acquire()
        }

        fun release() {
            wake?.takeIf { it.isHeld }?.release()
            wifi?.takeIf { it.isHeld }?.release()
        }

        companion object {
            const val HOLD_MS = 2L * 60 * 60 * 1000
            const val RENEW_MS = 60L * 60 * 1000
        }
    }

    private companion object {
        const val TAG = "CastSession"
        const val STOP_TIMEOUT_MS = 15_000L

        /** Typed-in receivers kept; a few is plenty. */
        const val MAX_ADDED = 5
    }
}
