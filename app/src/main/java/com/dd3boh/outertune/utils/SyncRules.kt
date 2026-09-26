/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.utils

import java.io.File
import java.security.MessageDigest
import java.time.LocalDateTime

/**
 * Whether sync may remove the local items that a remote list does not contain: only when the list
 * was read in full and holds something. An empty answer is far more often a fetch that did not
 * work (throttled, signed out, a page in a new shape) than someone who removed everything, and a
 * removal made on a bad read cannot be undone from here.
 */
fun mayRemoveMissing(complete: Boolean, remoteCount: Int): Boolean = complete && remoteCount > 0

/**
 * Whether a playlist's songs may be replaced by a remote copy with [remoteCount] songs. Only a
 * complete read, and never an empty one over a playlist that has songs here: the same fetch that
 * fails for a whole library fails for one playlist.
 */
fun mayReplacePlaylist(complete: Boolean, remoteCount: Int, hasLocalSongs: Boolean): Boolean =
    complete && (remoteCount > 0 || !hasLocalSongs)

/**
 * The decisions behind the liked songs sync, kept apart from the database and the network so they
 * can be tested.
 *
 * Sync used to unlike every local like that was missing from YouTube's Liked music (LM). That read
 * a like YouTube never saw (made signed out, offline, or under another account) as a like taken
 * back on YouTube, so signing in for the first time could empty the liked songs of someone who had
 * used the app signed out. A like is now only taken back when YouTube is known to have had it: it
 * was in LM at the last complete sync for the same account, and a complete read shows it gone.
 */
object LikedSync {

    /** A song liked here, as far as this decision needs it. */
    data class LocalLike(val id: String, val likedDate: LocalDateTime?)

    /**
     * Which of the local likes to take back.
     *
     * @param remoteIds everything read from LM this time.
     * @param complete whether that read is known to be the whole of LM. A read that stopped early
     *   looks exactly like a list whose tail was unliked.
     * @param snapshot the LM ids seen at the last complete liked sync for this account, or null
     *   when there is none (first sign-in, another account). With none, sync only adds.
     * @param addOnly the "Keep all local content" setting: sync never takes anything away.
     * @param graceDays a like newer than this is left alone even when it qualifies, in case its
     *   push to YouTube simply has not gone out yet.
     */
    fun idsToUnlike(
        localLikes: Collection<LocalLike>,
        remoteIds: Set<String>,
        complete: Boolean,
        snapshot: Set<String>?,
        addOnly: Boolean,
        now: LocalDateTime,
        graceDays: Long,
    ): Set<String> {
        if (addOnly || !complete || snapshot == null) return emptySet()
        // An empty LM is a fetch that did not work far more often than someone who unliked
        // everything, and acting on it cannot be undone.
        if (remoteIds.isEmpty()) return emptySet()
        val graceStart = now.minusDays(graceDays)
        return localLikes
            .asSequence()
            .filter { it.id in snapshot && it.id !in remoteIds }
            .filterNot { it.likedDate?.isAfter(graceStart) == true }
            .map { it.id }
            .toSet()
    }

    /**
     * Whether a read of [readCount] songs can be the whole of a playlist whose header says
     * [headerCount]. Some songs in the count never come back as rows (removed or unavailable
     * videos), so a little short is normal; clearly short means a page went missing, and the
     * songs after it would all look unliked. No header count, no objection.
     */
    fun readLooksComplete(readCount: Int, headerCount: Int?): Boolean {
        if (headerCount == null || headerCount <= 0) return true
        val tolerance = maxOf(5, headerCount / 10)
        return readCount + tolerance >= headerCount
    }

    /**
     * The number in a header such as "1,234 songs", "1 234 titres" or "1.234 Titel". The first
     * run of digits alone would read "1,234" as 1.
     */
    fun parseSongCount(text: String?): Int? {
        if (text.isNullOrBlank()) return null
        val match = Regex("""\d(?:[\d,.'   ]*\d)?""").find(text) ?: return null
        return match.value.filter { it.isDigit() }.toIntOrNull()
    }

    /**
     * Which account a snapshot belongs to. The DATASYNC_ID comes from the signed-in page itself,
     * so it is preferred over the email, which is fetched separately and can lag behind an
     * account switch. Null when neither is known: no snapshot, so add only.
     */
    fun accountKey(dataSyncId: String?, email: String?): String? {
        val id = dataSyncId?.takeIf { it.isNotBlank() && it != "null" }?.let {
            // The same reading App applies before handing it to YouTube.
            it.takeIf { !it.contains("||") }
                ?: it.takeIf { it.endsWith("||") }?.substringBefore("||")
                ?: it.substringAfter("||")
        }?.takeIf { it.isNotBlank() }
        if (id != null) return "dsid:$id"
        val mail = email?.trim()?.lowercase()?.takeIf { it.isNotEmpty() }
        return mail?.let { "email:$it" }
    }
}

/**
 * The LM ids seen at the last complete liked sync, one file per account under [dir]. The file
 * name is a hash of the account key, so no address or id is written out as a name.
 */
class LikedSnapshotStore(private val dir: File) {

    private fun fileFor(accountKey: String): File {
        val hash = MessageDigest.getInstance("SHA-256")
            .digest(accountKey.toByteArray())
            .joinToString("") { "%02x".format(it) }
            .take(32)
        return File(dir, "lm-$hash.txt")
    }

    /** Null when this account has no snapshot yet, or it cannot be read. */
    fun read(accountKey: String): Set<String>? {
        val file = fileFor(accountKey)
        if (!file.isFile) return null
        return runCatching {
            val lines = file.readLines()
            // The first line is the account key, so a hash collision cannot hand one account
            // another's snapshot.
            if (lines.firstOrNull() != accountKey) return null
            lines.drop(1).filter { it.isNotBlank() }.toSet()
        }.getOrNull()
    }

    /** Replaces the snapshot as a whole: written aside, then moved over the old one. */
    fun write(accountKey: String, ids: Set<String>) {
        dir.mkdirs()
        val file = fileFor(accountKey)
        val tmp = File(dir, file.name + ".tmp")
        tmp.writeText(buildString {
            appendLine(accountKey)
            ids.forEach { appendLine(it) }
        })
        if (!tmp.renameTo(file)) {
            tmp.copyTo(file, overwrite = true)
            tmp.delete()
        }
    }
}

/** What a sync did, so the button that started it can say so instead of always "Sync complete". */
enum class SyncResult {
    /** Read in full and applied. */
    SYNCED,

    /** A fetch failed or came back short, or the connection went. Nothing was removed on its account. */
    FAILED,

    /** Nothing ran: every kind switched off, already running, cooling down, or signed out. */
    NOTHING;

    companion object {
        fun combine(results: Collection<SyncResult>): SyncResult = when {
            FAILED in results -> FAILED
            SYNCED in results -> SYNCED
            else -> NOTHING
        }
    }
}
