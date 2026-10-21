/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.ui.screens.mix

import com.dd3boh.outertune.mix.FrontEnd
import com.dd3boh.outertune.mix.PlanInput
import com.dd3boh.outertune.mix.Planner
import com.dd3boh.outertune.mix.RegionAnalysis
import com.dd3boh.outertune.mix.RegionKind
import com.dd3boh.outertune.mix.edit.Lane
import com.dd3boh.outertune.mix.edit.Preset
import com.dd3boh.outertune.mix.edit.Side
import com.dd3boh.outertune.mix.edit.toJson
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.DataOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.max

/**
 * Not a test: the editor's trial on the PC. Each two songs in a row in MIX_EDITOR_TRIAL_DIR go
 * through what the editor does with them on the phone, but for the decoding (ffmpeg here,
 * MediaCodec there): the bands, the analysis, the planner's Auto, the page's state, each bands
 * preset, and the preview's sound of Auto and of Fade at 8 bars, written as WAV files with a
 * summary into MIX_EDITOR_TRIAL_OUT. The songs never leave the PC; nothing of them is kept in git.
 *
 * Run with: MIX_EDITOR_TRIAL_DIR=<folder> ./gradlew :app:testCoreDebugUnitTest --tests '*MixEditorTrial' --rerun
 */
class MixEditorTrial {

    private val rate = 48_000

    private class Song(val title: String, val file: File, val durationMs: Double)

    @Test
    fun trial() {
        val dir = System.getenv("MIX_EDITOR_TRIAL_DIR")
        assumeTrue("set MIX_EDITOR_TRIAL_DIR to run", dir != null)
        val out = File(System.getenv("MIX_EDITOR_TRIAL_OUT") ?: "../../experiments/features/automix/editor-trial").apply { mkdirs() }
        val pairs = (System.getenv("MIX_EDITOR_TRIAL_PAIRS") ?: "4").toInt()
        val songs = File(dir!!).listFiles { f -> f.extension.lowercase() in setOf("mp3", "m4a", "opus", "flac", "ogg", "wav") }!!
            .sortedBy { it.name }
            .map { Song(it.nameWithoutExtension, it, probeMs(it)) }
            .filter { it.durationMs >= 90_000 }
        val summary = StringBuilder("# The editor on the PC\n\nEach pair as the editor opens it: Auto from the planner, every bands preset checked, the preview rendered.\n\n")
        for (i in 0 until minOf(pairs, songs.size - 1)) {
            val a = songs[i]
            val b = songs[i + 1]
            val started = System.nanoTime()
            val aWindow = decode(a, max(0.0, a.durationMs - PairFacts.REACH_A_MS), PairFacts.REACH_A_MS, File(out, "a.raw"))
            val bWindow = decode(b, 0.0, PairFacts.REACH_B_MS, File(out, "b.raw"))
            val decodedMs = (System.nanoTime() - started) / 1e6
            val t0 = System.nanoTime()
            val aBands = bands(aWindow)
            val bBands = bands(bWindow)
            val bandsMs = (System.nanoTime() - t0) / 1e6
            val t1 = System.nanoTime()
            val aAnalysis = analyse(aWindow, RegionKind.TAIL)
            val bAnalysis = analyse(bWindow, RegionKind.HEAD)
            val analysisMs = (System.nanoTime() - t1) / 1e6
            val aGrid = PairLoader.gridOf(aAnalysis, aWindow.info.startMs)
            val bGrid = PairLoader.gridOf(bAnalysis, 0.0)
            val bFirst = bAnalysis.envelope.firstSound * 1000.0 / rate
            val facts = PairFacts(
                a = SongFacts(a.title, a.title, a.durationMs, aWindow.info.startMs, aWindow.info.endMs, aGrid, focusMs = a.durationMs - PairLoader.FOCUS_BEFORE_END_MS),
                b = SongFacts(b.title, b.title, b.durationMs, 0.0, bWindow.info.endMs, bGrid, firstSoundMs = bFirst, focusMs = bFirst),
                active = { side, lane: Lane, ms -> (if (side == Side.A) aBands.active(lane, ms - aWindow.info.startMs) else bBands.active(lane, ms)) },
            )
            val plan = Planner.plan(PlanInput(tail = aAnalysis, head = bAnalysis, readMark = 0, aDurationMs = a.durationMs.toLong(), bDurationMs = b.durationMs.toLong(), tailStartMs = aWindow.info.startMs.toLong()))
            val auto = AutoSpec.of(plan, facts, aWindow.info.startMs)
            val e = MixEditor(facts, auto, null)
            val label = "%02d %s into %s".format(i + 1, safe(a.title).take(36), safe(b.title).take(36))
            summary.append("## $label\n\n")
            summary.append("- read %.0f ms, bands %.0f ms, analysis %.0f ms\n".format(decodedMs, bandsMs, analysisMs))
            summary.append("- A: ${aAnalysis.describe()}; B: ${bAnalysis.describe()}\n")
            summary.append("- planner: ${plan.describe()}\n")
            summary.append("- Auto: ${e.lit()} ${if (auto.bars > 0) "${auto.bars} bars" else "${auto.seconds} s"}, beat matched ${auto.beatMatched} (ratio %.4f), bar 1 at A %s, B %s, A loses %s\n".format(
                auto.tempoRatio(), clock(auto.a.atMs), clock(auto.b.atMs), clock(e.unheardMs())))
            summary.append("- moments: ${e.moments().joinToString("; ") { "%.2f %s %s %s".format(it.at, it.kind, it.lane ?: "", it.how ?: "") }}\n")
            val bad = ArrayList<String>()
            for (p in listOf(Preset.FADE, Preset.RISE, Preset.BLEND, Preset.ECHO_OUT, Preset.CUT, Preset.RIFF_RISE)) {
                e.tapPreset(p)
                if (!e.spec.isValid || e.lit() != p) bad += "$p: ${e.spec.problems()} lit ${e.lit()}"
            }
            summary.append("- presets: ${if (bad.isEmpty()) "all valid and lit" else bad.joinToString()}\n")
            summary.append("- lengths: ${e.lengthOptions().joinToString { "${if (it.bars > 0) "${it.bars} bars" else "${it.seconds} s"} ${if (it.refused == null) "ok" else "refused (${it.refused})"}" }}\n")
            e.backToAuto()
            render(File(out, "$label, auto.wav"), e, aWindow, bWindow)
            if (facts.barsMode) {
                e.tapPreset(Preset.FADE)
                if (e.spec.bars != 8) e.setLength(8)
                render(File(out, "$label, fade 8 bars.wav"), e, aWindow, bWindow)
            }
            File(out, "$label, auto.json").writeText(auto.toJson())
            summary.append("\n")
        }
        File(out, "a.raw").delete()
        File(out, "b.raw").delete()
        File(out, "SUMMARY.md").writeText(summary.toString())
        println(summary)
    }

