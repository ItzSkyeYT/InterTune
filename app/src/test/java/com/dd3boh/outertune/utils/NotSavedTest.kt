/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.utils

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.io.IOException

/**
 * Writes a full phone refuses (UI scan, 5 Nov 2026): said in a toast, never thrown out of a scope
 * that has no handler, which closed the app at a tap on a switch, a sort or Add to playlist.
 */
class NotSavedTest {
    private fun source(path: String) = File("src/main/java/com/dd3boh/outertune/$path").readText()

    @Test
    fun `a write refused for want of space is told apart`() {
        assertTrue(NotSaved.isStorageFull(IOException("write failed: ENOSPC (No space left on device)")))
        assertTrue(NotSaved.isStorageFull(RuntimeException("edit failed", IOException("No space left on device"))))
        assertFalse(NotSaved.isStorageFull(IOException("Permission denied")))
        assertFalse(NotSaved.isStorageFull(IllegalStateException()))
    }

    @Test
    fun `both setting helpers write through editOrSay`() {
        val store = source("utils/DataStore.kt")
        assertFalse("a bare edit throws out of the setter's scope", "context.dataStore.edit" in store)
        assertTrue(Regex("""context\.editOrSay \{""").findAll(store).count() == 2)
    }

    @Test
    fun `Add to playlist says a failed write rather than throwing it`() {
        val dialog = source("ui/dialog/AddToPlaylistDialog.kt")
        val pick = dialog.substringAfter("coroutineScope.launch(Dispatchers.IO) {").substringBefore("if (syncMode == SyncMode.RO)")
        assertTrue("catch (e: Exception) {\n                            NotSaved.say(context, e)" in pick)
        val add = dialog.substringAfter("private fun addAndSay(").substringBefore("fun pushToYouTube(")
        assertTrue(Regex("""val written = try \{\s*database\.addSongToPlaylist\(playlist, ids\)\s*\} catch \(e: Exception\) \{\s*NotSaved\.say\(context, e\)""").containsMatchIn(add))
    }
}
