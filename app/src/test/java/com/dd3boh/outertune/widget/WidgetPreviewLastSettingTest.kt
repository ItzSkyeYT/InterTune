/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.widget

import java.util.concurrent.CountDownLatch
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The preview in the widget's settings ends up showing the last setting chosen.
 *
 * Every change of a setting cancels the drawing in flight and asks for a new one. The drawing was
 * wrapped in a runCatching, which catches the cancellation as it does a failure, so the cancelled
 * one went on to put nothing in the preview: it blinked out at every change made while a drawing
 * was in flight, and when the cancelled drawing came back after the newer one (the first of a
 * session reads the snapshot and decodes its covers off the main thread, and that is not cut
 * short) the preview stayed empty until the next change.
 *
 * [drawnIfStillWanted] is the code under test. The screen is a stand-in that does what
 * LaunchedEffect does with it at each change: cancel the drawing before, without waiting for it,
 * and start the next. The last test reads the source to see that the screen draws through it.
 */
class WidgetPreviewLastSettingTest {

    /** Stands where LiveWidget does. */
    private class Screen(private val scope: CoroutineScope) {
        var views: String? = null
        val drawings = mutableListOf<Job>()

        fun choose(draw: suspend () -> String) {
            drawings.lastOrNull()?.cancel()
            drawings += scope.launch { views = drawnIfStillWanted(draw) }
        }
    }

    @Test
    fun `a drawing cancelled by the next change does not empty the preview`() = runBlocking {
        val screen = Screen(this)
        screen.choose { "opacity 40" }
        yield()
        assertEquals("opacity 40", screen.views)

        val never = CompletableDeferred<String>()
        val next = CompletableDeferred<String>()
        screen.choose { never.await() }
        yield()
        screen.choose { next.await() }
        yield()
        assertEquals("what was drawn stays until the newer drawing is there", "opacity 40", screen.views)

        next.complete("opacity 60")
        screen.drawings.joinAll()
        assertEquals("opacity 60", screen.views)
    }

    @Test
    fun `a cancelled drawing that is not cut short and comes back last leaves the newer one`() = runBlocking {
        val screen = Screen(this)
        val started = CountDownLatch(1)
        val letGo = CountDownLatch(1)
        // Off the main thread and blocked there, as the first drawing of a session is while it
        // decodes its covers: cancelling does not end it, it is only told when it comes back.
        screen.choose {
            withContext(Dispatchers.IO) {
                started.countDown()
                letGo.await()
                "list of six"
            }
        }
        yield()
        started.await()
        screen.choose { "list of three" }
        yield()
        assertEquals("list of three", screen.views)

        letGo.countDown()
        screen.drawings.joinAll()
        assertEquals("list of three", screen.views)
    }

    @Test
    fun `an older drawing that ends well after it was cancelled is not shown either`() = runBlocking {
        val screen = Screen(this)
        val late = CompletableDeferred<String>()
        // A drawing that pays no heed to its cancellation at all and hands its views back.
        screen.choose { withContext(NonCancellable) { late.await() } }
        yield()
        screen.choose { "dark" }
        yield()
        assertEquals("dark", screen.views)

        late.complete("light")
        screen.drawings.joinAll()
        assertEquals("the last setting chosen is the one shown", "dark", screen.views)
    }

    @Test
    fun `whatever order three drawings end in, the third is the one shown`() = runBlocking {
        for (order in listOf(listOf(0, 1, 2), listOf(0, 2, 1), listOf(1, 0, 2), listOf(1, 2, 0), listOf(2, 0, 1), listOf(2, 1, 0))) {
            val screen = Screen(this)
            val ends = List(3) { CompletableDeferred<String>() }
            for (end in ends) {
                screen.choose { withContext(NonCancellable) { end.await() } }
                yield()
            }
            for (i in order) {
                ends[i].complete("setting $i")
                yield()
            }
            screen.drawings.joinAll()
            assertEquals("ended in the order $order", "setting 2", screen.views)
        }
    }

    @Test
    fun `a drawing that fails empties the preview, since its setting cannot be shown`() = runBlocking {
        val screen = Screen(this)
        screen.choose { "rounded" }
        yield()
        screen.choose { error("Glance could not compose") }
        screen.drawings.joinAll()
        assertNull("an older setting left on screen would pass for the one chosen", screen.views)
    }

    @Test
    fun `the settings screen draws its preview through it`() {
        val screen = File("src/main/java/com/dd3boh/outertune/widget/WidgetConfigActivity.kt").readText()
        val live = screen.substringAfter("private fun LiveWidget(").substringBefore("private class TouchlessFrame(")
        assertTrue("views = drawnIfStillWanted { renderWidget(" in live)
        assertFalse("a runCatching round the drawing catches its cancellation", "runCatching { renderWidget(" in live)
    }
}
