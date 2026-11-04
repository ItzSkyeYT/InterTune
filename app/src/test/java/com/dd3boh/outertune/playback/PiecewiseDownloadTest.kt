/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Downloads crawled at about 32 KB/s, twice real time: a three-hour mix took 100 minutes on the
 * Pixel 5 (31 Oct 2026). YouTube serves one open-ended request for a stream that slowly and ranges
 * at full speed: a 3.6 MB song took 110 s as one request and 0.4 s as 1 MB ranges
 * (experiments/bugs/dl-speed/probe_speed.py, 1 Nov 2026). Downloads now read in ranges.
 */
class PiecewiseDownloadTest {
    private val mb = 1L shl 20

    @Test
    fun `the whole stream's length is read off a range's answer, whatever the case of the header`() {
        assertEquals(3_620_000L, Pieces.totalOf(mapOf("Content-Range" to listOf("bytes 0-1048575/3620000"))))
        assertEquals(3_620_000L, Pieces.totalOf(mapOf("content-range" to listOf("bytes 1048576-2097151/3620000"))))
    }

    @Test
    fun `an answer that is the whole stream gives its length, and one that names no total gives none`() {
        assertEquals("a 200 with no range", 3_620_000L, Pieces.totalOf(mapOf("Content-Length" to listOf("3620000"))))
        assertNull("a range whose total is unknown", Pieces.totalOf(mapOf("Content-Range" to listOf("bytes 0-9/*"), "Content-Length" to listOf("10"))))
        assertNull(Pieces.totalOf(emptyMap()))
    }

    @Test
    fun `each range is a megabyte, and the last one only what is left`() {
        assertEquals(mb, Pieces.nextLength(0, 3_620_000, mb))
        assertEquals(3_620_000L - 3 * mb, Pieces.nextLength(3 * mb, 3_620_000, mb))
        assertEquals("the total not known yet", mb, Pieces.nextLength(0, null, mb))
        assertEquals(1L shl 20, Pieces.PIECE)
    }

    @Test
    fun `downloads read in ranges, below the cache, and only an open-ended web request is cut up`() {
        val source = File("src/main/java/com/dd3boh/outertune/playback/PiecewiseDataSource.kt").readText()
        assertTrue(source.contains("dataSpec.length == C.LENGTH_UNSET.toLong()"))
        assertTrue(source.contains("scheme == \"http\" || scheme == \"https\""))
        assertTrue("a used-up range is followed by the next", source.contains("if (n != C.RESULT_END_OF_INPUT)"))
        val downloads = File("src/main/java/com/dd3boh/outertune/playback/DownloadUtil.kt").readText()
        val upstream = downloads.substringAfter("private val dataSourceFactory = ResolvingDataSource.Factory(").substringBefore(") { dataSpec ->")
        assertTrue(upstream.contains("SabrOrHttp.Factory(\n                    PiecewiseDataSource.Factory(\n                        OkHttpDataSource.Factory("))
    }
}
