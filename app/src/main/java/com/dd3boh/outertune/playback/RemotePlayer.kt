/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.playback

import android.os.Looper
import android.util.Log
import androidx.media3.common.C
import androidx.media3.common.DeviceInfo
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.SimpleBasePlayer
import androidx.media3.common.util.Util
import com.dd3boh.outertune.cast.CastFormats
import com.dd3boh.outertune.cast.CastItem
import com.dd3boh.outertune.cast.CastServer
import com.dd3boh.outertune.cast.FormatChoice
import com.dd3boh.outertune.cast.LinkState
import com.dd3boh.outertune.cast.ReceiverLink
import com.dd3boh.outertune.cast.ReceiverState
import com.dd3boh.outertune.cast.ReceiverStatus
import com.dd3boh.outertune.cast.RemoteCommand
import com.dd3boh.outertune.cast.RemoteError
import com.dd3boh.outertune.cast.RemotePhase
import com.dd3boh.outertune.cast.RemotePlayback
import com.dd3boh.outertune.cast.RemoteSong
import com.dd3boh.outertune.cast.ReportAction
import com.dd3boh.outertune.cast.SentSong
import com.dd3boh.outertune.extensions.metadata
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.roundToInt

/**
 * The player while casting: the queue's songs, played one at a time on a receiver through [link],
 * and fetched by it from the phone's [server].
 *
 * What happens is decided by [RemotePlayback], which has no Android in it and is unit tested; this
 * is the media3 side of it. Each change the app makes through the player (a new queue, a skip, a
 * seek, play or pause from the app, the notification or a headset) goes to the playback rules, and
 * the commands they give go to the receiver one after another, off the main thread. What the
 * receiver reports comes back the other way and becomes the player's state, which media3 turns
 * into the usual listener calls, so the queue board, the notification and the screens follow the
 * receiver without knowing it is one.
 *
 * Each song goes in the form the receiver plays (CastFormats), and one it fails is tried once
 * more as AAC when the receiver never said what it plays. A song the phone cannot get, or that the
 * receiver cannot be sent, is a player error, so the skip-or-stop setting applies as it does for
 * a song the phone cannot play.
 *
 * The device volume is the receiver's, so the volume keys and the system panel change it once
 * media3 turns the session remote, which it does for [DeviceInfo.PLAYBACK_TYPE_REMOTE].
 */
