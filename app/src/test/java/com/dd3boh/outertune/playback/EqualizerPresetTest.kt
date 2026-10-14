/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.playback

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The equalizer's curves: how they are kept, and that the ready-made ones stay within reach. */
class EqualizerPresetTest {

    @Test
    fun `a curve comes back as it was kept`() {
        val mine = listOf(3f, -2f, 0f, 1f, 0f, 0f, -4f, 0f, 2f, 12f)
        assertEquals(mine, EqualizerPreset.parse(EqualizerPreset.format(mine)))
    }

    @Test
    fun `nothing kept, or a curve of another size, is flat`() {
        assertEquals(EqualizerPreset.FLAT.gainsDb, EqualizerPreset.parse(null))
        assertEquals(EqualizerPreset.FLAT.gainsDb, EqualizerPreset.parse(""))
        assertEquals(EqualizerPreset.FLAT.gainsDb, EqualizerPreset.parse("1,2,3"))
    }

    @Test
    fun `a band kept out of reach is brought back within it`() {
        val kept = EqualizerPreset.parse("40,-40,0,0,0,0,0,0,0,0")
        assertEquals(12f, kept[0])
        assertEquals(-12f, kept[1])
    }

    @Test
    fun `every ready-made curve has ten bands within reach, and is found by its gains`() {
        for (p in EqualizerPreset.entries) {
            assertEquals(p.name, EqualizerAudioProcessor.BANDS.size, p.gainsDb.size)
            assertTrue(p.name, p.gainsDb.all { it in -EqualizerAudioProcessor.MAX_DB..EqualizerAudioProcessor.MAX_DB })
            assertEquals(p, EqualizerPreset.of(p.gainsDb))
        }
        assertNull(EqualizerPreset.of(listOf(1f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f)))
    }

    @Test
    fun `the equalizer sits after the gain and before spatial audio, in both chains`() {
        val service = File("src/main/java/com/dd3boh/outertune/playback/MusicService.kt").readText()
        assertEquals(2, Regex("""arrayOf\(gainProcessor, equalizerProcessor, binauralProcessor, spatialUpmixProcessor\)""").findAll(service).count())
    }
}
