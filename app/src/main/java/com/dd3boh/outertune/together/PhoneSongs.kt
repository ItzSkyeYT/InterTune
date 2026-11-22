/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.together

import android.util.Log
import com.dd3boh.outertune.models.MediaMetadata
import com.dd3boh.outertune.models.toMediaMetadata
import com.dd3boh.outertune.playback.StandIn
import com.zionhuang.innertube.models.SongItem
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.CoroutineContext
import kotlin.random.Random

/**
 * Songs from files on the phone in control, as the rest of the room hears them (his rule of 22 Nov 2026: people who
 * listen to their own files are first-class). No other phone can play a file: Niko, in control in #testers, played one,
 * and the others were sent its id in the app, which played nowhere, with no word and no cover.
 *
 * So the others hear the same recording from YouTube Music, found here, once, by the file's tags: its title and artist,
 * never its file name, and a length within a few seconds, by the rules that find a song whose own id YouTube no longer
 * serves (StandIn). The match goes to the room in the song's place, its cover with it. A file with no usable tags, or
 * one with no match, goes as [OnlyHere]: an id made up for the room, in the shape of a song from a phone's files, so that
 * the others say why they hear nothing and take the room's next song as ever (TogetherSession.holdForPhoneSong); its
 * tags' name goes with it when it has one.
 *
 * Nothing of the file leaves the phone: not its path, not its file name, not its id in the app (review 2, 18 Nov 2026:
 * an untagged file's name was its title, and every guest saw it). Only its tags go out, in one search, signed out, as the
 * library import's searches do (ImportSearch).
 */
