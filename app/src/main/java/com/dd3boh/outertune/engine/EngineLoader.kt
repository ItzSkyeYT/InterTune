/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.engine

import com.dd3boh.outertune.constants.SimilarSource
import com.dd3boh.outertune.db.MusicDatabase
import java.io.File
import java.util.TimeZone

/** One song as the engine needs it, straight from the database. */
data class EngineSongRow(
    val id: String,
    val title: String,
    val artistId: String?,
    val artistName: String?,
    val liked: Boolean,
    /** As Converters stored it: the local wall clock read as UTC. */
    val likedDate: Long?,
    val inLibrary: Long?,
    val isLocal: Boolean,
    val localPath: String?,
)

/** [sources] is the edge's [Provenance] bits: 1 YouTube's list holds it, 2 Last.fm's, 3 both. */
data class EngineEdgeRow(val songId: String, val relatedSongId: String, val sources: Int)
data class EngineSeenRow(val songId: String, val visibleAt: Long)
data class EngineExclusionRow(val kind: Int, val targetId: String, val label: String, val reason: Int = 1)
data class EngineSeedsRow(val builtAt: Long, val seeds: String)

/**
 * Reads the whole engine input from Room in a handful of queries, on the calling (background)
 * thread. Nothing here scores anything: the engine takes plain rows so the JVM tests can feed it
 * the same shapes from a copy of a real database.
 */
object EngineLoader {
    /** [mode] is where similar songs come from; the caller has already applied similarSourceOf. */
    fun load(
        database: MusicDatabase,
        now: Long = System.currentTimeMillis(),
        bucket: Int? = null,
        chip: Int = ContextChip.AUTO,
        mode: SimilarSource = SimilarSource.YOUTUBE,
    ): EngineInput {
        val tz = TimeZone.getDefault().getOffset(now) / 60_000
        val (gone, exclusions) = splitGone(database.engineExclusions(now))
        val songs = HashMap<String, SongRow>()
        for (r in database.engineSongs()) {
            val likedAt = r.likedDate?.let { storedLocalToInstant(it) }
            val playable = r.id !in gone && (!r.isLocal || r.localPath.isNullOrEmpty() || File(r.localPath).exists())
            songs[r.id] = SongRow(r.id, r.title, r.artistId, r.artistName, r.liked, likedAt?.takeIf { r.liked }, r.inLibrary != null, r.isLocal, playable)
        }
        // A row still open is the song playing now: it ends, for the engine's purposes, now. A play
        // that failed is left out.
        val listens = EngineListens.forBuild(database.engineListens(), now)
        val day = 86_400_000L
        return EngineInput(
            now = now,
            songs = songs,
            listens = listens,
            edges = when (mode) {
                SimilarSource.YOUTUBE -> database.engineEdges(0)
                SimilarSource.LASTFM -> database.engineEdges(1)
                SimilarSource.BOTH -> database.engineEdgesAll()
            }.map { Edge(it.songId, it.relatedSongId, it.sources) },
            versionLinks = database.engineVersionLinks().map { VersionLink(it.songId, it.versionId) },
            seen = database.engineSeen(now - 14 * day).map { SeenCard(it.songId, it.visibleAt) },
            exclusions = exclusions,
            pastSeeds = database.engineRecentSeeds(now - 3 * 3_600_000L).map { PastSeeds(it.builtAt, parseSeeds(it.seeds)) },
            bucket = bucket ?: dayPartBucket(now, tz),
            tzOffsetMin = tz,
            chip = chip,
        )
    }

    /**
     * The exclusions in force, with the songs YouTube no longer serves taken out and returned by id.
     *
     * A ban covers a song by its title and artist, so that its other uploads go with it. A song
     * that is gone must not: another upload of it is exactly what should be offered in its place.
     * So it is not an exclusion to the row at all, only a song that cannot be played, like a local
     * file that is no longer there.
     */
    fun splitGone(rows: List<EngineExclusionRow>): Pair<Set<String>, List<ExclusionRow>> {
        val (gone, others) = rows.partition { it.kind == 1 && it.reason == REASON_GONE }
        return gone.mapTo(HashSet()) { it.targetId } to others.map { ExclusionRow(it.kind, it.targetId) }
    }

    /** ExclusionsViewModel.REASON_GONE, which this package does not otherwise reach for. */
    const val REASON_GONE = 4

    /** The seeds column is a JSON array of ids; no library is needed to read one. */
    fun parseSeeds(json: String): List<String> =
        Regex("\"([^\"]+)\"").findAll(json).map { it.groupValues[1] }.toList()

    fun seedsJson(seeds: List<String>): String = seeds.joinToString(",", "[", "]") { "\"$it\"" }
}
