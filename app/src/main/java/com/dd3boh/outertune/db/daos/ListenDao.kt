/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.db.daos

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import com.dd3boh.outertune.db.entities.Impression
import com.dd3boh.outertune.db.entities.Listen
import com.dd3boh.outertune.db.entities.RowBuild
import kotlinx.coroutines.flow.Flow

@Dao
interface ListenDao {

    @Insert
    fun insert(listen: Listen): Long

    @Insert
    fun insert(build: RowBuild): Long

    @Insert
    fun insertImpressions(impressions: List<Impression>)

    @Query("SELECT * FROM listen ORDER BY endedAt DESC LIMIT 1")
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
