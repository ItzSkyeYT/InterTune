/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.recognition

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * What the windows of one listen of the phone's own sound say between them (31 Oct 2026).
 *
 * One window is a guess. His reel of an organ playing a dance tune was named by its first twelve
 * seconds as "Sunshine" by Dance Nation, and asked again every twelve seconds of its 160 it was
 * named as fourteen different songs, none of them the same one twice in the same place: Shazam
 * matches whatever has the same few notes. A song that is really playing is named by window after
 * window, at the place in the song that the time between the windows says, so that is what counts.
 *
 * - A song is told by Shazam's key, or by title and first artist when it has several entries.
 * - Two windows are evidence of each other only when they heard different sound: no more than half
 *   of the shorter one in common ([independent]), and in a clip that is a loop, no more than half
 *   as the loop goes round, so that a seven second clip heard three times over is heard once.
 * - They agree when the later one lands where the earlier one moved on to: the song's position
 *   advanced by the time between them, at the speed it was put back from ([coherent]).
 * - How clean a match is says much: real recordings come back at a skew of 0.0001 to 0.0003 (85% of
 *   1,382 windows replayed in September and October), a wrong song at 0.003 or more. See [Quality].
 *
 * The answer is a song two windows [agreed] on, or one a convincing match names that most of the
 * windows so far back up ([settled]), or, when the listen is over, either of those ([last]). A
 * song named once by a match that is not convincing is not an answer.
 */
internal class ClipEvidence {

    /** How clean a match is, by Shazam's own time and frequency skew. */
    enum class Quality(val weight: Double) {
        /** Both skews under 0.002: the recording itself, at the speed it was put back from. */
        CONVINCING(2.0),

        /** Both under 0.01: [SpeedRetry.fits]. A copy that is a little off, or a wrong song that fits. */
        GOOD(1.0),

        /** A match that needed stretching to fit. */
        POOR(0.5),
    }

    /**
     * One window's answer. [fromS] is where the window starts in the sound heard and [lengthS] how
     * long it is, as heard. [rate] is the speed it was put back from, 1 as heard. [window] is the
     * question it answered, for counting how many windows a song explains.
     */
    class Sighting(
        val window: Int,
        val fromS: Double,
        val lengthS: Double,
        val rate: Double,
        val track: Recognised,
        val heardAtMs: Long = 0L,
    ) {
        val quality: Quality get() = qualityOf(track)
        private val skew: Double get() = abs(track.timeSkew) + abs(track.frequencySkew)

        /** Cleaner first, then the later one. */
        internal fun betterThan(other: Sighting): Boolean =
            quality.weight > other.quality.weight ||
                    (quality.weight == other.quality.weight && skew < other.skew)
    }

    /** One song and what was heard of it. [chain] is the most of its sightings that agree with each other. */
    class Song(val sightings: List<Sighting>, val chain: List<Sighting>) {
        /** The sighting to name it by: the cleanest of those that agree, else the cleanest. */
        val best: Sighting = (chain.ifEmpty { sightings }).reduce { a, b -> if (b.betterThan(a)) b else a }
        val windows: Int = sightings.map { it.window }.toSet().size

        /** Two or more windows of different sound put it in the same place. */
        val agreed: Boolean get() = chain.size >= 2
        val convincing: Boolean get() = sightings.any { it.quality == Quality.CONVINCING }

        /** What the windows that agree weigh, and a quarter of a sighting for every other one. */
        val score: Double = chain.sumOf { it.quality.weight } + 0.25 * (sightings.size - chain.size)
    }

    private val all = mutableListOf<Sighting>()

    /** Windows asked about so far, whatever Shazam said. */
    var windows = 0
        private set

    /** How long the clip is, once it has been heard to be a loop: the sound after that comes round again. */
    var loopSeconds: Double? = null

    /** A window is about to be asked about: the number to give its sightings. */
    fun nextWindow(): Int = windows++

    fun note(sighting: Sighting) {
        all += sighting
    }

    val sightings: List<Sighting> get() = all

    /** Every song named so far, the best supported first. */
    fun songs(): List<Song> {
        val groups = mutableListOf<MutableList<Sighting>>()
        for (s in all) {
            val group = groups.firstOrNull { g -> g.any { same(it.track, s.track) } }
            if (group != null) group += s else groups += mutableListOf(s)
        }
        return groups.map { Song(it, chainOf(it)) }.sortedByDescending { it.score }
    }

    /** A song that two windows of different sound put in the same place: the answer as soon as it is there. */
    fun agreed(): Song? = songs().firstOrNull { it.agreed }

    /**
     * A song a convincing match names that the windows asked so far back up, without waiting for the
     * rest of the clip: every window of two, both clean (a house track that loops its sample is
     * named at 173, 171 and 57 s in by three clean windows, and agrees on no position), or half of
     * [SETTLED_WINDOWS] or more, and no other song nearly as well supported. A remix of a song that
     * keeps its singer gets one window of the original and then nothing like it, so one clean window
     * is never enough.
     */
    fun settled(): Song? {
        if (windows < 2) return null
        val ranked = songs()
        val top = ranked.firstOrNull() ?: return null
        if (!top.convincing) return null
        val backed = if (windows >= SETTLED_WINDOWS) top.windows.toDouble() / windows >= SETTLED_SHARE
        else top.windows == windows && top.sightings.count { it.quality == Quality.CONVINCING } >= 2
        if (!backed) return null
        val rival = ranked.getOrNull(1)
        if (rival != null && rival.score * RIVAL_LEAD > top.score) return null
        return top
    }

