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
import android.util.Log
import android.widget.Toast
import androidx.core.content.getSystemService
import androidx.datastore.preferences.core.edit
import androidx.media3.common.Player
import com.dd3boh.outertune.BuildConfig
import com.dd3boh.outertune.R
import com.dd3boh.outertune.cast.CastDevice
import com.dd3boh.outertune.cast.CastLinkException
import com.dd3boh.outertune.cast.CastLinks
import com.dd3boh.outertune.cast.CastNetwork
import com.dd3boh.outertune.cast.CastServer
import com.dd3boh.outertune.cast.FCastLink
import com.dd3boh.outertune.cast.IdleCountdown
import com.dd3boh.outertune.cast.LinkProblem
import com.dd3boh.outertune.cast.ReceiverLink
import com.dd3boh.outertune.cast.ReceiverStatus
import com.dd3boh.outertune.cast.RecentReceivers
import com.dd3boh.outertune.constants.CastRecentKey
import com.dd3boh.outertune.constants.Unreleased
import com.dd3boh.outertune.utils.dataStore
import com.dd3boh.outertune.utils.get
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.updateAndGet
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

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
     * The receivers cast to before, the latest first, kept from one launch to the next (see
     * RecentReceivers): the picker asks them directly as it opens, so a receiver that does not
     * answer the network's search is still there at once.
     */
    private val _recent = MutableStateFlow(RecentReceivers.decode(service.dataStore.get(CastRecentKey, "")))
    val recent: StateFlow<List<CastDevice>> = _recent.asStateFlow()

    private fun rememberRecent(device: CastDevice) {
        val list = _recent.updateAndGet { RecentReceivers.remember(it, device) }
        scope.launch(Dispatchers.IO) {
            runCatching { service.dataStore.edit { it[CastRecentKey] = RecentReceivers.encode(list) } }
                .onFailure { Log.w(TAG, "The receivers cast to could not be kept", it) }
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
        Toast.makeText(service, service.getString(R.string.cast_ended_idle, minutes), Toast.LENGTH_LONG).show()
    }

    private fun lost(status: ReceiverStatus) {
        val remote = remote ?: return
        Log.i(TAG, "Lost the receiver: ${status.error}")
        val reason = say(status.problem ?: LinkProblem.LOST, remote.device.name)
        end(playHere = false)
        _problem.value = reason
        Toast.makeText(service, service.getString(R.string.cast_lost, reason), Toast.LENGTH_LONG).show()
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
