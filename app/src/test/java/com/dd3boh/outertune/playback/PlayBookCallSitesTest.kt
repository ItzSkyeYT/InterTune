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
 * died is written as a skip again, and with it after a return, an error met while offline (which
 * waits for the network and returns) never marks the play. Without plays.playbackState in onEvents,
 * a play that recovered from an error is still written as one. Stats with no play time that take
 * the oldest play of their song instead of plays.takeUnplayed take a new queue's play of a song
 * that was only loaded ahead. This reads the source, the way PlayOriginCoverageTest does, with
 * comments taken out so a call named in a comment does not count.
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
    fun `onPlayerError tells the book before it can return, waiting for the network included`() {
        val body = bodyOf("override fun onPlayerError(")
        val error = body.indexOf("plays.playerError(")
        val firstReturn = Regex("""\breturn\b""").find(body)?.range?.first ?: -1
        val wait = body.indexOf("waitOnNetworkError()")
        assertTrue("onPlayerError does not call plays.playerError", error >= 0)
        assertTrue("onPlayerError no longer waits for the network, so the order below proves nothing", wait >= 0)
        assertTrue("onPlayerError has no return, so the order below proves nothing", firstReturn >= 0)
        assertTrue("plays.playerError comes after waitOnNetworkError in onPlayerError", error < wait)
        assertTrue("plays.playerError comes after a return in onPlayerError", error < firstReturn)
    }

    @Test
    fun `stats with no play time close only a play that never sounded`() {
        val stats = bodyOf("override fun onPlaybackStatsReady(")
        val branch = blockAfter(stats, "if (playbackStats.totalPlayTimeMs <= 0L)")
        assertTrue("the branch for stats with no play time does not call plays.takeUnplayed", "plays.takeUnplayed(" in branch)
        assertTrue("the branch for stats with no play time calls plays.takeOldest", "takeOldest(" !in branch)
    }

    @Test
    fun `onEvents passes the player's state to the book`() {
        assertTrue("onEvents does not call plays.playbackState", "plays.playbackState(" in bodyOf("override fun onEvents("))
    }

    /** The body of the one function [signature] starts, between its braces. */
    private fun bodyOf(signature: String): String = blockAfter(service, signature)

    /** The block that the one [head] in [text] opens, between its braces. */
    private fun blockAfter(text: String, head: String): String {
        val at = text.indexOf(head)
        assertTrue("MusicService has no $head", at >= 0)
        assertEquals("MusicService has more than one $head", -1, text.indexOf(head, at + 1))
        val open = text.indexOf('{', at)
        var depth = 0
        for (i in open until text.length) {
            when (text[i]) {
                '{' -> depth++
                '}' -> if (--depth == 0) return text.substring(open + 1, i)
            }
        }
        throw AssertionError("The block of $head never closes")
    }

    /** Block comments, and line comments that start a line or follow a space, so a url in a string stays. */
    private fun stripComments(text: String): String =
        text.replace(Regex("""/\*.*?\*/""", RegexOption.DOT_MATCHES_ALL), "")
            .replace(Regex("""(^|\s)//.*$""", RegexOption.MULTILINE), "$1")
}
