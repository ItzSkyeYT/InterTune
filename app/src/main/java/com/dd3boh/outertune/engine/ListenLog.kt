/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.engine

import com.dd3boh.outertune.constants.EndReason

/**
 * The listen log as the adaptive queue last read it, so that the next read fetches only what can
 * have changed since, and hands back the very list a full read gives: every row, in id order.
 *
 * Each pass of a radio read the whole log again, "taste read in 1436 ms, 14961 listens" on 23 Oct
 * 2026 (experiments/features/battery-1023/MEASURE.md section 5), growing with every listen. Rows
 * are only ever added at the top, since the table is AUTOINCREMENT and its ids only grow, and the
 * one row that keeps changing is the one still open, the song playing. Anything else (a listen
 * forgotten on Your data, a queue no longer learned from, a song deleted with its listens, the
 * whole history cleared, a backup put back) changes rows already read, and [Mark] sees it: the
 * log is then read again from the start. ListenLogTest holds the merged log to a full read over
 * random histories on the real schema, and the taste made from it to the taste of a full read.
 *
 * Not thread-safe by itself: [read] is synchronized, and the caller is the adaptive dispatcher.
 */
class ListenLog {

    /** What a read needs of the database: EngineSql's queries, so the tests stand in with JDBC. */
    interface Source {
        /** Every row, in id order ([EngineSql.LISTENS_IN_ORDER]). */
        fun all(): List<ListenRow>

        /** In one statement, so one state of the log: the rows above [after] and those of [open] still there, in id order ([EngineSql.LISTENS_NEW_OR_OPEN]). */
        fun newOrOpen(after: Long, open: Collection<Long>): List<ListenRow>

        /** [Mark] of the rows with an id up to [upTo], [except] left out ([EngineSql.LISTEN_MARK]). */
        fun mark(upTo: Long, except: Collection<Long>): Mark
    }

    /** A count and two sums over a stretch of rows that change whenever one of its rows changes or goes. */
    data class Mark(val count: Long, val sumA: Long, val sumB: Long)

    /** The log as last read, in id order. */
    private var rows: List<ListenRow> = emptyList()
    private var top = 0L
    /** The rows still open at the last read: they change as the song plays and ends, so they are read again each time. */
    private var open: Set<Long> = emptySet()
    /** [Mark] of the other rows, as they were read; null before the first read. */
    private var settled: Mark? = null

    /** How the last read went, for the log line: whether it read everything, and how many rows it fetched. */
    var lastWasFull = true
        private set
    var lastFetched = 0
        private set

    @Synchronized
    fun read(source: Source): List<ListenRow> {
        val known = settled
        // Many rows left open is a log after crashes: read whole, as an id list that long would
        // pass the host parameters older phones' SQLite allows (999).
        if (known != null && open.size <= MAX_OPEN && runCatching { source.mark(top, open) }.getOrNull() == known) {
            val fetched = source.newOrOpen(top, open)
            val merged = (rows.filter { it.id !in open } + fetched).sortedBy { it.id }
            keep(merged)
            lastWasFull = false
            lastFetched = fetched.size
            return merged
        }
        val all = source.all()
        keep(all)
        lastWasFull = true
        lastFetched = all.size
        return all
    }

    /** Forgets everything read, so the next read reads the whole log. */
    @Synchronized
    fun clear() {
        rows = emptyList()
        top = 0L
        open = emptySet()
        settled = null
    }

    private fun keep(read: List<ListenRow>) {
        rows = read
        top = read.maxOfOrNull { it.id } ?: 0L
        open = read.filter { it.endReason == EndReason.OPEN }.mapTo(HashSet()) { it.id }
        // Worked out from the rows as they were read, not asked of the database afterwards: a row
        // that changed in between then fails the next comparison and is read again.
        settled = markOf(read.filter { it.id !in open })
    }

    companion object {
        /** More rows open than this and the log is read whole. */
        const val MAX_OPEN = 200

        /**
         * [EngineSql.LISTEN_MARK]'s numbers, worked out in memory: the two must stay the very same
         * sums, which ListenLogTest checks on every read it makes. Every column of
         * [EngineSql.LISTEN_COLUMNS] but the song is in both, each with its own factor, and the
         * second weighs each row by its id, so rows that trade values still change it. The song of
         * a row never changes: no write in the app changes it (ListenLogTest holds them to that),
         * and a song deleted takes its listens with it, which the count sees.
         */
        fun markOf(rows: Iterable<ListenRow>): Mark {
            var count = 0L
            var a = 0L
            var b = 0L
            for (l in rows) {
                count++
                val learn = if (l.learn) 1L else 0L
                val impression = l.impressionId ?: 0L
                val continues = l.continuesListenId ?: 0L
                a += (l.id % 1000003) * 31 + (l.startedAt % 999961) + (l.endedAt % 1000033) * 3 + (l.playedMs % 999983) * 5 +
                    (l.durationMs % 999979) * 7 + l.endReason * 104729L + l.origin * 7919L + l.autoplayDepth * 7907L +
                    (l.sessionId % 99929) * 11 + (l.tzOffsetMin % 9973) * 13L + learn * 104723 + (l.runId % 99991) * 17 +
                    (l.queueId % 99989) * 19 + (impression % 99971) * 23 + l.contextChip * 104717L + (continues % 99961) * 29
                b += ((l.id % 65521) + 1) * ((l.startedAt % 65519) + (l.endedAt % 65497) * 2 + (l.playedMs % 65479) * 3 +
                    (l.durationMs % 65449) * 5 + l.endReason * 7L + l.origin * 11L + (l.autoplayDepth % 65437) * 13L +
                    (l.sessionId % 65423) * 17 + (l.tzOffsetMin % 65413) * 19L + learn * 23 + (l.runId % 65393) * 29 +
                    (l.queueId % 65381) * 31 + (impression % 65371) * 37 + l.contextChip * 41L + (continues % 65357) * 43)
            }
            return Mark(count, a, b)
        }
    }
}
