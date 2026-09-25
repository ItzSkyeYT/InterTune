/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.playback

import android.util.Log
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import com.dd3boh.outertune.BuildConfig
import com.dd3boh.outertune.extensions.metadata
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Fades a song out over its last few seconds and the next one in over its first few.
 *
 * Not a crossfade. The songs play one after the other and never overlap, because overlapping them
 * needs a second player (docs/research/dj-transitions.md weighs that up). This is the cheap half,
 * and it rides player.volume the way the sleep timer's fade does, since that is the one gain stage
 * audio offload cannot skip. It never sets the volume itself: [factor] is one more term in the
 * single volume combine in MusicService, so it cannot fight the sleep timer, normalisation or
 * proximity over which of them wrote last.
 *
 * The curve and the rules for when there is a fade live in [TransitionFadeEnvelope]. This class
 * only reads the player and decides when to read it again.
 *
 * [scope] must dispatch on the player's application thread, since everything here reads the player
 * directly.
 */
class TransitionFade(
    private val scope: CoroutineScope,
    private val player: Player,
) : Player.Listener {

    private val _factor = MutableStateFlow(1f)

    /** Multiplied into the player volume. 1f except while fading, and always 1f while switched off. */
    val factor: StateFlow<Float> = _factor.asStateFlow()

    /** Whether the setting is on. Off costs nothing: no job, and every callback returns at once. */
    var enabled: Boolean = false
        set(value) {
            if (field == value) return
            field = value
            // Switched on partway into a song, the start of that song has already gone by at full
            // volume, and fading in from here would only make the music dip for no reason.
            fadeInArmed = false
            reschedule()
        }

    /** The chosen fade length. Each song's own fade can be shorter, see TransitionFadeEnvelope.lengthMs. */
    var fadeMs: Long = TransitionFadeEnvelope.fadeMsFor(TransitionFadeEnvelope.DEFAULT_SECONDS)
        set(value) {
            if (field == value) return
            field = value
            reschedule()
        }

    /**
     * Whether the current song fades in. Set only when the song before played straight into it,
     * and cleared by anything that moves the position by hand, so a seek back to the start of a
     * song plays it at full volume rather than fading it in a second time.
     */
    private var fadeInArmed = false

    private var tickJob: Job? = null
    private val window = Timeline.Window()

    override fun onPositionDiscontinuity(
        oldPosition: Player.PositionInfo,
        newPosition: Player.PositionInfo,
        reason: Int,
    ) {
        if (!enabled) return
        when (reason) {
            // The one way a song arrives out of the end of the last. The new position is where the
            // song started, as media3 reports it with the change, not wherever it has got to by the
            // time this callback runs, so a busy main thread cannot talk it out of its fade.
            Player.DISCONTINUITY_REASON_AUTO_TRANSITION -> {
                fadeInArmed = TransitionFadeEnvelope.fadesIn(
                    from = oldPosition.mediaItem?.toTrack(),
                    to = newPosition.mediaItem?.toTrack(),
                    startPositionMs = newPosition.positionMs,
                )
                if (BuildConfig.DEBUG) Log.d(TAG, "next song fades in: $fadeInArmed")
            }

            // The same song carrying on: silence skipped, or the source adjusting its own
            // timeline. Whatever it was doing, it keeps doing, from the new position.
            Player.DISCONTINUITY_REASON_SILENCE_SKIP,
            Player.DISCONTINUITY_REASON_INTERNAL -> Unit

            // A seek, within the song or to another, or the song taken out of the queue.
            else -> fadeInArmed = false
        }
    }

    override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
        // A new song that did not come out of the end of the last one: skipped to, picked, or a
        // new queue. It starts at full volume. The automatic case was settled by the discontinuity
        // above, which media3 always reports alongside it.
        if (reason != Player.MEDIA_ITEM_TRANSITION_REASON_AUTO) fadeInArmed = false
    }

    override fun onEvents(player: Player, events: Player.Events) {
        if (!enabled) return
        // Everything that moves the position, changes which song comes next, or starts or stops
        // the clock. Each is a reason to work the factor out again at once rather than at the next
        // tick, and between them they are why the long wait before a fade out can be trusted.
        if (events.containsAny(
                Player.EVENT_IS_PLAYING_CHANGED,
                Player.EVENT_POSITION_DISCONTINUITY,
                Player.EVENT_MEDIA_ITEM_TRANSITION,
                Player.EVENT_TIMELINE_CHANGED,
                Player.EVENT_REPEAT_MODE_CHANGED,
                Player.EVENT_SHUFFLE_MODE_ENABLED_CHANGED,
                Player.EVENT_PLAYBACK_PARAMETERS_CHANGED,
            )
        ) {
            reschedule()
        }
    }

    /**
     * Works the factor out now, then keeps it current only while the music is playing, and only
     * as often as the curve can actually change: every tick through a fade, and one wait from the
     * end of a fade in to the start of the fade out.
     */
    private fun reschedule() {
        tickJob?.cancel()
        tickJob = null
        if (!enabled) {
            _factor.value = 1f
            return
        }
        val first = update() ?: return
        if (!player.isPlaying) return
        tickJob = scope.launch {
            var next: Long? = first
            while (next != null && player.isPlaying) {
                delay(next)
                next = update()
            }
        }
    }

    /** Sets the factor for where the player is now, and says how long it can be left. */
    private fun update(): Long? {
        val position = player.currentPosition
        val duration = player.duration
        val fadeIn = fadeInArmed
        val fadeOut = fadesIntoNext()
        val value = TransitionFadeEnvelope.factor(position, duration, fadeMs, fadeIn, fadeOut)
        // Only where it leaves full volume and where it comes back, which is enough to check a
        // fade against the song's clock without a line for every step in between.
        if (BuildConfig.DEBUG && (value < 1f) != (_factor.value < 1f)) {
            Log.d(TAG, "${if (value < 1f) "below" else "back to"} full volume at $position of $duration ms")
        }
        _factor.value = value
        return TransitionFadeEnvelope.nextCheckMs(
            position, duration, fadeMs, fadeIn, fadeOut, player.playbackParameters.speed
        )
    }

    /**
     * Whether the current song fades out, which depends on the song that will actually follow it.
     *
     * Asked of the timeline with the real repeat mode rather than through nextMediaItemIndex,
     * which treats repeat one as off because it answers where the next button goes. At the end of
     * a song under repeat one, the song that plays is the same one again.
     */
    private fun fadesIntoNext(): Boolean {
        val timeline = player.currentTimeline
        if (timeline.isEmpty) return false
        val current = player.currentMediaItemIndex
        if (current !in 0 until timeline.windowCount) return false
        val next = timeline.getNextWindowIndex(current, player.repeatMode, player.shuffleModeEnabled)
        if (next == C.INDEX_UNSET || next !in 0 until timeline.windowCount) return false
        val following = timeline.getWindow(next, window).mediaItem.toTrack()
        val playing = timeline.getWindow(current, window).mediaItem.toTrack()
        return TransitionFadeEnvelope.fadesBetween(playing, following)
    }

    private fun MediaItem.toTrack() = TransitionFadeEnvelope.Track(mediaId, metadata?.album?.id)

    companion object {
        private const val TAG = "TransitionFade"
    }
}
