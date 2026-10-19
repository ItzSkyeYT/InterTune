/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.mix

import org.junit.Assume.assumeTrue
import org.junit.Test
import java.awt.BasicStroke
import java.awt.Color
import java.awt.image.BufferedImage
import java.io.DataOutputStream
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import javax.imageio.ImageIO
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.random.Random

/**
 * Not a test: the host trial. Every two songs in a row in AUTOMIX_TRIAL_DIR, by name, go through
 * the phone's own path: the incoming song's first 30 s through the head analysis, the outgoing
 * song's end held by [HeldTail] inside the model of the sink, the planner and the renderer. Each
 * transition is written as a WAV from 20 s before the incoming song starts to 20 s after the
 * outgoing one is gone, into AUTOMIX_RENDERS, with SUMMARY.md beside them and a picture of every
 * song's beat grid on its onsets in grids/. AUTOMIX_TRIAL_STYLES=all also renders every other style
 * a pair allows, for comparison. A few synthetic pairs go into synthetic/.
 *
 * Run it with --rerun: Gradle does not run a test again for a changed environment variable.
 * Nothing decoded or rendered here leaves the PC, and none of it goes into git.
 */
class AutomixTrial {

    private class Song(val title: String, val samples: ShortArray, val rate: Int, val seconds: Double, val album: String?, val id: String)

    private class Transition(
        val plan: MixPlan,
        val heard: ShortArray,
        val bAt: Long,
        val aGoneAt: Long,
        val note: String,
        val clipped: Long,
        val stalls: Int,
        val underruns: Int,
        val exactBefore: Boolean,
        val exactAfter: Boolean,
        val worstJump: Int,
        val rawJump: Int,
        val tail: RegionAnalysis?,
        val tailStartSeconds: Double,
        val headroomDb: Double,
    )

    private val held = 45.0

