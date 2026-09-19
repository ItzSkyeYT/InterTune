/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.engine

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Which titles are an advertisement for a song rather than the song. */
class SongTagsPreviewTest {

    @Test
    fun `a title that says preview in its qualifier is one`() {
        assertTrue(SongTags.isPreview("Heat Waves (Preview)"))
        assertTrue(SongTags.isPreview("Heat Waves [preview]"))
        assertTrue(SongTags.isPreview("Heat Waves - Preview"))
        assertTrue(SongTags.isPreview("Something (Snippet)"))
        assertTrue(SongTags.isPreview("Something (Official Teaser)"))
    }

    @Test
    fun `the body of a title is the song's name, not a claim about it`() {
        // The rule reads only the bracketed and dashed regions, so a song actually called this
        // survives. Same reasoning as the treatment tags.
        assertFalse(SongTags.isPreview("Preview"))
        assertFalse(SongTags.isPreview("Snippet of a Life"))
        assertFalse(SongTags.isPreview("Teaser"))
    }

    @Test
    fun `words that mean a real recording are left alone`() {
        // Every one of these is something somebody chooses to listen to on purpose.
        assertFalse(SongTags.isPreview("Heat Waves (Demo)"))
        assertFalse(SongTags.isPreview("Heat Waves (Intro)"))
        assertFalse(SongTags.isPreview("Heat Waves (Sample)"))
        assertFalse(SongTags.isPreview("Heat Waves (Live)"))
        assertFalse(SongTags.isPreview("Heat Waves"))
    }

    @Test
    fun `a preview is still a preview alongside a treatment`() {
        assertTrue(SongTags.isPreview("Heat Waves (Slowed + Reverb) (Preview)"))
        // and the treatment tags still read it the same way
        assertTrue("slowed" in SongTags.of("Heat Waves (Slowed + Reverb) (Preview)"))
    }
}
