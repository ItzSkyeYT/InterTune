/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.engine

import com.dd3boh.outertune.constants.EndReason
import kotlin.math.pow

/**
 * What the listener keeps skipping, across days: per artist and per song, how much less often than
 * their own habit a play of it is kept.
 *
 * The engine has no memory of skips. Signals gives a skip under half a minute no value at all, a
 * later skip almost never takes activation below zero, and nothing counts skips per song or per
 * artist. This adds that one signal, from the same listen log and in the engine's own terms, so
 * that a radio can leave out an artist somebody has turned down for a week.
 *
 * **Rows.** The listens of the last [EngineParams.contextWindowDays] days that are meant to teach
 * and that say how they ended: played to the end, or skipped. Stopped, replaced, failed, still open
 * and legacy rows carry no verdict. A row is a skip when it was skipped before half of it was heard
 * (or before half a minute, its length unknown), the rule a queue reads a skip by
 * (AdaptiveQueue.leftEarly); every other one is a keep. Unlike Signals, a skip in the first half
 * minute counts, because that is most skips and the most decided of them. What does not count in
 * full is a burst of them: somebody pressing next five times in ten seconds is scanning a radio,
 * not judging five artists, so a run of quick skips in a row, inside one session, counts as one
 * skip shared between its songs. This departs from Signals on purpose.
 *
 * **Weights.** Each row weighs `(days + 1)^-0.5`, the engine's own decay ([EngineParams.activationDecay]).
 *
 * **Penalty.** The listener's own keep share `B = (Σ w kept + 1) / (Σ w + 2)`, one keep and one skip
 * of pseudo-count so a new install sits at one half. An artist's `K = (Σ w kept + 4 B) / (Σ w + 4)`,
 * with the engine's context prior of 4 ([EngineParams.contextPrior]), so an artist heard a few times
 * sits at the listener's own rate. Its penalty is `log2(B / K)` held to [0, 1], the shape of x_ctx's
 * lift. A song's, by its title and first artist ([versionKey]), so another upload of it counts. An
 * artist skipped at the listener's own rate gets nothing, whether they skip a lot or a little: no
 * number here is fitted to anyone.
 *
 * A [penalty] of [DISLIKE] or more, kept at half the listener's own rate or less, is a lasting
 * dislike: with nothing kept, four weighted skips or more, which is five in a day or about eight
 * over a week. A liked song has none.
 */
class SkipHabits(listens: List<ListenRow>, songs: Map<String, SongRow>, now: Long, p: EngineParams = EngineParams.DEFAULT) {

    /** The listener's own keep share, B. */
    val base: Double

    /** How many rows it was built from. */
    val rows: Int

    private val byArtistId = HashMap<String, Double>()
    private val byArtistName = HashMap<String, Double>()
    private val bySong = HashMap<String, Double>()

    init {
        val day = 86_400_000.0
        val cut = now - p.contextWindowDays * day
        val ordered = listens.sortedBy { it.startedAt }
        // A burst: consecutive quick skips in one session, sharing one skip between them.
        val share = DoubleArray(ordered.size) { 1.0 }
        var i = 0
        while (i < ordered.size) {
            if (!quick(ordered[i], p)) { i++; continue }
            var j = i
            while (j + 1 < ordered.size && quick(ordered[j + 1], p) && ordered[j + 1].sessionId == ordered[i].sessionId) j++
            val n = j - i + 1
            for (k in i..j) share[k] = 1.0 / n
            i = j + 1
        }
        class Tally { var all = 0.0; var kept = 0.0 }
        val artistsById = HashMap<String, Tally>()
        val artistsByName = HashMap<String, Tally>()
        val songsByKey = HashMap<String, Tally>()
        var all = 0.0
        var kept = 0.0
        var counted = 0
        for ((index, l) in ordered.withIndex()) {
            if (!l.learn || l.startedAt < cut) continue
            if (l.endReason != EndReason.ENDED && l.endReason != EndReason.SKIPPED) continue
            counted++
            val w = ((now - l.startedAt) / day + 1.0).coerceAtLeast(1.0).pow(-p.activationDecay) * share[index]
            val keep = if (isSkip(l)) 0.0 else w
            all += w
            kept += keep
            val song = songs[l.songId] ?: continue
            for (t in listOfNotNull(
                song.artistId?.let { artistsById.getOrPut(it) { Tally() } },
                song.artistName?.trim()?.lowercase()?.takeIf { it.isNotEmpty() }?.let { artistsByName.getOrPut(it) { Tally() } },
                songsByKey.getOrPut(versionKey(song.title, song.artistName)) { Tally() },
            )) {
                t.all += w
                t.kept += keep
            }
        }
        rows = counted
        base = (kept + 1.0) / (all + 2.0)
        fun penalty(t: Tally): Double {
            val k = (t.kept + p.contextPrior * base) / (t.all + p.contextPrior)
            return LibraryStats.log2(base / k).coerceIn(0.0, 1.0)
        }
        artistsById.forEach { (key, t) -> penalty(t).takeIf { it > 0 }?.let { byArtistId[key] = it } }
        artistsByName.forEach { (key, t) -> penalty(t).takeIf { it > 0 }?.let { byArtistName[key] = it } }
        songsByKey.forEach { (key, t) -> penalty(t).takeIf { it > 0 }?.let { bySong[key] = it } }
    }

    /**
     * An artist's penalty, found by its id or by its name in lower case, whichever is higher: the
     * library may hold an artist YouTube gave no id under one of its own, while the radio's copy of
     * the same artist has none.
     */
    fun artist(id: String?, name: String?): Double = maxOf(
        id?.let { byArtistId[it] } ?: 0.0,
        name?.trim()?.lowercase()?.let { byArtistName[it] } ?: 0.0,
    )

    /** A song's own penalty, by its title and first artist, any upload of it. */
    fun song(title: String?, artist: String?): Double = title?.let { bySong[versionKey(it, artist)] } ?: 0.0

    /** The habit against a song: its artist's or its own, whichever is higher, and none once [liked]. */
    fun of(title: String?, artistId: String?, artistName: String?, liked: Boolean): Double =
        if (liked) 0.0 else maxOf(artist(artistId, artistName), song(title, artistName))

    companion object {
        /** A habit this strong or stronger is a lasting dislike. */
        const val DISLIKE = 1.0

        /** No listens at all: every penalty is zero. */
        val NONE = SkipHabits(emptyList(), emptyMap(), 0L)

        /** Skipped before half of it was heard, or half a minute when its length is not known. */
        fun isSkip(l: ListenRow): Boolean = l.endReason == EndReason.SKIPPED &&
            if (l.durationMs > 0) l.playedMs * 2 < l.durationMs else l.playedMs < 30_000L

        /** Skipped before the engine's floor for having an opinion ([EngineParams.minPositiveMs]). */
        private fun quick(l: ListenRow, p: EngineParams): Boolean = l.endReason == EndReason.SKIPPED && l.playedMs < p.minPositiveMs
    }
}
