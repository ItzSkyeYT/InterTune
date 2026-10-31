/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.testerlock

import androidx.activity.ComponentActivity
import androidx.compose.runtime.Composable

/**
 * A release has no tester lock, and none of its code or words: only the Preview built with the lock
 * on and the Tester build, the app testers are given, have one (src/preview). MainActivity asks every
 * build the same question, and here nothing ever holds the app. userdebug takes this file too
 * (build.gradle.kts, sourceSets).
 */
object TesterLock {
    @Suppress("UNUSED_PARAMETER")
    @Composable
    fun holds(activity: ComponentActivity): Boolean = false
}
