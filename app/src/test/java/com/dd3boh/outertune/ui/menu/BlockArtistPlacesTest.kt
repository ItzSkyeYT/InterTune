/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.ui.menu

import com.dd3boh.outertune.R
import com.dd3boh.outertune.ui.screens.playlist.moveAround
import com.dd3boh.outertune.ui.screens.settings.SETTINGS_INDEX
import com.dd3boh.outertune.ui.screens.settings.blockedSummary
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Where Block artist is (Unreleased.ARTIST_BLOCK), read from the source as the other tests of
 * places do: the last item of the song menus, so no item anyone knows moves; the first button of
 * an artist page's pill, so its heart and share stay put; a row of Library and content with its
 * entry in the settings search; and its words, short, in English and French.
 */
class BlockArtistPlacesTest {

    private val sources = File("src/main/java/com/dd3boh/outertune")

    /** The grid of a menu: the text of its GridMenu's block. */
    private fun grid(path: String): String {
        val text = File(sources, path).readText()
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

    /** The titles of a grid's items, in order. */
    private fun items(grid: String) =
        Regex("""GridMenuItem\([^)]*?title = R\.string\.(\w+)|DownloadGridMenu\(""").findAll(grid).map { it.groupValues[1].ifEmpty { "download" } }.toList()

    @Test
    fun `Block artist is the last item of both song menus, and Never this artist is still there`() {
        for (path in listOf("ui/menu/SongMenu.kt", "ui/menu/YouTubeSongMenu.kt")) {
            val titles = items(grid(path))
            assertEquals("$path: $titles", "menu_block_artist", titles.last())
            assertEquals(1, titles.count { it == "menu_block_artist" })
            assertTrue("$path lost Never this artist", "menu_never_artist" in titles)
        }
    }

    @Test
    fun `an artist page's pill starts with Block, before the heart and share`() {
        val text = File(sources, "ui/screens/artist/ArtistScreen.kt").readText()
        val pill = text.substring(text.indexOf("TopBarActions {"))
        val block = pill.indexOf("BlockArtistIcon")
        assertTrue(block > 0)
        assertTrue(block < pill.indexOf("Icons.Rounded.Favorite"))
        assertTrue(block < pill.indexOf("Icons.Rounded.Share"))
    }

    @Test
    fun `its icon is the ring with a bar, not Not this song's slashed ring`() {
        assertTrue("val BlockArtistIcon = Icons.Outlined.DoNotDisturbOn" in File(sources, "ui/menu/BlockArtist.kt").readText())
        val songMenu = File(sources, "ui/menu/SongMenu.kt").readText()
        assertTrue("GridMenuItem(icon = Icons.Rounded.Block, title = R.string.menu_not_this_song)" in songMenu)
    }

    @Test
    fun `Library and content has the Blocked artists row, and the settings search finds it`() {
        val entry = SETTINGS_INDEX.single { it.title == R.string.blocked_artists }
        assertEquals("settings/library", entry.route)
        val library = File(sources, "ui/screens/settings/LibrarySettings.kt").readText()
        assertTrue("Modifier.settingRow(R.string.blocked_artists)" in library)
        assertTrue("settings/library/blocked_artists" in library)
        val nav = File(sources, "ui/navigation/AppNavGraph.kt").readText()
        assertTrue("screen(\"settings/library/blocked_artists\")" in nav)
    }

    @Test
    fun `the row names the first three, and counts the rest`() {
        assertNull(blockedSummary(emptyList()))
        assertEquals("Sub Focus", blockedSummary(listOf("Sub Focus")))
        assertEquals("A, B, C", blockedSummary(listOf("A", "B", "C")))
        assertEquals("A, B, C +2", blockedSummary(listOf("A", "B", "C", "D", "E")))
    }

    @Test
    fun `a drag in a playlist with rows left out moves by the rows' positions`() {
        // Positions 0 to 4, the row at 2 hidden: on screen 0, 1, 3, 4.
        // The row at 4 dragged to the top: it goes before 0.
        assertEquals(4 to 0, moveAround(listOf(4, 0, 1, 3), to = 0))
        // The row at 0 dragged below 3: it goes to 3's position, 3 and the hidden row moving up.
        assertEquals(0 to 3, moveAround(listOf(1, 3, 0, 4), to = 2))
        // The row at 4 dragged up between 0 and 1: just after 0.
        assertEquals(4 to 1, moveAround(listOf(0, 4, 1, 3), to = 1))
        // Not moved at all.
        assertNull(moveAround(listOf(0, 1, 3, 4), to = 1))
        assertNull(moveAround(listOf(0), to = 0))
        assertNull(moveAround(listOf(0, 1), to = 5))
    }

    private val held = listOf(
        "menu_block_artist", "block_artist_which", "block_artist_title", "block_artist_body", "block_artist_confirm",
        "unblock", "blocked_artists", "blocked_artists_none", "blocked_artists_intro", "blocked_artists_add",
        "blocked_artists_not_found", "blocked_today", "blocked_on", "unblocked", "blocked_search_note",
        "blocked_song_skipped", "blocked_songs_left",
    )

    private fun text(folder: String, name: String): String? =
        Regex("""<string name="$name"[^>]*>(.*?)</string>""").find(File("src/main/res/$folder/strings-ot.xml").readText())?.groupValues?.get(1)

    @Test
    fun `the settings search finds the row by a few more words, open to translators as every search word is`() {
        val english = File("src/main/res/values/strings-ot.xml").readText()
        assertTrue("<string name=\"search_words_blocked_artists\">" in english)
        assertTrue(text("values-fr", "search_words_blocked_artists") != null)
        assertEquals(listOf(R.string.search_words_blocked_artists), SETTINGS_INDEX.single { it.title == R.string.blocked_artists }.words)
    }

    @Test
    fun `its words are held from translators, have their French, and are short and plain`() {
        val english = File("src/main/res/values/strings-ot.xml").readText()
        for (name in held) {
            assertTrue("$name is not held from translators", "<string name=\"$name\" translatable=\"false\">" in english)
            for (folder in listOf("values", "values-fr")) {
                val words = text(folder, name)
                assertTrue("$folder $name is missing", words != null)
                assertFalse("$folder $name has a dash", '\u2014' in words!! || '\u2013' in words)
                assertTrue("$folder $name says too much", Regex("[.!?](?=\\s|$)").findAll(words).count() <= 3)
            }
        }
    }

    @Test
    fun `Never this artist says it is for the recommendations, in its menu only`() {
        assertEquals("Never this artist (recommendations)", text("values", "menu_never_artist"))
        assertEquals("Jamais cet artiste (recommandations)", text("values-fr", "menu_never_artist"))
        assertEquals("Never this artist", text("values", "exclusion_reason_artist_ban"))
        assertEquals("Jamais cet artiste", text("values-fr", "exclusion_reason_artist_ban"))
    }
}
