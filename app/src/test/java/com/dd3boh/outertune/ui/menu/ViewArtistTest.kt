/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.ui.menu

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * #150: a local file with no artist tag has no artist (the scanners drop blank names), and View
 * artist in its menu opened the choice of artists with nothing in it. Every menu that offers View
 * artist offers it only when there is an artist to go to; without one the entry stays, greyed out,
 * and reads "No artist found" (Ti-Die's ask). Read from the source, comments taken out.
 */
class ViewArtistTest {

    private val menus = File("src/main/java/com/dd3boh/outertune/ui/menu")

    private fun code(file: File) = file.readText()
        .replace(Regex("""/\*.*?\*/""", RegexOption.DOT_MATCHES_ALL), "")
        .replace(Regex("""(^|\s)//.*$""", RegexOption.MULTILINE), "$1")

    @Test
    fun `View artist is offered only when there is an artist to go to`() {
        // The entries named View artist; the name also tells TalkBack what an artist's name does where it is a link,
        // on a queued song's row (QueueMenuArtistTest).
        val named = "title = R.string.view_artist"
        val offering = menus.listFiles { f -> f.extension == "kt" }!!.filter { named in code(it) }
        assertTrue("found ${offering.map { it.name }}", offering.size >= 5)
        for (file in offering) {
            val text = code(file)
            var at = text.indexOf(named)
            while (at >= 0) {
                // Right above the entry: an if that asks for at least one artist.
                val entry = text.lastIndexOf("GridMenuItem(", at)
                val before = text.substring(maxOf(0, entry - 120), entry)
                assertTrue(
                    "${file.name}: View artist without a check that there is an artist:\n$before",
                    Regex("""if \([\w.]*artists\.isNotEmpty\(\)\) \{\s*$""").containsMatchIn(before),
                )
                // Right below it: the else, a greyed-out entry that says no artist was found.
                val after = text.substring(at, minOf(text.length, at + 900))
                assertTrue(
                    "${file.name}: no greyed-out No artist found entry after View artist",
                    Regex("""\}\s*else \{\s*GridMenuItem\([^)]*R\.string\.no_artist_found,\s*enabled = false""")
                        .containsMatchIn(after),
                )
                at = text.indexOf(named, at + 1)
            }
        }
    }

    @Test
    fun `Open file location is offered for local songs only`() {
        for (name in listOf("SongMenu.kt", "PlayerMenu.kt")) {
            val text = code(File(menus, name))
            val at = text.indexOf("R.string.open_file_location")
            assertTrue("$name: no Open file location", at > 0)
            val before = text.substring(maxOf(0, text.lastIndexOf("GridMenuItem(", at) - 160), at)
            assertTrue("$name: not limited to local files:\n$before", "isLocal" in before && "localPath" in before)
        }
    }

    @Test
    fun `a file's folder is found from its path`() {
        assertEquals("primary:Music/Rock", OpenFileLocation.folderDocumentId("/storage/emulated/0/Music/Rock/a.mp3"))
        assertEquals("primary:", OpenFileLocation.folderDocumentId("/storage/emulated/0/a.mp3"))
        assertEquals("1234-ABCD:Music", OpenFileLocation.folderDocumentId("/storage/1234-ABCD/Music/b.flac"))
        assertEquals(null, OpenFileLocation.folderDocumentId("/data/data/x/a.mp3"))
        assertEquals(null, OpenFileLocation.folderDocumentId(null))
    }
}