    /** The listen is over: the best supported song that is an answer at all, or null. */
    fun last(): Song? = songs().firstOrNull { it.agreed || it.convincing }

    /** For the log: each song named, with its windows and its score. */
    fun summary(): String = songs().joinToString("; ") { song ->
        "'${song.best.track.title}' ${song.sightings.size} window(s)" +
                (if (song.agreed) ", agreed" else "") + (if (song.convincing) ", convincing" else "") +
                ", score ${"%.2f".format(song.score)}"
    }.ifEmpty { "nothing named" }

    /**
     * Whether [a] and [b] heard different sound: at most [MOST_SHARED] of the shorter one in common.
     * In a loop, sound is placed on the turn it goes round, so the same stretch heard a turn apart
     * is one stretch, and a window as long as the turn holds all of it.
     */
    internal fun independent(a: Sighting, b: Sighting): Boolean {
        val shorter = min(a.lengthS, b.lengthS)
        return commonSeconds(a, b) <= shorter * MOST_SHARED + 1e-9
    }

    private fun commonSeconds(a: Sighting, b: Sighting): Double {
        val turn = loopSeconds
        if (turn == null || turn <= 0.0) return overlap(a.fromS, a.fromS + a.lengthS, b.fromS, b.fromS + b.lengthS)
        if (a.lengthS >= turn || b.lengthS >= turn) return minOf(a.lengthS, b.lengthS, turn)
        val a0 = a.fromS.mod(turn)
        val b0 = b.fromS.mod(turn)
        var common = 0.0
        for (shift in doubleArrayOf(-turn, 0.0, turn)) {
            common += overlap(a0, a0 + a.lengthS, b0 + shift, b0 + shift + b.lengthS)
        }
        return min(common, min(a.lengthS, b.lengthS))
    }

    private fun overlap(a0: Double, a1: Double, b0: Double, b1: Double): Double = max(0.0, min(a1, b1) - max(a0, b0))

    /**
     * Whether [later] puts the song where [earlier] says it has moved on to: its position advanced
     * by the time between the two windows, at the speed both were put back from, to within a
     * second and a half and a hundredth of the time. Looser, a recurring song ("Nobody Told Me" came
     * back in four of the reel's windows at 223, 63, 77 and 87 s in) agreed with itself.
     */
    internal fun coherent(earlier: Sighting, later: Sighting): Boolean {
        if (abs(earlier.rate - later.rate) > 1e-9) return false
        val apart = later.fromS - earlier.fromS
        val moved = later.track.offsetSeconds - earlier.track.offsetSeconds
        val tolerance = TOLERANCE_S + TOLERANCE_PER_S * abs(apart)
        val expected = apart * earlier.rate
        return abs(moved - expected) <= tolerance ||
                abs(moved - expected * (1.0 + later.track.timeSkew)) <= tolerance
    }

    /** The most weight of [sightings] that each agree with the one before and heard different sound from it. */
    private fun chainOf(sightings: List<Sighting>): List<Sighting> {
        val sorted = sightings.sortedBy { it.fromS }
        val best = DoubleArray(sorted.size)
        val from = IntArray(sorted.size) { -1 }
        for (i in sorted.indices) {
            best[i] = sorted[i].quality.weight
            for (j in 0 until i) {
                if (independent(sorted[j], sorted[i]) && coherent(sorted[j], sorted[i]) &&
                    best[j] + sorted[i].quality.weight > best[i]
                ) {
                    best[i] = best[j] + sorted[i].quality.weight
                    from[i] = j
                }
            }
        }
        var end = best.indices.maxByOrNull { best[it] } ?: return emptyList()
        val chain = ArrayList<Sighting>()
        while (end >= 0) {
            chain += sorted[end]
            end = from[end]
        }
        return chain.reversed()
    }

    companion object {
        /**
         * Both skews under this make a convincing match. Real recordings, as Shazam names them, sit
         * at 0.0001 to 0.0003; the reel's organ, tuned a little sharp, never came under 0.003.
         */
        const val CONVINCING_SKEW = 0.002

        /** Windows of different sound share no more than this much of the shorter one. */
        const val MOST_SHARED = 0.5

        /** Where a song may be from where it should be, between two windows: a second and a half, and a hundredth of the time. */
        const val TOLERANCE_S = 1.5
        const val TOLERANCE_PER_S = 0.01

        /** Windows asked before one clean match can be an answer with only half of them behind it, and that share. */
        const val SETTLED_WINDOWS = 3
        const val SETTLED_SHARE = 0.5

        /** How much better supported the best song must be than the next for a lone clean match to stand. */
        const val RIVAL_LEAD = 1.5

        fun qualityOf(track: Recognised): Quality {
            val time = abs(track.timeSkew)
            val frequency = abs(track.frequencySkew)
            return when {
                time < CONVINCING_SKEW && frequency < CONVINCING_SKEW -> Quality.CONVINCING
                SpeedRetry.fits(track) -> Quality.GOOD
                else -> Quality.POOR
            }
        }

        /** Whether two matches are the same song: Shazam's key, or title and first artist for its other entries of it. */
        fun same(a: Recognised, b: Recognised): Boolean {
            val keyA = a.shazamKey
            val keyB = b.shazamKey
            if (keyA != null && keyA == keyB) return true
            if (!MixSearch.sameSong(a.title, b.title)) return false
            val artistA = a.artist?.let(MixSearch::primaryArtist)?.lowercase().orEmpty()
            val artistB = b.artist?.let(MixSearch::primaryArtist)?.lowercase().orEmpty()
            return artistA.isNotEmpty() && artistA == artistB
        }
    }
}
