/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.engine

import kotlin.math.ln
import kotlin.math.sqrt

/** Everything the features need about one song, folded from its listens once per build. */
class SongStats {
    /** `Σ v (h + 1)^-0.5`, at most three listens per session, plus the like pseudo-listen. */
    var activation = 0.0
    /** The same sum over listens older than the dormancy window only. */
    var oldActivation = 0.0
    /** The same sum over listens in the build's day part, inside the context window. */
    var bucketActivation = 0.0
    /** `Σ g (days + 1)^-0.5` over the satiation window. */
    var exposure = 0.0
    /** Starts of listens heard well, newest first, at most [EngineParams.gapListens]. */
    val goodStarts = ArrayList<Long>()
    var lastGoodAt = 0L
    var lastStartedAt = 0L
    var listens = 0
    var goodListens = 0
    /** How many listens of this song each session has contributed to activation so far. */
    internal val perSession = HashMap<Long, Int>()
}

class ArtistStats {
    /** `Σ max(0, A)` over the artist's songs. */
    var strength = 0.0
    var goodListens = 0
    var positiveShort = 0.0
    var positiveLong = 0.0
    /** Listens heard well, by day-part bucket, over the context window. */
    val goodByBucket = IntArray(8)
    /** Listens heard well while the build's chip was on, over the context window. */
    var goodTagged = 0
}

/**
 * One fold of the listen log into per-song and per-artist numbers. Built once per row build and
 * shared by every candidate, so a library of tens of thousands of songs costs one pass.
 */
class LibraryStats(input: EngineInput, private val p: EngineParams = EngineParams.DEFAULT) {
    val now = input.now
    val songs = HashMap<String, SongStats>()
    val artists = HashMap<String, ArtistStats>()
    /** Artists heard well in each of the last [EngineParams.coSessions] sessions, newest first. */
    val sessionArtists: List<Set<String>>
    val latestSessionId: Long
    /** All listens heard well in the context window, by bucket. */
    val goodByBucket = IntArray(8)
    var goodInContextWindow = 0
    /** Listens heard well while the build's chip was on, in all and over the context window. */
    var goodTaggedAll = 0
    var goodTaggedInWindow = 0
    /** Likes stamped in a bulk import; their dates say nothing. */
    val bulkLikeSongIds: Set<String>

    private val day = 86_400_000.0

    private fun song(id: String) = songs.getOrPut(id) { SongStats() }
    private fun artist(id: String) = artists.getOrPut(id) { ArtistStats() }

    init {
        // Likes that arrived by the hundred inside a minute are an import, not a verdict.
        val byMinute = HashMap<Long, MutableList<String>>()
        for (s in input.songs.values) {
            val at = s.likedAt ?: continue
            byMinute.getOrPut(at / 60_000L) { ArrayList() }.add(s.id)
        }
        bulkLikeSongIds = byMinute.values.filter { it.size > p.bulkLikeThreshold }.flatten().toHashSet()

        val dormantCut = now - p.dormantAfterDays * day
        val satiationCut = now - p.satiationWindowDays * day
        val contextCut = now - p.contextWindowDays * day
        val shortCut = now - p.overplayShortDays * day
        val longCut = now - p.overplayLongDays * day
        val bySession = HashMap<Long, HashSet<String>>()
        val sessionLatest = HashMap<Long, Long>()

        // Newest first, so a session's three most recent listens of a song are the ones that count.
        for (l in input.listens.sortedByDescending { it.startedAt }) {
            val songRow = input.songs[l.songId]
            val likedAt = songRow?.likedAt?.takeIf { l.songId !in bulkLikeSongIds }
            val g = Signals.engagement(l, likedAt, p)
            val v = Signals.value(l, likedAt, p)
            val st = song(l.songId)
            st.listens++
            if (l.startedAt > st.lastStartedAt) st.lastStartedAt = l.startedAt
            val good = g >= p.justPlayedEngagement
            if (good) {
                st.goodListens++
                if (l.startedAt > st.lastGoodAt) st.lastGoodAt = l.startedAt
                if (st.goodStarts.size < p.gapListens) st.goodStarts.add(l.startedAt)
            }
            val used = st.perSession.getOrDefault(l.sessionId, 0)
            if (used < p.activationPerSession) {
                st.perSession[l.sessionId] = used + 1
                val term = v * Signals.recency(now, l.endedAt, p.activationDecay)
                st.activation += term
                if (l.endedAt < dormantCut) st.oldActivation += term
                if (l.endedAt >= contextCut && dayPartBucket(l.startedAt, l.tzOffsetMin) == input.bucket) st.bucketActivation += term
            }
            if (l.endedAt >= satiationCut && g > 0) {
                st.exposure += g * Math.pow((now - l.endedAt) / day + 1.0, -p.activationDecay)
            }
            val artistId = songRow?.artistId
            if (artistId != null) {
                val a = artist(artistId)
                if (good) {
                    a.goodListens++
                    if (l.startedAt >= contextCut) {
                        val b = dayPartBucket(l.startedAt, l.tzOffsetMin)
                        a.goodByBucket[b]++
                        goodByBucket[b]++
                        goodInContextWindow++
                        if (input.chip != ContextChip.AUTO && l.contextChip == input.chip) { a.goodTagged++; goodTaggedInWindow++ }
                    }
                    if (input.chip != ContextChip.AUTO && l.contextChip == input.chip) goodTaggedAll++
                    bySession.getOrPut(l.sessionId) { HashSet() }.add(artistId)
                    sessionLatest[l.sessionId] = maxOf(sessionLatest[l.sessionId] ?: 0L, l.startedAt)
                }
                if (v > 0) {
                    if (l.endedAt >= shortCut) a.positiveShort += v
                    if (l.endedAt >= longCut) a.positiveLong += v
                }
            }
        }
        // The like pseudo-listen, once per liked song with a usable date.
        for (s in input.songs.values) {
            val at = s.likedAt ?: continue
            if (s.id in bulkLikeSongIds) continue
            val term = p.likePseudoListen * Signals.recency(now, at, p.activationDecay)
            val st = song(s.id)
            st.activation += term
            if (at < dormantCut) st.oldActivation += term
        }
        for ((id, st) in songs) {
            val artistId = input.songs[id]?.artistId ?: continue
            artist(artistId).strength += maxOf(0.0, st.activation)
        }
        val ordered = sessionLatest.entries.sortedByDescending { it.value }
        latestSessionId = ordered.firstOrNull()?.key ?: -1L
        sessionArtists = ordered.take(p.coSessions).map { bySession[it.key] ?: emptySet() }
    }

    /** `Σ (0.5 + 0.5 max(0, x_act(s)))` over the seeds with an edge to the song, squashed by the caller. */
    fun seedWeight(seedId: String): Double = 0.5 + 0.5 * maxOf(0.0, squash(songs[seedId]?.activation ?: 0.0))

    companion object {
        /** `A / (1 + |A|)`: into (-1, 1), a fixed squash that never drifts as the library grows. */
        fun squash(a: Double): Double = a / (1 + Math.abs(a))

        /** Square-root sampling weight for the artist lane. */
        fun sqrtWeight(strength: Double): Double = sqrt(maxOf(0.0, strength))

        fun log2(x: Double): Double = ln(x) / ln(2.0)
    }
}
