/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.playback

import android.util.Log
import com.dd3boh.outertune.constants.Unreleased
import com.dd3boh.outertune.migration.Scored
import com.dd3boh.outertune.migration.WantedTrack
import com.dd3boh.outertune.migration.normalise
import com.dd3boh.outertune.migration.score
import com.dd3boh.outertune.utils.YTPlayerUtils
import com.zionhuang.innertube.models.SongItem
import kotlinx.coroutines.withContext
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
 * What it cannot tell apart, and so does not try: a clean take from an explicit one, which carry
 * the same name and length and which the app keeps no note of (two results that differ in it and
 * pass alike are both turned down), and two tracks of one artist under one title and of one
 * length on different albums, an "Intro" and another "Intro". The song's album settles it where
 * results on that album are among those that pass, and is not asked for otherwise, because a
 * song put out again under another album is the very case this is for.
 *
 * The song keeps its own id everywhere else. Its queue entry, its place in History, its play
 * count, its cache and its download are all the song's; only the stream comes from the other id.
 * The cache is why the resolver has to know ([StandIns.Played.from]): bytes of one upload are not
 * carried on with another's (see sameStream in CacheCompleteness).
 */
internal object StandIn {
    /**
     * The least [Scored.duration] a stand-in may have: about three percent of the song's length,
     * five seconds on a song of three minutes. Uploads of one recording differ by a trimmed fade
     * or a silent tail; a radio edit, which reads as the same title once "Radio Edit" is taken as
     * the label it is, differs by far more.
     */
    const val MIN_DURATION = 0.75

    /** How many results are read, as the import does. */
    const val CANDIDATES = 8

    /** How many copies are asked for a stream before the song is given up as gone everywhere. */
    const val TRIES = 3

    /**
     * How long the search may take. The player is waiting on this song with nothing to show for
     * it, and a search that has not answered by now is the network's trouble, not the song's.
     */
    const val SEARCH_LIMIT_MS = 8_000L

    /** After a search that failed or did not answer, how long nothing more is searched for. */
    const val SEARCH_REST_MS = 2 * 60_000L

    /**
     * The song as the app knows it. [artists] in the order the song lists them; [album] and
     * [explicit] where they are known, which for [explicit] is nowhere yet.
     */
    class Wanted(
        val title: String,
        val artists: List<String>,
        val durationSeconds: Int?,
        val album: String? = null,
        val explicit: Boolean? = null,
    ) {
        val query: String get() = listOfNotNull(title, artists.firstOrNull()?.ifBlank { null }).joinToString(" ")
    }

    /**
     * The results that are [wanted] under another id, the likeliest first; empty when none surely is.
     *
     * The artists must be the same list, or failing that the same first artist: one catalogue
     * entry names everybody on the song and the next moves the guests into the title, where
     * [normalise] drops them from both.
     *
     * Where several pass: those on the song's own album, if any are; and then, if some are
     * explicit and some are not, the kind the song is, or none at all when that is not known.
     *
     * @param not ids that are known not to play: the song's own, and a stand-in that has just died.
     */
    fun ranked(wanted: Wanted, not: Set<String>, found: List<SongItem>): List<SongItem> {
        val track = WantedTrack(wanted.title, wanted.artists.joinToString(" "), wanted.durationSeconds?.takeIf { it > 0 })
        val first = wanted.artists.firstOrNull()?.let { normalise(it) }?.ifEmpty { null }
        var passing = found.take(CANDIDATES)
            .filter { it.id !in not }
            .map { it to score(track, it) }
            .filter { (candidate, scored) ->
                scored.title == 1.0 && scored.duration >= MIN_DURATION &&
                    (scored.artist == 1.0 || (first != null && first == candidate.artists.firstOrNull()?.let { normalise(it.name) }))
            }
        val album = wanted.album?.let { normalise(it) }?.ifEmpty { null }
        if (album != null) {
            passing.filter { (candidate, _) -> candidate.album?.name?.let { normalise(it) } == album }
                .takeIf { it.isNotEmpty() }?.let { passing = it }
        }
        if (passing.map { it.first.explicit }.distinct().size > 1) {
            passing = if (wanted.explicit == null) emptyList() else passing.filter { it.first.explicit == wanted.explicit }
        }
        // The closest in length first, then YouTube's own order.
        return passing.sortedByDescending { it.second.duration }.map { it.first }
    }

