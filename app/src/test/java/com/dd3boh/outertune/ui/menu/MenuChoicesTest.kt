/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.ui.menu

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Choose what's in the three-dot menus (0.13 roadmap, 9 Nov 2026, behind MENU_CHOICES): hold an entry and the
 * menu is edited the way Android's Quick Settings are; what is hidden is kept per kind of menu, by each entry's
 * text resource name, and a trimmed menu says how many it hides, with Edit.
 */
class MenuChoicesTest {

    private fun code(path: String) = File("src/main/java/com/dd3boh/outertune/$path").readText()
    private fun res(path: String) = File("src/main/res/$path").readText()

    @Test
    fun `what is hidden is kept as kind and name pairs, one a line, whatever else the line holds`() {
        val stored = "song:share\n\nplayer:details\n  song:action_download  \nnonsense\n"
        val hidden = MenuChoices.parse(stored)
        assertEquals(setOf("song:share", "player:details", "song:action_download"), hidden)
        assertEquals("player:details\nsong:action_download\nsong:share", MenuChoices.write(hidden))
        assertEquals(hidden, MenuChoices.parse(MenuChoices.write(hidden)))
        assertEquals(emptySet<String>(), MenuChoices.parse(null))
        assertEquals(emptySet<String>(), MenuChoices.parse(""))
    }

    @Test
    fun `each kind of menu has its own hidden entries`() {
        val hidden = setOf("song:share", "song:action_download", "player:share", "album:share")
        assertEquals(setOf("share", "action_download"), MenuChoices.hiddenIn(hidden, MenuKind.SONG))
        assertEquals(setOf("share"), MenuChoices.hiddenIn(hidden, MenuKind.PLAYER))
        assertEquals(emptySet<String>(), MenuChoices.hiddenIn(hidden, MenuKind.QUEUE))
        // the keys never change: they are what is stored
        assertEquals(
            listOf("song", "player", "selection", "queue", "album", "artist", "playlist", "folder", "lyrics"),
            MenuKind.entries.map { it.key },
        )
    }

    @Test
    fun `a tap moves an entry out of a kind's menus and a second brings it back`() {
        val once = MenuChoices.toggle(emptySet(), MenuKind.SONG, "share")
        assertEquals(setOf("song:share"), once)
        assertEquals(setOf("song:share", "player:share"), MenuChoices.toggle(once, MenuKind.PLAYER, "share"))
        assertEquals(emptySet<String>(), MenuChoices.toggle(once, MenuKind.SONG, "share"))
    }

    @Test
    fun `an entry whose words follow a state is one entry`() {
        assertEquals("action_download", MenuChoices.entryName("action_download"))
        assertEquals("action_download", MenuChoices.entryName("remove_download"))
        assertEquals("action_download", MenuChoices.entryName("downloading"))
        assertEquals("add_to_library", MenuChoices.entryName("remove_from_library"))
        assertEquals("add_all_to_library", MenuChoices.entryName("remove_all_from_library"))
        assertEquals("menu_dont_play_song", MenuChoices.entryName("menu_dont_play_lift"))
        assertEquals("share", MenuChoices.entryName("share"))
    }

    @Test
    fun `every three-dot menu says which kind it is, and only the flag lets a menu be trimmed`() {
        val menus = File("src/main/java/com/dd3boh/outertune/ui/menu").listFiles()!!.filter { it.extension == "kt" }
        var grids = 0
        for (file in menus) {
            val text = file.readText()
            Regex("""\n\s*GridMenu\(\n\s*kind = MenuKind\.(\w+),|\n\s*GridMenu\(""").findAll(text).forEach { m ->
                grids++
                assertTrue("${file.name}: a GridMenu without its kind", m.groupValues[1].isNotEmpty())
            }
        }
        assertEquals("the 13 menus with a grid", 13, grids)
        val grid = code("ui/menu/GridMenu.kt")
        assertTrue("if (kind == null || !Unreleased.MENU_CHOICES) {" in grid)
        assertTrue("val MENU_CHOICES = BuildConfig.DEBUG" in code("constants/Unreleased.kt"))
    }

    @Test
    fun `every entry asks whether it is hidden, the sleep timer too, and a menu is left as it was found`() {
        val grid = code("ui/menu/GridMenu.kt")
        assertTrue(") = menuEntry(title, onClick) { mark, click, longClick ->" in grid)
        assertTrue(") = menuEntry(R.string.sleep_timer, onClick) { mark, click, longClick ->" in grid)
        // the core entry no longer builds its own item: only menuEntry adds the menus' entries
        assertEquals(4, Regex("""item \{ entry\(""").findAll(grid).count())
        assertTrue("MenuBuild.current = build\n        try {\n            content()\n        } finally {\n            MenuBuild.current = null\n        }" in grid)
        // a hidden entry is counted, and kept for Hidden while editing
        assertTrue("menu.hiddenHere++\n            if (menu.editing) menu.held.add" in grid)
        // read and written in one edit, so two quick taps both count
        assertTrue("it[MenuHiddenEntriesKey] = MenuChoices.write(MenuChoices.toggle(MenuChoices.parse(it[MenuHiddenEntriesKey]), kind, name))" in grid)
        // nothing goes missing without a word: a trimmed menu says how many, with Edit
        assertTrue("} else if (build.hiddenHere > 0) {" in grid)
        assertTrue("HiddenCountRow(count) { editing = true }" in grid)
    }

    @Test
    fun `its words are held from translators, with French beside them`() {
        val en = res("values/strings-ot.xml")
        val fr = res("values-fr/strings-ot.xml")
        for (name in listOf("menu_hidden", "menu_hidden_hint", "menu_edit_done", "menu_edit_hide", "menu_edit_show", "menu_edit_start", "menu_choices_title", "menu_choices_description", "menu_choices_show_all")) {
            assertTrue(name, "<string name=\"$name\" translatable=\"false\">" in en)
            assertTrue(name, "<string name=\"$name\">" in fr)
        }
        assertTrue("<plurals name=\"menu_hidden_count\" translatable=\"false\">" in en)
        // the search's words are open to translators, as every search_words_ text is (SettingsSearchTest)
        assertTrue("<string name=\"search_words_menus\">" in en && "<string name=\"search_words_menus\">" in fr)
        assertTrue("<plurals name=\"menu_hidden_count\">" in fr)
        assertFalse("no em dash", '—' in en.substringAfter("MENU_CHOICES") || '—' in fr.substringAfter("name=\"menu_hidden\""))
    }
}
