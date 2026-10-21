/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.mix.auto

import com.dd3boh.outertune.mix.RegionAnalysis
import com.dd3boh.outertune.mix.RegionKind
import com.dd3boh.outertune.mix.Tempo
import com.dd3boh.outertune.mix.TrialSong
import com.dd3boh.outertune.mix.TrialSongs
import com.dd3boh.outertune.mix.edit.Preset
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.DataOutputStream
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.max
import kotlin.math.min

/**
 * Not a test: Auto's host trial on bands. Every two songs in a row of AUTOMIX_TRIAL_DIR go through
 * what the phone will do: the outgoing song's last minute and the incoming song's first minute
 * analysed, Auto's pick, each candidate rendered on bands and checked before it is kept, then the
 * change written as a WAV from 20 s before it to 20 s after. Two orders: the folder's own, by name,
 * as a playlist would have them, and one a DJ would choose for the same songs, each next song the
 * nearest in tempo (and key) that is left.
 *
 * SUMMARY.md says, for every change, the move and why, how far apart the two songs' beats sit
 * where both are heard (their low bands cross-correlated over each two bars of the rendered
 * audio), how far the level moves, and the honest count of DESIGN.md's review: changes that are
 * beat locked (both grids trusted, beats within 10 ms on average and 20 at worst) and start on a
 * phrase line.
 *
 * Run it with --rerun: Gradle does not run a test again for a changed environment variable.
 * Songs are only read; nothing decoded or rendered leaves this PC or goes into git.
 */
class AutoTrial {

    private class Analysed(val song: TrialSong, val head: SongEnd, val tail: SongEnd, val tailRegion: RegionAnalysis, val tailFromMs: Double, val headMillis: Double, val tailMillis: Double)

    private class Row(
        val n: Int,
        val a: Analysed,
        val b: Analysed,
        val pick: AutoPick,
        val verdict: ProofCheck.Verdict?,
        /** Whether what is heard before and after the change is each song to the bit (the rendered audio itself is not kept). */
        val exact: Pair<Boolean, Boolean>?,
        val file: File?,
        val before: String?,
    ) {
        /** DESIGN.md review point 4: beat locked (both grids trusted, beats within 10 ms) and on a phrase. */
        val honest: Boolean
            get() = pick.kind == AutoKind.BEAT_LOCKED && pick.onPhrase && verdict?.driftMeanMs?.let { it <= 10.0 } == true &&
                (verdict.driftWorstMs ?: 99.0) <= 20.0
    }

    private var debug = false

    private val headSeconds = System.getenv("AUTOMIX_AUTO_HEAD_S")?.toDoubleOrNull() ?: 60.0
    private val tailSeconds = System.getenv("AUTOMIX_AUTO_TAIL_S")?.toDoubleOrNull() ?: 60.0

