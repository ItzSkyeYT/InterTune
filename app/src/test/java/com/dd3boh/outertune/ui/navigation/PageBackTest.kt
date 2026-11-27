/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.ui.navigation

import androidx.activity.BackEventCompat
import androidx.activity.OnBackPressedCallback
import androidx.activity.OnBackPressedDispatcher
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The nav host's back handler behind PageBack (GitHub #210, "Cannot transition entry that is not in
 * the back stack"). The nav host is played here the way navigation-compose 2.9.8 is made: it keeps a
 * copy of the pages that catches up in the queue after each change, its work for a Back goes in that
 * same queue (PredictiveBackHandler's job), and the work asks for the copy's top page, which throws
 * when that page is gone (ComposeNavigator.prepareForTransition). The dispatchers are the real ones.
 */
class PageBackTest {

    /** The queue the composition's coroutines run from, one after the other. */
    private val queue = ArrayDeque<() -> Unit>()

    private fun runQueue() {
        while (queue.isNotEmpty()) queue.removeFirst()()
    }

    /** The pages, and the nav host's copy of them, which catches up in the queue after a change. */
    private var pages = listOf("Home", "Album", "Artist")
    private var copy = pages

    private fun change(to: List<String>) {
        pages = to
        queue.addLast { copy = to }
    }

    private var plainBacks = 0
    private val back = PageBack(
        pages = { pages },
        later = { queue.addLast(it) },
        plainBack = { plainBacks++; change(pages.dropLast(1)) },
    )

    /** What the nav host's own handler was given. */
    private val given = mutableListOf<String>()
    private var workRan = 0

    /** PredictiveBackHandler's callback as the nav host has it, with its work for each Back. */
    private inner class NavHostHandler : OnBackPressedCallback(true) {
        private inner class Work(var predictive: Boolean) {
            var calledOff = false
        }

        private var work: Work? = null

        private fun newWork(predictive: Boolean) = Work(predictive).also { w ->
            queue.addLast {
                if (!w.calledOff) {
                    workRan++
                    check(copy.last() in pages) { "Cannot transition entry that is not in the back stack" }
                }
            }
        }

        override fun handleOnBackStarted(backEvent: BackEventCompat) {
            given += "started"
            work?.calledOff = true
            work = newWork(predictive = true)
        }

        override fun handleOnBackProgressed(backEvent: BackEventCompat) {
            given += "progress"
        }

        override fun handleOnBackPressed() {
            given += "pressed"
            work?.let { if (!it.predictive) { it.calledOff = true; work = null } }
            if (work == null) work = newWork(predictive = false)
            work?.predictive = false
        }

        override fun handleOnBackCancelled() {
            given += "cancelled"
            work?.calledOff = true
            work?.predictive = false
        }
    }

    private val navHost = NavHostHandler()

    /** The app's own dispatcher, the activity's: with nothing on, Back leaves the app. */
    private var leftTheApp = 0
    private val app = OnBackPressedDispatcher({ leftTheApp++ }, null)

    private fun swipe(progress: Float) = BackEventCompat(0f, 1200f, progress, BackEventCompat.EDGE_LEFT)

    private fun withPageBack() {
        back.inner.addCallback(navHost)
        app.addCallback(back.door)
    }

    @Test
    fun `without PageBack a page that closes as a Back begins ends the app`() {
        // The model holds: this is #210 as it was.
        app.addCallback(navHost)
        app.dispatchOnBackStarted(swipe(0f))
        change(listOf("Home", "Album"))
        val crash = assertThrows(IllegalStateException::class.java) { runQueue() }
        assertEquals("Cannot transition entry that is not in the back stack", crash.message)
    }

    @Test
    fun `with PageBack the nav host is told to drop that gesture and the Back is a plain one`() {
        withPageBack()
        app.dispatchOnBackStarted(swipe(0f))
        change(listOf("Home", "Album"))
        runQueue()
        assertEquals(0, workRan)
        assertEquals(listOf("started", "cancelled"), given)
        app.dispatchOnBackProgressed(swipe(0.4f))
        app.onBackPressed()
        runQueue()
        assertEquals("the rest of the gesture stays ours", listOf("started", "cancelled"), given)
        assertEquals(1, plainBacks)
        assertEquals(listOf("Home"), pages)
    }

    @Test
    fun `a gesture with nothing changing reaches the nav host whole`() {
        withPageBack()
        app.dispatchOnBackStarted(swipe(0f))
        runQueue()
        app.dispatchOnBackProgressed(swipe(0.5f))
        app.onBackPressed()
        runQueue()
        assertEquals(listOf("started", "progress", "pressed"), given)
        assertEquals(1, workRan)
        assertEquals(0, plainBacks)
    }

    @Test
    fun `a quick swipe let go before the look is a plain Back too`() {
        withPageBack()
        app.dispatchOnBackStarted(swipe(0f))
        app.dispatchOnBackProgressed(swipe(0.9f))
        app.onBackPressed()
        change(listOf("Home", "Album"))
        runQueue()
        assertEquals(0, workRan)
        assertEquals(listOf("started", "progress", "pressed", "cancelled"), given)
        assertEquals(1, plainBacks)
    }

    @Test
    fun `a Back from a key or the buttons is looked at as well`() {
        withPageBack()
        app.onBackPressed()
        runQueue()
        assertEquals(listOf("pressed"), given)
        assertEquals(1, workRan)

        app.onBackPressed()
        change(listOf("Home", "Album"))
        runQueue()
        assertEquals(listOf("pressed", "pressed", "cancelled"), given)
        assertEquals(1, workRan)
        assertEquals(1, plainBacks)
    }

    @Test
    fun `a gesture let go at the edge is the nav host's to put back`() {
        withPageBack()
        app.dispatchOnBackStarted(swipe(0f))
        runQueue()
        app.dispatchOnBackProgressed(swipe(0.3f))
        app.dispatchOnBackCancelled()
        runQueue()
        assertEquals(listOf("started", "progress", "cancelled"), given)
        assertEquals(0, plainBacks)
    }

    @Test
    fun `a page that changes once the nav host has its pages is left to the nav host`() {
        withPageBack()
        app.dispatchOnBackStarted(swipe(0f))
        runQueue()
        change(listOf("Home", "Album", "Artist", "Playlist"))
        app.onBackPressed()
        runQueue()
        assertEquals(listOf("started", "pressed"), given)
        assertEquals(0, plainBacks)
    }

    @Test
    fun `with Back animations off a page's Back is plain and never the nav host's`() {
        withPageBack()
        back.animations = false
        app.dispatchOnBackStarted(swipe(0f))
        app.dispatchOnBackProgressed(swipe(0.6f))
        runQueue()
        app.onBackPressed()
        app.onBackPressed()
        runQueue()
        assertEquals(emptyList<String>(), given)
        assertEquals(2, plainBacks)
        assertEquals(listOf("Home"), pages)
    }

    @Test
    fun `the door is on exactly when the nav host's handler is`() {
        withPageBack()
        assertTrue(back.door.isEnabled)
        navHost.isEnabled = false
        assertFalse(back.door.isEnabled)
        app.onBackPressed()
        assertEquals("Back went on to leave the app, as before", 1, leftTheApp)
        navHost.isEnabled = true
        assertTrue(back.door.isEnabled)
    }

    @Test
    fun `a handler put in after the nav host's still comes first`() {
        // The pages' own (a selection's, a search's) and the player's go in after the nav host's
        // handler, and the door stands where that one stood.
        withPageBack()
        var selection = 0
        val selectionBack = object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() { selection++ }
        }
        app.addCallback(selectionBack)
        app.dispatchOnBackStarted(swipe(0f))
        app.onBackPressed()
        runQueue()
        assertEquals(1, selection)
        assertEquals(emptyList<String>(), given)
        selectionBack.isEnabled = false
        app.onBackPressed()
        runQueue()
        assertEquals(listOf("pressed"), given)
    }
}
