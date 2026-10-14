/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.ui.screens.settings.fragments

import com.dd3boh.outertune.lyrics.latinTools
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Show in Latin letters is offered only where the phone can do it: from Android 10, which has
 * android.icu's Transliterator. ICU4J is not in the build for the phones below. This reads the
 * source, as other tests here do, for what a unit test cannot run: a composable and its effect.
 */
class LatinLettersSwitchTest {

    private val frag = File("src/main/java/com/dd3boh/outertune/ui/screens/settings/fragments/LyricFrag.kt").readText()
    private val view = File("src/main/java/com/dd3boh/outertune/ui/component/Lyrics.kt").readText()

    @Test
    fun `below Android 10 there is nothing to do it with`() {
        // The Android of the unit tests says it is version 0.
        assertNull(latinTools())
    }

    @Test
    fun `the switch is only there when the phone can do it, and starts off`() {
        val gate = frag.indexOf("if (latinTools() != null) {")
        val title = frag.indexOf("R.string.lyrics_latin_title")
        assertTrue("the switch is not behind latinTools()", gate in 0 until title && '}' !in frag.substring(gate, title))
        assertTrue("rememberPreference(LyricLatinKey, defaultValue = false)" in frag)
        assertTrue("rememberPreference(LyricLatinKey, false)" in view)
    }

    @Test
    fun `the lyrics view asks the same, and works the lines out off the main thread`() {
        assertTrue("val tools = latinTools()" in view)
        assertTrue("withContext(Dispatchers.Default) { LyricsLatin.of(sung, tools) }" in view)
    }
}
