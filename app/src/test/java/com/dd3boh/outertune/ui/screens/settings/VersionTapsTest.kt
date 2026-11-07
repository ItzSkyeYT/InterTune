/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.ui.screens.settings

import com.dd3boh.outertune.ui.screens.settings.VersionTaps.Answer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Seven quick taps on About's version line find the card, counted as Android counts taps on its
 * build number: each within a second and a half of the one before, "3 more" from the fourth.
 */
class VersionTapsTest {
    private fun taps(vararg at: Long): List<Answer> {
        val taps = VersionTaps()
        return at.map(taps::tap)
    }

    @Test
    fun `seven quick taps give the card, counting down from the fourth`() {
        assertEquals(
            listOf(Answer.Quiet, Answer.Quiet, Answer.Quiet, Answer.Left(3), Answer.Left(2), Answer.Left(1), Answer.Card),
            taps(10_000, 10_400, 10_800, 11_200, 11_600, 12_000, 12_400),
        )
    }

    @Test
    fun `a tap a second and a half after the last still counts, a later one starts again`() {
        assertEquals(Answer.Card, taps(0, 1_500, 3_000, 4_500, 6_000, 7_500, 9_000).last())
        val broken = taps(0, 300, 600, 900, 2_401, 2_700, 3_000, 3_300)
        assertEquals(Answer.Left(3), broken[3])
        // the fifth came 1.501 s after the fourth: counted as a first
        assertEquals(listOf(Answer.Quiet, Answer.Quiet, Answer.Quiet, Answer.Left(3)), broken.drop(4))
    }

    @Test
    fun `after the card the count starts again`() {
        val answers = taps(0, 100, 200, 300, 400, 500, 600, 700, 800, 900)
        assertEquals(Answer.Card, answers[6])
        assertEquals(listOf(Answer.Quiet, Answer.Quiet, Answer.Quiet), answers.drop(7))
    }

    private val main = File("src/main/java/com/dd3boh/outertune")

    @Test
    fun `About's version line counts the taps, with no ripple to give it away`() {
        val about = File(main, "ui/screens/settings/AboutScreen.kt").readText()
        val line = about.substringAfter("text = \"\${BuildConfig.VERSION_NAME} (\${BuildConfig.VERSION_CODE}) | \${BuildConfig.FLAVOR}\",")
            .substringBefore("Spacer(")
        assertTrue("the version line has no tap", "Modifier.clickable(interactionSource = null, indication = null, onClick = versionTap)" in line)
        assertTrue("val versionTap = rememberVersionTap(onCard = { cardOpen = true })" in about)
        assertTrue("if (cardOpen) VersionCardDialog(onDismiss = { cardOpen = false })" in about)
        val card = File(main, "ui/screens/settings/VersionCard.kt").readText()
        val tap = card.substringAfter("fun rememberVersionTap(").substringBefore("\n}\n")
        assertTrue("the taps are not counted by VersionTaps", "VersionTaps()" in tap && "taps.tap(SystemClock.uptimeMillis())" in tap)
        assertTrue("found once, one tap opens it", "if (DeviceCard.foundOn(found.value, code) != null) open.value()" in tap)
    }

    @Test
    fun `the sensor is let go when the card closes or the app leaves the screen`() {
        val card = File(main, "ui/screens/settings/VersionCard.kt").readText()
        val tilt = card.substringAfter("private fun rememberTilt(")
        assertTrue("sensors.unregisterListener(listener)" in tilt)
        assertTrue("lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)" in tilt)
        assertTrue("onDispose {\n            lifecycle.removeObserver(observer)\n            listen(false)" in tilt)
        // nothing moves with animations off
        assertTrue("val tilt = rememberTilt(enabled = landed && !still)" in card)
    }

    @Test
    fun `the words are labels, in English and French`() {
        val en = File("src/main/res/values/strings-ot.xml").readText()
        val fr = File("src/main/res/values-fr/strings-ot.xml").readText()
        for ((name, english, french) in listOf(
            Triple("version_card_title", "Your card", "Votre carte"),
            Triple("version_card_more", "%1\$d more", "Encore %1\$d"),
            Triple("version_card_found", "Found %1\$s", "Trouvée le %1\$s"),
            Triple("version_card_number", "No.", "N°"),
        )) {
            assertTrue(name, en.contains("<string name=\"$name\" translatable=\"false\">$english</string>"))
            assertTrue(name, fr.contains("<string name=\"$name\" translatable=\"false\">$french</string>"))
        }
    }
}
