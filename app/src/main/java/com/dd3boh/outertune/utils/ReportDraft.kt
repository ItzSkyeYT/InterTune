/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.utils

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.atomic.AtomicReference

/**
 * What was typed on Report a problem or the crash page and not sent yet, "What happened?" and "How
 * to reach you": back when either page opens again, gone once a report is sent. frances, 4 Nov
 * 2026: "i wish it would save the text you've written when submitting a problem report until you
 * submit it". The pages kept it only while they were open, so leaving the app or closing the page
 * lost it.
 *
 * One draft for both pages: words begun on the crash page and not sent come back on Report a
 * problem, where the crashed run's log is among the earlier runs.
 *
 * On this phone only. Its own small file, not the settings: the app's own backups carry the
 * settings file, and a move to a new phone copies it. This one is left out of the cloud backup
 * and the move (data_extraction_rules.xml, backup_rules.xml), and nothing of it is ever logged.
 */
object ReportDraft {
    /** The file's name, as the backup rules leave it out: files/datastore/report_draft.preferences_pb. */
    const val FILE = "report_draft"

    private val Context.draftFile: DataStore<Preferences> by preferencesDataStore(name = FILE)

    @Volatile
    private var keeper: DraftKeeper? = null

    /** The one keeper of the app's draft. */
    fun of(context: Context): DraftKeeper = keeper ?: synchronized(this) {
        keeper ?: DraftKeeper(context.applicationContext.draftFile, CoroutineScope(SupervisorJob() + Dispatchers.IO)).also { keeper = it }
    }
}

/**
 * Keeps one draft in [store]. Words are written a moment after the typing pauses ([settleMs]),
 * one write for a burst of letters rather than one a letter, and always the newest: a write and
 * the clear of a sent report go one at a time, so a write made of older words never lands after
 * the clear. Within the process the newest words are read from memory, so a page closed and opened
 * again at once has them before they reach the file.
 */
class DraftKeeper(
    private val store: DataStore<Preferences>,
    scope: CoroutineScope,
    private val settleMs: Long = SETTLE_MS,
) {
    data class Draft(val what: String = "", val contact: String = "")

    /** The newest words in this process, null until anything was typed or sent. */
    private val latest = AtomicReference<Draft?>(null)
    private val due = Channel<Unit>(Channel.CONFLATED)
    private val writing = Mutex()

    init {
        scope.launch {
            for (signal in due) {
                delay(settleMs)
                writing.withLock { latest.get()?.let { write(it) } }
            }
        }
    }

    /** The words kept: this process's newest, or the file's. Empty when there are none or the file cannot be read. */
    suspend fun read(): Draft = latest.get() ?: runCatching {
        store.data.first().let { Draft(it[WHAT].orEmpty(), it[CONTACT].orEmpty()) }
    }.getOrDefault(Draft())

    /** The words as they are now, written a moment later. */
    fun keep(draft: Draft) {
        latest.set(draft)
        due.trySend(Unit)
    }

    /** A report went with these words: they are cleared at once, from memory and from the file. */
    suspend fun sent() {
        writing.withLock {
            latest.set(Draft())
            write(Draft())
        }
    }

    private suspend fun write(draft: Draft) {
        runCatching {
            store.edit {
                if (draft.what.isBlank()) it.remove(WHAT) else it[WHAT] = draft.what
                if (draft.contact.isBlank()) it.remove(CONTACT) else it[CONTACT] = draft.contact
            }
        }
    }

    companion object {
        /** How long the typing pauses before the words are written. */
        const val SETTLE_MS = 400L

        private val WHAT = stringPreferencesKey("what")
        private val CONTACT = stringPreferencesKey("contact")
    }
}
