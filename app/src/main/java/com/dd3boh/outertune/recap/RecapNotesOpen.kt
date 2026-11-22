/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.recap

import android.content.Context
import androidx.datastore.preferences.core.edit
import com.dd3boh.outertune.BuildConfig
import com.dd3boh.outertune.constants.RecapNotesToldKey
import com.dd3boh.outertune.constants.RecapOpenedHereKey
import com.dd3boh.outertune.constants.RecapRevealedEarlyKey
import com.dd3boh.outertune.utils.dataStore
import kotlinx.coroutines.flow.first
import java.time.LocalDate

/**
 * When the notes sealed for a Recap open (RecapNote): the one rule the reading slide, the Your notes
 * page and the notification all go by. Notes kept under a year are for the Recap of the year after,
 * and open once this phone can watch that Recap: it is out (RecapCalendar, the debug options' early
 * reveal included) or it has been opened here already (a debug build opens any). From then on they
 * are open everywhere.
 */
object RecapNotesOpen {
    /** The latest Recap this phone can watch: the one on show ([onShow]) or the latest opened here ([openedHere], 0 for none). Null before any. */
    fun upTo(onShow: Int?, openedHere: Int): Int? = maxOf(onShow ?: 0, openedHere).takeIf { it > 0 }

    /** Whether the notes kept under [keptYear] are open: the Recap of the year after can be watched, by [upTo]. */
    fun open(keptYear: Int, upTo: Int?): Boolean = upTo != null && keptYear + 1 <= upTo

    /** [upTo] from what this phone holds. Off the main thread. */
    suspend fun upTo(context: Context, today: LocalDate = LocalDate.now()): Int? {
        val prefs = context.dataStore.data.first()
        val onShow = RecapCalendar.yearOnShow(today, prefs[RecapRevealedEarlyKey] == true && BuildConfig.PREVIEW_FEATURES)
        return upTo(onShow, prefs[RecapOpenedHereKey] ?: 0)
    }

    /**
     * The Recap of [year] is open on this phone: its notes are open everywhere from now, and as the
     * story shows them they need no notification to say they are waiting (RecapNoteAlert).
     */
    suspend fun opened(context: Context, year: Int) {
        context.dataStore.edit {
            if ((it[RecapOpenedHereKey] ?: 0) < year) it[RecapOpenedHereKey] = year
            if ((it[RecapNotesToldKey] ?: 0) < year) it[RecapNotesToldKey] = year
        }
    }
}
