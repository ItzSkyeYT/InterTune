/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.ui.screens.playlist

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * What a drag in a synced playlist sends YouTube Music. Each list below is the playlist as the
 * screen shows it once the drag has ended, which is what the moves are now worked out from.
 */
class PlaylistDragTest {

    /** YouTube Music's ACTION_MOVE_VIDEO_BEFORE: the song comes out and goes back in front of the other. */
    private fun MutableList<String>.moveBefore(setVideoId: String, successor: String) {
        remove(setVideoId)
        add(indexOf(successor), setVideoId)
    }

    @Test
    fun `a song dragged up goes in front of the song now after it`() {
        // A B C D E, with E dragged to second place.
        assertEquals(listOf("E" to "B"), youTubeMovesAfterDrag(listOf("A", "E", "B", "C", "D"), from = 4, to = 1))
    }

    @Test
    fun `a song dragged down goes in front of the song now after it`() {
        // A B C D E, with B dragged to fourth place.
        assertEquals(listOf("B" to "E"), youTubeMovesAfterDrag(listOf("A", "C", "D", "B", "E"), from = 1, to = 3))
    }

    @Test
    fun `a song dragged to the end goes in front of the last one, which then goes in front of it`() {
        // A B C D E, with B dragged to the end. Nothing follows it to be put in front of.
        assertEquals(
            listOf("B" to "E", "E" to "B"),
            youTubeMovesAfterDrag(listOf("A", "C", "D", "E", "B"), from = 1, to = 4)
        )
    }

    @Test
    fun `a drag that ends where it started sends nothing`() {
        assertEquals(emptyList<Pair<String, String>>(), youTubeMovesAfterDrag(listOf("A", "B", "C"), from = 1, to = 1))
        assertEquals(emptyList<Pair<String, String>>(), youTubeMovesAfterDrag(listOf("A", "B", "C"), from = 2, to = 2))
    }

    @Test
    fun `a song YouTube has not given a set-video id yet cannot be named, so nothing is sent`() {
        assertEquals(emptyList<Pair<String, String>>(), youTubeMovesAfterDrag(listOf("A", null, "B"), from = 0, to = 1))
        assertEquals(emptyList<Pair<String, String>>(), youTubeMovesAfterDrag(listOf("B", null, "A"), from = 2, to = 0))
        assertEquals(emptyList<Pair<String, String>>(), youTubeMovesAfterDrag(listOf("A", null, "B"), from = 1, to = 2))
    }

    @Test
    fun `every drag leaves YouTube in the order on screen`() {
        for (size in 1..6) {
            val before = List(size) { "S$it" }
            for (from in before.indices) for (to in before.indices) {
                val onScreen = before.toMutableList().apply { add(to, removeAt(from)) }
                val youTube = before.toMutableList()
                youTubeMovesAfterDrag(onScreen, from, to).forEach { (song, successor) -> youTube.moveBefore(song, successor) }
                assertEquals("$from to $to of $size", onScreen, youTube)
            }
        }
    }
}
