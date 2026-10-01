/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.ui.screens.settings.fragments

import com.dd3boh.outertune.utils.AutoBackupPolicy
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Keep slider's count, one at a time: a count for where the thumb was let go before never
 * asks once the slider is touched again, and nothing asks while a finger is on it.
 *
 * Everything runs on runBlocking's one thread, as the real thing runs on the main one, so the
 * order of the steps below is the order they happen in.
 */
class KeepCountTest {

    private val app = "InterTune"

    /** Fourteen of ours, a second apart, so Keep 5 deletes nine and Keep 3 eleven. */
    private val folder = (0 until 14).map { "InterTune_25_202609301200%02d.backup".format(it) }

    private val saved = mutableListOf<Int>()
    private val asked = mutableListOf<Int>()

    /** Let go at [new] with [old] stored, the folder answering when [listing] completes. */
    private fun KeepCount.letGo(old: Int, new: Int, listing: CompletableDeferred<List<String>?>) =
        released(
            old = old,
            new = new,
            count = { keep -> listing.await()?.let { AutoBackupPolicy.toDelete(app, it, keep) } },
            save = { saved += it },
            ask = { keep, _ -> asked += keep },
        )

    private fun CoroutineScope.keepCount() = KeepCount(this)

    /** Lets everything that can run, run: counts, and whatever they resume into. */
    private suspend fun settle() = repeat(20) { yield() }

    @Test
    fun `a count that finishes during a newer drag asks nothing, and the newer one asks its own`() = runBlocking {
        val keepCount = keepCount()
        val first = CompletableDeferred<List<String>?>()
        val older = keepCount.letGo(14, 5, first)!!
        yield() // Counting, waiting on the folder.

        keepCount.moved() // The thumb is dragged again before the folder has answered.
        first.complete(folder)
        older.join()
        assertTrue(older.isCancelled)
        assertEquals("nothing asked during the newer drag", emptyList<Int>(), asked)

        val second = CompletableDeferred<List<String>?>()
        val newer = keepCount.letGo(14, 3, second)!!
        second.complete(folder)
        newer.join()
        assertEquals(listOf(3), asked)
        assertEquals(emptyList<Int>(), saved)
    }

    @Test
    fun `no dialog for the old value sits behind a save from the newer one`() = runBlocking {
        // Before, the count for 3 opened its dialog during the next drag, that drag let go at 15
        // saved 15 behind it, and Cancel then put the slider on 15, a value nobody kept.
        val keepCount = keepCount()
        val listing = CompletableDeferred<List<String>?>()
        val older = keepCount.letGo(14, 3, listing)!!
        yield()

        keepCount.moved()
        listing.complete(folder)
        older.join()

        val raise = keepCount.letGo(14, 15, CompletableDeferred(folder))!!
        raise.join()
        assertEquals(emptyList<Int>(), asked)
        assertEquals(listOf(15), saved)
    }

    @Test
    fun `a touch before the count has even started stops it too`() = runBlocking {
        val keepCount = keepCount()
        val listing = CompletableDeferred<List<String>?>()
        val older = keepCount.letGo(14, 5, listing)!!
        keepCount.moved()
        listing.complete(folder)
        older.join()
        assertEquals(emptyList<Int>(), asked)
        assertEquals(emptyList<Int>(), saved)
    }

    // A finger on the slider.

    @Test
    fun `a press alone stops the count for the last release`() = runBlocking {
        // Material3 calls onValueChange only for a whole step, so a press that does not move the
        // thumb never reached moved(), and the count went on to open its dialog under the finger.
        val keepCount = keepCount()
        val listing = CompletableDeferred<List<String>?>()
        val older = keepCount.letGo(14, 5, listing)!!
        yield() // Counting, waiting on the folder.

        keepCount.pressed()
        listing.complete(folder)
        settle()
        assertTrue(older.isCancelled)
        assertEquals(emptyList<Int>(), asked)
        assertEquals(emptyList<Int>(), saved)
    }

    @Test
    fun `nothing is asked while a finger is on the slider, only once it lifts`() = runBlocking {
        // A release that arrives after the next press has begun, as a drag's can: Material3 says
        // a drag has ended a moment after the finger is up.
        val keepCount = keepCount()
        keepCount.pressed()
        val count = keepCount.letGo(14, 5, CompletableDeferred(folder))!!
        settle()
        assertTrue("the count waits for the lift", count.isActive)
        assertEquals(emptyList<Int>(), asked)

        keepCount.lifted()
        settle()
        assertEquals(listOf(5), asked)
    }

    @Test
    fun `a press that scrolls the page instead asks about the last release once it lifts`() = runBlocking {
        // Nothing moved the thumb and the slider never let go, so it still shows 3 with Keep at
        // 14. Lifting has to count 3 again, or nothing would ever be decided for it.
        val keepCount = keepCount()
        val listing = CompletableDeferred<List<String>?>()
        keepCount.letGo(14, 3, listing)!!
        yield()

        keepCount.pressed()
        listing.complete(folder)
        settle()
        assertEquals(emptyList<Int>(), asked)

        keepCount.lifted()
        settle()
        assertEquals(listOf(3), asked)
        assertEquals(emptyList<Int>(), saved)
    }

    @Test
    fun `a press that moves the thumb leaves the old release to the new one`() = runBlocking {
        val keepCount = keepCount()
        val listing = CompletableDeferred<List<String>?>()
        keepCount.letGo(14, 3, listing)!!
        yield()

        keepCount.pressed()
        keepCount.moved() // Dragged on to 7.
        keepCount.lifted() // The drag's own release comes a moment after this.
        listing.complete(folder)
        settle()
        assertEquals("nothing for 3", emptyList<Int>(), asked)
        assertEquals(emptyList<Int>(), saved)

        keepCount.letGo(14, 7, CompletableDeferred(folder))!!.join()
        assertEquals(listOf(7), asked)
    }

    @Test
    fun `a tap where the thumb already is asks once`() = runBlocking {
        // The tap lets go at the same value without moving the thumb: its own release counts 3,
        // and lifting must not count it a second time.
        val keepCount = keepCount()
        keepCount.letGo(14, 3, CompletableDeferred())!!
        yield()

        keepCount.pressed()
        keepCount.letGo(14, 3, CompletableDeferred(folder))!!
        keepCount.lifted()
        settle()
        assertEquals(listOf(3), asked)
    }

    @Test
    fun `a question already asked is not asked again by the next touch`() = runBlocking {
        // Once a count has saved or asked, its release is answered. A later press and lift that
        // moves nothing must not count it again, or every touch would reopen the dialog.
        val keepCount = keepCount()
        keepCount.letGo(14, 3, CompletableDeferred(folder))!!.join()
        assertEquals(listOf(3), asked)

        keepCount.pressed()
        keepCount.lifted()
        settle()
        assertEquals(listOf(3), asked)
        assertEquals(emptyList<Int>(), saved)
    }

    @Test
    fun `raising saves without looking at the folder, and letting go where it was does nothing`() = runBlocking {
        val keepCount = keepCount()
        val raise = keepCount.released(
            old = 5,
            new = 8,
            count = { throw AssertionError("raising Keep listed the folder") },
            save = { saved += it },
            ask = { keep, _ -> asked += keep },
        )!!
        raise.join()
        assertEquals(listOf(8), saved)
        assertNull(keepCount.letGo(8, 8, CompletableDeferred(folder)))
        assertEquals(emptyList<Int>(), asked)
    }
}
