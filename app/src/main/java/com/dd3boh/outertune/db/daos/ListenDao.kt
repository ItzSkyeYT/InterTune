/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.db.daos

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

    data class CodeCount(val code: Int, val n: Int)

    /** One listen with its title, for the report; nothing else needs the join. */
    data class ListenRow(
        val id: Long, val title: String, val endReason: Int, val origin: Int, val originSlot: Int,
        val ratio: Float, val playedMs: Long, val endedAt: Long, val counted: Boolean, val autoplayDepth: Int,
    )
}
