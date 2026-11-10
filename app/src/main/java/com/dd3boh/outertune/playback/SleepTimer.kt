package com.dd3boh.outertune.playback

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import com.dd3boh.outertune.constants.SleepTimerDefaults
import com.dd3boh.outertune.extensions.metadata
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.min

/** What a sleep timer that waits for an end waits for: the end of this song, of its album, or of the queue. */
enum class SleepEnd { SONG, ALBUM, QUEUE }

/**
 * Sleep timer with an optional volume fade-out.
 *
 * It is one of two kinds. One counts a time down ([triggerTime]), and stops the music when the time
 * is up, or, with [letSongFinish], at the end of the song it is in then. The other waits for an
 * end ([endOf]): the end of the song, of the album the song is in, or of the queue. The player's
 * callbacks end that kind; this class's job only drives the fade, and only when there is one.
 *
 * Whichever kind it is, it follows the song that plays: a skip, a new queue or a seek never end an
 * end-of-song timer, they carry it over to the song chosen. The rules that need no player are in
 * [SleepTimerRules], where they are tested.
 *
 * [scope] must dispatch on the player's application thread, since the fade job reads
 * [Player.duration] and [Player.currentPosition] directly.
 */
class SleepTimer(
    private val scope: CoroutineScope,
    val player: Player,
    private val clock: () -> Long = { System.currentTimeMillis() },
    /** The album a song belongs to, which is what End of album compares. A test supplies its own. */
    private val albumOf: (MediaItem?) -> String? = { it?.metadata?.album?.id },
) : Player.Listener {
    private var sleepTimerJob: Job? = null

    /** When the time is up, in epoch milliseconds, or -1 for a timer with no time: one waiting for an end, or none. */
    var triggerTime by mutableLongStateOf(-1L)
        private set

    /** What the timer waits for, or null for one that counts a time down, or none. */
    var endOf by mutableStateOf<SleepEnd?>(null)
        private set

    /** For the readers that only know the end of the song. */
    val pauseWhenSongEnd: Boolean
        get() = endOf == SleepEnd.SONG

    val isActive: Boolean
        get() = triggerTime != -1L || endOf != null

    /**
     * Counts what the words of the notification depend on: another song, a pause or a play, a seek.
     * Compose state so that the service can watch it, and bumped only while a timer runs.
     */
    var changes by mutableIntStateOf(0)
        private set

    /** Whether the volume fades out before the timer stops playback. A running timer takes it at once. */
    var fadeEnabled: Boolean = SleepTimerDefaults.FADE_ENABLED
        set(value) {
            if (field == value) return
            field = value
            rearm()
        }

    /** Length of the fade-out, in milliseconds. */
    var fadeDurationMs: Long = SleepTimerDefaults.FADE_DURATION_SECONDS * 1000L
        set(value) {
            if (field == value) return
            field = value
            rearm()
        }

    /**
     * Whether a timer whose time is up lets the song it is in finish, and stops after it. Read when
     * the time comes, so a switch flipped on a running timer counts.
     */
    var letSongFinish: Boolean = SleepTimerDefaults.LET_SONG_FINISH
        set(value) {
            if (field == value) return
            field = value
            rearm()
        }

    private val _fadeFactor = MutableStateFlow(1f)

    /** Multiplied into the player volume. 1f except while fading. */
    val fadeFactor: StateFlow<Float> = _fadeFactor.asStateFlow()

    /** Called when the active sleep timer completes. */
    var onFinish: () -> Unit = { player.pause() }

    /**
     * How long the fade that is running was given, which is its length unless the timer came into
     * the last seconds with less left than that: it then fades from the volume it has to nothing in
     * what there is, instead of dropping to a third at once. Zero when no fade runs.
     */
    private var fadeWindowMs = 0L

    /** The album of the song the timer last saw play, for End of album. */
    private var lastAlbum: String? = null

    private val window by lazy { Timeline.Window() }

    /** A timer of [minute] minutes, or the end of the song for -1. Whatever ran before is replaced. */
    fun start(minute: Int) {
        if (minute == -1) {
            start(SleepEnd.SONG)
            return
        }
        reset()
        triggerTime = SleepTimerRules.triggerAfter(clock(), minute)
        launchJob()
    }

    /** A timer that waits for the end of the song, its album or the queue. Whatever ran before is replaced. */
    fun start(end: SleepEnd) {
        reset()
        endOf = end
        if (end == SleepEnd.ALBUM) lastAlbum = albumOf(player.currentMediaItem)
        launchJob()
    }

    /**
     * Moves the stop [minutes] later. A timer that waits for an end becomes one with a time: the
     * time the end would come, as far as it can be told, plus the minutes.
     */
    fun extend(minutes: Int) {
        val end = endOf
        if (!isActive || minutes <= 0) return
        val now = clock()
        val base = if (end == null) triggerTime else now + (endLeftMs(end) ?: songLeftMs() ?: 0L)
        reset()
        triggerTime = SleepTimerRules.extended(base, now, minutes)
        launchJob()
    }

    fun clear() = reset()

    private fun reset() {
        sleepTimerJob?.cancel()
        sleepTimerJob = null
        endOf = null
        triggerTime = -1L
        lastAlbum = null
        fadeWindowMs = 0L
        _fadeFactor.value = 1f
    }

    /** A setting changed under a running timer: its job looks again from the start. */
    private fun rearm() {
        if (!isActive) return
        // The notification names the fade and the song after which it stops, which a setting changes.
        changes++
        launchJob()
    }

    /** A job only where there is something to drive: a time to wait out, or a fade at an end. */
    private fun launchJob() {
        sleepTimerJob?.cancel()
        sleepTimerJob = null
        if (triggerTime == -1L && !(endOf != null && fadeEnabled)) {
            fadeWindowMs = 0L
            _fadeFactor.value = 1f
            return
        }
        sleepTimerJob = scope.launch {
            while (currentCoroutineContext().isActive) {
                val wait = look(clock()) ?: break
                if (wait > 0L) delay(wait)
            }
        }
    }

    /**
     * One look at the timer at [now]: does what is due, and says how long to wait before the next,
     * or null when there is nothing more for the job to do.
     */
    internal fun look(now: Long): Long? {
        val end = endOf
        return if (end != null) lookAtEnd(end) else lookAtTime(now)
    }

    private fun lookAtTime(now: Long): Long? {
        val trigger = triggerTime
        if (trigger == -1L) return null
        val remaining = trigger - now
        if (remaining <= 0L) {
            return when (SleepTimerRules.atTimeUp(lateMs = -remaining, waitForSong = letSongFinish && isPlayingNow())) {
                SleepTimerRules.AtTimeUp.STOP -> {
                    onFinish()
                    triggerTime = -1L
                    fadeWindowMs = 0L
                    _fadeFactor.value = 1f
                    null
                }
                // The phone slept through the time, so the music now playing was put on after it.
                SleepTimerRules.AtTimeUp.LEAVE -> {
                    reset()
                    null
                }
                // From here the timer is the one set to the end of the song: its fade, its ending.
                SleepTimerRules.AtTimeUp.WAIT_FOR_SONG -> {
                    triggerTime = -1L
                    endOf = SleepEnd.SONG
                    fadeWindowMs = 0L
                    if (fadeEnabled) 0L else null
                }
            }
        }
        // With Let the song finish the fade belongs to the end of the song, not to the time.
        val fade = if (fadeEnabled && !letSongFinish) fadeDurationMs else 0L
        if (fade > 0L && remaining <= fade) {
            if (fadeWindowMs == 0L) fadeWindowMs = remaining
            _fadeFactor.value = SleepTimerRules.fadeFactor(remaining, fadeWindowMs)
            return min(FADE_TICK_MS, remaining)
        }
        fadeWindowMs = 0L
        _fadeFactor.value = 1f
        // Up to the start of the fade, or the end, but looked at again each minute: the clock can be set.
        return min(remaining - fade, LONGEST_WAIT_MS)
    }

    private fun lookAtEnd(end: SleepEnd): Long? {
        // Nothing to drive without a fade: the player's callbacks end the timer.
        if (!fadeEnabled) {
            fadeWindowMs = 0L
            _fadeFactor.value = 1f
            return null
        }
        // Only the song the timer ends with fades: the last one of the album or the queue.
        val left = fadeLeftMs(end)
        if (left == null || left > fadeDurationMs) {
            fadeWindowMs = 0L
            _fadeFactor.value = 1f
            return FADE_POLL_MS
        }
        if (fadeWindowMs == 0L) fadeWindowMs = left.coerceAtLeast(1L)
        _fadeFactor.value = SleepTimerRules.fadeFactor(left, fadeWindowMs)
        return if (player.isPlaying) FADE_TICK_MS else FADE_POLL_MS
    }

    /** How long, in real time, until the song playing ends, or null when it is not the song the timer ends with or its length is unknown. */
    private fun fadeLeftMs(end: SleepEnd): Long? {
        val left = songLeftMs() ?: return null
        return if (isFinalSong(end)) left else null
    }

    private fun songLeftMs(): Long? {
        val duration = player.duration
        if (duration == C.TIME_UNSET || duration <= 0L) return null
        return SleepTimerRules.wallLeft(duration - player.currentPosition, player.playbackParameters.speed)
    }

    private fun isPlayingNow(): Boolean =
        player.playWhenReady && player.playbackState != Player.STATE_ENDED && player.playbackState != Player.STATE_IDLE

    /** Whether the song playing is the last one the timer waits for: nothing in the queue follows it that the end covers. */
    private fun isFinalSong(end: SleepEnd): Boolean {
        if (end == SleepEnd.SONG) return true
        val timeline = player.currentTimeline
        val index = player.currentMediaItemIndex
        val next = if (timeline.isEmpty || index !in 0 until timeline.windowCount) C.INDEX_UNSET
        else timeline.getNextWindowIndex(index, Player.REPEAT_MODE_OFF, false)
        val albumNext = if (next == C.INDEX_UNSET) null else albumOf(timeline.getWindow(next, window).mediaItem)
        return SleepTimerRules.finalSong(end, player.repeatMode, next != C.INDEX_UNSET, albumOf(player.currentMediaItem), albumNext)
    }

    /** Whether the song at [index] is the queue's first, repeat left out: nothing comes before it. */
    private fun isFirstInQueue(index: Int): Boolean {
        val timeline = player.currentTimeline
        if (timeline.isEmpty || index !in 0 until timeline.windowCount) return false
        return timeline.getPreviousWindowIndex(index, Player.REPEAT_MODE_OFF, false) == C.INDEX_UNSET
    }

    /**
     * What the timer says about itself now, for the sheet and the notification, or null when none
     * runs. How long is left is null where it cannot be told: a song whose length is not known.
     */
    fun status(now: Long = clock()): Status? {
        val end = endOf
        if (end == null) {
            val trigger = triggerTime
            if (trigger == -1L) return null
            return Status(null, (trigger - now).coerceAtLeast(0L), trigger, afterSong = letSongFinish)
        }
        val left = endLeftMs(end)
        return Status(end, left, left?.let { now + it }, afterSong = false)
    }

    /** What the timer says about itself. [stopAtMs] is the clock time to show, [afterSong] says it waits for the end of a song. */
    data class Status(val end: SleepEnd?, val leftMs: Long?, val stopAtMs: Long?, val afterSong: Boolean)

    /**
     * Whether the song playing outlasts the time of a timer with a time: the one Let the song finish
     * will then let finish, which is worth naming. False when that cannot be told.
     */
    fun songOutlastsTime(now: Long = clock()): Boolean {
        val trigger = triggerTime
        if (trigger == -1L) return false
        val left = songLeftMs() ?: return false
        return SleepTimerRules.songAtTimeUp(now, trigger, left)
    }

    /** Real time until the end the timer waits for, or null when a length on the way is not known. */
    private fun endLeftMs(end: SleepEnd): Long? {
        val song = songLeftMs() ?: return null
        if (end == SleepEnd.SONG) return song
        val timeline = player.currentTimeline
        var index = player.currentMediaItemIndex
        if (timeline.isEmpty || index !in 0 until timeline.windowCount) return song
        val album = albumOf(player.currentMediaItem)
        val speed = player.playbackParameters.speed
        val following = ArrayList<Long?>()
        while (true) {
            val next = timeline.getNextWindowIndex(index, Player.REPEAT_MODE_OFF, false)
            if (next == C.INDEX_UNSET) break
            val w = timeline.getWindow(next, window)
            if (end == SleepEnd.ALBUM && SleepTimerRules.albumOver(album, albumOf(w.mediaItem))) break
            val ms = w.durationMs.takeIf { it != C.TIME_UNSET && it > 0L }
                ?: w.mediaItem.metadata?.duration?.takeIf { it > 0 }?.let { it * 1000L }
            following.add(ms?.let { SleepTimerRules.wallLeft(it, speed) })
            index = next
        }
        return SleepTimerRules.totalLeft(song, following)
    }

    override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
        if (isActive) changes++
        val end = endOf ?: return
        // Only the song ending by itself, or starting over on repeat, ends it. Next, previous and a
        // new queue counted as well, so with the timer set to the end of the song, pressing next
        // paused the song just chosen. The timer now carries over to that song instead.
        val over = when (end) {
            SleepEnd.SONG -> endsSong(reason)
            else -> endsAlbumOrQueue(end, reason, mediaItem)
        }
        // The album the timer ends is the one playing, so a skip into another carries it over.
        if (end == SleepEnd.ALBUM) lastAlbum = albumOf(mediaItem)
        if (over) finishEnd()
    }

    private fun endsAlbumOrQueue(end: SleepEnd, reason: Int, mediaItem: MediaItem?): Boolean {
        if (!endsSong(reason)) return false
        val repeatAll = player.repeatMode == Player.REPEAT_MODE_ALL
        return SleepTimerRules.endsAt(
            end, reason, repeatAll,
            albumBefore = lastAlbum,
            albumAfter = albumOf(mediaItem),
            newIsFirst = repeatAll && isFirstInQueue(player.currentMediaItemIndex),
        )
    }

    override fun onPlaybackStateChanged(@Player.State playbackState: Int) {
        if (isActive) changes++
        // The queue running out is the end of the song, of the album and of the queue.
        if (playbackState == Player.STATE_ENDED && endOf != null) finishEnd()
    }

    override fun onIsPlayingChanged(isPlaying: Boolean) {
        if (isActive) changes++
    }

    override fun onPositionDiscontinuity(oldPosition: Player.PositionInfo, newPosition: Player.PositionInfo, reason: Int) {
        // A jump somebody made. Skipped silence and the like come by the minute and change no word of it.
        if (isActive && reason == Player.DISCONTINUITY_REASON_SEEK) changes++
    }

    /** Ends an armed end-of timer, restoring the volume so the next play is not silent. */
    private fun finishEnd() {
        endOf = null
        lastAlbum = null
        fadeWindowMs = 0L
        sleepTimerJob?.cancel()
        sleepTimerJob = null
        onFinish()
        _fadeFactor.value = 1f
    }

    companion object {
        /** Whether a transition for [reason] means the song before it played to its end. */
        internal fun endsSong(reason: Int): Boolean = SleepTimerRules.endsSong(reason)

        /** Poll interval while actively fading. ~25 steps/s is smooth without being costly. */
        private const val FADE_TICK_MS = 40L

        /** Poll interval while waiting for a song to get close enough to its end to fade. */
        private const val FADE_POLL_MS = 1000L

        /** The longest wait before a timer with a time looks at the clock again. */
        private const val LONGEST_WAIT_MS = 60_000L
    }
}

