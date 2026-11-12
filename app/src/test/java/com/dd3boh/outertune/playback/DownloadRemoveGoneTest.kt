/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.playback

import com.dd3boh.outertune.playback.downloadManager.FileRemoval
import com.dd3boh.outertune.playback.downloadManager.fileRemoval
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * What a delete of a song's file in the download folder came to (fileRemoval): a file gone already,
 * deleted in a file manager since the folder was listed, is told from one the folder refused to
 * delete (dvd, 11 Nov 2026: Remove all left such a song listed under Downloads).
 */
class DownloadRemoveGoneTest {

    private fun removal(deleted: Boolean, fileThere: Boolean, folderThere: Boolean) =
        fileRemoval(deleted, { fileThere }, { folderThere })

    @Test
    fun `a file that was deleted is deleted, and nothing more is asked of the folder`() {
        val answer = fileRemoval(
            deleted = true,
            fileThere = { fail("the file was asked after a delete that went through"); true },
            folderThere = { fail("the folder was asked after a delete that went through"); true },
        )
        assertEquals(FileRemoval.DELETED, answer)
    }

    @Test
    fun `a delete that did not go through on a file the folder no longer has is a file gone already`() {
        // The case of the report: the file was deleted in a file manager, the list still holds it.
        assertEquals(FileRemoval.GONE, removal(deleted = false, fileThere = false, folderThere = true))
    }

    @Test
    fun `a delete that did not go through on a file that is still there is refused`() {
        // A permission, or a provider that does not delete.
        assertEquals(FileRemoval.REFUSED, removal(deleted = false, fileThere = true, folderThere = true))
    }

    @Test
    fun `a folder that does not answer says nothing of the file, so the song stays`() {
        // A card not mounted, a grant that is gone, the folder deleted: the scan spares such songs the same way.
        assertEquals(FileRemoval.REFUSED, removal(deleted = false, fileThere = false, folderThere = false))
        assertEquals(FileRemoval.REFUSED, removal(deleted = false, fileThere = true, folderThere = false))
    }

    @Test
    fun `a file still there is enough to refuse, the folder is not asked`() {
        val answer = fileRemoval(
            deleted = false,
            fileThere = { true },
            folderThere = { fail("the folder was asked of a file that is there"); true },
        )
        assertEquals(FileRemoval.REFUSED, answer)
    }

    @Test
    fun `a song's download is over when its file went or was gone, and at no other time`() {
        assertTrue(FileRemoval.DELETED.over)
        assertTrue(FileRemoval.GONE.over)
        assertFalse("nothing was tried: no file known, or one in an extra folder, which is never changed", FileRemoval.NOT_HERE.over)
        assertFalse("the file is still there", FileRemoval.REFUSED.over)
    }
}
