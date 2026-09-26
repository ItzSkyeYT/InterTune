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
class ImportRun(val parsed: ImportParse.Parsed) {

    val tracks: List<ImportedTrack>
    private val playlistIndices: List<Pair<String, List<Int>>>

    init {
        val seen = LinkedHashMap<String, Int>()
        val unique = mutableListOf<ImportedTrack>()
        playlistIndices = parsed.playlists.map { playlist ->
            playlist.name to playlist.tracks.map { track ->
                seen.getOrPut(track.key) {
                    unique += track
                    unique.lastIndex
                }
            }
        }
        tracks = unique
    }

    private val outcomes = arrayOfNulls<Outcome>(tracks.size)
    private val choices = HashMap<Int, Choice>()

    sealed interface Choice {
        data class Picked(val song: SongItem) : Choice
        data object Skipped : Choice
    }

    @Synchronized
    fun isDone(index: Int) = outcomes[index] != null

    @Synchronized
    fun record(index: Int, outcome: Outcome) {
        outcomes[index] = outcome
    }

    @Synchronized
    fun pick(index: Int, song: SongItem) {
        choices[index] = Choice.Picked(song)
    }

    @Synchronized
    fun skip(index: Int) {
        choices[index] = Choice.Skipped
    }

    /** Takes a pick or a skip back, so the track waits for a decision again. */
    @Synchronized
    fun undecide(index: Int) {
        choices.remove(index)
    }

    data class ReviewItem(val index: Int, val track: ImportedTrack, val candidates: List<Scored>, val choice: Choice?)

    data class Snapshot(
        val total: Int,
        val checked: Int,
        val matched: Int,
        val review: List<ReviewItem>,
        val notFound: List<ImportedTrack>,
    ) {
        val undecided: Int get() = review.count { it.choice == null }
        val picked: Int get() = review.count { it.choice is Choice.Picked }
    }

    @Synchronized
    fun snapshot(): Snapshot {
        var checked = 0
        var matched = 0
        val review = mutableListOf<ReviewItem>()
        val notFound = mutableListOf<ImportedTrack>()
        outcomes.forEachIndexed { i, outcome ->
            if (outcome != null) checked++
            when (outcome) {
                is Outcome.Matched -> matched++
                is Outcome.Review -> review += ReviewItem(i, tracks[i], outcome.candidates, choices[i])
                Outcome.NotFound -> notFound += tracks[i]
                null -> Unit
            }
        }
        return Snapshot(tracks.size, checked, matched, review, notFound)
    }

    /**
     * Each playlist in the file, named as the file names it, holding what was matched or picked in
     * the file's order. Anything skipped, still undecided or not found is left out, and so is a
     * song YouTube resolved twice within one playlist, which happens when a service holds both the
     * single and the album cut and YouTube only one. A playlist left with nothing is dropped.
     */
    @Synchronized
    fun playlistsToCreate(): List<Pair<String, List<SongItem>>> = playlistIndices.mapNotNull { (name, indices) ->
        val songs = indices.mapNotNull { i ->
            when (val outcome = outcomes[i]) {
                is Outcome.Matched -> outcome.best.candidate
                is Outcome.Review -> (choices[i] as? Choice.Picked)?.song
                else -> null
            }
        }.distinctBy { it.id }
        if (songs.isEmpty()) null else name to songs
    }
}
