/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.db.daos

import com.dd3boh.outertune.db.entities.EngineWeight
import com.dd3boh.outertune.engine.EngineSql
import com.dd3boh.outertune.db.entities.RecommendationExclusion
import com.dd3boh.outertune.engine.EngineSeedsRow
import com.dd3boh.outertune.engine.EngineExclusionRow
import com.dd3boh.outertune.engine.EngineSeenRow
import com.dd3boh.outertune.engine.EngineEdgeRow
import com.dd3boh.outertune.engine.EngineSongRow
import com.dd3boh.outertune.engine.PlayedSong
import com.dd3boh.outertune.engine.LegacyEventRow
import androidx.room.Transaction
import androidx.room.Dao
import androidx.room.Insert
import com.dd3boh.outertune.db.entities.SongVersionMap
import com.dd3boh.outertune.db.entities.ListenSignal
import androidx.room.OnConflictStrategy
import androidx.room.Update
import androidx.room.Query
import com.dd3boh.outertune.db.entities.Impression
import com.dd3boh.outertune.db.entities.Listen
import com.dd3boh.outertune.db.entities.RowBuild
import kotlinx.coroutines.flow.Flow

@Dao
interface ListenDao {

    @Insert
    fun insert(listen: Listen): Long

    @Update
    fun update(listen: Listen)

    /** Rows opened at play start that were never closed: the app died with them playing. */
    @Query("SELECT * FROM listen WHERE endReason = 6")
    fun openListens(): List<Listen>

    @Query("UPDATE listen SET playedMs = :playedMs, endPositionMs = :positionMs WHERE id = :id AND endReason = 6")
    fun checkpoint(id: Long, playedMs: Long, positionMs: Long)

    /** The latest stopped or still-open play of this song, for linking a resume to it. */
    @Query("DELETE FROM listen WHERE id = :id AND endReason = 6")
    fun discardOpenListen(id: Long)

    @Query("SELECT * FROM listen WHERE songId = :songId AND endReason IN (4, 6) ORDER BY id DESC LIMIT 1")
    fun lastStoppedListen(songId: String): Listen?

    @Insert
    fun insertSignal(signal: ListenSignal)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    fun insertVersionMap(rows: List<SongVersionMap>)

    @Query("SELECT id FROM listen WHERE songId = :songId AND endReason = 6 ORDER BY id DESC LIMIT 1")
    fun openListenId(songId: String): Long?

    /** Something done about a song from the interface, tied to its open listen if it is playing right now. */
    @Transaction
    fun noteSignal(songId: String, kind: Int, value: Float) {
        insertSignal(ListenSignal(listenId = openListenId(songId), songId = songId, kind = kind, value = value, at = System.currentTimeMillis()))
    }

    @Query("UPDATE impression SET tappedAt = :at WHERE id = :id")
    fun markImpressionTapped(id: Long, at: Long)

    @Query("SELECT id FROM impression WHERE tappedAt = :tappedAt ORDER BY id DESC LIMIT 1")
    fun impressionIdByTap(tappedAt: Long): Long?

    // ---- Backfill of the legacy play log, see engine/LegacyBackfill.kt. The same SQL runs in its JVM test.
    @Query("""SELECT e.id, e.songId, e.timestamp, e.playTime, s.duration FROM event e JOIN song s ON s.id = e.songId
        WHERE e.id > :afterId AND NOT EXISTS (SELECT 1 FROM listen l WHERE l.sourceEventId = e.id) ORDER BY e.id LIMIT :limit""")
    fun pendingLegacyEvents(afterId: Long, limit: Int): List<LegacyEventRow>

    @Query("SELECT * FROM listen WHERE sourceEventId IS NOT NULL ORDER BY sourceEventId DESC LIMIT 1")
    fun lastBackfilledListen(): Listen?

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    fun insertLegacyListens(rows: List<Listen>)

    @Query("""UPDATE related_song_map SET fetchedAt = (SELECT MIN(l.startedAt) FROM listen l WHERE l.songId = related_song_map.songId)
        WHERE fetchedAt = 0 AND EXISTS (SELECT 1 FROM listen l WHERE l.songId = related_song_map.songId)""")
    fun dateLegacyRelatedEdges()

