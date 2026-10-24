/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.ui.screens.settings

import com.dd3boh.outertune.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The search of Settings (Unreleased.SETTINGS_SEARCH) looks through SETTINGS_INDEX, which is
 * written by hand, and finds a row on its screen by the mark its title gives it (settingRow).
 * This holds the two to each other by reading the screens (SettingsSource): a row added to a
 * screen without an entry fails here, and so does an entry left behind by a row that went, or
 * moved to another screen, or into a fold, or behind a flag.
 */
class SettingsIndexTest {

    private val sources = File("src/main/java/com/dd3boh/outertune")
    private val source = SettingsSource(sources)

    /** Every string resource by its id, as the index names them, and back. */
    private val nameOf: Map<Int, String> = R.string::class.java.fields.associate { it.getInt(null) to it.name }

    private fun name(id: Int) = nameOf[id] ?: error("no string with id $id")

    private fun leftOut(row: SettingsSource.Row) =
        row.file in LEFT_OUT_FILES || row.component in LEFT_OUT_MAKERS ||
            row.titles.any { title -> title in LEFT_OUT || "${row.route}:$title" in LEFT_OUT }

    /** The rows that are settings: every row of a screen, but the inside of a row maker and what is left out. */
    private val rows = source.rows.filter { !it.maker && !leftOut(it) }

    private val entries = SETTINGS_INDEX.map { it.route to name(it.title) }

    /** The flags that are on in every build: they hold nothing back, and an entry does not write them. */
    private val alwaysOn: Set<String> = Regex("""^\s*val ([A-Z][A-Z0-9_]*) = true\s*$""", RegexOption.MULTILINE)
        .findAll(SettingsSource.blank(File(sources, "constants/Unreleased.kt").readText()))
        .map { "Unreleased." + it.groupValues[1] }.toSet()

    private fun held(flags: Set<String>) = flags.filter { it.removePrefix("!") !in alwaysOn }.toSet()

    @Test
    fun `the screens are read, rows and all`() {
        // So that a reading gone wrong fails here rather than passing everything below.
        assertTrue(source.routes.size.toString(), source.routes.size >= 20)
        assertTrue(source.rows.size.toString(), source.rows.size >= 200)
        val listed = settingsGroups(engine = true).flatten().map { it.route }
        assertTrue((listed - rows.map { it.route }.toSet()).toString(), rows.map { it.route }.toSet().containsAll(listed))
    }

    @Test
    fun `every row whose title is written carries the mark of its title`() {
        val unmarked = rows.filter { it.titles.isNotEmpty() && it.marks.none { mark -> mark in it.titles } }
        assertTrue(unmarked.joinToString("\n") { "${it.file}:${it.line} ${it.titles} has no settingRow of its title" }, unmarked.isEmpty())
    }

    @Test
    fun `a row whose title is worked out carries a mark all the same`() {
        val unmarked = rows.filter { it.titles.isEmpty() && it.marks.isEmpty() && it.file !in WORKED_OUT_TITLES }
        assertTrue(unmarked.joinToString("\n") { "${it.file}:${it.line} ${it.component} has a title the test cannot read and no mark" }, unmarked.isEmpty())
    }

    @Test
    fun `every marked row is in the index, on the screen it is drawn on`() {
        val missing = rows.flatMap { row -> row.marks.map { row.route to it } }.toSet() - entries.toSet()
        assertTrue(missing.joinToString("\n") { "${it.second} on ${it.first} is not in SETTINGS_INDEX" }, missing.isEmpty())
    }

    @Test
    fun `every entry of the index has its row on its screen`() {
        val drawn = rows.flatMap { row -> row.marks.map { row.route to it } }.toSet()
        val gone = entries.filter { it !in drawn }
        assertTrue(gone.joinToString("\n") { "${it.second} is in SETTINGS_INDEX on ${it.first}, and no row there carries its mark" }, gone.isEmpty())
    }

    @Test
    fun `nothing is in the index twice`() {
        assertEquals(entries.groupBy { it }.filterValues { it.size > 1 }.keys.toString(), entries.size, entries.toSet().size)
    }

    @Test
    fun `an entry's description is the row's own`() {
        val wrong = mutableListOf<String>()
        for (entry in SETTINGS_INDEX) {
            val description = entry.description ?: continue
            val written = rows.filter { it.route == entry.route && name(entry.title) in it.marks }.flatMap { it.descriptions }
            if (name(description) !in written) wrong += "${name(entry.title)}: ${name(description)}, the row has $written"
        }
        assertTrue(wrong.joinToString("\n"), wrong.isEmpty())
    }

    @Test
    fun `an entry is in the fold its row is in`() {
        val wrong = mutableListOf<String>()
        for (entry in SETTINGS_INDEX) {
            val folds = rows.filter { it.route == entry.route && name(entry.title) in it.marks }.map { it.folds.lastOrNull() }.toSet()
            val written = entry.fold?.let(::name)
            if (folds != setOf(written)) wrong += "${name(entry.title)}: fold $written, the row is in $folds"
        }
        assertTrue(wrong.joinToString("\n"), wrong.isEmpty())
    }

