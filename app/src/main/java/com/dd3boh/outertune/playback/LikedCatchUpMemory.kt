/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.playback

import androidx.media3.exoplayer.offline.Download
import java.io.File

/**
 * What the liked songs catch up remembers between syncs: the songs whose download the person
 * removed, and the songs whose download keeps failing.
 *
 * With "Automatically download your liked songs" on, every liked sync (at opening, then every half
 * hour to six hours, and a pull on Liked songs) queued each liked song without a download. It could
 * not tell a song never downloaded from one whose download the person had removed, which came back
 * at the next sync, nor from one whose download always fails, which was queued again each time with
 * its resolve, media3's retries and one more "Download failed" notification.
 *
 * - A download the person removed stays removed: Remove download from any menu, Clear all
 *   downloads, the download notification's Cancel, the catch up's own Stop. It comes back when the
 *   person downloads the song, likes it anew, or asks for every liked song in Settings ("Download
 *   all liked songs now", whose row says it brings back what was deleted by hand).
 * - A download that fails is tried again by the sync a day later, then three days, then a week
 *   later ([RETRY_GAPS_MS]). After its fourth failure the sync leaves it, until the person brings it
 *   back the same ways. A download that lands forgets both.
 *
 * The ids of songs no longer liked are dropped when the sync meets them ([keepOnly]). Not thread
 * safe: DownloadUtil reads and changes it on one thread at a time, in the order the changes come.
 */
internal class LikedCatchUpMemory {
    private class Failures(val count: Int, val lastAt: Long)

    /** Songs whose download the person removed, and when. */
    private val removed = HashMap<String, Long>()

    /** Songs whose download failed, how many times since it last landed or was asked for, and when last. */
    private val failed = HashMap<String, Failures>()

    /**
     * Failed downloads whose removal has not come yet: the app removes a failed download at once,
     * to free what it had fetched (DownloadUtil's listener), and that removal is nobody's choice.
     * Not kept in the file.
     */
    private val freeing = HashSet<String>()

    /** Every song it holds something for. */
    val ids: Set<String> get() = removed.keys + failed.keys

    /**
     * A change of [id]'s download, as the download listener hears it, at [now]. True when something
     * kept changed, and the file is due a write.
     */
    fun afterChange(id: String, state: Int, now: Long): Boolean = when (state) {
        Download.STATE_COMPLETED -> {
            freeing -= id
            forget(id)
        }
        Download.STATE_FAILED -> {
            freeing += id
            removed -= id
            failed[id] = Failures((failed[id]?.count ?: 0) + 1, now)
            true
        }
        // A removal the app made of a failed download, or the person's: Remove download, Clear all
        // downloads, the notification's Cancel, the catch up's Stop.
        Download.STATE_REMOVING -> if (freeing.remove(id)) false else removedByPerson(id, now)
        else -> {
            freeing -= id
            false
        }
    }

    /** The person removed [id]'s download at [now], or cancelled it on its way. */
    fun removedByPerson(id: String, now: Long): Boolean {
        failed -= id
        removed[id] = now
        return true
    }

    /** The person wants [ids] again: a download by hand, a like given anew, every liked song asked for in Settings. */
    fun wantedAgain(ids: Collection<String>): Boolean {
        var changed = false
        for (id in ids) if (forget(id)) changed = true
        return changed
    }

    /** Whether the sync may queue [id] at [now]: the person has not removed it, and it has no failure to wait out. */
    fun mayCatchUp(id: String, now: Long): Boolean {
        if (id in removed) return false
        val failures = failed[id] ?: return true
        if (failures.count > RETRY_GAPS_MS.size) return false
        return now - failures.lastAt >= RETRY_GAPS_MS[failures.count - 1]
    }

    /** Drops every song but [liked]: one no longer liked is not the catch up's, and a new like starts it afresh anyway. */
    fun keepOnly(liked: Set<String>): Boolean {
        val before = removed.size + failed.size
        removed.keys.retainAll(liked)
        failed.keys.retainAll(liked)
        return removed.size + failed.size != before
    }

    private fun forget(id: String): Boolean {
        val wasRemoved = removed.remove(id) != null
        val hadFailed = failed.remove(id) != null
        return wasRemoved || hadFailed
    }

    /** One line a song: "removed <id> <when>" or "failed <id> <times> <when>", times in ms since 1970. */
    fun encode(): String = buildString {
        removed.forEach { (id, at) -> append("removed ").append(id).append(' ').append(at).append('\n') }
        failed.forEach { (id, f) -> append("failed ").append(id).append(' ').append(f.count).append(' ').append(f.lastAt).append('\n') }
    }

    /** What [encode] wrote. A line it cannot read is left out: the song is then tried as if new. */
    fun decode(text: String) {
        for (line in text.lineSequence()) {
            val parts = line.trim().split(' ')
            when {
                parts.size == 3 && parts[0] == "removed" -> parts[2].toLongOrNull()?.let { removed[parts[1]] = it }
                parts.size == 4 && parts[0] == "failed" -> {
                    val count = parts[2].toIntOrNull()
                    val at = parts[3].toLongOrNull()
                    if (count != null && count > 0 && at != null) failed[parts[1]] = Failures(count, at)
                }
            }
        }
    }

    companion object {
        private const val DAY_MS = 24 * 60 * 60 * 1000L

        /**
         * How long the sync waits before it tries a failed download again: after its first failure,
         * its second, its third. After the fourth it does not try again.
         */
        val RETRY_GAPS_MS = longArrayOf(DAY_MS, 3 * DAY_MS, 7 * DAY_MS)

        /** What [file] holds, or nothing remembered when it is not there or cannot be read. */
        fun load(file: File): LikedCatchUpMemory = LikedCatchUpMemory().apply {
            if (file.isFile) runCatching { decode(file.readText()) }
        }

        /** Writes [memory] to [file]: aside first, then moved over the old one, so a write cut short leaves the old file. */
        fun save(memory: LikedCatchUpMemory, file: File) {
            file.parentFile?.mkdirs()
            val aside = File(file.parentFile, file.name + ".tmp")
            aside.writeText(memory.encode())
            if (!aside.renameTo(file)) {
                aside.copyTo(file, overwrite = true)
                aside.delete()
            }
        }
    }
}
