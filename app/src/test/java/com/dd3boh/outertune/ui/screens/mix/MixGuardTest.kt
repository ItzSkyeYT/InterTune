/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.ui.screens.mix

import com.dd3boh.outertune.mix.pill.PlainReason
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 6 Nov 2026: a saved change did not play and nothing in the editor said why. The editor says when a
 * setting keeps every change plain right now, as the playlist's lines do, with the page that changes
 * it (his rule: say why, and offer the fix).
 */
class MixGuardTest {
    private val dir = File("src/main/java/com/dd3boh/outertune/ui/screens/mix")

    @Test
    fun `the editor names what keeps a change plain now, on both levels, with its settings page`() {
        val screen = File(dir, "MixEditorScreen.kt").readText()
        assertTrue("val blocker = rememberMixBlocker()" in screen)
        assertTrue("else -> blocker?.let { MixWords.blocked(it) }?.let { stringResource(it) }" in screen)
        assertTrue("if (guard != null) GuardBand(guard, guardRoute, openGuard)" in screen)
        assertTrue("guardRoute = guardRoute," in screen && "onOpenGuard = openGuard," in screen)
        assertTrue("if (guard != null) GuardBand(guard, guardRoute, onOpenGuard)" in File(dir, "MixSimpleSheet.kt").readText())
    }

    @Test
    fun `the button opens the page behind the reason, none where no page has it`() {
        assertEquals("settings/player", guardRoute(skipSilence = true, blocker = null))
        assertEquals("settings/player", guardRoute(skipSilence = false, blocker = PlainReason.SPATIAL))
        assertNull(guardRoute(skipSilence = false, blocker = PlainReason.CASTING))
        assertNull(guardRoute(skipSilence = false, blocker = null))
    }

    @Test
    fun `head tracking is no longer named among what stops a mix`() {
        for (file in listOf("src/main/res/values/strings-mix.xml", "src/main/res/values-fr/strings-mix.xml")) {
            val line = File(file).readText().substringAfter("<string name=\"mix_blocked_spatial\">").substringBefore("</string>")
            assertFalse(line, "head tracking" in line || "suivi de la tête" in line)
        }
    }
}
