/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.ui.screens.mix

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.unit.Velocity
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * A quick swipe down in the Simple sheet's list, scrolled part of the way, closed the whole editor
 * (the Pixel 5, 10 Nov 2026): the list ran to its top and the sheet took the speed that was left. A
 * swipe that begins in a scrolling area scrolls it and goes no further; one that begins at its top,
 * or on the handle, is the sheet's.
 */
class SheetScrollTest {

    private val finger = NestedScrollSource.UserInput
    private val fling = NestedScrollSource.SideEffect

    @Test
    fun `a swipe that begins at the top, where nothing scrolls, goes on to the sheet`() {
        val g = ScrollsBeforeSheet()
        g.onPreScroll(Offset(0f, 40f), finger)
        // The area took none of it: all of it is left for the sheet's drag, and the speed on release too.
        assertEquals(Offset.Zero, g.onPostScroll(consumed = Offset.Zero, available = Offset(0f, 40f), source = finger))
        g.onPreScroll(Offset(0f, 30f), finger)
        assertEquals(Offset.Zero, g.onPostScroll(consumed = Offset.Zero, available = Offset(0f, 30f), source = finger))
        assertEquals(Velocity.Zero, runBlocking { g.onPostFling(consumed = Velocity.Zero, available = Velocity(0f, 3000f)) })
    }

    @Test
    fun `a swipe that scrolled the area ends there, whatever speed it reaches the top with`() {
        val g = ScrollsBeforeSheet()
        // The finger moves and the list scrolls...
        g.onPreScroll(Offset(0f, 60f), finger)
        assertEquals(Offset.Zero, g.onPostScroll(consumed = Offset(0f, 60f), available = Offset.Zero, source = finger))
        // ...the fling carries it to the top and scrolls on there with nothing to scroll: not the sheet's.
        assertEquals(Offset.Zero, g.onPostScroll(consumed = Offset(0f, 200f), available = Offset(0f, 150f), source = fling))
        assertEquals(Velocity(0f, 3200f), runBlocking { g.onPostFling(consumed = Velocity.Zero, available = Velocity(0f, 3200f)) })
        // A drag past the top in the same swipe is not followed by the sheet either.
        val h = ScrollsBeforeSheet()
        h.onPreScroll(Offset(0f, 60f), finger)
        h.onPostScroll(consumed = Offset(0f, 60f), available = Offset.Zero, source = finger)
        h.onPreScroll(Offset(0f, 80f), finger)
        assertEquals(Offset(0f, 80f), h.onPostScroll(consumed = Offset.Zero, available = Offset(0f, 80f), source = finger))
    }

    @Test
    fun `the next swipe is judged afresh`() {
        val g = ScrollsBeforeSheet()
        g.onPreScroll(Offset(0f, 60f), finger)
        g.onPostScroll(consumed = Offset(0f, 60f), available = Offset.Zero, source = finger)
        runBlocking { g.onPostFling(consumed = Velocity.Zero, available = Velocity(0f, 900f)) }
        // Begun again at the top: the sheet's.
        g.onPreScroll(Offset(0f, 50f), finger)
        assertEquals(Offset.Zero, g.onPostScroll(consumed = Offset.Zero, available = Offset(0f, 50f), source = finger))
        assertEquals(Velocity.Zero, runBlocking { g.onPostFling(consumed = Velocity.Zero, available = Velocity(0f, 2500f)) })
    }

    @Test
    fun `every scrolling area of the sheet has the guard above its scroll`() {
        val sheet = File("src/main/java/com/dd3boh/outertune/ui/screens/mix/MixSimpleSheet.kt").readText()
        val body = sheet.substringAfter("if (landscape) {\n                    // On its side: two upright widths, the songs on the left").substringBefore("SnackbarHost(host")
        // The portrait list and the landscape pair: three, each with the guard first.
        assertEquals(3, Regex("""scrollsBeforeSheet\(\)\.verticalScroll\(rememberScrollState\(\)\)""").findAll(body).count())
        assertTrue("no scrolling area without it", Regex("""[^.]verticalScroll\(rememberScrollState\(\)\)""").findAll(body).count() == 0)
    }
}
