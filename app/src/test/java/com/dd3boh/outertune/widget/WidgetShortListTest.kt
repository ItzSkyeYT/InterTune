/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.widget

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * A Recently played list that holds fewer songs than the widget has rows for is filled up from
 * History when a widget appears.
 *
 * The list is the widget's own: a song for each one played since the snapshot file first existed.
 * On a new install, or just after the update that brought the widget, that is one or two songs,
 * and the library was only asked when the list was empty. A widget tall enough for six rows then
 * drew two and left the rest of its frame bare, however much History held.
 */
class WidgetShortListTest {

    private fun song(id: String) = WidgetSong(id = id, title = "title $id", artist = "artist $id")

    private fun ids(songs: List<WidgetSong>) = songs.joinToString(" ") { it.id }

    @Test
    fun `a short list keeps its songs first and takes the rest from the library`() {
        val held = listOf(song("b"), song("a"))
        val history = listOf(song("b"), song("a"), song("z"), song("y"), song("x"), song("w"), song("v"))
        assertEquals("b a z y x w", ids(filledUp(held, history, maxPicks = 6)))
    }

    @Test
    fun `a song is never there twice, and a held one keeps its own row`() {
        val held = listOf(song("b").copy(artPath = "/art/b.png"), song("a"))
        val filled = filledUp(held, listOf(song("z"), song("a"), song("b"), song("y")), maxPicks = 6)
        assertEquals("b a z y", ids(filled))
        assertEquals("/art/b.png", filled.first().artPath)
    }

    @Test
    fun `an empty list is the library's, as before`() {
        val history = listOf(song("z"), song("y"))
        assertEquals(history, filledUp(emptyList(), history, maxPicks = 6))
    }

    @Test
    fun `a library with nothing more leaves the list as it is`() {
        val held = listOf(song("b"), song("a"))
        assertEquals(held, filledUp(held, emptyList(), maxPicks = 6))
        assertEquals(held, filledUp(held, listOf(song("a"), song("b")), maxPicks = 6))
    }

    @Test
    fun `no more than the widget has rows for`() {
        val held = List(5) { song("h$it") }
        assertEquals("h0 h1 h2 h3 h4 l0", ids(filledUp(held, List(4) { song("l$it") }, maxPicks = 6)))
    }

    /**
     * Only Recently played. A row of Home's is what Home showed, and Home writes it again at its
     * next build: filled up from the library here, it would shrink back then.
     */
    @Test
    fun `a new widget fills Recently played up, and Home's rows only when they are empty`() {
        val hydrate = File("src/main/java/com/dd3boh/outertune/widget/WidgetStore.kt").readText()
            .substringAfter("suspend fun hydrate(")
            .substringBefore("private suspend fun fromLibrary(")
        assertTrue("which == WidgetList.RECENT) held.size < WidgetLayout.MAX_PICKS else held.isEmpty()" in hydrate)
        assertTrue("filledUp(held, fromLibrary(context, which), WidgetLayout.MAX_PICKS)" in hydrate)
    }
}
