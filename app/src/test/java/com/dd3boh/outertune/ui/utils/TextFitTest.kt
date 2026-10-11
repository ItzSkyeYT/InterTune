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
 * A label that does not fit gets smaller before it is cut or broken, a heading before it takes a
 * second line, and the labels of one row all take the size of the one that needs the smallest.
 * What decides the size is here; the measuring itself needs a device.
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

    /** Setup's first heading: "Selamat datang di InterTune" is on one line from 24.5sp down. */
    @Test
    fun `a heading takes the largest size that puts it on one line`() {
        val sizes = sizesToTry(largest = 28f, smallest = 21f)
        assertEquals(28f, oneLineOrWhole(sizes, isOneLine = { true }, isWhole = { true }))
        assertEquals(24.5f, oneLineOrWhole(sizes, isOneLine = { it <= 24.5f }, isWhole = { true }))
        assertEquals(21f, oneLineOrWhole(sizes, isOneLine = { it <= 21f }, isWhole = { true }))
    }

    @Test
    fun `a heading too long for one line at its smallest goes on two at its own size`() {
        val sizes = sizesToTry(largest = 28f, smallest = 21f)
        assertEquals(28f, oneLineOrWhole(sizes, isOneLine = { false }, isWhole = { true }))
    }

    @Test
    fun `on two lines a heading still gets smaller before a word of it is broken`() {
        val sizes = sizesToTry(largest = 28f, smallest = 21f)
        assertEquals(23f, oneLineOrWhole(sizes, isOneLine = { false }, isWhole = { it <= 23f }))
    }

    /** Home's shortcuts in French at a large font size: only "Statistiques" has to shrink. */
    @Test
    fun `the labels of a row take the smallest size any of them needs`() {
        val row = SameTextSize()
        row.needs("Historique", 12f)
        row.needs("Statistiques", 10f)
        row.needs("Scanner locale", 12f)
        row.needs("Compte", 12f)
        assertEquals(10f, row.size)
    }

    @Test
    fun `a row nobody has measured yet holds no label down`() {
        assertEquals(12f, minOf(12f, SameTextSize().size))
    }

    @Test
    fun `a label measured again says what it needs now`() {
        val row = SameTextSize()
        row.needs("Historique", 12f)
        row.needs("Statistiques", 10f)
        row.needs("Statistiques", 11.5f)
        assertEquals(11.5f, row.size)
    }

    /** A tab taken off the bottom bar. */
    @Test
    fun `a label that is gone no longer holds the others down`() {
        val row = SameTextSize()
        row.needs("Accueil", 12f)
        row.needs("Bibliothèque", 9.5f)
        row.forget("Bibliothèque")
        assertEquals(12f, row.size)
    }
}
