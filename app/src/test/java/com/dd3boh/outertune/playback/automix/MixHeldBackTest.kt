/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.playback.automix

import com.dd3boh.outertune.mix.Planner
import com.dd3boh.outertune.mix.pill.PlainReason
import com.dd3boh.outertune.mix.pill.Pills
import com.dd3boh.outertune.mix.pill.PlayerFacts
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Mix, the playlist side (Unreleased.AUTOMIX_EDITOR), is held back: the Mix row, the pills between
 * songs, the queue's chip and line, the player menu's way into the editor and Settings > Mixing.
 * With the flag off, playlists, the queue, the menu and the settings are what they were, and with
 * it on, every playlist still starts with Mix off. This reads the source, as AutomixHeldBackTest
 * does, because a flag is a constant of the build and a test cannot turn it.
 */
class MixHeldBackTest {

    private val sources = File("src/main/java/com/dd3boh/outertune")

    private fun source(path: String) = File(sources, path).readText()

    /** The text without its comments, so a sentence that names the flag is not taken for a read of it. */
    private fun code(text: String) = text
        .replace(Regex("""/\*.*?\*/""", RegexOption.DOT_MATCHES_ALL), "")
        .lines().joinToString("\n") { it.substringBefore("//") }

    private fun lines(path: String) = code(source(path)).lines().map { it.trim() }

    @Test
    fun `the flag is read only where the playlist side and the service hook in`() {
        val readers = sources.walkTopDown()
            .filter { it.extension == "kt" && it.name != "Unreleased.kt" }
            .filter { "Unreleased.AUTOMIX_EDITOR" in code(it.readText()) }
            .map { it.relativeTo(sources).path }
            .toSet()
        assertEquals(
            setOf(
                "playback/MusicService.kt",
                "ui/navigation/AppNavGraph.kt",
                "ui/player/Queue.kt",
                "ui/menu/PlayerMenu.kt",
                "ui/menu/PlaylistMenu.kt",
                "ui/screens/playlist/LocalPlaylistScreen.kt",
                "ui/screens/settings/PlayerSettings.kt",
                "ui/screens/settings/fragments/PlayerFrag.kt",
                "ui/screens/mix/MixPlaylist.kt",
                "ui/screens/mix/MixQueue.kt",
            ),
            readers,
        )
    }

    @Test
    fun `Mix's pages are in the navigation only under the flag`() {
        val graph = code(source("ui/navigation/AppNavGraph.kt"))
        val block = graph.substringAfter("if (Unreleased.AUTOMIX_EDITOR) {").substringBefore("screen(\"settings/storage\")")
        assertTrue(block, "screen(MixRoutes.SETTINGS)" in block)
        assertTrue(block, "route = MixRoutes.EDITOR," in block)
        assertEquals(1, Regex("""MixRoutes\.SETTINGS""").findAll(graph).count())
        assertEquals(1, Regex("""MixRoutes\.EDITOR""").findAll(graph).count())
    }

    @Test
    fun `every door into Mix is behind the flag`() {
        // Settings: the Mixing row stands where the prototype's switch stood, only with the flag.
        assertTrue("if (Unreleased.AUTOMIX_EDITOR) MixingEntry(navController)" in lines("ui/screens/settings/PlayerSettings.kt"))
        // The playlist: the row and the pills.
        val playlist = lines("ui/screens/playlist/LocalPlaylistScreen.kt")
        assertTrue("if (Unreleased.AUTOMIX_EDITOR && navController != null) {" in playlist)
        assertEquals("MixRow(playlistId = playlist.id, songCount = songs.size, navController = navController)",
            playlist[playlist.indexOf("if (Unreleased.AUTOMIX_EDITOR && navController != null) {") + 1])
        // The queue: the chip and the line.
        val queue = lines("ui/player/Queue.kt")
        assertTrue("if (Unreleased.AUTOMIX_EDITOR) MixQueueChip(Modifier.padding(start = 12.dp))" in queue)
        assertTrue("if (Unreleased.AUTOMIX_EDITOR && index == currentWindowIndex && !detachedHead && !isSearching) {" in queue)
        // The player's menu, and the playlist's.
        assertTrue("val mixEntry = if (Unreleased.AUTOMIX_EDITOR) rememberMixMenuEntry(mediaMetadata.id) else null" in lines("ui/menu/PlayerMenu.kt"))
        assertTrue("val mixMenu = if (Unreleased.AUTOMIX_EDITOR && songs.size >= 2) rememberMixMenuSwitch(playlist.id) else null" in lines("ui/menu/PlaylistMenu.kt"))
        // Each Mix composable is called from those places only.
        for ((name, home) in mapOf(
            "MixRow(" to "ui/screens/playlist/LocalPlaylistScreen.kt",
            "MixPillLine(" to "ui/screens/playlist/LocalPlaylistScreen.kt",
            "MixQueueChip(" to "ui/player/Queue.kt",
            "MixNextLine(" to "ui/player/Queue.kt",
            "rememberMixMenuEntry(" to "ui/menu/PlayerMenu.kt",
            "MixingEntry(" to "ui/screens/settings/PlayerSettings.kt",
            "rememberMixMenuSwitch(" to "ui/menu/PlaylistMenu.kt",
        )) {
            val callers = sources.walkTopDown()
                .filter { it.extension == "kt" && !it.path.contains("/ui/screens/mix/") }
                .filter { name in code(it.readText()) }
                .map { it.relativeTo(sources).path }
                .toSet()
            assertEquals(name, setOf(home), callers)
        }
    }

