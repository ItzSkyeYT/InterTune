/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Remove download for a song whose file in the download folder is gone already, deleted in a file
 * manager since the folder was last listed (dvd, 11 Nov 2026): Remove all deleted the other songs'
 * files and left that one listed under Downloads, with its download state, until the app was
 * restarted. Every Remove download goes through DownloadUtil.deleteSong, which gave up as soon as
 * the folder's delete did not go through. Reads the source, as the folder tests do (FolderMoveTest).
 */
class DownloadRemoveGoneWiringTest {
    private val util = File("src/main/java/com/dd3boh/outertune/playback/DownloadUtil.kt").readText()
    private val folder = File("src/main/java/com/dd3boh/outertune/playback/downloadManager/DirectoryMangerOt.kt").readText()
    private val english = File("src/main/res/values/strings-ot.xml").readText()
    private val french = File("src/main/res/values-fr/strings-ot.xml").readText()

    private val deleteFile = folder.substringAfter("fun deleteFile(mediaId: String): FileRemoval {", "").substringBefore("\n    }\n")
    private val deleteSong = util.substringAfter("private fun deleteSong(id: String) {", "").substringBefore("\n    }\n")

    @Test
    fun `the folder says what came of a delete, not only whether it went through`() {
        assertTrue("deleteFile returns a FileRemoval", deleteFile.isNotEmpty())
        // Still under the lock that keeps a save and a delete from undoing each other in the list.
        assertTrue(folder.contains("@Synchronized\n    fun deleteFile(mediaId: String): FileRemoval {"))
    }

    @Test
    fun `a delete that did not go through is told from a file that is gone already`() {
        assertTrue(deleteFile.contains("fileRemoval("))
        // Asked of the folder only then: the file's own answer, and the folder's.
        assertTrue(deleteFile.contains("file.exists()"))
        assertTrue(deleteFile.contains("mainDir?.exists()"))
        assertTrue("a file in an extra folder is still left alone", deleteFile.contains("if (!isInMainDir(file)) return FileRemoval.NOT_HERE"))
        assertTrue("no file known is nothing to delete", deleteFile.contains("?: return FileRemoval.NOT_HERE"))
    }

    @Test
    fun `a file gone already leaves the folder's list as a deleted one does`() {
        val over = deleteFile.indexOf("if (removal.over) {")
        assertTrue(over > 0)
        assertTrue(deleteFile.indexOf("availableFiles = availableFiles - file") > over)
        assertTrue(deleteFile.indexOf("songFileIds.remove(it)") > over)
    }

    @Test
    fun `the song is forgotten when its file went or was gone, and kept when the delete was refused`() {
        assertTrue("deleteSong reads what came of the delete", deleteSong.contains("val removal = localMgr.deleteFile(id)"))
        val stop = deleteSong.indexOf("if (!removal.over) return@launch")
        assertTrue("it gives up only when the file is still there or none was tried", stop > 0)
        // What forgets the song, as a delete always did: the map entry and both database columns.
        val touched = deleteSong.indexOf("scanTouchedIds.add(id)")
        val map = deleteSong.indexOf("remove(id)")
        val row = deleteSong.indexOf("database.query { removeDownloadSong(id) }")
        assertTrue(touched > stop)
        assertTrue(map > touched)
        assertTrue(row > map)
        assertFalse("the delete's result is no longer a yes or a no", deleteSong.contains("val deleted ="))
    }

    @Test
    fun `a refused delete says why in a toast, on the main thread, and keeps the song`() {
        val refused = deleteSong.indexOf("if (removal == FileRemoval.REFUSED) {")
        val stop = deleteSong.indexOf("if (!removal.over) return@launch")
        assertTrue(refused > 0)
        assertTrue("told before it gives up", refused < stop)
        val toast = deleteSong.substring(refused, stop)
        // Toast.makeText kills the process on a thread with no looper, which dlCoroutine is (ToastOnMainThreadTest).
        assertTrue(toast.contains("withContext(Dispatchers.Main) { Toaster.show(context, R.string.remove_download_refused, Toast.LENGTH_LONG) }"))
    }

    @Test
    fun `the words of the toast are in both languages, held from translators`() {
        val en = Regex("""<string name="remove_download_refused"([^>]*)>([^<]*)</string>""").find(english)
        val fr = Regex("""<string name="remove_download_refused"([^>]*)>([^<]*)</string>""").find(french)
        assertTrue("English", en != null)
        assertTrue("French", fr != null)
        assertTrue("held until the next push to translators", en!!.groupValues[1].contains("translatable=\"false\""))
        for (text in listOf(en.groupValues[2], fr!!.groupValues[2])) {
            assertFalse("no em dash", '—' in text)
            assertTrue("two or three short sentences at most", text.count { it == '.' } in 1..3)
            // Android cuts a toast at two lines (about 30 letters a line on a phone): longer, its last words were lost on the emulator.
            assertTrue("a toast keeps to two lines: ${text.length} letters", text.length <= 64)
        }
        assertEquals(2, Regex("""remove_download_refused""").findAll(english + french).count())
    }
}
