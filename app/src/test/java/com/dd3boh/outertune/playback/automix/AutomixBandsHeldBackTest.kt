/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.playback.automix

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Changes on lows, mids and highs, and saved changes, play only under Unreleased.AUTOMIX_EDITOR:
 * without it the controller works as the prototype did, reads no saved change and copies nothing.
 */
class AutomixBandsHeldBackTest {

    private val sources = File("src/main/java/com/dd3boh/outertune").takeIf { it.isDirectory } ?: File("app/src/main/java/com/dd3boh/outertune")
    private val service by lazy { File(sources, "playback/MusicService.kt").readText() }
    private val controller by lazy { File(sources, "playback/automix/AutomixController.kt").readText() }

    @Test
    fun `the service plays on bands and reads saved changes only under the editor's flag`() {
        assertTrue("lanes = Unreleased.AUTOMIX_EDITOR," in service)
        // Through the one store the editor saves to, never a second one on the same file.
        assertTrue(
            "savedChange = { from, to -> if (Unreleased.AUTOMIX_EDITOR) MixStores.transitions(this@MusicService).get(SongPair(from, to)) else null }," in service,
        )
        assertTrue("beatFix = { id -> if (Unreleased.AUTOMIX_EDITOR) MixStores.beatFixes(this@MusicService).get(id) else null }," in service)
        assertTrue("analysed = { id, end -> if (Unreleased.AUTOMIX_EDITOR) MixStores.analysed(this@MusicService, id, end) }," in service)
        assertTrue("JsonFileTransitionStore" !in service)
    }

    @Test
    fun `without bands the controller reads, copies and plans as before`() {
        assertTrue("private val lanes: Boolean = false," in controller)
        assertTrue("val saved = if (lanes) withContext(Dispatchers.IO) { runCatching { savedChange(aId, b.id) }.getOrNull() } else null" in controller)
        assertTrue("val copySeconds = if (lanes) copySecondsFor(saved) else 0.0" in controller)
        assertTrue("if (!lanes) heads[b.id]?.let {" in controller)
        assertTrue("val onBands = if (lanes && c.soundBeforeMix) {" in controller)
        assertTrue("} else if (lanes && !t.abandoned.get()) {" in controller)
    }

    @Test
    fun `with the boost, the equalizer or spatial audio before the mixer, a change stays off bands`() {
        assertTrue(
            "soundBeforeMix = gainProcessor.gain > 1f || !equalizerProcessor.settings.isFlat || binauralProcessor.enabled || spatialUpmixProcessor.enabled," in service,
        )
    }
}
