/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.testerlock

import android.content.Context
import androidx.activity.ComponentActivity
import androidx.compose.runtime.Composable
import com.dd3boh.outertune.ui.screens.settings.ExtraSettingsPage

/**
 * A debug build has no tester lock: only the Preview built with the lock on and the Tester build, the app
 * testers are given, have one (src/preview, which the tester type takes too).
 * MainActivity asks every build the same question, and here nothing ever holds the app.
 */
object TesterLock {
    @Suppress("UNUSED_PARAMETER")
    @Composable
    fun holds(activity: ComponentActivity): Boolean = false

    /** Settings asks every build for the Testers page, and here there is none: no entry, no screen, nothing to search for. */
    internal val testers: ExtraSettingsPage? = null

    /** A report asks every build whose tester sends it, and here nobody is a tester: no number. */
    @Suppress("UNUSED_PARAMETER")
    fun testerNumber(context: Context): Int? = null
}
