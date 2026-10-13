/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.migration

/**
 * A tool that writes the file an import reads, and where it lives. [host] is the one place its
 * pages may be on: an address here is opened in the browser from a tap, so it is never built
 * from anything but these constants.
 */
enum class ImportTool(val label: String, val host: String) {
    EXPORTIFY("Exportify", "exportify.app"),
    TUNEMYMUSIC("TuneMyMusic", "www.tunemymusic.com"),
}

/**
 * The apps an import can come from, in the order the import's first page lists them, and for
 * each the tool that exports from it and the page of that tool to open.
 *
 * As read on the tools' own pages on 12 Oct 2026: Exportify is free with no limit and knows
 * Spotify only. TuneMyMusic exports to a CSV file on its free plan, up to 500 songs, and has a
 * page for each app it reads (/transfer/<app>-to-file; each address below answered that day).
 * Soundiiz exports files on its paid plans only, which is why it is not offered here, though a
 * file of its making is read like any other.
 *
 * [label] is the app's own name, the same in every language. [OTHER] has none: the page names
 * it in words (R.string.import_library_source_other).
 */
enum class ImportSource(val label: String, val tool: ImportTool, val address: String) {
    SPOTIFY("Spotify", ImportTool.EXPORTIFY, "https://exportify.app/"),
    APPLE_MUSIC("Apple Music", ImportTool.TUNEMYMUSIC, "https://www.tunemymusic.com/transfer/apple-music-to-file"),
    DEEZER("Deezer", ImportTool.TUNEMYMUSIC, "https://www.tunemymusic.com/transfer/deezer-to-file"),
    TIDAL("Tidal", ImportTool.TUNEMYMUSIC, "https://www.tunemymusic.com/transfer/tidal-to-file"),
    AMAZON_MUSIC("Amazon Music", ImportTool.TUNEMYMUSIC, "https://www.tunemymusic.com/transfer/amazon-music-to-file"),
    SOUNDCLOUD("SoundCloud", ImportTool.TUNEMYMUSIC, "https://www.tunemymusic.com/transfer/soundcloud-to-file"),
    QOBUZ("Qobuz", ImportTool.TUNEMYMUSIC, "https://www.tunemymusic.com/transfer/qobuz-to-file"),
    OTHER("", ImportTool.TUNEMYMUSIC, "https://www.tunemymusic.com/"),
    ;

    /** Apple's own Music app on a computer exports the whole library, which no other app here does. */
    val alsoFromComputer: Boolean get() = this == APPLE_MUSIC
}