    /** The likeliest of [ranked], for a song whose own id is all that is known not to play. */
    fun pick(wanted: Wanted, ownId: String, found: List<SongItem>): SongItem? = ranked(wanted, setOf(ownId), found).firstOrNull()
}

/**
 * What is known of the songs whose own id is gone: the id each is played from, or that it was
 * looked for and nothing was surely the song. Kept across launches by the app, so the search is
 * made once and the song then starts as fast as any other, or fails as fast as it used to.
 */
internal object StandInMemory {
    /** [standIn] null: looked for, and no result was surely the song. */
    data class Entry(val standIn: String?, val at: Long)

    data class Memory(val of: Map<String, Entry> = emptyMap())

    /**
     * How long a stand-in is used before the song's own id is asked again. Ids come back too: a
     * block is lifted, a video restored. A month on, one more refusal and one more search is a
     * fair price for noticing.
     */
    const val KEEP_MS = 30L * 24 * 60 * 60 * 1000

    /**
     * How long a search that found nothing is not made again. Shorter: a copy can be put up any
     * day, and what the note saves is a search and three more requests at every try of a song
     * that fails either way.
     */
    const val NONE_MS = 3L * 24 * 60 * 60 * 1000

    /** Far more than a library has dead songs in play; the oldest go first. */
    const val MAX = 300

    @Volatile
    var current = Memory()

    /** Told the encoded memory whenever it changes, to store it. */
    @Volatile
    var onChanged: ((String) -> Unit)? = null

    fun known(memory: Memory, id: String, now: Long): String? =
        memory.of[id]?.takeIf { now - it.at in 0 until KEEP_MS }?.standIn

    /** Whether [id] was looked for lately and nothing was surely it. */
    fun lookedInVain(memory: Memory, id: String, now: Long): Boolean =
        memory.of[id]?.let { it.standIn == null && now - it.at in 0 until NONE_MS } == true

    /** The id to ask YouTube about for [id]: its stand-in while it has one, itself otherwise. */
    fun idFor(id: String, now: Long = System.currentTimeMillis()): String = known(current, id, now) ?: id

    /** [standIn] null notes a search that found nothing. Either takes the place of what was there. */
    fun remember(memory: Memory, id: String, standIn: String?, now: Long): Memory {
        val kept = (memory.of - id).entries.sortedByDescending { it.value.at }.take(MAX - 1)
        return Memory(kept.associate { it.key to it.value } + (id to Entry(standIn, now)))
    }

    fun forget(memory: Memory, id: String): Memory = if (id in memory.of) Memory(memory.of - id) else memory

    fun encode(memory: Memory): String =
        memory.of.toSortedMap().map { (id, entry) -> "$id=${entry.standIn.orEmpty()}@${entry.at}" }.joinToString(";")

