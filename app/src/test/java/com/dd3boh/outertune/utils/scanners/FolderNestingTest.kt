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
}