    @Test
    fun `an entry is behind the held flags its row is behind, written on its line`() {
        assertTrue("the flags of Unreleased.kt were not read", "Unreleased.ENGINE" in alwaysOn && "Unreleased.AUTO_REWORK" !in alwaysOn)
        val lines = File(sources, "ui/screens/settings/SettingsIndex.kt").readLines().filter { "SettingEntry(" in it && "R.string." in it }
        val wrong = mutableListOf<String>()
        for (entry in SETTINGS_INDEX) {
            val title = name(entry.title)
            val route = entry.route
            val line = lines.filter { Regex("""SettingEntry\([^,]+, R\.string\.$title\b""").containsMatchIn(it) }
                .singleOrNull { route in it || ROUTE_NAMES[route]?.let { constant -> constant in it } == true }
            if (line == null) {
                wrong += "$title on $route: no line of its own in SettingsIndex.kt"
                continue
            }
            val flags = rows.filter { it.route == route && title in it.marks }.map { held(it.flags) }.toSet()
            val written = held(SettingsSource.flagsWritten(line))
            if (flags != setOf(written)) wrong += "$title: written behind $written, the row is behind $flags"
        }
        assertTrue(wrong.joinToString("\n"), wrong.isEmpty())
    }

    @Test
    fun `a row to light instead is on the same screen`() {
        val wrong = SETTINGS_INDEX.filter { entry -> entry.under != null && entries.none { it == entry.route to name(entry.under!!) } }
        assertTrue(wrong.joinToString { name(it.title) }, wrong.isEmpty())
    }

    @Test
    fun `a description is the same words whatever the setting is set to`() {
        // A line with a number or a name in it would be searched with its %1$d.
        val english = File("src/main/res/values").listFiles()!!.filter { it.name.startsWith("strings") }.joinToString("\n") { it.readText() }
        val withValues = SETTINGS_INDEX.mapNotNull { it.description }.map(::name).filter { description ->
            Regex("""<string name="$description"[^>]*>([^<]*)</string>""").find(english)?.groupValues?.get(1)?.contains('%') == true
        }
        assertTrue(withValues.toString(), withValues.isEmpty())
    }

    @Test
    fun `the left out rows are still there, so the list says something true`() {
        val titles = source.rows.flatMap { row -> row.titles.flatMap { listOf(it, "${row.route}:$it") } }.toSet()
        assertTrue((LEFT_OUT.keys - titles).toString(), titles.containsAll(LEFT_OUT.keys))
        assertTrue(source.rows.any { it.component in LEFT_OUT_MAKERS })
        val files = source.rows.map { it.file }.toSet()
        assertTrue((LEFT_OUT_FILES.keys - files).toString(), files.containsAll(LEFT_OUT_FILES.keys))
    }

    private companion object {
        /** The constants SettingsIndex.kt writes routes with. */
        val ROUTE_NAMES = mapOf(
            "settings/account_sync" to "Tour.ROUTE_ACCOUNT",
            "settings/library" to "Tour.ROUTE_LIBRARY",
            "settings/local" to "Tour.ROUTE_LOCAL",
            "settings/appearance" to "Tour.ROUTE_LOOK_AND_FEEL",
            "settings/player" to "Tour.ROUTE_PLAYER",
            "settings/recognition" to "Tour.ROUTE_RECOGNITION",
            "settings/library/lyrics" to "Tour.ROUTE_LYRICS",
            "settings/storage" to "Tour.ROUTE_STORAGE",
            "settings/backup" to "Tour.ROUTE_BACKUP",
            "settings/privacy" to "Tour.ROUTE_PRIVACY",
            "settings/updates" to "Tour.ROUTE_UPDATES",
            "settings/recommendations" to "Tour.ROUTE_RECOMMENDATIONS",
            "settings/advanced" to "Tour.ROUTE_ADVANCED",
            "settings/about" to "Tour.ROUTE_ABOUT",
        )

        /** Rows that are not settings to search for, by a string of their title, and why. */
        val LEFT_OUT = mapOf(
            "lastfm_unavailable" to "Says that this build has no Last.fm; there is nothing to set.",
            "lastfm_connected_as" to "The connected account, named: \"Connect a Last.fm account\" lights the Scrobble switch beside it instead.",
            "polls_unavailable" to "Says that this build has no questions; there is nothing to set.",
            "polls_one_waiting" to "A question waiting, shown only then.",
            "audio_quality_now" to "What is playing now, a reading.",
            "spatial_audio_status" to "What this phone can do, a reading.",
            "proximity_probe_stop" to "A measurement for the developer, in debug builds only (Unreleased.SIGNAL_LOG).",
            "dl_calculate_size" to "A button that is there until the sizes have been worked out.",
            "update_fdroid_source" to "A note for a copy from F-Droid.",
            "update_prompt_fdroid" to "A step of an update that has been found.",
            "update_install" to "A step of an update that has been found.",
            "update_open_page" to "A step of an update that has been found.",
            "update_skip" to "A step of an update that has been found.",
            "import_library_source_other" to "A step of the import, which its own row in Backup leads to.",
            "settings/player/equalizer:equalizer" to "The switch of the screen that the Equalizer row of Player and audio leads to.",
        )

        /** Rows made by a function of the package that are never settings, and why. */
        val LEFT_OUT_MAKERS = mapOf(
            "StatEntry" to "A figure of what the engine has done or learned, not a setting.",
        )

        /** Files whose rows are left out altogether, and why. */
        val LEFT_OUT_FILES = mapOf(
            "fragments/DeveloperFrag.kt" to "The developer's own settings, hidden behind a switch and never translated.",
            "EngineDeveloperSettings.kt" to "The engine's tuning constants, for the developer.",
        )

        /** Files whose rows with a title worked out are figures or lists, not settings. */
        val WORKED_OUT_TITLES = setOf(
            "RecommendationsDoingSettings.kt",
            "ExclusionsSettings.kt",
        )
    }
}
