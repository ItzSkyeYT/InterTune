/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * A song found by a search in the car plays on into its radio, as a song picked in the phone's
 * search does, and no longer into the other songs that matched the same words. Tapped in the
 * results or said to the assistant. Seen in a real car on 24 Oct 2026: "it just plays the next
 * ones in the search list".
 *
 * The decision is SearchPlay's and is tested as it is. What MediaLibrarySessionCallback does with
 * it needs a car or a device, so the wiring is read from the source, as AutoBrowseTest does.
 */
class CarSearchRadioTest {

    private data class Hit(val id: String, val local: Boolean = false)

    private fun plan(found: List<Hit>, picked: String?) = SearchPlay.plan(found, picked, { it.id }, { it.local })

    private val results = listOf(Hit("a"), Hit("b"), Hit("c"))

    @Test
    fun `a song tapped in the results plays with its radio, not with the other results`() {
        assertEquals(SearchPlay.Radio(Hit("b")), plan(results, "b"))
        assertEquals(SearchPlay.Radio(Hit("c")), plan(results, "c"))
    }

    @Test
    fun `a result tapped in the car, read back from the id the car sends, plays with its radio`() {
        // The id onGetSearchResult gives a result, "search/<query>/<song>", with a query that has
        // slashes of its own, as resolvePlayRequest reads it before the plan.
        val tapped = PlayRequest.parse("${MusicService.SEARCH}/AC/DC thunderstruck/b")
        assertEquals(PlayRequest.Search("AC/DC thunderstruck", "b"), tapped)
        assertEquals(SearchPlay.Radio(Hit("b")), plan(results, tapped!!.songId))
    }

    @Test
    fun `said to the assistant, the best match plays with its radio`() {
        assertEquals(SearchPlay.Radio(Hit("a")), plan(results, null))
    }

    @Test
    fun `a file on the device has no radio and plays on into what was found with it, as before`() {
        val mixed = listOf(Hit("file", local = true), Hit("b"), Hit("other file", local = true))
        assertEquals(SearchPlay.Found(mixed, 2), plan(mixed, "other file"))
        // The best match is the first found, a file or not: never a song further down instead.
        assertEquals(SearchPlay.Found(mixed, 0), plan(mixed, null))
        // A song with an id still gets its radio beside files in the same results.
        assertEquals(SearchPlay.Radio(Hit("b")), plan(mixed, "b"))
    }

    @Test
    fun `nothing found, or a song no longer found, fails the request`() {
        assertNull(plan(emptyList(), null))
        assertNull(plan(emptyList(), "a"))
        // A stale id fails as everywhere else in the car (PlayRequestIndex), rather than playing another song.
        assertNull(plan(results, "gone"))
    }

    // The wiring, read from the source.

    private fun source(path: String) = File("src/main/java/com/dd3boh/outertune/$path").readText()

    private val callback = source("playback/MediaLibrarySessionCallback.kt")

    /** The function [name] declares in [text], from its name to the next member of the class (its comment or itself). */
    private fun body(text: String, name: String): String {
        val start = text.indexOf("fun $name(")
        assertTrue("no function $name", start >= 0)
        val next = Regex("\n    (/\\*\\*|(private |override |internal )?(suspend )?fun )").find(text, start + 1)?.range?.first ?: text.length
        return text.substring(start, next)
    }

    @Test
    fun `both ways a car searches go through the plan, and nothing hands the player the whole list unasked`() {
        val resolve = body(callback, "resolvePlayRequest")
        // Said: no id, the words in the request.
        val spoken = resolve.substringAfter("if (mediaId.isEmpty()) {").substringBefore("\n        }\n")
        assertTrue("a request with no id no longer goes through playFound:\n$spoken", "playFound(searchCar(query), picked = null" in spoken)
        // Tapped: "search/<query>/<song>".
        assertTrue("a tapped search result no longer goes through playFound", "playFound(searchCar(target.query), picked = target.songId" in resolve)
        // The old way: every result became the queue.
        assertTrue("the search's results are handed to the player as the queue again", "startExternalQueue(searchCar(" !in callback)
        assertTrue("the plan no longer decides what a found song plays into", "SearchPlay.plan(" in body(callback, "playFound"))
    }

    /** The arguments of the call that starts at [at] in [text], split at its own commas. */
    private fun arguments(text: String, at: Int): List<String> {
        var depth = 0
        var i = text.indexOf('(', at)
        val out = ArrayList<String>()
        val current = StringBuilder()
        while (i < text.length) {
            val c = text[i++]
            when {
                c == '(' && depth++ == 0 -> continue
                c == ')' && --depth == 0 -> break
                c == ',' && depth == 1 -> { out += current.toString().trim(); current.clear(); continue }
            }
            if (depth >= 1) current.append(c)
        }
        out += current.toString().trim()
        return out.filter { it.isNotEmpty() }
    }

    @Test
    fun `the car starts the radio with the call the phone's search makes`() {
        val car = body(callback, "startSearchRadio")
        val carCall = arguments(car, car.indexOf("playQueue("))
        // The two screens of the phone's online search: a result and a suggestion.
        for (screen in listOf("ui/screens/search/OnlineSearchScreen.kt", "ui/screens/search/OnlineSearchResult.kt")) {
            val text = source(screen)
            val phoneCall = arguments(text, text.lastIndexOf("playQueue(", text.indexOf("YouTubeQueue.radio(")))
            assertTrue("$screen no longer starts a radio from a picked song: $phoneCall", phoneCall.first().startsWith("YouTubeQueue.radio("))
            assertTrue("the car no longer starts a radio from the picked song: $carCall", carCall.first().startsWith("YouTubeQueue.radio("))
            // Same flags and origin, and no title of its own: YouTube's answer names it, as on the phone.
            assertEquals(screen, phoneCall.drop(1).toSet(), carCall.drop(1).toSet())
        }
        // The radio reaches the player through playQueue, so media3 must load nothing over it.
        assertTrue("startSearchRadio hands media3 something to load over the radio", "return null" in car)
        // And answers only once the song is in the player: a controller's prepare and play come
        // after its request, and would otherwise start the song before it for a moment.
        assertTrue("startSearchRadio no longer waits for the song to be in the player", "currentMediaMetadata.first {" in car)
    }

    @Test
    fun `browsing an album, a playlist, Liked songs or Home still plays the list it was tapped in`() {
        val resolve = body(callback, "resolvePlayRequest")
        // Everything after the line that takes a tapped search result away.
        val browsing = resolve.substringAfter("if (target is PlayRequest.Search)").substringAfter("\n")
        assertTrue("resolvePlayRequest no longer reads as it did", "is PlayRequest.Album ->" in browsing && "is PlayRequest.Playlist ->" in browsing && "is PlayRequest.Home ->" in browsing)
        assertTrue("a list browsed in the car no longer plays as the list", "return startExternalQueue(songs.map { it.toMediaItem() }, index, startPositionMs)" in browsing)
        assertTrue("shuffling a list in the car no longer plays the list", "startExternalQueue(list.shuffled()" in browsing)
        for (radio in listOf("startSearchRadio(", "YouTubeQueue", "playFound(")) {
            assertTrue("browsing goes through $radio", radio !in browsing)
        }
    }
}
