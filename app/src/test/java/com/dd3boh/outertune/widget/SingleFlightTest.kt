/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.widget

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * The widget's pictures are drawn on workers ahead of the composition, and the composition asks for
 * the same ones: whoever asks first draws, the others wait for that drawing, and nothing is drawn twice.
 */
class SingleFlightTest {
    private val kept = ConcurrentHashMap<String, String>()
    private val flight = SingleFlight<String, String>()

    private fun get(key: String, draw: () -> String?) = flight.get(key, { kept[it] }, { k, v -> kept[k] = v }, draw)

    @Test
    fun `a kept picture is handed out without drawing`() {
        kept["a"] = "kept"
        val drawn = AtomicInteger()
        assertEquals("kept", get("a") { drawn.incrementAndGet(); "new" })
        assertEquals(0, drawn.get())
    }

    @Test
    fun `a miss is drawn once and kept`() {
        val drawn = AtomicInteger()
        assertEquals("one", get("a") { drawn.incrementAndGet(); "one" })
        assertEquals("one", get("a") { drawn.incrementAndGet(); "two" })
        assertEquals(1, drawn.get())
    }

    @Test
    fun `many asking for the same picture at once draw it once and all get that one`() {
        val pool = Executors.newFixedThreadPool(8)
        try {
            val drawn = AtomicInteger()
            val release = CountDownLatch(1)
            val started = CountDownLatch(8)
            val results = (1..8).map {
                pool.submit<String?> {
                    started.countDown()
                    get("row") {
                        drawn.incrementAndGet()
                        // Slow enough that the others have come asking while it is in flight.
                        release.await(5, TimeUnit.SECONDS)
                        Thread.sleep(50)
                        "records ${drawn.get()}"
                    }
                }
            }
            assertTrue(started.await(5, TimeUnit.SECONDS))
            Thread.sleep(100)
            release.countDown()
            val got = results.map { it.get(10, TimeUnit.SECONDS) }
            assertEquals("drawn once", 1, drawn.get())
            assertEquals(setOf("records 1"), got.toSet())
        } finally {
            pool.shutdownNow()
        }
    }

    @Test
    fun `different pictures are drawn side by side, not one after the other`() {
        val pool = Executors.newFixedThreadPool(2)
        try {
            val both = CountDownLatch(2)
            val results = listOf("sleeve", "records").map { key ->
                pool.submit<String?> {
                    get(key) {
                        // Each waits for the other to be in its drawing: only side by side can both get out.
                        both.countDown()
                        if (!both.await(5, TimeUnit.SECONDS)) null else "$key drawn"
                    }
                }
            }
            assertEquals(listOf("sleeve drawn", "records drawn"), results.map { it.get(10, TimeUnit.SECONDS) })
        } finally {
            pool.shutdownNow()
        }
    }

    @Test
    fun `a drawing that fails gives nothing, is not kept, and the next to ask draws again`() {
        assertNull(get("a") { throw IllegalStateException("no room") })
        assertNull(get("a") { null })
        assertTrue("nothing kept", kept.isEmpty())
        assertEquals("later", get("a") { "later" })
    }

    @Test
    fun `a picture put away between the lookup and the drawing is the one used`() {
        var looked = 0
        val drawn = AtomicInteger()
        val picture = "from the one before"
        // The first lookup misses, as the one before has not put it away yet; the second, taken once this
        // caller holds the place, finds it.
        val result = flight.get("a", { looked++; if (looked >= 2) picture else null }, { _, _ -> }) { drawn.incrementAndGet(); "again" }
        assertSame(picture, result)
        assertEquals(0, drawn.get())
    }
}
