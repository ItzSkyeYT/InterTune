/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.playback.downloadManager

/** A song picked for Export to folder (Unreleased.DOWNLOAD_FORMATS): its id, its name for the result, and whether it is a file the person already has. */
class ExportItem(val id: String, val title: String, val isLocal: Boolean = false)

/** Why a song was left out of an export. */
enum class SkipReason {
    /** It is neither downloaded nor cached whole, and an export never fetches a song. */
    NOT_ON_PHONE,

    /** A file of that name is in the folder already: the song's own file (Original into the download folder), or a copy from an export before. */
    ALREADY_THERE,

    /** A file on the phone that the library only scanned: there is nothing of YouTube's to export. */
    LOCAL,

    /** The conversion or the write failed; the reason is in the log. */
    FAILED,
}

class ExportSkip(val item: ExportItem, val reason: SkipReason)

/** How an export ended: how many songs were exported, which were not and why, and whether it was stopped before the end. */
class ExportResult(val exported: Int, val skipped: List<ExportSkip>, val stopped: Boolean = false) {
    fun count(reason: SkipReason): Int = skipped.count { it.reason == reason }
}

/** What an export says of itself in a line: "12 songs exported, 1 skipped: not downloaded". */
object ExportSummary {
    enum class Part { EXPORTED, NOT_ON_PHONE, ALREADY_THERE, LOCAL, FAILED }

    /**
     * The parts of the line in the order they are said, each with its count: how many were exported
     * always, even none, and a reason for skipping only when a song was skipped for it.
     */
    fun parts(result: ExportResult): List<Pair<Part, Int>> = buildList {
        add(Part.EXPORTED to result.exported)
        for ((reason, part) in REASONS) {
            val n = result.count(reason)
            if (n > 0) add(part to n)
        }
    }

    /** The titles of the songs left out for [reason], the first [limit] of them, and how many more there are. */
    fun named(result: ExportResult, reason: SkipReason, limit: Int = 4): Pair<List<String>, Int> {
        val all = result.skipped.filter { it.reason == reason }.map { it.item.title }
        return all.take(limit) to (all.size - limit).coerceAtLeast(0)
    }

    private val REASONS = listOf(
        SkipReason.NOT_ON_PHONE to Part.NOT_ON_PHONE,
        SkipReason.ALREADY_THERE to Part.ALREADY_THERE,
        SkipReason.LOCAL to Part.LOCAL,
        SkipReason.FAILED to Part.FAILED,
    )
}

/** Where an export finds a song's bytes: only on the phone already, in the order they are looked for. Nothing is ever fetched. */
object ExportSources {
    enum class Source { LOCAL_FILE, FOLDER_FILE, DOWNLOAD_CACHE, SONG_CACHE, NOWHERE }

    /**
     * A file the library scanned is a file already. Otherwise the song's file in a download folder, then
     * the download cache (the song downloaded and not moved to a folder yet), then the song cache when
     * it holds the song whole. A song that is in none of them is not on the phone.
     */
    fun pick(isLocal: Boolean, inFolder: Boolean, inDownloadCache: Boolean, inSongCache: Boolean): Source = when {
        isLocal -> Source.LOCAL_FILE
        inFolder -> Source.FOLDER_FILE
        inDownloadCache -> Source.DOWNLOAD_CACHE
        inSongCache -> Source.SONG_CACHE
        else -> Source.NOWHERE
    }
}
