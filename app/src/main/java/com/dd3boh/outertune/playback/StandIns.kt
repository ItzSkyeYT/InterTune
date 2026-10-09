/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.playback

import android.util.Log
import com.dd3boh.outertune.migration.Scored
import com.dd3boh.outertune.migration.WantedTrack
import com.dd3boh.outertune.migration.normalise
import com.dd3boh.outertune.migration.score
import com.dd3boh.outertune.utils.YTPlayerUtils
import com.zionhuang.innertube.models.SongItem
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.cancellation.CancellationException

/**
 * A song YouTube no longer serves under the id the app knows it by, played from the id the same
 * recording goes by now.
 *
 * Ids die while songs stay. An album is put out again, a video is taken down and its audio kept,
 * one upload is blocked in a country and another is not. The app keeps the id it first saw, in
 * the library and in everything Best recommendations draws on, so a card can name a song that
 * answers "This video is not available", for a listener who wanted to hear it and who finds it at
 * once by searching for it. This makes that search for them.
 *
 * Only on YouTube's own word that the song is gone ([YTPlayerUtils.SongUnavailable]), never
 * because a stream was refused or the network failed. And only for the same recording: the same
 * title and artist once both are written the same way, and a length within a few seconds. The
 * library import's scoring does the reading ([score]), since it was built to tell a song from its
 * slowed, live, remixed and extended versions, which all carry the right name. A stand-in that is
 * not surely the song is not played: the failure the listener gets is the one they got before.
 *
 * The song keeps its own id everywhere else. Its queue entry, its place in History, its play
 * count, its cache and its download are all the song's; only the stream comes from the other id.
 */
internal object StandIn {
    /**
     * The least [com.dd3boh.outertune.migration.Scored.duration] a stand-in may have: about three
     * percent of the song's length, six seconds on a song of three minutes. Uploads of one
     * recording differ by a trimmed fade or a silent tail; a radio edit, which reads as the same
     * title once "Radio Edit" is taken as the label it is, differs by far more.
     */
    const val MIN_DURATION = 0.75

    /** How many results are read, as the import does. */
    const val CANDIDATES = 8

    /** The song as the app knows it. [artists] in the order the song lists them. */
    class Wanted(val title: String, val artists: List<String>, val durationSeconds: Int?, val explicit: Boolean? = null) {
        val query: String get() = listOfNotNull(title, artists.firstOrNull()?.ifBlank { null }).joinToString(" ")
    }

    /** How many copies are asked for a stream before the song is given up as gone everywhere. */
    const val TRIES = 3

    /**
     * How long the search may take. The player is waiting on this song with nothing to show for
     * it, and a search that has not answered by now is the network's trouble, not the song's.
     */
    const val SEARCH_LIMIT_MS = 8_000L

    /**
     * The results that are [wanted] under another id, the likeliest first; empty when none surely is.
     *
     * The artists must be the same list, or failing that the same first artist: one catalogue
     * entry names everybody on the song and the next moves the guests into the title, where
     * [normalise] drops them from both.
     *
     * @param not ids that are known not to play: the song's own, and a stand-in that has just died.
     */
    fun ranked(wanted: Wanted, not: Set<String>, found: List<SongItem>): List<SongItem> {
        val track = WantedTrack(wanted.title, wanted.artists.joinToString(" "), wanted.durationSeconds?.takeIf { it > 0 })
        val first = wanted.artists.firstOrNull()?.let { normalise(it) }?.ifEmpty { null }
        return found.take(CANDIDATES)
            .filter { it.id !in not }
            .map { it to score(track, it) }
            .filter { (candidate, scored) ->
                scored.title == 1.0 && scored.duration >= MIN_DURATION &&
                    (scored.artist == 1.0 || (first != null && first == candidate.artists.firstOrNull()?.let { normalise(it.name) }))
            }
            // The closest in length first, then one as explicit or as clean as the song, then
            // YouTube's own order.
            .sortedWith(
                compareByDescending<Pair<SongItem, Scored>> { it.second.duration }
                    .thenByDescending { wanted.explicit != null && it.first.explicit == wanted.explicit }
            )
            .map { it.first }
    }

