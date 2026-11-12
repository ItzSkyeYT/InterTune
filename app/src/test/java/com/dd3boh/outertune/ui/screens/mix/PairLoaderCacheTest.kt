/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.ui.screens.mix

import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import com.dd3boh.outertune.mix.edit.BeatFix
import com.dd3boh.outertune.mix.edit.TransitionJson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * His ask of 6 Nov 2026: once a pair was analysed, going back to it should not analyse it again.
 * The editor keeps each window's analysis and Auto's pick for the pair; a second opening takes
 * both from the cache and opens on the same change, a beat fix has Auto pick again, and a change
 * to the Mix code analyses afresh. The stamp is the Mix code's, not the install's: a new build
 * whose Mix code is the same keeps what was found.
 */
@UnstableApi
class PairLoaderCacheTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val rate = 44_100

    /** A beat at [bpm]: a kick on every beat, a hat between, a bass note under each bar, [seconds] long. */
    private fun beat(seconds: Double, bpm: Double): ShortArray {
        val n = (seconds * rate).roundToInt()
        val mix = FloatArray(n)
        val beat = 60.0 / bpm
        var k = 0
        while (k * beat < seconds) {
            val at = (k * beat * rate).roundToInt()
            for (i in 0 until (0.15 * rate).roundToInt()) {
                if (at + i >= n) break
                val t = i.toDouble() / rate
                mix[at + i] += (0.7 * sin(2 * PI * (50 + 60 * exp(-t * 30)) * t) * exp(-t * 18)).toFloat()
            }
            val off = ((k + 0.5) * beat * rate).roundToInt()
            for (i in 0 until (0.03 * rate).roundToInt()) {
                if (off + i >= n) break
                val noise = ((i * 1103515245 + 12345) and 0xffff) / 32768.0 - 1.0
                mix[off + i] += (0.12 * noise * exp(-i.toDouble() / rate * 120)).toFloat()
            }
            k++
        }
        for (i in 0 until n) mix[i] += (0.08 * sin(2 * PI * 55.0 * i / rate)).toFloat()
        return ShortArray(n * 2) { (mix[it / 2].coerceIn(-1f, 1f) * 32000).toInt().toShort() }
    }

    /** A window as the editor keeps it, so the loader takes it from the cache instead of decoding. */
    private fun keep(cache: EditorCache, id: String, end: String, info: WindowInfo, samples: ShortArray) {
        val (pcm, json, bands) = cache.files(id, end)
        cache.dir.mkdirs()
        val bytes = ByteBuffer.allocate(samples.size * 2).order(ByteOrder.LITTLE_ENDIAN)
        bytes.asShortBuffer().put(samples)
        pcm.writeBytes(bytes.array())
        json.writeText(info.toText())
        BandMeter(info.sampleRate, info.channels).apply { push(samples, samples.size / 2) }.result().write(bands)
    }

    private val aInfo = WindowInfo(rate, 2, startMs = 170_000.0, frames = 30L * rate, durationMs = 200_000.0)
    private val bInfo = WindowInfo(rate, 2, startMs = 0.0, frames = 30L * rate, durationMs = 180_000.0)

    private fun cache(stamp: String) = EditorCache(tmp.root, stamp).also {
        if (!it.files("songA", "tail").first.exists()) {
            keep(it, "songA", "tail", aInfo, beat(30.0, 124.0))
            keep(it, "songB", "head", bInfo, beat(30.0, 126.0))
        }
    }

    private val noRead = DataSource.Factory { error("the windows are in the cache; nothing is read") }

    private fun load(cache: EditorCache, fixA: BeatFix = BeatFix.NONE): LoadedPair =
        PairLoader(noRead, cache).load(
            PairLoader.SongInput("songA", "A", aInfo.durationMs, null, fixA),
            PairLoader.SongInput("songB", "B", bInfo.durationMs, null, BeatFix.NONE),
            stage = {}, cancelled = { false },
        )

    @Test
    fun `a second opening takes the analysis and Auto's pick from the cache, and opens on the same change`() {
        val first = load(cache("build-1"))
        val took1 = first.took!!
        assertFalse(took1.analysisKept)
        assertFalse(took1.autoKept)
        // Something worth keeping: beats found on both, and a pick from Auto rather than its fallback.
        assertNotNull(first.a.grid)
        assertNotNull(first.b.grid)
        assertFalse(first.autoWhy, first.autoWhy.startsWith("no analysis"))

        val second = load(cache("build-1"))
        val took2 = second.took!!
        assertTrue(took2.toString(), took2.analysisKept)
        assertTrue(took2.toString(), took2.autoKept)
        assertEquals(TransitionJson.encode(first.auto), TransitionJson.encode(second.auto))
        assertEquals(first.a.grid, second.a.grid)
        assertEquals(first.b.grid, second.b.grid)
        for ((one, two) in listOf(first.a.key to second.a.key, first.b.key to second.b.key)) {
            assertEquals(one?.key, two?.key)
            assertEquals(one?.correlation ?: 0.0, two?.correlation ?: 0.0, 1e-9)
            assertEquals(one?.sure, two?.sure)
        }
        assertEquals(first.facts.a.beatConfidence, second.facts.a.beatConfidence, 0.0)
        assertEquals(first.facts.b.barConfidence, second.facts.b.barConfidence, 0.0)
    }

    @Test
    fun `a beat fix has Auto pick again on the kept analysis`() {
        load(cache("build-1"))
        val fixed = load(cache("build-1"), fixA = BeatFix(downbeatShift = 1))
        assertTrue(fixed.took!!.analysisKept)
        assertFalse(fixed.took!!.autoKept)
        // And that pick is kept in turn.
        assertTrue(load(cache("build-1"), fixA = BeatFix(downbeatShift = 1)).took!!.autoKept)
    }

    @Test
    fun `the editor keeps its finds under the Mix code's stamp, not the install's`() {
        val model = File("src/main/java/com/dd3boh/outertune/ui/screens/mix/MixEditorViewModel.kt").readText()
        assertTrue("EditorCache(context.cacheDir, MixCode.STAMP)" in model)
        assertFalse("lastUpdateTime" in model)
    }

    @Test
    fun `a change to the Mix code analyses afresh`() {
        load(cache("build-1"))
        val after = load(cache("build-2"))
        assertFalse(after.took!!.analysisKept)
        assertFalse(after.took!!.autoKept)
    }

    @Test
    fun `an analysis is only taken for the window it was made from`() {
        val cache = cache("build-1")
        load(cache)
        assertNotNull(cache.analysis("songA", "tail", aInfo))
        assertNull(cache.analysis("songA", "tail", aInfo.copy(startMs = aInfo.startMs + 500.0)))
        assertNull(cache.analysis("songA", "tail", aInfo.copy(frames = aInfo.frames - 1)))
        // A damaged file is analysed again rather than read.
        File(cache.analysisFile("songA", "tail").path).writeBytes(ByteArray(40))
        assertNull(cache.analysis("songA", "tail", aInfo))
        assertFalse(load(cache).took!!.analysisKept)
    }
}
