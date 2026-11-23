/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.playback.downloadManager

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * A move cut short never leaves half a song under the song's name (review of 0.13, F5). The file was
 * written in place: the app ended halfway left "Artist - Title.opus" with its tags and half its
 * sound, the next scan took it for the song, and the next Move took it as moved already and deleted
 * the whole copy inside the app. It is written under a name of its own now and renamed once whole.
 */
class PartialFilesTest {
    private val manager = File("src/main/java/com/dd3boh/outertune/playback/downloadManager/DirectoryMangerOt.kt").readText()

    @Test
    fun `a part has a name of its own, with no song id a scan could read in it`() {
        val part = PartialFiles.nameWhileWritten("Artist - Title.opus")
        assertEquals("Artist - Title.opus.intertune-part", part)
        assertTrue(PartialFiles.isPartial(part))
        assertFalse(SongFileNames.isSongFile(part))
        assertFalse(PartialFiles.SUFFIX.contains('[') || PartialFiles.SUFFIX.contains(']'))
    }

    @Test
    fun `the copy Android makes beside a part left behind is a part too`() {
        assertTrue(PartialFiles.isPartial("Artist - Title.opus (1).intertune-part"))
        assertTrue(PartialFiles.isPartial("Title [abc].mka.intertune-part"))
        assertFalse(PartialFiles.isPartial("Artist - Title.opus"))
        assertFalse(PartialFiles.isPartial("Title [abc].mka"))
        assertFalse(PartialFiles.isPartial(null))
    }

    @Test
    fun `a part goes only once no move can be writing it`() {
        val now = 100_000_000L
        assertFalse(PartialFiles.leftBehind(now - 1_000, now))
        assertTrue(PartialFiles.leftBehind(now - PartialFiles.LEFT_BEHIND_MS, now))
        assertFalse("a time not known keeps it", PartialFiles.leftBehind(0, now))
    }

    @Test
    fun `both kinds of file are written whole or not at all`() {
        val save = manager.substringAfter("fun saveFile(").substringBefore("\n    }\n")
        assertTrue(save.contains("writeWhole(directory, fileName)"))
        assertFalse(save.contains("createFile("))
        val song = manager.substringAfter("fun saveSongFile(").substringBefore("\n    }\n")
        assertTrue(song.contains("writeWhole(directory, fileName)"))
        assertFalse(song.contains("createFile("))
        val whole = manager.substringAfter("private fun writeWhole(").substringBefore("\n    }\n")
        assertTrue(whole.contains("directory.createFile(PartialFiles.MIME_TYPE, partName)"))
        // Where the part cannot be made or renamed, the final name from the start, as before: never a move lost.
        assertTrue(whole.contains("val renames = made != null && canRename(made.uri)"))
        // Renamed only after the write, and a failure deletes what was made.
        assertTrue(whole.indexOf("out.use(write)") < whole.indexOf("DocumentsContract.renameDocument("))
        assertTrue(whole.substringAfter("} catch (e: Exception) {").contains("file.delete()"))
    }

    @Test
    fun `a part is never any song's, and a full listing leaves it out`() {
        val idOf = manager.substringAfter("fun idOf(file: DocumentFile): String? {").substringBefore("\n    }\n")
        assertTrue(idOf.indexOf("if (PartialFiles.isPartial(name)) return null") in 0 until idOf.indexOf("SongFileNames.isSongFile(name)"))
        val listing = manager.substringAfter("fun getAvailableFiles(useCache: Boolean").substringBefore("\n    }\n")
        val removal = listing.indexOf("result.removeAll {")
        assertTrue(removal >= 0)
        assertTrue(removal < listing.indexOf("learnSongFiles(result, knownPaths)"))
        // Deleted from the main folder only: the extra folders are never changed.
        assertTrue(listing.contains("partial && isInMainDir(file) && PartialFiles.leftBehind("))
    }
}
