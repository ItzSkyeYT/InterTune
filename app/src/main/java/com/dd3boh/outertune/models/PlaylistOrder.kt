/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.models

/**
 * Playlists in an order of your own (Library > Playlists, Custom order): their ids in the order
 * they were dragged into, kept as one preference, one id a line.
 */
object PlaylistOrder {

    fun decode(saved: String?): List<String> =
        saved?.lineSequence()?.map { it.trim() }?.filter { it.isNotEmpty() }?.distinct()?.toList().orEmpty()

    fun encode(ids: List<String>): String = ids.distinct().joinToString("\n")

    /**
     * [items], oldest first as the database lists them, in the order [saved]: the ones it names in
     * that order, after the ones it does not name yet (made or saved since the last drag), newest
     * first, as a new playlist is the one being looked for. Ids it names that are gone are left
     * out. With nothing saved yet, newest first, as the library opens by default.
     */
    fun <T> apply(items: List<T>, saved: List<String>, id: (T) -> String): List<T> {
        if (saved.isEmpty()) return items.asReversed().toList()
        val byId = items.associateBy(id)
        val named = saved.mapNotNull { byId[it] }
        val known = saved.toHashSet()
        val fresh = items.filter { id(it) !in known }.asReversed()
        return fresh + named
    }

    /** The order after a drag in the list shown: [ids] with the one at [from] put at [to]. */
    fun moved(ids: List<String>, from: Int, to: Int): List<String> {
        if (from !in ids.indices || to !in ids.indices || from == to) return ids
        return ids.toMutableList().apply { add(to, removeAt(from)) }
    }
}
