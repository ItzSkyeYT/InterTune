/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.playback.automix

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Rules of AutoMix's controller and of the service around it that only show on a phone, held to
 * the shape they were written in. This reads the source, as AutomixHeldBackTest does: the
 * controller listens to the phone's own player, which a test cannot build.
 */
class AutomixControllerRulesTest {

    private val sources = File("src/main/java/com/dd3boh/outertune")

    /** The text without its comments, so a sentence that names a call is not taken for the call. */
    private fun code(path: String) = File(sources, path).readText()
        .replace(Regex("""/\*.*?\*/""", RegexOption.DOT_MATCHES_ALL), "")
        .lines().joinToString("\n") { it.substringBefore("//") }

    private val controller by lazy { code("playback/automix/AutomixController.kt") }
    private val service by lazy { code("playback/MusicService.kt") }

    private fun body(text: String, header: String) = text.substringAfter(header).substringBefore("\n    }\n")

    @Test
    fun `with AutoMix switched off the levels follow the songs for as long as the stage is in the chain`() {
        // The song that starts under an end held as AutoMix is switched off is scaled by the stage,
        // and the volume leaves normalisation out: it must be scaled by its own level, not the last song's.
        for (handler in listOf("override fun onMediaItemTransition(", "override fun onPositionDiscontinuity(", "private fun refresh()")) {
            assertTrue(handler, "if (!enabled) return levelsWhileOff()" in body(controller, handler))
        }
        assertTrue("if (stage.activeForStream) publishLevels()" in body(controller, "private fun levelsWhileOff()"))
    }

    @Test
    fun `the song playing takes the level the service works out from its stored loudness as soon as it is written`() {
        assertTrue("now == null || c.normalizeNowId == now -> c.normalizeNow" in body(controller, "private fun publishLevels()"))
        val collector = service.substringAfter("normalizeFactor.value = Normalization.factor(format?.loudnessDb, isLocal, normalizeAudio)").substringBefore("\n            }\n")
        assertTrue(collector, "automixNormalizeId = format?.id" in collector)
        assertTrue(collector, "automix?.levelsChanged(normalizationSettingChanged = false)" in collector)
        assertTrue(collector, "if (Unreleased.AUTOMIX) {" in collector)
        assertTrue("normalizeNowId = automixNormalizeId," in service)
    }

    @Test
    fun `new playback parameters drop whatever the stage holds, whether AutoMix is on or not`() {
        val parameters = body(controller, "private fun parametersChanged(")
        assertFalse(parameters, "if (!enabled) return" in parameters)
        assertTrue(parameters, "if (stage.state == HeldTail.State.CAPTURE || stage.state == HeldTail.State.DRAIN) player.seekTo(player.currentPosition)" in parameters)
    }

    @Test
    fun `a plan worked out for a change given up is not handed to the stage, and a dropped end is let go`() {
        assertTrue("t.abandoned.set(true)" in body(controller, "private fun abort("))
        assertTrue("if (!t.abandoned.get()) stage.offer(held, plan, rendered)" in controller)
        val dropped = controller.substringAfter("override fun dropped(id: Long)").substringBefore("\n    }\n")
        assertTrue(dropped, "t.finished = true" in dropped)
    }

    @Test
    fun `the player's resolver fetches a song once at a time only with AutoMix in the build`() {
        assertTrue("val resolver = SongResolver(streamAddresses).also { if (Unreleased.AUTOMIX) it.fetches = OneFetchAtATime() }" in service)
        assertTrue("val one = fetches ?: return fetch(mediaId, position, staleQuality, partialCopy)" in service)
    }
}
