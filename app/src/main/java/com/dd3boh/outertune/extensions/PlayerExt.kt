package com.dd3boh.outertune.extensions

import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.Player.REPEAT_MODE_ALL
import androidx.media3.common.Player.REPEAT_MODE_OFF
import androidx.media3.common.Player.REPEAT_MODE_ONE
import androidx.media3.common.Timeline
import androidx.media3.common.TrackSelectionParameters
import com.dd3boh.outertune.models.MediaMetadata
import java.util.ArrayDeque

/**
 * Should the button show "play" rather than "pause"?
 *
 * Mirrors media3's own Util.shouldShowPlayButton, which is what the notification and MediaSession
 * buttons use. That is why the notification's play button always worked on the first tap while the
 * one inside the app did not.
 */
fun Player.shouldShowPlayButton(): Boolean =
    !playWhenReady || playbackState == Player.STATE_IDLE || playbackState == Player.STATE_ENDED

/**
 * Actually start playback, mirroring Util.handlePlayButtonAction: prepare when idle, rewind when
 * ended, then play. Never a toggle.
 */
fun Player.playSafe() {
    // prepare() on an empty timeline lands in STATE_ENDED rather than IDLE, which is exactly how
    // the play button used to turn itself into a replay button on the first tap.
    if (currentTimeline.isEmpty()) return
    when (playbackState) {
        Player.STATE_IDLE -> prepare()
        Player.STATE_ENDED -> seekTo(0, 0)
    }
    play()
}

/**
 * The old version flipped playWhenReady unconditionally and only called prepare() when the player
 * was BOTH paused and idle. Coming back to a queue that had finished, playWhenReady was still true,
 * so the first tap skipped prepare and set it to false: silence, and a tap wasted.
 */
fun Player.togglePlayPause() {
    if (shouldShowPlayButton()) {
        playSafe()
    } else {
        pause()
    }
}

fun Player.toggleRepeatMode() {
    repeatMode = when (repeatMode) {
        REPEAT_MODE_OFF -> REPEAT_MODE_ALL
        REPEAT_MODE_ALL -> REPEAT_MODE_ONE
        REPEAT_MODE_ONE -> REPEAT_MODE_OFF
        else -> throw IllegalStateException()
    }
}

fun Player.toggleShuffleMode() {
    shuffleModeEnabled = !shuffleModeEnabled
}

fun Player.getQueueWindows(): List<Timeline.Window> {
    val timeline = currentTimeline
    if (timeline.isEmpty) {
        return emptyList()
    }
    val queue = ArrayDeque<Timeline.Window>()
    val queueSize = timeline.windowCount

    val currentMediaItemIndex: Int = currentMediaItemIndex
    queue.add(timeline.getWindow(currentMediaItemIndex, Timeline.Window()))

    var firstMediaItemIndex = currentMediaItemIndex
    var lastMediaItemIndex = currentMediaItemIndex
    while ((firstMediaItemIndex != C.INDEX_UNSET || lastMediaItemIndex != C.INDEX_UNSET) && queue.size < queueSize) {
        if (lastMediaItemIndex != C.INDEX_UNSET) {
            lastMediaItemIndex = timeline.getNextWindowIndex(lastMediaItemIndex, REPEAT_MODE_OFF, false)
            if (lastMediaItemIndex != C.INDEX_UNSET) {
                queue.add(timeline.getWindow(lastMediaItemIndex, Timeline.Window()))
            }
        }
        if (firstMediaItemIndex != C.INDEX_UNSET && queue.size < queueSize) {
            firstMediaItemIndex = timeline.getPreviousWindowIndex(firstMediaItemIndex, REPEAT_MODE_OFF, false)
            if (firstMediaItemIndex != C.INDEX_UNSET) {
                queue.addFirst(timeline.getWindow(firstMediaItemIndex, Timeline.Window()))
            }
        }
    }
    return queue.toList()
}

fun Player.getCurrentQueueIndex(): Int {
    if (currentTimeline.isEmpty) {
        return -1
    }
    var index = 0
    var currentMediaItemIndex = currentMediaItemIndex
    while (currentMediaItemIndex != C.INDEX_UNSET) {
        currentMediaItemIndex = currentTimeline.getPreviousWindowIndex(currentMediaItemIndex, REPEAT_MODE_OFF, false)
        if (currentMediaItemIndex != C.INDEX_UNSET) {
            index++
        }
    }
    return index
}

val Player.currentMetadata: MediaMetadata?
    get() = currentMediaItem?.metadata

val Player.mediaItems: List<MediaItem>
    get() = object : AbstractList<MediaItem>() {
        override val size: Int
            get() = mediaItemCount

        override fun get(index: Int): MediaItem = getMediaItemAt(index)
    }

fun Player.findNextMediaItemById(mediaId: String): MediaItem? {
    for (i in currentMediaItemIndex until mediaItemCount) {
        if (getMediaItemAt(i).mediaId == mediaId) {
            return getMediaItemAt(i)
        }
    }
    return null
}


fun Player.setOffloadEnabled(enabled: Boolean) {
    trackSelectionParameters = trackSelectionParameters.buildUpon()
        .setAudioOffloadPreferences(
            TrackSelectionParameters.AudioOffloadPreferences
                .Builder()
                .setAudioOffloadMode(
                    if (enabled) {
                        TrackSelectionParameters.AudioOffloadPreferences.AUDIO_OFFLOAD_MODE_ENABLED
                    } else {
                        TrackSelectionParameters.AudioOffloadPreferences.AUDIO_OFFLOAD_MODE_DISABLED
                    }
                )
                .build()
        ).build()
}