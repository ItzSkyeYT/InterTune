/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.mix.engine

import com.dd3boh.outertune.mix.MixRender
import com.dd3boh.outertune.mix.PlanInput
import com.dd3boh.outertune.mix.Planner
import com.dd3boh.outertune.mix.RegionAnalysis
import com.dd3boh.outertune.mix.RegionKind
import com.dd3boh.outertune.mix.Style
import com.dd3boh.outertune.mix.Tempo
import com.dd3boh.outertune.mix.TrialSong
import com.dd3boh.outertune.mix.TrialSongs
import com.dd3boh.outertune.mix.attenuate
import com.dd3boh.outertune.mix.edit.Preset
import com.dd3boh.outertune.mix.edit.Presets
import com.dd3boh.outertune.mix.edit.TransitionSpec
import com.dd3boh.outertune.mix.toPcm16
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.DataOutputStream
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Not a test: the host trial of changes on bands. Every two songs in a row in AUTOMIX_TRIAL_DIR,
 * and the pairs whose tempos meet, go through the planner as the phone runs it, and Auto's change
 * is rendered twice: on bands, from the held end and the next song's copy, as this build plays it,
 * and the prototype's way, for comparison. AUTOMIX_TRIAL_STYLES=all also renders every preset on
 * bands at Auto's place for a beat-matched pair. WAVs and SUMMARY.md go to AUTOMIX_RENDERS/bands.
 *
 * Run it with --rerun. Nothing decoded or rendered here leaves the PC, and none of it goes into git.
 */
class BandsTrial {

    private val held = 45.0
    private val readMark = 8.0

    @Test
    fun trial() {
        val dir = TrialSongs.dir
        assumeTrue("set AUTOMIX_TRIAL_DIR to run", dir != null)
        val everyPreset = System.getenv("AUTOMIX_TRIAL_STYLES") == "all"
        val out = File(TrialSongs.renders, "bands").apply { mkdirs() }
        out.listFiles { f -> f.extension == "wav" }?.forEach { it.delete() }
        val songs = TrialSongs.load(File(dir!!))
        val heads = HashMap<String, RegionAnalysis>()
        for (s in songs) heads[s.title] = RegionAnalysis.analyse(s.read(0, 30 * s.sampleRate), s.sampleRate, 2, RegionKind.HEAD)

        val pairs = ArrayList<kotlin.Pair<TrialSong, TrialSong>>()
        for (i in 0 until songs.size - 1) pairs += songs[i] to songs[i + 1]
        // And the pairs a DJ would put together: both ends' bars trusted, tempos within 4% once folded.
        val matched = ArrayList<Triple<TrialSong, TrialSong, Double>>()
        val tails = HashMap<String, RegionAnalysis>()
        for (s in songs) tails[s.title] = RegionAnalysis.analyse(s.read(s.frames - (held * s.sampleRate).toInt(), s.frames), s.sampleRate, 2, RegionKind.TAIL)
        for (a in songs) for (b in songs) {
            if (a === b || a.sampleRate != b.sampleRate) continue
            val ta = tails[a.title]!!
            val hb = heads[b.title]!!
            if (!ta.barsConfident || !hb.barsConfident) continue
            val gap = abs(Tempo.fold(ta.bpm!!) / Tempo.fold(hb.bpm!!) - 1)
            if (gap <= 0.04) matched += Triple(a, b, gap)
        }
        val used = HashMap<String, Int>()
        for ((a, b, _) in matched.sortedBy { it.third }) {
            if ((used[a.title] ?: 0) >= 2 || (used[b.title] ?: 0) >= 2) continue
            if (pairs.any { it.first === a && it.second === b }) continue
            pairs += a to b
            used[a.title] = (used[a.title] ?: 0) + 1
            used[b.title] = (used[b.title] ?: 0) + 1
            if (used.size > 24) break
        }

        val rows = StringBuilder()
        var n = 0
        for ((a, b) in pairs) {
            if (a.sampleRate != b.sampleRate) continue
            n++
            val rate = a.sampleRate
            val tail = a.read(max(0, a.frames - (held * rate).toInt()), a.frames)
            val copy = b.read(0, min(b.frames, 75 * rate))
            val tailAnalysis = RegionAnalysis.analyse(tail, rate, 2, RegionKind.TAIL)
            val head = heads[b.title]!!
            val mark = (readMark * rate).toLong()
            val earliest = mark + 4L * rate
            val plan = Planner.plan(
                PlanInput(
                    tail = tailAnalysis, head = head, readMark = mark, earliestChange = earliest,
                    aDurationMs = (a.seconds * 1000).toLong(), bDurationMs = (b.seconds * 1000).toLong(),
                    aId = a.title, bId = b.title, aAlbum = a.album, bAlbum = b.album,
                ),
            )
            val name = "%02d %s into %s".format(n, safe(a.title).take(36), safe(b.title).take(36))
            val made = AutoSpec.of(plan, tailAnalysis, head, a.title, b.title, (a.seconds - held) * 1000, (copy.size / 2).toLong(), earliest)
            if (made == null) {
                rows.append("| $n | ${a.title.take(40)} | ${b.title.take(40)} | plain | ${plan.reason.take(80)} | | | |\n")
                continue
            }
            val lanePlan = when (val r = SpecPlan.plan(made.spec, made.facts)) {
                is SpecPlan.Result.Planned -> r.plan
                is SpecPlan.Result.Refused -> {
                    rows.append("| $n | ${a.title.take(40)} | ${b.title.take(40)} | ${AutoSpec.label(made.spec)} | refused: ${r.why} | | | |\n")
                    continue
                }
            }
            val render = LaneMixer(lanePlan).render(tail, copy, 1.0, 1.0)
            val from = max(0L, lanePlan.bStart - 12L * rate).coerceAtMost(lanePlan.mixFrom)
            val to = lanePlan.mixTo + 10L * rate
            writeWav(File(out, "$name, auto on bands, ${safe(AutoSpec.label(made.spec))}.wav"), render.mix(tail, copy, from, to), rate)
            writeWav(File(out, "$name, prototype ${plan.style.name.lowercase()}.wav"), prototype(plan, tail, tailAnalysis, copy, from, to), rate)
            rows.append(
                "| $n | ${a.title.take(40)} | ${b.title.take(40)} | ${AutoSpec.label(made.spec)} | %s | %.0f ms | %.1f dB | %.2f |\n".format(
                    if (plan.speed != 1.0) "A at %.4f".format(plan.speed) else "", render.renderMillis, render.headroom.deepestDb, render.peakAfter,
                ),
            )
            if (everyPreset && made.spec.beatMatched) {
                for (preset in listOf(Preset.FADE, Preset.RISE, Preset.BLEND, Preset.ECHO_OUT, Preset.CUT, Preset.RIFF_RISE, Preset.VOCALS_OVER)) {
                    val spec = Presets.apply(made.spec, preset)
                    val p = (SpecPlan.plan(spec, made.facts) as? SpecPlan.Result.Planned)?.plan ?: continue
                    val r = LaneMixer(p).render(tail, copy, 1.0, 1.0)
                    val f = max(0L, p.bStart - 12L * rate).coerceAtMost(p.mixFrom)
                    writeWav(File(out, "$name, preset ${preset.key}.wav"), r.mix(tail, copy, f, p.mixTo + 10L * rate), rate)
                }
            }
        }
        File(out, "SUMMARY.md").writeText(
            "# Auto on bands, host trial\n\nEach pair as Auto plays it on bands from copies (\"auto on bands\") and the prototype's way (\"prototype\"). " +
                "Rendered from the planner's choice with the held end starting ${held.toInt()} s before the end and a read mark of ${readMark.toInt()} s.\n\n" +
                "| # | From | Into | Auto | Tempo | Render | Headroom | Peak |\n|---|---|---|---|---|---|---|---|\n" + rows,
        )
        println(rows)
    }