    @Query("DELETE FROM related_song_map WHERE id NOT IN (SELECT MIN(id) FROM related_song_map GROUP BY songId, relatedSongId)")
    fun dropDuplicateRelatedEdges()

    /** A live listen that also wrote a legacy event row, so the backfill never doubles it. */
    @Query("UPDATE listen SET sourceEventId = :eventId WHERE id = :id")
    fun setListenSource(id: Long, eventId: Long)

    @Query("SELECT COUNT(*) FROM impression WHERE tappedAt IS NOT NULL")
    fun tapCount(): Flow<Int>

    /**
     * What the listener has just had: heard at engagement 0.5 or more (45% of a known length, or
     * two minutes of an unknown one) in the last day, or started at all in the given session.
     */
    @Query("""SELECT DISTINCT s.id AS id, s.title AS title,
        (SELECT a.name FROM song_artist_map m JOIN artist a ON a.id = m.artistId WHERE m.songId = s.id ORDER BY m.position LIMIT 1) AS artist
        FROM listen l JOIN song s ON s.id = l.songId
        WHERE (l.startedAt >= :dayAgo AND l.playedMs >= 30000 AND ((l.durationMs > 0 AND l.playedMs * 20 >= l.durationMs * 9) OR (l.durationMs <= 0 AND l.playedMs >= 120000)))
           OR l.sessionId = :sessionId""")
    fun justPlayed(dayAgo: Long, sessionId: Long): List<PlayedSong>

    /** When a seed's related list was fetched (its oldest edge), null with no edges, 0 for a legacy list of unknown age. */
    @Query("SELECT MIN(fetchedAt) FROM related_song_map WHERE songId = :songId")
    fun relatedFetchedAt(songId: String): Long?

    @Query("DELETE FROM related_song_map WHERE songId = :songId")
    fun deleteRelated(songId: String)

    // ---- The engine's input, see engine/EngineLoader.kt. Plain rows, no entities.
    @Query(EngineSql.SONGS)
    fun engineSongs(): List<EngineSongRow>

    @Query("SELECT songId, startedAt, endedAt, playedMs, durationMs, endReason, origin, autoplayDepth, sessionId, tzOffsetMin, learn, runId, queueId, impressionId FROM listen")
    fun engineListens(): List<com.dd3boh.outertune.engine.ListenRow>

    // ---- The loop: grading what was shown, applying what was graded, keeping the weights.
    @Query("SELECT * FROM impression WHERE gradedAt IS NULL AND visibleAt IS NOT NULL ORDER BY id")
    fun pendingImpressions(): List<Impression>

    @Query("UPDATE impression SET outcome = :outcome, y = :y, u = :u, gradedAt = :at WHERE id = :id")
    fun markGraded(id: Long, outcome: Int, y: Float, u: Float, at: Long)

    /** Graded examples the engine placed, not yet applied, oldest first. */
    @Query("SELECT * FROM impression WHERE gradedAt IS NOT NULL AND appliedAt IS NULL AND features IS NOT NULL AND u > 0 ORDER BY gradedAt, id")
    fun unappliedExamples(): List<Impression>

    /** Everything applied so far, in the order it was applied: a rebuild replays exactly this. */
    @Query("SELECT * FROM impression WHERE appliedAt IS NOT NULL AND features IS NOT NULL AND u > 0 ORDER BY appliedAt, id")
    fun appliedExamples(): List<Impression>

    @Query("UPDATE impression SET appliedAt = :at WHERE id = :id")
    fun markApplied(id: Long, at: Long)

    @Query("UPDATE impression SET appliedAt = NULL WHERE appliedAt IS NOT NULL")
    fun unapplyAll()

    @Query("SELECT * FROM engine_weight")
    fun engineWeights(): List<EngineWeight>

    @Query("SELECT * FROM engine_weight")
    fun engineWeightsFlow(): Flow<List<EngineWeight>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun upsertEngineWeights(rows: List<EngineWeight>)

