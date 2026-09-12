/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.widget

/**
 * What the home screen widget draws, and the file it is kept in.
 *
 * A widget is drawn by the launcher, often long after the app's process has gone, so it cannot ask
 * the app anything: everything it shows has to be sitting on disk, written the last time the app
 * had something to say. That is this. The app writes it when the song changes, when playback
 * starts or stops, and when Home fills its Quick picks row; the widget reads it and draws.
 *
 * Plain text, one record per line, tab separated, the way the stored rows of the engine are kept.
 * A file a person can read is a file whose bugs are visible, and nothing here is worth a schema.
 */
data class WidgetSong(
    val id: String,
    val title: String,
    val artist: String,
    /** A small square image cached on disk for the widget to draw, or null while it is being fetched. */
    val artPath: String? = null,
    /** Enough to start playing this without a lookup, for the tap that opens the app. */
    val thumbnailUrl: String? = null,
    val durationSec: Int = 0,
    /** Whether the song is on this device already, which decides how the art is loaded. */
    val isLocal: Boolean = false,
    /** The artwork's own colour, for a widget told to take its background from the cover. */
    val colour: Int? = null,
)

data class WidgetSnapshot(
    val nowPlaying: WidgetSong? = null,
    val isPlaying: Boolean = false,
    /** Quick picks as Home last showed them, so the widget and the app agree. */
    val picks: List<WidgetSong> = emptyList(),
    /** Forgotten favourites, the same row Home shows under that name. */
    val forgotten: List<WidgetSong> = emptyList(),
    /** Keep listening, songs only: an album or an artist is not something a widget row can play. */
    val keepListening: List<WidgetSong> = emptyList(),
    /** What was played last, newest first, kept by the widget itself as songs come and go. */
    val recent: List<WidgetSong> = emptyList(),
    val updatedAt: Long = 0L,
) {
    /** The list this widget was set to show. */
    fun list(which: WidgetList): List<WidgetSong> = when (which) {
        WidgetList.QUICK_PICKS -> picks
        WidgetList.FORGOTTEN_FAVOURITES -> forgotten
        WidgetList.KEEP_LISTENING -> keepListening
        WidgetList.RECENT -> recent
    }

    fun withList(which: WidgetList, songs: List<WidgetSong>): WidgetSnapshot = when (which) {
        WidgetList.QUICK_PICKS -> copy(picks = songs)
        WidgetList.FORGOTTEN_FAVOURITES -> copy(forgotten = songs)
        WidgetList.KEEP_LISTENING -> copy(keepListening = songs)
        WidgetList.RECENT -> copy(recent = songs)
    }

    /** Every song the snapshot names, for the artwork it needs. */
    fun songs(): List<WidgetSong> = listOfNotNull(nowPlaying) + picks + forgotten + keepListening + recent
}

object WidgetCodec {
    private const val VERSION = "1"
    private const val TAB = '\t'

    fun encode(snapshot: WidgetSnapshot): String = buildString {
        append(VERSION).append('\n')
        append("at").append(TAB).append(snapshot.updatedAt).append(TAB).append(if (snapshot.isPlaying) 1 else 0).append('\n')
        snapshot.nowPlaying?.let { append(line("now", it)) }
        snapshot.picks.forEach { append(line("pick", it)) }
        snapshot.forgotten.forEach { append(line("forgotten", it)) }
        snapshot.keepListening.forEach { append(line("keep", it)) }
        snapshot.recent.forEach { append(line("recent", it)) }
    }

    fun decode(text: String?): WidgetSnapshot {
        if (text.isNullOrBlank()) return WidgetSnapshot()
        val lines = text.trim().lines()
        if (lines.firstOrNull()?.trim() != VERSION) return WidgetSnapshot()
        var now: WidgetSong? = null
        var playing = false
        var at = 0L
        val picks = ArrayList<WidgetSong>()
        val forgotten = ArrayList<WidgetSong>()
        val keep = ArrayList<WidgetSong>()
        val recent = ArrayList<WidgetSong>()
        for (line in lines.drop(1)) {
            val f = line.split(TAB)
            when (f.getOrNull(0)) {
                "at" -> { at = f.getOrNull(1)?.toLongOrNull() ?: 0L; playing = f.getOrNull(2) == "1" }
                "now" -> now = song(f)
                "pick" -> song(f)?.let { picks += it }
                "forgotten" -> song(f)?.let { forgotten += it }
                "keep" -> song(f)?.let { keep += it }
                "recent" -> song(f)?.let { recent += it }
                // An unknown kind is a file written by a later version: skipped, not fatal.
            }
        }
        return WidgetSnapshot(now, playing, picks, forgotten, keep, recent, at)
    }

    private fun line(kind: String, s: WidgetSong): String =
        listOf(kind, s.id, esc(s.title), esc(s.artist), esc(s.artPath.orEmpty()), esc(s.thumbnailUrl.orEmpty()), s.durationSec.toString(), if (s.isLocal) "1" else "0", s.colour?.toString().orEmpty())
            .joinToString(TAB.toString()) + "\n"

    private fun song(f: List<String>): WidgetSong? {
        val id = f.getOrNull(1)?.takeIf { it.isNotEmpty() } ?: return null
        return WidgetSong(
            id = id,
            title = unesc(f.getOrNull(2).orEmpty()),
            artist = unesc(f.getOrNull(3).orEmpty()),
            artPath = unesc(f.getOrNull(4).orEmpty()).takeIf { it.isNotEmpty() },
            thumbnailUrl = unesc(f.getOrNull(5).orEmpty()).takeIf { it.isNotEmpty() },
            durationSec = f.getOrNull(6)?.toIntOrNull() ?: 0,
            isLocal = f.getOrNull(7) == "1",
            colour = f.getOrNull(8)?.toIntOrNull(),
        )
    }

    /** A song title can hold anything at all, including the two characters this format is made of. */
    private fun esc(s: String) = s.replace("\\", "\\\\").replace("\t", "\\t").replace("\n", "\\n")

    private fun unesc(s: String): String {
        if ('\\' !in s) return s
        val out = StringBuilder(s.length)
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c == '\\' && i + 1 < s.length) {
                when (s[i + 1]) {
                    't' -> { out.append('\t'); i += 2; continue }
                    'n' -> { out.append('\n'); i += 2; continue }
                    '\\' -> { out.append('\\'); i += 2; continue }
                }
            }
            out.append(c); i++
        }
        return out.toString()
    }
}
