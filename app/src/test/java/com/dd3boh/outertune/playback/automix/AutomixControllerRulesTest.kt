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
        assertTrue("SongResolver(streamAddresses).also { if (Unreleased.AUTOMIX) it.fetches = OneFetchAtATime() }" in service)
        assertTrue("val one = fetches ?: return fetch(mediaId, position, staleQuality, partialCopy)" in service)
    }

    @Test
    fun `shuffled playback mixes by Auto alone, as the queue's line says, and never by a saved change`() {
        // Pills.of(shuffled = true) names Auto's pick for the queue's line, so a saved change, a
        // plain one included, is read for neither the held end nor the change on bands.
        val read = body(controller, "private fun readHead(")
        assertTrue(read, "t.shuffled = player.shuffleModeEnabled" in read)
        assertTrue(read, "val saved = if (lanes && !shuffled)" in read)
        val bands = controller.substringAfter("private fun onBands(").substringBefore("Presets.onBands(")
        assertTrue(bands, "val latest = if (t.shuffled) Result.success(null) else runCatching { savedChange(t.a.id, b.id) }" in bands)
        // Nothing else reads the saved changes.
        assertTrue(Regex("""savedChange\(""").findAll(controller).count() == 2)
    }

    @Test
    fun `a plain change saved while the end is held is heard plain, not mixed by Auto`() {
        // The end is held before the store is read again on the worker: a plain change saved by
        // then hands the stage a gapless plan, as the queue's line says, instead of Auto's mix.
        val bands = controller.substringAfter("private fun onBands(").substringBefore("val allowance = ")
        val plain = bands.substringAfter("if (latestSaved?.plain == true) {").substringBefore("\n        }\n")
        assertTrue(plain, "stage.offer(held, MixPlan.gapless(" in plain && "replaces = true)" in plain)
        assertTrue(plain, "auto = AutoChoice.Plain(PlainReason.YOUR_CHOICE)" in plain)
        assertFalse(bands, ".takeIf { !it.plain }" in bands)
    }

    @Test
    fun `only a queue a tap started is linked to its Mix playlist`() {
        val play = service.substringAfter("fun playQueue(").substringBefore("private val queueStart = QueueStart(")
        assertTrue(play, "val started = try {" in play)
        assertTrue(play, "if (Unreleased.AUTOMIX_EDITOR && started) linkMixQueue((queue as? ListQueue)?.mixPlaylistId)" in play)
        assertTrue(Regex("""linkMixQueue\(""").findAll(service).count() == 2)
    }
}
