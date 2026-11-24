/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.recognition

import android.view.WindowManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** The What's playing? tile's question, tapped once Quick Settings had taken its window back, no longer closes the app (#257). */
class TileDialogTest {

    private val sources = File("src/main/java/com/dd3boh/outertune")

    @Test
    fun `a question Quick Settings no longer lends a window for is dropped, not thrown`() {
        // What Dialog.show() throws there: "Unable to add window -- token ... is not valid; is your activity running?"
        assertFalse(unlessPanelGone { throw WindowManager.BadTokenException() })
    }

    @Test
    fun `a question that shows says so, and is shown once`() {
        var shown = 0
        assertTrue(unlessPanelGone { shown++ })
        assertEquals(1, shown)
    }

    @Test
    fun `any other failure still comes out`() {
        assertThrows(IllegalStateException::class.java) {
            unlessPanelGone { throw IllegalStateException("not the panel's window") }
        }
    }

    @Test
    fun `every dialog a tile shows over the panel goes through the guard`() {
        val call = Regex("""(?<![\w.])showDialog\(""")
        val calls = sources.walkTopDown().filter { it.extension == "kt" }
            .flatMap { file -> file.readLines().filter { call.containsMatchIn(it) }.map { file.name to it.trim() } }
            .toList()
        assertTrue("no tile shows a question any more", calls.isNotEmpty())
        for ((file, line) in calls) {
            assertTrue("$file shows a dialog over the panel unguarded: $line", "unlessPanelGone { showDialog(" in line)
        }
    }
}