    @Test
    fun trial() {
        System.setProperty("java.awt.headless", "true")
        val dir = TrialSongs.dir
        assumeTrue("set AUTOMIX_TRIAL_DIR to run", dir != null)
        val everyStyle = System.getenv("AUTOMIX_TRIAL_STYLES") == "all"
        val renders = TrialSongs.renders.apply { mkdirs() }
        renders.listFiles { f -> f.extension == "wav" }?.forEach { it.delete() }
        val grids = File(renders, "grids").apply { mkdirs() }
        val trialSongs = TrialSongs.load(File(dir!!))

        // Every song's opening and end, analysed once, for the tempo table and the pictures.
        val heads = HashMap<String, RegionAnalysis>()
        val tails = HashMap<String, RegionAnalysis>()
        val tempoRows = StringBuilder()
        for ((i, s) in trialSongs.withIndex()) {
            val rate = s.sampleRate
            val head = RegionAnalysis.analyse(s.read(0, 30 * rate), rate, 2, RegionKind.HEAD)
            val tail = RegionAnalysis.analyse(s.read(s.frames - 40 * rate, s.frames), rate, 2, RegionKind.TAIL)
            heads[s.title] = head
            tails[s.title] = tail
            picture(head, s.read(0, 30 * rate), File(grids, "%02d %s head.png".format(i + 1, safe(s.title))), head.envelope.firstSound.toDouble() / rate)
            picture(tail, s.read(s.frames - 40 * rate, s.frames), File(grids, "%02d %s tail.png".format(i + 1, safe(s.title))), max(0.0, tail.envelope.trueEnd.toDouble() / rate - 14))
            val known = KNOWN[s.title.substringBefore(" - ")]
            tempoRows.append("| %d | %s | %s | %s | %s | %.0f / %.0f ms |\n".format(
                i + 1, s.title.take(48), tempoCell(head), tempoCell(tail),
                known?.let { k ->
                    // Against the end whose beats are trusted, the end if both or neither are.
                    val r = listOf(tail, head).firstOrNull { it.gates.beats } ?: tail
                    r.bpm?.let { "%.0f (%+.2f%%)".format(k, (Tempo.fold(it) / Tempo.fold(k) - 1) * 100) } ?: "%.0f".format(k)
                } ?: "",
                head.analysisMillis, tail.analysisMillis,
            ))
        }

        val pairRows = StringBuilder()
        val checks = StringBuilder()
        val styles = ArrayList<Style>()
        var styleCount = HashMap<Style, Int>()
        for (i in 0 until trialSongs.size - 1) {
            val a = trialSongs[i]
            val b = trialSongs[i + 1]
            if (a.sampleRate != b.sampleRate) continue
            val rate = a.sampleRate
            val aSong = Song(a.title, a.read(max(0, a.frames - (held * rate).toInt()), a.frames), rate, a.seconds, a.album, a.title)
            val bSong = Song(b.title, b.read(0, min(b.frames, 75 * rate)), rate, b.seconds, b.album, b.title)
            val head = heads[b.title]!!
            val t = transition(aSong, bSong, head, a.seconds - held, styles.takeLast(2).reversed(), forced = null, seed = i.toLong())
            styles += t.plan.style
            styleCount[t.plan.style] = (styleCount[t.plan.style] ?: 0) + 1
            val name = "%02d %s into %s, %s.wav".format(i + 1, safe(a.title).take(40), safe(b.title).take(40), t.plan.style.name.lowercase())
            writeWav(File(renders, name), t.heard, window(t, rate), rate)
            pairRows.append(pairRow(i + 1, a.title, b.title, tails[a.title]!!, head, t, rate))
            checks.append(checkRow(i + 1, t))
            if (everyStyle) {
                for (style in listOf(Style.BEAT, Style.CUT, Style.SMOOTH, Style.BLEND, Style.GAPLESS)) {
                    if (style == t.plan.style) continue
                    val other = transition(aSong, bSong, head, a.seconds - held, emptyList(), forced = style, seed = i.toLong())
                    if (other.plan.style != style) continue
                    val otherName = "%02d %s into %s, %s (compare).wav".format(i + 1, safe(a.title).take(40), safe(b.title).take(40), style.name.lowercase())
                    writeWav(File(renders, otherName), other.heard, window(other, rate), rate)
                }
            }
        }

        val synthetic = synthetic(File(renders, "synthetic"))

        val summary = StringBuilder()
        summary.append("# AutoMix host trial\n\n")
        summary.append("Every two songs in a row of `${File(dir).name}`, by name, through the phone's path: the next song's first 30 s analysed as its opening, this song's last 40 s held by HeldTail in the model of media3's sink, then the planner and the renderer. Renders run from 20 s before the next song starts to 20 s after this one is gone. Nothing here leaves the PC.\n\n")
        summary.append("Styles: " + styleCount.entries.sortedByDescending { it.value }.joinToString(", ") { "${it.key.name.lowercase()} ${it.value}" } + " of ${styles.size} transitions.\n\n")
        summary.append("## Transitions\n\n")
        summary.append("| # | from | into | tempos (trust) | ratio | style | why | B starts, A gone, in A's time | A unheard | peak | clipped |\n")
        summary.append("|---|---|---|---|---|---|---|---|---|---|---|\n")
        summary.append(pairRows)
        summary.append("\nTrust: B = beats on a steady grid, D = bars too; a dash where that gate failed.\n\n")
        summary.append("## Listen-free checks\n\n")
        summary.append("Before the outgoing song's first processed frame and after the incoming song's shaping ends, what was heard is compared with the raw songs sample for sample. The largest jump between two samples inside the transition is held against the largest in the raw songs over the same stretch: a click at a join would stand out.\n\n")
        summary.append("| # | note | exact before | exact after | largest jump in the blend / raw | headroom | sink stalls | underruns |\n")
        summary.append("|---|---|---|---|---|---|---|---|\n")
        summary.append(checks)
        summary.append("\n## Tempos\n\n")
        summary.append("Each song's opening (first 30 s) and end (last 40 s), analysed on their own. Published tempos from memory, approximate, for the few known well enough; folded to 85-170 before comparing.\n\n")
        summary.append("| # | song | opening | end | published | analysis time, opening / end |\n|---|---|---|---|---|---|\n")
        summary.append(tempoRows)
        summary.append("\n## Synthetic pairs\n\n")
        summary.append(synthetic)
        File(renders, "SUMMARY.md").writeText(summary.toString())
        print(summary)
    }

