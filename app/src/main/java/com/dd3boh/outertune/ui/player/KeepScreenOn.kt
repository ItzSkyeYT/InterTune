/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.ui.player

import android.view.View
import java.util.WeakHashMap

/**
 * The reasons something in the app can ask to keep the screen on.
 */
enum class KeepScreenOnReason {
    LYRICS,
    IMMERSIVE_LANDSCAPE,
    RECOGNITION,
}

/**
 * Holds which reasons are asking for the screen to stay on.
 *
 * Pulled out of [KeepScreenOnController] so the actual "any reason keeps it on" rule has no
 * Android dependency and can be unit tested directly.
 */
class KeepScreenOnReasons {
    private val active = mutableSetOf<KeepScreenOnReason>()

    /**
     * Adds or removes [reason] and returns whether any reason is active afterwards.
     */
    @Synchronized
    fun set(reason: KeepScreenOnReason, on: Boolean): Boolean {
        if (on) active.add(reason) else active.remove(reason)
        return active.isNotEmpty()
    }
}

/**
 * Single owner of a View's [View.keepScreenOn][android.view.View.setKeepScreenOn] flag, shared by
 * every reason that wants the screen held awake (lyrics in the open player, immersive landscape
 * while playing, recognition listening).
 *
 * keepScreenOn is one boolean on one View, so two places writing it directly race: whichever
 * disposes last wins and silently clears the other's request. Each caller here instead adds or
 * removes only its own [KeepScreenOnReason], and the flag is only ever written as
 * "some reason is active", so one caller's cleanup can never clobber another's.
 *
 * Keyed on the View's identity (a [WeakHashMap], so a destroyed View's entry is not kept alive)
 * rather than passed in from one owner, since lyrics/landscape (in the player) and recognition (in
 * a screen or a sheet) are unrelated composables that may be alive at the same time and all target
 * the same Activity root View.
 */
object KeepScreenOnController {
    private val perView = WeakHashMap<View, KeepScreenOnReasons>()

    @Synchronized
    private fun reasonsFor(view: View): KeepScreenOnReasons =
        perView.getOrPut(view) { KeepScreenOnReasons() }

    /**
     * Adds or removes [reason] for [view], applying the resulting flag immediately.
     */
    fun set(view: View, reason: KeepScreenOnReason, on: Boolean) {
        view.keepScreenOn = reasonsFor(view).set(reason, on)
    }
}
