/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.utils.scanners

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FolderNestingTest {

    private fun tree(id: String) =
        "content://com.android.externalstorage.documents/tree/" + java.net.URLEncoder.encode(id, "UTF-8")

    @Test
    fun `the folder id is read from a picked tree address`() {
        assertEquals(
            "primary:Music/Downloads",
            FolderNesting.treeDocumentId("content://com.android.externalstorage.documents/tree/primary%3AMusic%2FDownloads")
        )
        assertNull(FolderNesting.treeDocumentId("file:///storage/emulated/0/Music"))
    }

    @Test
    fun `a download folder inside a scan folder is caught`() {
        assertTrue(FolderNesting.isSameOrInside(tree("primary:Music/InterTune"), tree("primary:Music")))
        assertTrue(FolderNesting.isSameOrInside(tree("primary:Music/A/B"), tree("primary:Music")))
    }

    @Test
    fun `the same folder twice is caught`() {
        assertTrue(FolderNesting.isSameOrInside(tree("primary:Music"), tree("primary:Music")))
    }

    @Test
    fun `a folder whose name only starts the same is not inside`() {
        assertFalse(FolderNesting.isSameOrInside(tree("primary:Music2"), tree("primary:Music")))
    }

    @Test
    fun `a parent is not inside its child`() {
        assertFalse(FolderNesting.isSameOrInside(tree("primary:Music"), tree("primary:Music/InterTune")))
    }

    @Test
    fun `everything on a volume is inside its root, and nothing on another volume is`() {
        assertTrue(FolderNesting.isSameOrInside(tree("primary:Music"), tree("primary:")))
        assertFalse(FolderNesting.isSameOrInside(tree("1234-5678:Music"), tree("primary:")))
        assertFalse(FolderNesting.isSameOrInside(tree("1234-5678:Music"), tree("primary:Music")))
    }

    // isSameOrInsideId: the same rule taking document ids directly, for comparing a found file's
    // own id (read with DocumentsContract.getDocumentId) against a folder's tree id (read with
    // DocumentsContract.getTreeDocumentId), which is what a raw tree address cannot do for a file.

    @Test
    fun `a file's document id inside an excluded folder's id is caught`() {
        assertTrue(FolderNesting.isSameOrInsideId("primary:Music/WhatsApp Audio/x.opus", "primary:Music/WhatsApp Audio"))
        assertTrue(FolderNesting.isSameOrInsideId("primary:Music/WhatsApp Audio", "primary:Music/WhatsApp Audio"))
    }

    @Test
    fun `a sibling folder's id is not caught by a prefix that only looks similar`() {
        assertFalse(FolderNesting.isSameOrInsideId("primary:Music2/x.opus", "primary:Music"))
        assertFalse(FolderNesting.isSameOrInsideId("primary:MusicVideos/x.opus", "primary:Music"))
    }
}
