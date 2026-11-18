/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.utils

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * A local song or album with no picture of its own gets its cover from CoilBitmapLoader.drawPlaceholder,
 * which Coil hands out as the file's picture: the lists, the player and the widget show it. It used to be
 * a note on nothing, so a widget showed whatever was behind it (#150, Ti-Die). Now a square fills the
 * whole picture with an opaque colour and the note sits on it. Read from the source, as the flag tests
 * do: a vector cannot be drawn on the JVM.
 */
class LocalSongCoverTest {
    private val cover = File("src/main/res/drawable/local_song_cover.xml").readText()
    private val loader = File("src/main/java/com/dd3boh/outertune/utils/CoilBitmapLoader.kt").readText()

    @Test
    fun `the cover is drawn over the whole picture`() {
        val draw = loader.substringAfter("fun drawPlaceholder(").substringBefore("return bitmap")
        assertTrue(draw.contains("R.drawable.local_song_cover"))
        assertTrue(draw.contains("setBounds(0, 0, x, y)"))
    }

    @Test
    fun `its first shape fills the square with an opaque colour`() {
        assertTrue(cover.contains("""android:viewportWidth="960""""))
        val square = cover.substringAfter("<path").substringBefore("</path>")
        assertTrue(square.contains("""android:pathData="M0,0h960v960h-960z""""))
        val colours = Regex("""android:(?:startColor|endColor|fillColor)="#([0-9A-Fa-f]{8})"""")
            .findAll(square).map { it.groupValues[1] }.toList()
        assertEquals(2, colours.size)
        assertTrue("every colour of the square is opaque: $colours", colours.all { it.startsWith("FF", ignoreCase = true) })
    }
}
