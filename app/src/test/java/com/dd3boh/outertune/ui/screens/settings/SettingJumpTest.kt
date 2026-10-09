/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.ui.screens.settings

import com.dd3boh.outertune.ui.screens.walkthrough.SETTINGS_CLOSER_LOOK
import com.dd3boh.outertune.ui.screens.walkthrough.Tour
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * A setting named in an explanation is a way to it: a tap on the name closes the explanation,
 * opens the screen the setting is on, scrolls to its row and flashes it (Unreleased.EXPLANATION_LINKS).
 * What can be tried without a screen is here: finding the names, and the list of where they lead.
 */
class SettingJumpTest {

    private val sources = File("src/main/java/com/dd3boh/outertune")

    private fun names(text: String) = quotedNames(text).map { text.substring(it) }

    /** The text without its comments, so a sentence that names the flag is not taken for a check. */
    private fun code(text: String) = text
        .replace(Regex("""/\*.*?\*/""", RegexOption.DOT_MATCHES_ALL), "")
        .lines().joinToString("\n") { it.substringBefore("//") }

    @Test
    fun `the names in an English explanation are what stands between its quotation marks`() {
        val text = "\"Tidy Home rows\" and \"Rank with your listening\" act on songs, whatever fills \"Quick picks\"."
        assertEquals(listOf("Tidy Home rows", "Rank with your listening", "Quick picks"), names(text))
    }

    @Test
    fun `in French they stand between guillemets, whatever kind of space is inside them`() {
        assertEquals(
            listOf("Épurer les rangées de l'accueil", "Classer selon votre écoute"),
            names("« Épurer les rangées de l'accueil » et « Classer selon votre écoute » agissent sur les titres."),
        )
        // A space that does not break, as French typography sets it, and the narrow one.
        assertEquals(listOf("Vos données"), names("dans « Vos données »."))
        assertEquals(listOf("Vos données"), names("dans « Vos données »."))
    }

    @Test
    fun `a range is the name alone, without its marks`() {
        val text = "It needs \"Tidy Home rows\" on."
        val range = quotedNames(text).single()
        assertEquals("Tidy Home rows", text.substring(range))
        assertEquals('"', text[range.first - 1])
        assertEquals('"', text[range.last + 1])
    }

    @Test
    fun `a text with no names, or with a mark that is never closed, has none`() {
        assertTrue(quotedNames("Each song fades out at its end and the next one fades in.").isEmpty())
        assertTrue(quotedNames("A 12\" record and nothing after it").isEmpty())
        assertTrue(quotedNames("« sans fin").isEmpty())
        assertTrue(quotedNames("").isEmpty())
        // Nothing between the marks is not a name, and neither is a paragraph.
        assertTrue(quotedNames("He said \"\" and left").isEmpty())
        assertTrue(quotedNames("\"one line\nand another\"").isEmpty())
    }

    private val source = Jump("source", "settings/library")
    private val tidy = Jump("tidy", "settings/recommendations")
    private val named = mapOf("Quick picks source" to source, "Best recommendations" to source, "Try both" to source, "Tidy Home rows" to tidy)
    private val rows = mapOf("Quick picks source" to source, "Tidy Home rows" to tidy)

    private fun linked(body: String, own: String) = linkedNames(body, own, named, rows).map { body.substring(it.first) to it.second }

    @Test
    fun `a name that leads somewhere is a link, and one that leads nowhere is left as it is`() {
        assertEquals(
            listOf("Tidy Home rows" to tidy),
            linked("It needs \"Tidy Home rows\" on, whatever fills \"Quick picks\".", own = "Rests everywhere"),
        )
    }

    @Test
    fun `under a title that is only a choice's name, the setting it is a choice of is still linked`() {
        // Seen on the emulator, 9 Oct 2026: the group "Best recommendations" was read as the row
        // "Quick picks source", whose choice it names, and its explanation had no link left.
        val body = "These settings apply while \"Quick picks source\" is \"Best recommendations\" or \"Try both\"."
        assertEquals(
            listOf("Quick picks source" to source, "Best recommendations" to source, "Try both" to source),
            linked(body, own = "Best recommendations"),
        )
    }