    @Test
    fun `pills never show in a release build, nor with the switch off`() {
        val playlist = lines("ui/screens/playlist/LocalPlaylistScreen.kt")
        val show = playlist.indexOf("val showPills = Unreleased.AUTOMIX_EDITOR && mixOn && sortType == PlaylistSongSortType.CUSTOM &&")
        assertTrue(show > 0)
        assertEquals("!isSearching && !inSelectMode && !addMode", playlist[show + 1])
        assertTrue("val mixOn = Unreleased.AUTOMIX_EDITOR && rememberMixOn(viewModel.playlistId)" in playlist)
        assertTrue("if (showPills && next != null) {" in playlist)
        assertEquals("MixPillLine(", playlist[playlist.indexOf("if (showPills && next != null) {") + 1])
    }

    @Test
    fun `the Mix row is off for every playlist until it is switched on`() {
        val mix = code(source("ui/screens/mix/MixPlaylist.kt"))
        assertTrue("val on by rememberPreference(MixPlaylistsKey, defaultValue = emptySet())" in mix)
        assertTrue("return MixSwitches.isOn(on, playlistId)" in mix)
        assertTrue("if (!Unreleased.AUTOMIX_EDITOR || songCount < 2) return" in mix)
        assertFalse(MixSwitches.isOn(null, "LP1"))
        assertFalse(MixSwitches.isOn(emptySet(), "LP1"))
        assertFalse(MixSwitches.isOn(setOf("LP2"), "LP1"))
        assertFalse(MixSwitches.isOn(setOf("LP1"), null))
        assertTrue(MixSwitches.isOn(setOf("LP1"), "LP1"))
        // The service mixes a queue only when the playlist it came from has its switch on.
        val wanted = code(source("playback/MusicService.kt")).substringAfter("private fun automixWanted(): Flow<Boolean> = when {")
            .substringBefore("}.distinctUntilChanged()")
        assertTrue(wanted, "dataStore.data.map { it[MixPlaylistsKey] ?: emptySet() }," in wanted)
        assertTrue(wanted, "{ playlist, on -> playlist != null && playlist in on }" in wanted)
    }

    @Test
    fun `without the flag the service links no queue and names no playlist`() {
        val service = code(source("playback/MusicService.kt"))
        for (fn in listOf("private fun refreshMix()", "private fun linkMixQueue(playlistId: String?)", "fun mixBlocker(): PlainReason?")) {
            val body = service.substringAfter(fn).trimStart().lines().take(3).joinToString("\n")
            assertTrue("$fn: $body", "if (!Unreleased.AUTOMIX_EDITOR) return" in body)
        }
        assertTrue("if (Unreleased.AUTOMIX_EDITOR) linkMixQueue((queue as? ListQueue)?.mixPlaylistId)" in lines("playback/MusicService.kt"))
        // The playlist names itself on the queue only with the flag.
        val playlist = code(source("ui/screens/playlist/LocalPlaylistScreen.kt"))
        assertEquals(3, Regex("""mixPlaylistId = .*takeIf \{ Unreleased\.AUTOMIX_EDITOR \}""").findAll(playlist).count())
        val menu = code(source("ui/menu/PlaylistMenu.kt"))
        assertEquals(2, Regex("""mixPlaylistId = playlist\.id\.takeIf \{ Unreleased\.AUTOMIX_EDITOR \}""").findAll(menu).count())
    }

