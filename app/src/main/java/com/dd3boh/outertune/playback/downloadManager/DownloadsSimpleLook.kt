/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.playback.downloadManager

import android.content.Context
import com.dd3boh.outertune.constants.Unreleased

/**
 * The simpler ways to download songs and to choose where they go, drawn beside the page of today
 * to be chosen from pictures (experiments/features/downloads-simple/sheet.png, 12 Nov 2026, after
 * 360 downloads were found never to have reached the folder chosen for them). He chose [C] the
 * same evening ("go with C for downloading"): it is the page wherever Unreleased.DOWNLOADS_SIMPLE
 * is on, [TODAY] elsewhere; the other two were taken out with what only they drew. C keeps
 * everything the page can do today: it says it in other words, puts it where it is looked for, or
 * does it itself.
 */
enum class DownloadsSimpleLook {
    /** The page and the Download action as they are. */
    TODAY,

    /**
     * The simpler page: today's in plain words ("Inside the app", "In your folder"), one row that
     * says where downloads go and how many, with the move on it while songs are still inside the
     * app and the way out when the folder cannot be opened; choosing is one step, "Inside the app"
     * or "In a folder", the phone's folder picker at once and the question about the move after;
     * and Download says where the songs went once they are there.
     */
    C;

    /** The page's words in plain words. */
    val plainWords: Boolean get() = this == C

    /** The one row for where downloads go, with the move on it. */
    val oneRow: Boolean get() = this == C

    /** Download says where the songs went once they are downloaded. */
    val saysWhenDone: Boolean get() = this == C

    companion object {
        /** The look last read, for the places that have no context to read it with (the menus' entries). */
        @Volatile
        var last: DownloadsSimpleLook = TODAY
            private set

        /** [C] where the simpler page is [offered], today's page elsewhere. */
        fun pick(offered: Boolean): DownloadsSimpleLook = if (offered) C else TODAY

        /** The look to draw now. The [context] is the callers' still: the look no longer reads a file. */
        @Suppress("UNUSED_PARAMETER")
        fun of(context: Context): DownloadsSimpleLook = pick(Unreleased.DOWNLOADS_SIMPLE).also { last = it }

        private const val PHONE_STORAGE = "/storage/emulated/0/"

        /** A folder as a person names it: "Music/InterTune", without the phone's own start of the path. */
        fun folderName(path: String): String =
            path.trimEnd('/').let { if (it.startsWith(PHONE_STORAGE)) it.removePrefix(PHONE_STORAGE) else it }.ifEmpty { path }

        /** The downloads that are files in a folder: all of them less the ones still inside the app. */
        fun inFolder(downloads: Int, inside: Int): Int = (downloads - inside).coerceAtLeast(0)
    }
}