    /** The prototype's change over the same window: A's rendered part, B shaped live, as HeldTail plays it. */
    private fun prototype(plan: com.dd3boh.outertune.mix.MixPlan, tail: ShortArray, analysis: RegionAnalysis, b: ShortArray, from: Long, to: Long): ShortArray {
        val rendered = MixRender.renderOutgoing(plan, tail, analysis.guide, analysis.decimation, plan.anchors)
        val n = (to - from).toInt()
        val out = ShortArray(n * 2)
        val shaper = com.dd3boh.outertune.mix.IncomingShaper(plan, rendered.headroom)
        val mixer = com.dd3boh.outertune.mix.Mixer()
        val shaped = DoubleArray(2)
        val one = ShortArray(2)
        val bFrames = b.size / 2
        for (i in 0 until n) {
            val o = from + i
            when {
                o < plan.aFirstProcessed -> for (c in 0..1) out[i * 2 + c] = if (o in 0 until tail.size / 2) tail[(o * 2).toInt() + c] else 0
                o < plan.bStart -> for (c in 0..1) out[i * 2 + c] = rendered.at(o, c)
                o < plan.shapedUntil -> {
                    val j = (o - plan.bStart).toInt()
                    if (j >= bFrames) break
                    shaper.shape(b, j, 1, j.toLong(), shaped)
                    mixer.mix(rendered, o, shaped, 0, 1, one, 0)
                    out[i * 2] = one[0]
                    out[i * 2 + 1] = one[1]
                }
                else -> {
                    val j = (o - plan.bStart).toInt()
                    if (j >= bFrames) break
                    for (c in 0..1) out[i * 2 + c] = b[j * 2 + c]
                }
            }
        }
        return out
    }

    private fun safe(s: String) = s.replace(Regex("""[/\\:*?"<>|·]"""), "-")

    private fun writeWav(file: File, samples: ShortArray, rate: Int) {
        DataOutputStream(FileOutputStream(file).buffered()).use { o ->
            val bytes = samples.size * 2
            fun le32(v: Int) = o.write(ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(v).array())
            fun le16(v: Int) = o.write(ByteBuffer.allocate(2).order(ByteOrder.LITTLE_ENDIAN).putShort(v.toShort()).array())
            o.writeBytes("RIFF"); le32(36 + bytes); o.writeBytes("WAVE")
            o.writeBytes("fmt "); le32(16); le16(1); le16(2); le32(rate); le32(rate * 4); le16(4); le16(16)
            o.writeBytes("data"); le32(bytes)
            val buf = ByteBuffer.allocate(bytes).order(ByteOrder.LITTLE_ENDIAN)
            buf.asShortBuffer().put(samples)
            o.write(buf.array())
        }
    }
}
