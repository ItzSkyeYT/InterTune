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
        // A seek the player really makes: one to the very millisecond it is at is dropped (seekInPlace).
        assertTrue(parameters, "if (stage.state == HeldTail.State.CAPTURE || stage.state == HeldTail.State.DRAIN) seekInPlace()" in parameters)
    }

    @Test
    fun `a change started over after a seek keeps everything read with the opening`() {
        // On the Pixel 5 (6 Nov) the debug app's trial lost its flag this way: its end had been
        // held once, the queue was started again and A sought, so the change started over without
        // knowing it was the trial; the change on bands looked for a change saved for the pair,
        // found none, and A played out whole. A shuffled queue lost its shuffle the same way, and
        // both songs the beats read from all of them.
        val seek = body(controller, "override fun onPositionDiscontinuity(")
        assertTrue(seek, "transition = old.startedOver(nextTransitionId++)" in seek)
        val over = body(controller, "fun startedOver(id: Long): Transition")
        for (field in listOf("head", "headFailed", "copy", "copyFrames", "saved", "trial", "shuffled", "gridA", "gridB", "armFromMs")) {
            assertTrue("$field is lost when the change starts over", "it.$field = $field" in over)
        }
    }

    @Test
    fun `a jump lets go of the change before it for good, and nothing of it reaches the change started over`() {
        // 8 Nov 2026: a plan worked out before a jump was refused after it ("the plan came after
        // the change"); one could have been taken by the end held after it.
        val seek = body(controller, "override fun onPositionDiscontinuity(")
        assertTrue(seek, "old.abandoned.set(true)" in seek)
        assertTrue(seek, "stage.withdrawArmsBefore(old.id + 1)" in seek)
        assertTrue(seek, "if (old.planning) stopWork()" in seek)
        assertTrue(seek, seek.indexOf("old.abandoned.set(true)") < seek.indexOf("transition = old.startedOver(nextTransitionId++)"))
        // Every plan handed to the stage names the end it was worked out on (HeldTail refuses it for another).
        val offers = Regex("""stage\.offer\(([^\n]*)""").findAll(controller).map { it.groupValues[1] }.toList()
        assertTrue(offers.toString(), offers.size >= 8)
        for (o in offers) assertTrue(o, "ring = ring)" in o)
        // The kept change goes over the jump with the rest, not read again.
        val over = body(controller, "fun startedOver(id: Long): Transition")
        for (field in listOf("keptFound", "kept", "changeLeftMs", "entrySeconds")) assertTrue(field, "it.$field = $field" in over)
    }

    @Test
    fun `a saved change kept as rendered plays as kept before anything is worked out, and its end is held until five seconds before the end`() {
        val plan = body(controller, "private fun plan(")
        assertTrue(plan, "if (kept) {" in plan && "playKept(t, held, ring, frames" in plan)
        assertTrue(plan, plan.indexOf("playKept(") < plan.indexOf("RegionAnalysis.analyse("))
        // Not as kept: the next song comes in where the change held for brings it in.
        assertTrue(plan, "if (t.entryOpen) stage.decideEntry(held, t.entryFrames, ring)" in plan)
        assertTrue("AutomixSchedule.due(left, t.armFromMs, holdUntil(t))" in controller)
        assertTrue(body(controller, "private fun holdUntil(").contains("AutomixSchedule.KEPT_UNTIL_LEFT_MS"))
        // Held too late to be heard whole, the next song's entry is the change's to say.
        val arm = body(controller, "private fun arm(")
        assertTrue(arm, "entryOpen = open" in arm)
        val kept = body(controller, "private fun playKept(")
        assertTrue(kept, "stored.replayFrom(" in kept && "StoredChange.EntryRule.Open(" in kept && "StoredChange.EntryRule.Fixed(t.entryFrames)" in kept)
        assertTrue(kept, "t.entryFrames = play.plan.bEntry" in kept)
    }

    @Test
    fun `a request to hold the end lost to the flush of a jump is asked again, never twice at once`() {
        // 8 Nov 2026 on the emulator: a jump to 30 s before the end, the ring handed over before the
        // jump's flush, which let the request go without a word, and the end was never held.
        val arm = body(controller, "private fun arm(")
        val handed = arm.substringAfter("stage.requestArm(")
        assertTrue(arm, "t.armAskedAtMs = SystemClock.elapsedRealtime()" in handed && "main.postDelayed({ armLost(t) }, ARM_LOST_MS)" in handed)
        val lost = body(controller, "private fun armLost(")
        assertTrue(lost, "!t.armRequested || t.holding || t.finished" in lost && "t.armRequested = false" in lost)
        // Longer than the stage waits for a request, so the one lost can no longer be taken.
        assertTrue(1_500L > 1_000L && "const val ARM_LOST_MS = 1_500L" in controller)
        val stage = code("playback/automix/AutomixAudioProcessor.kt")
        assertTrue("private const val ARM_WAIT_NANOS = 1_000_000_000L" in stage)
        // A request asked again and refused once the first was taken does not undo the end held.
        val note = body(controller, "override fun note(")
        assertTrue(note, "t.id == id && !t.holding && text.startsWith(\"not holding the end\")" in note)
    }

    @Test
    fun `a plan worked out for a change given up is not handed to the stage, and a dropped end is let go`() {
        assertTrue("t.abandoned.set(true)" in body(controller, "private fun abort("))
        assertTrue("if (!t.abandoned.get() && t.entryFrames == 0L) stage.offer(held, plan, rendered, ring = ring)" in controller)
        val dropped = controller.substringAfter("override fun dropped(id: Long)").substringBefore("\n    }\n")
        assertTrue(dropped, "t.finished = true" in dropped)
    }

    @Test
    fun `the player's resolver fetches a song once at a time only with AutoMix in the build`() {
        assertTrue("SongResolver(streamAddresses).also { if (Unreleased.AUTOMIX) it.fetches = OneFetchAtATime() }" in service)
        assertTrue("val one = fetches ?: return fetch(mediaId, position, staleQuality, partialCopy, said)" in service)
    }

    @Test
    fun `shuffled playback mixes by Auto alone, as the queue's line says, and never by a saved change`() {
        // Pills.of(shuffled = true) names Auto's pick for the queue's line, so a saved change, a
        // plain one included, is read for neither the held end nor the change on bands.
        val read = body(controller, "private fun readHead(")
        assertTrue(read, "t.shuffled = player.shuffleModeEnabled" in read)
        assertTrue(read, "val listeners = if (bands && !shuffled)" in read)
        assertTrue(read, "val trial = if (bands && !shuffled && listeners == null) {" in read)
        val bands = controller.substringAfter("private fun onBands(").substringBefore("Presets.onBands(")
        // Shuffled first: not even the debug app's forced trial plays for a shuffled queue.
        assertTrue(bands, "t.shuffled -> Result.success(null)" in bands)
        assertTrue(bands, bands.indexOf("t.shuffled -> Result.success(null)") < bands.indexOf("t.trial -> Result.success(t.saved)"))
        assertTrue(bands, "else -> runCatching { savedChange(t.a.id, b.id) }" in bands)
        // Nothing else reads the saved changes.
        assertTrue(Regex("""savedChange\(""").findAll(controller).count() == 2)
    }

    @Test
    fun `a plain change saved while the end is held is heard plain, not mixed by Auto`() {
        // The end is held before the store is read again on the worker: a plain change saved by
        // then hands the stage a gapless plan, as the queue's line says, instead of Auto's mix.
        val bands = controller.substringAfter("private fun onBands(").substringBefore("val allowance = ")
        val plain = bands.substringAfter("if (latestSaved?.plain == true) {").substringBefore("\n        }\n")
        assertTrue(plain, "stage.offer(held, MixPlan.gapless(" in plain && "replaces = true, ring = ring)" in plain)
        assertTrue(plain, "auto = AutoChoice.Plain(PlainReason.YOUR_CHOICE)" in plain)
        assertFalse(bands, ".takeIf { !it.plain }" in bands)
    }

    @Test
    fun `only a queue a tap starts is linked to its Mix playlist, before its first song starts`() {
        // QueueStart calls beforeStart only for the start that goes ahead (QueueStartTest).
        val play = service.substringAfter("fun playQueue(").substringBefore("private val queueStart = QueueStart(")
        assertTrue(play, "beforeStart = { if (Unreleased.AUTOMIX_EDITOR) linkMixQueue((queue as? ListQueue)?.mixPlaylistId) }," in play)
        assertTrue(Regex("""linkMixQueue\(""").findAll(service).count() == 2)
    }

    @Test
    fun `a decision said again is not a new value for the queue's line and the heard changes`() {
        // decide() runs at every look while a change is refused; StateFlow drops a value equal to
        // the last, so what is said again neither rewrites heard.json nor plans every pair again.
        assertTrue("data class Decided(val pair: SongPair, val choice: AutoChoice)" in controller)
        val pair = com.dd3boh.outertune.mix.edit.SongPair("a", "b")
        val plain = com.dd3boh.outertune.mix.pill.AutoChoice.Plain(com.dd3boh.outertune.mix.pill.PlainReason.RUN_TOGETHER)
        val flow = kotlinx.coroutines.flow.MutableStateFlow<AutomixController.Decided?>(AutomixController.Decided(pair, plain))
        val before = flow.value
        flow.value = AutomixController.Decided(pair, com.dd3boh.outertune.mix.pill.AutoChoice.Plain(com.dd3boh.outertune.mix.pill.PlainReason.RUN_TOGETHER))
        assertTrue(flow.value === before)
    }
}
