/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.playback

import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.lang.reflect.InvocationHandler
import java.lang.reflect.Proxy
import kotlin.coroutines.CoroutineContext
import kotlin.math.sqrt

/**
 * TransitionFade, the half of Fade between tracks that reads the player: which songs fade, and what
 * a transition, a seek, a pause or switching off does to a fade. The curve itself is pinned by
 * TransitionFadeEnvelopeTest.
 *
 * Notes on the harness, because this is a plain JVM test with isReturnDefaultValues = true:
 *  - Player is faked with a java.lang.reflect.Proxy, answering only what TransitionFade reads.
 *  - onEvents cannot be driven here: Player.Events is backed by FlagSet, which stores its bits in
 *    android.util.SparseBooleanArray, and the mockable android.jar answers false to every get().
 *    Each test therefore re-evaluates by nudging fadeMs, whose setter calls reschedule() without
 *    touching the fade-in flag, which is exactly what onEvents does. The other callbacks are called
 *    directly, in the order media3 sends them.
 *  - MediaItem.Builder().setUri() goes through android.net.Uri (null here), so items carry no
 *    localConfiguration and no album tag: every pair of different ids counts as different albums.
 *  - The scope's dispatcher drops every task, so the tick loop never runs and each reading is the
 *    one reschedule() takes synchronously.
 */
class TransitionFadeTest {

    private val song = 240_000L
    private val step = 1f / TransitionFadeEnvelope.STEPS

    /** Never runs anything: the tick loop stays parked, so readings are deterministic. */
    private val parked = object : CoroutineDispatcher() {
        override fun dispatch(context: CoroutineContext, block: Runnable) = Unit
    }
    private val scope = CoroutineScope(Job() + parked)

    @After
    fun tearDown() {
        scope.cancel()
    }

    private class State(
        val items: List<MediaItem>,
        var index: Int = 0,
        var position: Long = 0L,
        var duration: Long = 240_000L,
        var playing: Boolean = false,
        var repeatMode: Int = Player.REPEAT_MODE_OFF,
    )

    private class ListTimeline(private val items: List<MediaItem>) : Timeline() {
        override fun getWindowCount(): Int = items.size

        override fun getWindow(
            windowIndex: Int,
            window: Timeline.Window,
            defaultPositionProjectionUs: Long,
        ): Timeline.Window {
            window.mediaItem = items[windowIndex]
            window.firstPeriodIndex = windowIndex
            window.lastPeriodIndex = windowIndex
            return window
        }

        override fun getPeriodCount(): Int = items.size

        override fun getPeriod(periodIndex: Int, period: Timeline.Period, setIds: Boolean): Timeline.Period {
            period.windowIndex = periodIndex
            return period
        }

        override fun getIndexOfPeriod(uid: Any): Int = (uid as? Int) ?: C.INDEX_UNSET

        override fun getUidOfPeriod(periodIndex: Int): Any = periodIndex
    }

    private fun item(id: String): MediaItem = MediaItem.Builder().setMediaId(id).build()

    private fun state(vararg ids: String, index: Int = 0, position: Long = 0L) =
        State(ids.map { item(it) }, index = index, position = position)

    private fun fakePlayer(s: State): Player {
        val handler = InvocationHandler { _, method, _ ->
            when (method.name) {
                "getCurrentPosition" -> s.position
                "getDuration" -> s.duration
                "isPlaying" -> s.playing
                "getCurrentTimeline" -> ListTimeline(s.items)
                "getCurrentMediaItemIndex" -> s.index
                "getRepeatMode" -> s.repeatMode
                "getShuffleModeEnabled" -> false
                "getPlaybackParameters" -> PlaybackParameters.DEFAULT
                "hashCode" -> System.identityHashCode(s)
                "equals" -> false
                "toString" -> "FakePlayer"
                else -> throw UnsupportedOperationException("TransitionFade read ${method.name}")
            }
        }
        return Proxy.newProxyInstance(
            Player::class.java.classLoader,
            arrayOf(Player::class.java),
            handler,
        ) as Player
    }

    /** Switched on while paused, so no tick job is launched by the switch itself. */
    private fun fadeFor(s: State): TransitionFade {
        val wasPlaying = s.playing
        s.playing = false
        val fade = TransitionFade(scope, fakePlayer(s))
        fade.fadeMs = TransitionFadeEnvelope.fadeMsFor(TransitionFadeEnvelope.DEFAULT_SECONDS)
        fade.enabled = true
        s.playing = wasPlaying
        reevaluate(fade)
        return fade
    }

