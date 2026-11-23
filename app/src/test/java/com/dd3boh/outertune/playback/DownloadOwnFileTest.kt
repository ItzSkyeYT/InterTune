/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.playback

import com.dd3boh.outertune.playback.downloadManager.PartialFiles
import com.dd3boh.outertune.playback.downloadManager.emptyFileGoes
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * A download is never written into the folder beside the song's own file of the same name. The move
 * looks for the song's recorded file and its "<title> [<id>].mka" first (DownloadKeepsFolderFilesTest),
 * but in a folder that lists as empty (dvd's phone, 20 Nov 2026) a song file "Artist - Title.opus" the
 * app no longer knew of was missed, and the move wrote "Artist - Title (1).opus" beside it; and a
 * write that met an empty "<title> [<id>].mka" wrote "<title> [<id>] (1).mka". Read from the source,
 * as the folder tests do.
 */
class DownloadOwnFileTest {
    private val folder = File("src/main/java/com/dd3boh/outertune/playback/downloadManager/DirectoryMangerOt.kt").readText()

    private fun body(start: String) = folder.substringAfter(start).substringBefore("\n    }\n")

    @Test
    fun `both writes into the folder look at the name they are about to take first`() {
        for (write in listOf(body("fun saveFile("), body("fun saveSongFile("))) {
            val own = write.indexOf("ownFileAt(mediaId, fileName)?.let { return it }")
            assertTrue(own >= 0)
            assertTrue("before anything is written", own < write.indexOf("writeWhole(directory, fileName)"))
        }
    }

    @Test
    fun `the file under that name is kept only when it is the song's own`() {
        val own = body("private fun ownFileAt(")
        // Asked by its name, as fileAlreadyThere does: a listing that came back empty does not hide it.
        assertTrue(own.contains("val uri = DocumentsContract.buildDocumentUriUsingTree(main.dir.uri, childDocumentId(main.documentId, name))"))
        // A song file says whose it is in its tags: another song of the same name is never taken for this one.
        assertTrue(own.contains("val whose = if (SongFileNames.isSongFile(found.name)) songFileIdOf(file) else idOf(file)"))
        assertTrue(own.contains("if (whose != mediaId) return null"))
        assertTrue("kept, in the folder's list as a listed file", own.indexOf("adopt(mediaId, file)") > own.indexOf("if (whose != mediaId) return null"))
        val tags = body("private fun songFileIdOf(")
        assertTrue(tags.contains("SongFileIds.read(name, BufferedInputStream(it, 16 * 1024))"))
        // An empty file goes only by the rule below, and is never kept.
        assertTrue(own.indexOf("emptyFileGoes(") in 0 until own.indexOf("DocumentsContract.deleteDocument("))
        assertTrue(own.substringAfter("if (found.size == 0L) {").substringBefore("val file =").contains("return null"))
    }

    @Test
    fun `an empty file in the way of a download's name goes only once it is no write's any more`() {
        val now = 1_800_000_000_000L
        val old = now - PartialFiles.LEFT_BEHIND_MS
        assertTrue(emptyFileGoes(songFile = false, modified = old, now = now))
        // Being written now, by this app or another: left alone.
        assertFalse(emptyFileGoes(songFile = false, modified = now - 5_000, now = now))
        // When it was written is not known: kept.
        assertFalse(emptyFileGoes(songFile = false, modified = 0, now = now))
        // An empty song file cannot say whose it is.
        assertFalse(emptyFileGoes(songFile = true, modified = old, now = now))
    }
}
