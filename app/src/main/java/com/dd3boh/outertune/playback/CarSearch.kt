/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.playback

/**
 * What a search typed or said in the car is answered with, decided apart from
 * MediaLibrarySessionCallback so a test can hold it to account without a car.
 *
 * The library's songs first, then YouTube Music's, in every build. Until 0.13 only the debug and
 * preview builds asked YouTube Music: the question sat behind Unreleased.AUTO_REWORK with the
 * reworked car screens, so a release searched the library alone, and an artist typed into the car
 * by somebody with a small library found nothing at all (#112, a Ford SYNC 3, 8 Nov 2026).
 *
 * When YouTube Music was asked and gave nothing back, the answer ends on a row that says why and
 * searches again at a tap ([AGAIN]). Without it a failed search showed the same empty list as one
 * that matched nothing.
 */
object CarSearch {
    /**
     * The row that searches again: "again/<query>", a list that opens on what the search finds
     * now. The query is everything after the first slash, slashes of its own included.
     */
    const val AGAIN = "again"

    /** The shortest query asked of YouTube Music: "U2" is a band, one letter is not a question yet. */
    const val SHORTEST_ONLINE = 2

    /** The most songs YouTube Music adds, and a library answer this long is not added to. */
    const val MOST = AutoBrowse.MOST

    /** Whether YouTube Music is asked about [query], when the library found [inLibrary] songs. */
    fun asksOnline(query: String, inLibrary: Int): Boolean =
        query.trim().length >= SHORTEST_ONLINE && inLibrary < MOST

    /** Why YouTube Music's songs are missing from an answer. */
    enum class Miss {
        /** The phone has no network, or none with the internet behind it. */
        OFFLINE,

        /** It was asked and failed, or did not answer in time. */
        NO_ANSWER,
    }

    /**
     * Why YouTube Music's half is missing, or null when it answered or was not asked. [answered]
     * is false for a request that failed or timed out; [online] is whether the phone has the
     * internet as Android sees it.
     */
    fun miss(asked: Boolean, answered: Boolean, online: Boolean): Miss? = when {
        !asked || answered -> null
        !online -> Miss.OFFLINE
        else -> Miss.NO_ANSWER
    }

    fun againId(query: String): String = "$AGAIN/$query"

    /** The query an id of [againId] names, or null for any other id. */
    fun againQuery(id: String): String? =
        id.takeIf { it.startsWith("$AGAIN/") }?.substringAfter('/')?.takeIf { it.isNotBlank() }

    /**
     * Page [page] of [items], [pageSize] long, as a browser asks for it. Media3 refuses an answer
     * longer than the page asked for, and the car's own screens ask for 50. A page past the end
     * is empty; a browser that names no page (Android Auto) is handed it all, as media3 hands
     * such a request over: page 0 of Int.MAX_VALUE.
     */
    fun <T> page(items: List<T>, page: Int, pageSize: Int): List<T> {
        if (page < 0 || pageSize < 1) return items
        val from = page.toLong() * pageSize
        if (from >= items.size) return emptyList()
        return items.subList(from.toInt(), minOf(items.size.toLong(), from + pageSize).toInt())
    }
}
