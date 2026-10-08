/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Which stream address a song is handed again, and when it is asked for afresh.
 *
 * The first half is the rule by itself. The second reads MusicService, the way
 * PlayBookCallSitesTest does, because a JVM test cannot run the service and the rule is worth
 * nothing if the player's error never reaches it, or reaches it after the wait for the network
 * has returned.
 */
class StreamAddressesTest {
    private val now = 1_791_480_000_000L
    private val sixHours = 6 * 60 * 60 * 1000L

    /** Synthetic, in the shape of the real ones. */
    private val unchecked = "https://rr1---sn-test.example/videoplayback?itag=139"
    private val checked = "https://rr2---sn-test.example/videoplayback?itag=251"

    @Test
    fun `the rest of a song comes from the address its start was given`() {
        val addresses = StreamAddresses()
        assertNull("nothing is known of a song that was never asked for", addresses.of("song", now))
        addresses.keep("song", checked, now + sixHours)

        // The player opens a song at its start, again for the rest, and again for every seek.
        assertEquals(checked, addresses.of("song", now + 2_000))
        assertEquals(checked, addresses.of("song", now + 180_000))
    }

    @Test
    fun `an address past the time YouTube gave it is asked for again`() {
        val addresses = StreamAddresses()
        addresses.keep("song", checked, now + sixHours)

        assertEquals(checked, addresses.of("song", now + sixHours - 1))
        assertNull(addresses.of("song", now + sixHours))
        assertNull(addresses.of("song", now + sixHours + 1))
    }

    @Test
    fun `the player's own retries are handed the address it is trying`() {
        // With every connection refused the walk ends in an address nothing could check, and the
        // player tries it four times in four seconds before it gives up. Those are not four walks
        // of the chain: each would be three more requests to YouTube.
        val addresses = StreamAddresses()
        addresses.keep("song", unchecked, now + sixHours)

        for (retry in listOf(0L, 1_000L, 3_000L)) assertEquals(unchecked, addresses.of("song", now + retry))
    }

    @Test
    fun `once the player has failed on a song its address is asked for afresh`() {
        // The run of 8 Oct 2026: the address taken unchecked was IOS's, which answers 403, and
        // Play was handed it again every time until the listener skipped.
        val addresses = StreamAddresses()
        addresses.keep("song", unchecked, now + sixHours)

        assertTrue("an address was forgotten, and it says so", addresses.playerFailedOn("song"))
        assertNull("Play after the failure asks afresh", addresses.of("song", now + 12_000))
    }

    @Test
    fun `an address that passed its check goes the same way`() {
        // It can stop working too, when the phone leaves the network it was issued on.
        val addresses = StreamAddresses()
        addresses.keep("song", checked, now + sixHours)

        assertTrue(addresses.playerFailedOn("song"))
        assertNull(addresses.of("song", now + 12_000))
    }

    @Test
    fun `the address asked for after a failure is kept like any other`() {
        val addresses = StreamAddresses()
        addresses.keep("song", unchecked, now + sixHours)
        addresses.playerFailedOn("song")
        addresses.keep("song", checked, now + 12_000 + sixHours)

        assertEquals(checked, addresses.of("song", now + 14_000))
    }

    @Test
    fun `a failure on one song leaves the others as they were`() {
        // The song loaded ahead has an address too, and nothing has been learned of it.
        val addresses = StreamAddresses()
        addresses.keep("failed", unchecked, now + sixHours)
        addresses.keep("next", checked, now + sixHours)

        addresses.playerFailedOn("failed")
        assertEquals(checked, addresses.of("next", now + 12_000))
    }

    @Test
    fun `a failure that names no song, or one with no address, forgets nothing`() {
        val addresses = StreamAddresses()
        addresses.keep("song", checked, now + sixHours)

        assertFalse(addresses.playerFailedOn(null))
        // A local file, a download, or a song whose address was never found.
        assertFalse(addresses.playerFailedOn("another"))
        assertEquals(checked, addresses.of("song", now + 12_000))
        assertTrue(addresses.playerFailedOn("song"))
        assertFalse("there is nothing to forget twice", addresses.playerFailedOn("song"))
    }

    // ---- MusicService

    private val service = stripComments(File("src/main/java/com/dd3boh/outertune/playback/MusicService.kt").readText())

    @Test
    fun `the resolver hands out and keeps its addresses here, and nowhere else`() {
        val resolver = bodyOf("private fun createDataSourceFactory(")
        assertTrue("the resolver does not ask streamAddresses for the address", "streamAddresses.of(" in resolver)
        assertTrue("the resolver does not keep the address it resolved", "streamAddresses.keep(" in resolver)
        assertFalse("the resolver keeps addresses in a map of its own again, which no failure reaches", "HashMap<" in resolver)
    }

    @Test
    fun `onPlayerError forgets the address before it skips, stops or waits for the network`() {
        val body = bodyOf("override fun onPlayerError(")
        val forget = body.indexOf("streamAddresses.playerFailedOn(")
        val firstReturn = Regex("""\breturn\b""").find(body)?.range?.first ?: -1
        val wait = body.indexOf("waitOnNetworkError()")
        val skip = body.indexOf("skipOnError()")
        val stop = body.indexOf("stopOnError()")
        assertTrue("onPlayerError does not call streamAddresses.playerFailedOn", forget >= 0)
        for ((name, at) in listOf("return" to firstReturn, "waitOnNetworkError" to wait, "skipOnError" to skip, "stopOnError" to stop)) {
            assertTrue("onPlayerError has no $name, so the order below proves nothing", at >= 0)
            // Before the wait, whose retries prepare the player again, and before the skip, which
            // makes another song the current one.
            assertTrue("streamAddresses.playerFailedOn comes after $name in onPlayerError", forget < at)
        }
    }

    @Test
    fun `the address forgotten is the one of the song the error names`() {
        val body = bodyOf("override fun onPlayerError(")
        val call = body.substring(body.indexOf("streamAddresses.playerFailedOn(").coerceAtLeast(0)).substringBefore('\n')
        val named = Regex("""val (\w+) = PlayBook\.itemOf\(error, player\.currentTimeline\) \?: player\.currentMediaItem\?\.mediaId""")
            .find(body)?.groupValues?.get(1)
        assertTrue("onPlayerError does not work out which song failed (the one the error names, else the current one)", named != null)
        assertTrue("streamAddresses.playerFailedOn is not given the song that failed: $call", "playerFailedOn($named)" in call)
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
        throw AssertionError("The block of $signature never closes")
    }

    /** Block comments, and line comments that start a line or follow a space, so a url in a string stays. */
    private fun stripComments(text: String): String =
        text.replace(Regex("""/\*.*?\*/""", RegexOption.DOT_MATCHES_ALL), "")
            .replace(Regex("""(^|\s)//.*$""", RegexOption.MULTILINE), "$1")
}
