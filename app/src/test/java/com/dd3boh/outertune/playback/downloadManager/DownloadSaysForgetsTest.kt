/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.playback.downloadManager

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Where a download went is said only of a download that lands after it was asked for (review of 0.13,
 * S4). A song asked for that was downloaded already, failed or was removed stayed waited for, and if
 * a like or a sync downloaded it much later the toast came then, out of nowhere.
 */
class DownloadSaysForgetsTest {
    private val util = File("src/main/java/com/dd3boh/outertune/playback/DownloadUtil.kt").readText()
    private val says = File("src/main/java/com/dd3boh/outertune/playback/downloadManager/DownloadSays.kt").readText()

    @Test
    fun `a song already downloaded is not waited for`() {
        assertTrue(util.contains("private fun rememberAskedByHand(ids: List<String>) = says.asked(ids.filterNot { isDownloaded(it) })"))
    }

    @Test
    fun `a download that failed or went is forgotten`() {
        val forget = says.substringAfter("fun forget(id: String) {").substringBefore("\n    }\n")
        assertTrue(forget.contains("asked.remove(id)"))
        val ended = util.substringAfter("if (download.state == Download.STATE_FAILED || download.state == Download.STATE_REMOVING) {").substringBefore("\n                    }\n")
        assertTrue(ended.contains("says.forget(download.request.id)"))
    }
}
