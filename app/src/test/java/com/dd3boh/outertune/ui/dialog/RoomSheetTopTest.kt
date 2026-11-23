/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.ui.dialog

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The room sheet on his Pixel 5 in a room, 21 Nov 2026: it jumped about 20dp at the end of its opening, and dragged down
 * slowly it sprang back 17dp every few frames instead of following the finger. Material's sheet (1.4) gives its page the
 * status bar's room less as far as the sheet is up under the bar. A page that left less room above it than the bar is
 * tall was then taller the higher the sheet went, so the place it rests moved as it moved, and Material put it there at
 * once, a drag on the page (its scroll, handed on) or not. Upright, the sheet now stands below the status bar and its
 * page never asks for the top: its height is the same wherever the sheet is.
 */
class RoomSheetTopTest {

    private val sheet = File("src/main/java/com/dd3boh/outertune/ui/dialog/TogetherDialog.kt").readText()
        .substringAfter("fun TogetherSheet(").substringAfter("ModalBottomSheet(").substringBefore("\n    ) {\n")

    @Test
    fun `upright, the sheet stands below the status bar`() {
        assertTrue(
            sheet,
            "modifier = if (landscape.active) Modifier else Modifier.windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top))" in sheet,
        )
    }

    @Test
    fun `its page never asks for the top, which Material takes from it as the sheet moves`() {
        val insets = sheet.substringAfter("contentWindowInsets = {").substringBefore("\n        },")
        assertFalse(insets, Regex("""WindowInsets\.safeDrawing(?!\.only)""").containsMatchIn(insets))
        assertFalse(insets, "Top" in insets)
        assertTrue(insets, "else WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal + WindowInsetsSides.Bottom)" in insets)
    }
}