    /** One transition through the held tail and the sink model, as the phone would run it. */
    private fun transition(a: Song, b: Song, head: RegionAnalysis, aOffsetSeconds: Double, lastStyles: List<Style>, forced: Style?, seed: Long): Transition {
        val rate = a.rate
        val holder = HeldTail(2, rate, 48 * rate)
        val model = SinkModel(holder, listOf(ModelSong(a.title, a.samples), ModelSong(b.title, b.samples)), Random(seed), rate)
        var armFrame = -1
        var plan: MixPlan? = null
        var tailAnalysis: RegionAnalysis? = null
        val aFrames = a.samples.size / 2
        while (!model.ended && model.clockMs < 400_000) {
            val (song, frame) = model.position()
            if (armFrame < 0 && song == 0 && frame >= aFrames - 40 * rate) {
                if (holder.arm()) armFrame = model.consumedPosition()
            }
            if (plan == null && holder.state == HeldTail.State.DRAIN) {
                val tail = holder.heldTail()!!.copyOf((holder.written * 2).toInt())
                val analysis = RegionAnalysis.analyse(tail, rate, 2, RegionKind.TAIL)
                tailAnalysis = analysis
                val p = Planner.plan(PlanInput(
                    tail = analysis, head = head, readMark = holder.read, lastStyles = lastStyles,
                    aDurationMs = (a.seconds * 1000).toLong(), bDurationMs = (b.seconds * 1000).toLong(),
                    aId = a.id, bId = b.id, aAlbum = a.album, bAlbum = b.album,
                    tailStartMs = ((aOffsetSeconds + armFrame.toDouble() / rate) * 1000).toLong(), forced = forced,
                ))
                val r = MixRender.renderOutgoing(p, tail, analysis.guide, analysis.decimation, if (p.style == Style.BEAT) analysis.beatFrames() else DoubleArray(0))
                plan = p
                holder.offer(p, r)
            }
            model.step()
        }
        val p = plan ?: MixPlan.gapless(rate, 2, 0, 1.0, 1.0, "never armed")
        val heard = model.heard.toArray()
        val bAt = model.checkpoints.first { it.song == 1 }.trackFrame
        val taken = !p.isGapless && holder.note.startsWith("planned") || holder.note.isEmpty() && !p.isGapless
        val aGoneAt = if (taken) armFrame + p.aGone else bAt
        // Outside the transition, what was heard must be the raw songs exactly.
        val firstProcessed = if (taken) armFrame + p.aFirstProcessed else bAt
        val shapedUntil = if (taken) armFrame + p.shapedUntil else bAt
        var exactBefore = true
        for (i in 0 until (firstProcessed * 2).toInt()) if (heard[i] != a.samples[i]) { exactBefore = false; break }
        var exactAfter = true
        val bFrom = shapedUntil - bAt
        for (i in (bFrom * 2).toInt() until min(b.samples.size, (heard.size - (bAt * 2).toInt()))) {
            if (heard[(bAt * 2).toInt() + i] != b.samples[i]) { exactAfter = false; break }
        }
        // The largest jump between neighbouring samples inside the transition, and in the raw songs there.
        var worst = 0
        var raw = 0
        for (f in max(1L, firstProcessed - rate)..min(heard.size / 2L - 1, shapedUntil + rate)) {
            for (c in 0 until 2) {
                val i = (f * 2 + c).toInt()
                worst = max(worst, abs(heard[i] - heard[i - 2]))
                val aIndex = i
                if (aIndex < a.samples.size && aIndex >= 2) raw = max(raw, abs(a.samples[aIndex] - a.samples[aIndex - 2]))
                val bIndex = i - (bAt * 2).toInt()
                if (bIndex >= 2 && bIndex < b.samples.size) raw = max(raw, abs(b.samples[bIndex] - b.samples[bIndex - 2]))
            }
        }
        return Transition(
            plan = p, heard = heard, bAt = bAt, aGoneAt = aGoneAt, note = holder.note, clipped = holder.clipped,
            stalls = model.stalls, underruns = model.underruns, exactBefore = exactBefore, exactAfter = exactAfter,
            worstJump = worst, rawJump = raw, tail = tailAnalysis, tailStartSeconds = aOffsetSeconds + armFrame.toDouble() / rate,
            headroomDb = p.headroom.deepestDb,
        )
    }

    private fun window(t: Transition, rate: Int): LongRange {
        val from = max(0L, t.bAt - 20L * rate)
        val to = min(t.heard.size / 2L, max(t.aGoneAt, t.bAt) + 20L * rate)
        return from until to
    }

    private fun tempoCell(r: RegionAnalysis): String {
        val bpm = r.bpm ?: return "none"
        val trust = (if (r.gates.beats) "B" else "-") + (if (r.gates.downbeats) "D" else "-")
        return "%.2f %s".format(bpm, trust)
    }

