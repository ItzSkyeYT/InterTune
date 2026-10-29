/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.recap

import android.content.Context
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.annotation.OptIn
import androidx.media.AudioAttributesCompat
import androidx.media.AudioFocusRequestCompat
import androidx.media.AudioManagerCompat
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import com.dd3boh.outertune.constants.ShareAudioFocusKey
import com.dd3boh.outertune.playback.MusicService
import com.dd3boh.outertune.recap.RecapSoundPlan.Step
import com.dd3boh.outertune.utils.Throttle
import com.dd3boh.outertune.utils.dataStore
import com.dd3boh.outertune.utils.get
import com.zionhuang.innertube.YouTube
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * The Recap's sound (RecapSound), on two players of its own, never the app's: one plays the page's
 * song while the other gets the next song ready at its start point, so that a song takes over the
 * moment its page comes up. They read a song the way the app's player does, from its download, the
 * cache or the stream (MusicService.songSource), and sound as the app's playback does: on the media
 * stream, so that the media volume sets them and a phone whose media is silent stays silent, and
 * each song at the level the app plays it at (MusicService.volumeFor), its levelling included.
 *
 * The listener's own music is paused and carried on through MusicService's player, and the last
 * song slid into their queue there (MusicService.slideIn). Audio focus is asked once for the whole
 * Recap, as the app asks it, and not at all when Settings has the app share it.
 *
 * One heat map is asked for per song that is not a local file, from Open Recap, in the order of
 * the pages, a page coming up asking for its own at once if it is still waiting (RecapHeat). Main
 * thread.
 */
