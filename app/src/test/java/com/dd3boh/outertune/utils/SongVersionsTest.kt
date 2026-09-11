/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.utils

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Titles taken from YouTube Music's related pages on 11 Sep 2026, not invented. */
class SongVersionsTest {

    @Test
    fun `every version of Take on Me the related page offered is caught`() {
        listOf(
            "Take on Me (1985 Single Mix) (1985 Single Mix; 2015 Remaster)", // in "You might also like"
            "Take On Me",
            "Take on Me (Symphonic Version)",
            "Take on Me (Electrobossa Mix)",
            "Take On Me (2017 Acoustic)",
        ).forEach { assertTrue(it, SongVersions.isVersionOf(it, "Take on Me")) }
    }

    @Test
    fun `a live recording with a long bracket is a version`() {
        assertTrue(
            SongVersions.isVersionOf(
                "Hotel California (Live at the Millennium Concert, Staples Center, Los Angeles, CA, 12/31/1999; 2018 Remaster)",
                "Hotel California",
            )
        )
    }

    @Test
    fun `other songs on the same related page are not versions`() {
        // "Every Breath You Take" contains "take", which a containment test would have caught out.
        listOf("Africa", "Every Breath You Take", "Time After Time", "Take Me Home", "Forever Young")
            .forEach { assertFalse(it, SongVersions.isVersionOf(it, "Take on Me")) }
    }

    @Test
    fun `the seed's own bracket does not stop its plain version matching`() {
        assertTrue(SongVersions.isVersionOf("Heart of Glass", "Heart Of Glass (Single Version / Remastered)"))
    }

    @Test
    fun `a spaced dash suffix is a qualifier`() {
        assertTrue(SongVersions.isVersionOf("Bohemian Rhapsody - Remastered 2011", "Bohemian Rhapsody"))
    }

    @Test
    fun `case and punctuation are ignored`() {
        assertTrue(SongVersions.isVersionOf("Stayin' Alive", "Stayin Alive"))
    }

    @Test
    fun `a title that is only brackets is nobody's version`() {
        // Both reduce to nothing; matching empty to empty would filter every untitled track.
        assertFalse(SongVersions.isVersionOf("(Untitled)", "(Intro)"))
    }

    @Test
    fun `versions in other scripts and full-width brackets are caught`() {
        assertTrue(SongVersions.isVersionOf("夜に駆ける（Live）", "夜に駆ける"))
        assertTrue(SongVersions.isVersionOf("夜に駆ける【MV】", "夜に駆ける"))
        assertTrue(SongVersions.isVersionOf("Кино (Live)", "Кино"))
    }

    @Test
    fun `different titles in other scripts are not versions`() {
        assertFalse(SongVersions.isVersionOf("クイーン", "キング"))
        // A title quoted in corner brackets keeps its words: stripping them would have emptied it.
        assertFalse(SongVersions.isVersionOf("「群青」", "「夜に駆ける」"))
        assertTrue(SongVersions.isVersionOf("「群青」", "「群青」"))
    }
}
