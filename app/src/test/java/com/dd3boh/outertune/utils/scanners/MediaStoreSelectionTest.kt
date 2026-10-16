/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.utils.scanners

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The MediaStore scanner's question to MediaStore. With no scan folder it closed a bracket it had
 * never opened, MediaStore refused the query as a syntax error and the app closed (issue #24: the
 * MediaStore scanner, Scan tapped before any folder was added).
 */
class MediaStoreSelectionTest {

    @Test
    fun `no folder means nothing to ask MediaStore`() {
        assertNull(mediaStoreSelection(emptyList()))
    }

    @Test
    fun `a folder whose path could not be worked out is left out`() {
        assertNull(mediaStoreSelection(listOf(null)))
        val (selection, args) = mediaStoreSelection(listOf(null, "/storage/emulated/0/Music"))!!
        assertEquals("is_music != 0 AND (_data LIKE ?)", selection)
        assertArrayEquals(arrayOf("/storage/emulated/0/Music%"), args)
    }

    @Test
    fun `one folder is one clause in brackets that close`() {
        val (selection, args) = mediaStoreSelection(listOf("/storage/emulated/0/Music"))!!
        assertEquals("is_music != 0 AND (_data LIKE ?)", selection)
        assertArrayEquals(arrayOf("/storage/emulated/0/Music%"), args)
    }

    @Test
    fun `several folders share one pair of brackets`() {
        val (selection, args) = mediaStoreSelection(listOf("/a", "/b", "/c"))!!
        assertEquals("is_music != 0 AND (_data LIKE ? OR _data LIKE ? OR _data LIKE ?)", selection)
        assertArrayEquals(arrayOf("/a%", "/b%", "/c%"), args)
        assertEquals(selection.count { it == '(' }, selection.count { it == ')' })
    }

    @Test
    fun `the scanner asks through it`() {
        val scanner = File("src/main/java/com/dd3boh/outertune/utils/scanners/LocalMediaScanner.kt").readText()
        assertTrue(
            "fullMediaStoreSync must build its selection with mediaStoreSelection",
            Regex("""mediaStoreSelection\(scanPaths\.map""").containsMatchIn(scanner)
        )
        assertTrue("the old builder must be gone", !scanner.contains("selectionBuilder"))
    }

    @Test
    fun `Scan with no folder opens the folder list, and a failed scan does not close the app`() {
        val page = File("src/main/java/com/dd3boh/outertune/ui/screens/settings/fragments/LocalMediaSettingsFrag.kt")
            .readText()
        val noFolder = page.indexOf("if (uriListFromString(scanPaths).isEmpty()) {")
        assertTrue("Scan must look for a folder first", noFolder > 0)
        assertTrue(
            "and open the folder list",
            page.substring(noFolder, noFolder + 400).contains("showAddFolderDialog = true")
        )
        assertTrue(
            "before any scan starts",
            noFolder < page.indexOf("coroutineScope.launch(lmScannerCoroutine) {")
        )
        assertEquals(
            "both scans (full and quick) end any other failure in a message",
            2,
            Regex("""catch \(e: CancellationException\) \{\s*throw e\s*\} catch \(e: Exception\) \{""").findAll(page).count()
        )
    }
}
