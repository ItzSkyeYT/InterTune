/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.utils

import com.dd3boh.outertune.db.entities.Song

/**
 * The text of an .m3u file and the name to give one. No Android in here, so it is tested.
 *
 * The playlist text is the one the playlist and folder menus have always written; both call
 * [playlist] now, so that exporting a whole library produces the same files as exporting one
 * playlist at a time, and the importer keeps reading what it always has. A local song is written
 * as its id and path, which is what the importer matches on; a YouTube song is written as a watch
 * link that any player can follow.
 */
object M3u {
    private const val FALLBACK_NAME = "Playlist"
    private const val EXTENSION = ".m3u"

    /**
     * What neither Android nor a FAT card accepts in a file name, plus the control range. A
     * playlist name is free text and a folder chosen through the picker is often an SD card, so
     * the stricter of the two rules is the one applied.
     */
    private val illegal = Regex("""[\\/:*?"<>|\p{Cntrl}]""")
    private val spaces = Regex(" +")

    fun playlist(songs: List<Song>): String {
        val out = StringBuilder("#EXTM3U\n")
        songs.forEach { s ->
            val se = s.song
            out.append("#EXTINF:${se.duration},${s.artists.joinToString(";") { it.name }} - ${s.title}\n")
            out.append(if (se.isLocal) "${se.id}, ${se.localPath}" else "https://youtube.com/watch?v=${se.id}")
            out.append("\n")
        }
        return out.toString()
    }

    /**
     * A file name for [playlistName] that is not in [taken], the names this has already handed
     * out. Two playlists may sanitise to the same name, and a provider asked for a name it holds
     * already would either fail or rename on its own terms, so the counter is decided here where
     * it is predictable. The comparison ignores case because the folder may well be case
     * insensitive.
     */
    fun fileName(playlistName: String, taken: Set<String>): String {
        val base = playlistName.trim()
            .replace(illegal, " ")
            .replace(spaces, " ")
            .trim()
            .ifEmpty { FALLBACK_NAME }
        val used = taken.mapTo(HashSet()) { it.lowercase() }
        var candidate = base + EXTENSION
        var n = 2
        while (candidate.lowercase() in used) {
            candidate = "$base ($n)$EXTENSION"
            n++
        }
        return candidate
    }
}
