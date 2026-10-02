/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.engine

/*
 * The rows of the build log (row_build.rowKey), in one place, so How it's doing can name every row
 * the app writes. A key with no name there showed as a bare number: Try both's 5 read "5: held 0
 * of the 4 songs".
 */

/** Best recommendations' own row in Quick picks. */
const val ENGINE_ROW_KEY = 1

/** Your library's row in Quick picks, also what stands in when Best recommendations has too few cards. */
const val LIBRARY_ROW_KEY = 2

/** YouTube Music's shelf in Quick picks. */
const val YOUTUBE_ROW_KEY = 3

/** Best recommendations' row built in the background while another source fills Quick picks, never shown. */
const val SHADOW_ROW_KEY = 4

/** Try both's row in Quick picks: Best recommendations' cards and another source's, drawn in turn. */
const val COMPARE_ROW_KEY = 5

/** Discover something new. */
const val DISCOVER_ROW_KEY = 6

/** Every key the app writes. */
val ROW_KEYS = listOf(ENGINE_ROW_KEY, LIBRARY_ROW_KEY, YOUTUBE_ROW_KEY, SHADOW_ROW_KEY, COMPARE_ROW_KEY, DISCOVER_ROW_KEY)

/**
 * The key of the Quick picks row on screen, from the source HomeScreen says is showing it: 1
 * YouTube's shelf, 2 Best recommendations, 3 Try both, anything else the library row.
 */
fun quickPicksRowKey(shownSource: Int): Int = when (shownSource) {
    1 -> YOUTUBE_ROW_KEY
    2 -> ENGINE_ROW_KEY
    3 -> COMPARE_ROW_KEY
    else -> LIBRARY_ROW_KEY
}
