/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.ui.screens.mix

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * What TalkBack hears for the canvas follows the change drawn (10 Nov 2026, the Pixel 5): after
 * Rise replaced the moves it still listed the old ones, because its lambda held only the editor,
 * the same object after every edit, and was kept as it was. It reads the spec it is drawn from, and
 * describes that spec, not whatever the editor holds a moment later.
 */
class CanvasDescriptionTest {

    private val dir = File("src/main/java/com/dd3boh/outertune/ui/screens/mix")

    @Test
    fun `the canvas describes the spec it draws, in a lambda that holds it`() {
        val view = File(dir, "MixCanvasView.kt").readText()
        assertTrue(".semantics { contentDescription = words.summary(editor, spec) }" in view)
        assertTrue("never from the editor alone", ".semantics { contentDescription = words.summary(editor) }" !in view)
        val simple = File(dir, "MixSimpleSheet.kt").readText()
        assertTrue("words.summary(editor, spec)" in simple)
    }

    @Test
    fun `the summary is worked out from the spec it is given`() {
        val words = File(dir, "EditorWords.kt").readText()
        val summary = words.substringAfter("fun summary(e: MixEditor, s: TransitionSpec = e.spec): String {").substringBefore("\n    }\n")
        assertTrue(summary, "e.lit(s)" in summary)
        assertTrue(summary, "e.moments(s)" in summary)
        assertTrue(summary, "length(s)" in summary)
        assertTrue(summary, "e.spec" !in summary)
    }
}
