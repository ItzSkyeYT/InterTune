/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.engine

import com.dd3boh.outertune.constants.EndReason
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

data class EngineEdgeRow(val songId: String, val relatedSongId: String)
data class EngineSeenRow(val songId: String, val visibleAt: Long)
data class EngineExclusionRow(val kind: Int, val targetId: String)
data class EngineSeedsRow(val builtAt: Long, val seeds: String)

/**
 * Reads the whole engine input from Room in a handful of queries, on the calling (background)
 * thread. Nothing here scores anything: the engine takes plain rows so the JVM tests can feed it
 * the same shapes from a copy of a real database.
 */
object EngineLoader {
    fun load(database: MusicDatabase, now: Long = System.currentTimeMillis(), bucket: Int? = null): EngineInput {
        val tz = TimeZone.getDefault().getOffset(now) / 60_000
        val songs = HashMap<String, SongRow>()
        for (r in database.engineSongs()) {
            val likedAt = r.likedDate?.let { storedLocalToInstant(it) }
            val playable = !r.isLocal || r.localPath.isNullOrEmpty() || File(r.localPath).exists()
            songs[r.id] = SongRow(r.id, r.title, r.artistId, r.artistName, r.liked, likedAt?.takeIf { r.liked }, r.inLibrary != null, r.isLocal, playable)
        }
        val listens = database.engineListens().map { l ->
            // A row still open is the song playing now: it ends, for the engine's purposes, now.
            if (l.endReason == EndReason.OPEN) l.copy(endedAt = now) else l
        }
        val day = 86_400_000L
        return EngineInput(
            now = now,
            songs = songs,
            listens = listens,
            edges = database.engineEdges().map { Edge(it.songId, it.relatedSongId) },
            versionLinks = database.engineVersionLinks().map { VersionLink(it.songId, it.versionId) },
            seen = database.engineSeen(now - 14 * day).map { SeenCard(it.songId, it.visibleAt) },
            exclusions = database.engineExclusions(now).map { ExclusionRow(it.kind, it.targetId) },
            pastSeeds = database.engineRecentSeeds(now - 3 * 3_600_000L).map { PastSeeds(it.builtAt, parseSeeds(it.seeds)) },
            bucket = bucket ?: dayPartBucket(now, tz),
            tzOffsetMin = tz,
        )
    }

    /** The seeds column is a JSON array of ids; no library is needed to read one. */
    fun parseSeeds(json: String): List<String> =
        Regex("\"([^\"]+)\"").findAll(json).map { it.groupValues[1] }.toList()

    fun seedsJson(seeds: List<String>): String = seeds.joinToString(",", "[", "]") { "\"$it\"" }
}