    /** The likeliest of [ranked], for a song whose own id is all that is known not to play. */
    fun pick(wanted: Wanted, ownId: String, found: List<SongItem>): SongItem? = ranked(wanted, setOf(ownId), found).firstOrNull()
}

/**
 * Which id each song that died is played from. Kept across launches by the app, so the search is
 * made once and the song then starts as fast as any other.
 */
internal object StandInMemory {
    data class Entry(val standIn: String, val at: Long)

    data class Memory(val of: Map<String, Entry> = emptyMap())

    /**
     * How long a stand-in is used before the song's own id is asked again. Ids come back too: a
     * block is lifted, a video restored. A month on, one more refusal and one more search is a
     * fair price for noticing.
     */
    const val KEEP_MS = 30L * 24 * 60 * 60 * 1000

    /** Far more than a library has dead songs in play; the oldest go first. */
    const val MAX = 300

    @Volatile
    var current = Memory()

    /** Told the encoded memory whenever it changes, to store it. */
    @Volatile
    var onChanged: ((String) -> Unit)? = null

    fun known(memory: Memory, id: String, now: Long): String? =
        memory.of[id]?.takeIf { now - it.at in 0 until KEEP_MS }?.standIn

    /** The id to ask YouTube about for [id]: its stand-in while it has one, itself otherwise. */
    fun idFor(id: String, now: Long = System.currentTimeMillis()): String = known(current, id, now) ?: id

    fun remember(memory: Memory, id: String, standIn: String, now: Long): Memory {
        val kept = (memory.of - id).entries.sortedByDescending { it.value.at }.take(MAX - 1)
        return Memory(kept.associate { it.key to it.value } + (id to Entry(standIn, now)))
    }

    fun forget(memory: Memory, id: String): Memory = if (id in memory.of) Memory(memory.of - id) else memory

    fun encode(memory: Memory): String =
        memory.of.toSortedMap().map { (id, entry) -> "$id=${entry.standIn}@${entry.at}" }.joinToString(";")

    /** What [encode] wrote. A part that does not read as two ids and a time is dropped, not guessed at. */
    fun decode(stored: String?): Memory {
        val entries = stored?.split(';').orEmpty().mapNotNull { field ->
            val id = field.substringBefore('=', "").takeIf { ID.matches(it) }
            val standIn = field.substringAfter('=', "").substringBefore('@', "").takeIf { ID.matches(it) }
            val at = field.substringAfterLast('@', "").toLongOrNull()
            if (id != null && standIn != null && at != null) id to Entry(standIn, at) else null
        }
        return Memory(entries.toMap())
    }

    fun set(memory: Memory) {
        if (memory == current) return
        current = memory
        onChanged?.invoke(encode(memory))
    }

    private val ID = Regex("[A-Za-z0-9_-]{6,32}")
}

/**
 * The songs YouTube called gone one after the other, with no stream played between them.
 *
 * YouTube words a client it has stopped serving the way it words a song it has taken down. On
 * 9 Oct 2026 its web client answered "Video unavailable" for a song that every other client
 * played. Were the clients this app plays from ever answered that way, every song would look
 * gone, and the app would search for a copy of each, ask three copies for a stream, and call the
 * song gone: five times the requests, at the moment YouTube is turning the app away, and a
 * library of songs marked as lost.
 *
 * So a run of [LIMIT] is not believed. From there nothing is searched for, nothing more is
 * called gone, what the run did call gone is taken back ([StandIns.onDoubt]) and stand-ins already
 * remembered are kept, until a stream plays again. In memory only: a new process starts a new run.
 */
internal object GoneRun {
    const val LIMIT = 3

    private val ids = LinkedHashSet<String>()

    /** A stream played, a song's own or a stand-in's. What was called gone before it stands. */
    @Synchronized
    fun played() = ids.clear()

    /** [id] was called gone and no copy of it played. Returns the run so far, this one included. */
    @Synchronized
    fun gone(id: String): List<String> {
        ids += id
        return ids.toList()
    }

    /** Not a song already looked for in this run, and not once the run is too long to believe. */
    @Synchronized
    fun maySearch(id: String): Boolean = id !in ids && ids.size < LIMIT

