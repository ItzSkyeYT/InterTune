/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.engine

import com.dd3boh.outertune.db.MusicDatabase
import com.dd3boh.outertune.db.entities.Listen

/** The backfill's view of the app database. Blocking; call it off the main thread. */
class DatabaseBackfillIo(private val database: MusicDatabase) : BackfillIo {
    override fun pendingEvents(afterId: Long, limit: Int): List<LegacyEventRow> = database.pendingLegacyEvents(afterId, limit)

    override fun lastBackfilled(): BackfillCursor? = database.lastBackfilledListen()?.let { BackfillCursor(it.endedAt, it.sessionId) }

    override fun insertLegacyListens(rows: List<Listen>) = database.transactionNow { insertLegacyListens(rows) }

    override fun tidyLegacyEdges() = database.transactionNow {
        dateLegacyRelatedEdges()
        dropDuplicateRelatedEdges()
    }
}
