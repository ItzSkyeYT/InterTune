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
import java.io.File

/**
 * #243 (21 Nov 2026, Android 9, most likely a Huawei on EMUI 9): "0/111 songs processed" and "Scanner failed: Could
 * not access file". Android 9's folder picker offers Downloads and Documents before the phone's storage, the scanner
 * read only the storage's own ids, and one song it could not find a path for ended the whole full scan, while a plain
 * scan dropped them all without a word.
 */
class ScanFolderPlacesTest {

    private val storage = "com.android.externalstorage.documents"
    private val downloads = "com.android.providers.downloads.documents"

    private fun path(authority: String?, docId: String, card: String? = null) = documentPathBelowR(
        authority, docId,
        primaryDir = "/storage/emulated/0",
        documentsDir = "/storage/emulated/0/Documents",
        cardDir = { volume -> card?.takeIf { volume == "1234-ABCD" } },
    )

    @Test
    fun `the phone's storage and a card read as before`() {
        assertEquals("/storage/emulated/0/Music/a.mp3", path(storage, "primary:Music/a.mp3"))
        assertEquals("/storage/emulated/0", path(storage, "primary:"))
        // A colon in a name stays in the name (volumeAndPath splits at the first one only).
        assertEquals("/storage/emulated/0/Music/Live: Paris.mp3", path(storage, "primary:Music/Live: Paris.mp3"))
        assertEquals("/storage/1234-ABCD/Music/a.mp3", path(storage, "1234-ABCD:Music/a.mp3", card = "/storage/1234-ABCD"))
        // A card that is not in has no path, as before.
        assertNull(path(storage, "1234-ABCD:Music/a.mp3", card = null))
        assertNull(path(storage, "no colon at all"))
    }

    @Test
    fun `Documents, the storage provider's home root, is the phone's Documents folder`() {
        assertEquals("/storage/emulated/0/Documents/Songs/a.mp3", path(storage, "home:Songs/a.mp3"))
        assertEquals("/storage/emulated/0/Documents", path(storage, "home:"))
    }

    @Test
    fun `Downloads' raw ids are the file's own path, and a numbered one has none`() {
        assertEquals(
            "/storage/emulated/0/Download/Music/a.mp3",
            path(downloads, "raw:/storage/emulated/0/Download/Music/a.mp3"),
        )
        assertNull("a file the download list knows by number", path(downloads, "1234"))
        assertNull(path(downloads, "raw:relative/a.mp3"))
        // Any other provider (a cloud, the media provider) has no path either.
        assertNull(path("com.android.providers.media.documents", "audio:12"))
        assertNull(path(null, "primary:Music/a.mp3"))
    }

    private fun main(path: String) = File("src/main/java/com/dd3boh/outertune/$path").readText()

    @Test
    fun `fileFromUri uses it below Android 11, and Android 11 and later are unchanged`() {
        val utils = main("utils/scanners/UriFileUtils.kt")
        val below = utils.substringAfter("fun fileFromUri(").substringBefore("\n}\n").substringAfter("} else {")
        assertTrue(below, below.contains("documentPathBelowR("))
        assertFalse("the storage provider alone again", below.contains("""if (uri.authority != "com.android.externalstorage.documents") return null"""))
        val from11 = utils.substringAfter("fun fileFromUri(").substringBefore("} else {")
        assertTrue(from11.contains("""if (uri.authority != "com.android.externalstorage.documents") return null"""))
    }

    @Test
    fun `one song with no path no longer ends the scan, and none is dropped without a word`() {
        val scanner = main("utils/scanners/LocalMediaScanner.kt")
        assertFalse("the full sync's error again", scanner.contains("""throw IOException("Could not access file")"""))
        assertTrue(scanner.contains("fileFromUri(context, uri) ?: throw NoFilePathException("))
        // The full sync counts them apart.
        val full = scanner.substringAfter("suspend fun fullSync(").substringBefore("scannerJobs.awaitAll()")
        assertTrue(full, full.contains("} catch (e: NoFilePathException) {\n                                elsewhere.incrementAndGet()"))
        // The plain sync counts the ones it cannot turn into a path.
        val quick = scanner.substringAfter("suspend fun quickSync(").substringBefore("val delta =")
        assertTrue(quick, quick.contains("elsewhere.addAndGet(newSongs.size - converted.size)"))
        // Each scan starts from nothing, as the unreadable count does.
        assertEquals(scanner.split("unreadable.set(0)").size, scanner.split("elsewhere.set(0)").size)
    }

    @Test
    fun `the scan page says it, and its picker shows the phone's storage first below Android 11`() {
        val frag = main("ui/screens/settings/fragments/LocalMediaSettingsFrag.kt")
        assertEquals(2, frag.split("ScanReport.noteFor(scanner.lastFolders, scanner.unreadableFiles, scanner.elsewhereFiles)").size - 1)
        assertTrue(frag.contains("rememberLauncherForActivityResult(\n                ScanFolderPicker()\n            )"))
        val picker = main("utils/scanners/UriFileUtils.kt").substringAfter("class ScanFolderPicker")
        assertTrue(picker.contains("if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) putExtra(EXTRA_SHOW_ADVANCED, true)"))
        assertTrue(picker.contains("""EXTRA_SHOW_ADVANCED = "android.content.extra.SHOW_ADVANCED""""))
    }
}
