/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The service tells the play book about errors and the player's state where PlayBookTest assumes it
 * does.
 *
 * PlayBookTest drives the book the way the player drives the service, but a JVM test cannot run
 * MusicService, so nothing there notices if a call moves or goes. Without plays.playerError in
 * onPlayerError, or with it after skipOnError (which makes the next song current), a stream that
 * died is written as a skip again. Without plays.playbackState in onEvents, a play that recovered
 * from an error is still written as one. This reads the source, the way PlayOriginCoverageTest
 * does, with comments taken out so a call named in a comment does not count.
 */
class PlayBookCallSitesTest {

    private val service = stripComments(File("src/main/java/com/dd3boh/outertune/playback/MusicService.kt").readText())

    @Test
    fun `onPlayerError tells the book before it skips or stops`() {
        val body = bodyOf("override fun onPlayerError(")
        val error = body.indexOf("plays.playerError(")
        val skip = body.indexOf("skipOnError()")
        val stop = body.indexOf("stopOnError()")
        assertTrue("onPlayerError does not call plays.playerError", error >= 0)
        assertTrue("onPlayerError does not call skipOnError, so the order below proves nothing", skip >= 0)
        assertTrue("onPlayerError does not call stopOnError, so the order below proves nothing", stop >= 0)
        assertTrue("plays.playerError comes after skipOnError in onPlayerError", error < skip)
        assertTrue("plays.playerError comes after stopOnError in onPlayerError", error < stop)
    }

    @Test
    fun `onEvents passes the player's state to the book`() {
        assertTrue("onEvents does not call plays.playbackState", "plays.playbackState(" in bodyOf("override fun onEvents("))
    }

    /** The body of the one function [signature] starts, between its braces. */
    private fun bodyOf(signature: String): String {
        val at = service.indexOf(signature)
        assertTrue("MusicService has no $signature", at >= 0)
        assertEquals("MusicService has more than one $signature", -1, service.indexOf(signature, at + 1))
        val open = service.indexOf('{', at)
        var depth = 0
        for (i in open until service.length) {
            when (service[i]) {
                '{' -> depth++
                '}' -> if (--depth == 0) return service.substring(open + 1, i)
            }
        }
        throw AssertionError("The body of $signature never closes")
    }

    /** Block comments, and line comments that start a line or follow a space, so a url in a string stays. */
    private fun stripComments(text: String): String =
        text.replace(Regex("""/\*.*?\*/""", RegexOption.DOT_MATCHES_ALL), "")
            .replace(Regex("""(^|\s)//.*$""", RegexOption.MULTILINE), "$1")
}
