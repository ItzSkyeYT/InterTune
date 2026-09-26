/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.recognition

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread

/**
 * A run replaced by another while it is dealing with an answer, or waiting for one: see
 * [serialHolding].
 *
 * The engine cannot be built here, so this is its loop in miniature, on the dispatcher it runs on.
 * The run adds what it heard to whichever playlist is current once it has dealt with the answer, as
 * the engine's add() does, and the switch, Listen for this playlist instead, is made from another
 * thread, as the main thread makes it, holding the lock RecognitionEngine.stop and start hold.
 */
class SerialHoldingTest {

    private val scope = CoroutineScope(SupervisorJob())

    @Test
    fun `a switch waits for the answer in hand, and the new run gets none of the old one's`() {
        val lock = Any()
        val playlist = AtomicReference("Road trip")
        val added = CopyOnWriteArrayList<Pair<String, String>>()
        val dealing = CountDownLatch(1)
        val dealt = CountDownLatch(1)
        val nextAnswer = CompletableDeferred<String>()
        try {
            val run = scope.launch(serialHolding(lock)) {
                // Faint has just been named, and the run is working through the answer, all of it on
                // the loop's thread, from one suspension to the next.
                dealing.countDown()
                check(dealt.await(10, TimeUnit.SECONDS))
                added += "Faint" to playlist.get()
                // And the next window's answer, which comes back after the switch.
                added += nextAnswer.await() to playlist.get()
            }
            check(dealing.await(10, TimeUnit.SECONDS))
            val switch = thread {
                synchronized(lock) {
                    run.cancel()
                    playlist.set("Workout")
                }
            }
            // Held up until the answer in hand has been dealt with. It used to go straight through,
            // and the rest of the answer went into the new run's playlist.
            val until = System.currentTimeMillis() + 10_000
            while (switch.isAlive && switch.state != Thread.State.BLOCKED) {
                check(System.currentTimeMillis() < until)
                Thread.sleep(1)
            }
            dealt.countDown()
            switch.join(10_000)
            nextAnswer.complete("Numb")
            runBlocking { run.join() }

            // Faint in the playlist it was heard for, and nothing at all in the new run's.
            assertEquals(listOf("Faint" to "Road trip"), added.toList())
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `a switch does not wait for a request that is out, and its answer is dropped`() {
        val lock = Any()
        val playlist = AtomicReference("Road trip")
        val added = CopyOnWriteArrayList<Pair<String, String>>()
        val asking = CountDownLatch(1)
        val answered = CountDownLatch(1)
        try {
            val run = scope.launch(serialHolding(lock)) {
                // As ShazamClient asks: a blocking request on the IO threads, which cancelling
                // cannot interrupt. The loop is suspended meanwhile and holds nothing.
                val answer = withContext(Dispatchers.IO) {
                    asking.countDown()
                    check(answered.await(10, TimeUnit.SECONDS))
                    "Faint"
                }
                added += answer to playlist.get()
            }
            check(asking.await(10, TimeUnit.SECONDS))
            val switch = thread {
                synchronized(lock) {
                    run.cancel()
                    playlist.set("Workout")
                }
            }
            // Straight through: a request can take the whole of its timeouts, 35 s, to come back.
            switch.join(10_000)
            assertFalse(switch.isAlive)
            answered.countDown()
            runBlocking { run.join() }

            // Come back after the switch, the answer is dropped where the run would take it.
            assertEquals(emptyList<Pair<String, String>>(), added.toList())
        } finally {
            scope.cancel()
        }
    }
}