class RemotePlayer(
    looper: Looper,
    private val link: ReceiverLink,
    private val server: CastServer,
    items: List<MediaItem>,
    startIndex: Int,
    startMs: Long,
    playWhenReady: Boolean,
    repeatMode: Int,
    private var shuffle: Boolean,
    /** Called on the main thread when the receiver is gone, with the link's last status, which says why. */
    private val onLost: (ReceiverStatus) -> Unit,
) : SimpleBasePlayer(looper) {
    private val playback = RemotePlayback<MediaItem>(now = ::linkClock)
    // Not immediate: the status watcher started in init must not run before construction is done.
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    /** What is still to be said to the receiver, in order. */
    private val work = Channel<suspend () -> Unit>(Channel.UNLIMITED)

    /** The ticket of the latest load, readable off the main thread so a load overtaken by another is never sent. */
    private val latestTicket = AtomicInteger(0)

    /** The last song sent, and in what form; read by the status watcher on the main thread. */
    @Volatile
    private var sent: SentSong<MediaItem>? = null

    /**
     * Types this receiver failed and then played as AAC, which its later songs skip straight to
     * (CastFormats.choose). Only a receiver that never said what it plays ends up here, and only
     * for this cast.
     */
    private val refusedAsIs: MutableSet<String> = ConcurrentHashMap.newKeySet()

    private var released = false
    private var deviceVolume = MAX_VOLUME
    private var volumeBeforeMute: Int? = null

    private var builtState: State? = null
    private var builtVersion = -1
    private var shownError: RemoteError? = null
    private var shownException: PlaybackException? = null

    init {
        playback.begin(items.map(::songOf), startIndex, startMs, playWhenReady, repeatMode)
        link.status.value.volume?.let { deviceVolume = (it * MAX_VOLUME).roundToInt() }
        scope.launch(Dispatchers.IO) {
            for (job in work) {
                try {
                    job()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.w(TAG, "${link.device.name}: ${e.message}")
                }
            }
        }
        scope.launch { link.status.collect(::onStatus) }
    }

    /** The receiver it plays on. */
    val device get() = link.device

    /** Where the current song is, for handing playback back to the phone. */
    val positionNow: Long get() = playback.positionMs()

    override fun getState(): State {
        val cached = builtState?.takeIf { builtVersion == playback.version }
        // The playlist is rebuilt only when it changed. A new one on every report from the receiver
        // would be a new timeline every second, and every listener compares timelines.
        val builder = cached?.buildUpon()?.clearPositionDiscontinuity()
            ?: State.Builder().setPlaylist(
                playback.entries.map { entry ->
                    MediaItemData.Builder(entry.key)
                        .setMediaItem(entry.item)
                        .setDurationUs(if (entry.durationMs > 0) Util.msToUs(entry.durationMs) else C.TIME_UNSET)
                        .setIsSeekable(true)
                        .build()
                },
            ).also { builtVersion = playback.version }
        val phase = playback.phase
        val position = playback.positionSnapshot()
        val supplier = PositionSupplier { position.at(linkClock()) }
        builder
            .setAvailableCommands(
                (if (link.caps.hasVolume) COMMANDS_WITH_VOLUME else COMMANDS).buildUpon()
                    .addIf(Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM, playback.hasPrevious)
                    .addIf(Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM, playback.hasNext)
                    .addIf(Player.COMMAND_SEEK_TO_NEXT, playback.hasNext)
                    .build(),
            )
            .setPlayWhenReady(
                playback.playWhenReady,
                if (playback.changedOnReceiver) Player.PLAY_WHEN_READY_CHANGE_REASON_REMOTE else Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST,
            )
            .setPlaybackState(
                when (phase) {
                    RemotePhase.IDLE -> Player.STATE_IDLE
                    RemotePhase.BUFFERING -> Player.STATE_BUFFERING
                    RemotePhase.READY -> Player.STATE_READY
                    RemotePhase.ENDED -> Player.STATE_ENDED
                },
            )
            .setIsLoading(phase == RemotePhase.BUFFERING)
            .setPlayerError(errorFor(playback.error))
            .setRepeatMode(playback.repeatMode)
            .setShuffleModeEnabled(shuffle)
            .setCurrentMediaItemIndex(if (playback.entries.isEmpty()) C.INDEX_UNSET else playback.index)
            .setContentPositionMs(supplier)
            .setContentBufferedPositionMs(supplier)
            .setSeekBackIncrementMs(SEEK_INCREMENT_MS)
            .setSeekForwardIncrementMs(SEEK_INCREMENT_MS)
            .setDeviceInfo(
                DeviceInfo.Builder(DeviceInfo.PLAYBACK_TYPE_REMOTE)
                    .setMinVolume(0)
                    .setMaxVolume(if (link.caps.hasVolume) MAX_VOLUME else 0)
                    .build(),
            )
            .setDeviceVolume(if (link.caps.hasVolume) deviceVolume else 0)
            .setIsDeviceMuted(volumeBeforeMute != null)
        if (playback.takeAutoTransition()) {
            builder.setPositionDiscontinuity(Player.DISCONTINUITY_REASON_AUTO_TRANSITION, 0)
        }
        return builder.build().also { builtState = it }
    }

    override fun handleSetPlayWhenReady(playWhenReady: Boolean) = enqueue(playback.setPlayWhenReady(playWhenReady))

    override fun handlePrepare() = enqueue(playback.prepare())

    override fun handleStop() = enqueue(playback.stop())

    override fun handleRelease(): ListenableFuture<*> {
        released = true
        work.close()
        scope.cancel()
        return Futures.immediateVoidFuture()
    }

    override fun handleSetRepeatMode(repeatMode: Int): ListenableFuture<*> {
        playback.repeatMode = repeatMode
        return Futures.immediateVoidFuture()
    }

    override fun handleSetShuffleModeEnabled(shuffleModeEnabled: Boolean): ListenableFuture<*> {
        // A flag only: the queue board shuffles by reordering the list it hands the player.
        shuffle = shuffleModeEnabled
        return Futures.immediateVoidFuture()
    }

    override fun handleSetMediaItems(mediaItems: MutableList<MediaItem>, startIndex: Int, startPositionMs: Long) =
        enqueue(playback.setItems(mediaItems.map(::songOf), startIndex, startPositionMs))

    override fun handleAddMediaItems(index: Int, mediaItems: MutableList<MediaItem>) =
        enqueue(playback.addItems(index, mediaItems.map(::songOf)))

    override fun handleMoveMediaItems(fromIndex: Int, toIndex: Int, newIndex: Int) =
        enqueue(playback.moveItems(fromIndex, toIndex, newIndex))

    override fun handleReplaceMediaItems(fromIndex: Int, toIndex: Int, mediaItems: MutableList<MediaItem>) =
        enqueue(playback.replaceItems(fromIndex, toIndex, mediaItems.map(::songOf)))

    override fun handleRemoveMediaItems(fromIndex: Int, toIndex: Int) = enqueue(playback.removeItems(fromIndex, toIndex))

    override fun handleSeek(mediaItemIndex: Int, positionMs: Long, seekCommand: Int) =
        enqueue(playback.seekTo(mediaItemIndex, positionMs))

    override fun handleSetDeviceVolume(deviceVolume: Int, flags: Int) = setVolume(deviceVolume)

    override fun handleIncreaseDeviceVolume(flags: Int) = setVolume((volumeBeforeMute ?: deviceVolume) + 1)

    override fun handleDecreaseDeviceVolume(flags: Int) = setVolume((volumeBeforeMute ?: deviceVolume) - 1)

    override fun handleSetDeviceMuted(muted: Boolean, flags: Int): ListenableFuture<*> {
        if (muted == (volumeBeforeMute != null)) return Futures.immediateVoidFuture()
        return if (muted) {
            val before = deviceVolume
            setVolume(0).also { volumeBeforeMute = before }
        } else {
            setVolume(volumeBeforeMute ?: MAX_VOLUME)
        }
    }

    private fun setVolume(level: Int): ListenableFuture<*> {
        val clamped = level.coerceIn(0, MAX_VOLUME)
        deviceVolume = clamped
        volumeBeforeMute = null
        work.trySend { link.setVolume(clamped.toFloat() / MAX_VOLUME) }
        return Futures.immediateVoidFuture()
    }

    /** Queues [commands] for the receiver, in order. */
    private fun enqueue(commands: List<RemoteCommand<MediaItem>>): ListenableFuture<*> {
        latestTicket.set(playback.ticket)
        for (command in commands) {
            when (command) {
                is RemoteCommand.Load -> delivering { send(command, aac = null) }
                is RemoteCommand.Seek -> work.trySend { if (command.ticket == latestTicket.get()) link.seek(command.positionMs) }
                RemoteCommand.Play -> delivering { link.play() }
                RemoteCommand.Pause -> delivering { link.pause() }
                RemoteCommand.Stop -> work.trySend { link.stop() }
            }
        }
        return Futures.immediateVoidFuture()
    }

    /** Queues [job], telling the playback rules when it has gone through (RemotePlayback.sending). */
    private fun delivering(job: suspend () -> Unit) {
        playback.sending()
        val queued = work.trySend {
            try {
                job()
            } finally {
                scope.launch { if (!released) playback.delivered() }
            }
        }
        if (queued.isFailure) playback.delivered()
    }

    /**
     * Sends a song. With [aac] null, in the form the receiver said it plays; true is the one retry
     * as AAC. Blocking work (the phone may have to go to YouTube for the song first), so on the
     * worker, never the main thread.
     */
    private suspend fun send(load: RemoteCommand.Load<MediaItem>, aac: Boolean?) {
        if (load.ticket != latestTicket.get()) return
        val mediaId = load.entry.mediaId
        val song = load.entry.item.metadata
        val local = song?.isLocal == true
        // This attempt's own, once it is on its way to the receiver. A retry as AAC reuses the load
        // of the attempt that failed, so [sent] can still be that attempt while this one resolves.
        var attempt: SentSong<MediaItem>? = null
        try {
            val asIs = server.prepare(mediaId)
            val choice = if (aac == true) FormatChoice.Aac else CastFormats.choose(link.caps.formats, asIs.mimeType, canFetchAac = !local, refused = refusedAsIs)
            if (choice is FormatChoice.Unplayable) {
                failed(load.ticket, "${link.device.name} does not play ${choice.mimeType}")
                return
            }
            val offer = if (choice == FormatChoice.Aac) server.prepare(mediaId, aac = true) else asIs
            // Resolving can take seconds; the listener may have moved on meanwhile.
            if (load.ticket != latestTicket.get()) return
            val item = CastItem(
                mediaId = mediaId,
                url = offer.url,
                mimeType = offer.mimeType,
                length = offer.length,
                durationMs = load.entry.durationMs,
                title = song?.title,
                artist = song?.artists?.joinToString { it.name }?.takeIf { it.isNotEmpty() },
                album = song?.album?.title,
                artworkUrl = song?.thumbnailUrl?.takeIf { it.startsWith("https://") || it.startsWith("http://") },
            )
            Log.i(TAG, "Sending $mediaId to ${link.device.name} as $choice (${offer.mimeType}), from ${load.startMs} ms")
            // Noted before the load, since a receiver can refuse the song before load returns. Loads
            // are sent one at a time, so this one is the next number on the link.
            attempt = SentSong(load, link.status.value.load + 1, asIs.mimeType, choice, local, retry = aac == true)
            sent = attempt
            link.load(item, load.startMs, load.play)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Could not send $mediaId to ${link.device.name}", e)
            // A DLNA renderer that refuses the song is reported as FAILED on the link before load
            // throws, and onStatus has taken that already, perhaps to try the song as AAC. Failing
            // it here too would stop the player while the retry plays. Only this attempt's own
            // report counts: a retry that could not even get the AAC copy has none, and the
            // failure before it was taken for the retry, so the player hears of it only here.
            val status = link.status.value
            val reported = attempt != null && status.load == attempt.linkLoad && status.state == ReceiverState.FAILED
            if (!reported) failed(load.ticket, e.message ?: "Could not send the song to ${link.device.name}")
        }
    }

    private suspend fun failed(ticket: Int, message: String) = withContext(Dispatchers.Main) {
        if (released) return@withContext
        playback.onLoadFailed(ticket, message)
        invalidateState()
    }

    private fun onStatus(status: ReceiverStatus) {
        if (released) return
        if (status.link == LinkState.LOST) {
            onLost(status)
            return
        }
        if (volumeBeforeMute == null) status.volume?.let { deviceVolume = (it * MAX_VOLUME).roundToInt().coerceIn(0, MAX_VOLUME) }
        val sent = sent
        if (sent != null) {
            sent.refusedType(status)?.let { type ->
                if (refusedAsIs.add(type)) Log.i(TAG, "${link.device.name} plays AAC where it failed $type; sending AAC from now on")
            }
            when (sent.onReport(status, latestTicket.get(), link.caps.formats)) {
                ReportAction.IGNORE -> Unit
                ReportAction.FOLLOW -> enqueue(playback.onReceiver(sent.load.ticket, status))
                ReportAction.RETRY_AS_AAC -> {
                    // Resolved again whatever happens next: the url may simply have expired.
                    server.forget(sent.load.entry.mediaId)
                    Log.i(TAG, "${link.device.name} failed ${sent.load.entry.mediaId}; trying it once more as AAC")
                    work.trySend { send(sent.load, aac = true) }
                }
                ReportAction.FAILED -> {
                    server.forget(sent.load.entry.mediaId)
                    enqueue(playback.onReceiver(sent.load.ticket, status))
                }
            }
        }
        invalidateState()
    }

    /** The same exception for the same failure, or media3 would report it again on every change. */
    private fun errorFor(error: RemoteError?): PlaybackException? {
        if (error == null) return null
        if (error !== shownError) {
            shownError = error
            shownException = PlaybackException(error.message, null, PlaybackException.ERROR_CODE_REMOTE_ERROR)
        }
        return shownException
    }

    private fun songOf(item: MediaItem): RemoteSong<MediaItem> =
        RemoteSong(item.mediaId, item.metadata?.duration?.takeIf { it > 0 }?.let { it * 1000L } ?: -1L, item)

    private companion object {
        const val TAG = "RemotePlayer"

        /** Volume steps for the keys and the system panel: 5% each. */
        const val MAX_VOLUME = 20

        /** As the phone's own player is set up in MusicService. */
        const val SEEK_INCREMENT_MS = 5_000L

        /** The links' clock, which every reported position is timed by. */
        fun linkClock(): Long = System.nanoTime() / 1_000_000L

        /** Always offered; skipping to the next or previous song is added when there is one (getState). */
        private val COMMANDS: Player.Commands = Player.Commands.Builder()
            .addAll(
                Player.COMMAND_PLAY_PAUSE,
                Player.COMMAND_PREPARE,
                Player.COMMAND_STOP,
                Player.COMMAND_SEEK_TO_DEFAULT_POSITION,
                Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM,
                // Back to the start of the song, which there always is.
                Player.COMMAND_SEEK_TO_PREVIOUS,
                Player.COMMAND_SEEK_TO_MEDIA_ITEM,
                Player.COMMAND_SEEK_BACK,
                Player.COMMAND_SEEK_FORWARD,
                Player.COMMAND_SET_REPEAT_MODE,
                Player.COMMAND_SET_SHUFFLE_MODE,
                Player.COMMAND_GET_CURRENT_MEDIA_ITEM,
                Player.COMMAND_GET_TIMELINE,
                Player.COMMAND_GET_METADATA,
                Player.COMMAND_SET_MEDIA_ITEM,
                Player.COMMAND_CHANGE_MEDIA_ITEMS,
                Player.COMMAND_RELEASE,
            )
            .build()

        private val COMMANDS_WITH_VOLUME: Player.Commands = COMMANDS.buildUpon()
            .addAll(
                Player.COMMAND_GET_DEVICE_VOLUME,
                Player.COMMAND_SET_DEVICE_VOLUME,
                Player.COMMAND_SET_DEVICE_VOLUME_WITH_FLAGS,
                Player.COMMAND_ADJUST_DEVICE_VOLUME,
                Player.COMMAND_ADJUST_DEVICE_VOLUME_WITH_FLAGS,
            )
            .build()
    }
}
