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
        /** For a beat mix: how far the outgoing song's beats, found again in its rendered audio, sit from the incoming song's, mean and worst, in ms. */
        val lock: kotlin.Pair<Double, Double>?,
        /** The sharpest spike at any join, and the 95th percentile of the same measure at random points of the render. */
        val joinSpike: Double,
        val spikeBaseline: Double,
        /** The same measure on the raw song heard most at that join. */
        val rawSpike: Double,
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

        // Pairs chosen by tempo rather than by name, as a DJ or a queue built for flow would put
        // them: an end with trusted bars and an opening with trusted bars whose tempos are within
        // 6% once folded, the closest first, each song at most twice.
        val matchedDir = File(renders, "matched").apply { mkdirs() }
        matchedDir.listFiles { f -> f.extension == "wav" }?.forEach { it.delete() }
        val candidates = ArrayList<Triple<TrialSong, TrialSong, Double>>()
        for (a in trialSongs) for (b in trialSongs) {
            if (a === b || a.sampleRate != b.sampleRate) continue
            if (a.album != null && a.album == b.album) continue
            val ta = tails[a.title]!!
            val hb = heads[b.title]!!
            if (!ta.barsConfident || !hb.barsConfident) continue
            val distance = Tempo.distance(ta.bpm!!, hb.bpm!!)
            if (distance <= Planner.BEAT_MAX_SPEED) candidates += Triple(a, b, distance)
        }
        val used = HashMap<String, Int>()
        val matchedRows = StringBuilder()
        var matchedCount = 0
        for ((a, b, _) in candidates.sortedBy { it.third }) {
            if (matchedCount >= 12) break
            if ((used[a.title] ?: 0) >= 2 || (used[b.title] ?: 0) >= 2) continue
            used[a.title] = (used[a.title] ?: 0) + 1
            used[b.title] = (used[b.title] ?: 0) + 1
            matchedCount++
            val rate = a.sampleRate
            val aSong = Song(a.title, a.read(max(0, a.frames - (held * rate).toInt()), a.frames), rate, a.seconds, a.album, a.title)
            val bSong = Song(b.title, b.read(0, min(b.frames, 75 * rate)), rate, b.seconds, b.album, b.title)
            val t = transition(aSong, bSong, heads[b.title]!!, a.seconds - held, emptyList(), forced = null, seed = 100L + matchedCount)
            val name = "m%02d %s into %s, %s.wav".format(matchedCount, safe(a.title).take(40), safe(b.title).take(40), t.plan.style.name.lowercase())
            writeWav(File(matchedDir, name), t.heard, window(t, rate), rate)
            matchedRows.append(pairRow(matchedCount, a.title, b.title, tails[a.title]!!, heads[b.title]!!, t, rate).replaceFirst("| $matchedCount |", "| m$matchedCount |"))
            checks.append(checkRow("m$matchedCount", t))
        }

        val synthetic = synthetic(File(renders, "synthetic"))

        val summary = StringBuilder()
        summary.append("# AutoMix host trial\n\n")
        // A hand-written reading of the results is kept beside the renders and put first, so a
        // new run of the trial does not lose it.
        File(renders, "NOTES.md").takeIf { it.exists() }?.let { summary.append(it.readText().trim()).append("\n\n") }
        summary.append("Every two songs in a row of `${File(dir).name}`, by name, through the phone's path: the next song's first 30 s analysed as its opening, this song's last 40 s held by HeldTail in the model of media3's sink, then the planner and the renderer. Renders run from 20 s before the next song starts to 20 s after this one is gone. Nothing here leaves the PC.\n\n")
        summary.append("Styles: " + styleCount.entries.sortedByDescending { it.value }.joinToString(", ") { "${it.key.name.lowercase()} ${it.value}" } + " of ${styles.size} transitions.\n\n")
        summary.append("## Transitions\n\n")
        summary.append("| # | from | into | tempos (trust) | ratio | style | why | B starts, A gone, in A's time | A unheard | peak | clipped |\n")
        summary.append("|---|---|---|---|---|---|---|---|---|---|---|\n")
        summary.append(pairRows)
        summary.append("\nTrust: B = beats on a steady grid, D = bars too; a dash where that gate failed.\n\n")
        summary.append("## Listen-free checks\n\n")
        summary.append("Before the outgoing song's first processed frame and after the incoming song's shaping ends, what was heard is compared with the raw songs sample for sample. The largest jump between two samples inside the transition is held against the largest in the raw songs over the same stretch: a click at a join would stand out.\n\n")
        summary.append("For a beat mix, the beat lock: the outgoing song's part rendered without its curves and filters, its beat line found again in that audio, and each of its beats held against the incoming song's nearest beat through the overlap, mean and worst.\n\n")
        summary.append("Join spike: at each join (first processed frame, the next song's start, where this song is gone, where the shaping ends), the sharpest second difference within 0.7 ms over the median of the 90 ms around it. Shown for the sharpest join, then the same measure on the raw song heard most there, then the 95th percentile of the measure at 200 random points of the render. A click would put the first far above the other two; the music's own transient lands the first and second together.\n\n")
        summary.append("| # | note | exact before | exact after | largest jump in the blend / raw | join spike / raw there / random 95th | headroom | beat lock | sink stalls | underruns |\n")
        summary.append("|---|---|---|---|---|---|---|---|---|---|\n")
        summary.append(checks)
        summary.append("\n## Matched pairs\n\n")
        summary.append("Not in name order: an end with trusted bars and an opening with trusted bars whose tempos are within 6% once folded, the closest first, each song in at most two pairs, rendered the same way into matched/. This is where the beat mix can be heard on these songs; name order almost never puts two such songs side by side.\n\n")
        summary.append("| # | from | into | tempos (trust) | ratio | style | why | B starts, A gone, in A's time | A unheard | peak | clipped |\n")
        summary.append("|---|---|---|---|---|---|---|---|---|---|---|\n")
        summary.append(matchedRows)
        summary.append("\n## Tempos\n\n")
        summary.append("Each song's opening (first 30 s) and end (last 40 s), analysed on their own. Published tempos from memory, approximate, for the few known well enough; folded to 85-170 before comparing.\n\n")
        summary.append("| # | song | opening | end | published | analysis time, opening / end |\n|---|---|---|---|---|---|\n")
        summary.append(tempoRows)
        summary.append("\n## Synthetic pairs\n\n")
        summary.append(synthetic)
        summary.append("\n## Analysis accuracy on synthetic songs\n\n")
        summary.append(accuracy())
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
        val lock = if (taken && p.style == Style.BEAT && tailAnalysis != null) beatLock(p, holder, tailAnalysis!!, head, armFrame, a) else null
        // A click is a spike in the second difference that its surroundings do not have: at each
        // join, the largest within 0.7 ms, over the median of the 90 ms around it; and the same at
        // random points of the render, for what this music does anyway.
        fun spike(f: Long): Double {
            fun d2(n: Long): Double {
                if (n < 2 || n >= heard.size / 2) return 0.0
                var m = 0.0
                for (c in 0 until 2) {
                    val i = (n * 2 + c).toInt()
                    m = max(m, abs(heard[i] - 2.0 * heard[i - 2] + heard[i - 4]))
                }
                return m
            }
            var peak = 0.0
            for (n in f - 32..f + 32) peak = max(peak, d2(n))
            val around = (f - 2048..f + 2048 step 8).map { d2(it) }.sorted()
            return peak / (around[around.size / 2] + 1.0)
        }
        val joins = listOf(firstProcessed, bAt, aGoneAt, shapedUntil).distinct().filter { it in 2 until heard.size / 2L - 2 }
        val joinSpike = joins.maxOfOrNull { spike(it) } ?: 0.0
        // The same measure on the raw song that is heard most at each join: the outgoing song up to
        // where it is gone, the incoming one after. Near equal, the spike is the music's own.
        fun rawSpike(f: Long): Double {
            val useA = f < aGoneAt
            val src = if (useA) a.samples else b.samples
            val offset = if (useA) 0L else bAt
            fun d2(n: Long): Double {
                val m = n - offset
                if (m < 2 || m >= src.size / 2) return 0.0
                var v = 0.0
                for (c in 0 until 2) {
                    val i = (m * 2 + c).toInt()
                    v = max(v, abs(src[i] - 2.0 * src[i - 2] + src[i - 4]))
                }
                return v
            }
            var peak = 0.0
            for (n in f - 32..f + 32) peak = max(peak, d2(n))
            val around = (f - 2048..f + 2048 step 8).map { d2(it) }.sorted()
            return peak / (around[around.size / 2] + 1.0)
        }
        val worstJoin = joins.maxByOrNull { spike(it) }
        val rawAtWorst = worstJoin?.let { rawSpike(it) } ?: 0.0
        val random = Random(seed + 7)
        val w = window(Transition(p, heard, bAt, aGoneAt, "", 0, 0, 0, true, true, 0, 0, null, 0.0, 0.0, null, 0.0, 0.0, 0.0), rate)
        val baseline = (0 until 200).map { spike(w.first + 4096 + random.nextLong(max(1L, w.last - w.first - 8192))) }.sorted()
        return Transition(
            plan = p, heard = heard, bAt = bAt, aGoneAt = aGoneAt, note = holder.note, clipped = holder.clipped,
            stalls = model.stalls, underruns = model.underruns, exactBefore = exactBefore, exactAfter = exactAfter,
            worstJump = worst, rawJump = raw, tail = tailAnalysis, tailStartSeconds = aOffsetSeconds + armFrame.toDouble() / rate,
            headroomDb = p.headroom.deepestDb,
            lock = lock,
            joinSpike = joinSpike,
            spikeBaseline = baseline[(baseline.size * 95) / 100],
            rawSpike = rawAtWorst,
        )
    }

    /**
     * The outgoing song through the overlap as rendered, without its curves and filters, analysed
     * again as audio: its beat line held against the incoming song's grid, beat by beat. This is
     * the stretch and its anchors checked on real music, not the plan's arithmetic.
     */
    private fun beatLock(p: MixPlan, holder: HeldTail, tail: RegionAnalysis, head: RegionAnalysis, armFrame: Int, a: Song): kotlin.Pair<Double, Double>? {
        val rate = p.sampleRate
        val tailSamples = a.samples.copyOfRange(armFrame * 2, min(a.samples.size, (armFrame + p.tailFrames.toInt()) * 2))
        // From the ramp's end, where the speed holds, through the overlap and on for as much of
        // the held tail as is left, up to 6 s more, so even a 4-bar overlap gives the tempo stage
        // the 8 s it needs; only beats inside the overlap are compared.
        val from = (p.aMap.rampStart + p.aMap.rampLength).toLong()
        val to = min(p.aMap.outAt(p.tailFrames.toDouble() - Wsola.FRAME), p.overlapEnd + 6.0 * rate).toLong()
        if (to - from < 8 * rate) return null
        val plain = MixPlan(
            Style.BEAT, "plain", rate, 2, p.tailFrames, p.bStart, p.aMap, p.aFirstProcessed, to,
            p.overlapStart, p.overlapEnd, Curve.constant(1.0), Curve.constant(1.0), emptyList(), emptyList(),
            Headroom.NONE, DoubleArray(0), 1.0, 1.0, p.shapedUntil, 0, 0, p.speed, p.bars,
        )
        val rendered = MixRender.renderOutgoing(plain, tailSamples, tail.guide, tail.decimation, tail.beatFrames())
        val audio = ShortArray(((to - from) * 2).toInt()) { rendered.at(from + it / 2, it % 2) }
        val r = Rhythm.analyse(Spectrum.of(FrontEnd.of(audio, rate, 2)))
        val grid = r.grid ?: return null
        val spectrumFactor = Pcm.decimationFor(rate)
        fun aBeat(k: Int) = from + (grid.frameOf(k) * Spectrum.HOP + Spectrum.ONSET_LAG) * spectrumFactor
        val periodB = head.periodFrames!!
        val gaps = ArrayList<Double>()
        for (k in grid.firstBeat..grid.lastBeat) {
            val at = aBeat(k)
            if (at < p.overlapStart || at > p.overlapEnd) continue
            // The incoming song's nearest beat, in output frames.
            val bBeat = head.beatNear(at - p.bStart)
            val bAt = p.bStart + head.beatFrame(bBeat)
            val gap = (at - bAt) / rate * 1000
            if (abs(gap) < periodB / rate * 1000 / 2) gaps += abs(gap)
        }
        if (gaps.isEmpty()) return null
        return gaps.average() to gaps.max()
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

    private fun checkRow(n: Any, t: Transition): String =
        "| %s | %s | %s | %s | %d / %d | %.0f / %.0f / %.0f | %s | %s | %d | %d |\n".format(
            n, t.note.take(60).replace("|", "/"), if (t.exactBefore) "yes" else "NO", if (t.exactAfter) "yes" else "NO",
            t.worstJump, t.rawJump, t.joinSpike, t.rawSpike, t.spikeBaseline, if (t.headroomDb < -0.005) "%.1f dB".format(t.headroomDb) else "none",
            t.lock?.let { "%.1f / %.1f ms".format(it.first, it.second) } ?: "", t.stalls, t.underruns,
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

    /**
     * The analysis against the generator's answers over many songs: three grooves at nine tempos
     * and two seeds, the harder variants, and the controls that must never be trusted.
     */
    private fun accuracy(): String {
        val out = StringBuilder()
        var regions = 0
        var tempoRight = 0
        var beatsTrusted = 0
        var beatsTrustedRight = 0
        var fSum = 0.0
        var barsTrusted = 0
        var barsRight = 0
        var barsWrong = 0
        var octaveRegions = 0
        val tempos = doubleArrayOf(85.0, 100.0, 110.0, 124.0, 128.0, 140.0, 150.0, 160.0, 174.0)
        val specs = ArrayList<kotlin.Pair<String, SynthSpec>>()
        for (groove in listOf(Groove.FOUR_ON_THE_FLOOR, Groove.BACKBEAT, Groove.HALF_TIME)) for (bpm in tempos) for (seed in 1L..2L) {
            specs += "$groove $bpm" to SynthSpec(bpm = bpm, bars = (bpm / 4 + 4).toInt(), groove = groove, seed = seed)
        }
        for (seed in 1L..2L) {
            specs += "shuffle 96" to SynthSpec(bpm = 96.0, bars = 16, groove = Groove.BACKBEAT, swing = 2.0 / 3.0, seed = seed)
            specs += "shuffle 132" to SynthSpec(bpm = 132.0, bars = 20, groove = Groove.BACKBEAT, swing = 2.0 / 3.0, seed = seed)
            specs += "noise -20 dB" to SynthSpec(bpm = 126.0, bars = 20, noiseDb = -20.0, seed = seed)
            specs += "no chords" to SynthSpec(bpm = 122.0, bars = 20, chords = false, seed = seed)
            specs += "48 kHz" to SynthSpec(bpm = 118.0, bars = 20, sampleRate = 48_000, seed = seed)
        }
        for ((_, spec) in specs) {
            val h = Heard.of(Synth.song(spec), 0.0, 30.0)
            regions++
            val bpm = h.rhythm.bpm
            val right = bpm != null && Tempo.distance(bpm, spec.bpm) < 0.001
            if (right) tempoRight++
            // Is the grid at the song's own beat, or an octave of it? Bars only mean the song's
            // bars when it is at the song's own beat.
            val ownBeat = bpm != null && abs(bpm / spec.bpm - 1) < 0.01
            if (!ownBeat && right) octaveRegions++
            if (h.rhythm.gates.beats) {
                beatsTrusted++
                if (right) beatsTrustedRight++
                fSum += if (ownBeat) h.beatF() else octaveF(h)
            }
            if (h.rhythm.gates.downbeats && ownBeat) {
                barsTrusted++
                if (h.downbeatAccuracy() >= 0.99) barsRight++ else barsWrong++
            }
        }
        out.append("Each song's first 30 s analysed as an opening; %d songs: three grooves (four on the floor, backbeat, half time) at 85 to 174 BPM, two seeds each, and shuffles, added noise, no chords and 48 kHz.\n\n".format(regions))
        out.append("| measure | result |\n|---|---|\n")
        out.append("| tempo within 0.1%% once folded | %d of %d |\n".format(tempoRight, regions))
        out.append("| beats trusted | %d of %d, all with the right tempo: %s |\n".format(beatsTrusted, regions, if (beatsTrustedRight == beatsTrusted) "yes" else "NO, ${beatsTrusted - beatsTrustedRight} wrong"))
        out.append("| beat F-measure at ±70 ms, trusted grids, against the song's beats or the octave the grid is at | %.3f |\n".format(if (beatsTrusted > 0) fSum / beatsTrusted else 0.0))
        out.append("| grids at an octave of the song's beat (folded into 85-170, so bars are not judged) | %d |\n".format(octaveRegions))
        out.append("| bars trusted on the song's own beat | %d, right %d, wrong %d |\n".format(barsTrusted, barsRight, barsWrong))

        // The controls: nothing here may be trusted at any stage.
        var controls = 0
        var tempoClaimed = 0
        var beatsClaimed = 0
        var barsClaimed = 0
        fun control(r: Rhythm) {
            controls++
            if (r.gates.tempo) tempoClaimed++
            if (r.gates.beats) beatsClaimed++
            if (r.gates.downbeats) barsClaimed++
        }
        for (seed in 1L..3L) {
            control(Heard.rhythmOf(Synth.pad(30.0, seed = seed)))
            control(Heard.rhythmOf(Synth.noise(30.0, seed = seed)))
            control(Heard.of(Synth.song(SynthSpec(bpm = 120.0, bars = 15, rampPercent = 2.0, seed = seed)), 0.0, 30.0).rhythm)
        }
        var waltzBars = 0
        for (seed in 1L..3L) {
            if (Heard.of(Synth.song(SynthSpec(bpm = 120.0, bars = 22, groove = Groove.WALTZ, seed = seed)), 0.0, 30.0).rhythm.gates.downbeats) waltzBars++
        }
        out.append("| controls (pads, white noise, a 2%% tempo ramp, three seeds each) trusted for tempo / beats / bars | %d / %d / %d of %d |\n".format(tempoClaimed, beatsClaimed, barsClaimed, controls))
        out.append("| waltzes whose bars of four were trusted | %d of 3 |\n".format(waltzBars))
        return out.toString()
    }

    /** The F-measure of a grid at an octave of the song's beat, against the song's beats at that octave. */
    private fun octaveF(h: Heard): Double {
        val beats = h.beats()
        if (beats.size < 2) return 0.0
        val truth = h.song.beats
        val ratio = h.rhythm.bpm!! / h.song.spec.bpm
        val shifted: DoubleArray = when {
            ratio > 1.5 -> DoubleArray(truth.size * 2 - 1) { if (it % 2 == 0) truth[it / 2] else (truth[it / 2] + truth[it / 2 + 1]) / 2 }
            ratio < 0.75 -> {
                // Every other true beat, whichever half lines up with the grid.
                val even = truth.filterIndexed { i, _ -> i % 2 == 0 }.toDoubleArray()
                val odd = truth.filterIndexed { i, _ -> i % 2 == 1 }.toDoubleArray()
                if (Truth.fMeasure(beats, even) >= Truth.fMeasure(beats, odd)) even else odd
            }
            else -> truth
        }
        return Truth.fMeasure(beats, Truth.within(shifted, beats.first() - 0.1, beats.last() + 0.1))
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
