/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.playback

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * With the download folder not opening (its permission gone, a backup restored, a card out), the
 * move after a download no longer converts every song in full only to throw it away, and the notice
 * no longer says the song was saved as it came when nothing was saved (review of 0.13, F6). Each
 * download was decoded and encoded, the folder refused the file and the song as it came, and the
 * song stayed inside the app under "1 song could not be converted and was saved as it came".
 */
class DownloadFolderClosedMoveTest {
    private val util = File("src/main/java/com/dd3boh/outertune/playback/DownloadUtil.kt").readText()
    private val migrate = util.substringAfter("suspend fun migrateDownloads(").substringBefore("    private suspend fun saveSongFile(")

    @Test
    fun `nothing moves for a folder that does not open, before anything is read or converted`() {
        val check = migrate.indexOf("if (localMgr.folderState.value != DownloadFolderState.OPEN) {")
        assertTrue(check >= 0)
        for (later in listOf("isProcessingDownloads.value = true", "ConvertNotice(", "converter.sweep()", "openFromCache(", "moveIntoFolder(")) {
            assertTrue(later, check < migrate.indexOf(later))
        }
        val skip = migrate.substring(check).substringBefore("\n        }\n")
        assertTrue(skip.contains("return@withLock"))
    }

    @Test
    fun `a move asked for is told at once, the quiet one after a download as before`() {
        val skip = migrate.substringAfter("if (localMgr.folderState.value != DownloadFolderState.OPEN) {").substringBefore("\n        }\n")
        assertTrue(skip.contains("if (!quiet) withContext(Dispatchers.Main) { Toaster.show(context, R.string.dl_folder_cannot_open_notice, Toast.LENGTH_LONG) }"))
        // The quiet move: said once a start, before the move (notifyIfFolderCannotOpen).
        val idle = util.substringAfter("override fun onIdle(downloadManager: DownloadManager) {").substringBefore("override fun onDownloadChanged(")
        assertTrue(idle.indexOf("notifyIfFolderCannotOpen()") in 0 until idle.indexOf("migrateDownloads(quiet = true, only = just)"))
    }

    @Test
    fun `saved as it came is said only of a song that was`() {
        val save = util.substringAfter("private suspend fun saveSongFile(").substringBefore("\n    }\n")
        assertTrue(save.contains("if (conversion?.failure != null && saved != null) notice?.fellBack()"))
        assertFalse(save.contains("if (conversion?.failure != null) notice?.fellBack()"))
    }
}
