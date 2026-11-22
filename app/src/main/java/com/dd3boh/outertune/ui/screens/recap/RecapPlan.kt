/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.ui.screens.recap

import com.dd3boh.outertune.recap.RecapPage
import com.dd3boh.outertune.recap.RecapPiece
import com.dd3boh.outertune.recap.RecapShown
import com.dd3boh.outertune.recap.RecapSongShown

/**
 * The slides, in the mock's order. A slide the year has nothing for is left out. NOTE_READ, last
 * year's note to this year's you, comes only when one was sealed; NOTE_WRITE, the note to next
 * year's you, always comes just before the thank-you page (his add of 22 Nov 2026, RecapNote).
 */
enum class SlideKind { SINCE, NOTE_READ, MINUTES, HOUR, STREAK, ARTISTS, ONLY_HERE, WHERE_FROM, SKIPPED, COUNTDOWN, NUMBER_ONE, CARD, NOTE_WRITE, THANKS }

/**
 * One slide: the length the piece of its song is cut to for each of its [steps] (0, the last one,
 * stays until left), the song under it at each step, and the cover it is about. The story itself
 * moves on 7 s after everything on the slide has settled (RecapStage.lengthOf), whatever [ms] says.
 */
class Slide(
    val kind: SlideKind,
    val ms: Long,
    val steps: Int = 1,
    private val songAt: (Int) -> RecapSongShown?,
    private val coverAt: (Int) -> Any? = { songAt(it)?.cover },
) {
    fun song(step: Int): RecapSongShown? = songAt(step)
    fun cover(step: Int): Any? = coverAt(step)
    fun piece(step: Int): RecapPiece? = song(step)?.let { RecapPiece.of(it.durationS, ms) }
}

/** The year's slides. */
fun planSlides(shown: RecapShown): List<Slide> {
    val r = shown.recap
    val top = r.topSongs.first()
    return buildList {
        add(Slide(SlideKind.SINCE, 9_000, songAt = { shown.song(r.firstSong.songId) }))
        // Last year's note, under the first song still: as long as its words take to read.
        shown.pastNote?.let { n -> add(Slide(SlideKind.NOTE_READ, 10_000 + noteReadMs(n.text), songAt = { shown.song(r.firstSong.songId) }, coverAt = { null })) }
        add(Slide(SlideKind.MINUTES, 10_000, songAt = { shown.song(r.mostTime?.songId ?: top.songId) }))
        add(Slide(SlideKind.HOUR, 10_000, songAt = { shown.song(r.peakSong.songId) }))
        r.streak?.let { s -> add(Slide(SlideKind.STREAK, 10_000, songAt = { shown.song(s.songId) })) }
        r.topArtists.firstOrNull()?.let { a ->
            add(
                Slide(
                    SlideKind.ARTISTS, 11_000,
                    songAt = { shown.song(a.topSongId) },
                    coverAt = { shown.artists[a.artistId]?.picture ?: shown.song(a.topSongId)?.cover },
                )
            )
        }
        if (r.auto != null || r.recognition != null || r.rooms != null) {
            val song = r.auto?.stuck?.songId ?: r.recognition?.songIds?.firstOrNull()
            add(Slide(SlideKind.ONLY_HERE, 12_000, songAt = { shown.song(song) ?: shown.song(top.songId) }))
        } else r.whereFrom?.let { f ->
            // Nothing only InterTune does: where the songs came from takes its place (22 Nov 2026), the first source's song under it.
            add(Slide(SlideKind.WHERE_FROM, 11_000, songAt = { shown.song(f.rows.first().songId) ?: shown.song(top.songId) }))
        }
        // The one skipped most, its own song under it, before the countdown to number one.
        r.mostSkipped?.let { k -> add(Slide(SlideKind.SKIPPED, 10_000, songAt = { shown.song(k.songId) })) }
        if (r.topSongs.size >= 2) {
            // Step 0 is the last of the top songs, the last step number two.
            add(Slide(SlideKind.COUNTDOWN, 6_500, steps = r.topSongs.size - 1, songAt = { step -> shown.song(r.topSongs[r.topSongs.lastIndex - step].songId) }))
        }
        add(Slide(SlideKind.NUMBER_ONE, 12_000, songAt = { shown.song(top.songId) }))
        add(Slide(SlideKind.CARD, 12_000, songAt = { shown.song(top.songId) }))
        // The note to next year's you, under number two as in the mock (number one where there is none).
        add(Slide(SlideKind.NOTE_WRITE, 12_000, songAt = { shown.song((r.topSongs.getOrNull(1) ?: top).songId) }, coverAt = { null }))
        add(Slide(SlideKind.THANKS, 0, songAt = { shown.song(r.firstSong.songId) }, coverAt = { null }))
    }
}

/**
 * How long a note takes to read, beyond the time its paper takes to land: about 55 ms a letter, a
 * slow reader's pace, never less than 3 s. The slide then rests 7 s more, as every slide does.
 */
fun noteReadMs(text: String): Long = maxOf(3_000L, text.length * 55L)

/** The story's pages as its sound goes through them (RecapSound): each slide, each of the countdown's steps. */
fun storyPages(slides: List<Slide>): List<RecapPage> =
    slides.flatMap { slide -> (0 until slide.steps).map { step -> RecapPage(slide.song(step), slide.piece(step)) } }

/** Which of [storyPages] the story's place is: slide [at] (1 for the first), step [step] of it. */
fun pageOf(slides: List<Slide>, at: Int, step: Int): Int =
    slides.take((at - 1).coerceAtLeast(0)).sumOf { it.steps } + step
