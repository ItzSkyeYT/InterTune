/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.db

/**
 * Reads of the listen log that decide what a play is, kept as constants so ListenDao and
 * ListenSqlTest run the same text, the way [StatsSql] is.
 */
object ListenSql {

    /**
     * The latest play of :songId that was cut off where it stood (stopped, or stopped on a playback
     * error) or is still open, for linking a resume to it. An open row is included so that a newer
     * play still waiting for its close is found before an older stop, and then turned down by
     * ListenProgress.continues, which links only to a stop or an error.
     */
    const val LAST_RESUMABLE = """
        SELECT * FROM listen WHERE songId = :songId AND endReason IN (4, 5, 6) ORDER BY id DESC LIMIT 1
    """
}
