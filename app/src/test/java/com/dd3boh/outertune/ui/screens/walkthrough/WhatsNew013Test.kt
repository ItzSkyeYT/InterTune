/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.ui.screens.walkthrough

import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.unit.IntSize
import com.dd3boh.outertune.R
import com.dd3boh.outertune.ui.screens.Screens
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 0.13's What's new page, trimmed to its headline features (his question of 26 Nov 2026, "is the what's new banner
 * menu thing good?": about twenty cards, a wall after the first three), and a Show me on every one of them (his note
 * the same morning, "show me is lacking for items... like the listen together or the playlist reordering").
 */
class WhatsNew013Test {

    private val v01210 = 102
    private val v013 = 103
    private val main = File("src/main/java/com/dd3boh/outertune")

    private fun source(path: String) = File(main, path).readText()
    private fun thing(id: String) = NEW_THINGS.single { it.id == id }
    private fun way(id: String) = thing(id).stops.map { it.targetId to it.route }

    @After
    fun clearTargets() {
        TourTargets.forget(Tour.PLAYER_CAST)
    }

    @Test
    fun `the page is the headline features, each with a Show me, and the smaller things are kept, not listed`() {
        val page = newThingsFor(v01210, buildVersionCode = v013)
        assertEquals(
            listOf("together", "airplay", "listenbrainz", "file_format", "block_artist", "custom_order", "varispeed", "vibration", "metrolist_import"),
            page.map { it.id },
        )
        for (it in page) assertTrue("${it.id} has no Show me", it.action == NewThingAction.SHOW && it.stops.isNotEmpty())
        // Nothing is lost: the cards that were on it are still here, with their words and ways, not listed.
        val kept = NEW_THINGS.filter { !it.listed }
        assertEquals(
            setOf("dont_play", "saved_twice", "sleep_timer_more", "liked_sync", "album_goes_on", "recognise_offline", "animations",
                "stats_plays", "queue_swipe_undo", "downloads_move", "original_audio"),
            kept.map { it.id }.toSet(),
        )
        assertTrue("only 0.13's page is trimmed", kept.all { it.release == "0.13" })
        // And listed nowhere, the page under About included.
        assertTrue(newThingsFor(0, buildVersionCode = v013, everything = true).none { !it.listed })
    }

    @Test
    fun `Listen together goes to Home's tile, then to Start a room in its sheet`() {
        assertEquals(listOf(Tour.HOME_TOGETHER to null, Tour.TOGETHER_START to Tour.ROUTE_TOGETHER_SHEET), way("together"))
        assertEquals(listOf(false, true), thing("together").stops.map { it.inSheet })
        assertEquals(R.string.together_start, thing("together").stops[1].title)
    }

    @Test
    fun `AirPlay opens the player at the cast button by the title`() {
        assertEquals(listOf(Tour.PLAYER_CAST to Tour.ROUTE_NOW_PLAYING), way("airplay"))
        assertFalse("the player is no sheet: the overlay draws over it", thing("airplay").stops.single().inSheet)
    }

    @Test
    fun `your own order goes to Library, then to the Playlists screen's sort`() {
        assertEquals(listOf(Tour.NAV_LIBRARY to null, Tour.PLAYLISTS_SORT to Tour.ROUTE_PLAYLISTS), way("custom_order"))
        assertEquals(Screens.Playlists.route, Tour.ROUTE_PLAYLISTS)
        assertEquals(R.string.sort_by_custom, thing("custom_order").stops[1].title)
    }

    @Test
    fun `Varispeed has a card, and goes through Player and audio to its switch`() {
        assertEquals(listOf(Tour.ROW_PLAYER to Tour.ROUTE_SETTINGS, Tour.SETTING_VARISPEED to Tour.ROUTE_PLAYER), way("varispeed"))
        assertTrue("Modifier.tourTarget(Tour.SETTING_VARISPEED).settingRow(R.string.varispeed)" in source("ui/screens/settings/fragments/PlayerFrag.kt"))
    }

    @Test
    fun `a stop on the player is kept only when its button is there`() {
        // With nothing in the player it cannot open, and the page says there is nothing to point at.
        val state = TourState()
        state.start(thing("airplay").stops)
        assertFalse(state.running)
        TourTargets.arrive(Tour.PLAYER_CAST, BringIntoViewRequester())
        TourTargets.put(Tour.PLAYER_CAST, Rect(0f, 0f, 10f, 10f), IntSize(10, 10))
        state.start(thing("airplay").stops)
        assertTrue(state.running)
        assertTrue("its one bubble is the last step", state.onLastStep)
    }

    @Test
    fun `the player and the sheet are no screens of the navigation, and say when they are up`() {
        val graph = source("ui/navigation/AppNavGraph.kt")
        assertTrue(Regex("""screen\(\s*"tour/""").findAll(graph).none())
        assertTrue(Tour.ROUTE_NOW_PLAYING.startsWith("tour/") && Tour.ROUTE_TOGETHER_SHEET.startsWith("tour/sheet/"))
        // Opened, never navigated to.
        val activity = source("MainActivity.kt")
        val goTo = activity.substringAfter("val tourGoTo: (String) -> Unit = { route ->").substringBefore("\n                        }\n")
        assertTrue(goTo, "if (route == Tour.ROUTE_NOW_PLAYING) {" in goTo && "playerBottomSheetState.expandSoft()" in goTo)
        assertTrue(goTo, "} else if (route.startsWith(\"tour/\")) {" in goTo)
        assertTrue("else if (route.startsWith(\"tour/\")) {\n                                        tourGoTo(route)" in activity)
        assertTrue("TourTargets.screenArrived(Tour.ROUTE_NOW_PLAYING)" in source("ui/player/Player.kt"))
        val sheet = source("ui/dialog/TogetherDialog.kt")
        assertTrue("TourTargets.screenArrived(Tour.ROUTE_TOGETHER_SHEET)" in sheet)
        assertTrue("TourSpot(Tour.ROUTE_TOGETHER_SHEET, Modifier.matchParentSize())" in sheet)
        // Home opens its sheet for the tour, as a tap on the tile would.
        val home = source("ui/screens/HomeScreen.kt")
        assertTrue("val tourAtSheet = TourInSheet.stop?.route == Tour.ROUTE_TOGETHER_SHEET" in home)
        assertTrue("LaunchedEffect(tourAtSheet) { if (tourAtSheet) showTogether = true }" in home)
    }

    @Test
    fun `the sheet's stop is let go of only by the stop that holds it`() {
        val state = TourState()
        val start = thing("together").stops[1]
        val tile = thing("together").stops[0]
        TourInSheet.open(state, start, advance = {}, finish = {})
        TourInSheet.close(tile)
        assertEquals(start, TourInSheet.stop)
        TourInSheet.close(start)
        assertNull(TourInSheet.stop)
    }

    @Test
    fun `on the page the button stands under the words, at their start`() {
        // Beside them it took a third of the card and left the sentence a narrow column (26 Nov 2026).
        val card = source("ui/screens/walkthrough/WelcomeBack.kt").substringAfter("private fun ThingCard(")
        assertTrue("verticalAlignment = Alignment.Top," in card)
        val words = card.substringAfter("Column(").substringBefore("\n            }\n        }\n")
        assertTrue("the button is in the column of words", "text = body," in words && "FilledTonalButton(" in words)
        assertTrue(words.indexOf("text = body,") < words.indexOf("FilledTonalButton("))
    }
}