/**
 * What a sleep timer decides, with no player in it: the times, the ends and the fade. The
 * [SleepTimer] reads the player and asks these.
 */
object SleepTimerRules {
    const val MINUTE_MS = 60_000L

    /**
     * How late the job may wake at the time and still stop the music. Later means the phone slept
     * through it, and whatever plays was put on after the time: it plays on.
     */
    const val STALE_MS = 10_000L

    /** The furthest a timer is moved ahead, as far as the sheet's own field goes: 24 hours. */
    const val MAX_AHEAD_MS = 24 * 60 * MINUTE_MS

    /** What to do when the time of a timer with a time has come. */
    enum class AtTimeUp {
        /** Stop the music now. */
        STOP,

        /** Let the song finish: from here the timer is the one set to the end of the song. */
        WAIT_FOR_SONG,

        /** Stop nothing, and let the timer go. */
        LEAVE,
    }

    fun atTimeUp(lateMs: Long, waitForSong: Boolean): AtTimeUp = when {
        lateMs > STALE_MS -> AtTimeUp.LEAVE
        waitForSong -> AtTimeUp.WAIT_FOR_SONG
        else -> AtTimeUp.STOP
    }

    /** The time a timer of [minutes] set at [nowMs] runs out. */
    fun triggerAfter(nowMs: Long, minutes: Int): Long = nowMs + minutes.coerceAtLeast(0) * MINUTE_MS

