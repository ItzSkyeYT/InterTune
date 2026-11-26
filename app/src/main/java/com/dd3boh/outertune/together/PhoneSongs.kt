/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.together

import android.util.Log
import com.dd3boh.outertune.migration.WantedTrack
import com.dd3boh.outertune.migration.score
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
 * A file that came from YouTube says which song it is, and that comes first (Niko, #testers, 23 Nov 2026: his file of
 * 秋赤音 Flash Back, "i downloaded it from there", was not found by its tags, which name the video as its uploader wrote
 * it): the video id yt-dlp and the app's own .mka downloads put at the end of its name ([idInFileName]), or the link in its
 * tags that the app's song files and yt-dlp's keep (SongFileIds). YouTube Music is asked for that id, signed out; the
 * others hear it when its length is the file's, and the tags are looked for as before when it is not, when YouTube has
 * none, or when the ask fails.
 *
 * Nothing of the file leaves the phone: not its path, not its file name, not its id in the app (review 2, 18 Nov 2026:
 * an untagged file's name was its title, and every guest saw it). Only its tags go out, in one search, signed out, as the
 * library import's searches do (ImportSearch), or the YouTube id it carries, in one ask, signed out.
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
    /** YouTube Music's song for a video id, asked signed out: null when it has none. It throws when the ask fails. */
    private val songById: suspend (String) -> SongItem? = { null },
    /** The video id in the tags of the file at this path (SongFileIds), read from its first bytes, or null. */
    private val idInTags: suspend (String) -> String? = { null },
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
    private val waiting = ArrayDeque<Look>()

    /** A song from a file to look for: its [tags] when they say something, the [path] of its file, the id [named] in it. */
    private class Look(val id: String, val tags: StandIn.Wanted?, val seconds: Int?, val path: String?, val named: String?)
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
        val tags = tagsOf(song)
        val named = idInFileName(song?.localPath)
        // A file that names no video, and has no file to read a video's link from in its tags (SongFileIds), is known by
        // its tags alone: with none, or no length to tell the recording from another version of it, nothing would pass
        // (StandIn.MIN_DURATION). A file with a link in its tags carries its video all the same, whatever its title and
        // artist say: a yt-dlp download titled by its file name was taken for one that is only here (review of 0.13,
        // 26 Nov 2026).
        if (named == null && (tags?.durationSeconds == null) && song?.localPath.isNullOrBlank()) {
            tags ?: return keep(id, OnlyHere(madeUpFor(id), "", ""))
            return keep(id, onlyHere(id, tags))
        }
        if (id in asked || !lookUp) return null
        if (now() < restUntil || !mayAsk()) return onlyHere(id, tags)
        asked += id
        waiting.addLast(Look(id, tags, song?.duration?.takeIf { it > 0 }, song?.localPath, named))
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
                    val look = waiting.removeFirst()
                    val id = look.id
                    // Whatever goes wrong in a look is no answer, never the end of the room (TogetherSession.failed).
                    val answer = try {
                        lookFor(look)
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

    /** The answer for the song [look] is for, or null when the search failed and nothing is known of it. */
    private suspend fun lookFor(look: Look): Answer? {
        if (now() < restUntil || !mayAsk()) return null
        val id = look.id
        val carried = (look.named ?: look.path?.let { idInTagsOf(it) })?.takeIf { VIDEO_ID.matches(it) }
        var askFailed = false
        if (carried != null) {
            val byId = try {
                withTimeoutOrNull(StandIn.SEARCH_LIMIT_MS) { withContext(searchOn) { Result.success(songById(carried)) } }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "A song from a file here: YouTube Music could not be asked for the id it carries: ${e.javaClass.simpleName}")
                null
            }
            // An ask that failed is not taken for YouTube's word: the tags are looked for, whose search rests when the
            // network is the trouble, and a name that only looks like an id (YouTube may refuse to be asked for it) is
            // not asked for again once the tags have the answer.
            val song = byId?.getOrNull()?.takeIf { it.id == carried }
            if (song != null && sameLength(look.seconds, song)) {
                Log.i(TAG, "A song from a file here is $carried on YouTube Music, by the id the file carries: the others hear that")
                return heard(song)
            }
            if (byId != null) {
                Log.i(TAG, "A song from a file here: the id it carries is ${if (song == null) "no song YouTube Music has" else "another length"}, looked for by its tags")
            }
            askFailed = byId == null
        }
        val tags = look.tags?.takeIf { it.durationSeconds != null }
        if (tags == null) {
            // Nothing to search for. An ask for the id it carries that failed is asked again a while later; otherwise
            // no answer is coming.
            if (askFailed) {
                restUntil = now() + StandIn.SEARCH_REST_MS
                return null
            }
            return onlyHere(id, look.tags)
        }
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
        return heard(match)
    }

    /** YouTube Music's [match] as the others get it. */
    private fun heard(match: SongItem): Heard {
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

    /** The id in the tags of the file at [path], or null, whatever goes wrong in reading them. */
    private suspend fun idInTagsOf(path: String): String? = try {
        withContext(searchOn) { idInTags(path) }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        null
    }

    /**
     * Whether the song under the id a file carries is the file's recording by its length, as a stand-in must be
     * (StandIn.MIN_DURATION): a name that only looks like an id, or a file cut short, is not taken for it. Where either
     * length is unknown, the file's word is taken.
     */
    private fun sameLength(seconds: Int?, song: SongItem): Boolean =
        seconds == null || song.duration == null ||
            score(WantedTrack(song.title, "", seconds), song).duration >= StandIn.MIN_DURATION

    private fun onlyHere(id: String, tags: StandIn.Wanted?) =
        OnlyHere(madeUpFor(id), tags?.title.orEmpty(), tags?.artists?.joinToString(", ").orEmpty())

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

/** A YouTube video id: eleven letters, digits, dashes and underscores. */
private val VIDEO_ID = Regex("[A-Za-z0-9_-]{11}")

/** A video id in brackets at the very end of a file's name, before its extension. */
private val NAMED_ID = Regex("""\[([A-Za-z0-9_-]{11})]\s*(?:\.[A-Za-z0-9]{1,5})?$""")

/**
 * The video id at the end of the name of the file at [path], in brackets before its extension, as yt-dlp names what it
 * downloads ("Flash Back [aKiAkAnE_01].opus") and as the app named its own downloads (DirectoryMangerOt.saveFile, .mka):
 * null when it has none. Read on the phone; only YouTube Music is asked for the id, never told the name.
 */
internal fun idInFileName(path: String?): String? =
    path?.substringAfterLast('/')?.trim()?.let { NAMED_ID.find(it)?.groupValues?.get(1) }

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
