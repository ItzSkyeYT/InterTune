/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.utils

/**
 * What each Home row has shown lately, so that a refresh brings forward what it has not.
 *
 * A pull to refresh used to reorder the same songs. YouTube's shelf is the same for a whole
 * session and the classic query's strongest forty are much the same from one minute to the next,
 * so a new sample of either was mostly the old row in a new order. Each row now remembers the ids
 * of its last few fillings and, when it is filled again, the songs it has not shown lately go
 * first, in the order they arrived in, and the ones it has go last, the longest ago first. Nothing
 * is dropped: a pool smaller than a few rows still fills the row, cycling through itself.
 */
class RecentlyShown(private val rowsKept: Int = 3) {
    private val rows = HashMap<String, ArrayDeque<List<String>>>()

    /** The row [key] has just shown [ids]. */
    fun note(key: String, ids: List<String>) {
        if (ids.isEmpty()) return
        val kept = rows.getOrPut(key) { ArrayDeque() }
        kept.addLast(ids)
        while (kept.size > rowsKept) kept.removeFirst()
    }

    /** [items] with what [key] has not shown lately first, then the rest, the longest ago first; ties keep their order. */
    fun <T> order(key: String, items: List<T>, id: (T) -> String): List<T> {
        val kept = rows[key] ?: return items
        val lastShown = HashMap<String, Int>()
        kept.forEachIndexed { i, row -> row.forEach { lastShown[it] = i } }
        return items.withIndex()
            .sortedWith(compareBy({ lastShown[id(it.value)] ?: -1 }, { it.index }))
            .map { it.value }
    }
}
