/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.playback

import com.dd3boh.outertune.constants.EndReason
import com.dd3boh.outertune.engine.EngineInput
import com.dd3boh.outertune.engine.EngineLoader
import com.dd3boh.outertune.engine.EngineParams
import com.dd3boh.outertune.engine.Features
import com.dd3boh.outertune.engine.LibraryStats
import com.dd3boh.outertune.engine.Signals
import com.dd3boh.outertune.engine.SkipHabits
import com.dd3boh.outertune.engine.SongRow
import com.dd3boh.outertune.engine.SongTags
import com.dd3boh.outertune.engine.TagFit
import com.dd3boh.outertune.engine.Weights
import com.dd3boh.outertune.engine.versionKey
import com.dd3boh.outertune.models.MediaMetadata
import com.dd3boh.outertune.viewmodels.ExclusionsViewModel

/**
 * How well a song fits a radio right now: an upcoming one, or a fresh pick that might take its place.
 *
 * Built from the engine's own pieces, but only the ones that mean something inside a radio. The
 * Quick picks score as a whole leans on familiarity (activation, artist strength, likes, novelty,
 * co-occurrence, the day part), with weights learned from taps on Home, and it would judge a
 * familiar related song against a radio song the library has never seen by how well the listener
 * knows it: on a long history the radio would turn into the library within a few passes. So:
 *
 * ```
 * fit = w_seed x_seed + w_fit x_fit + w_sat x_sat + w_act x_act      the learned weights (Weights)
 *     - 0.5   when an artist of the song was skipped once in this run and not forgiven
 *     - 1.0 h the lasting habit against it (SkipHabits), none for a liked song or an artist kept in this run
 * ```
 *
 * - x_seed, related to what this run kept or liked: the share of this run's seeds whose stored
 *   related list holds the song, each weighed as the engine weighs a seed (LibraryStats.seedWeight).
 * - x_fit, the song's words against the run as the listener kept it (TagFit), live, not the log's
 *   copy, which can be minutes old.
 * - x_sat, the engine's brake on what was heard too much lately.
 * - x_act, how present it is in memory: what keeps being replayed scores higher.
 *
 * The session term is half the margin a score alone needs to replace a song ([REPLACE_MARGIN]):
 * one skip of an artist tips a close call and never decides one, since one skip is a mood. The
 * habit's weight is the size of the engine's own penalty priors (x_imp, x_over). The margin is one
 * logit of the engine's score, e times the odds: clearly better, not noise.
 *
 * No Android in here, so it is tested.
 */
object QueueFit {

    /** One skip of the song's artist in this run. */
    const val SESSION_SKIP = -0.5

    /** The weight of the lasting habit against a song. */
    const val HABIT_WEIGHT = 1.0

    /** How much better a pick must fit to replace a song for its score alone. */
    const val REPLACE_MARGIN = 1.0