    private fun pairRow(n: Int, a: String, b: String, tailA: RegionAnalysis, headB: RegionAnalysis, t: Transition, rate: Int): String {
        val p = t.plan
        val ratio = if (tailA.bpm != null && headB.bpm != null) {
            val r = headB.bpm!! / tailA.bpm!!
            val folded = listOf(r, r * 2, r / 2).minBy { abs(it - 1) }
            "%.3f".format(folded)
        } else ""
        val tempos = "%s / %s".format(tempoCell(tailA), tempoCell(headB))
        val style = when (p.style) {
            Style.BEAT -> "beat, ${p.bars} bars, %.1f s, A at %.4f".format(p.seconds, p.speed)
            Style.GAPLESS -> "gapless"
            else -> "${p.style.name.lowercase()}, %.1f s".format(p.seconds)
        }
        val taken = !p.isGapless && (t.note.startsWith("planned") || t.note.isEmpty())
        val timing = if (taken) "%s, %s".format(clock(t.tailStartSeconds + p.bStart.toDouble() / rate), clock(t.tailStartSeconds + p.aMap.tailAt(p.aGone.toDouble()) / rate)) else "at the end"
        val unheard = if (taken && t.tail != null) "%.1f s".format(p.unheardTailFrames(t.tail.envelope.trueEnd).toDouble() / rate) else ""
        val peak = window(t, rate).let { w ->
            var m = 0
            for (f in w) for (c in 0 until 2) m = max(m, abs(t.heard[(f * 2 + c).toInt()].toInt()))
            "%.1f dBFS".format(20 * log10(max(1, m) / 32768.0))
        }
        val why = (if (taken || p.isGapless) p.reason else "${p.reason}; then ${t.note}").replace("|", "/")
        return "| %d | %s | %s | %s | %s | %s | %s | %s | %s | %s | %d |\n".format(n, a.take(36), b.take(36), tempos, ratio, style, why, timing, unheard, peak, t.clipped)
    }

    private fun checkRow(n: Int, t: Transition): String =
        "| %d | %s | %s | %s | %d / %d | %s | %d | %d |\n".format(
            n, t.note.take(60).replace("|", "/"), if (t.exactBefore) "yes" else "NO", if (t.exactAfter) "yes" else "NO",
            t.worstJump, t.rawJump, if (t.headroomDb < -0.005) "%.1f dB".format(t.headroomDb) else "none", t.stalls, t.underruns,
        )

    private fun clock(seconds: Double): String {
        val hundredths = Math.round(seconds * 100)
        return "%d:%02d.%02d".format(hundredths / 6000, hundredths / 100 % 60, hundredths % 100)
    }

    private fun safe(title: String): String = title.replace(Regex("[/\\\\:*?\"<>|]"), "_")

    private fun writeWav(file: File, samples: ShortArray, frames: LongRange, rate: Int) {
        val n = (frames.last - frames.first + 1).toInt().coerceAtLeast(0)
        val data = ByteBuffer.allocate(n * 4).order(ByteOrder.LITTLE_ENDIAN)
        for (f in frames) {
            data.putShort(samples[(f * 2).toInt()])
            data.putShort(samples[(f * 2 + 1).toInt()])
        }
        DataOutputStream(FileOutputStream(file)).use { out ->
            val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
            header.put("RIFF".toByteArray()).putInt(36 + n * 4).put("WAVE".toByteArray())
            header.put("fmt ".toByteArray()).putInt(16).putShort(1).putShort(2).putInt(rate).putInt(rate * 4).putShort(4).putShort(16)
            header.put("data".toByteArray()).putInt(n * 4)
            out.write(header.array())
            out.write(data.array())
        }
    }

