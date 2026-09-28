/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune

import android.content.Intent
import com.dd3boh.outertune.widget.WidgetCommands
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Whether a widget tap's song is still owed: a song replayed when the app is reopened from
 * recents or restored after process death was the bug.
 */
class WidgetSongOwedTest {

    @Test
    fun `a fresh widget tap owes its song`() {
        assertTrue(widgetSongOwed(WidgetCommands.ACTION_PLAY_SONG, hadSavedInstanceState = false, flags = 0))
    }

    @Test
    fun `restored after process death, nothing is owed`() {
        assertFalse(widgetSongOwed(WidgetCommands.ACTION_PLAY_SONG, hadSavedInstanceState = true, flags = 0))
    }

    @Test
    fun `reopened from recents, nothing is owed`() {
        assertFalse(
            widgetSongOwed(
                WidgetCommands.ACTION_PLAY_SONG,
                hadSavedInstanceState = false,
                flags = Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY,
            )
        )
    }

    @Test
    fun `a non-widget launch owes nothing`() {
        assertFalse(widgetSongOwed(Intent.ACTION_MAIN, hadSavedInstanceState = false, flags = 0))
    }
}
