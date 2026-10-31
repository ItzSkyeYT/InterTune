/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.testerlock

import androidx.activity.ComponentActivity
import androidx.compose.runtime.Composable

/**
 * A debug build has no tester lock: only the Preview built with the lock on and the Tester build, the app
 * testers are given, have one (src/preview, which the tester type takes too).
 * MainActivity asks every build the same question, and here nothing ever holds the app.
 */
object TesterLock {
    @Suppress("UNUSED_PARAMETER")
    @Composable
    fun holds(activity: ComponentActivity): Boolean = false
}
