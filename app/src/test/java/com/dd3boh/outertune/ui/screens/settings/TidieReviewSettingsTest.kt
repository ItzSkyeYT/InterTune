/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.ui.screens.settings

import com.dd3boh.outertune.constants.SleepTimerDefaults
import com.dd3boh.outertune.constants.SyncMode
import com.dd3boh.outertune.ui.screens.settings.fragments.SleepFadeChoice
import com.dd3boh.outertune.ui.screens.settings.fragments.SyncChoice
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * His answers of 19 Nov 2026 to Ti-Die's review of the settings: lyrics are always trimmed, the sleep
 * timer's fade is one row where 0 is no fade, Sync mode has Off in place of the sync switch, and seven
 * descriptions that repeated their title or the preview above them are gone. Nothing anybody chose is
 * lost: each merged row writes the settings it always wrote.
 */
class TidieReviewSettingsTest {

    private fun read(path: String) = File("src/main/java/com/dd3boh/outertune/$path").readText()

    @Test
    fun `lyrics are always trimmed, with no switch`() {
        val helper = read("lyrics/LyricsHelper.kt")
        assertEquals("both lookups trim", 2, Regex("""val trim = true""").findAll(helper).count())
        assertFalse(helper.contains("LyricTrimKey"))
        assertTrue(read("ui/menu/LyricsMenu.kt").contains("val lyricTrim = true"))
        assertFalse("no switch in Lyrics", read("ui/screens/settings/fragments/LyricFrag.kt").contains("lyrics_trim_title"))
        assertFalse(read("constants/PreferenceKeys.kt").contains("LyricTrimKey"))
    }

    @Test
    fun `a fade of 0 is no fade, and the length is kept for the timer's own switch`() {
        assertNull(SleepFadeChoice.of(0))
        assertEquals(SleepTimerDefaults.FADE_DURATION_RANGE.first, SleepFadeChoice.of(1))
        assertEquals(30, SleepFadeChoice.of(30))
        assertEquals(SleepTimerDefaults.FADE_DURATION_RANGE.last, SleepFadeChoice.of(600))
        val frag = read("ui/screens/settings/fragments/PlayerFrag.kt")
        assertTrue(frag.contains("initialValue = if (sleepTimerFade) sleepTimerFadeDuration else 0"))
        assertTrue(frag.contains("onSleepTimerFadeChange(it > 0)"))
        assertFalse("the switch row is gone", frag.contains("checked = sleepTimerFade"))
    }

    @Test
    fun `Off in Sync mode is the old switch's off, and the mode under it stays`() {
        assertEquals(SyncChoice.OFF, SyncChoice.of(false, SyncMode.RW))
        assertEquals(SyncChoice.OFF, SyncChoice.of(false, SyncMode.RO))
        assertEquals(SyncChoice.RO, SyncChoice.of(true, SyncMode.RO))
        assertEquals(SyncChoice.RW, SyncChoice.of(true, SyncMode.RW))
        var syncs: Boolean? = null
        var mode: SyncMode? = null
        SyncChoice.apply(SyncChoice.OFF, { syncs = it }, { mode = it })
        assertEquals(false, syncs)
        assertNull("Off leaves the mode as it was", mode)
        SyncChoice.apply(SyncChoice.RO, { syncs = it }, { mode = it })
        assertEquals(true, syncs)
        assertEquals(SyncMode.RO, mode)
        val frag = read("ui/screens/settings/fragments/SyncFrag.kt")
        assertEquals("one Sync mode row", 1, Regex("""settingRow\(R\.string\.sync_mode\)""").findAll(frag).count())
        assertFalse("no switch row", frag.contains("settingRow(R.string.ytm_sync)"))
    }

    @Test
    fun `the descriptions Ti-Die found repeated are gone from the screens`() {
        val gone = listOf(
            "spatial_bass_description", "proximity_volume_description", "player_living_strength_description",
            "player_living_smoothing_description", "player_glass_intensity_description",
            "show_liked_and_downloaded_playlist_description", "lyrics_source_youtube_captions_line", "poll_banner_hint",
        )
        val sources = File("src/main/java").walkTopDown().filter { it.extension == "kt" }.map { it.readText() }.toList()
        for (key in gone) assertFalse(key, sources.any { it.contains("R.string.$key") })
        val english = File("src/main/res/values/strings-ot.xml").readText()
        for (key in gone) assertFalse("$key left in English", english.contains("name=\"$key\""))
    }
}
