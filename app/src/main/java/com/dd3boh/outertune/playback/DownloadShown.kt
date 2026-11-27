/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.playback

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.time.LocalDateTime

/**
 * What the screens show of a song's download: what DownloadUtil's map holds, and until the download
 * folders have been listed since the start, what the database records.
 *
 * The map is filled by that listing (DownloadUtil.rescanDownloads), which on a phone with a download
 * folder takes a few seconds or more after a cold start. Until then every downloaded song showed the
 * plain Download arrow: the player's button and menu, every row. Opened again after it was closed,
 * the app showed the song it came back to as not downloaded (his report of 27 Nov 2026).
 */
internal object DownloadShown {

    /**
     * The download of [id] as shown: the map's entry, else the database's record, unless something
     * has changed the song's download since the start ([touched], DownloadUtil.scanTouchedIds): a
     * download removed during the listing is gone from the map and must not come back from the record.
     */
    fun of(id: String, live: Map<String, LocalDateTime>, recorded: Map<String, LocalDateTime>, touched: Set<String>): LocalDateTime? =
        live[id] ?: recorded[id]?.takeIf { id !in touched }

    /** The database's rows as a record to show: only downloads that have landed, by the map's own rule (hasLanded). */
    fun recorded(rows: List<Pair<String, LocalDateTime?>>): Map<String, LocalDateTime> =
        rows.mapNotNull { (id, date) -> date?.takeIf { hasLanded(it) }?.let { id to it } }.toMap()
}

/**
 * The database's record of the downloads, shown from the start until the first listing of the download
 * folders has landed in the map ([listed]), and never again after it: a record offered later, by a read
 * that answered after the listing, is not taken. See [DownloadShown].
 */
internal class RecordedUntilListed {
    private val _shown = MutableStateFlow<Map<String, LocalDateTime>>(emptyMap())
    val shown: StateFlow<Map<String, LocalDateTime>> = _shown.asStateFlow()
    private var listed = false

    @Synchronized
    fun offer(recorded: Map<String, LocalDateTime>) {
        if (!listed) _shown.value = recorded
    }

    @Synchronized
    fun listed() {
        listed = true
        _shown.value = emptyMap()
    }
}
