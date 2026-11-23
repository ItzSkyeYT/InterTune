/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.playback.downloadManager

/**
 * Inside the app chosen while a download folder is set (the choice of two on the simpler Storage
 * page) keeps that folder as one of the extra folders the app reads: the songs already in it stay
 * in Downloads and play offline, and only new downloads stay inside the app.
 *
 * It used to unset the folder and nothing more, at one tap and with no question: at the next start
 * the scan found no folder and took every song in it out of Downloads, the extra folders' songs
 * with them, and the liked ones were downloaded again inside the app. The files were still on the
 * phone. Nothing a person had downloaded leaves Downloads because of that choice.
 */
object InsideKeepsFolder {
    /** The extra folders, as their addresses, once [main] is no longer the download folder. */
    fun extras(main: String, extras: List<String>): List<String> =
        if (main.isBlank() || extras.any { it == main }) extras else extras + main
}
