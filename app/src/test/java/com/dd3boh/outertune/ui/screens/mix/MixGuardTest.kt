/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.ui.screens.mix

import com.dd3boh.outertune.R
import com.dd3boh.outertune.mix.pill.PlainReason
import com.dd3boh.outertune.ui.screens.settings.SETTINGS_INDEX
import com.dd3boh.outertune.ui.screens.walkthrough.Tour
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 6 Nov 2026: a saved change did not play and nothing in the editor said why. The editor says when a
 * setting keeps every change plain right now, as the playlist's lines do, with a button that opens
 * the settings at that setting's row (his rule: say why, and offer the fix).
 */
class MixGuardTest {
    private val dir = File("src/main/java/com/dd3boh/outertune/ui/screens/mix")

    @Test
    fun `the editor names what keeps a change plain now, on both levels, with its setting`() {
        val screen = File(dir, "MixEditorScreen.kt").readText()
        assertTrue("val blocker = rememberMixBlocker()" in screen)
        assertTrue("else -> blocker?.let { MixWords.blocked(it) }?.let { stringResource(it) }" in screen)
        assertTrue("if (guard != null) GuardBand(guard, guardSetting, phoneFix)" in screen)
        assertTrue("guardSetting = guardSetting," in screen)
        assertTrue("guardFix = phoneFix," in screen)
        assertTrue("if (guard != null) GuardBand(guard, guardSetting, guardFix)" in File(dir, "MixSimpleSheet.kt").readText())
        // A change past what this phone holds in time: said, with the fix that moves it closer.
        assertTrue("phoneGuard != null -> words.phoneGuard(phoneGuard)" in screen)
        assertTrue("vm.edit { fitToPhone() }" in screen)
    }

    @Test
    fun `the button names the setting behind the reason, none where no setting has it`() {
        assertEquals(R.string.skip_silence, guardSetting(skipSilence = true, blocker = null))
        assertEquals(R.string.spatial_audio, guardSetting(skipSilence = false, blocker = PlainReason.SPATIAL))
        assertEquals(R.string.audio_decoder_preference, guardSetting(skipSilence = false, blocker = PlainReason.DECODER))
        assertNull(guardSetting(skipSilence = false, blocker = PlainReason.CASTING))
        assertNull(guardSetting(skipSilence = false, blocker = PlainReason.SPEED))
        assertNull(guardSetting(skipSilence = false, blocker = null))
    }

    /** The jump finds a row by its title in the index: a title it does not know would stop at nothing. */
    @Test
    fun `every setting a reason names is a row of Player and audio, and both buttons jump to it`() {
        for (reason in PlainReason.entries) {
            val title = MixWords.blockedSetting(reason) ?: continue
            val rows = SETTINGS_INDEX.filter { it.title == title }
            assertEquals("$reason", 1, rows.size)
            assertEquals("$reason", Tour.ROUTE_PLAYER, rows.single().route)
        }
        assertTrue("TextButton(onClick = { MixWords.openSetting(setting) })" in File(dir, "MixGuard.kt").readText())
        assertTrue("onClick = { MixWords.openSetting(settings) }," in File(dir, "MixPlaylist.kt").readText())
        assertTrue("SettingJump.go(it.jump())" in File(dir, "MixWords.kt").readText())
        // And the row comes to rest clear of the mini player and the tabs, not behind them.
        assertTrue("TourTargets.bring(row, maxOf(room, third))" in File("src/main/java/com/dd3boh/outertune/ui/screens/settings/SettingJump.kt").readText())
    }

    @Test
    fun `head tracking is no longer named among what stops a mix`() {
        for (file in listOf("src/main/res/values/strings-mix.xml", "src/main/res/values-fr/strings-mix.xml")) {
            val line = File(file).readText().substringAfter("<string name=\"mix_blocked_spatial\">").substringBefore("</string>")
            assertFalse(line, "head tracking" in line || "suivi de la tête" in line)
        }
    }
}
