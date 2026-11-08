/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.ui.menu

import com.dd3boh.outertune.R
import com.dd3boh.outertune.ui.screens.settings.exclusionInForce
import com.dd3boh.outertune.ui.screens.settings.exclusionReason
import com.dd3boh.outertune.ui.screens.settings.showsDontPlayNote
import com.dd3boh.outertune.viewmodels.ExclusionsViewModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Where Don't play this song is (DontPlay, Unreleased.ARTIST_BLOCK), read from the source as the
 * other tests of places do: beside Block artist at the end of the song menus, so no item anyone
 * knows moves; after the player menu's items that are always there; a row of its own reason in
 * Exclusions, with Lift; Not this song left to the recommendation rows; and its words, short, in
 * English and French.
 */
class DontPlayPlacesTest {

    private val sources = File("src/main/java/com/dd3boh/outertune")

    private fun code(path: String) = File(sources, path).readText()

    /** The grid of a menu: the text of its GridMenu's block. */
    private fun grid(path: String): String {
        val text = code(path)
        val start = text.indexOf("GridMenu(")
        assertTrue("$path has no grid", start >= 0)
        val open = text.indexOf('{', text.indexOf(") {", start))
        var depth = 0
        for (i in open until text.length) {
            when (text[i]) {
                '{' -> depth++
                '}' -> if (--depth == 0) return text.substring(open, i + 1)
            }
        }
        error("$path: the grid never closes")
    }

    @Test
    fun `both song menus offer it under the flag, last but for Block artist, which stays last`() {
        for (path in listOf("ui/menu/SongMenu.kt", "ui/menu/YouTubeSongMenu.kt")) {
            val grid = grid(path)
            val item = grid.indexOf("dontPlayMenuItem(")
            assertTrue("$path has no Don't play this song", item > 0)
            assertEquals("$path offers it twice", 1, grid.split("dontPlayMenuItem(").size - 1)
            assertTrue("$path offers it outside the flag", grid.substring(0, item).trimEnd().endsWith("if (Unreleased.ARTIST_BLOCK) {\n            val held = dontPlay.has(song.id)"))
            val block = grid.indexOf("title = R.string.menu_block_artist")
            assertTrue("$path: Block artist moved before it", item < block)
            assertFalse("$path: an item between it and Block artist", "GridMenuItem(" in grid.substring(item, grid.lastIndexOf("GridMenuItem(", block)))
            assertEquals("$path: Block artist is no longer last", grid.lastIndexOf("GridMenuItem("), grid.lastIndexOf("GridMenuItem(", block))
            assertTrue("$path lost Never this artist", "R.string.menu_never_artist" in grid)
            assertTrue("$path lost Not this song", "R.string.menu_not_this_song" in grid)
        }
    }

    @Test
    fun `the player's menu offers it under the flag, after the items always there and before the ones some moments have`() {
        val grid = grid("ui/menu/PlayerMenu.kt")
        val item = grid.indexOf("dontPlayMenuItem(")
        assertTrue(item > 0)
        assertTrue(grid.substring(0, item).trimEnd().endsWith("if (Unreleased.ARTIST_BLOCK) {\n            val held = dontPlay.has(mediaMetadata.id)"))
        val signal = grid.indexOf("title = R.string.signal_path")
        assertTrue("it moved Signal path", signal in 0 until item)
        assertEquals("an item between Signal path and it", signal, grid.lastIndexOf("title = R.string.", item))
        assertTrue(item < grid.indexOf("if (Unreleased.AUTOMIX_EDITOR) {"))
        assertTrue(item < grid.indexOf("title = R.string.head_tracking_recentre"))
    }

    @Test
    fun `its item says Don't play this song, and for a song set so lifts it, under a note of its own`() {
        val text = code("ui/menu/DontPlaySong.kt")
        assertTrue("val DontPlayIcon = Icons.Rounded.MusicOff" in text)
        assertTrue("title = if (held) R.string.menu_dont_play_lift else R.string.menu_dont_play_song" in text)
        // Written as a row of Exclusions, with the song's own row, or lifted, and said in a line either way.
        assertTrue("ExclusionsViewModel.dontPlay(song.id, song.title, System.currentTimeMillis())" in text)
        assertTrue("insert(song)\n        insertExclusion(row)" in text)
        assertTrue("database.query { liftDontPlay(song.id) }" in text)
        assertTrue("R.string.dont_play_done" in text && "R.string.dont_play_lifted" in text)
        // Not the icons of Not this song or Block artist.
        assertFalse("Icons.Rounded.Block" in text || "DoNotDisturbOn" in text)
    }

