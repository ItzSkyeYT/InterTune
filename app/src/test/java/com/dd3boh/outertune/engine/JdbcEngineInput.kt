/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.engine

import com.dd3boh.outertune.constants.EndReason
import java.sql.Connection
import java.time.ZoneId

/** The engine's input read over JDBC from a version 21 database, the way the loader reads it from Room. */
object JdbcEngineInput {
    private fun Connection.rows(sql: String): List<Map<String, Any?>> = createStatement().use { st ->
        st.executeQuery(sql).use { rs ->
            val cols = (1..rs.metaData.columnCount).map { rs.metaData.getColumnName(it) }
            buildList { while (rs.next()) add(cols.associateWith { rs.getObject(it) }) }
        }
    }

    fun songs(db: Connection, zone: ZoneId = ZoneId.systemDefault()): Map<String, SongRow> =
        db.rows(EngineSql.SONGS).associate { r ->
            val id = r["id"] as String
            val liked = (r["liked"] as Number).toInt() != 0
            val likedDate = (r["likedDate"] as Number?)?.toLong()
            id to SongRow(id, r["title"] as String, r["artistId"] as String?, r["artistName"] as String?, liked,
                likedDate?.takeIf { liked }?.let { storedLocalToInstant(it, zone) }, r["inLibrary"] != null, (r["isLocal"] as Number).toInt() != 0)
        }

    fun listens(db: Connection, now: Long): List<ListenRow> =
        db.rows("SELECT songId, startedAt, endedAt, playedMs, durationMs, endReason, origin, autoplayDepth, sessionId, tzOffsetMin, learn, runId, queueId, impressionId, contextChip FROM listen").map {
            val endReason = (it["endReason"] as Number).toInt()
            ListenRow(it["songId"] as String, (it["startedAt"] as Number).toLong(), if (endReason == EndReason.OPEN) now else (it["endedAt"] as Number).toLong(),
                (it["playedMs"] as Number).toLong(), (it["durationMs"] as Number).toLong(), endReason, (it["origin"] as Number).toInt(),
                (it["autoplayDepth"] as Number).toInt(), (it["sessionId"] as Number).toLong(), (it["tzOffsetMin"] as Number).toInt(), (it["learn"] as Number).toInt() != 0,
                (it["runId"] as Number).toLong(), (it["queueId"] as Number).toLong(), (it["impressionId"] as Number?)?.toLong(), (it["contextChip"] as Number).toInt())
        }

    fun edges(db: Connection): List<Edge> = db.rows("SELECT songId, relatedSongId FROM related_song_map").map { Edge(it["songId"] as String, it["relatedSongId"] as String) }

    fun links(db: Connection): List<VersionLink> = db.rows("SELECT songId, versionId FROM song_version_map").map { VersionLink(it["songId"] as String, it["versionId"] as String) }

    fun load(db: Connection, now: Long, tzOffsetMin: Int = 0): EngineInput {
        val listens = listens(db, now)
        return EngineInput(now, songs(db), listens, edges(db), links(db), bucket = dayPartBucket(now, tzOffsetMin), tzOffsetMin = tzOffsetMin)
    }
}
