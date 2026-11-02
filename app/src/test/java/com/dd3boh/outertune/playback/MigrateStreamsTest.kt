/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * A download moved into the download folder, by Migrate in Settings or by itself once it finishes, is
 * copied from the cache's files as it is written, never read whole into memory first: a song over about
 * 128 MB, a long mix, ran the app out of memory (#67, 31 Oct 2026, a Xiaomi with a 256 MB heap).
 */
class MigrateStreamsTest {
    @get:Rule
    val dir = TemporaryFolder()

    private fun piece(name: String, text: String) = dir.newFile(name).apply { writeText(text) }

    @Test
    fun `the pieces of a song read as one stream, in their order`() {
        val read = cachedPieces(listOf(piece("0", "Flynn "), piece("1", ""), piece("2", "Lives"))).use { it.readBytes() }
        assertEquals("Flynn Lives", String(read))
    }

    @Test
    fun `a piece is opened only once the one before it is used up`() {
        val first = piece("first", "ab")
        val later = File(dir.root, "later")   // not there yet: opening it now would fail
        cachedPieces(listOf(first, later)).use { stream ->
            assertEquals('a'.code, stream.read())
            assertEquals('b'.code, stream.read())
            later.writeText("cd")
            assertEquals("cd", String(stream.readBytes()))
        }
    }

    @Test
    fun `nothing of a song goes through an array of its own size`() {
        val body = File("src/main/java/com/dd3boh/outertune/playback/DownloadUtil.kt").readText()
        assertFalse(body.contains("ByteArrayOutputStream"))
        assertFalse("the old reader that held the whole song", body.contains("fun getFromCache("))
        val migrate = body.substringAfter("suspend fun migrateDownloads(").substringBefore("fun cd()")
        assertTrue(migrate.contains("openFromCache(downloadCache, s.key)"))
        assertTrue("the stream is closed after the copy", migrate.contains(".use { stream ->"))
        assertTrue(migrate.contains("localMgr.saveFile(s.key, stream, displayName)"))
    }
}