    @Synchronized
    fun doubted(): Boolean = ids.size >= LIMIT
}

/**
 * Resolves a song's stream, by way of a stand-in when YouTube says the song's own id is gone.
 *
 * [resolve] is the walk of the client chain for one id, [wanted] what the app knows of a song
 * (null when it knows nothing, and then nothing is looked for), [search] YouTube's song search,
 * which throws when the request fails.
 */
internal class StandIns(
    private val resolve: suspend (String) -> Result<YTPlayerUtils.PlaybackData>,
    private val wanted: suspend (String) -> StandIn.Wanted?,
    private val search: suspend (String) -> List<SongItem>,
    private val now: () -> Long = System::currentTimeMillis,
    private val log: (String) -> Unit = { Log.i(TAG, it) },
    private val searchLimitMs: Long = StandIn.SEARCH_LIMIT_MS,
) {
    /** Told a song's id when YouTube called it gone and no copy of it played. */
    var onGone: ((String) -> Unit)? = null

    /** Told the songs of a run too long to believe ([GoneRun]): what was said of them is taken back. */
    var onDoubt: ((List<String>) -> Unit)? = null

    /** Told a song's id when it played under its own id. */
    var onPlays: ((String) -> Unit)? = null

    suspend fun playbackData(videoId: String): Result<YTPlayerUtils.PlaybackData> {
        val dead = mutableSetOf(videoId)
        StandInMemory.known(StandInMemory.current, videoId, now())?.let { known ->
            val played = resolve(known)
            if (played.isSuccess) {
                GoneRun.played()
                return played
            }
            // Only YouTube saying the stand-in is gone too ends its use, and not while nothing
            // YouTube says of that kind is believed. A refused stream or a dropped connection
            // says nothing of it either, and the song fails as any other would.
            if (played.exceptionOrNull() !is YTPlayerUtils.SongUnavailable || GoneRun.doubted()) return played
            log("[$videoId] its stand-in $known is gone as well, asking for the song itself")
            StandInMemory.set(StandInMemory.forget(StandInMemory.current, videoId))
            dead += known
        }

        val own = resolve(videoId)
        if (own.isSuccess) {
            GoneRun.played()
            onPlays?.invoke(videoId)
            return own
        }
        if (own.exceptionOrNull() !is YTPlayerUtils.SongUnavailable) return own
        if (!GoneRun.maySearch(videoId)) return own

        val song = wanted(videoId) ?: return own
        val found = try {
            withTimeoutOrNull(searchLimitMs) { search(song.query) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Nothing is known of the song's copies, so nothing is said of it either.
            log("[$videoId] gone, and the search for another copy failed: ${e.javaClass.simpleName}")
            return own
        }
        if (found == null) {
            log("[$videoId] gone, and the search for another copy did not answer in $searchLimitMs ms")
            return own
        }
        val copies = StandIn.ranked(song, dead, found).take(StandIn.TRIES)
        for (copy in copies) {
            val played = resolve(copy.id)
            if (played.isSuccess) {
                log("[$videoId] gone from YouTube under this id, played from ${copy.id}")
                GoneRun.played()
                StandInMemory.set(StandInMemory.remember(StandInMemory.current, videoId, copy.id, now()))
                return played
            }
            // A copy that is gone as well leaves the next to try. Anything else is the network
            // or the stream, and says nothing of whether the song can still be had.
            if (played.exceptionOrNull() !is YTPlayerUtils.SongUnavailable) {
                log("[$videoId] gone, and its copy ${copy.id} could not be played")
                return own
            }
        }
        log("[$videoId] gone, and " + if (copies.isEmpty()) "none of ${found.size} results is surely the same recording" else "so is every copy of it that was found")
        val run = GoneRun.gone(videoId)
        if (run.size >= GoneRun.LIMIT) {
            log("${run.size} songs in a row called gone with nothing played between them: not believed, and nothing more is looked for until a song plays")
            onDoubt?.invoke(run)
        } else {
            onGone?.invoke(videoId)
        }
        return own
    }

    private companion object {
        const val TAG = "StandIns"
    }
}
