/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.migration

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The question Import from Metrolist asks before it writes anything. It said "Nothing you already have is changed or
 * removed", but a song you already have takes the backup's like, its library date, its artist and album links and
 * the larger play count (MetrolistImport.merge). It now says so, in two short sentences, held from translators until
 * the words are agreed (review of 18 Nov 2026).
 */
class MetrolistTextsTest {

    private fun summary(file: String): Pair<String, String> {
        val line = File(file).readLines().single { "name=\"metrolist_import_summary\"" in it }
        return line.substringBefore(">") to line.substringAfter(">").substringBefore("</string>")
    }

    private val english = summary("src/main/res/values/strings-ot.xml")
    private val french = summary("src/main/res/values-fr/strings-ot.xml")

    @Test
    fun `the question says what it adds to songs you already have, in English and French`() {
        assertEquals(
            "Songs in its library: %1\$d\\nLiked songs: %2\$d\\nPlaylists: %3\$d\\nPlays: %4\$d\\n\\n" +
                "Nothing is removed. Songs you already have get its likes, library dates, artists and albums, and the higher play count.",
            english.second,
        )
        assertEquals(
            "Titres de sa bibliothèque : %1\$d\\nTitres aimés : %2\$d\\nPlaylists : %3\$d\\nÉcoutes : %4\$d\\n\\n" +
                "Rien n\\'est supprimé. Les titres que vous avez déjà reçoivent ses j\\'aime, ses dates d\\'ajout à la bibliothèque, ses artistes et albums, et le plus grand nombre d\\'écoutes.",
            french.second,
        )
    }

    @Test
    fun `it is held from translators, two sentences at most, and says nothing untrue`() {
        for ((tag, text) in listOf(english, french)) {
            assertTrue(tag, "translatable=\"false\"" in tag)
            val sentences = text.substringAfterLast("\\n").split(Regex("""(?<=\.)\s+""")).filter { it.isNotBlank() }
            assertTrue(text, sentences.size <= 2)
            assertFalse(text, '—' in text || '–' in text || ';' in text)
            for (n in 1..4) assertTrue(text, "%$n\$d" in text)
        }
        assertFalse("changed" in english.second)
        assertFalse("changé" in french.second)
    }

    @Test
    fun `the dialog shows it with the four counts it names`() {
        val screen = File("src/main/java/com/dd3boh/outertune/ui/screens/settings/fragments/BackupRestoreFrag.kt").readText()
        assertTrue("stringResource(R.string.metrolist_import_summary, m.summary.inLibrary, m.summary.liked, m.summary.playlists, m.summary.plays)" in screen)
    }
}
