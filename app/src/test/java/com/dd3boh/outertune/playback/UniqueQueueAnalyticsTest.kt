/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.playback

import androidx.media3.exoplayer.analytics.AnalyticsCollector
import androidx.media3.exoplayer.source.MediaSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.lang.reflect.Modifier

/**
 * #102 and #103 (0.12.9, a TECNO on Android 15, twice in two minutes): media3's analytics closed
 * the app with "Multiple entries with same key" when the player's queue listed a period twice.
 * The player's analytics now see each period once, and nothing else they hear changes.
 */
class UniqueQueueAnalyticsTest {

    private fun period(uid: String, window: Long) = MediaSource.MediaPeriodId(uid, window)

    @Test
    fun `a period listed twice is told once, in its order`() {
        val a = period("a", 1)
        val b = period("b", 2)
        assertEquals(listOf(a, b), UniqueQueueAnalytics.unique(listOf(a, b, a)))
        assertEquals(listOf(a, b), UniqueQueueAnalytics.unique(listOf(a, a, b)))
        // The same period as another object: equal ids are one key in media3's map.
        assertEquals(listOf(a), UniqueQueueAnalytics.unique(listOf(a, period("a", 1))))
        val clean = listOf(a, b, period("a", 3))
        assertSame("a queue with nothing repeated is passed as it is", clean, UniqueQueueAnalytics.unique(clean))
        val empty = emptyList<MediaSource.MediaPeriodId>()
        assertSame(empty, UniqueQueueAnalytics.unique(empty))
    }

    @Test
    fun `every call media3 makes reaches its own collector`() {
        // Each method of the collector is passed on, the player's listener
        // methods with Java default bodies among them, or the app's play counts and health line
        // would stop hearing the player.
        val wrapper = UniqueQueueAnalytics::class.java
        val missing = AnalyticsCollector::class.java.methods
            .filter { !Modifier.isStatic(it.modifiers) }
            .filter { m -> runCatching { wrapper.getDeclaredMethod(m.name, *m.parameterTypes) }.isFailure }
            .map { it.name }
        assertTrue("not forwarded: $missing", missing.isEmpty())
    }

    @Test
    fun `the player is built with it`() {
        val service = File("src/main/java/com/dd3boh/outertune/playback/MusicService.kt").readText()
        assertTrue(".setAnalyticsCollector(UniqueQueueAnalytics())" in service)
    }
}
