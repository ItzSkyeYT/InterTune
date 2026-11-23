/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Buttons TalkBack only called "Button", found by the walks on the 0.13 integration (19 Nov 2026): the minus and plus of
 * Tempo and Pitch (player menu > Advanced), and the add buttons of an empty playlist's own search. And the playlist
 * search's tick, which said "Add to playlist" over a song already there. Each now says what it does or what it means.
 */
class TalkBackWordsTest {
    private fun source(path: String) = File("src/main/java/com/dd3boh/outertune/$path").readText()

    @Test
    fun `tempo and pitch's minus and plus say what they do`() {
        val menu = source("ui/menu/PlayerMenu.kt")
        val adjuster = menu.substringAfter("fun <T : Comparable<T>> ValueAdjuster(")
        assertTrue(adjuster.contains("contentDescription = downName"))
        assertTrue(adjuster.contains("contentDescription = upName"))
        val dialog = menu.substringAfter("fun PitchTempoDialog(").substringBefore("internal val TEMPO_STEPS")
        assertTrue(dialog.contains("downName = stringResource(R.string.tempo_slower),"))
        assertTrue(dialog.contains("upName = stringResource(R.string.tempo_faster),"))
        assertTrue(dialog.contains("downName = stringResource(R.string.pitch_lower),"))
        assertTrue(dialog.contains("upName = stringResource(R.string.pitch_higher),"))
        // Open to translators with 0.13's other new words since 20 Nov 2026, French beside them.
        val english = File("src/main/res/values/strings-ot.xml").readText()
        val french = File("src/main/res/values-fr/strings-ot.xml").readText()
        for (name in listOf("tempo_slower", "tempo_faster", "pitch_lower", "pitch_higher")) {
            assertTrue(name, english.contains("<string name=\"$name\">"))
            assertTrue(name, french.contains("<string name=\"$name\">"))
        }
    }

    @Test
    fun `a playlist's add buttons say add, and their tick says the song is already there`() {
        val words = "if (added) R.string.duplicates_description_single else R.string.add_to_playlist"
        val empty = source("ui/screens/playlist/LocalPlaylistScreen.kt")
            .substringAfter("items(items = addResults, key = { \"add_\" + it.id })").take(1200)
        assertTrue(empty.contains(words))
        assertFalse(empty.substringBefore("modifier = Modifier.clickable").contains("contentDescription = null"))
        val search = source("ui/screens/playlist/PlaylistYouTubeSearch.kt")
        assertEquals(1, Regex(Regex.escape(words)).findAll(search).count())
    }

    @Test
    fun `every heart in a three-dot menu says what a tap on it does`() {
        // Found on the walk of 19 Nov 2026: the heart beside a song's title in its menu had no words, as had those of
        // the album, artist and playlist menus. Each now says it as the player and the screens' hearts do (likeName).
        val menus = File("src/main/java/com/dd3boh/outertune/ui/menu").listFiles()!!.filter { it.name.endsWith(".kt") }
        var hearts = 0
        for (file in menus) {
            val lines = file.readLines()
            for ((i, line) in lines.withIndex()) {
                if ("R.drawable.favorite_border" !in line || "painterResource(if (" !in line) continue
                hearts++
                val cond = Regex("""painterResource\(if \((.+?)\) R\.drawable\.favorite else R\.drawable\.favorite_border\)""")
                    .find(line)?.groupValues?.get(1)
                val next = lines.subList(i + 1, minOf(i + 5, lines.size)).joinToString("\n")
                assertTrue("${file.name}:${i + 1}: the heart has no words", "contentDescription = likeName($cond)" in next)
            }
        }
        assertTrue("the menus' hearts could not be found", hearts >= 9)
    }
}
