/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.engine

import com.dd3boh.outertune.viewmodels.ExclusionsViewModel
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * A song YouTube no longer serves reaches the row as a song that cannot be played, by its id
 * alone, and not as a ban, which would take its other uploads with it: another upload is what
 * should be offered in its place.
 */
class EngineLoaderGoneTest {
    @Test
    fun `a gone song is taken out of the exclusions and returned by id, everything else is passed on`() {
        val rows = listOf(
            EngineExclusionRow(kind = 1, targetId = "BANNED00001", label = "A", reason = ExclusionsViewModel.REASON_BAN),
            EngineExclusionRow(kind = 1, targetId = "GONE0000001", label = "B", reason = ExclusionsViewModel.REASON_GONE),
            EngineExclusionRow(kind = 2, targetId = "ARTIST00001", label = "C", reason = ExclusionsViewModel.REASON_SNOOZE),
            EngineExclusionRow(kind = 1, targetId = "RESTED00001", label = "D", reason = ExclusionsViewModel.REASON_REST),
            EngineExclusionRow(kind = 1, targetId = "GONE0000002", label = "E", reason = ExclusionsViewModel.REASON_GONE),
        )

        val (gone, exclusions) = EngineLoader.splitGone(rows)

        assertEquals(setOf("GONE0000001", "GONE0000002"), gone)
        assertEquals(
            listOf(ExclusionRow(1, "BANNED00001"), ExclusionRow(2, "ARTIST00001"), ExclusionRow(1, "RESTED00001")),
            exclusions,
        )
    }

    @Test
    fun `the engine and the settings page mean the same reason`() {
        assertEquals(ExclusionsViewModel.REASON_GONE, EngineLoader.REASON_GONE)
    }
}
