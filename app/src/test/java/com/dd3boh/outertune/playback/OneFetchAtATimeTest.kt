/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

/**
 * The player's resolver with AutoMix in the build: a song fetched by two readers at once is
 * fetched once, the second finding what the first kept, and two songs never wait on each other.
 */
class OneFetchAtATimeTest {

    @Test
    fun `a second fetch of a song under way waits for the first and finds what it kept`() {
        val fetches = OneFetchAtATime()
        val kept = HashMap<String, String>()
        val asked = AtomicInteger()
        val firstInside = CountDownLatch(1)
        val letFirstGo = CountDownLatch(1)
        // Each reader asks for the address, and fetches one only if none is kept by then.
        fun resolve(): String = fetches.run("song") {
            synchronized(kept) { kept["song"] } ?: run {
                asked.incrementAndGet()
                firstInside.countDown()
                letFirstGo.await(5, TimeUnit.SECONDS)
                "address".also { synchronized(kept) { kept["song"] = it } }
            }
        }
        var first = ""
        var second = ""
        val a = thread { first = resolve() }
        assertTrue(firstInside.await(5, TimeUnit.SECONDS))
        val b = thread { second = resolve() }
        Thread.sleep(100)
        assertTrue("the second waits while the first fetches", b.isAlive)
        letFirstGo.countDown()
        a.join(5_000)
        b.join(5_000)
        assertEquals("address", first)
        assertEquals("address", second)
        assertEquals("YouTube was asked once", 1, asked.get())
    }

    @Test
    fun `fetches of two songs do not wait on each other`() {
        val fetches = OneFetchAtATime()
        val bothInside = CountDownLatch(2)
        val results = ArrayList<String>()
        val threads = listOf("one", "two").map { id ->
            thread {
                fetches.run(id) {
                    bothInside.countDown()
                    // Each waits for the other to be inside at the same time.
                    assertTrue(bothInside.await(5, TimeUnit.SECONDS))
                    synchronized(results) { results += id }
                }
            }
        }
        threads.forEach { it.join(5_000) }
        assertEquals(setOf("one", "two"), results.toSet())
    }
}
