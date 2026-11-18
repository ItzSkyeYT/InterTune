/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.ui.screens.settings

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Word by word lyrics stand still while Android's battery saver is on, and nothing said so: Niko took them for broken
 * (#testers, 18 Nov 2026, his "maybe add it to the settings"). The switch now says it while the saver is on.
 */
class LyricsSaverNoteTest {

    @Test
    fun `the switch says word by word is off while the battery saver is on, as the lyrics do`() {
        val settings = File("src/main/java/com/dd3boh/outertune/ui/screens/settings/fragments/LyricFrag.kt").readText()
        val lyrics = File("src/main/java/com/dd3boh/outertune/ui/component/Lyrics.kt").readText()
        assertTrue(settings.contains("if (lyricsFancy && LocalContext.current.isPowerSaver()) {"))
        assertTrue(settings.contains("MeaningLine(stringResource(R.string.lyrics_word_by_word_saver))"))
        assertTrue("the note follows the lyrics' own rule", lyrics.contains("lyricsFancy && item.words != null && !context.isPowerSaver()"))
    }

    @Test
    fun `the note is written in English and French`() {
        for (file in listOf("src/main/res/values/strings-ot.xml", "src/main/res/values-fr/strings-ot.xml")) {
            assertTrue(file, File(file).readText().contains("name=\"lyrics_word_by_word_saver\""))
        }
    }
}