    /** What onEvents does: reschedule() and nothing else. See the header for why not onEvents. */
    private fun reevaluate(fade: TransitionFade) {
        val ms = fade.fadeMs
        fade.fadeMs = ms + 1
        fade.fadeMs = ms
    }

    private fun positionInfo(s: State, index: Int, positionMs: Long) = Player.PositionInfo(
        /* windowUid = */ null,
        /* mediaItemIndex = */ index,
        /* mediaItem = */ s.items[index],
        /* periodUid = */ null,
        /* periodIndex = */ index,
        /* positionMs = */ positionMs,
        /* contentPositionMs = */ positionMs,
        /* adGroupIndex = */ C.INDEX_UNSET,
        /* adIndexInAdGroup = */ C.INDEX_UNSET,
    )

    /** The two callbacks media3 sends, in its order, for a song that ends into the next. */
    private fun playsInto(fade: TransitionFade, s: State, to: Int) {
        val from = s.index
        val old = positionInfo(s, from, s.duration)
        s.index = to
        s.position = 0L
        fade.onPositionDiscontinuity(old, positionInfo(s, to, 0L), Player.DISCONTINUITY_REASON_AUTO_TRANSITION)
        fade.onMediaItemTransition(
            s.items[to],
            if (from == to) Player.MEDIA_ITEM_TRANSITION_REASON_REPEAT else Player.MEDIA_ITEM_TRANSITION_REASON_AUTO,
        )
    }

    /**
     * A pause, as the listener, the sleep timer or unplugged headphones make one, with the callback
     * media3 sends for it: playWhenReady goes false and the player stops.
     */
    private fun pause(fade: TransitionFade, s: State) {
        s.playing = false
        fade.onPlayWhenReadyChanged(false, Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST)
        reevaluate(fade)
    }

    private fun play(fade: TransitionFade, s: State) {
        s.playing = true
        fade.onPlayWhenReadyChanged(true, Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST)
        reevaluate(fade)
    }

    // ---------------------------------------------------------------------------------------
    // Pauses. A song resumed starts at full volume, like one skipped to.
    // ---------------------------------------------------------------------------------------

    @Test
    fun `a song the sleep timer paused at its start resumes at full volume`() {
        // Sleep timer "end of song": SleepTimer.onMediaItemTransition -> finishSongEnd -> pause(),
        // in the same callback round as the automatic change into b.
        val s = state("a", "b", "c", index = 0, position = song - 500L).apply { playing = true }
        val fade = fadeFor(s)
        playsInto(fade, s, to = 1)
        pause(fade, s)

        // Later the listener presses play. Nothing has moved: b is still at 0 ms.
        play(fade, s)

        // Commit f1ec1e0a1: "one skipped to, picked, resumed or seeked into starts at full volume".
        // The factor was 0f here, so b started from silence and faded in over the next 6 s.
        assertEquals(1f, fade.factor.value, 0f)
    }

    @Test
    fun `a pause part way into a fade in resumes at full volume`() {
        val s = state("a", "b", "c", index = 0, position = song - 500L).apply { playing = true }
        val fade = fadeFor(s)
        playsInto(fade, s, to = 1)
        s.position = 2_000L
        reevaluate(fade)
        assertTrue(fade.factor.value < 1f)
        pause(fade, s)
        play(fade, s)
        assertEquals(1f, fade.factor.value, 0f)
    }

    @Test
    fun `a song that has to buffer after being played into still fades in`() {
        val s = state("a", "b", "c", index = 0, position = song - 500L).apply { playing = true }
        val fade = fadeFor(s)
        playsInto(fade, s, to = 1)
        // Buffering stops the player without pausing it: isPlaying goes false and comes back, but
        // playWhenReady stays true, so media3 sends no onPlayWhenReadyChanged.
        s.playing = false
        reevaluate(fade)
        s.playing = true
        reevaluate(fade)
        assertEquals(0f, fade.factor.value, 0f)
        s.position = 6_000L
        reevaluate(fade)
        assertEquals(1f, fade.factor.value, 0f)
    }

    // ---------------------------------------------------------------------------------------
    // Transition reasons, repeat, switching off and songs of unknown length.
    // ---------------------------------------------------------------------------------------

