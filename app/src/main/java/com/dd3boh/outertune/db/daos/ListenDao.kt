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

    data class CodeCount(val code: Int, val n: Int)
}
