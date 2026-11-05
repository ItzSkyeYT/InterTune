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
 * New downloads go into the folder set in Settings. A download is always made inside the app
 * first, and only Migrate ever took songs out, so a folder set in Storage and downloads never got a
 * new download (dvd on Discord, 26 Oct 2026).
 */
class DownloadToFolderTest {
    private val body = File("src/main/java/com/dd3boh/outertune/playback/DownloadUtil.kt").readText()

    @Test
    fun `a finished download goes into the folder once the downloads under way are done`() {
        val idle = body.substringAfter("override fun onIdle(downloadManager: DownloadManager) {").substringBefore("override fun onDownloadChanged(")
        assertTrue("only with a folder set", idle.contains("context.dataStore.get(DownloadPathKey, \"\").isEmpty()"))
        assertTrue(idle.contains("migrateDownloads(quiet = true, only = just)"))
        assertTrue(body.contains("if (download.state == Download.STATE_COMPLETED) {\n                        finishedSinceMove += download.request.id"))
    }

    @Test
    fun `only the songs just finished move, never the earlier downloads without a word`() {
        assertTrue(body.contains("(only == null || it.key in only)"))
    }

    @Test
    fun `Migrate reads media3's index itself and starts no download manager of its own`() {
        val migrate = body.substringAfter("suspend fun migrateDownloads(").substringBefore("fun cd()")
        assertFalse("a second DownloadManager starts a thread that is never let go", migrate.contains("DownloadManager("))
        assertTrue(migrate.contains("DefaultDownloadIndex(databaseProvider)"))
    }
}
