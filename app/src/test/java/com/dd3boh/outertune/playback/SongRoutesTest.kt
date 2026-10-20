/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.playback

import org.junit.Assert.assertEquals
import org.junit.Test

class SongRoutesTest {
    @Test
    fun `a route the player failed on is avoided for the song for half an hour`() {
        val routes = SongRoutes()
        routes.mark("song", "VISIONOS", 0)
        routes.mark("song", "VISIONOS_104", 1_000)
        routes.mark("song", null, 2_000)
        assertEquals(setOf("VISIONOS", "VISIONOS_104"), routes.avoid("song", 10_000))
        assertEquals(emptySet<String>(), routes.avoid("other", 10_000))
        assertEquals(emptySet<String>(), routes.avoid("song", 1_000 + SongRoutes.KEEP_MS))
    }

    @Test
    fun `the 201st song pushes out the one marked longest ago`() {
        val routes = SongRoutes()
        for (i in 0..200) routes.mark("song$i", "VISIONOS", i.toLong())
        assertEquals(emptySet<String>(), routes.avoid("song0", 300))
        assertEquals(setOf("VISIONOS"), routes.avoid("song1", 300))
        assertEquals(setOf("VISIONOS"), routes.avoid("song200", 300))
    }
}