internal class PhoneSongs(
    private val scope: CoroutineScope,
    /** YouTube Music's songs for a query. It throws when the search fails; an empty list is an answer. */
    private val search: suspend (String) -> List<SongItem>,
    /** A search has its answer, or failed, for the song with this id; [more] while others still wait for theirs. */
    private val answered: (id: String, more: Boolean) -> Unit,
    /** False while YouTube is refusing this network (Throttle): nothing is asked of it then. */
    private val mayAsk: () -> Boolean = { true },
    private val spacingMs: Long = SPACING_MS,
    private val searchOn: CoroutineContext = Dispatchers.IO,
    private val now: () -> Long = System::currentTimeMillis,
) {
    /** What the others in the room get for a song from a file here, in a State or a Queue. */
    sealed interface Answer {
        val id: String
        val title: String
        val artist: String
    }

    /** The same recording on YouTube Music: every other phone plays it. */
    data class Heard(val song: TogetherMessage.Song) : Answer {
        override val id: String get() = song.id
        override val title: String get() = song.title
        override val artist: String get() = song.artist
    }

    /** Not on YouTube Music as far as its tags tell, or no tags to look with: [id] is made up for the room. */
    data class OnlyHere(override val id: String, override val title: String, override val artist: String) : Answer

    private val answers = LinkedHashMap<String, Answer>()
    private val madeUp = LinkedHashMap<String, String>()
    private val waiting = ArrayDeque<Pair<String, StandIn.Wanted>>()
    private val asked = mutableSetOf<String>()
    private var working = false

    /** After a search that failed or did not answer, nothing is asked until then (StandIn.SEARCH_REST_MS). */
    private var restUntil = 0L

    /**
     * What the room gets for the song from a file here with [id], [song] being what the app knows of it: its answer, or
     * null while it is looked for. Looked for when [lookUp] and not known yet. While YouTube refuses this network, or a
     * moment after a search failed, it is [OnlyHere] for now, and looked for again later.
     */
    fun forRoom(id: String, song: MediaMetadata?, lookUp: Boolean): Answer? {
        answers[id]?.let { return it }
        val tags = tagsOf(song) ?: return keep(id, OnlyHere(madeUpFor(id), "", ""))
        // No length to tell the recording from another version of it: nothing would pass (StandIn.MIN_DURATION).
        if (tags.durationSeconds == null) return keep(id, onlyHere(id, tags))
        if (id in asked || !lookUp) return null
        if (now() < restUntil || !mayAsk()) return onlyHere(id, tags)
        asked += id
        waiting.addLast(id to tags)
        if (!working) work()
        return null
    }

    /** One search at a time, [spacingMs] apart, as the library import paces its own (ImportMatcher). */
    private fun work() {
        working = true
        scope.launch {
            try {
                var first = true
                while (waiting.isNotEmpty()) {
                    if (!first) delay(spacingMs)
                    first = false
                    val (id, tags) = waiting.removeFirst()
                    // Whatever goes wrong in a look is no answer, never the end of the room (TogetherSession.failed).
                    val answer = try {
                        lookFor(id, tags)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        Log.w(TAG, "A song from a file here could not be matched: ${e.javaClass.simpleName}")
                        null
                    }
                    asked -= id
                    if (answer != null) keep(id, answer)
                    answered(id, waiting.isNotEmpty())
                }
            } finally {
                working = false
            }
        }
    }

    /** The answer for the song with [id] and [tags], or null when the search failed and nothing is known of it. */
    private suspend fun lookFor(id: String, tags: StandIn.Wanted): Answer? {
        if (now() < restUntil || !mayAsk()) return null
        val found = try {
            withTimeoutOrNull(StandIn.SEARCH_LIMIT_MS) { withContext(searchOn) { search(tags.query) } }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "A song from a file here could not be looked for on YouTube Music: ${e.javaClass.simpleName}")
            null
        }
        if (found == null) {
            restUntil = now() + StandIn.SEARCH_REST_MS
            return null
        }
        val match = StandIn.ranked(tags, emptySet(), found).firstOrNull()
            // A clean and an explicit take both pass, and a file does not say which it is: the explicit one, the album's
            // own more often than not.
            ?: StandIn.ranked(StandIn.Wanted(tags.title, tags.artists, tags.durationSeconds, tags.album, explicit = true), emptySet(), found).firstOrNull()
        if (match == null) {
            // Never its name: a report can be posted in public.
            Log.i(TAG, "A song from a file here: none of ${found.size} results on YouTube Music is surely the same recording")
            return onlyHere(id, tags)
        }
        Log.i(TAG, "A song from a file here is ${match.id} on YouTube Music: the others hear that")
        val heard = match.toMediaMetadata()
        return Heard(
            TogetherMessage.Song(
                id = heard.id,
                title = heard.title,
                artist = heard.artists.joinToString { it.name },
                thumbnail = TogetherMessage.thumbnailFor(heard.thumbnailUrl),
            )
        )
    }

    private fun onlyHere(id: String, tags: StandIn.Wanted) = OnlyHere(madeUpFor(id), tags.title, tags.artists.joinToString(", "))

    private fun keep(id: String, answer: Answer): Answer {
        answers.remove(id)
        answers[id] = answer
        while (answers.size > KEPT) answers.remove(answers.keys.first())
        return answer
    }

    /**
     * The id the others get for the song with [id] when they can't hear it: the same one for the same song while the room
     * lasts, and nothing of the song's own. In the shape of a song from a phone's files (fromThisPhone), which every
     * phone in a room knows it can't play, this build's and those before it.
     */
    private fun madeUpFor(id: String): String {
        madeUp[id]?.let { return it }
        var made: String
        do {
            made = "LS" + (1..8).map { LETTERS[Random.nextInt(LETTERS.length)] }.joinToString("")
        } while (made == id)
        madeUp[id] = made
        while (madeUp.size > KEPT) madeUp.remove(madeUp.keys.first())
        return made
    }

    companion object {
        private const val TAG = "Together"

        /** About two and a half searches a second at most, as the library import (ImportMatcher.SPACING_MS). */
        const val SPACING_MS = 400L

        /** How many songs from files are looked for ahead in the queue: the next half hour or so. */
        const val AHEAD = 10

        /** Answers kept while the room lasts. */
        const val KEPT = 300

        private const val LETTERS = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789"
    }
}

/** What the phone calls an artist a file's tags don't name (MediaStore.UNKNOWN_STRING). */
private const val UNKNOWN_ARTIST = "<unknown>"

/**
 * What the tags of a song from a file say, to look for it on YouTube Music with: null when they say nothing usable. An
 * untagged file is titled by its file name (every scanner does so), with or without its extension, and an artist the
 * phone doesn't know is "<unknown>": neither is a tag, and a file's name never leaves the phone.
 */
internal fun tagsOf(song: MediaMetadata?): StandIn.Wanted? {
    song ?: return null
    val title = song.title.trim()
    val file = song.localPath?.substringAfterLast('/')?.trim().orEmpty()
    if (title.isEmpty() || file.isEmpty()) return null
    if (title.equals(file, ignoreCase = true) || title.equals(file.substringBeforeLast('.').trim(), ignoreCase = true)) return null
    val artists = song.artists.map { it.name.trim() }.filter { it.isNotEmpty() && !it.equals(UNKNOWN_ARTIST, ignoreCase = true) }
    if (artists.isEmpty()) return null
    return StandIn.Wanted(title, artists, song.duration.takeIf { it > 0 }, album = song.album?.title?.trim()?.ifEmpty { null })
}
