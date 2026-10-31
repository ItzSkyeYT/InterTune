/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.utils

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/** Covers of downloaded songs kept as files (DownloadCovers), so Android emptying the image cache cannot take them. */
class DownloadCoversTest {
    private val base: File = shared

    @Test
    fun `a saved cover is kept as a file and found again without fetching`() {
        val url = "https://yt3.googleusercontent.com/kept=w120-h120-l90-rj"
        var fetched = 0
        assertTrue(DownloadCovers.save(base, url) { fetched++; byteArrayOf(1, 2, 3) })
        assertTrue(DownloadCovers.isKept(base, url))
        assertTrue(DownloadCovers.fileFor(base, url).readBytes().contentEquals(byteArrayOf(1, 2, 3)))
        assertTrue(DownloadCovers.save(base, url) { fetched++; byteArrayOf(9) })
        assertEquals(1, fetched)
    }

    @Test
    fun `a failed fetch keeps nothing and leaves no half file`() {
        val url = "https://i.ytimg.com/vi/failed/hqdefault.jpg"
        assertFalse(DownloadCovers.save(base, url) { null })
        assertFalse(DownloadCovers.isKept(base, url))
        assertFalse(DownloadCovers.dir(base).list().orEmpty().any { it.endsWith(".part") })
    }

    @Test
    fun `removing a download's cover forgets it`() {
        val url = "https://yt3.googleusercontent.com/removed=w128-h128"
        DownloadCovers.save(base, url) { byteArrayOf(7) }
        DownloadCovers.remove(base, url)
        assertFalse(DownloadCovers.isKept(base, url))
        assertFalse(DownloadCovers.fileFor(base, url).exists())
    }

    @Test
    fun `each address has its own steady name`() {
        assertEquals(DownloadCovers.nameFor("https://a/x=w120"), DownloadCovers.nameFor("https://a/x=w120"))
        assertNotEquals(DownloadCovers.nameFor("https://a/x=w120"), DownloadCovers.nameFor("https://a/x=w128"))
    }

    @Test
    fun `the image loader serves kept covers and downloads keep and drop theirs`() {
        val app = File("src/main/java/com/dd3boh/outertune/App.kt").readText()
        assertEquals(2, Regex("""add\(DownloadCovers\.Interceptor\(filesDir\)\)""").findAll(app).count())
        val util = File("src/main/java/com/dd3boh/outertune/playback/DownloadUtil.kt").readText()
        assertTrue(util.substringAfter("private fun keepCoverForOffline(").contains("DownloadCovers.save(context.filesDir, model)"))
        assertTrue(util.contains("rescanDownloads()\n            // Downloads made before their covers were kept as files get theirs now, one at a time;\n            // with no network nothing comes and the next start tries again (DownloadCovers).\n            keepMissingCovers()"))
        assertTrue(util.substringAfter("fun removeDownload(id: String) {").substringBefore("\n    }\n").contains("DownloadCovers.remove(context.filesDir, model)"))
    }

    companion object {
        private val shared: File = Files.createTempDirectory("covers").toFile()
    }
}
