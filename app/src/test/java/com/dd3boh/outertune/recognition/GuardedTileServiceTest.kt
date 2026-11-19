/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.recognition

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** A tile bound while the phone's Quick Settings dies no longer closes the app (#227). */
class GuardedTileServiceTest {

    private val sources = File("src/main/java/com/dd3boh/outertune")

    @Test
    fun `every Quick Settings tile is a guarded one`() {
        val tiles = sources.walkTopDown().filter { it.extension == "kt" }
            .filter { Regex(""":\s*(Guarded)?TileService\(\)""").containsMatchIn(it.readText()) }
            .associate { it.name to it.readText() }
        assertEquals(
            setOf("GuardedTileService.kt", "PhoneSongTileService.kt", "RecognitionChoiceTileService.kt", "RecognitionTileService.kt"),
            tiles.keys,
        )
        for ((name, text) in tiles - "GuardedTileService.kt") {
            assertTrue("$name binds unguarded", Regex(""":\s*GuardedTileService\(\)""").containsMatchIn(text))
        }
    }

    @Test
    fun `the bind gives nothing back instead of throwing when Quick Settings is gone`() {
        val guard = File(sources, "recognition/GuardedTileService.kt").readText()
        val bind = guard.substringAfter("override fun onBind(intent: Intent?): IBinder? = try {")
        assertTrue("super.onBind(intent)" in bind.substringBefore("} catch (e: RuntimeException) {"))
        assertTrue("null" in bind.substringAfter("} catch (e: RuntimeException) {").substringBefore("\n    }\n"))
    }
}
