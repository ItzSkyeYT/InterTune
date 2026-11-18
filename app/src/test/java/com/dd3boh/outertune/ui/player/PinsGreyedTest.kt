/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.ui.player

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * A pin that cannot act for the song playing used to vanish: on a local song six of them went, the row moved, and
 * Niko took them for broken (#testers, 18 Nov 2026). His pick that day: it keeps its place, greyed, and says why.
 */
class PinsGreyedTest {

    private val actions = File("src/main/java/com/dd3boh/outertune/ui/player/PinActions.kt").readText()
    private val player = File("src/main/java/com/dd3boh/outertune/ui/player/Player.kt").readText()

    @Test
    fun `a pin is hidden only where its feature is missing, never for the song playing`() {
        assertTrue(actions.contains("shown = { pin -> pinOffered(pin) },"))
        assertFalse("no pin hides for a local song", Regex("""shown = \{[^}]*!local""").containsMatchIn(actions))
        assertTrue(actions.contains("val unavailable: (PlayerPin) -> Int?"))
    }

    @Test
    fun `every place that draws pins greys the ones that cannot act`() {
        assertTrue(player.contains("val pinCircle: @Composable (PlayerPin) -> Unit = { pin -> PinGreyed(pinActions.unavailable(pin)) {"))
        assertTrue(player.contains("val titleSegment: @Composable (PlayerPin, Shape) -> Unit = { pin, shape -> PinGreyed(pinActions.unavailable(pin)) {"))
        assertTrue(player.contains("PinGreyed(pinActions.unavailable(pin)) { when (pin) {"))
    }

    @Test
    fun `each reason is written in English and French`() {
        val en = File("src/main/res/values/strings-ot.xml").readText()
        val fr = File("src/main/res/values-fr/strings-ot.xml").readText()
        val reasons = Regex("""R\.string\.(pin_\w+)""").findAll(actions).map { it.groupValues[1] }.toSet()
        assertTrue("the reasons read", reasons.size >= 5)
        reasons.forEach { name ->
            assertTrue("$name in English", en.contains("name=\"$name\""))
            assertTrue("$name in French", fr.contains("name=\"$name\""))
        }
    }
}
