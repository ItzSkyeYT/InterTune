/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.ui.screens.settings.fragments

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The sound stage picture is held under the page's title while a slider further down its group
 * is on screen, and only then.
 *
 * A release has one slider in the group, Stage width (the other three are held back), and the
 * group is then about as tall as the picture: held, the picture slid down over its own slider
 * as the page scrolled, the one thing it is there to be seen beside. With one slider it stays
 * where it stands and scrolls with the page.
 */
class StagePictureHeldTest {

    // A page in pixels: the title's foot at 300, the picture 330 tall.
    private val ceiling = 300f
    private val tall = 330f

    @Test
    fun `with several sliders it follows the page down to the end of the group`() {
        val foot = { stands: Float -> stands + tall + 900f }      // three more rows under it
        assertEquals("not scrolled: where it stands", 0f, stageShift(ceiling, 500f, tall, foot(500f), held = true), 0f)
        assertEquals("scrolled 200 past the title: drawn 200 lower", 200f, stageShift(ceiling, 100f, tall, foot(100f), held = true), 0f)
        assertEquals("never past the last slider", 900f, stageShift(ceiling, -2000f, tall, foot(-2000f), held = true), 0f)
    }

    @Test
    fun `with one slider it is never moved`() {
        val foot = { stands: Float -> stands + tall + 280f }      // one row and its slider
        for (stands in listOf(500f, 300f, 100f, 0f, -200f, -2000f)) {
            assertEquals("moved at $stands", 0f, stageShift(ceiling, stands, tall, foot(stands), held = false), 0f)
        }
    }

    @Test
    fun `the picture is held only where the other sliders are offered`() {
        val page = File("src/main/java/com/dd3boh/outertune/ui/screens/settings/fragments/PlayerFrag.kt").readText()
        assertTrue(
            "StageWidthFrag does not tie the holding to the sliders under Stage width",
            Regex("""held\s*=\s*tuning\b""").containsMatchIn(page) && "val tuning = Unreleased.SPATIAL_TUNING" in page,
        )
        assertTrue("the held picture does not go through stageShift", "stageShift(ceiling, stands.floatValue, tall.floatValue, foot(), held)" in page)
    }
}