    private fun probeMs(f: File): Double {
        val p = ProcessBuilder("ffprobe", "-v", "error", "-show_entries", "format=duration", "-of", "default=nw=1:nk=1", f.path).redirectErrorStream(true).start()
        val text = p.inputStream.bufferedReader().readText().trim()
        p.waitFor()
        return (text.toDoubleOrNull() ?: 0.0) * 1000
    }

    private fun decode(song: Song, fromMs: Double, lengthMs: Double, raw: File): PcmWindow {
        val p = ProcessBuilder(
            "ffmpeg", "-v", "error", "-y", "-ss", "%.3f".format(fromMs / 1000), "-i", song.file.path, "-t", "%.3f".format(lengthMs / 1000),
            "-f", "s16le", "-ac", "2", "-ar", rate.toString(), raw.path,
        ).redirectErrorStream(true).start()
        p.inputStream.readBytes()
        check(p.waitFor() == 0) { "ffmpeg failed on ${song.title}" }
        val bytes = raw.readBytes()
        val shorts = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
        return PcmWindow(WindowInfo(rate, 2, fromMs, (bytes.size / 4).toLong(), song.durationMs), shorts)
    }

    private fun bands(w: PcmWindow): BandEnvelopes {
        val meter = BandMeter(rate, 2)
        val block = ShortArray(8192 * 2)
        var at = 0
        while (at < w.frames) {
            val n = minOf(8192, w.frames - at)
            w.read(at, n, block)
            meter.push(block, n)
            at += n
        }
        return meter.result()
    }

    private fun analyse(w: PcmWindow, kind: RegionKind): RegionAnalysis {
        val front = FrontEnd(rate, 2, filteredPeaks = kind == RegionKind.HEAD)
        val block = ShortArray(8192 * 2)
        var at = 0
        while (at < w.frames) {
            val n = minOf(8192, w.frames - at)
            w.read(at, n, block)
            front.push(block, 0, n)
            at += n
        }
        return RegionAnalysis.analyse(front.result(), kind)
    }

    /** The preview's loop, from 2 bars before the change to 2 after, as the phone plays it. */
    private fun render(file: File, e: MixEditor, a: PcmWindow, b: PcmWindow) {
        val spec = e.spec
        val frames = ((e.fitEnd() - e.fitStart()) * spec.b.barMs / 1000 * rate).toInt()
        val pcm = ShortArray(frames * 2)
        PreviewMixer(a, b).render(spec, e.fitStart(), rate, frames, pcm)
        DataOutputStream(file.outputStream().buffered()).use { o ->
            fun le32(v: Int) = o.write(ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(v).array())
            fun le16(v: Int) = o.write(ByteBuffer.allocate(2).order(ByteOrder.LITTLE_ENDIAN).putShort(v.toShort()).array())
            o.writeBytes("RIFF"); le32(36 + pcm.size * 2); o.writeBytes("WAVE")
            o.writeBytes("fmt "); le32(16); le16(1); le16(2); le32(rate); le32(rate * 4); le16(4); le16(16)
            o.writeBytes("data"); le32(pcm.size * 2)
            val bytes = ByteBuffer.allocate(pcm.size * 2).order(ByteOrder.LITTLE_ENDIAN)
            bytes.asShortBuffer().put(pcm)
            o.write(bytes.array())
        }
    }

    private fun clock(ms: Double): String {
        val t = (max(0.0, ms) / 100).toInt()
        return "%d:%02d.%d".format(t / 600, t / 10 % 60, t % 10)
    }

    private fun safe(s: String) = s.replace(Regex("""[/\\:*?"<>|]"""), "_")
}