    /** What [encode] wrote. A part that does not read as an id, an id or nothing, and a time is dropped, not guessed at. */
    fun decode(stored: String?): Memory {
        val entries = stored?.split(';').orEmpty().mapNotNull { field ->
            if ('=' !in field || '@' !in field) return@mapNotNull null
            val id = field.substringBefore('=').takeIf { ID.matches(it) }
            val standIn = field.substringAfter('=').substringBeforeLast('@')
            val at = field.substringAfterLast('@').toLongOrNull()
            if (id == null || at == null || (standIn.isNotEmpty() && !ID.matches(standIn))) null
            else id to Entry(standIn.ifEmpty { null }, at)
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
 * The songs YouTube called gone one after the other, with no stream resolved between them.
 *
 * YouTube words a client it has stopped serving the way it words a song it has taken down. On
 * 9 Oct 2026 its web client answered "Video unavailable" for a song that every other client
 * played. Were the clients this app plays from ever answered that way, every song would look
 * gone, and the app would search for a copy of each and ask three copies for a stream: five times
 * the requests, at the moment YouTube is turning the app away.
 *
 * So a run of [LIMIT] is not believed. From there nothing is searched for and nothing is noted,
 * and what the run did note is put back ([StandIns]), until a stream resolves again. A stream
 * resolved, not a song heard: one played from the cache asks YouTube nothing and says nothing of
 * it. In memory only: a new process starts a new run. The player has one run and downloads have
 * another, so a batch of downloads cannot end the player's.
 */
internal class GoneRun {
    private val ids = LinkedHashSet<String>()

    /** A stream resolved, a song's own or a stand-in's. */
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

    companion object {
        const val LIMIT = 3
    }
}

/**
 * Resolves a song's stream, by way of a stand-in when YouTube says the song's own id is gone.
 *
 * [resolve] is the walk of the client chain for one id, [wanted] what the app knows of a song
 * (null when it knows nothing, and then nothing is looked for), [search] YouTube's song search,
 * which throws when the request fails. With [enabled] false it is [resolve] and nothing else.
 *
 * [trail] is what the walk just made answered, as the error report words it, and [report] puts
 * a whole lookup there in its place: see [Lookup].
 *
 * [setAside] says whether a song is set aside as gone (GoneSongs: the Exclusions page lists it,
 * with Lift). Such a song, looked for in vain these last days, is not asked again at every tap:
 * it fails at once as gone, with nothing asked of YouTube and nothing searched. On 21 Oct 2026 a
 * song set aside at its third tap was walked and searched for at ten more. Within the three days
 * of [StandInMemory.NONE_MS] only, after which it is asked again, as a copy may have been put up
 * or the id come back; and at once when it is lifted. Never when the listener asks for it again
 * ([playbackData]'s askedAgain: Retry, or Play on the song the music stopped on): only the
 * player's own passes, the queue reaching it and its replays, are spared the walk.
 */
internal class StandIns(
    private val resolve: suspend (String) -> Result<YTPlayerUtils.PlaybackData>,
    private val wanted: suspend (String) -> StandIn.Wanted?,
    private val search: suspend (String) -> List<SongItem>,
    private val now: () -> Long = System::currentTimeMillis,
    private val log: (String) -> Unit = { Log.i(TAG, it) },
    private val searchLimitMs: Long = StandIn.SEARCH_LIMIT_MS,
    private val run: GoneRun = GoneRun(),
    private val enabled: () -> Boolean = { Unreleased.STAND_INS },
    private val trail: () -> String? = { YTPlayerUtils.lastStreamTrail },
    private val report: (String) -> Unit = { YTPlayerUtils.reportTrail(it) },
    private val setAside: suspend (String) -> Boolean = { false },
) {
    /** A stream, and the id it was had under: the song's own, or its stand-in's. */
    class Played(val data: YTPlayerUtils.PlaybackData, val from: String)

    /**
     * Told a song's id each time YouTube called it gone and no copy of it played, and whether the
     * account said so too ([YTPlayerUtils.SongUnavailable.byTheAccountToo]).
     */
    var onGone: ((id: String, sure: Boolean) -> Unit)? = null

    /**
     * Told the songs of a run that is not believed ([GoneRun]), as what the run noted of them is
     * put back: what was set aside at once on the account's word goes back with it.
     */
    var onDoubted: ((List<String>) -> Unit)? = null

    /** Told a song's id each time a stream was had for it, under its own id or a stand-in's. */
    var onPlays: ((String) -> Unit)? = null

    /** What the memory held for each song of the run before the run noted anything, to put back. */
    private val before = HashMap<String, StandInMemory.Entry?>()

    /** When a search last failed or did not answer; nothing is searched for a while after. */
    @Volatile
    private var searchFailedAt: Long? = null

    /**
     * What one lookup asked, in order, for the error report. Its "stream chain" is the last walk
     * made, and after a lookup that is the last copy's, shown under an error that is the song's
     * own, with no sign that anything was searched for. So the lookup's walks go there together:
     * "own id: VISIONOS ERROR, IOS ERROR | search: 26 results, none surely the same recording".
     * Without the ids: the report is pasted into issues, and an id says what somebody listens to.
     * A song resolved in one walk under its own id leaves the chain as that walk wrote it.
     */
    private inner class Lookup {
        private val steps = mutableListOf<String>()
        private var plain = true

        suspend fun walk(what: String, id: String): Result<YTPlayerUtils.PlaybackData> {
            if (what != OWN || steps.isNotEmpty()) plain = false
            // Another copy is not asked of the account: see YTPlayerUtils.OfAnotherCopy.
            val walked = if (what == OWN) resolve(id) else withContext(YTPlayerUtils.OfAnotherCopy()) { resolve(id) }
            return walked.also { steps += "$what: ${trail() ?: "unknown"}" }
        }

        fun note(what: String) {
            plain = false
            steps += what
        }

        fun <T> told(result: T): T {
            if (!plain) report(steps.joinToString(" | "))
            return result
        }
    }

    suspend fun playbackData(videoId: String, askedAgain: Boolean = false): Result<Played> {
        if (!enabled()) return resolve(videoId).map { Played(it, videoId) }
        val lookup = Lookup()
        return lookup.told(look(videoId, lookup, askedAgain))
    }

    private suspend fun look(videoId: String, lookup: Lookup, askedAgain: Boolean): Result<Played> {
        val dead = mutableSetOf(videoId)
        val known = StandInMemory.known(StandInMemory.current, videoId, now())
        // Set aside as gone and looked for in vain lately: not asked again at every tap, see
        // setAside. Nothing is counted in the run or reported: YouTube was not asked.
        if (!askedAgain && known == null && StandInMemory.lookedInVain(StandInMemory.current, videoId, now()) && setAside(videoId)) {
            log("[$videoId] set aside as gone from YouTube, and looked for in vain lately: not asked again for now")
            lookup.note(SET_ASIDE)
            return Result.failure(YTPlayerUtils.SongUnavailable(NOT_ASKED))
        }
        if (known != null) {
            val played = lookup.walk("stand-in", known)
            if (played.isSuccess) return had(videoId, played, known)
            // Only YouTube saying the stand-in is gone too sends the song back to be looked for,
            // and not while nothing YouTube says of that kind is believed. A refused stream or a
            // dropped connection says nothing of it, and the song fails as any other would.
            if (played.exceptionOrNull() is YTPlayerUtils.SongUnavailable && run.doubted()) return turnedAway(played)
            if (played.exceptionOrNull() !is YTPlayerUtils.SongUnavailable) return played.map { Played(it, known) }
            log("[$videoId] its stand-in $known is gone as well, asking for the song itself")
            dead += known
        }

        val own = lookup.walk(OWN, videoId)
        if (own.isSuccess) {
            // Back under its own id: what was noted of it is no longer true.
            synchronized(run) { StandInMemory.set(StandInMemory.forget(StandInMemory.current, videoId)) }
            return had(videoId, own, videoId)
        }
        val failed = own.map { Played(it, videoId) }
        if (own.exceptionOrNull() !is YTPlayerUtils.SongUnavailable) return failed
        // The account was asked and called it gone too: see YTPlayerUtils.SongUnavailable.
        val sure = (own.exceptionOrNull() as YTPlayerUtils.SongUnavailable).byTheAccountToo
        if (!run.maySearch(videoId)) {
            lookup.note(
                if (run.doubted()) "no search: ${GoneRun.LIMIT} songs in a row were called gone, which is not believed"
                else "no search: looked for already since a stream last resolved"
            )
            return if (run.doubted()) turnedAway(own) else failed
        }
        // Looked for these last days, and nothing was surely it: it fails as fast as it used to.
        if (known == null && StandInMemory.lookedInVain(StandInMemory.current, videoId, now())) {
            lookup.note("no search: looked for these last days, and nothing was surely it")
            onGone?.invoke(videoId, sure)
            return failed
        }
        if (searchFailedAt?.let { now() - it in 0 until StandIn.SEARCH_REST_MS } == true) {
            lookup.note("no search: one failed a moment ago")
            return failed
        }

        val song = wanted(videoId)
        if (song == null) {
            lookup.note("no search: nothing is known of the song")
            return failed
        }
        val found = try {
            withTimeoutOrNull(searchLimitMs) { search(song.query) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Nothing is known of the song's copies, so nothing is said of it either.
            log("[$videoId] gone, and the search for another copy failed: ${e.javaClass.simpleName}")
            lookup.note("search failed: ${e.javaClass.simpleName}")
            searchFailedAt = now()
            return failed
        }
        if (found == null) {
            log("[$videoId] gone, and the search for another copy did not answer in $searchLimitMs ms")
            lookup.note("search did not answer in $searchLimitMs ms")
            searchFailedAt = now()
            return failed
        }
        val copies = StandIn.ranked(song, dead, found).take(StandIn.TRIES)
        if (copies.isEmpty()) lookup.note("search: ${found.size} results, none surely the same recording")
        for ((n, copy) in copies.withIndex()) {
            val played = lookup.walk("copy ${n + 1}", copy.id)
            if (played.isSuccess) {
                log("[$videoId] gone from YouTube under this id, played from ${copy.id}")
                synchronized(run) { StandInMemory.set(StandInMemory.remember(StandInMemory.current, videoId, copy.id, now())) }
                return had(videoId, played, copy.id)
            }
            // A copy that is gone as well leaves the next to try. Anything else is the network
            // or the stream, and says nothing of whether the song can still be had.
            if (played.exceptionOrNull() !is YTPlayerUtils.SongUnavailable) {
                log("[$videoId] gone, and its copy ${copy.id} could not be played")
                return failed
            }
        }
        log("[$videoId] gone, and " + if (copies.isEmpty()) "none of ${found.size} results is surely the same recording" else "so is every copy of it that was found")
        noCopy(videoId, sure)
        // The song that makes the run too long to believe is the first said to be the connection.
        return if (run.doubted()) turnedAway(failed) else failed
    }

    /**
     * A song called gone while the run of them is too long to believe ([GoneRun.doubted]): not
     * the song, the connection, turned away in the words YouTube has for a song that is gone
     * (issue #28). It fails as that, so that the player says so instead of "This video is not
     * available" song after song, and nothing is looked for or noted. YouTube's words go with it,
     * for the log.
     */
    private fun turnedAway(failed: Result<*>): Result<Played> =
        Result.failure(YTPlayerUtils.ConnectionTurnedAway(failed.exceptionOrNull()?.message))

    /** A stream was had for [videoId] under [from]: the run is over, and what it noted stands. */
    private fun had(videoId: String, played: Result<YTPlayerUtils.PlaybackData>, from: String): Result<Played> {
        synchronized(run) {
            run.played()
            before.clear()
        }
        onPlays?.invoke(videoId)
        return played.map { Played(it, from) }
    }

    /**
     * [videoId] is gone and no copy of it played. Noted, so it is not looked for again for a few
     * days, and reported, [sure] when the account said so too. Unless it is the one that makes the
     * run too long to believe: then what the run noted is put back as it was, the run's songs are
     * handed to [onDoubted], and nothing is reported.
     */
    private fun noCopy(videoId: String, sure: Boolean) {
        var doubted: List<String>? = null
        val told = synchronized(run) {
            val ids = run.gone(videoId)
            if (videoId !in before) before[videoId] = StandInMemory.current.of[videoId]
            if (ids.size >= GoneRun.LIMIT) {
                log("${ids.size} songs in a row called gone with no stream resolved between them: not believed, and nothing more is looked for until one resolves")
                var memory = StandInMemory.current
                for ((id, entry) in before) {
                    memory = if (entry == null) StandInMemory.forget(memory, id) else StandInMemory.Memory(memory.of + (id to entry))
                }
                doubted = before.keys.toList()
                before.clear()
                StandInMemory.set(memory)
                false
            } else {
                StandInMemory.set(StandInMemory.remember(StandInMemory.current, videoId, null, now()))
                true
            }
        }
        doubted?.let { onDoubted?.invoke(it) }
        if (told) onGone?.invoke(videoId, sure)
    }

    private companion object {
        const val TAG = "StandIns"
        const val OWN = "own id"

        /** For the error report, in place of a walk: see setAside. */
        const val SET_ASIDE = "not asked: set aside as gone, and looked for in vain these last days"

        /** The failure's words, for the log and the error's details: the player shows its own sentence for any song that is gone. */
        const val NOT_ASKED = "Set aside as gone from YouTube, not asked again for now"
    }
}
