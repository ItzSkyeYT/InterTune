/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.playback

import android.view.KeyEvent

/**
 * Play pressed on a queue that has played to its end, on the widget or the player's notification.
 *
 * An ended player is still set to play, and media3 1.8.0 reads that for play/pause
 * (MediaSessionImpl.applyMediaButtonKeyEvent): the press paused it, and nothing played. A second press, a play then,
 * played the last song again (Util.handlePlayButtonAction). The app's own Play starts the queue again from its first
 * song (playSafe), and so does this press now.
 *
 * Only a press that comes with media3's own controller: the widget, the notification and the media button receiver,
 * which start the service with it. A press through Android's own session (a headset, a car) stays media3's, which
 * waits out a double press for it, and a guest who only listens in a room has a mute there instead.
 */
object EndedQueuePress {
    /**
     * Whether a media button starts the queue again from its first song: [keyCode] is play or play/pause, [action] its
     * press and [repeatCount] 0 (not held), not through Android's own session ([legacy]), while the player has [ended]
     * with songs in it, on a phone that does not only listen in a room ([listensInRoom]).
     */
    fun startsOver(keyCode: Int, action: Int, repeatCount: Int, legacy: Boolean, ended: Boolean, listensInRoom: Boolean): Boolean =
        ended && !legacy && !listensInRoom && action == KeyEvent.ACTION_DOWN && repeatCount == 0 &&
            (keyCode == KeyEvent.KEYCODE_MEDIA_PLAY || keyCode == KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE)
}
