/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.utils

/**
 * Whether sync may remove the local items that a remote list does not contain: only when the list
 * was read in full and holds something. An empty answer is far more often a fetch that did not
 * work (throttled, signed out, a page in a new shape) than someone who removed everything, and a
 * removal made on a bad read cannot be undone from here.
 */
fun mayRemoveMissing(complete: Boolean, remoteCount: Int): Boolean = complete && remoteCount > 0

/**
 * Whether a playlist's songs may be replaced by a remote copy with [remoteCount] songs. Only a
 * complete read, and never an empty one over a playlist that has songs here: the same fetch that
 * fails for a whole library fails for one playlist.
 */
fun mayReplacePlaylist(complete: Boolean, remoteCount: Int, hasLocalSongs: Boolean): Boolean =
    complete && (remoteCount > 0 || !hasLocalSongs)
