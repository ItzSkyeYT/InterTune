/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.utils

import android.content.Context
import android.util.Log
import androidx.datastore.preferences.core.edit
import com.dd3boh.outertune.constants.ArtistCreditRepairedKey
import com.dd3boh.outertune.db.MusicDatabase
import com.dd3boh.outertune.db.StoredCredit
import com.dd3boh.outertune.extensions.isInternetConnected
import com.dd3boh.outertune.models.ArtistCredit
import com.dd3boh.outertune.models.MediaMetadata
import com.zionhuang.innertube.YouTube
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.cancellation.CancellationException

/**
 * Puts back, once, the artists of the songs kept here whose credit was stored as one name (ArtistCredit).
 *
 * A song already stored is never written again when it is met later, so a credit YouTube first sent as one
 * unlinked name stayed one artist: his liked, downloaded "Thread The Needle" was by "Hoax, So What, & Dynamite
 * MC", an artist whose page had nothing on it, long after YouTube named Hoax, So What and Dynamite MC one by one
 * (27 Nov 2026). From now on a song met again is put right as it comes in (DatabaseDao.insert). This pass is for
 * those already stored and kept (liked, in the library, downloaded or in a playlist) whose only artist has an id
 * made up here and a name that may hold several (ArtistCredit.maybeSeveral): YouTube Music is asked for them, 50
 * songs to a request with a pause between, and a song whose artists it now names, and spell the name stored
 * (ArtistCredit.spells), takes them (DatabaseDao.splitStoredCredit). Its like, its plays and its download are
 * left as they are. A song YouTube does not answer for, or names the same way, stays as it is.
 *
 * Once: a pass that has been through them all does not run again. One that could not ask (offline, YouTube not
 * answering) carries on at a later launch after the last page it finished.
 */
object ArtistCreditRepair {
    private const val TAG = "ArtistCreditRepair"

    const val PAGE = 50
    private const val PAUSE_MS = 1_000L

    /** Launch has enough to do: the pass starts a little after it. */
    private const val START_AFTER_MS = 8_000L

    /** Holds the id of the last song of the last page done. In noBackupFilesDir: it is this phone's. */
    private const val MARK_FILE = "artist-credit-repair"

    /** Where the pass is, kept across launches. */
    interface Marks {
        /** The id of the last song of the last page done, or null when it has not begun. */
        fun at(): String?
        fun setAt(id: String)
        suspend fun done(): Boolean
        suspend fun setDone()
    }

    /**
     * Runs the pass, or what is left of it, and returns how many songs got their artists back.
     *
     * [page] gives the songs to look at after an id, in id order (DatabaseDao.creditsToAsk). [ask] gives the
     * artists YouTube names now for each id it answered for, or null when it could not be asked: the pass stops
     * there, not done. [split] gives a song those artists when they spell its stored name, and says whether it
     * did (DatabaseDao.splitStoredCredit).
     */
    suspend fun run(
        marks: Marks,
        page: (afterId: String, limit: Int) -> List<StoredCredit>,
        ask: suspend (ids: List<String>) -> Map<String, List<MediaMetadata.Artist>>?,
        split: (songId: String, artists: List<MediaMetadata.Artist>) -> Boolean,
        pauseMs: Long = PAUSE_MS,
    ): Int {
        if (marks.done()) return 0
        var after = marks.at() ?: ""
        var fixed = 0
        while (true) {
            val songs = page(after, PAGE)
            if (songs.isEmpty()) break
            currentCoroutineContext().ensureActive()
            // "Milk Talk" is one name and never needed asking about.
            val wanted = songs.filter { ArtistCredit.maybeSeveral(it.name) }
            if (wanted.isNotEmpty()) {
                val answer = ask(wanted.map { it.songId }) ?: return fixed
                for (song in wanted) {
                    val artists = answer[song.songId] ?: continue
                    if (split(song.songId, artists)) fixed++
                }
            }
            after = songs.last().songId
            marks.setAt(after)
            if (wanted.isNotEmpty()) delay(pauseMs)
        }
        marks.setDone()
        return fixed
    }

    /** One pass at a time, whatever starts it. */
    private val running = AtomicBoolean(false)

    /**
     * The pass as the app runs it, after the scan at launch. Returns how many songs got their artists back, so
     * that the queues are read again and the player shows them (MusicService.refreshQueuesAfterScan).
     */
    suspend fun runOnce(context: Context, database: MusicDatabase): Int {
        if (!running.compareAndSet(false, true)) return 0
        try {
            val marks = marks(context)
            if (marks.done()) return 0
            delay(START_AFTER_MS)
            if (!context.isInternetConnected()) return 0
            val fixed = run(
                marks = marks,
                page = { after, limit -> database.creditsToAsk(after, limit) },
                ask = { ids ->
                    YouTube.queue(ids).onFailure { Log.w(TAG, "YouTube Music did not answer for ${ids.size} songs", it) }
                        .getOrNull()
                        ?.associate { song -> song.id to song.artists.map { MediaMetadata.Artist(id = it.id, name = it.name) } }
                },
                split = { id, artists -> database.splitStoredCredit(id, artists) },
            )
            if (fixed > 0) Log.i(TAG, "$fixed songs stored with their credit as one name have their artists again")
            if (marks.done()) Log.i(TAG, "Done")
            return fixed
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            reportException(e)
            return 0
        } finally {
            running.set(false)
        }
    }

    private fun marks(context: Context) = object : Marks {
        private val file = File(context.noBackupFilesDir, MARK_FILE)

        override fun at(): String? = runCatching { file.readText().trim() }.getOrNull()?.ifEmpty { null }

        override fun setAt(id: String) {
            runCatching { file.writeText(id) }
        }

        override suspend fun done(): Boolean = context.dataStore.get(ArtistCreditRepairedKey, false)

        override suspend fun setDone() {
            context.dataStore.edit { it[ArtistCreditRepairedKey] = true }
            file.delete()
        }
    }
}
