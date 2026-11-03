/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

/**
 * Android closes the app when a toast is made on a thread with no looper (#74, 3 Nov 2026: the
 * Developer settings' artist linking showed its "complete" toast from the scanner's worker
 * thread). This reads the source, as the flag tests do: a toast whose nearest enclosing coroutine
 * runs on IO, Default or the scanner's dispatcher, with no switch back to Main in between, fails.
 */
class ToastOnMainThreadTest {
    private val sources = File("src/main/java")
    private val offMain = Regex("""\b(launch|withContext|async)\((Dispatchers\.IO|Dispatchers\.Default|lmScannerCoroutine)""")
    private val onMain = Regex("""\bwithContext\((Dispatchers\.)?Main|\blaunch\(Dispatchers\.Main""")

    /** Each toast made off the main thread, as file:line. */
    private fun offMainToasts(): List<String> = sources.walkTopDown().filter { it.extension == "kt" }.flatMap { file ->
        val lines = file.readLines()
        lines.indices.filter { "Toast.makeText" in lines[it] }.mapNotNull { at ->
            var depth = 0
            for (j in at - 1 downTo maxOf(0, at - 40)) {
                val line = lines[j]
                depth += line.count { it == '}' } - line.count { it == '{' }
                if (depth < 0) {
                    // The block this toast sits in opens here: which thread is it?
                    if (onMain.containsMatchIn(line)) return@mapNotNull null
                    if (offMain.containsMatchIn(line)) return@mapNotNull "${file.relativeTo(sources).path}:${at + 1}"
                    depth = 0
                }
            }
            null
        }
    }.toList()

    @Test
    fun `no toast is made off the main thread`() {
        assertEquals(emptyList<String>(), offMainToasts())
    }
}
