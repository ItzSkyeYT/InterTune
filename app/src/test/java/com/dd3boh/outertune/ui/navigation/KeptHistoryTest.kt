/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.ui.navigation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * #107: the app closed as it went to the background (TransactionTooLargeException, 527 KB of saved
 * state on a Galaxy S25 Ultra). 472 KB of it was the navigation's history: every page opened and not
 * left with Back stays in it, about 2.5 KB each (measured on the emulator, 9 Nov 2026: 40 searches in
 * a row took the saved state from 8 KB to 108 KB), so that phone had some 200 pages piled up. What is
 * written down now keeps the root, the start page and the newest pages only; while the app runs,
 * Back still goes through all of them.
 */
class KeptHistoryTest {

    @Test
    fun `a history no longer than the limit is written whole`() {
        assertEquals((0 until 10).toList(), KeptHistory.kept(10, 25))
        assertEquals((0 until 25).toList(), KeptHistory.kept(25, 25))
        assertEquals(listOf(0, 1), KeptHistory.kept(2, 25))
        assertEquals(emptyList<Int>(), KeptHistory.kept(0, 25))
    }

    @Test
    fun `a longer one keeps its root, its start page and the newest pages`() {
        assertEquals(listOf(0, 1) + (3 until 26).toList(), KeptHistory.kept(26, 25))
        assertEquals(listOf(0, 1) + (19 until 42).toList(), KeptHistory.kept(42, 25))
        val kept = KeptHistory.kept(210, 25)
        assertEquals(25, kept.size)
        assertEquals(listOf(0, 1), kept.take(2))
        assertEquals(209, kept.last())
        assertEquals("in order, none twice", kept.sorted().distinct(), kept)
    }

    @Test
    fun `the limit is never below the root, the start page and the page shown`() {
        assertEquals(listOf(0, 1, 9), KeptHistory.kept(10, 1))
    }

    @Test
    fun `a history parked by a tab change keeps its newest pages`() {
        assertEquals((0 until 10).toList(), KeptHistory.keptParked(10, 25))
        assertEquals((175 until 200).toList(), KeptHistory.keptParked(200, 25))
        assertEquals(emptyList<Int>(), KeptHistory.keptParked(0, 25))
    }

    private fun code(path: String) = File("src/main/java/com/dd3boh/outertune/$path").readText()

    @Test
    fun `the activity cuts it as its state is written, after everything else wrote theirs`() {
        val main = code("MainActivity.kt")
        val save = main.substringAfter("override fun onSaveInstanceState(outState: Bundle) {").substringBefore("\n    }\n")
        assertTrue(save.contains("KeptHistory.cut(outState)"))
        assertTrue("after super, which writes the navigation's state", save.indexOf("super.onSaveInstanceState(outState)") < save.indexOf("KeptHistory.cut(outState)"))
        val kept = code("ui/navigation/KeptHistory.kt")
        // the keys navigation-runtime 2.10.1 writes the histories under (NavControllerImpl)
        assertTrue(kept.contains("\"android-support-nav:controller:backStack\""))
        assertTrue(kept.contains("\"android-support-nav:controller:backStackStates:\""))
    }
}