@OptIn(UnstableApi::class)
class RecapPlayer(
    private val context: Context,
    private val service: MusicService,
    private val scope: CoroutineScope,
) : RecapSound {
    private val plan = RecapSoundPlan()
    private var pages: List<RecapPage> = emptyList()
    private var done = false

    /** The player sounding the page's song, and the one getting the next song ready. */
    private var active: ExoPlayer? = null
    private var spare: ExoPlayer? = null

    /** What each player has been given: the song and where it starts. */
    private val holding = HashMap<ExoPlayer, Pair<String, Long>>()
    private val failed = HashSet<ExoPlayer>()

    /** Each player's song at the volume the app plays it at (MusicService.volumeFor), and those volumes by song. */
    private val gain = HashMap<ExoPlayer, Float>()
    private val volumes = HashMap<String, Float>()

    private val heat = HashMap<String, Deferred<List<HeatMarker>?>>()
    private var asking: Job? = null
    private var readying: Job? = null
    private var takingOver: Job? = null
    private var fadingIn: Job? = null

    private var held = false
    private var muted = false
    private var focusLost = false
    private var ducked = false
    private var focus: AudioFocusRequestCompat? = null
    private val audio = context.getSystemService(AudioManager::class.java)

    override var onInterrupted: (() -> Unit)? = null

    override fun open(pages: List<RecapPage>) {
        if (done || plan.theirs != null) return
        this.pages = pages
        val theirs = theirs()
        Log.i(TAG, "open: ${pages.size} pages, the listener's music ${theirs.name.lowercase()}")
        run(plan.open(theirs, pages.size))
        val source = service.songSource()
        active = player(source)
        spare = player(source)
        takeFocus()
        // One request a song, in the order of the pages: a page whose song is still waiting asks
        // for its own at once (startOf).
        val ids = pages.mapNotNull { it.song }.filterNot { it.local }.map { it.id }.distinct()
        for (id in ids) heat[id] = scope.async(Dispatchers.IO, start = CoroutineStart.LAZY) { heatMap(id) }
        asking = scope.launch {
            for (id in ids) heat[id]?.await()
            for (song in pages.mapNotNull { it.song }.distinctBy { it.id }) volumeOf(song)
        }
        // The first song made ready while the story opens, where it starts without a heat map: its
        // stream is found meanwhile, and the heat map's start, when it comes in time, is a seek away.
        readying = scope.launch { makeReady(-1, waitMs = 0L) }
    }

    override fun show(page: Int) {
        if (page !in pages.indices) return
        for (step in plan.show(page, pages[page].song?.id)) {
            if (step is Step.TakeOver) takeOver(step.page)
        }
    }

    override fun pause() {
        held = true
        apply()
    }

    override fun resume() {
        held = false
        if (!plan.opened) return
        if (focusLost && focus == null) takeFocus()
        apply()
    }

    override fun mute(muted: Boolean) {
        this.muted = muted
        plan.mute(muted)
        apply()
    }

    override fun close() {
        if (done) return
        val now = active?.let { p ->
            val holds = holding[p]
            val sounding = p.playWhenReady && p !in failed &&
                (p.playbackState == Player.STATE_READY || p.playbackState == Player.STATE_BUFFERING)
            if (holds != null && sounding) Sounding(holds.first, p.currentPosition.coerceAtLeast(0L)) else null
        }
        val page = plan.page
        val steps = plan.close(now)
        Log.i(TAG, "close: on page $page of ${pages.size}, ${now?.let { "${it.songId} at ${it.positionMs} ms" } ?: "nothing sounding"}: ${steps.joinToString()}")
        // The Recap lets go first, so that the listener's player, which takes audio focus as it
        // plays, does not take it from a Recap still holding it.
        release()
        run(steps)
    }

    /**
     * Lets go of both players and of audio focus, the listener's music left as it is: also when the
     * window went for good with the Recap still open. Closing is what carries their music on.
     */
    fun release() {
        done = true
        onInterrupted = null
        listOf(asking, readying, takingOver, fadingIn).forEach { it?.cancel() }
        heat.values.forEach { it.cancel() }
        heat.clear()
        listOfNotNull(active, spare).forEach { it.release() }
        active = null
        spare = null
        holding.clear()
        failed.clear()
        gain.clear()
        volumes.clear()
        focus?.let { AudioManagerCompat.abandonAudioFocusRequest(audio, it) }
        focus = null
    }

    private fun run(steps: List<Step>) {
        for (step in steps) when (step) {
            Step.PauseTheirs -> service.player.pause()
            Step.ResumeTheirs -> service.player.play()
            is Step.SlideIn -> service.slideIn(step.songId, step.positionMs)
            is Step.TakeOver -> takeOver(step.page)
            // Done by release, before the steps that touch the listener's music.
            Step.Stop -> Unit
        }
    }

    /** What the listener's own player is doing. */
    private fun theirs(): TheirMusic {
        val p = service.player
        return when {
            p.currentMediaItem == null -> TheirMusic.NONE
            p.playWhenReady && p.playbackState != Player.STATE_IDLE && p.playbackState != Player.STATE_ENDED -> TheirMusic.PLAYING
            else -> TheirMusic.PAUSED
        }
    }

    /**
     * Page [page]'s song takes over, from its start point, on the spare player, which has it ready
     * unless the story got there first. The song before fades out and that player is given the
     * next song to make ready.
     */
    private fun takeOver(page: Int) {
        val song = pages.getOrNull(page)?.song ?: return
        val piece = pages[page].piece ?: RecapPiece.of(song.durationS, 0)
        readying?.cancel()
        takingOver?.cancel()
        takingOver = scope.launch {
            val (from, how) = startOf(song, piece, waitMs = HEAT_WAIT_MS)
            val volume = volumeOf(song)
            val next = spare ?: return@launch
            val old = active
            if (holding[next] != song.id to from) load(next, song.id, from)
            gain[next] = volume
            active = next
            spare = old
            Log.i(TAG, "page $page: ${song.id} from $from ms ($how)")
            next.volume = 0f
            fadingIn?.cancel()
            fadingIn = launch { ramp(next, FADE_IN_MS, from = 0f) { level() } }
            apply()
            if (old != null) {
                ramp(old, FADE_OUT_MS, from = old.volume) { 0f }
                old.stop()
                old.clearMediaItems()
                holding.remove(old)
                failed.remove(old)
                gain.remove(old)
            }
            makeReady(page)
        }
    }

    /** The next song to take over after page [page], made ready on the spare player, waiting up to [waitMs] for its heat map. */
    private suspend fun makeReady(page: Int, waitMs: Long = READY_WAIT_MS) {
        val now = pages.getOrNull(page)?.song?.id
        val index = (page + 1 until pages.size).firstOrNull { pages[it].song != null && pages[it].song?.id != now } ?: return
        val song = pages[index].song ?: return
        val piece = pages[index].piece ?: RecapPiece.of(song.durationS, 0)
        val (from, _) = startOf(song, piece, waitMs = waitMs)
        val volume = volumeOf(song)
        val p = spare ?: return
        if (holding[p] != song.id to from) load(p, song.id, from)
        gain[p] = volume
    }

    private fun load(p: ExoPlayer, songId: String, fromMs: Long) {
        p.playWhenReady = false
        if (holding[p]?.first == songId && p !in failed && p.playbackState != Player.STATE_IDLE) {
            // The same song made ready from another start, the first one while its heat map was
            // on its way: only the start moves, and the stream found for it is kept.
            p.seekTo(fromMs)
        } else {
            failed.remove(p)
            p.setMediaItem(MediaItem.Builder().setMediaId(songId).setUri(songId).setCustomCacheKey(songId).build(), fromMs)
            p.prepare()
        }
        holding[p] = songId to fromMs
    }

    /** The volume the app plays [song] at, asked once. */
    private suspend fun volumeOf(song: RecapSongShown): Float =
        volumes[song.id] ?: service.volumeFor(song.id, song.local).also { volumes[song.id] = it }

    /** Where [song] starts, and what said so, waiting up to [waitMs] for its heat map. */
    private suspend fun startOf(song: RecapSongShown, piece: RecapPiece, waitMs: Long): Pair<Long, String> {
        if (song.local) return piece.fromMs to "a local file: a third of the way in"
        // The wait gives up, the request does not: a later page has it.
        val markers = heat[song.id]?.let { d -> withTimeoutOrNull(waitMs) { d.await() } }
        val start = RecapHeat.startMs(piece, local = false, markers = markers)
        val how = when {
            markers == null -> "no heat map: a third of the way in"
            RecapHeat.peakMs(markers, piece.lengthMs) == null -> "no peak that leaves the page its song: a third of the way in"
            else -> "the heat map's peak"
        }
        return start to how
    }

    private suspend fun heatMap(songId: String): List<HeatMarker>? {
        if (Throttle.isBlocked) return null
        val answer = YouTube.heatMap(songId)
        answer.exceptionOrNull()?.let { Log.w(TAG, "no heat map for $songId: ${it.javaClass.simpleName}") }
        val markers = answer.getOrNull()?.let(RecapHeat::markers)
        Log.i(TAG, "heat map of $songId: ${markers?.let { "${it.size} markers" } ?: "none"}")
        return markers
    }

    /**
     * How loud the song playing is: as loud as the app plays it (its levelling and the player's own
     * volume), nothing when muted, a little while another app speaks over it.
     */
    private fun level(): Float = (active?.let { gain[it] } ?: 1f) * when {
        muted -> 0f
        ducked -> DUCKED
        else -> 1f
    }

    /** The song playing plays unless the story waits or the sound was taken; at its level once it has faded in. */
    private fun apply() {
        val p = active ?: return
        p.playWhenReady = plan.opened && !held && !focusLost
        if (fadingIn?.isActive != true) p.volume = level()
    }

    /** From [from] to [to] over [ms], [to] read at each step so that a mute meanwhile is heard at once. */
    private suspend fun ramp(p: ExoPlayer, ms: Long, from: Float, to: () -> Float) {
        val steps = (ms / RAMP_STEP_MS).coerceAtLeast(1)
        for (i in 1..steps) {
            p.volume = from + (to() - from) * i / steps
            delay(RAMP_STEP_MS)
        }
        p.volume = to()
    }

    private fun player(source: DataSource.Factory): ExoPlayer = ExoPlayer.Builder(context)
        .setMediaSourceFactory(DefaultMediaSourceFactory(source))
        // The app's own: media, music. Audio focus is the Recap's as a whole (takeFocus).
        .setAudioAttributes(AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MUSIC).build(), false)
        // Headphones out: it stops, and the story with it.
        .setHandleAudioBecomingNoisy(true)
        // A page's worth and some: a song plays about ten seconds a page.
        .setLoadControl(DefaultLoadControl.Builder().setBufferDurationsMs(15_000, 30_000, 500, 1_000).build())
        .build()
        .also { p ->
            p.addListener(object : Player.Listener {
                override fun onPlaybackStateChanged(state: Int) {
                    if (state == Player.STATE_ENDED && p === active) holding[p]?.let { plan.ended(it.first) }
                }

                override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
                    if (!playWhenReady && reason == Player.PLAY_WHEN_READY_CHANGE_REASON_AUDIO_BECOMING_NOISY && p === active) {
                        Log.i(TAG, "headphones out: the story pauses")
                        onInterrupted?.invoke()
                    }
                }

                override fun onPlayerError(error: PlaybackException) {
                    failed += p
                    Log.w(TAG, "${holding[p]?.first} could not be played: ${error.errorCodeName}")
                }
            })
        }

    /** Audio focus for the Recap, unless Settings has the app share it, in which case nothing is asked. */
    private fun takeFocus() {
        if (context.dataStore.get(ShareAudioFocusKey, false)) return
        val request = focus ?: AudioFocusRequestCompat.Builder(AudioManagerCompat.AUDIOFOCUS_GAIN)
            .setAudioAttributes(
                AudioAttributesCompat.Builder()
                    .setUsage(AudioAttributesCompat.USAGE_MEDIA)
                    .setContentType(AudioAttributesCompat.CONTENT_TYPE_MUSIC)
                    .build()
            )
            .setOnAudioFocusChangeListener({ change -> focusChanged(change) }, Handler(Looper.getMainLooper()))
            .build()
        focus = request
        focusLost = AudioManagerCompat.requestAudioFocus(audio, request) != AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        if (focusLost) Log.i(TAG, "audio focus refused: the sound waits")
    }

    private fun focusChanged(change: Int) {
        if (done) return
        when (change) {
            AudioManager.AUDIOFOCUS_GAIN -> {
                focusLost = false
                ducked = false
            }
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> ducked = true
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> focusLost = true
            AudioManager.AUDIOFOCUS_LOSS -> {
                // Another app's sound for good: the story pauses, and its play button asks again.
                focusLost = true
                focus?.let { AudioManagerCompat.abandonAudioFocusRequest(audio, it) }
                focus = null
                Log.i(TAG, "audio focus lost: the story pauses")
                onInterrupted?.invoke()
            }
        }
        apply()
    }

    private companion object {
        const val TAG = "RecapSound"

        /** How long a song coming up waits for its heat map before it starts a third of the way in. */
        const val HEAT_WAIT_MS = 1_500L

        /** And the next song, made ready ahead of its page. */
        const val READY_WAIT_MS = 8_000L

        /** The song coming up, and the one it takes over from. */
        const val FADE_IN_MS = 150L
        const val FADE_OUT_MS = 400L
        const val RAMP_STEP_MS = 16L

        /** Under another app's short sound, as Android asks. */
        const val DUCKED = 0.2f
    }
}