    @Query("SELECT team AS team, outcome AS outcome, COUNT(*) AS n, SUM(CASE WHEN y >= 0.5 THEN 1 ELSE 0 END) AS wins FROM impression WHERE gradedAt IS NOT NULL GROUP BY team, outcome")
    fun gradedByTeam(): Flow<List<TeamOutcome>>

    /** The engine's slotted, graded cards: prediction beside grade, for the Brier score and the reliability table. */
    @Query("SELECT p AS p, y AS y FROM impression WHERE team = 1 AND slot >= 0 AND gradedAt IS NOT NULL AND u > 0 AND p IS NOT NULL AND y IS NOT NULL")
    fun engineCalibration(): Flow<List<PredictionGrade>>

    @Query("SELECT songId, relatedSongId FROM related_song_map")
    fun engineEdges(): List<EngineEdgeRow>

    @Query("SELECT songId, versionId, fetchedAt FROM song_version_map")
    fun engineVersionLinks(): List<SongVersionMap>

    @Query("SELECT songId, visibleAt FROM impression WHERE visibleAt >= :since AND tappedAt IS NULL AND visibleAt > 0")
    fun engineSeen(since: Long): List<EngineSeenRow>

    @Query("SELECT kind, targetId, label, reason FROM recommendation_exclusion WHERE expiresAt IS NULL OR expiresAt > :now")
    fun engineExclusions(now: Long): List<EngineExclusionRow>

    @Query("SELECT builtAt, seeds FROM row_build WHERE rowKey IN (1, 4) AND builtAt >= :since")
    fun engineRecentSeeds(since: Long): List<EngineSeedsRow>

    // ---- Exclusions: what the recommendation rows must never suggest.
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun insertExclusion(exclusion: RecommendationExclusion): Long

    @Query("SELECT * FROM recommendation_exclusion WHERE kind = :kind AND targetId = :targetId LIMIT 1")
    fun exclusion(kind: Int, targetId: String): RecommendationExclusion?

    @Query("DELETE FROM recommendation_exclusion WHERE id = :id")
    fun deleteExclusion(id: Long)

    @Query("SELECT * FROM recommendation_exclusion ORDER BY createdAt DESC")
    fun exclusions(): Flow<List<RecommendationExclusion>>

    @Query("SELECT COUNT(*) FROM recommendation_exclusion WHERE expiresAt IS NULL OR expiresAt > :now")
    fun activeExclusionCount(now: Long): Flow<Int>

    /** An exclusion in force, with its target's version group left to the caller. */
    @Query("SELECT kind, targetId, label, reason FROM recommendation_exclusion WHERE expiresAt IS NULL OR expiresAt > :now")
    fun activeExclusions(now: Long): Flow<List<EngineExclusionRow>>

    // ---- Forget this listening: a session or a day stops teaching, and what it taught is skipped.
    @Query("UPDATE listen SET learn = 0 WHERE sessionId = :sessionId")
    fun forgetSession(sessionId: Long)

    @Query("UPDATE listen SET learn = 0 WHERE startedAt >= :from AND startedAt < :to")
    fun forgetBetween(from: Long, to: Long)

    @Query("UPDATE impression SET u = 0, outcome = 6 WHERE id IN (SELECT impressionId FROM listen WHERE learn = 0 AND impressionId IS NOT NULL)")
    fun dropForgottenExamples()

    @Query("SELECT COUNT(*) FROM event WHERE songId = :songId")
    fun playCountOf(songId: String): Int

    @Query("SELECT liked FROM song WHERE id = :id")
    fun isLiked(id: String): Boolean?

    @Query("SELECT COUNT(*) FROM listen_signal")
    fun signalCount(): Flow<Int>

    @Insert
    fun insert(build: RowBuild): Long

    @Insert
    fun insertImpressions(impressions: List<Impression>): List<Long>

    /** The latest closed listen, for the session rule; an open row has no end yet. */
    @Query("SELECT * FROM listen WHERE endReason != 6 ORDER BY endedAt DESC LIMIT 1")
    fun lastListen(): Listen?

