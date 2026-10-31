/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.testerlock

import androidx.activity.ComponentActivity
import androidx.compose.runtime.Composable

/**
 * A debug build has no tester lock: only a preview published for testers has one (src/preview).
 * MainActivity asks every build the same question, and here nothing ever holds the app.
 */
object TesterLock {
    @Suppress("UNUSED_PARAMETER")
    @Composable
    fun holds(activity: ComponentActivity): Boolean = false
}
