/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.recap

import org.junit.Assert.assertEquals
import org.junit.Test

class RecapPieceTest {
    @Test
    fun `a third of the way in, as long as the slide`() {
        assertEquals(RecapPiece(60_000, 10_000), RecapPiece.of(180, 10_000))
    }

    @Test
    fun `never shorter than six seconds, and the slide that stays takes twelve`() {
        assertEquals(RecapPiece(60_000, 6_000), RecapPiece.of(180, 2_000))
        assertEquals(RecapPiece(60_000, 12_000), RecapPiece.of(180, 0))
    }

    @Test
    fun `a short song plays from where the piece still fits, and a tiny one whole`() {
        assertEquals(RecapPiece(3_000, 12_000), RecapPiece.of(15, 12_000))
        assertEquals(RecapPiece(0, 4_000), RecapPiece.of(4, 12_000))
    }

    @Test
    fun `a song of unknown length is taken for three minutes`() {
        assertEquals(RecapPiece(60_000, 10_000), RecapPiece.of(-1, 10_000))
    }
}
