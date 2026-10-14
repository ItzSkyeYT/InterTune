/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.lyrics

/**
 * The order the lyrics sources are asked in, as set under Lyrics sources.
 *
 * Kept as the sources' ids joined by commas. A source the stored order does not name, one added
 * in a later version or a store from before the order could be set, takes its default place at
 * the end, so an old setting never leaves a source out; an id nothing knows any more is dropped.
 */
object LyricsSources {
    const val YOUTUBE_CAPTIONS = "youtube_captions"
    const val LRCLIB = "lrclib"
    const val KUGOU = "kugou"
    const val YOUTUBE_MUSIC = "youtube_music"

    /** The order they were always asked in, before anyone could change it. */
    val DEFAULT = listOf(YOUTUBE_CAPTIONS, LRCLIB, KUGOU, YOUTUBE_MUSIC)

    /** Every source, in the order [saved] gives and then the default one for the rest. */
    fun order(saved: String?): List<String> {
        val named = saved.orEmpty().split(',').map { it.trim() }.filter { it in DEFAULT }.distinct()
        return named + DEFAULT.filterNot { it in named }
    }

    fun encode(order: List<String>): String = order.joinToString(",")
}
