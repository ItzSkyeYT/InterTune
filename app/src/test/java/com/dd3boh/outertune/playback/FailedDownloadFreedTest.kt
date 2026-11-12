/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.playback

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * A download media3 has given up on no longer keeps the bytes it had.
 *
 * media3 keeps a failed download in its index with its bytes, for a retry nothing asks for, and the
 * download cache never evicts. The song reads as not downloaded, so no Remove download is offered,
 * and only Clear all downloads freed them. The counts that follow the downloads keep such a song as
 * failed through that removal: DownloadRunTest and LikedCatchUpTest.
 */
class FailedDownloadFreedTest {
    private val util = File("src/main/java/com/dd3boh/outertune/playback/DownloadUtil.kt").readText()

    /** The index of the brace closing the one at [open]. */
    private fun closing(text: String, open: Int): Int {
        var depth = 0
        for (i in open until text.length) {
            when (text[i]) {
                '{' -> depth++
                '}' -> if (--depth == 0) return i
            }
        }
        return text.length
    }

    @Test
    fun `a failed download is removed as Remove download removes a finished one, in the change itself`() {
        val listener = util.substringAfter("downloadManager.addListener(\n            object : DownloadManager.Listener {")
        val open = listener.indexOf("override fun onDownloadChanged(")
        assertTrue(open > 0)
        val body = listener.substring(open, closing(listener, listener.indexOf('{', open)))
        val freed = body.indexOf("if (download.state == Download.STATE_FAILED) {\n                        runCatching { downloadManager.removeDownload(download.request.id) }")
        assertTrue("the listener removes a failed download", freed > 0)
        // On the listener's own thread, where the manager lives, and not in a coroutine: a download of
        // the same song asked for again afterwards is then queued after this removal, never taken by it.
        val launch = body.indexOf("CoroutineScope(Dispatchers.IO).launch {")
        assertTrue(launch > 0)
        assertTrue("the removal is outside the database write's coroutine", freed > closing(body, body.indexOf('{', launch)))
        // After the ending and the map, so the catch up has recorded the failure first.
        assertTrue(freed > body.indexOf("LikedCatchUp.afterUpdate(it, batch, download.request.id, download.state)"))
        assertTrue(freed > body.indexOf("downloads.update { map ->"))
    }
}
