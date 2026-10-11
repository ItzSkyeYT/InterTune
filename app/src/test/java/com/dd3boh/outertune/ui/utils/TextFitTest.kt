/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.ui.utils

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A label that does not fit gets smaller before it is cut or broken. What decides the size is
 * here; the measuring itself needs a device.
 */
class TextFitTest {

    @Test
    fun `a label is tried at its own size first, then half a point less each time`() {
        assertEquals(listOf(12f, 11.5f, 11f, 10.5f, 10f, 9.5f, 9f), sizesToTry(largest = 12f, smallest = 9f))
    }

    @Test
    fun `the last size tried is the smallest, even when it is not a whole step away`() {
        assertEquals(listOf(12f, 11.5f, 11f, 10.8f), sizesToTry(largest = 12f, smallest = 10.8f))
    }

    @Test
    fun `a smallest size that is not under the label's own leaves the one size`() {
        assertEquals(listOf(12f), sizesToTry(largest = 12f, smallest = 12f))
        assertEquals(listOf(12f), sizesToTry(largest = 12f, smallest = 14f))
    }

    @Test
    fun `the size kept is the largest at which the label is whole`() {
        val sizes = sizesToTry(largest = 12f, smallest = 9f)
        assertEquals(10.5f, largestWhole(sizes) { it <= 10.5f })
        assertEquals(9f, largestWhole(sizes) { it <= 9f })
    }

    @Test
    fun `a label that fits at its own size is measured once`() {
        var measured = 0
        val kept = largestWhole(sizesToTry(largest = 18f, smallest = 14f)) { measured++; true }
        assertEquals(18f, kept)
        assertEquals(1, measured)
    }

    @Test
    fun `a label that is whole at no size is drawn at the smallest`() {
        assertEquals(9f, largestWhole(sizesToTry(largest = 12f, smallest = 9f)) { false })
    }

    /** What Home drew at a large font size, and the tour's button in French. */
    @Test
    fun `a line that ends inside a word breaks it`() {
        assertTrue(breaksAWord("Statistiques", lineEnds = listOf(10)))
        assertTrue(breaksAWord("Historique", lineEnds = listOf(9)))
        assertTrue(breaksAWord("Faites-moi visiter", lineEnds = listOf(9)))
        // One good line end does not excuse a bad one.
        assertTrue(breaksAWord("Faites-moi visiter", lineEnds = listOf(7, 15)))
    }

    @Test
    fun `a line that ends after a space or a hyphen breaks nothing`() {
        assertFalse(breaksAWord("Scanner locale", lineEnds = listOf(8)))
        assertFalse(breaksAWord("Faites-moi visiter", lineEnds = listOf(7)))
        assertFalse(breaksAWord("Faites-moi visiter", lineEnds = listOf(7, 11)))
    }

    @Test
    fun `a label on one line breaks nothing`() {
        assertFalse(breaksAWord("Statistiques", lineEnds = emptyList()))
        // The end of the text is the end of its last line, not a break.
        assertFalse(breaksAWord("Statistiques", lineEnds = listOf(12)))
    }

    @Test
    fun `one word has nowhere to wrap`() {
        assertFalse(canWrap("Historique"))
        assertFalse(canWrap("Statistiques"))
        assertFalse(canWrap("Compte "))
        assertFalse(canWrap(""))
    }

    @Test
    fun `several words, or a hyphen, can wrap`() {
        assertTrue(canWrap("Scanner locale"))
        assertTrue(canWrap("Local scanner"))
        assertTrue(canWrap("Faites-moi"))
    }
}
