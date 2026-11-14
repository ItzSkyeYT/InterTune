/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.playback

import androidx.media3.datasource.cache.Cache
import androidx.media3.datasource.cache.CacheSpan
import androidx.media3.datasource.cache.ContentMetadata
import androidx.media3.datasource.cache.ContentMetadataMutations
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.NavigableSet

/**
 * #179: a song that is whole in a cache said "No network connection" offline when the player
 * began a read inside its last 512 KB (a seek near the end, a retry, a resume): the resolver asked
 * the cache for a whole chunk from there, which a song with less than a chunk left never holds.
 *
 * media3's SimpleCache cannot be built on the JVM (its index uses android.util.SparseArray), so a
 * small cache stands in with media3's own rule for isCached: every byte of the range is in a span,
 * and the range may not run past the last one (CachedContent.getCachedBytesLength).
 */
class CacheTailTest {
    private val chunk = 512 * 1024L
    private val size = 3_000_000L

    /** Spans as [start, end) per key and the length recorded per key, as media3's content metadata holds it. */
    private class FakeCache(val spans: Map<String, List<LongRange>>, val lengths: Map<String, Long>) : Cache {
        override fun isCached(key: String, position: Long, length: Long): Boolean {
            val end = position + length
            var at = position
            val list = spans[key].orEmpty()
            while (at < end) {
                val span = list.firstOrNull { at >= it.first && at < it.last } ?: return false
                at = span.last
            }
            return true
        }

        override fun getContentMetadata(key: String): ContentMetadata {
            val length = lengths[key]
            return object : ContentMetadata {
                override fun get(key: String, defaultValue: ByteArray?): ByteArray? = defaultValue
                override fun get(key: String, defaultValue: String?): String? = defaultValue
                override fun get(key: String, defaultValue: Long): Long =
                    if (key == ContentMetadata.KEY_CONTENT_LENGTH && length != null) length else defaultValue
                override fun contains(key: String): Boolean = key == ContentMetadata.KEY_CONTENT_LENGTH && length != null
            }
        }

        override fun getUid(): Long = 0
        override fun release() = Unit
        override fun addListener(key: String, listener: Cache.Listener): NavigableSet<CacheSpan> = TODO()
        override fun removeListener(key: String, listener: Cache.Listener) = TODO()
        override fun getCachedSpans(key: String): NavigableSet<CacheSpan> = TODO()
        override fun getKeys(): MutableSet<String> = TODO()
        override fun getCacheSpace(): Long = TODO()
        override fun startReadWrite(key: String, position: Long, length: Long): CacheSpan = TODO()
        override fun startReadWriteNonBlocking(key: String, position: Long, length: Long): CacheSpan? = TODO()
        override fun startFile(key: String, position: Long, length: Long): File = TODO()
        override fun commitFile(file: File, length: Long) = TODO()
        override fun releaseHoleSpan(holeSpan: CacheSpan) = TODO()
        override fun removeResource(key: String) = TODO()
        override fun removeSpan(span: CacheSpan) = TODO()
        override fun getCachedLength(key: String, position: Long, length: Long): Long = TODO()
        override fun getCachedBytes(key: String, position: Long, length: Long): Long = TODO()
        override fun applyContentMetadataMutations(key: String, mutations: ContentMetadataMutations) = TODO()
    }

    private val whole = FakeCache(mapOf("song" to listOf(0L..size)), mapOf("song" to size))

    @Test
    fun `the premise, a whole chunk from inside the last one is never cached`() {
        assertTrue(whole.holdsWhole("song"))
        assertFalse(whole.isCached("song", size - 100_000, chunk))
    }

    @Test
    fun `a read inside the last chunk of a whole song is found in the cache`() {
        assertTrue(whole.holdsFrom("song", size - 100_000, chunk))
        assertTrue(whole.holdsFrom("song", 0, chunk))
        assertTrue(whole.holdsFrom("song", size - 1, chunk))
    }

    @Test
    fun `a song cached only part of the way is not found past its bytes`() {
        val part = FakeCache(mapOf("part" to listOf(0L..1_000_000L)), mapOf("part" to size))
        assertTrue(part.holdsFrom("part", 100, chunk))
        assertFalse(part.holdsFrom("part", 1_000_000, chunk))
        assertFalse(part.holdsFrom("part", size - 100_000, chunk))
    }

    @Test
    fun `a song with no length recorded keeps the old answer, and a hole before the end is a hole`() {
        val unknown = FakeCache(mapOf("u" to listOf(0L..size)), emptyMap())
        assertFalse(unknown.holdsFrom("u", size - 100_000, chunk))
        val holed = FakeCache(mapOf("h" to listOf(0L..1_000_000L, 1_200_000L..size)), mapOf("h" to size))
        assertFalse(holed.holdsFrom("h", size - 2_000_000, chunk * 3))
        assertTrue(holed.holdsFrom("h", size - 100_000, chunk))
    }

    @Test
    fun `the resolver asks the cache this way for the song cache and for a bounded download read`() {
        val src = File("src/main/java/com/dd3boh/outertune/playback/MusicService.kt").readText()
        assertTrue(src.contains("playerCache.holdsFrom(mediaId, position, CHUNK_LENGTH)"))
        assertTrue(src.contains("downloadCache.holdsFrom(mediaId, position, if (length >= 0) length else 1)"))
        assertFalse(src.contains("playerCache.isCached(mediaId, position, CHUNK_LENGTH)"))
    }
}
