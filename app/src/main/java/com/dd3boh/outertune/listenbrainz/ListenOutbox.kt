/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.listenbrainz

import android.content.Context
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

/**
 * A play waiting to reach ListenBrainz.
 *
 * @param user the ListenBrainz account it was played under: a token for another account does not
 *   send it there.
 * @param json the listen exactly as it was first made (ListenBrainz.encode), so a listen sent twice
 *   is the same listen to ListenBrainz, which keeps it once. Also what tells two waiting apart.
 * @param keptAt when it was kept, which orders the outbox.
 */
data class WaitingListen(val user: String, val json: String, val keptAt: Long)

/**
 * The plays ListenBrainz has not taken yet (Unreleased.LISTENBRAINZ): every counted play is kept
 * here before it is sent, so nothing is lost when the phone is offline, ListenBrainz is down, or
 * the app dies before the answer, and it comes off once ListenBrainz has it or refuses it for good.
 * ListenBrainzWorker sends what is left.
 *
 * At most [MOST] wait, about three weeks of heavy listening offline; past that the oldest go,
 * counted in the log. Disconnecting empties it, and a token for another account drops what the
 * one before played.
 *
 * As RecogniseLater keeps its listens: a file of its own in the app's files that are not backed
 * up, written whole beside the old one and renamed into place, so a phone that dies in the
 * middle keeps the list it had; read and written with kotlinx.serialization's tree.
 */
@Singleton
class ListenOutbox internal constructor(private val file: File) {

    @Inject
    constructor(@ApplicationContext context: Context) : this(File(context.noBackupFilesDir, FILE))

    private val lock = Mutex()

    /** The list, once read from the file, and null until then. */
    private val loaded = MutableStateFlow<List<WaitingListen>?>(null)

    /** Every play waiting, oldest first. */
    val waiting: Flow<List<WaitingListen>> = loaded
        .onStart { withContext(Dispatchers.IO) { lock.withLock { load() } } }
        .filterNotNull()

    /** Keeps [listen]. The same listen kept again stays once. */
    suspend fun keep(listen: WaitingListen) {
        change { kept(it, listen) }
    }

    /** What waits for [user], oldest first. */
    suspend fun of(user: String): List<WaitingListen> = change { it }.filter { it.user == user }

    /** Takes [done] off: sent, or refused for good. */
    suspend fun remove(done: Collection<WaitingListen>) {
        if (done.isEmpty()) return
        val gone = done.map { it.user to it.json }.toSet()
        change { list -> list.filterNot { (it.user to it.json) in gone } }
    }

    /** Drops what was played under any account but [user]'s: a token for another one was pasted. */
    suspend fun keepOnly(user: String) {
        change { list -> list.filter { it.user == user } }
    }

    /** Forgets everything waiting, as disconnecting does: the file goes too, also one that could not be read. */
    suspend fun clear() {
        change(always = true) { emptyList() }
    }

    private suspend fun change(always: Boolean = false, how: (List<WaitingListen>) -> List<WaitingListen>): List<WaitingListen> =
        withContext(Dispatchers.IO) {
            lock.withLock {
                val before = load()
                val after = how(before)
                // Into the file first: a list the file does not hold is not taken for kept.
                if (always || after != before) write(after)
                loaded.value = after
                after
            }
        }

    /** The list as kept, read from the file the first time. Under [lock]. */
    private fun load(): List<WaitingListen> = loaded.value ?: decode(
        runCatching { file.takeIf { it.exists() }?.readText() }
            .onFailure { Log.w(TAG, "Could not read the plays waiting for ListenBrainz", it) }
            .getOrNull()
    ).also { loaded.value = it }

    /** [list] as the file: written whole beside it, forced onto the disk, and only then given the name. No file once it is empty. */
    private fun write(list: List<WaitingListen>) {
        if (list.isEmpty()) {
            file.delete()
            return
        }
        file.parentFile?.mkdirs()
        val beside = File(file.path + TEMPORARY)
        try {
            FileOutputStream(beside).use { out ->
                out.write(encode(list).toByteArray(Charsets.UTF_8))
                out.flush()
                runCatching { out.fd.sync() }
            }
            if (!beside.renameTo(file)) throw IOException("Could not put ${file.name} in place")
        } catch (e: Exception) {
            beside.delete()
            throw e
        }
    }

    companion object {
        private const val TAG = "ListenBrainz"

        /** The file, in the app's files that are not backed up. */
        const val FILE = "listenbrainz_outbox.json"
        private const val TEMPORARY = ".new"
        private const val VERSION = 1

        /** Plays waiting at most: about three weeks of heavy listening offline. */
        const val MOST = 1_000

        /** Whether anything waits, without reading the file: it is there only while something does. */
        fun anyWaiting(context: Context): Boolean = File(context.noBackupFilesDir, FILE).exists()

        internal fun kept(list: List<WaitingListen>, listen: WaitingListen): List<WaitingListen> {
            if (list.any { it.user == listen.user && it.json == listen.json }) return list
            val all = (list + listen).sortedBy { it.keptAt }
            if (all.size > MOST) Log.w(TAG, "ListenBrainz: ${all.size - MOST} oldest waiting dropped, $MOST at most")
            return all.takeLast(MOST)
        }

        internal fun encode(list: List<WaitingListen>): String = buildJsonObject {
            put("v", VERSION)
            put("waiting", buildJsonArray {
                list.forEach { listen ->
                    add(buildJsonObject {
                        put("user", listen.user)
                        put("keptAt", listen.keptAt)
                        put("listen", listen.json)
                    })
                }
            })
        }.toString()

        /** The plays in [text], oldest first, or none when it is not a file this wrote. Never a throw. */
        internal fun decode(text: String?): List<WaitingListen> = runCatching {
            val root = Json.parseToJsonElement(text ?: return emptyList()).jsonObject
            if (root["v"]?.jsonPrimitive?.intOrNull != VERSION) return emptyList()
            root["waiting"]?.jsonArray.orEmpty()
                .mapNotNull { runCatching { waiting(it.jsonObject) }.getOrNull() }
                .sortedBy { it.keptAt }
        }.getOrElse {
            Log.w(TAG, "Could not read the plays waiting for ListenBrainz, starting over", it)
            emptyList()
        }

        private fun waiting(o: JsonObject): WaitingListen? = WaitingListen(
            user = o["user"]?.jsonPrimitive?.contentOrNull ?: return null,
            json = o["listen"]?.jsonPrimitive?.contentOrNull ?: return null,
            keptAt = o["keptAt"]?.jsonPrimitive?.longOrNull ?: return null,
        )
    }
}
