/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.engine

import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.sql.DriverManager
import kotlin.random.Random

/**
 * What Quick picks would hold right after a library copy was taken, row by row, for a picture of
 * a change before it is on a phone. Gated on `ENGINE_PREVIEW=<a copy of song.db>`; the copy is
 * copied again into `ENGINE_PREVIEW_WORK` before it is opened. `ENGINE_PREVIEW_ARMS` names the rows
 * as [TrialArm] does (default AUTO and the new Auto), `ENGINE_PREVIEW_OUT` gets one line a card:
 * row, place, title, artist, lane, and 1 for a song new to the listener.
 */
class QuickPicksPreviewTrial {
    @Test
    fun preview() {
        val path = System.getenv("ENGINE_PREVIEW")?.takeIf { it.isNotBlank() }
        assumeTrue("ENGINE_PREVIEW not set", path != null && File(path).isFile)
        val work = File(System.getenv("ENGINE_PREVIEW_WORK") ?: File(path!!).parent).apply { mkdirs() }
        val copy = File.createTempFile("preview", ".db", work).apply { deleteOnExit() }
        File(path!!).copyTo(copy, overwrite = true)
        val arms = (System.getenv("ENGINE_PREVIEW_ARMS")?.takeIf { it.isNotBlank() } ?: "AUTO,MIX+ranker+cap3").split(',').map { it.trim() }
        val out = System.getenv("ENGINE_PREVIEW_OUT")?.takeIf { it.isNotBlank() }?.let { File(it).printWriter() }
        DriverManager.getConnection("jdbc:sqlite:${copy.path}").use { db ->
            val last = db.createStatement().use { st -> st.executeQuery("SELECT MAX(endedAt) AS e, (SELECT tzOffsetMin FROM listen ORDER BY endedAt DESC LIMIT 1) AS tz FROM listen").use { rs -> rs.next(); rs.getLong("e") to rs.getInt("tz") } }
            // An hour after the last listen, so the row is the one the next opening would show.
            val now = last.first + 3_600_000L
            val input = JdbcEngineInput.load(db, now, last.second)
            val heardWell = LibraryStats(input).songs.filterValues { it.goodListens > 0 }.keys
            val groups = VersionGroups(input.songs.values, input.versionLinks)
            val heardGroups = heardWell.mapTo(HashSet()) { groups.groupOf(it) }
            for (arm in arms) {
                val row = EngineRow.build(input, p = TrialArm.params(arm), random = Random(16), stored = TrialArm.lean(arm))
                println("$arm: ${row.cards.size} cards, lean ${row.lean}")
                for ((i, c) in row.cards.withIndex()) {
                    val s = input.songs[c.songId]
                    val new = groups.groupOf(c.songId) !in heardGroups
                    out?.println("$arm\t${i + 1}\t${s?.title.orEmpty().replace('\t', ' ')}\t${s?.artistName.orEmpty().replace('\t', ' ')}\t${c.lane.name}\t${if (new) 1 else 0}")
                }
            }
        }
        out?.close()
    }
}