    /** The time moved [minutes] later, and never more than a day from [nowMs]. */
    fun extended(triggerMs: Long, nowMs: Long, minutes: Int): Long =
        min(triggerMs + minutes.coerceAtLeast(0) * MINUTE_MS, nowMs + MAX_AHEAD_MS)

    /** Whether a song with [songLeftMs] left at [nowMs] is still playing at [triggerMs]: the one Let the song finish lets finish. */
    fun songAtTimeUp(nowMs: Long, triggerMs: Long, songLeftMs: Long): Boolean = nowMs + songLeftMs > triggerMs

    /**
     * When the music stops for a timer with the time [triggerMs] at [nowMs]. Without Let the song
     * finish that is the time. With it, the end of the song playing then: [songLeftMs] is what is
     * left of the one playing now and [followingMs] the lengths of the songs after it. The queue
     * running out first stops it there. Null when a length that matters is not known.
     */
    fun musicStopsAt(nowMs: Long, triggerMs: Long, letSongFinish: Boolean, songLeftMs: Long?, followingMs: List<Long?>): Long? {
        if (!letSongFinish) return triggerMs
        var end = nowMs + (songLeftMs ?: return null)
        for (length in followingMs) {
            if (end > triggerMs) break
            end += length ?: return null
        }
        return end
    }