    /** Pulls a queue out of what the engine learns from, after the fact. */
    @Query("UPDATE listen SET learn = :learn WHERE queueId = :queueId")
    fun setQueueLearns(queueId: Long, learn: Boolean)

    @Query("SELECT COUNT(*) FROM listen")
    fun listenCount(): Flow<Int>

    @Query("SELECT COUNT(*) FROM listen WHERE counted")
    fun countedListenCount(): Flow<Int>

    @Query("SELECT COUNT(DISTINCT sessionId) FROM listen")
    fun sessionCount(): Flow<Int>

    @Query("SELECT endReason AS code, COUNT(*) AS n FROM listen GROUP BY endReason")
    fun listensByEndReason(): Flow<List<CodeCount>>

    @Query("SELECT origin AS code, COUNT(*) AS n FROM listen GROUP BY origin")
    fun listensByOrigin(): Flow<List<CodeCount>>

    @Query("SELECT COUNT(*) FROM impression")
    fun impressionCount(): Flow<Int>

    @Query("SELECT * FROM listen ORDER BY endedAt DESC LIMIT :limit")
    fun recentListens(limit: Int): Flow<List<Listen>>

    @Query("""
        SELECT listen.id, song.title, listen.endReason, listen.origin, listen.originSlot, listen.ratio,
               listen.playedMs, listen.endedAt, listen.counted, listen.autoplayDepth
        FROM listen JOIN song ON song.id = listen.songId
        ORDER BY listen.endedAt DESC LIMIT :limit
    """)
    fun recentListenRows(limit: Int): Flow<List<ListenRow>>

    @Query("SELECT duration FROM song WHERE id = :id")
    fun songDurationSec(id: String): Int?

    @Query("UPDATE song SET duration = :duration WHERE id = :id")
    fun setSongDuration(id: String, duration: Int)

    @Query("SELECT EXISTS(SELECT 1 FROM song WHERE id = :id)")
    fun songExists(id: String): Boolean

    @Query("SELECT COUNT(*) FROM row_build")
    fun rowBuildCount(): Flow<Int>

    @Query("SELECT * FROM row_build WHERE rowKey = :rowKey ORDER BY builtAt DESC LIMIT 1")
    fun lastBuild(rowKey: Int): RowBuild?

    /** Builds whose day is over and not yet scored, oldest first. */
    @Query("SELECT * FROM row_build WHERE gradedAt IS NULL AND cards IS NOT NULL AND builtAt < :before ORDER BY builtAt")
    fun buildsToScore(before: Long): List<RowBuild>

    @Query("SELECT MIN(builtAt) FROM row_build WHERE rowKey = :rowKey AND builtAt > :after")
    fun nextBuildAt(rowKey: Int, after: Long): Long?

    @Query("UPDATE row_build SET plays = :plays, hits = :hits, gradedAt = :at WHERE id = :id")
    fun scoreBuild(id: Long, plays: Int, hits: Int, at: Long)

    @Query("SELECT rowKey AS rowKey, COUNT(*) AS builds, SUM(plays) AS plays, SUM(hits) AS hits FROM row_build WHERE gradedAt IS NOT NULL GROUP BY rowKey")
    fun buildScores(): Flow<List<BuildScore>>

    @Query("SELECT COUNT(*) FROM impression WHERE buildId = :buildId AND slot < 0")
    fun poolPicksOf(buildId: Long): Int

    data class CodeCount(val code: Int, val n: Int)

    /** One listen with its title, for the report; nothing else needs the join. */
    data class ListenRow(
        val id: Long, val title: String, val endReason: Int, val origin: Int, val originSlot: Int,
        val ratio: Float, val playedMs: Long, val endedAt: Long, val counted: Boolean, val autoplayDepth: Int,
    )
}

data class TeamOutcome(val team: Int, val outcome: Int, val n: Int, val wins: Int)
data class PredictionGrade(val p: Float, val y: Float)
data class BuildScore(val rowKey: Int, val builds: Int, val plays: Int, val hits: Int)