    @Test
    fun `Exclusions lists it in force until lifted, with its own reason and a note while one is there`() {
        val row = ExclusionsViewModel.dontPlay("af1", "Africa", at = 5L)
        assertEquals(ExclusionsViewModel.KIND_SONG, row.kind)
        assertEquals(ExclusionsViewModel.REASON_DONT_PLAY, row.reason)
        assertEquals("Africa", row.label)
        assertNull(row.expiresAt)
        assertTrue(exclusionInForce(row, now = Long.MAX_VALUE))
        assertEquals(R.string.exclusion_reason_dont_play, exclusionReason(row, dontPlayOn = true))
        // A row of it restored into a build without the feature keeps the song off the rows alone.
        assertEquals(R.string.exclusion_reason_song_ban, exclusionReason(row, dontPlayOn = false))
        assertTrue(showsDontPlayNote(listOf(row), dontPlayOn = true))
        assertFalse(showsDontPlayNote(listOf(row), dontPlayOn = false))
        assertFalse(showsDontPlayNote(listOf(row.copy(reason = ExclusionsViewModel.REASON_BAN)), dontPlayOn = true))
        // The other reasons keep their words.
        assertEquals(R.string.exclusion_reason_song_ban, exclusionReason(row.copy(reason = ExclusionsViewModel.REASON_BAN), dontPlayOn = true))
        assertEquals(R.string.exclusion_reason_artist_ban, exclusionReason(row.copy(kind = ExclusionsViewModel.KIND_ARTIST, reason = ExclusionsViewModel.REASON_BAN), dontPlayOn = true))
        assertEquals(R.string.exclusion_reason_gone, exclusionReason(row.copy(reason = ExclusionsViewModel.REASON_GONE), dontPlayOn = true))
        assertEquals(R.string.exclusion_reason_rest, exclusionReason(row.copy(reason = ExclusionsViewModel.REASON_REST), dontPlayOn = true))
        // Every row in force is listed, each with its Lift.
        val page = code("ui/screens/settings/ExclusionsSettings.kt")
        assertTrue("val active = exclusions.filter { exclusionInForce(it, now) }" in page)
        assertTrue("active.forEach { row(it) }" in page)
        assertTrue("}) { Text(stringResource(R.string.exclusion_lift)) }" in page.substringAfter("fun row(e: RecommendationExclusion)"))
        assertTrue("if (showsDontPlayNote(active)) {" in page)
    }

    @Test
    fun `Not this song keeps to the rows, with a reason of its own, and never in place of Don't play`() {
        val reasons = with(ExclusionsViewModel) { listOf(REASON_BAN, REASON_SNOOZE, REASON_REST, REASON_GONE, REASON_DONT_PLAY) }
        assertEquals(reasons.size, reasons.toSet().size)
        for (weaker in listOf(ExclusionsViewModel.REASON_BAN, ExclusionsViewModel.REASON_SNOOZE, ExclusionsViewModel.REASON_REST, ExclusionsViewModel.REASON_GONE)) {
            assertFalse(ExclusionsViewModel.mayReplace(ExclusionsViewModel.REASON_DONT_PLAY, weaker))
            assertTrue(ExclusionsViewModel.mayReplace(weaker, ExclusionsViewModel.REASON_DONT_PLAY))
        }
        assertTrue(ExclusionsViewModel.mayReplace(null, ExclusionsViewModel.REASON_BAN))
        assertTrue(ExclusionsViewModel.mayReplace(ExclusionsViewModel.REASON_REST, ExclusionsViewModel.REASON_BAN))
        assertTrue(ExclusionsViewModel.mayReplace(ExclusionsViewModel.REASON_DONT_PLAY, ExclusionsViewModel.REASON_DONT_PLAY))
        val home = code("viewmodels/HomeViewModel.kt")
        assertTrue("if (ExclusionsViewModel.mayReplace(this.exclusion(kind, targetId)?.reason, reason)) insertExclusion(exclusion)" in home)
        // Home's menu still writes its own reasons, 1 and 2.
        val songMenu = code("ui/menu/SongMenu.kt")
        assertTrue("onExclude(1, 1)" in songMenu && "REASON_DONT_PLAY" !in songMenu)
    }

    private val words = listOf(
        "menu_dont_play_song", "menu_dont_play_lift", "dont_play_done", "dont_play_lifted", "dont_play_skipped",
        "dont_play_songs_left", "exclusion_reason_dont_play", "exclusions_dont_play_note",
    )

    private fun text(folder: String, name: String): String? =
        Regex("""<string name="$name"[^>]*>(.*?)</string>""").find(File("src/main/res/$folder/strings-ot.xml").readText())?.groupValues?.get(1)

    @Test
    fun `its words are held from translators, have their French, and are short and plain`() {
        val english = File("src/main/res/values/strings-ot.xml").readText()
        for (name in words) {
            assertTrue("$name is not held from translators", "<string name=\"$name\" translatable=\"false\">" in english)
            for (folder in listOf("values", "values-fr")) {
                val said = text(folder, name)
                assertTrue("$folder $name is missing", said != null)
                assertFalse("$folder $name has a dash", '\u2014' in said!! || '\u2013' in said)
                assertTrue("$folder $name says too much", Regex("[.!?](?=\\s|$)").findAll(said).count() <= 2)
            }
        }
        assertEquals("Don\\'t play this song", text("values", "menu_dont_play_song"))
        assertEquals("Ne plus jouer ce titre", text("values-fr", "menu_dont_play_song"))
        // The skip names the song; the menu's lines fit a toast's two lines.
        for (folder in listOf("values", "values-fr")) {
            assertTrue("%1\$s" in text(folder, "dont_play_skipped")!!)
            for (name in listOf("dont_play_done", "dont_play_lifted")) assertTrue("$folder $name", text(folder, name)!!.length <= 60)
        }
    }
}
