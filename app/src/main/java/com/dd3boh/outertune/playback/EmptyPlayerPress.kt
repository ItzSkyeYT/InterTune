/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.playback

import android.view.KeyEvent

/**
 * Play/pause on a headset, a speaker or a car, pressed while the app runs and has played nothing yet.
 *
 * After a cold start the player is empty. The song the app shows comes from the saved queue, which goes
 * into the player only when something asks for it: the app's own buttons do (PlayerConnection.loadQueueIfEmpty),
 * and so does Resume playback on launch. A key from Bluetooth or a wired headset reaches a running service
 * through Android's own session, and media3 1.8.0 takes play/pause and the hook there as a toggle of the
 * player as it is: MediaSessionImpl.onMediaButtonEvent waits out a double tap, then
 * MediaSessionLegacyStub.handleMediaPlayPauseOnHandler calls Util.handlePlayPauseButtonAction, which asks for
 * no saved queue. An empty player stays empty and the press does nothing; it took a tap in the app. Play
 * goes another way in media3 (applyMediaButtonKeyEvent, then handleMediaControllerPlayRequest), which loads
 * the saved queue and plays it, as a press does when the app is closed. Seen on the emulator on 12 Nov 2026:
 * four hook presses at a running service with nothing loaded toggled an empty player (experiments/bugs/issue-153).
 *
 * So play/pause and the hook now do there what play does: the saved queue plays. A key that came as a start
 * of the service (the widget, the notification, a press with the app closed) is media3's own and resumes already.
 */
object EmptyPlayerPress {
    /**
     * Whether a media button should play the saved queue: [keyCode] is play/pause or a wired headset's hook,
     * [action] its press and [repeatCount] 0 (not held), sent through Android's own session ([legacy]: a
     * Bluetooth device, a wired headset, a car, another app), while the player holds no song ([empty]).
     */
    fun playsSavedQueue(keyCode: Int, action: Int, repeatCount: Int, legacy: Boolean, empty: Boolean): Boolean =
        empty && isPlayPausePress(keyCode, action, repeatCount, legacy)

    /**
     * Whether a press is the second of a double press whose first one played the saved queue, [sinceLoadMs]
     * ago (null: no press did), within Android's double tap time [doubleTapMs]: it is taken and does nothing.
     *
     * media3 tells a double press (next) from a single one (play/pause) only for presses it has seen, and the
     * one that played the saved queue never reached it. So the second press of a double click, or a second
     * press because nothing seemed to happen, went to media3 as a new single press, and a moment later it
     * toggled the song that had just started: the saved queue loaded and stayed paused.
     */
    fun secondOfDoublePress(keyCode: Int, action: Int, repeatCount: Int, legacy: Boolean, sinceLoadMs: Long?, doubleTapMs: Long): Boolean =
        sinceLoadMs != null && sinceLoadMs in 0..doubleTapMs && isPlayPausePress(keyCode, action, repeatCount, legacy)

    /** Play/pause or the hook, pressed (not released, not held), through Android's own session: what media3 counts for a double press. */
    private fun isPlayPausePress(keyCode: Int, action: Int, repeatCount: Int, legacy: Boolean): Boolean =
        legacy && action == KeyEvent.ACTION_DOWN && repeatCount == 0 &&
            (keyCode == KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE || keyCode == KeyEvent.KEYCODE_HEADSETHOOK)

    /** The key in the log's words. */
    fun keyName(keyCode: Int): String = if (keyCode == KeyEvent.KEYCODE_HEADSETHOOK) "headset button" else "play/pause"
}
