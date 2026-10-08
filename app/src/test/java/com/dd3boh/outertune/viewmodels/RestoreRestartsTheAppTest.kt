/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.viewmodels

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * After a restore the app has to come back by itself.
 *
 * It restarts by starting its activity and ending its process. MainActivity is singleTask, so a
 * plain start is handed to the instance already on top and makes nothing new: when the process
 * then ends the app is gone, and somebody who has just restored a backup is looking at their
 * launcher. Seen on a Pixel 5 with Android 14. A start that clears the task makes a new activity,
 * and the system brings that up in a new process once this one has ended.
 *
 * Read from the source, because what goes wrong takes a device to show.
 */
class RestoreRestartsTheAppTest {

    private val source = File("src/main/java/com/dd3boh/outertune/viewmodels/BackupRestoreViewModel.kt").readText()

    @Test
    fun `the app is started in a task of its own before the process ends`() {
        val end = source.indexOf("exitProcess(0)")
        assertTrue("the restore no longer ends the process: look at how it restarts instead", end > 0)
        val before = source.substring(0, end).takeLast(900)
        assertTrue(before, "Intent.makeRestartActivityTask(" in before)
        assertFalse("a plain start is handed to the activity already there", "Intent(context, MainActivity::class.java)" in before)
    }
}
