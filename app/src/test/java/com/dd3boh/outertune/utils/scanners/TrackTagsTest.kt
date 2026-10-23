/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.utils.scanners

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TrackTagsTest {

    @Test
    fun `a tag with the count after a slash gives its number`() {
        // What TagLib hands over for an MP3's TRCK or an M4A's trkn when the count is set (#39).
        assertEquals(4, TrackTags.number("4/12"))
        assertEquals(1, TrackTags.number("1/2"))
    }

    @Test
    fun `plain and padded numbers read as numbers`() {
        assertEquals(1, TrackTags.number("1"))
        assertEquals(10, TrackTags.number("10"))
        assertEquals(8, TrackTags.number("08"))
        assertEquals(7, TrackTags.number(" 7 "))
    }

    @Test
    fun `no number reads as null`() {
        assertNull(TrackTags.number(null))
        assertNull(TrackTags.number(""))
        assertNull(TrackTags.number("0"))
        assertNull(TrackTags.number("/12"))
        assertNull(TrackTags.number("A1"))
        assertNull(TrackTags.number("99999999999"))
    }

    @Test
    fun `MediaStore's text columns win, padded or with a count`() {
        assertEquals(Pair(3, 2), TrackTags.fromMediaStore("3/12", "2/2", 2003))
        // getInt read "08" as octal and gave 0.
        assertEquals(Pair(8, null), TrackTags.fromMediaStore("08", null, 8))
    }

    @Test
    fun `MediaStore's packed TRACK fills in on Android 10 and older`() {
        assertEquals(Pair(3, 2), TrackTags.fromMediaStore(null, null, 2003))
        assertEquals(Pair(7, null), TrackTags.fromMediaStore(null, null, 7))
        assertEquals(Pair(null, 1), TrackTags.fromMediaStore(null, null, 1000))
    }

    @Test
    fun `no tag in MediaStore is no number, not 0`() {
        assertEquals(Pair(null, null), TrackTags.fromMediaStore(null, null, null))
        assertEquals(Pair(null, null), TrackTags.fromMediaStore(null, null, 0))
        assertEquals(Pair(null, null), TrackTags.fromMediaStore("", "", null))
    }
}
