/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.ui.navigation

import android.os.Bundle
import android.os.Parcelable

/**
 * What is written down of the navigation's history when Android asks the app to save its state (the
 * app going to the background, the phone turned): the newest pages only.
 *
 * #107: the app closed as it went to the background on a Galaxy S25 Ultra, the 527 KB of state it
 * handed Android being more than a binder call carries. 472 KB of it was the history: every page
 * opened and not left with Back stays in it, about 2.5 KB a page (40 searches in a row took the
 * saved state from 8 KB to 108 KB on the emulator, 9 Nov 2026), so that phone had some 200 pages
 * piled up. While the app runs, Back still goes through all of them; only what is written down is
 * cut, and the app opened again after Android ended it goes back as far as the newest page kept.
 *
 * The history is cut where navigation-runtime wrote it (NavControllerImpl, 2.10.1), in the state
 * the activity hands over: the history shown, and the ones parked by a tab change.
 */
internal object KeptHistory {
    /** About 65 KB of history at most. */
    const val MOST = 25

    /** The history shown: the graph's own entry first, then the start page, then the pages above it. */
    private const val SHOWN = "android-support-nav:controller:backStack"

    /** A history parked by a tab change (popUpTo with saveState), one per id: the pages above the start page. */
    private const val PARKED = "android-support-nav:controller:backStackStates:"

    /** Which pages of the history shown, [size] long, are kept: the first two and the newest, [most] in all. */
    fun kept(size: Int, most: Int = MOST): List<Int> {
        val limit = most.coerceAtLeast(3)
        if (size <= limit) return (0 until size).toList()
        return listOf(0, 1) + ((size - (limit - 2)) until size).toList()
    }

    /** Which pages of a parked history, [size] long, are kept: the newest [most]. */
    fun keptParked(size: Int, most: Int = MOST): List<Int> =
        ((size - most.coerceAtLeast(1)).coerceAtLeast(0) until size).toList()

    /**
     * Cuts every history in [state], the activity's state as it was just written, wherever the
     * navigation's saver put it inside. Returns how many pages were left out.
     */
    fun cut(state: Bundle, depth: Int = 0): Int {
        var left = 0
        for (key in state.keySet().toList()) {
            @Suppress("DEPRECATION")
            val value = state.get(key)
            val keep: ((Int) -> List<Int>)? = when {
                key == SHOWN -> { size -> kept(size) }
                key.startsWith(PARKED) -> { size -> keptParked(size) }
                else -> null
            }
            if (keep != null) {
                left += cutPages(state, key, value, keep)
                continue
            }
            if (depth >= 8) continue
            when (value) {
                is Bundle -> left += cut(value, depth + 1)
                is List<*> -> value.forEach { if (it is Bundle) left += cut(it, depth + 1) }
                is Array<*> -> value.forEach { if (it is Bundle) left += cut(it, depth + 1) }
            }
        }
        return left
    }

    /** The pages under [key] cut to those [keep] names, as the list or array they were written as. Pages that are not saved states are left as they are. */
    private fun cutPages(state: Bundle, key: String, pages: Any?, keep: (Int) -> List<Int>): Int {
        val all = when (pages) {
            is List<*> -> pages
            is Array<*> -> pages.toList()
            else -> return 0
        }
        if (all.any { it !is Bundle }) return 0
        val kept = keep(all.size)
        if (kept.size >= all.size) return 0
        val cut = kept.map { all[it] as Parcelable }
        if (pages is List<*>) state.putParcelableArrayList(key, ArrayList(cut)) else state.putParcelableArray(key, cut.toTypedArray())
        return all.size - kept.size
    }
}
