/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.ui.screens.artist

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The artist page stays put while a row of albums is scrolled sideways (#86, 6 Nov 2026). A lazy
 * row is only as tall as the cards on screen, so an album whose name takes two lines made the row
 * taller when it scrolled in and shorter when it scrolled out, and everything under it moved.
 * The page's rows of cards hold every card of their section, so they are as tall as their
 * tallest card wherever they are scrolled to. A section holds a few dozen cards at most.
 */
class ArtistRowsHoldStillTest {
    private val page = File("src/main/java/com/dd3boh/outertune/ui/screens/artist/ArtistScreen.kt").readText()

    @Test
    fun `no row of cards on the artist page is a lazy row`() {
        assertFalse(page.contains("LazyRow {"))
        assertFalse(page.contains("import androidx.compose.foundation.lazy.LazyRow"))
    }

    @Test
    fun `both rows of cards scroll sideways with every card in them`() {
        assertEquals(2, Regex("""Row\(Modifier\.horizontalScroll\(rememberScrollState\(\)\)\)""").findAll(page).count())
        assertTrue(page.contains("libraryAlbums.distinctBy { it.id }.forEach { album ->"))
        assertTrue(page.contains("section.items.distinctBy { it.id }.forEach { item ->"))
    }
}
