/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.ui.screens

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The battery saver note (his ask of 18 Nov 2026): said when the phone's battery saver puts the
 * app on Saver, twice at most, once each time the saver comes on, and never over another screen.
 */
class SaverNoteTest {

    private fun source(path: String) = File("src/main/java/com/dd3boh/outertune/$path").readText()

    private fun text(folder: String, name: String): String? =
        Regex("<string name=\"$name\"[^>]*>([^<]*)</string>")
            .find(File("src/main/res/$folder/strings-ot.xml").readText())?.groupValues?.get(1)

    @Test
    fun `owed while the phone puts the app on Saver, twice at most and once each time`() {
        assertTrue(SaverNote.owed(saverFromPhone = true, shown = 0, waitingForOff = false))
        assertTrue(SaverNote.owed(saverFromPhone = true, shown = 1, waitingForOff = false))
        // Said twice already: Battery use's own row says it from then on.
        assertFalse(SaverNote.owed(saverFromPhone = true, shown = 2, waitingForOff = false))
        // Said already during this time the saver is on.
        assertFalse(SaverNote.owed(saverFromPhone = true, shown = 1, waitingForOff = true))
        // Saver picked by hand, the switch under Battery use off, or no saver: the phone changed nothing.
        assertFalse(SaverNote.owed(saverFromPhone = false, shown = 0, waitingForOff = false))
        assertEquals(2, SaverNote.MOST)
    }

    @Test
    fun `on for everyone from 0_13, said from the app's root, and waiting for every other screen`() {
        assertTrue("val SAVER_NOTE = true" in source("constants/Unreleased.kt"))
        val main = source("MainActivity.kt")
        assertTrue("if (Unreleased.SAVER_NOTE) {" in main)
        val block = main.substringAfter("if (Unreleased.SAVER_NOTE) {").substringBefore("SaverNoteDialog(")
        // Saver because of the phone, as Battery use's own row tells it.
        assertTrue("BatteryUse.saverFromPhone(chosenBatteryMode, followPhoneSaver, phoneSaverOn)" in block)
        // The account notice's waits, and the account notice itself.
        for (wait in listOf(
            "oobeStatus >= OOBE_VERSION", "!catchUpOpen", "!welcomeOpen", "!tourState.running",
            "!tourFromWelcome", "!updatePromptVisible", "!accountNoticeOwed(",
        )) {
            assertTrue("the note does not wait for $wait", wait in block)
        }
        // Said is written before the count, whose change relaunches the check; the saver seen off clears it.
        val said = block.indexOf("setSaverNoteWaiting(true)")
        val counted = block.indexOf("setSaverNoteShown(saverNoteShown + 1)")
        assertTrue(said >= 0 && counted > said)
        assertTrue("if (!phoneSaverOn && saverNoteWaiting) setSaverNoteWaiting(false)" in block)
        // Battery use, where the switch is, one tap away.
        assertTrue("navController.navigate(\"settings/battery\")" in main.substringAfter("SaverNoteDialog("))
    }

    @Test
    fun `its words are in English and French, three short sentences at most, no long dash`() {
        for (folder in listOf("values", "values-fr")) {
            for (name in listOf("saver_note_title", "saver_note_body")) {
                val words = text(folder, name)
                assertNotNull("$name is missing in $folder", words)
                assertFalse("$name has a long dash in $folder", '—' in words!!)
            }
            val body = text(folder, "saver_note_body")!!
            assertTrue("the note runs to ${body.count { it == '.' }} sentences in $folder", body.count { it == '.' } <= 3)
        }
    }

    @Test
    fun `word-by-word lyrics are no longer among what pauses, and the rest of the list is as it was`() {
        // His pick of 19 Nov 2026: the phone's saver leaves them on (BatteryUse.holdsWordByWord).
        val english = text("values", "saver_note_body")!!
        val french = text("values-fr", "saver_note_body")!!
        assertFalse(english, english.contains("Word-by-word", ignoreCase = true) || english.contains("lyrics", ignoreCase = true))
        assertFalse(french, french.contains("mot à mot") || french.contains("paroles"))
        assertTrue(english, english.contains("The player\\'s moving background and sound effects (not the equalizer) pause, the theme turns black and syncs wait for Wi-Fi."))
        assertTrue(french, french.contains("Le fond animé du lecteur et les effets sonores (pas l\\'égaliseur) se mettent en pause, le thème passe en noir et les synchronisations attendent le Wi-Fi."))
    }
}
