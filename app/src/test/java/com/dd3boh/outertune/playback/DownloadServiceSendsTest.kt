/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * A message to the download service that Android refuses is a line in the log, not a crash.
 *
 * Issue #65: the automatic scan resumed the downloads after the person had left for the update
 * installer, startService was refused with a BackgroundServiceStartNotAllowedException, and the
 * worker thread it was thrown on had nothing to catch it.
 */
class DownloadServiceSendsTest {

    private val lines = mutableListOf<String>()
    private val sends = DownloadServiceSends { lines += it }

    /** What startService throws for an app in the background (the subclass is an IllegalStateException). */
    private fun refused(): Nothing =
        throw IllegalStateException("Not allowed to start service Intent { act=androidx.media3.exoplayer.downloadService.action.RESUME_DOWNLOADS }: app is in background")

    @Test
    fun `the exception Android throws is the one that is caught`() {
        val android = Class.forName("android.app.BackgroundServiceStartNotAllowedException")
        assertTrue(IllegalStateException::class.java.isAssignableFrom(android))
    }

    @Test
    fun `a message that goes through is sent once and logs nothing`() {
        var sent = 0
        assertTrue(sends.attempt("add") { sent++ })
        assertEquals(1, sent)
        assertEquals(emptyList<String>(), lines)
    }

    @Test
    fun `a refusal is one line in the log and a false`() {
        assertFalse(sends.attempt("requirements") { refused() })
        assertEquals(1, lines.size)
        assertTrue(lines[0], "requirements" in lines[0])
        assertTrue("one line", '\n' !in lines[0])
    }

    @Test
    fun `only the refusal is caught`() {
        try {
            sends.attempt("add") { throw IllegalArgumentException("a bug") }
            fail("a bug in a send must still show")
        } catch (expected: IllegalArgumentException) {
            assertEquals("a bug", expected.message)
        }
        assertEquals(emptyList<String>(), lines)
        try {
            sends.resume { throw NullPointerException() }
            fail("a bug in the resume must still show")
        } catch (expected: NullPointerException) {
            // thrown on
        }
        assertFalse("a bug is not a refusal", sends.resumeOwed)
    }

    @Test
    fun `a refused resume is owed until one goes through`() {
        assertFalse("nothing is owed at the start", sends.resumeOwed)

        sends.resume { refused() }
        assertTrue(sends.resumeOwed)

        sends.resume { refused() }
        assertTrue("still owed after a second refusal", sends.resumeOwed)

        var sent = 0
        sends.resume { sent++ }
        assertEquals(1, sent)
        assertFalse("sent, so nothing is owed", sends.resumeOwed)
    }

    @Test
    fun `the app in front sends the owed resume once`() {
        sends.resume { refused() }
        var sent = 0
        // MainActivity.onResume, every time the app is in front.
        repeat(3) { if (sends.resumeOwed) sends.resume { sent++ } }
        assertEquals("once, not at every resume of the activity", 1, sent)
        assertFalse(sends.resumeOwed)
    }

    @Test
    fun `a resume that was owed says so once when it goes through`() {
        sends.resume { refused() }
        assertEquals("the refusal", 1, lines.size)
        sends.resume { }
        assertEquals("and the resume that made up for it", 2, lines.size)
        sends.resume { }
        assertEquals("the next ones are as quiet as ever", 2, lines.size)
    }

    @Test
    fun `a resume that was never refused is sent as it always was`() {
        var sent = 0
        sends.resume { sent++ }
        assertEquals(1, sent)
        assertFalse(sends.resumeOwed)
        assertEquals("quiet when nothing was refused", emptyList<String>(), lines)
    }

    @Test
    fun `another message that is refused does not owe a resume`() {
        sends.attempt("remove") { refused() }
        sends.attempt("add") { refused() }
        assertFalse(sends.resumeOwed)
        assertEquals(2, lines.size)
    }
}
