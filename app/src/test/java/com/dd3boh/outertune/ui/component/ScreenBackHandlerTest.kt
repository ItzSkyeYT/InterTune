/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.ui.component

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * A screen under the player takes Back only through ScreenBackHandler, which steps aside while the
 * player is open. A plain BackHandler on such a screen is put in place after the player's and wins:
 * one Back cleared a Library filter under the open player and left the player open (Pixel 5, 21
 * Oct 2026). The flows that fill the whole screen, with no player over them, keep their own.
 * This reads the source, as ReleasedFlagsTest does.
 */
class ScreenBackHandlerTest {

    private val screens = File("src/main/java/com/dd3boh/outertune/ui/screens")
    private val wholeScreenFlows = setOf("LoginScreen.kt", "SetupWizard.kt", "ShortSetup.kt", "TourOverlay.kt")
    private val plainBackHandler = Regex("""(?<![A-Za-z])BackHandler\s*[({]""")

    @Test
    fun noScreenUnderThePlayerTakesBackFromIt() {
        val offenders = screens.walkTopDown()
            .filter { it.isFile && it.extension == "kt" && it.name !in wholeScreenFlows }
            .filter { plainBackHandler.containsMatchIn(it.readText()) }
            .map { it.relativeTo(screens).path }
            .sorted()
            .toList()
        assertEquals("screens that take Back with a plain BackHandler", emptyList<String>(), offenders)
    }

    @Test
    fun theHelperStepsAsideWhileThePlayerIsOpen() {
        val text = File("src/main/java/com/dd3boh/outertune/ui/component/ScreenBackHandler.kt").readText()
        assertTrue("ScreenBackHandler does not read the player", "LocalPlayerSheet.current" in text)
        assertTrue("ScreenBackHandler does not step aside", "enabled && !playerOpen" in text)
    }
}