    /**
     * The lasting half of the taste, from the listen log: the engine's stats, the skip habits, the
     * learned weights, the songs the log names, the bans, and what was heard so lately that a
     * radio should not bring it back. Built once and kept a few minutes (the session itself is
     * always live in AdaptiveQueue.Heard, so an older copy only ages the habits).
     */
    class Taste(
        val now: Long,
        val stats: LibraryStats,
        val habits: SkipHabits,
        val weights: Weights,
        val songs: Map<String, SongRow>,
        /** Songs banned or snoozed, by id and by title and first artist ([versionKey]), so their other uploads go too. */
        val bannedSongIds: Set<String> = emptySet(),
        val bannedSongKeys: Set<String> = emptySet(),
        /** Artists banned or snoozed, by id and by name in lower case. */
        val bannedArtists: Set<String> = emptySet(),
        /** Songs YouTube no longer serves. */
        val goneIds: Set<String> = emptySet(),
        /** Started in the current listening session, in any queue, or heard well in the last hour: by id and by key. */
        val freshIds: Set<String> = emptySet(),
        val freshKeys: Set<String> = emptySet(),
        val listens: Int = 0,
    ) {
        /** Whether the listener has played [id] before. */
        fun heardBefore(id: String): Boolean = (stats.songs[id]?.listens ?: 0) > 0

        fun liked(song: MediaMetadata): Boolean = song.liked || songs[song.id]?.liked == true

        /** Banned or snoozed: the song, another upload of it, or its artist. */
        fun banned(song: MediaMetadata): Boolean {
            if (song.id in bannedSongIds) return true
            if (bannedSongKeys.isNotEmpty() && versionKey(song.title, song.artists.firstOrNull()?.name) in bannedSongKeys) return true
            if (bannedArtists.isEmpty()) return false
            return song.artists.any { (it.id != null && it.id in bannedArtists) || it.name.trim().lowercase() in bannedArtists }
        }

        /** Heard too lately for a radio to bring it back as a pick: the engine row's own freshness rule. */
        fun justHeard(song: MediaMetadata): Boolean =
            song.id in freshIds || versionKey(song.title, song.artists.firstOrNull()?.name) in freshKeys

        /**
         * The lasting habit against [song], none when it is liked or its first artist had a song
         * kept, liked or heard again in this run ([exempt], lower case): no habit stands against
         * what the listener is doing right now.
         */
        fun habit(song: MediaMetadata, exempt: Set<String>): Double {
            val artist = song.artists.firstOrNull()
            if (artist != null && artist.name.trim().lowercase() in exempt) return 0.0
            return habits.of(song.title, artist?.id, artist?.name, liked(song))
        }

        companion object {
            /** No history at all, or a read that failed: the engine's priors and no habits. */
            fun empty(now: Long): Taste = Taste(
                now = now,
                stats = LibraryStats(EngineInput(now, emptyMap(), emptyList(), emptyList())),
                habits = SkipHabits.NONE,
                weights = Weights.PRIORS,
                songs = emptyMap(),
            )

            /** The taste from what EngineLoader.loadHeard read. */
            fun of(rows: EngineLoader.HeardRows, p: EngineParams = EngineParams.DEFAULT): Taste {
                val input = rows.input
                val now = input.now
                val inForce = rows.exclusions
                val bans = inForce.filter { it.reason == ExclusionsViewModel.REASON_BAN || it.reason == ExclusionsViewModel.REASON_SNOOZE }
                val bannedSongIds = bans.filter { it.kind == ExclusionsViewModel.KIND_SONG }.mapTo(HashSet()) { it.targetId }
                // A song ban's label is the song's title; its artist comes from the song itself
                // where the library has it, as Home's tidy pass matches a ban's other uploads.
                val bannedSongKeys = bans.filter { it.kind == ExclusionsViewModel.KIND_SONG }.mapNotNullTo(HashSet()) { ban ->
                    val song = input.songs[ban.targetId]
                    (song?.title ?: ban.label.takeIf { it.isNotBlank() })?.let { versionKey(it, song?.artistName) }
                }
                val bannedArtists = bans.filter { it.kind == ExclusionsViewModel.KIND_ARTIST }
                    .flatMapTo(HashSet()) { listOf(it.targetId, it.label.trim().lowercase()) }
                val goneIds = inForce.filter { it.kind == ExclusionsViewModel.KIND_SONG && it.reason == ExclusionsViewModel.REASON_GONE }
                    .mapTo(HashSet()) { it.targetId }
                // The engine row's own freshness rule (EngineParams.engineFreshHours): nothing from
                // the current listening session, in any queue, and nothing heard well in the last hour.
                val session = input.listens.maxByOrNull { it.startedAt }?.sessionId
                val freshCut = now - p.engineFreshHours * 3_600_000L
                val fresh = input.listens.filter { l ->
                    l.endReason != EndReason.ERROR && (l.sessionId == session ||
                        (l.endedAt >= freshCut && Signals.engagement(l, input.songs[l.songId]?.likedAt, p) >= p.justPlayedEngagement))
                }
                val freshIds = fresh.mapTo(HashSet()) { it.songId }
                val freshKeys = fresh.mapNotNullTo(HashSet()) { l -> input.songs[l.songId]?.let { versionKey(it.title, it.artistName) } }
                return Taste(
                    now = now,
                    stats = LibraryStats(input, p),
                    habits = SkipHabits(input.listens, input.songs, now, p),
                    weights = rows.weights,
                    songs = input.songs,
                    bannedSongIds = bannedSongIds,
                    bannedSongKeys = bannedSongKeys,
                    bannedArtists = bannedArtists,
                    goneIds = goneIds,
                    freshIds = freshIds,
                    freshKeys = freshKeys,
                    listens = input.listens.size,
                )
            }
        }
    }

    /**
     * What this run says about any song: the run's sound as kept ([tags]), the seeds whose related
     * list holds each song ([referrers]), the artists skipped once and not forgiven ([artistSkips]),
     * and those with a positive in the run ([exempt]), all lower case.
     */
    class Run(
        val tags: TagFit.Context,
        val referrers: Map<String, List<String>> = emptyMap(),
        val artistSkips: Set<String> = emptySet(),
        val exempt: Set<String> = emptySet(),
    )

    /** A song's fit and the parts a pass reads on their own: its lasting habit, its words against the run, one skip of its artist. */
    data class Score(val fit: Double, val habit: Double, val tagFit: Double, val oneSkip: Boolean)

    fun score(song: MediaMetadata, taste: Taste, run: Run, p: EngineParams = EngineParams.DEFAULT): Score {
        val st = taste.stats.songs[song.id]
        val r = run.referrers[song.id].orEmpty().sumOf { taste.stats.seedWeight(it) }
        val xSeed = r / (1 + r)
        val tagFit = TagFit.score(SongTags.of(song.title), run.tags)
        val xSat = (((st?.exposure ?: 0.0) - p.satiationStart) / p.satiationRamp).coerceIn(0.0, 1.0)
        val xAct = LibraryStats.squash(st?.activation ?: 0.0)
        val w = taste.weights
        val base = w[Features.names[Features.SEED]] * xSeed + w[Features.names[Features.FIT]] * tagFit +
            w[Features.names[Features.SAT]] * xSat + w[Features.names[Features.ACT]] * xAct
        val oneSkip = song.artists.any { it.name.trim().lowercase() in run.artistSkips }
        val habit = taste.habit(song, run.exempt)
        val fit = base + (if (oneSkip) SESSION_SKIP else 0.0) - HABIT_WEIGHT * habit
        return Score(fit, habit, tagFit, oneSkip)
    }
}
