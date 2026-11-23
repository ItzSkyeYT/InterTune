/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.ui.utils

import androidx.activity.compose.LocalActivity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.currentStateAsState

/**
 * Whether a dialog may put its window up now: while the app is on screen (started, as Player.kt
 * counts it), and never on an activity that is closing.
 *
 * A dialog is a window of its own, and Android adds it only to a screen it still knows. 0.12.9
 * crashed with "Unable to add window, the token is not valid; is your activity running?" (#197,
 * a Pixel 8 Pro on Android 17): a dialog came up in a recomposition after Android had let its
 * screen go, while the app's composition was still running. A dialog that opens by itself (a
 * check that answers, a timer, a setting, the phone) can come up at any such moment; one opened
 * by a tap cannot, since the app is on screen to be tapped.
 */
internal fun windowMayOpen(state: Lifecycle.State, finishing: Boolean): Boolean =
    !finishing && state.isAtLeast(Lifecycle.State.STARTED)

/** Whether [OpensOnScreen]'s dialog is up already. Plain, not state: it changes nothing on screen. */
private class Opened {
    var yes = false
}

/**
 * For a dialog that opens by itself: [content] comes when [windowMayOpen] says the screen can take
 * it. One owed while the app is away waits for the app to be back, so nothing is lost, only put
 * off. Once up it stays when the app goes away, as its window does, with everything it holds.
 */
@Composable
fun OpensOnScreen(content: @Composable () -> Unit) {
    val state by LocalLifecycleOwner.current.lifecycle.currentStateAsState()
    val finishing = LocalActivity.current?.isFinishing == true
    val opened = remember { Opened() }
    if (opened.yes || windowMayOpen(state, finishing)) {
        // Marked once the composition that adds the window is applied: one thrown away adds nothing.
        SideEffect { opened.yes = true }
        content()
    }
}
