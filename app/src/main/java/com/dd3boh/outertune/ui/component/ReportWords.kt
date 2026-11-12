/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.ui.component

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import com.dd3boh.outertune.utils.DraftKeeper
import com.dd3boh.outertune.utils.ReportDraft
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

/**
 * "What happened?" and "How to reach you" on Report a problem and the crash page, kept until a
 * report is sent ([ReportDraft]): what was typed before comes back as the page opens, and what is
 * typed is kept as it is typed. [back] is false until the kept words are read, and the boxes are
 * shown only from then, so that nothing typed is replaced by them.
 */
@Stable
internal class ReportWords(private val keeper: DraftKeeper, what: String, contact: String, back: Boolean) {
    var what by mutableStateOf(what)
        private set
    var contact by mutableStateOf(contact)
        private set
    var back by mutableStateOf(back)
        private set

    fun typeWhat(text: String) {
        what = text.take(WHAT_MOST)
        keep()
    }

    fun typeContact(text: String) {
        contact = text.take(CONTACT_MOST)
        keep()
    }

    /** The words kept, read once as the page opens; nothing when they are back already, on a page turned on its side. */
    suspend fun bringBack() {
        if (back) return
        val kept = keeper.read()
        what = kept.what
        contact = kept.contact
        back = true
    }

    /** A report went with these words, so the next one starts empty. Finished even when the page closes meanwhile. */
    suspend fun sent() = withContext(NonCancellable) { keeper.sent() }

    private fun keep() = keeper.keep(DraftKeeper.Draft(what, contact))

    companion object {
        const val WHAT_MOST = 2000
        const val CONTACT_MOST = 200

        fun saver(keeper: DraftKeeper) = listSaver<ReportWords, Any>(
            save = { listOf(it.what, it.contact, it.back) },
            restore = { ReportWords(keeper, it[0] as String, it[1] as String, it[2] as Boolean) },
        )
    }
}

@Composable
internal fun rememberReportWords(): ReportWords {
    val context = LocalContext.current
    val keeper = remember { ReportDraft.of(context) }
    val words = rememberSaveable(saver = ReportWords.saver(keeper)) { ReportWords(keeper, "", "", back = false) }
    LaunchedEffect(words) { words.bringBack() }
    return words
}
