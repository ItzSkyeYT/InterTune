/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.ui.navigation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The nav host's own back handler stays behind PagesBack (GitHub #210), and only that one: inside
 * every page Back is looked up as it always was, from the window, so a page's handlers go in among
 * the app's, and a dialog's in the dialog's own window (the poll's Back steps through its questions).
 * This reads the source, as ScreenBackHandlerTest does.
 */
class PagesBackWiringTest {

    private val main = File("src/main/java/com/dd3boh/outertune/MainActivity.kt").readText()
    private val graph = File("src/main/java/com/dd3boh/outertune/ui/navigation/AppNavGraph.kt").readText()
    private val pageBack = File("src/main/java/com/dd3boh/outertune/ui/navigation/PageBack.kt").readText()

    @Test
    fun `the one nav host is composed behind PagesBack`() {
        assertEquals("NavHost calls", 1, Regex("""(?<![A-Za-z])NavHost\(""").findAll(main).count())
        val pages = main.indexOf("val pages: @Composable () -> Unit = @Composable {")
        assertTrue("no pages lambda", pages >= 0)
        assertTrue("the NavHost is not the pages lambda's", main.indexOf("NavHost(", pages) - pages < 120)
        assertTrue("the pages are not behind PagesBack", "PagesBack(navController, backAnimations, pages)" in main)
    }

    @Test
    fun `inside every page Back is looked up as before`() {
        // Every destination is made by screen() (LeavingScreenFillTest holds that).
        assertTrue(
            "screen() does not give the page its own lookup of Back",
            "PageBackOwner {\n        Box(Modifier.fillMaxSize().pageFillWhileLeaving(transition))" in graph,
        )
        assertTrue("a page is not given the unprovided local", "CompositionLocalProvider(reset provides null" in pageBack)
        // Handing a page the activity's owner would also hand it to every dialog drawn from the page,
        // whose BackHandlers then went in the activity's dispatcher, not the dialog's.
        assertFalse("a page is handed the activity's owner", "LocalOnBackPressedDispatcherOwner provides app" in pageBack)
        assertEquals("owners handed down", 1, Regex("""LocalOnBackPressedDispatcherOwner provides owner""").findAll(pageBack).count())
    }
}