    /** Real time left of [mediaLeftMs] of music at [speed]. */
    fun wallLeft(mediaLeftMs: Long, speed: Float): Long {
        val s = if (speed.isFinite() && speed > 0f) speed else 1f
        return (mediaLeftMs.coerceAtLeast(0L) / s).toLong()
    }

    /** The time left to the end of the queue or the album: the song playing and those after it, or null if one length is unknown. */
    fun totalLeft(songLeftMs: Long?, followingMs: List<Long?>): Long? {
        var total = songLeftMs ?: return null
        for (length in followingMs) total += length ?: return null
        return total
    }

    /** Whether a transition for [reason] means the song before it played to its end, or started over on repeat. */
    fun endsSong(reason: Int): Boolean =
        reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO || reason == Player.MEDIA_ITEM_TRANSITION_REASON_REPEAT

    /**
     * Whether an album is over from one song to the next. A song with no album is its own: the
     * timer then stops at its end. Two songs of the same album are one album.
     */
    fun albumOver(before: String?, after: String?): Boolean = before.isNullOrBlank() || before != after

    /**
     * Whether the move to the song that now plays ends a timer set to [end]. A skip, a new queue
     * and a seek never do. [newIsFirst] says the song now playing is the queue's first, which under
     * repeat all is the queue starting over: the end of the queue, and of the album it was in.
     * Under repeat one the song starts over and neither ends: the end of the song is all that does.
     */
    fun endsAt(end: SleepEnd, reason: Int, repeatAll: Boolean, albumBefore: String?, albumAfter: String?, newIsFirst: Boolean): Boolean {
        if (!endsSong(reason)) return false
        val wrapped = repeatAll && (reason == Player.MEDIA_ITEM_TRANSITION_REASON_REPEAT || newIsFirst)
        return when (end) {
            SleepEnd.SONG -> true
            SleepEnd.ALBUM -> wrapped || (reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO && albumOver(albumBefore, albumAfter))
            SleepEnd.QUEUE -> wrapped
        }
    }

    /**
     * Whether the song playing is the last one a timer set to [end] waits for, which is the one
     * that fades. [hasNext] says a song follows it in the queue, repeat left out. Under repeat one
     * the album and the queue never end.
     */
    fun finalSong(end: SleepEnd, repeatMode: Int, hasNext: Boolean, albumNow: String?, albumNext: String?): Boolean = when (end) {
        SleepEnd.SONG -> true
        SleepEnd.ALBUM -> repeatMode != Player.REPEAT_MODE_ONE && (!hasNext || albumOver(albumNow, albumNext))
        SleepEnd.QUEUE -> repeatMode != Player.REPEAT_MODE_ONE && !hasNext
    }

    /** The volume factor with [remainingMs] to go of a fade given [windowMs]: 1 at the start, 0 at the end. */
    fun fadeFactor(remainingMs: Long, windowMs: Long): Float {
        if (windowMs <= 0L) return 1f
        return (remainingMs.toFloat() / windowMs).coerceIn(0f, 1f)
    }
}
