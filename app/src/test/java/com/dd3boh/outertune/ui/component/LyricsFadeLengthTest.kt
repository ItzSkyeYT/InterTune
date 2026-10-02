/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.ui.component

import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LyricsFadeLengthTest {

    @Test
    fun `a view of any ordinary size keeps the fade it always had`() {
        var height = 256.dp
        while (height <= 2000.dp) {
            assertEquals(64.dp, lyricsFadeLength(height))
            height += 13.dp
        }
    }

    @Test
    fun `the two fades never meet, so some lines always show at full strength`() {
        // About 117dp on a keypad phone with a navigation bar, status bar included.
        for (height in listOf(40.dp, 80.dp, 117.dp, 165.dp, 255.dp)) {
            val fade = lyricsFadeLength(height)
            assertTrue("fades meet at $height", height - fade * 2 >= height / 2)
        }
    }
}
