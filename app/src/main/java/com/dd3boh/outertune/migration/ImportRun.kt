/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.migration

import com.zionhuang.innertube.models.SongItem

/**
 * One file being imported: which songs it holds, what each was matched to, and what the person
 * picked for the ones that needed a look.
 *
 * A song that sits in three playlists of the same file is searched for once and reviewed once, so
 * matching works on [tracks], the file's songs with repeats taken out, and each playlist keeps the
 * index of its songs in that list.
 *
 * Plain Kotlin and synchronised, because the search runs off the main thread while the review
 * screen picks and skips on it.
 */
class ImportRun private constructor(layout: Layout) {

    /** The file's songs once each, and each playlist by name with the places of its songs among them. */
    private class Layout(val tracks: List<ImportedTrack>, val playlists: List<Pair<String, List<Int>>>)

    constructor(parsed: ImportParse.Parsed) : this(layoutOf(parsed))

    /**
     * A run as it was written down (ImportStore): the songs already once each, and the playlists
     * already as places among them. What was found comes after, a track at a time, by [restore].
     */
    constructor(tracks: List<ImportedTrack>, playlists: List<Pair<String, List<Int>>>) : this(Layout(tracks, playlists))

    val tracks: List<ImportedTrack> = layout.tracks

    /** Each playlist of the file by name, in the file's order, with the places of its songs in [tracks]. */
    val playlists: List<Pair<String, List<Int>>> = layout.playlists

    private val outcomes = arrayOfNulls<Outcome>(tracks.size)
    private val choices = HashMap<Int, Choice>()

    /**
     * The tracks whose answer or decision has changed since somebody last took the list, which
     * is what is still to be written down. Kept here, beside what changes, so that a change and
     * its mark cannot come apart between two threads.
     */
    private val changed = HashSet<Int>()

    sealed interface Choice {
        data class Picked(val song: SongItem) : Choice
        data object Skipped : Choice
    }

    @Synchronized
    fun isDone(index: Int) = outcomes[index] != null

    /** Whether every track has its answer, so that nothing is left to look up. */
    @Synchronized
    fun complete() = outcomes.none { it == null }

    @Synchronized
    fun record(index: Int, outcome: Outcome) {
        outcomes[index] = outcome
        changed += index
    }

    @Synchronized
    fun pick(index: Int, song: SongItem) {
        choices[index] = Choice.Picked(song)
        changed += index
    }

    @Synchronized
    fun skip(index: Int) {
        choices[index] = Choice.Skipped
        changed += index
    }

    /** Takes a pick or a skip back, so the track waits for a decision again. */
    @Synchronized
    fun undecide(index: Int) {
        choices.remove(index)
        changed += index
    }

    /**
     * Picks the first result for every track that has results and no decision yet: the one that
     * scored best, which is the one the review list shows first. A track somebody picked for or
     * skipped keeps what it has. These are picks like any other, each to be changed, skipped or
     * taken back by itself.
     */
    @Synchronized
    fun pickFirsts() {
        outcomes.forEachIndexed { i, outcome ->
            if (outcome is Outcome.Review && choices[i] == null) {
                outcome.candidates.firstOrNull()?.let { pick(i, it.candidate) }
            }
        }
    }

    /** What is known of one track: its answer if it has one, and what was decided about it if anything was. */
    data class Slot(val outcome: Outcome?, val choice: Choice?)

    /** The tracks from [from] up to but not [until], as they stand now, for writing them down. */
    @Synchronized
    fun slots(from: Int, until: Int): List<Slot> = (from until until).map { Slot(outcomes[it], choices[it]) }

    /** Puts back what was known of a track when the run was written down. Not a change: it is on disk already. */
    @Synchronized
    fun restore(index: Int, slot: Slot) {
        if (index !in outcomes.indices) return
        outcomes[index] = slot.outcome
        if (slot.choice != null) choices[index] = slot.choice else choices.remove(index)
    }

    @Synchronized
    fun hasChanged() = changed.isNotEmpty()

    /** The tracks changed since this was last asked, which are then no longer counted as changed. */
    @Synchronized
    fun takeChanged(): Set<Int> = changed.toSet().also { changed.clear() }

    /** Counts [indices] as changed again, for a write of them that failed. */
    @Synchronized
    fun touch(indices: Collection<Int>) {
        changed += indices
    }

    data class ReviewItem(val index: Int, val track: ImportedTrack, val candidates: List<Scored>, val choice: Choice?)

    /** A track that went in without asking, and whether somebody has since taken it out. */
    data class MatchedItem(val index: Int, val track: ImportedTrack, val best: Scored, val leftOut: Boolean)

    data class Snapshot(
        val total: Int,
        val checked: Int,
        val matchedItems: List<MatchedItem>,
        val review: List<ReviewItem>,
        val notFound: List<ImportedTrack>,
    ) {
        val matched: Int get() = matchedItems.size
        val undecided: Int get() = review.count { it.choice == null }
        val picked: Int get() = review.count { it.choice is Choice.Picked }

        /** What Create playlists would put in: matches not taken out, and picks. */
        val kept: Int get() = matchedItems.count { !it.leftOut } + picked
    }

    @Synchronized
    fun snapshot(): Snapshot {
        var checked = 0
        val matched = mutableListOf<MatchedItem>()
        val review = mutableListOf<ReviewItem>()
        val notFound = mutableListOf<ImportedTrack>()
        outcomes.forEachIndexed { i, outcome ->
            if (outcome != null) checked++
            when (outcome) {
                is Outcome.Matched -> matched += MatchedItem(i, tracks[i], outcome.best, choices[i] is Choice.Skipped)
                is Outcome.Review -> review += ReviewItem(i, tracks[i], outcome.candidates, choices[i])
                Outcome.NotFound -> notFound += tracks[i]
                null -> Unit
            }
        }
        return Snapshot(tracks.size, checked, matched, review, notFound)
    }

    /**
     * Each playlist in the file, named as the file names it, holding what was matched or picked in
     * the file's order. Anything skipped or taken out, still undecided or not found is left out,
     * and so is a song YouTube resolved twice within one playlist, which happens when a service
     * holds both the single and the album cut and YouTube only one. A playlist left with nothing
     * is dropped.
     */
    @Synchronized
    fun playlistsToCreate(): List<Pair<String, List<SongItem>>> = playlists.mapNotNull { (name, indices) ->
        val songs = indices.mapNotNull { i ->
            when (val outcome = outcomes[i]) {
                // A match can be taken out by hand, which is the answer to the few that are wrong.
                is Outcome.Matched -> outcome.best.candidate.takeUnless { choices[i] is Choice.Skipped }
                is Outcome.Review -> (choices[i] as? Choice.Picked)?.song
                else -> null
            }
        }.distinctBy { it.id }
        if (songs.isEmpty()) null else name to songs
    }

    private companion object {
        fun layoutOf(parsed: ImportParse.Parsed): Layout {
            val seen = LinkedHashMap<String, Int>()
            val unique = mutableListOf<ImportedTrack>()
            val playlists = parsed.playlists.map { playlist ->
                playlist.name to playlist.tracks.map { track ->
                    seen.getOrPut(track.key) {
                        unique += track
                        unique.lastIndex
                    }
                }
            }
            return Layout(unique, playlists)
        }
    }
}
