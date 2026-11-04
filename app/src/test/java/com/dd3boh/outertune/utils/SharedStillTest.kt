/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.utils

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * His report of 1 Nov 2026: a lyrics video shared to Discord was black at 0:00 / 0:00, and what had
 * gone out was the 360 x 640 picture saved beside the video for the share sheet. The provider gave
 * that picture to any reader whose type filter took a JPEG, and the filter for any type takes anything: an app that
 * reads a shared file as "any type" got the picture under the video's name. The picture is now for
 * a reader that asks for a picture and would not take the video itself.
 */
class SharedStillTest {
    @Test
    fun `a reader that takes anything, or a video, gets the video`() {
        assertFalse("the report: any type", SharedStill.wanted("video/mp4", "*/*"))
        assertFalse(SharedStill.wanted("video/mp4", "video/*"))
        assertFalse(SharedStill.wanted("video/mp4", "video/mp4"))
    }

    @Test
    fun `a reader that asks for a picture, as the share sheet's preview does, gets the picture`() {
        assertTrue(SharedStill.wanted("video/mp4", "image/*"))
        assertTrue(SharedStill.wanted("video/mp4", "image/jpeg"))
        assertFalse("a picture of another kind", SharedStill.wanted("video/mp4", "image/png"))
    }

    @Test
    fun `only a video has a picture beside it`() {
        assertFalse(SharedStill.wanted("image/png", "image/*"))
        assertFalse(SharedStill.wanted("audio/mpeg", "*/*"))
        assertFalse(SharedStill.wanted(null, "image/*"))
    }

    @Test
    fun `types are matched as Android matches them`() {
        assertTrue(SharedStill.matches("image/jpeg", "*/*"))
        assertTrue(SharedStill.matches("image/jpeg", "image/*"))
        assertTrue(SharedStill.matches("IMAGE/JPEG", "image/jpeg"))
        assertFalse(SharedStill.matches("video/mp4", "image/*"))
        assertFalse(SharedStill.matches("image/jpeg", "video/*"))
    }

    @Test
    fun `asked what a video can be, it says the video first and then its picture, which the share sheet shows`() {
        assertEquals(listOf("video/mp4", "image/jpeg"), SharedStill.streamTypes("video/mp4", "*/*", hasStill = true)?.toList())
        assertEquals(listOf("image/jpeg"), SharedStill.streamTypes("video/mp4", "image/*", hasStill = true)?.toList())
        assertEquals(listOf("video/mp4"), SharedStill.streamTypes("video/mp4", "video/*", hasStill = true)?.toList())
        assertEquals("no picture beside it", listOf("video/mp4"), SharedStill.streamTypes("video/mp4", "*/*", hasStill = false)?.toList())
        assertNull("not a video: the provider's own answer", SharedStill.streamTypes("image/png", "*/*", hasStill = true))
        assertNull(SharedStill.streamTypes("video/mp4", "audio/*", hasStill = true))
    }

    @Test
    fun `the provider asks these questions, and no longer the old one a reader of any type passed`() {
        val provider = File("src/main/java/com/dd3boh/outertune/utils/SharedFileProvider.kt").readText()
        assertTrue(provider.contains("if (SharedStill.wanted(getType(uri), mimeTypeFilter))"))
        assertTrue(provider.contains("SharedStill.streamTypes(getType(uri), mimeTypeFilter, hasStill)"))
        assertFalse(provider.contains("ClipDescription.compareMimeTypes(STILL_TYPE, mimeTypeFilter)"))
    }
}