    /**
     * Twelve seconds of a region from [fromSeconds]: the onset curve in grey, the low onsets in
     * blue, the grid's beats as thin lines and its downbeats thick, green where the stretch is
     * trusted and red where not.
     */
    private fun picture(r: RegionAnalysis, samples: ShortArray, file: File, fromSeconds: Double) {
        val spectrum = Spectrum.of(FrontEnd.of(samples, r.sampleRate, 2))
        val width = 1800
        val height = 260
        val image = BufferedImage(width, height, BufferedImage.TYPE_INT_RGB)
        val g = image.createGraphics()
        g.color = Color.WHITE
        g.fillRect(0, 0, width, height)
        val seconds = 12.0
        val rate = r.sampleRate
        fun x(sample: Double) = ((sample / rate - fromSeconds) / seconds * width).toInt()
        val grid = r.grid
        if (grid != null) {
            val trusted = r.gates.beats
            for (k in grid.firstBeat - 64..grid.lastBeat + 64) {
                val s = r.beatFrame(k)
                val px = x(s)
                if (px < 0 || px >= width) continue
                val inStretch = k in grid.firstBeat..grid.lastBeat
                val down = r.downbeats?.isDownbeat(k) == true
                g.color = when {
                    !inStretch -> Color(220, 220, 220)
                    trusted -> Color(60, 160, 60)
                    else -> Color(200, 60, 60)
                }
                g.stroke = BasicStroke(if (down) 3f else 1f)
                g.drawLine(px, 0, px, height)
            }
        }
        g.stroke = BasicStroke(1f)
        for ((curve, colour, scale) in listOf(Triple(spectrum.onset, Color(90, 90, 90), 0.12), Triple(spectrum.lowOnset, Color(40, 80, 200), 0.12))) {
            g.color = colour
            var lastX = -1
            var lastY = height
            for (t in 0 until spectrum.frames) {
                val px = x(spectrum.frameToSample(t.toDouble()))
                if (px < 0 || px >= width) continue
                val py = height - 4 - min(height - 8, (curve[t] * scale * height).toInt())
                if (lastX >= 0) g.drawLine(lastX, lastY, px, py)
                lastX = px
                lastY = py
            }
        }
        g.color = Color.BLACK
        g.drawString("%s  %s  from %.1f s".format(file.nameWithoutExtension, r.describe().take(150), fromSeconds), 8, 14)
        g.dispose()
        ImageIO.write(image, "png", file)
    }

    /** The synthetic pairs: their plans in a table, their renders in [folder]. */
    private fun synthetic(folder: File): String {
        folder.mkdirs()
        folder.listFiles { f -> f.extension == "wav" }?.forEach { it.delete() }
        val rate = 44_100
        val pairs = listOf(
            "128 into 124" to (SynthSpec(bpm = 128.0, bars = 48, seed = 1) to SynthSpec(bpm = 124.0, bars = 40, seed = 2)),
            "140 into 70" to (SynthSpec(bpm = 140.0, bars = 48, groove = Groove.BACKBEAT, seed = 3) to SynthSpec(bpm = 70.0, bars = 24, groove = Groove.HALF_TIME, seed = 4)),
            "128 into 90" to (SynthSpec(bpm = 128.0, bars = 48, seed = 5) to SynthSpec(bpm = 90.0, bars = 30, groove = Groove.BACKBEAT, seed = 6)),
        )
        val out = StringBuilder("| pair | plan | peak | clipped | exact outside |\n|---|---|---|---|---|\n")
        fun run(label: String, aSamples: ShortArray, bSamples: ShortArray) {
            val a = Song("A", aSamples.copyOfRange(max(0, aSamples.size - (held * rate).toInt() * 2), aSamples.size), rate, aSamples.size / 2.0 / rate, "a", "a")
            val b = Song("B", bSamples.copyOfRange(0, min(bSamples.size, 75 * rate * 2)), rate, bSamples.size / 2.0 / rate, "b", "b")
            val head = RegionAnalysis.analyse(b.samples.copyOfRange(0, min(b.samples.size, 30 * rate * 2)), rate, 2, RegionKind.HEAD)
            val t = transition(a, b, head, a.seconds - held, emptyList(), null, 1)
            writeWav(File(folder, "$label, ${t.plan.style.name.lowercase()}.wav"), t.heard, window(t, rate), rate)
            var peak = 0
            for (f in window(t, rate)) for (c in 0 until 2) peak = max(peak, abs(t.heard[(f * 2 + c).toInt()].toInt()))
            out.append("| %s | %s | %.1f dBFS | %d | %s |\n".format(label, t.plan.describe().replace("|", "/"), 20 * log10(max(1, peak) / 32768.0), t.clipped, if (t.exactBefore && t.exactAfter) "yes" else "NO"))
        }
        for ((label, specs) in pairs) run(label, Synth.song(specs.first).samples, Synth.song(specs.second).samples)
        run("pad into a beat", Synth.pad(90.0), Synth.song(SynthSpec(bpm = 124.0, bars = 40, seed = 7)).samples)
        return out.toString()
    }

    private companion object {
        /** Published tempos, from memory and approximate, for the few songs known well enough. */
        val KNOWN = mapOf(
            "Music Sounds Better With You" to 124.0,
            "Godzilla (feat. Juice WRLD)" to 166.0,
            "Till I Collapse" to 171.0,
            "MILLION DOLLAR BABY" to 138.0,
            "i like the way you kiss me" to 151.0,
        )
    }
}
