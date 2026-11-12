/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.innertube

import com.zionhuang.innertube.utils.parseTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Song lengths as YouTube Music writes them in the listener's language (#83, 5 Nov 2026: in
 * Indonesian, Finnish and Danish they come as "3.34", and every song of a radio after the first was
 * dropped). Read from /next on 5 Nov 2026 in 40 languages: experiments/bugs/issue-83/lengths-by-language.txt.
 */
class SongLengthTest {
    @Test
    fun `a length with a colon or with a dot is read, with or without hours`() {
        assertEquals(214, "3:34".parseTime())
        assertEquals(214, "3.34".parseTime())
        assertEquals(360, "6.00".parseTime())
        assertEquals(3723, "1:02:03".parseTime())
        assertEquals(3723, "1.02.03".parseTime())
        assertEquals("spaces round it", 215, " 3.35 ".parseTime())
    }

    @Test
    fun `what is not a length stays unread`() {
        for (text in listOf("3", "2019", "1,8 M", "1.8B", "19 jt suka", "3:5", "3.75", "", "a:bc", "1:2:3:4")) {
            assertNull(text, text.parseTime())
        }
    }
}
