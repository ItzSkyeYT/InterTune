/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.playback

import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.time.LocalDateTime

/**
 * Removing one song's download leaves the kept cover to the other downloads that show it.
 *
 * An album's songs share one cover address (the album page gives every track the album's cover),
 * and Remove download deleted the kept cover of the song's address without asking: the album's
 * other downloaded songs then showed no cover offline, until a start with a network fetched it again.
 */
class SharedCoverTest {
    private val landed = LocalDateTime.of(2026, 11, 4, 12, 0)
    private val album = listOf("a", "b", "c")

    @Test
    fun `one song of a downloaded album leaves the cover to the others`() = runBlocking {
        val downloads = MutableStateFlow(mapOf("a" to landed, "b" to landed, "c" to landed))
        val check = async { coverUnusedOnceRemoved("a", downloads, waitMs = 60_000) { album } }
        downloads.value -= "a"
        assertFalse(check.await())
    }

    @Test
    fun `removing a whole album leaves the cover to its last song, which takes it`() = runBlocking {
        // A bulk Remove download asks for each song in a row, before any removal has landed.
        val downloads = MutableStateFlow(mapOf("a" to landed, "b" to landed, "c" to landed, "other" to landed))
        val checks = album.associateWith { id -> async { coverUnusedOnceRemoved(id, downloads, waitMs = 60_000) { album } } }
        downloads.value -= "a"
        assertFalse(checks.getValue("a").await())
        downloads.value -= "b"
        assertFalse(checks.getValue("b").await())
        downloads.value -= "c"
        assertTrue(checks.getValue("c").await())
    }

    @Test
    fun `a song still downloading shows the cover too`() = runBlocking {
        val downloads = MutableStateFlow(mapOf("a" to landed, "b" to DownloadUtil.STATE_DOWNLOADING))
        val check = async { coverUnusedOnceRemoved("a", downloads, waitMs = 60_000) { album } }
        downloads.value -= "a"
        assertFalse(check.await())
    }

    @Test
    fun `a removal that never lands keeps the cover`() = runBlocking {
        // A file in an import folder, which Remove download never deletes, or a service Android
        // refused: the song is still downloaded when the wait ends.
        val downloads = MutableStateFlow(mapOf("a" to landed))
        assertFalse(coverUnusedOnceRemoved("a", downloads, waitMs = 50) { album })
    }

    @Test
    fun `a cover nothing else shows goes at once`() = runBlocking {
        val downloads = MutableStateFlow(mapOf("elsewhere" to landed))
        assertTrue(coverUnusedOnceRemoved("a", downloads, waitMs = 60_000) { listOf("a") })
    }

    @Test
    fun `when the songs sharing it cannot be read, the cover stays`() = runBlocking {
        val downloads = MutableStateFlow(emptyMap<String, LocalDateTime>())
        assertFalse(coverUnusedOnceRemoved("a", downloads, waitMs = 60_000) { null })
        assertFalse(coverUnusedOnceRemoved("a", downloads, waitMs = 60_000) { error("the database did not answer") })
    }

    @Test
    fun `Remove download takes the covers only through that check, one check at a time`() {
        val util = File("src/main/java/com/dd3boh/outertune/playback/DownloadUtil.kt").readText()
        val remove = util.substringAfter("fun removeDownload(id: String) {").substringBefore("\n    }\n")
        val check = remove.indexOf("if (!coverUnusedOnceRemoved(id, downloads, COVER_WAIT_MS) { database.songsWithCover(url) }) return@launch")
        assertTrue(check > 0)
        assertTrue(remove.indexOf("DownloadCovers.remove(context.filesDir, model)") > check)
        assertTrue("checks run one after another, so a bulk removal does not take every database connection", remove.contains("CoroutineScope(coverChecks).launch {"))
        assertTrue(util.contains("private val coverChecks = Dispatchers.IO.limitedParallelism(1)"))
        // The query is the one DownloadSqlTest runs.
        val dao = File("src/main/java/com/dd3boh/outertune/db/daos/SongsDao.kt").readText()
        assertTrue(dao.contains("@Query(DownloadSql.SONGS_WITH_COVER)\n    fun songsWithCover(url: String): List<String>"))
    }
}
