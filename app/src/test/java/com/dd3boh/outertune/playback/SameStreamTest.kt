/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.playback

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A cached part is carried on only with the stream it came from. Since a song can be played from
 * a stand-in, another upload under the same itag, the itag no longer says that by itself: the
 * part would be the start of one upload and the rest of another, joined where the part stopped.
 */
class SameStreamTest {
    @Test
    fun `the same itag and the same length is the same stream`() {
        assertTrue(sameStream(251, 3_000_000, 251, 3_000_000))
        assertFalse(anotherUpload(251, 3_000_000, 251, 3_000_000))
    }

    @Test
    fun `the same itag and another length is another upload`() {
        assertFalse(sameStream(251, 3_000_000, 251, 3_004_511))
        assertTrue(anotherUpload(251, 3_000_000, 251, 3_004_511))
    }

    @Test
    fun `another itag is not the same stream, and is not called another upload either`() {
        // Another quality of the same upload: what the part's check has always caught at the
        // song's start, and what is left alone further in, as before.
        assertFalse(sameStream(140, 3_000_000, 251, 3_000_000))
        assertFalse(anotherUpload(140, 3_000_000, 251, 3_004_511))
    }

    @Test
    fun `a length that is not known settles nothing, and the itag decides as it used to`() {
        assertTrue(sameStream(251, null, 251, 3_000_000))
        assertTrue(sameStream(251, 0, 251, 3_000_000))
        assertTrue(sameStream(251, 3_000_000, 251, 0))
        assertFalse(anotherUpload(251, null, 251, 3_000_000))
        assertFalse(anotherUpload(251, 0, 251, 3_000_000))
        assertFalse(anotherUpload(251, 3_000_000, 251, 0))
    }

    @Test
    fun `no row at all is not the same stream`() {
        assertFalse(sameStream(null, null, 251, 3_000_000))
        assertFalse(anotherUpload(null, null, 251, 3_000_000))
    }
}
