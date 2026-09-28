/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.db.daos

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.lang.reflect.Proxy

/**
 * chunkSongIds, chunkedUnion and chunkedConcat: the more-than-999-songs crash (add to playlist on
 * a big saved queue, folder or M3U import) was a Room query binding every song id as its own SQL
 * argument, which SQLite before Android 12 refuses past 999. The JVM's own SQLite is more
 * permissive, so these tests check that the helper actually splits the work into chunks and
 * combines them right, rather than trying to reproduce the platform's error.
 */
class ChunkedQueryTest {

    @Test
    fun `ids are split into groups of the chunk size, with the remainder in its own group`() {
        val ids = (1..2000).map { "song-$it" }
        val chunks = chunkSongIds(ids, chunkSize = 900)
        assertEquals(3, chunks.size)
        assertEquals(900, chunks[0].size)
        assertEquals(900, chunks[1].size)
        assertEquals(200, chunks[2].size)
        assertEquals(ids, chunks.flatten())
    }

    @Test
    fun `a list under the chunk size is not split`() {
        assertEquals(1, chunkSongIds(listOf("a", "b", "c"), chunkSize = 900).size)
    }

    @Test
    fun `chunkedUnion runs one query per chunk and unions the results`() = runBlocking {
        val ids = (1..2000).map { "song-$it" }
        var calls = 0
        // A playlist id that shows up while looking up songs from more than one chunk should
        // still be one entry in the result, the way playlistIdBySongsChunked needs it to be.
        val result = chunkedUnion(ids) { chunk ->
            calls++
            assertTrue("a chunk of ${chunk.size} ids", chunk.size <= 900)
            listOf("playlist-shared") + chunk.take(1).map { "playlist-${it}" }
        }
        assertEquals(3, calls)
        assertEquals(1, result.count { it == "playlist-shared" })
        assertEquals(4, result.size) // the shared one plus one per chunk
    }

    @Test
    fun `chunkedConcat runs one query per chunk and concatenates in order`() = runBlocking {
        val ids = (1..2000).map { "song-$it" }
        var calls = 0
        val result = chunkedConcat(ids) { chunk ->
            calls++
            assertTrue("a chunk of ${chunk.size} ids", chunk.size <= 900)
            chunk // every id in the chunk "is a duplicate", as playlistDuplicatesChunked expects
        }
        assertEquals(3, calls)
        assertEquals(ids, result)
    }

    @Test
    fun `a song listed twice across two chunks is one duplicate, as in one query`() = runBlocking {
        // A target playlist holding X once: its duplicates query returns X for any chunk that
        // asks about X.
        val dao = Proxy.newProxyInstance(
            PlaylistsDao::class.java.classLoader, arrayOf(PlaylistsDao::class.java)
        ) { _, method, args ->
            check(method.name == "playlistDuplicates") { "unexpected call to ${method.name}" }
            if ((args[1] as List<*>).contains("X")) listOf("X") else emptyList<String>()
        } as PlaylistsDao
        // The first X falls in the first chunk of 900 and the second in the next one.
        val adding = listOf("X") + (1..900).map { "song-$it" } + "X"
        assertEquals(listOf("X"), dao.playlistDuplicatesChunked("P", adding))
    }
}
