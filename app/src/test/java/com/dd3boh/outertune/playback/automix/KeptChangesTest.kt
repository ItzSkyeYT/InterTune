/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.playback.automix

import com.dd3boh.outertune.mix.engine.StoredChange
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * The change the editor rendered on Save is kept (8 Nov 2026: "plays the transition from storage
 * instead of having to re-render it"): one file a pair, read back as written, gone on Forget, and
 * played by the playlist before anything is rendered there.
 */
class KeptChangesTest {

    @get:Rule
    val folder = TemporaryFolder()

    private fun change(spec: String) = StoredChange(
        spec = spec, onParts = true, rate = 48_000, channels = 2, aStartMs = 184_041.0, aBar1 = 1_000.0, bBar1 = 2_000.0,
        anchors = null, attenuationA = 0.8, attenuationB = 0.9, mixFrom = 10, bStart = 20, mixTo = 30,
        outFirst = 10, outSamples = ShortArray(8) { it.toShort() }, headStart = 10, headStep = 4, headValues = FloatArray(3) { 0.5f },
        inFirst = 20, inSamples = ShortArray(6) { (-it).toShort() }, inEntry = 0, inBeatFrames = 24_000,
        peakBefore = 1.1, peakAfter = 0.97, aProbeFrom = 5, aProbe = ShortArray(4) { 7 }, bProbeFrom = 6, bProbe = ShortArray(4) { 9 },
    )

    @Test
    fun `a kept change reads back as written, one file a pair, and goes on Forget`() {
        val kept = KeptChanges(folder.root)
        assertNull(kept.get("am-i-dreaming", "starboy"))
        assertTrue(kept.put("am-i-dreaming", "starboy", change("spec 1")))
        assertTrue(kept.put("starboy", "am-i-dreaming", change("spec 2")))
        val back = kept.get("am-i-dreaming", "starboy") ?: throw AssertionError("not kept")
        assertEquals("spec 1", back.spec)
        assertTrue(back.onParts)
        assertEquals(184_041.0, back.aStartMs, 0.0)
        assertEquals((0 until 8).map { it.toShort() }, back.outSamples.toList())
        assertEquals("spec 2", kept.get("starboy", "am-i-dreaming")!!.spec)
        // Saved again: the newer one.
        assertTrue(kept.put("am-i-dreaming", "starboy", change("spec 3")))
        assertEquals("spec 3", kept.get("am-i-dreaming", "starboy")!!.spec)
        assertTrue(kept.delete("am-i-dreaming", "starboy"))
        assertNull(kept.get("am-i-dreaming", "starboy"))
        assertFalse("nothing half-written is left", kept.dir.listFiles()!!.any { it.name.endsWith(".new") })
    }

    @Test
    fun `the editor keeps a pair's change again only where it was kept for another spec, or on bands with parts now at hand`() {
        val kept = KeptChanges(folder.root)
        val spec = com.dd3boh.outertune.mix.edit.TransitionSpec(
            a = com.dd3boh.outertune.mix.edit.Anchor("am-i-dreaming", 230_000.0, 90.0), b = com.dd3boh.outertune.mix.edit.Anchor("starboy", 250.0, 93.0),
            lanes = com.dd3boh.outertune.mix.edit.LaneSet.BANDS, bars = 8, beatMatch = false,
            preset = com.dd3boh.outertune.mix.edit.Preset.CUSTOM, moves = emptyMap(),
        )
        assertFalse(kept.holds("am-i-dreaming", "starboy", spec, parts = false))
        kept.put("am-i-dreaming", "starboy", change(KeptChanges.written(spec)))
        assertTrue("kept on parts, for this spec", kept.holds("am-i-dreaming", "starboy", spec, parts = true))
        assertFalse("another spec", kept.holds("am-i-dreaming", "starboy", spec.copy(bars = 4), parts = true))
        kept.put("am-i-dreaming", "starboy", StoredChange(
            KeptChanges.written(spec), false, 48_000, 2, 0.0, 0.0, 0.0, null, 1.0, 1.0, 0, 0, 0,
            0, ShortArray(0), 0, 1, FloatArray(0), 0, ShortArray(0), 0, 0, 0.0, 0.0, 0, ShortArray(0), 0, ShortArray(0),
        ))
        assertTrue("on bands, no parts at hand", kept.holds("am-i-dreaming", "starboy", spec, parts = false))
        assertFalse("on bands, the parts at hand now", kept.holds("am-i-dreaming", "starboy", spec, parts = true))
    }

    @Test
    fun `only the newest are kept`() {
        val kept = KeptChanges(folder.root)
        for (i in 0 until KeptChanges.MAX_FILES + 3) {
            assertTrue(kept.put("song-$i", "next", change("spec $i")))
            kept.file("song-$i", "next").setLastModified(1_000_000L * (i + 1))
        }
        kept.put("song-last", "next", change("spec last"))
        assertEquals(KeptChanges.MAX_FILES, kept.dir.listFiles { f -> f.name.endsWith(".bin") }!!.size)
        assertNull("the oldest went", kept.get("song-0", "next"))
        assertNotNull(kept.get("song-last", "next"))
    }

    @Test
    fun `the playlist plays the kept change before it renders anything, and Save and Forget keep it in step`() {
        val controller = File("src/main/java/com/dd3boh/outertune/playback/automix/AutomixController.kt").readText()
        val saved = controller.substringAfter("latestSaved?.let { saved ->")
        assertTrue("the kept change is tried first", saved.indexOf("kept.get(t.a.id, b.id)") in 0 until saved.indexOf("PartsForPlay.lineUp("))
        assertTrue(saved, "KeptChanges.written(saved), saved, ring, frames, t.tailStartMs.toDouble(), copy, t.copyFrames" in saved)
        val service = File("src/main/java/com/dd3boh/outertune/playback/MusicService.kt").readText()
        assertTrue("kept where backups do not go", "keptChanges = KeptChanges(noBackupFilesDir)" in service)
        val vm = File("src/main/java/com/dd3boh/outertune/ui/screens/mix/MixEditorViewModel.kt").readText()
        val write = vm.substringAfter("private fun write(action: SaveAction)").substringBefore("}.onFailure")
        assertTrue("Forget drops the kept render", "KeptChanges(context.noBackupFilesDir).delete(action.pair.from, action.pair.to)" in write)
        val save = vm.substringAfter("fun save(done: (Boolean) -> Unit) {").substringBefore("\n    }\n")
        assertTrue(save, "if (ok) keepIfStale()" in save)
        val cleared = vm.substringAfter("override fun onCleared() {").substringBefore("\n    }\n")
        assertTrue("leaving the page keeps the change as rendered", "keepRendered(pair)" in cleared)
        val load = vm.substringAfter("phase.value = Phase.Ready").substringBefore("} catch (e: CancellationException)")
        assertTrue("a saved pair's change is kept when the page opens it, where it is not yet", "holds(fromId, toId, stored, loaded.partsReady)" in load && "keepRendered(SongPair(fromId, toId))" in load)
        val keep = vm.substringAfter("private fun keepRendered(").substringBefore("\n    }\n")
        assertTrue("rendered past the page, which Save closes", "KeptChanges.scope.launch { keepRender(context, loaded, levelA, levelB, saved) }" in keep)
        val render = File("src/main/java/com/dd3boh/outertune/ui/screens/mix/KeptRender.kt").readText()
        assertTrue("rendered from what the playlist will read", "MixStores.transitions(context).get(saved)" in render)
        assertTrue("at the levels the page plays", "Preview.render(heard, loaded.aCopy, loaded.bCopy, loaded.a.analysis, levelA, levelB)" in render)
        assertTrue("on parts where the page has them", "val heard = if (loaded.partsReady) Presets.onParts(spec) else spec" in render)
    }
}
