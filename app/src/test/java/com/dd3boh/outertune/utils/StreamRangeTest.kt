/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.utils

import okhttp3.Request
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Requests for a song's bytes always name a range (StreamRange): without one, YouTube held Low's first bytes back for seconds. */
class StreamRangeTest {

    private fun get(url: String, range: String? = null) =
        Request.Builder().url(url).apply { if (range != null) header("Range", range) }.build()

    @Test
    fun `a stream server GET without a range asks from the start`() {
        val asked = StreamRange.withRange(get("https://rr5---sn-u125g5-5i.googlevideo.com/videoplayback?itag=249"))
        assertEquals("bytes=0-", asked.header("Range"))
    }

    @Test
    fun `a range the player asked for is kept`() {
        val asked = StreamRange.withRange(get("https://rr5---sn-u125g5-5i.googlevideo.com/videoplayback", "bytes=524288-"))
        assertEquals("bytes=524288-", asked.header("Range"))
        assertEquals(1, asked.headers("Range").size)
    }

    @Test
    fun `other servers and other methods are left alone`() {
        assertNull(StreamRange.withRange(get("https://music.youtube.com/youtubei/v1/player")).header("Range"))
        val head = Request.Builder().url("https://rr5---sn-u125g5-5i.googlevideo.com/videoplayback").head().build()
        assertNull(StreamRange.withRange(head).header("Range"))
    }

    @Test
    fun `the stream client carries it`() {
        val family = File("src/main/java/com/dd3boh/outertune/utils/StreamFamily.kt").readText()
        assertTrue(family.contains(".addInterceptor(StreamRange.interceptor)"))
    }
}