    @Test
    fun `a setting's own explanation does not link to the row it was opened from`() {
        val body = "\"Best recommendations\" builds the row. \"Try both\" alternates. It needs \"Tidy Home rows\"."
        assertEquals(listOf("Tidy Home rows" to tidy), linked(body, own = "Quick picks source"))
    }

    @Test
    fun `every setting the tour stops at can be jumped to, by the title of its row`() {
        val byTitle = SettingJumps.ROWS.toMap()
        for (stop in SETTINGS_CLOSER_LOOK) {
            assertEquals(stop.id, Jump(stop.targetId!!, stop.route!!), byTitle[stop.title])
        }
    }

    @Test
    fun `the rows marked for a jump alone carry their mark, on the screen the list says`() {
        val marked = mapOf(
            SettingJumps.TIDY_HOME_ROWS to ("ui/screens/settings/RecommendationsSettings.kt" to Tour.ROUTE_RECOMMENDATIONS),
            SettingJumps.RANK_WITH_LISTENING to ("ui/screens/settings/RecommendationsSettings.kt" to Tour.ROUTE_RECOMMENDATIONS),
            SettingJumps.FAMILIARITY to ("ui/screens/settings/RecommendationsSettings.kt" to Tour.ROUTE_RECOMMENDATIONS),
            SettingJumps.SIMILAR_SOURCE to ("ui/screens/settings/RecommendationsSettings.kt" to Tour.ROUTE_RECOMMENDATIONS),
            SettingJumps.CLEAR_LISTEN_HISTORY to ("ui/screens/settings/fragments/LibraryFrag.kt" to Tour.ROUTE_PRIVACY),
        )
        val file = File(sources, "ui/screens/settings/SettingJump.kt").readText()
        for ((mark, where) in marked) {
            val (path, route) = where
            val name = Regex("""const val (\w+) = "$mark"""").find(file)!!.groupValues[1]
            assertTrue("$name is on no row of $path", "Modifier.tourTarget(SettingJumps.$name)" in File(sources, path).readText())
            assertTrue("$name leads nowhere", SettingJumps.NAMED.any { it.second == Jump(mark, route) })
        }
        // No two rows under one mark: the flash would land on whichever reported last.
        assertEquals(marked.size, marked.keys.toSet().size)
    }

    @Test
    fun `a choice leads to the setting it is a choice of`() {
        val quickPicks = SETTINGS_CLOSER_LOOK.single { it.id == "closer_quick_picks_source" }
        val spatial = SETTINGS_CLOSER_LOOK.single { it.id == "closer_spatial_audio" }
        val byTitle = SettingJumps.CHOICES.toMap()
        // A choice is never a row of its own: that is what keeps a title that names one from being taken for the row.
        assertTrue(SettingJumps.CHOICES.none { choice -> SettingJumps.ROWS.any { it.first == choice.first } })
        for (choice in listOf(
            com.dd3boh.outertune.R.string.recommendations_engine_title,
            com.dd3boh.outertune.R.string.recommendations_row_try_both,
            com.dd3boh.outertune.R.string.quick_picks_source_youtube,
            com.dd3boh.outertune.R.string.quick_picks_source_library,
        )) assertEquals(Jump(quickPicks.targetId!!, quickPicks.route!!), byTitle[choice])
        for (choice in listOf(
            com.dd3boh.outertune.R.string.spatial_audio_headphones,
            com.dd3boh.outertune.R.string.spatial_audio_surround,
        )) assertEquals(Jump(spatial.targetId!!, spatial.route!!), byTitle[choice])
    }

    @Test
    fun `nothing is named twice with two places to go`() {
        val twice = SettingJumps.NAMED.groupBy({ it.first }, { it.second }).filter { it.value.toSet().size > 1 }
        assertTrue(twice.toString(), twice.isEmpty())
    }

    @Test
    fun `the links are behind their flag, read in one place`() {
        val readers = sources.walkTopDown()
            .filter { it.extension == "kt" && it.name != "Unreleased.kt" }
            .filter { "Unreleased.EXPLANATION_LINKS" in code(it.readText()) }
            .map { it.relativeTo(sources).path }
            .toSet()
        assertEquals(setOf("ui/component/Explain.kt"), readers)
        assertFalse("EXPLANATION_LINKS = true" in File(sources, "constants/Unreleased.kt").readText())
    }
}