    @Test
    fun trial() {
        System.setProperty("java.awt.headless", "true")
        val dir = TrialSongs.dir
        assumeTrue("set AUTOMIX_TRIAL_DIR to run", dir != null)
        val out = File(System.getenv("AUTOMIX_AUTO_OUT")?.takeIf { it.isNotBlank() }
            ?: "/home/melb/projects/intertune/experiments/features/automix/auto-2026-10-21")
        val label = System.getenv("AUTOMIX_AUTO_LABEL")?.takeIf { it.isNotBlank() } ?: "run"
        val renders = File(out, "renders/$label").apply { mkdirs() }
        val started = System.nanoTime()
        val songs = TrialSongs.load(File(dir!!))
        val analysed = songs.map { analyse(it) }
        val beforeDir = File(TrialSongs.renders.path)

        val orders = linkedMapOf(
            "name" to analysed,
            "tempo" to byTempo(analysed),
        )
        // AUTOMIX_AUTO_PAIRS=3-9 runs only those changes of the name order, telling every candidate's verdict.
        val pairsWanted = System.getenv("AUTOMIX_AUTO_PAIRS")?.takeIf { it.isNotBlank() }
        val range = pairsWanted?.contains('-') == true
        val only = pairsWanted?.split('-', ',')?.map { it.trim().toInt() }
        if (only != null) {
            orders.remove(if (System.getenv("AUTOMIX_AUTO_ORDER") == "tempo") "name" else "tempo")
            debug = true
        }
        val summary = StringBuilder()
        summary.append("# Auto on bands, host trial ($label)\n\n")
        File(out, "NOTES.md").takeIf { it.exists() }?.let { summary.append(it.readText().trim()).append("\n\n") }
        summary.append("Songs: `${File(dir).name}`, ${songs.size} of them. Each outgoing song's last %.0f s and each incoming song's first %.0f s analysed as the phone would, Auto's pick on bands, every candidate rendered and checked (ProofCheck) before it is kept. Renders in `renders/$label/<order>/`, from 20 s before the change to 20 s after. Nothing here leaves the PC.\n\n".format(tailSeconds, headSeconds))
        val totals = StringBuilder("| order | changes | beat locked on a phrase, beats within 10 ms (honest T4) | beat locked, any | intro mixes | hand-overs on a phrase | hand-overs on a beat | blends | plain | refused by the proof | pairs whose tempos allow a lock |\n|---|---|---|---|---|---|---|---|---|---|---|\n")
        val tables = StringBuilder()
        for ((order, list) in orders) {
            val dirOut = File(renders, order).apply { mkdirs() }
            dirOut.listFiles { f -> f.extension == "wav" }?.forEach { it.delete() }
            val rows = ArrayList<Row>()
            val recent = ArrayList<Preset>()
            for (i in 0 until list.size - 1) {
                if (only != null && range && (i + 1 < only.first() || i + 1 > only.last())) continue
                if (only != null && !range && (i + 1) !in only) continue
                val a = list[i]
                val b = list[i + 1]
                rows += change(i + 1, a, b, recent.reversed().take(2), dirOut, if (order == "name") before(beforeDir, i + 1) else null)
                rows.last().pick.takeIf { !it.isPlain }?.let { recent += it.move }
            }
            val lockable = rows.count { r ->
                val la = r.a.tail.line
                val lb = r.b.head.line
                la != null && lb != null && Tempo.distance(la.bpm, lb.bpm) <= 0.06
            }
            totals.append("| %s | %d | %d (%.0f%%) | %d | %d | %d | %d | %d | %d | %d | %d |\n".format(
                order, rows.size, rows.count { it.honest }, 100.0 * rows.count { it.honest } / max(1, rows.size),
                rows.count { it.pick.kind == AutoKind.BEAT_LOCKED }, rows.count { it.pick.kind == AutoKind.INTRO_MIX },
                rows.count { it.pick.kind == AutoKind.HAND_OVER && it.pick.onPhrase }, rows.count { it.pick.kind == AutoKind.HAND_OVER && !it.pick.onPhrase },
                rows.count { it.pick.kind == AutoKind.BLEND }, rows.count { it.pick.isPlain }, rows.count { it.pick.why.contains("refused") }, lockable,
            ))
            tables.append("## Order: ${if (order == "name") "the folder's own, by name" else "by tempo, as a DJ would line them up"}\n\n")
            tables.append("| # | from | into | tempos (trust) | keys | move | kind | phrase | beats apart, mean / worst | level: sag / dip / rise | peak | silence | A unheard | exact before / after | why | render | before (19 Oct) |\n")
            tables.append("|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|\n")
            for (r in rows) tables.append(row(r, out))
            tables.append("\n")
        }
        summary.append("## Counts\n\n").append(totals).append("\n")
        summary.append("Level, K-weighted over 3 s: sag is how far the mix sank under the quieter of the two songs as each would sound there (the proof refuses over 6 dB); dip and rise are against the straight line from the level before the change to the level after it, which a quiet intro after a loud ending moves too, so they are only reported.\n\n")
        summary.append("Honest T4 (DESIGN.md, review point 4): only changes that are beat locked, both grids trusted and the two songs' beats heard within 10 ms on average and 20 at worst in the rendered audio (the rises of their bands under 150 Hz, kicks and bass, cross-correlated over each two bars where both play), and whose outgoing song's bar 1 sits on a phrase line of 4 bars or longer. \"Pairs whose tempos allow a lock\" counts neighbours whose tempos are within 6% once folded, whatever their trust: no planner can lock the others.\n\n")
        summary.append(tables)
        summary.append("## The songs\n\n| # | song | opening: tempo, trust, key | end: tempo, trust, key, phrases | analysis ms, opening / end |\n|---|---|---|---|---|\n")
        for ((i, s) in analysed.withIndex()) {
            summary.append("| %d | %s | %s | %s | %.0f / %.0f |\n".format(i + 1, s.song.title.take(48).replace("|", "/"), endCell(s.head), endCell(s.tail), s.headMillis, s.tailMillis))
        }
        summary.append("\nTrust: B beats on a steady grid, D bars too, d bars taken from an opening's first beat (its downbeats leaning the same way), 8 bars of eight grid beats. Phrases: heard where the 4-bar grid's place won clearly from where the music changes, counted where it fell back on the last loud bar.\n")
        summary.append("\nRun took %.0f s.\n".format((System.nanoTime() - started) / 1e9))
        File(out, "SUMMARY-$label.md").writeText(summary.toString())
        print(summary)
    }

    private fun analyse(s: TrialSong): Analysed {
        val rate = s.sampleRate
        val songMs = s.seconds * 1000
        val headFrames = min(s.frames, (headSeconds * rate).toInt())
        val t0 = System.nanoTime()
        val headRegion = RegionAnalysis.analyse(s.read(0, headFrames), rate, 2, RegionKind.HEAD)
        val head = SongEnd.of(headRegion, 0.0, songMs, firstBeatBars = System.getenv("AUTOMIX_AUTO_FIRST_BEAT") != "off")
        val t1 = System.nanoTime()
        val tailFrom = max(0, s.frames - (tailSeconds * rate).toInt())
        val tailRegion = RegionAnalysis.analyse(s.read(tailFrom, s.frames), rate, 2, RegionKind.TAIL)
        val tailFromMs = tailFrom * 1000.0 / rate
        val tail = SongEnd.of(tailRegion, tailFromMs, songMs)
        val t2 = System.nanoTime()
        return Analysed(s, head, tail, tailRegion, tailFromMs, (t1 - t0) / 1e6, (t2 - t1) / 1e6)
    }

    /**
     * The songs as a DJ would line them up for flow: from the first by name, each next one the
     * nearest in tempo of those left (folded by two), its key breaking ties, beats that can be
     * trusted first.
     */
    private fun byTempo(songs: List<Analysed>): List<Analysed> {
        val left = songs.toMutableList()
        val out = ArrayList<Analysed>()
        var current = left.removeAt(0)
        out += current
        while (left.isNotEmpty()) {
            val a = current
            val next = left.minBy { b ->
                val la = a.tail.line
                val lb = b.head.line
                val tempo = if (la != null && lb != null) Tempo.distance(la.bpm, lb.bpm) else 0.5
                val trust = (if (la?.barsTrusted == true) 0.0 else 0.03) + (if (lb?.barsTrusted == true) 0.0 else 0.03)
                val ka = a.tail.key
                val kb = b.head.key
                val key = if (ka != null && kb != null && ka.sure && kb.sure && ka.key.distanceTo(kb.key) >= 3) 0.01 else 0.0
                tempo + trust + key
            }
            left.remove(next)
            out += next
            current = next
        }
        return out
    }

    private fun change(n: Int, a: Analysed, b: Analysed, recent: List<Preset>, dir: File, before: String?): Row {
        val rate = a.song.sampleRate
        if (rate != b.song.sampleRate) {
            val pick = AutoPlanner.pick(AutoInput(a.tail, b.head, a.song.title, b.song.title, a.song.album, b.song.album, recent = recent))
            return Row(n, a, b, pick, null, null, null, before)
        }
        val tailSamples = a.song.read(max(0, a.song.frames - (tailSeconds * rate).toInt()), a.song.frames)
        val bSamples = b.song.read(0, min(b.song.frames, 100 * rate))
        val renderer = BandRender(rate, tailSamples, a.tailFromMs, a.tailRegion.guide, a.tailRegion.decimation, bSamples)
        val proof = Proof.Renderer { c ->
            val r = renderer.render(c.spec(), c.kind == AutoKind.BEAT_LOCKED, 8.0, 8.0)
            if (debug && r != null) {
                val over = (0 until r.mix.size / 2).filter { kotlin.math.abs(r.mix[2 * it]) > 1f || kotlin.math.abs(r.mix[2 * it + 1]) > 1f }
                if (over.isNotEmpty()) println("    past full scale at frames %s; change %d to %d, B starts %d, bar 1 %d".format(over.take(5), r.changeFrom, r.changeTo, r.bStartFrame, r.o0Frame))
            }
            r?.proof(rate)
        }
        val input = AutoInput(a.tail, b.head, a.song.title, b.song.title, a.song.album, b.song.album, recent = recent)
        if (debug) {
            println("\n== $n ${a.song.title} into ${b.song.title}\n  A end: ${a.tail.trustNote}\n  B opening: ${b.head.trustNote}")
            val all = AutoPlanner.candidates(input)
            println("  notes: " + all.last().why)
            for (c in all.take(6)) println("  candidate %.2f %s".format(c.score, c.describe()))
        }
        val pick = AutoPlanner.pick(input, proof) { c, v ->
            if (debug) println("  %s (%.2f) %s: %s; dip %.1f, rise %.1f, silence %.0f ms, drift %s".format(c.pill, c.score, c.kind, v.why, v.dipDb, v.bumpDb, v.longestSilenceMs, v.lock?.let { "%.1f/%.1f over %d beats, late %.1f, spread %.1f".format(it.meanMs, it.worstMs, it.beats, it.lateMs, it.spreadMs) } ?: "-"))
        }
        if (debug) println("  -> ${pick.describe()}")
        if (pick.isPlain) return Row(n, a, b, pick, null, null, null, before)
        val rendered = renderer.render(pick.spec(), pick.kind == AutoKind.BEAT_LOCKED) ?: return Row(n, a, b, pick, null, null, null, before)
        val verdict = ProofCheck.check(rendered.proof(rate))
        val name = "%02d %s into %s, %s.wav".format(n, safe(a.song.title).take(40), safe(b.song.title).take(40), pick.pill.replace(" · ", " ").lowercase())
        val file = File(dir, name)
        writeWav(file, rendered.heard, rate)
        return Row(n, a, b, pick, verdict, rendered.exactBefore to rendered.exactAfter, file, before)
    }

    private fun row(r: Row, out: File): String {
        val p = r.pick
        val v = r.verdict
        val la = r.a.tail
        val lb = r.b.head
        val tempos = "%s / %s".format(tempoCell(la), tempoCell(lb))
        val keys = listOf(la.key, lb.key).joinToString(" to ") { k -> k?.let { it.key.camelot + if (it.sure) "" else "?" } ?: "?" } +
            (p.keyDistance?.let { " (%d)".format(it) } ?: "")
        val phrase = when {
            p.isPlain -> ""
            p.phraseBars >= 4 -> "on a ${p.phraseBars}-bar line" + if (la.phrases?.heard == true) "" else " (counted)"
            p.phraseBars > 0 -> "bar line"
            p.kind == AutoKind.BLEND -> "seconds"
            else -> "beat"
        }
        val drift = v?.lock?.let { "%.1f / %.1f ms (%d beats, A %+.1f ms)".format(it.meanMs, it.worstMs, it.beats, it.lateMs) } ?: ""
        val level = v?.let { "%.1f / %.1f / +%.1f dB".format(it.sagDb, it.dipDb, it.bumpDb) } ?: ""
        val peak = v?.let { "%.1f dBFS".format(it.peakDb) } ?: ""
        val silence = v?.let { if (it.longestSilenceMs > 0) "%.0f ms".format(it.longestSilenceMs) else "none" } ?: ""
        val unheard = if (p.isPlain) "" else "%.1f s".format(p.unheardMs / 1000)
        val exact = r.exact?.let { "${if (it.first) "yes" else "NO"} / ${if (it.second) "yes" else "NO"}" } ?: ""
        val file = r.file?.relativeTo(out)?.path?.let { "`$it`" } ?: ""
        val honest = if (r.honest) " **T4**" else ""
        return "| %d | %s | %s | %s | %s | %s%s | %s | %s | %s | %s | %s | %s | %s | %s | %s | %s | %s |\n".format(
            r.n, r.a.song.title.take(34).replace("|", "/"), r.b.song.title.take(34).replace("|", "/"), tempos, keys, p.pill, honest,
            p.kind.name.lowercase().replace('_', ' '), phrase, drift, level, peak, silence, unheard, exact,
            p.why.replace("|", "/"), file, r.before?.let { "`$it`" } ?: "",
        )
    }

    private fun endCell(e: SongEnd): String {
        val line = e.line
        val tempo = e.bpm?.let { "%.2f".format(it) } ?: "none"
        val trust = if (line == null) "--" else (if (line.beatsTrusted) "B" else "-") + (if (line.barsFromFirstBeat) "d" else if (line.barsTrusted) "D" else "-") + (if (line.barsTrusted && line.beatsPerBar == 8) "8" else "")
        val key = e.key?.let { it.key.camelot + if (it.sure) "" else "?" } ?: "?"
        val phrases = e.phrases?.let { if (e.kind == RegionKind.TAIL) ", phrases " + if (it.heard) "heard (t %.1f)".format(it.margin) else "counted (t %.1f)".format(it.margin) else "" } ?: ""
        return "$tempo $trust, $key$phrases"
    }

    private fun tempoCell(e: SongEnd): String {
        val line = e.line ?: return "none"
        return "%.2f %s%s".format(line.bpm, if (line.beatsTrusted) "B" else "-", if (line.barsFromFirstBeat) "d" else if (line.barsTrusted) "D" else "-")
    }

    /** The prototype's render of the same pair on 19 Oct, by its number, where there is one. */
    private fun before(dir: File, n: Int): String? {
        val prefix = "%02d ".format(n)
        return dir.listFiles { f -> f.extension == "wav" && f.name.startsWith(prefix) }?.firstOrNull()?.let { "../renders/${it.name}" }
    }

    private fun safe(title: String): String = title.replace(Regex("[/\\\\:*?\"<>|]"), "_")

    private fun writeWav(file: File, samples: ShortArray, rate: Int) {
        val n = samples.size / 2
        val data = ByteBuffer.allocate(n * 4).order(ByteOrder.LITTLE_ENDIAN)
        for (s in samples) data.putShort(s)
        DataOutputStream(FileOutputStream(file)).use { out ->
            val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
            header.put("RIFF".toByteArray()).putInt(36 + n * 4).put("WAVE".toByteArray())
            header.put("fmt ".toByteArray()).putInt(16).putShort(1).putShort(2).putInt(rate).putInt(rate * 4).putShort(4).putShort(16)
            header.put("data".toByteArray()).putInt(n * 4)
            out.write(header.array())
            out.write(data.array())
        }
    }
}
