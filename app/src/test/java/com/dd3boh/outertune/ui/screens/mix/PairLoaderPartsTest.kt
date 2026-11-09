/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.ui.screens.mix

import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import com.dd3boh.outertune.mix.edit.BeatFix
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.ShortBuffer
import java.util.concurrent.CancellationException
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * The editor splits a window into parts as it opens a pair, where the parts store has none and the
 * phone can (the debug app with the model, PartsMaker): the loading line says how far it is, the
 * pair opens on parts, a split done is kept for next time, and without a maker, on a failure or
 * when the page is closed, it opens on bands as before or stops. A stand-in splits here: the real
 * one runs ONNX Runtime, arm64 only (the :parts module's tests hold it to the PC).
 */
@UnstableApi
class PairLoaderPartsTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val rate = 44_100
    private val aInfo = WindowInfo(rate, 2, startMs = 170_000.0, frames = 30L * rate, durationMs = 200_000.0)
    private val bInfo = WindowInfo(rate, 2, startMs = 0.0, frames = 30L * rate, durationMs = 180_000.0)

    /** Kicks on the beat and a low note under them, [seconds] long, stereo. */
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
            k++
        }
        for (i in 0 until n) mix[i] += (0.08 * sin(2 * PI * 55.0 * i / rate)).toFloat()
        return ShortArray(n * 2) { (mix[it / 2].coerceIn(-1f, 1f) * 32000).toInt().toShort() }
    }

    private fun cache(): EditorCache = EditorCache(tmp.root, "s", "s").also {
        if (!it.files("songA", "tail").first.exists()) {
            keep(it, "songA", "tail", aInfo, beat(30.0, 124.0))
            keep(it, "songB", "head", bInfo, beat(30.0, 126.0))
        }
    }

    private fun keep(cache: EditorCache, id: String, end: String, info: WindowInfo, samples: ShortArray) {
        val (pcm, json, bands) = cache.files(id, end)
        cache.dir.mkdirs()
        val bytes = ByteBuffer.allocate(samples.size * 2).order(ByteOrder.LITTLE_ENDIAN)
        bytes.asShortBuffer().put(samples)
        pcm.writeBytes(bytes.array())
        json.writeText(info.toText())
        BandMeter(info.sampleRate, info.channels).apply { push(samples, samples.size / info.channels) }.result().write(bands)
    }

    private val noRead = DataSource.Factory { error("the windows are in the cache; nothing is read") }

    private val store get() = PartsStore(File(tmp.root, "mix"))

    /** Splits as the real one hands its files over: a quarter of the window to each of three parts, melody the rest, written aside and put in place. */
    private open inner class Stand : PartsMaker {
        val splits = ArrayList<String>()
        var closed = 0

        override fun split(window: ShortBuffer, rate: Int, channels: Int, targets: List<File>, progress: (Double) -> Unit, cancelled: () -> Boolean): Boolean {
            splits += "${targets.first().name.substringBefore('.')} $rate $channels ${window.limit()}"
            val n = window.limit()
            val parts = Array(4) { ShortArray(n) }
            for (i in 0 until n) {
                val w = window.get(i).toInt()
                val q = (w * 0.25).roundToInt()
                parts[0][i] = q.toShort()
                parts[1][i] = q.toShort()
                parts[2][i] = q.toShort()
                parts[3][i] = (w - 3 * q).toShort()
            }
            for (step in 1..4) progress(step / 4.0)
            for ((k, f) in targets.withIndex()) {
                val temp = File(f.path + ".part")
                temp.parentFile.mkdirs()
                val bytes = ByteBuffer.allocate(n * 2).order(ByteOrder.LITTLE_ENDIAN)
                bytes.asShortBuffer().put(parts[k])
                temp.writeBytes(bytes.array())
                if (!temp.renameTo(f)) throw IOException("could not put ${f.name} in place")
            }
            return true
        }

        override fun close() {
            closed++
        }
    }

    private class Run(val pair: LoadedPair?, val stages: List<PairLoader.Stage>, val progress: List<Double>, val error: Throwable?)

    private fun load(parts: (() -> PartsMaker?)?, cancelled: () -> Boolean = { false }): Run {
        val stages = ArrayList<PairLoader.Stage>()
        val progress = ArrayList<Double>()
        val result = runCatching {
            PairLoader(noRead, cache(), parts = parts).load(
                PairLoader.SongInput("songA", "A", aInfo.durationMs, null, BeatFix.NONE),
                PairLoader.SongInput("songB", "B", bInfo.durationMs, null, BeatFix.NONE),
                stage = { stages += it }, cancelled = cancelled, splitting = { progress += it },
            )
        }
        return Run(result.getOrNull(), stages, progress, result.exceptionOrNull())
    }

    private val samples = aInfo.frames * aInfo.channels

    @Test
    fun `a window with no parts is split as the pair loads, and the pair opens on parts`() {
        val stand = Stand()
        var opened = 0
        val run = load({ opened++; stand })
        val pair = run.pair!!
        assertTrue(pair.partsReady)
        assertEquals(1, opened)
        assertEquals(1, stand.closed)
        // Both windows, at their rate and channels and whole, into the store's names.
        val a = EditorCache.digest("songA")
        val b = EditorCache.digest("songB")
        assertEquals(listOf("$a-tail 44100 2 $samples", "$b-head 44100 2 $samples"), stand.splits)
        for (name in PartsStore.NAMES) {
            assertEquals(samples * 2, File(store.dir, "$a-tail.$name.pcm").length())
            assertEquals(samples * 2, File(store.dir, "$b-head.$name.pcm").length())
        }
        assertTrue(store.dir.list()!!.none { it.endsWith(".part") })
        // The loading line: the split between the reading and the beats, from 0 to all of both windows.
        assertEquals(listOf(PairLoader.Stage.READING_A, PairLoader.Stage.READING_B, PairLoader.Stage.SPLITTING, PairLoader.Stage.ANALYSING), run.stages)
        assertEquals(0.0, run.progress.first(), 0.0)
        assertEquals(1.0, run.progress.last(), 1e-12)
        assertEquals(0.5, run.progress[4], 1e-12)
        assertTrue(run.progress.zipWithNext().all { (x, y) -> y >= x })

        // Kept for next time: the next opening splits nothing, and opens no model.
        val again = load({ opened++; Stand() })
        assertTrue(again.pair!!.partsReady)
        assertEquals(1, opened)
        assertFalse(PairLoader.Stage.SPLITTING in again.stages)
    }

    @Test
    fun `only the window without parts is split`() {
        Stand().apply { split(ShortBuffer.wrap(ShortArray(samples.toInt())), rate, 2, store.files("songA", "tail"), {}, { false }) }
        val stand = Stand()
        val run = load({ stand })
        assertTrue(run.pair!!.partsReady)
        assertEquals(listOf("${EditorCache.digest("songB")}-head 44100 2 $samples"), stand.splits)
    }

    @Test
    fun `without a maker, or without the model, the pair opens on bands as before`() {
        for (parts in listOf<(() -> PartsMaker?)?>(null, { null })) {
            val run = load(parts)
            assertFalse(run.pair!!.partsReady)
            assertEquals(listOf(PairLoader.Stage.READING_A, PairLoader.Stage.READING_B, PairLoader.Stage.ANALYSING), run.stages)
            assertTrue(run.progress.isEmpty())
        }
    }

    @Test
    fun `a failed split leaves the pair on bands, logged, and is tried again at the next opening`() {
        val failing = object : Stand() {
            override fun split(window: ShortBuffer, rate: Int, channels: Int, targets: List<File>, progress: (Double) -> Unit, cancelled: () -> Boolean): Boolean =
                throw IllegalStateException("no memory for the model")
        }
        val run = load({ failing })
        assertTrue(run.error?.toString(), run.pair != null)
        assertFalse(run.pair!!.partsReady)
        assertEquals(1, failing.closed)
        assertTrue(store.dir.list().orEmpty().isEmpty())
        val stand = Stand()
        assertTrue(load({ stand }).pair!!.partsReady)
        assertEquals(2, stand.splits.size)
    }

    @Test
    fun `closing the page stops the split and the load, and keeps a window already split`() {
        var closedPage = false
        val stand = object : Stand() {
            override fun split(window: ShortBuffer, rate: Int, channels: Int, targets: List<File>, progress: (Double) -> Unit, cancelled: () -> Boolean): Boolean {
                if (splits.isEmpty()) return super.split(window, rate, channels, targets, progress, cancelled)
                // The page is closed while the second window splits: the splitter stops between segments.
                closedPage = true
                splits += "stopped"
                return !cancelled()
            }
        }
        val run = load({ stand }, cancelled = { closedPage })
        assertTrue(run.error is CancellationException)
        assertEquals(1, stand.closed)
        assertTrue(store.has("songA", "tail", samples))
        assertFalse(store.has("songB", "head", samples))
        // The next opening splits only what is left.
        val next = Stand()
        assertTrue(load({ next }).pair!!.partsReady)
        assertEquals(listOf("${EditorCache.digest("songB")}-head 44100 2 $samples"), next.splits)
    }

    @Test
    fun `a window split into parts stays with them when four newer pairs come, and a split cut short is cleared after an hour`() {
        val cache = cache()
        Stand().split(ShortBuffer.wrap(ShortArray(samples.toInt())), rate, 2, store.files("songA", "tail"), {}, { false })
        val now = System.currentTimeMillis()
        val split = cache.files("songA", "tail").first
        (store.files("songA", "tail") + split).forEach { it.setLastModified(now - 60 * 60_000) }
        // B's opening, never split, is the oldest window without parts; nine newer ones push it out,
        // and the oldest of them, as before.
        val plain = cache.files("songB", "head").first.apply { setLastModified(now - 30 * 60_000) }
        val newer = (0..EditorCache.KEPT).map { k -> cache.files("song$k", "head").first.apply { writeBytes(ByteArray(40)); setLastModified(now - k * 1000L) } }
        // Parts whose window the system cleared can never be lined up again.
        val orphans = store.files("gone", "head").onEach { it.writeBytes(ByteArray(40)) }
        val stale = File(store.dir, "x-head.vocals.pcm.part").apply { writeText("cut short"); setLastModified(now - PartsStore.STALE_MS - 60_000) }
        val fresh = File(store.dir, "y-head.vocals.pcm.part").apply { writeText("being written") }
        cache.prune(free = 1L shl 40)
        assertTrue(split.exists())
        assertTrue(store.has("songA", "tail", samples))
        assertFalse(plain.exists())
        assertEquals(List(EditorCache.KEPT) { true } + false, newer.map { it.exists() })
        assertTrue(orphans.none { it.exists() })
        assertFalse(stale.exists())
        assertTrue(fresh.exists())
    }

    @Test
    fun `windows split into parts go oldest first only past their room, and never below four pairs`() {
        val cache = EditorCache(tmp.root, "s", "s")
        cache.dir.mkdirs()
        val now = System.currentTimeMillis()
        // Ten windows of 100 bytes, each with its four parts: 500 bytes a window, the newest first.
        val ids = (0 until 10).map { k ->
            cache.files("song$k", "tail").first.apply { writeBytes(ByteArray(100)); setLastModified(now - k * 60_000L) }
            store.files("song$k", "tail").forEach { it.parentFile!!.mkdirs(); it.writeBytes(ByteArray(100)) }
            "song$k"
        }
        fun kept() = ids.filter { cache.files(it, "tail").first.exists() }
        fun partsLeft() = ids.filter { id -> store.files(id, "tail").any { it.exists() } }
        // A quarter of the space there would be without them: 13 000 bytes left and their 5 000.
        assertEquals(4_500L, EditorCache.partsRoom(13_000, 5_000))
        cache.prune(free = 13_000)
        assertEquals(ids.take(9), kept())
        assertEquals(ids.take(9), partsLeft())
        // No room left at all: the newest four pairs' windows stay all the same, as before.
        cache.prune(free = 0)
        assertEquals(ids.take(EditorCache.KEPT), kept())
        assertEquals(ids.take(EditorCache.KEPT), partsLeft())
    }

    @Test
    fun `going round a Mix playlist of more than four changes splits each window once`() {
        // His report of 9 Nov 2026, "IT KEEPS SPLITTING PARTS EVEN THOUGH IT ALREADY DIDDDDDD": the
        // editor kept four pairs' windows and let each window's parts go with it, so a playlist of
        // eight songs had its first changes split again every time round, a minute a window.
        val songs = (0 until 7).map { "s$it" }
        val cache = EditorCache(tmp.root, "s", "s")
        val seconds = 12.0
        val tail = WindowInfo(rate, 1, startMs = 188_000.0, frames = (seconds * rate).toLong(), durationMs = 200_000.0)
        val head = WindowInfo(rate, 1, startMs = 0.0, frames = (seconds * rate).toLong(), durationMs = 200_000.0)
        fun mono(bpm: Double) = beat(seconds, bpm).let { st -> ShortArray(st.size / 2) { st[2 * it] } }
        val stand = Stand()
        var reads = 0
        for (round in 1..2) for ((a, b) in songs.zipWithNext()) {
            // A window the cache let go is read again, as the editor reads it.
            if (!cache.files(a, "tail").first.exists()) keep(cache, a, "tail", tail, mono(120.0)).also { reads++ }
            if (!cache.files(b, "head").first.exists()) keep(cache, b, "head", head, mono(122.0)).also { reads++ }
            val pair = PairLoader(noRead, cache, parts = { stand }).load(
                PairLoader.SongInput(a, a, tail.durationMs, null, BeatFix.NONE),
                PairLoader.SongInput(b, b, head.durationMs, null, BeatFix.NONE),
                stage = {}, cancelled = { false },
            )
            assertTrue("$a to $b, round $round", pair.partsReady)
        }
        // Each window read and split once: the first song's end, the last one's opening, both of the others.
        assertEquals(12, reads)
        assertEquals(12, stand.splits.size)
        assertEquals(stand.splits.distinct(), stand.splits)
    }

    @Test
    fun `a window opened again is kept as fresh as its parts`() {
        load({ Stand() })
        val hourAgo = System.currentTimeMillis() - 60 * 60_000
        val files = listOf("songA" to "tail", "songB" to "head").flatMap { (id, end) -> cache().files(id, end).toList() + store.files(id, end) }
        files.forEach { it.setLastModified(hourAgo) }
        assertTrue(load({ Stand() }).pair!!.partsReady)
        assertTrue(files.all { it.lastModified() > hourAgo + 30 * 60_000 })
    }

    @Test
    fun `a window read again drops the parts made from the one before, and the editor asks for its maker`() {
        val audio = File("src/main/java/com/dd3boh/outertune/ui/screens/mix/EditorAudio.kt").readText()
        val read = audio.substring(audio.indexOf("private fun window(song: SongInput"))
        val decode = read.indexOf("WindowDecoder(source).decode(")
        assertTrue(read.indexOf("PartsStore(cache.dir.parentFile!!).delete(song.id, end)") in 0 until decode)
        val model = File("src/main/java/com/dd3boh/outertune/ui/screens/mix/MixEditorViewModel.kt").readText()
        assertTrue("parts = { PartsMaker.open(context) }," in model)
        assertTrue("splitting = { phase.value = Phase.Loading(PairLoader.Stage.SPLITTING, it) }," in model)
        // Both loading lines say it.
        for (screen in listOf("MixEditorScreen.kt", "MixSimpleSheet.kt")) {
            val text = File("src/main/java/com/dd3boh/outertune/ui/screens/mix/$screen").readText()
            assertTrue(screen, "PairLoader.Stage.SPLITTING -> words.splitting(phase.progress)" in text)
        }
    }

    @Test
    fun `its words are short, with the share done`() {
        fun value(path: String) = Regex("""<string name="mix_edit_splitting">(.*?)</string>""").find(File("src/main/res/$path/strings-mix.xml").readText())!!.groupValues[1]
        assertEquals("Splitting parts · %1\$d%%", value("values"))
        assertEquals("Séparation des pistes · %1\$d %%", value("values-fr"))
    }
}