    @Test
    fun `a song played straight into starts silent and rises over the fade`() {
        val s = state("a", "b", "c", index = 0, position = song - 500L).apply { playing = true }
        val fade = fadeFor(s)
        playsInto(fade, s, to = 1)
        reevaluate(fade)
        assertEquals(0f, fade.factor.value, 0f)
        s.position = 3_000L
        reevaluate(fade)
        assertEquals(sqrt(0.5f), fade.factor.value, step)
        s.position = 6_000L
        reevaluate(fade)
        assertEquals(1f, fade.factor.value, 0f)
    }

    @Test
    fun `a song skipped to starts at full volume`() {
        val s = state("a", "b", "c", index = 0, position = 100_000L).apply { playing = true }
        val fade = fadeFor(s)
        val old = positionInfo(s, 0, 100_000L)
        s.index = 1
        s.position = 0L
        fade.onPositionDiscontinuity(old, positionInfo(s, 1, 0L), Player.DISCONTINUITY_REASON_SEEK)
        fade.onMediaItemTransition(s.items[1], Player.MEDIA_ITEM_TRANSITION_REASON_SEEK)
        reevaluate(fade)
        assertEquals(1f, fade.factor.value, 0f)
    }

    @Test
    fun `a seek back to the start of a song that faded in plays it at full volume`() {
        val s = state("a", "b", "c", index = 0, position = song - 500L).apply { playing = true }
        val fade = fadeFor(s)
        playsInto(fade, s, to = 1)
        s.position = 30_000L
        reevaluate(fade)
        val old = positionInfo(s, 1, 30_000L)
        s.position = 0L
        fade.onPositionDiscontinuity(old, positionInfo(s, 1, 0L), Player.DISCONTINUITY_REASON_SEEK)
        reevaluate(fade)
        assertEquals(1f, fade.factor.value, 0f)
    }

    @Test
    fun `a new queue clears a fade in that had started`() {
        val s = state("a", "b", "c", index = 0, position = song - 500L).apply { playing = true }
        val fade = fadeFor(s)
        playsInto(fade, s, to = 1)
        fade.onMediaItemTransition(s.items[1], Player.MEDIA_ITEM_TRANSITION_REASON_PLAYLIST_CHANGED)
        reevaluate(fade)
        assertEquals(1f, fade.factor.value, 0f)
    }

    @Test
    fun `the end of a song fades only when a different song follows`() {
        val s = state("a", "b", index = 0, position = song - 1_000L).apply { playing = true }
        val fade = fadeFor(s)
        assertTrue(fade.factor.value < 0.3f)

        // Repeat one: the same song follows, so no fade out.
        s.repeatMode = Player.REPEAT_MODE_ONE
        reevaluate(fade)
        assertEquals(1f, fade.factor.value, 0f)

        // The last song of the queue with repeat off: nothing follows.
        s.repeatMode = Player.REPEAT_MODE_OFF
        s.index = 1
        reevaluate(fade)
        assertEquals(1f, fade.factor.value, 0f)

        // Repeat all wraps the last song into the first, which is a different song.
        s.repeatMode = Player.REPEAT_MODE_ALL
        reevaluate(fade)
        assertTrue(fade.factor.value < 0.3f)
    }

    @Test
    fun `repeat all over a one song queue never fades either end`() {
        val s = state("a", index = 0, position = song - 1_000L).apply {
            playing = true
            repeatMode = Player.REPEAT_MODE_ALL
        }
        val fade = fadeFor(s)
        assertEquals(1f, fade.factor.value, 0f)
        playsInto(fade, s, to = 0)
        reevaluate(fade)
        assertEquals(1f, fade.factor.value, 0f)
    }

    @Test
    fun `switching off in the middle of a fade puts the volume straight back`() {
        val s = state("a", "b", index = 0, position = song - 1_000L).apply { playing = true }
        val fade = fadeFor(s)
        assertTrue(fade.factor.value < 1f)
        fade.enabled = false
        assertEquals(1f, fade.factor.value, 0f)
    }

    @Test
    fun `a song of unknown length is left alone even when it was played into`() {
        val s = state("a", "b", "c", index = 0, position = song - 500L).apply { playing = true }
        val fade = fadeFor(s)
        playsInto(fade, s, to = 1)
        s.duration = C.TIME_UNSET
        reevaluate(fade)
        assertEquals(1f, fade.factor.value, 0f)
    }
}
