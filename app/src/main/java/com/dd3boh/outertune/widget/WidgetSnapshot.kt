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
)

data class WidgetSnapshot(
    val nowPlaying: WidgetSong? = null,
    val isPlaying: Boolean = false,
    /** Quick picks as Home last showed them, so the widget and the app agree. */
    val picks: List<WidgetSong> = emptyList(),
    val updatedAt: Long = 0L,
)

object WidgetCodec {
    private const val VERSION = "1"
    private const val TAB = '\t'

    fun encode(snapshot: WidgetSnapshot): String = buildString {
        append(VERSION).append('\n')
        append("at").append(TAB).append(snapshot.updatedAt).append(TAB).append(if (snapshot.isPlaying) 1 else 0).append('\n')
        snapshot.nowPlaying?.let { append(line("now", it)) }
        snapshot.picks.forEach { append(line("pick", it)) }
    }

    fun decode(text: String?): WidgetSnapshot {
        if (text.isNullOrBlank()) return WidgetSnapshot()
        val lines = text.trim().lines()
        if (lines.firstOrNull()?.trim() != VERSION) return WidgetSnapshot()
        var now: WidgetSong? = null
        var playing = false
        var at = 0L
        val picks = ArrayList<WidgetSong>()
        for (line in lines.drop(1)) {
            val f = line.split(TAB)
            when (f.getOrNull(0)) {
                "at" -> { at = f.getOrNull(1)?.toLongOrNull() ?: 0L; playing = f.getOrNull(2) == "1" }
                "now" -> now = song(f)
                "pick" -> song(f)?.let { picks += it }
            }
        }
        return WidgetSnapshot(now, playing, picks, at)
    }

    private fun line(kind: String, s: WidgetSong): String =
        listOf(kind, s.id, esc(s.title), esc(s.artist), esc(s.artPath.orEmpty()), esc(s.thumbnailUrl.orEmpty()), s.durationSec.toString(), if (s.isLocal) "1" else "0")
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

/**
 * How much of the widget's content fits the space the launcher gave it. Pure arithmetic, so the
 * shape of the widget at every size is decided by a test rather than by dragging one around a
 * home screen.
 */
object WidgetLayout {
    /** Below this height there is room for one line of song and the controls, and nothing else. */
    const val COMPACT_HEIGHT_DP = 110

    /** A row of a list, including its padding. */
    const val PICK_ROW_DP = 56

    /** What the now playing block takes before any picks. */
    const val NOW_PLAYING_DP = 96

    /** Narrower than this and the skip buttons go, leaving play and pause. */
    const val WIDE_ENOUGH_DP = 180

    /** Never more than this many picks, however tall the widget is: past that it is a list, not a widget. */
    const val MAX_PICKS = 6

    fun showsSkipButtons(widthDp: Int): Boolean = widthDp >= WIDE_ENOUGH_DP

    fun showsArtwork(heightDp: Int): Boolean = heightDp >= 72

    /** How many Quick picks fit under the now playing block at this height. */
    fun pickCount(heightDp: Int, available: Int): Int {
        if (heightDp < COMPACT_HEIGHT_DP + PICK_ROW_DP) return 0
        val room = (heightDp - NOW_PLAYING_DP) / PICK_ROW_DP
        return room.coerceIn(0, minOf(MAX_PICKS, available))
    }
}
