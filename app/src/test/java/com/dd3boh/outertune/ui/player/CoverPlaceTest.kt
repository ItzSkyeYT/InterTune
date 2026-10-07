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
 * Where the player's cover is on screen changes on every frame while the player sheet or the swipe
 * strip moves. Kept in a Compose state that composition read, it had every cover in the player
 * recompose on each of those frames. This reads the source, because recompositions cannot be
 * counted without a screen.
 */
class CoverPlaceTest {

    private val thumbnail = File("src/main/java/com/dd3boh/outertune/ui/player/Thumbnail.kt").readText()

    @Test
    fun `the cover's place is not kept in a state`() {
        assertFalse(Regex("""mutableStateOf<Rect""").containsMatchIn(thumbnail))
    }

    @Test
    fun `it goes straight from where it is measured to the living background`() {
        val measured = thumbnail.substringAfter(".onGloballyPositioned {").substringBefore(".clip(")
        assertTrue("PlayerCoverPlace.bounds = at" in measured)
        assertTrue("only the cover of the song that plays says where it is", "if (showsWhatPlays)" in measured)
    }

    @Test
    fun `it is handed over when the song changes and taken back when the cover leaves`() {
        // a cover that has not moved is not measured again, so the one that now shows the song says so itself
        assertTrue(Regex("""LaunchedEffect\(ownsError\) \{\s+if \(ownsError\) place\.value\?\.let \{ PlayerCoverPlace\.bounds = it }""").containsMatchIn(thumbnail))
        assertTrue(Regex("""onDispose \{ if \(showsWhatPlays\) PlayerCoverPlace\.bounds = null }""").containsMatchIn(thumbnail))
    }
}
