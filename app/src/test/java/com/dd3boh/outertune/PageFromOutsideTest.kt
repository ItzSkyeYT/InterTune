/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune

import com.dd3boh.outertune.search.SystemSearch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * What a tap from outside the app opens comes in front of the player. His note of 30 Oct 2026: a
 * song found on the phone, its notification tapped, and the results opened under the queue he had
 * left open, where nothing showed them. The recognition tile listened under it the same way.
 */
class PageFromOutsideTest {

    private val activity = File("src/main/java/com/dd3boh/outertune/MainActivity.kt").readText()

    /** The body of the LaunchedEffect keyed as [keys], down to the next blank line. */
    private fun effect(keys: String): String {
        val at = activity.indexOf("LaunchedEffect($keys)")
        assertTrue("an effect keyed $keys", at >= 0)
        return activity.substring(at, activity.indexOf("\n\n", at))
    }

    @Test
    fun `the song found on this phone opens a page, and so do the other links but a song to play`() {
        // The found notification's tap (RecognitionService.notifyFound) and the phone's own search.
        assertTrue(linkOpensPage(SystemSearch.searchLink("Lush Life (Riot Remix) Zara Larsson")))
        assertTrue(linkOpensPage(SystemSearch.playlistLink("1a2b3c")))
        assertTrue(linkOpensPage(SystemSearch.artistLink("UCnUs-yi_YFKp8z3S-xL5kpQ")))
        assertTrue(linkOpensPage(SystemSearch.albumLink("MPREb_abcdefghijk")))
        assertTrue(linkOpensPage("https://music.youtube.com/playlist?list=PLabcdefghij"))
        // A song from a link opens its album, or its menu: a page of sorts over the player too.
        assertTrue(linkOpensPage("https://music.youtube.com/watch?v=abcdefghijk"))
        // Only a song to play at once is left to the player, which shows it open or not.
        assertFalse(linkOpensPage(SystemSearch.songLink("abcdefghijk")))
        assertFalse(linkOpensPage("lush life"))
        assertFalse(linkOpensPage(""))
    }

    @Test
    fun `the player and the search overlay are put away the way the downloads tap put them away`() {
        val putAway = activity.substring(activity.indexOf("fun putAwayForPage()"), activity.indexOf("// The recognition tile and widget"))
        assertTrue("if (playerBottomSheetState.isExpanded) playerBottomSheetState.collapseSoft()" in putAway)
        assertTrue("searchActive = false" in putAway)
        assertTrue("putAwayForPage()" in effect("pendingDownloads.asked, oobeStatus"))
    }

    @Test
    fun `a link that opens a page puts them away before it opens`() {
        val links = effect("pendingLaunchLink, pendingLink.link, playerConnection, oobeStatus")
        val putAway = links.indexOf("if (linkOpensPage(link)) putAwayForPage()")
        assertTrue("the links put the player away", putAway >= 0)
        assertTrue("before the search opens", putAway < links.indexOf("navController.navigate(\"search/"))
        assertTrue("before a YouTube page opens", putAway < links.indexOf("youtubeNavigator("))
    }

    @Test
    fun `the recognition tile and widget put them away before the screen opens`() {
        val tile = effect("pendingRecognition.asked, oobeStatus")
        val putAway = tile.indexOf("putAwayForPage()")
        assertTrue("the tile puts the player away", putAway >= 0)
        assertTrue(putAway < tile.indexOf("navController.navigate(RecognitionShortcut.ROUTE)"))
    }

    @Test
    fun `the notification of the song found opens the app's search, which is a link that opens a page`() {
        val service = File("src/main/java/com/dd3boh/outertune/recognition/RecognitionService.kt").readText()
        val found = service.substring(service.indexOf("private fun notifyFound("))
        assertTrue("SystemSearch.searchLink(words)" in found)
        assertEquals("Lush Life (Riot Remix) Zara Larsson", SystemSearch.searchOf(SystemSearch.searchLink("Lush Life (Riot Remix) Zara Larsson")))
    }
}
