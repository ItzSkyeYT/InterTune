/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** A stale or removed song id must fail the request, not silently play whatever sorts first. */
class PlayRequestIndexTest {

    @Test
    fun `finds the requested song`() {
        assertEquals(2, PlayRequestIndex.indexOf(listOf("a", "b", "c"), "c"))
        assertEquals(0, PlayRequestIndex.indexOf(listOf("a", "b", "c"), "a"))
    }

    @Test
    fun `a song no longer in the list is null, not the first song`() {
        assertNull(PlayRequestIndex.indexOf(listOf("a", "b", "c"), "removed"))
    }

    @Test
    fun `an empty list is null`() {
        assertNull(PlayRequestIndex.indexOf(emptyList(), "anything"))
    }
}
