/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.ui.screens.settings

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Word by word lyrics stood still while Android's battery saver was on, and nothing said so: Niko took them for broken
 * (#testers, 18 Nov 2026, his "maybe add it to the settings"). The switch then said it while the saver was on.
 *
 * Since 19 Nov 2026 (his pick, after Ti-Die asked to keep them) they draw under the phone's saver too, since they draw
 * only while the lyrics are open, and only Saver picked by hand in Battery use stops them: the switch says that instead.
 */
class LyricsSaverNoteTest {

    @Test
    fun `the switch says word by word is off while Saver is picked by hand, as the lyrics do`() {
        val settings = File("src/main/java/com/dd3boh/outertune/ui/screens/settings/fragments/LyricFrag.kt").readText()
        val lyrics = File("src/main/java/com/dd3boh/outertune/ui/component/Lyrics.kt").readText()
        // The mode picked and stored, not the one in force, which the phone's saver can make Saver.
        for (code in listOf(settings, lyrics)) assertTrue(code.contains("rememberEnumPreference(BatteryModeKey, BatteryUse.DEFAULT)"))
        assertTrue(settings.contains("if (lyricsFancy && BatteryUse.holdsWordByWord(pickedBatteryMode)) {"))
        assertTrue(settings.contains("MeaningLine(stringResource(R.string.lyrics_word_by_word_saver))"))
        assertTrue(lyrics.contains("val wordsHeld = BatteryUse.holdsWordByWord(pickedBatteryMode)"))
        assertTrue("the note follows the lyrics' own rule", lyrics.contains("lyricsFancy && item.words != null && !wordsHeld"))
        // The phone's saver itself is read by neither any more.
        assertFalse(lyrics.contains("isPowerSaver"))
        assertFalse(settings.contains("isPowerSaver"))
    }

    @Test
    fun `the note is written in English and French, in the words of Battery use's other notes`() {
        val english = File("src/main/res/values/strings-ot.xml").readText()
        val french = File("src/main/res/values-fr/strings-ot.xml").readText()
        assertTrue(english.contains("<string name=\"lyrics_word_by_word_saver\" translatable=\"false\">Off while \\\"Battery use\\\" is on \\\"Saver\\\".</string>"))
        assertTrue(french.contains("<string name=\"lyrics_word_by_word_saver\" translatable=\"false\">Désactivé tant que « Utilisation de la batterie » est sur « Économie ».</string>"))
        // The same names as the notes under the other rows Saver holds.
        assertTrue(english.contains("while \\\"Battery use\\\" is on \\\"Saver\\\".</string>"))
        assertTrue(french.contains("<string name=\"battery_saver_glass_note\">Sans flou tant que « Utilisation de la batterie » est sur « Économie ».</string>"))
    }
}
