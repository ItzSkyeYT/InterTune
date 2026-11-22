/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.together

import com.dd3boh.outertune.migration.ImportSearch
import com.dd3boh.outertune.migration.WantedTrack
import com.dd3boh.outertune.migration.match
import com.zionhuang.innertube.models.SongItem
import kotlinx.coroutines.CancellationException

/**
 * The YouTube song that stands in, in a room, for a song from a file on the leader's phone (his ask of 22 Nov 2026,
 * "yes! could we try to implement that?"). Nobody else in the room has the file, so the guests used to sit on "song
 * unavailable" until the next song. Now the leader looks it up on YouTube Music by its title, artist and length, as
 * the import does (TrackMatch, ImportSearch), and the room plays the YouTube song while the leader plays its file.
 *
 * Only a confident match, by TrackMatch's own rule (the lengths compared and 0.84 or more): a remix or a live take in
 * everyone's ears is worse than nothing, and with nothing the guests show the song as unavailable, as before. Only a
 * file with a title and an artist tag is looked up: a file's name, which an untagged file has as its title, is never
 * sent to YouTube. One
 * search a song for the whole room, from the leader's phone, signed out (ImportSearch), with the title alone tried when
 * the title and the artist find nothing sure, as the import does; a search that fails is tried again only after
 * [RETRY_MS], not with each word the leader sends. Guests on 0.13 play the stand-in as any other song. Main thread.
 */
class RoomStandIns(
    private val search: suspend (String) -> List<SongItem> = ImportSearch::songs,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    /** Each file's stand-in, null for a file that has none. */
    private val found = HashMap<String, String?>()
    private val asking = HashSet<String>()
    private val failedAt = HashMap<String, Long>()

    /**
     * The id the room plays for the song [mediaId]: itself, unless it is a file on this phone; for a file, its stand-in
     * once found, and null while it is not known or there is none.
     */
    fun roomId(mediaId: String, isLocal: Boolean?): String? =
        if (!fromThisPhone(isLocal, mediaId)) mediaId else found[mediaId]

    /** Whether the file [mediaId] is still to be looked up: not known, not being looked up, not failed a moment ago. */
    fun toLookUp(mediaId: String, isLocal: Boolean?): Boolean =
        fromThisPhone(isLocal, mediaId) && mediaId !in found && mediaId !in asking &&
            failedAt[mediaId]?.let { clock() - it >= RETRY_MS } != false

    /**
     * Looks up the stand-in of the file [mediaId], tagged [title] by [artist], [durationS] seconds long (null or 0 when
     * not known). Its YouTube id, or null: none sure enough, or the search failed, in which case it is tried again later.
     */
    suspend fun lookUp(mediaId: String, title: String, artist: String, durationS: Int?): String? {
        if (mediaId in found) return found[mediaId]
        if (title.isBlank() || artist.isBlank()) {
            // A file with no artist tag is not asked about: an untagged file's title is its name, which can be private
            // (a voice note, a recording), and is never sent to YouTube.
            found[mediaId] = null
            return null
        }
        if (!asking.add(mediaId)) return null
        try {
            val wanted = WantedTrack(title.trim(), artist.trim(), durationS?.takeIf { it > 0 })
            var best = match(wanted, search("${title.trim()} ${artist.trim()}").take(CANDIDATES))
            if (best?.confident != true) {
                val byTitle = match(wanted, search(title.trim()).take(CANDIDATES))
                if (byTitle != null && (best == null || byTitle.total > best.total)) best = byTitle
            }
            val id = best?.takeIf { it.confident }?.candidate?.id
            found[mediaId] = id
            failedAt.remove(mediaId)
            return id
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            failedAt[mediaId] = clock()
            return null
        } finally {
            asking.remove(mediaId)
        }
    }

    companion object {
        /** The results scored of each search, as the import scores them. */
        const val CANDIDATES = 10

        /** How long a search that failed is left before it is tried again. */
        const val RETRY_MS = 60_000L
    }
}