    @Test
    fun `the guards give a reason a pill has, with the log line as it was`() {
        val a = AutomixGuards.Song("a", "album-a", 200_000)
        val b = AutomixGuards.Song("b", "album-b", 180_000)
        fun snapshot(
            casting: Boolean = false, repeatOne: Boolean = false, next: AutomixGuards.Song? = b, speed: Float = 1f,
            skipSilence: Boolean = false, headTracking: Boolean = false, upmix: Boolean = false, ambience: Boolean = false,
            ffmpeg: Boolean = false, sleepTimer: Boolean = false, seekedLate: Boolean = false, stageInChain: Boolean = true,
        ) = AutomixGuards.Snapshot(
            true, stageInChain, casting, repeatOne, a, next, speed, 1f, skipSilence,
            headTracking, upmix, ambience, ffmpeg, sleepTimer, seekedLate,
        )
        val reasons = mapOf(
            PlainReason.CASTING to snapshot(casting = true),
            PlainReason.REPEAT_ONE to snapshot(repeatOne = true),
            PlainReason.SAME_SONG to snapshot(next = a),
            PlainReason.RUN_TOGETHER to snapshot(next = AutomixGuards.Song("b", "album-a", 180_000)),
            PlainReason.SHORT_SONG to snapshot(next = AutomixGuards.Song("b", null, 54_000)),
            PlainReason.SPEED to snapshot(speed = 1.25f),
            PlainReason.SKIP_SILENCE to snapshot(skipSilence = true),
            PlainReason.SPATIAL to snapshot(headTracking = true),
            PlainReason.DECODER to snapshot(ffmpeg = true),
            PlainReason.SLEEP_TIMER to snapshot(sleepTimer = true),
            PlainReason.NO_TIME to snapshot(seekedLate = true),
            PlainReason.FROM_NEXT to snapshot(stageInChain = false),
        )
        for ((reason, s) in reasons) {
            val refusal = AutomixGuards.refusal(s)
            assertEquals(reason, refusal?.reason)
            assertEquals(AutomixGuards.whyNot(s), refusal?.log)
        }
        assertEquals(PlainReason.SPATIAL, AutomixGuards.refusal(snapshot(upmix = true))?.reason)
        assertEquals(PlainReason.SPATIAL, AutomixGuards.refusal(snapshot(ambience = true))?.reason)
        // The player's state alone gives the same reason as the guards, for the Mix row and the pills.
        assertEquals(PlainReason.SKIP_SILENCE, Pills.blocker(PlayerFacts(skipSilence = true)))
        // The pills call a song short where the guards and the planner do.
        assertEquals(Planner.MIN_SONG_MS, AutomixGuards.MIN_SONG_MS)
    }

    @Test
    fun `its texts are short, in English and in French, and use no em dash`() {
        val res = File("src/main/res")
        val english = File(res, "values/strings-ot.xml").readText()
        val french = File(res, "values-fr/strings-ot.xml").readText()
        val names = Regex("""<(?:string|plurals) name="(mix(?:ing)?_?[a-z_]*)"(?! translatable="false")""").findAll(english)
            .map { it.groupValues[1] }.toList()
        assertTrue(names.toString(), names.size >= 60)
        for (name in names) {
            for ((language, file) in listOf("English" to english, "French" to french)) {
                val text = Regex("""<(string|plurals) name="$name">(.*?)</\1>""", RegexOption.DOT_MATCHES_ALL).find(file)?.groupValues?.get(2)
                assertTrue("$name has no $language", text != null)
                assertTrue("$name in $language has an em dash", '—' !in text!!)
                // French counts in one, many and other (a million titres): lint refuses a plural without many.
                if (language == "French" && "<item" in text) assertTrue("$name in French has no many", "quantity=\"many\"" in text)
                for (item in Regex("""<item quantity="[a-z]+">(.*?)</item>""").findAll(text).map { it.groupValues[1] }.ifEmpty { sequenceOf(text) }) {
                    assertTrue("$name in $language runs past three sentences", Regex("[.!?](?=\\s|$)").findAll(item).count() <= 3)
                }
            }
        }
    }
}
