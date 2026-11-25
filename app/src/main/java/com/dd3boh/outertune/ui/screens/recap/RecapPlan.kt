/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.ui.screens.recap

import com.dd3boh.outertune.constants.Unreleased
import com.dd3boh.outertune.recap.RecapPage
import com.dd3boh.outertune.recap.RecapPiece
import com.dd3boh.outertune.recap.RecapShown
import com.dd3boh.outertune.recap.RecapSongShown

/**
 * The slides, in the mock's order. A slide the year has nothing for is left out. NOTE_READ, last
 * year's notes to this year's you, comes only when some were sealed; NOTE_WRITE, the notes to next
 * year's you, always comes just before the thank-you page (his adds of 22 Nov 2026, RecapNote).
 * SEASONS, NIGHT, WEEK and CAME_BACK are the four of 24 Nov 2026 (RecapMoments, behind
 * Unreleased.RECAP_MOMENTS).
 */
enum class SlideKind { SINCE, NOTE_READ, MINUTES, SEASONS, HOUR, NIGHT, STREAK, WEEK, ARTISTS, ONLY_HERE, WHERE_FROM, SKIPPED, CAME_BACK, COUNTDOWN, NUMBER_ONE, CARD, NOTE_WRITE, THANKS }

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

/**
 * The year's slides. The four of 24 Nov 2026 (RecapMoments) go where the story already turns to what
 * they tell, so that it reads like a year, from the whole of it to its days and then to its songs: the
 * seasons after the minutes (the year, then its parts), the 2 AM songs after the listening hour (the
 * day's busiest hour, then its two ends), the week you couldn't stop after the longest streak (two runs
 * of days), and the one you came back to after the one you kept skipping, just before the countdown
 * (two songs that would not let go). The slides that were there keep their order.
 */
fun planSlides(shown: RecapShown): List<Slide> {
    val r = shown.recap
    val top = r.topSongs.first()
    val moments = Unreleased.RECAP_MOMENTS
    return buildList {
        add(Slide(SlideKind.SINCE, 9_000, songAt = { shown.song(r.firstSong.songId) }))
        // Last year's notes, under the first song still: as long as all their words take to read.
        if (shown.pastNotes.isNotEmpty()) {
            add(Slide(SlideKind.NOTE_READ, 10_000 + noteReadMs(shown.pastNotes.joinToString("") { it.text }), songAt = { shown.song(r.firstSong.songId) }, coverAt = { null }))
        }
        add(Slide(SlideKind.MINUTES, 10_000, songAt = { shown.song(r.mostTime?.songId ?: top.songId) }))
        // One song a season, the busiest season's under it.
        if (moments) r.seasons?.let { s -> add(Slide(SlideKind.SEASONS, 11_000, songAt = { shown.song(s.busiest.songId) })) }
        add(Slide(SlideKind.HOUR, 10_000, songAt = { shown.song(r.peakSong.songId) }))
        // After midnight and first thing in the morning, the night's song under it (the morning's where there is no night).
        if (moments) r.nightAndMorning?.let { n -> add(Slide(SlideKind.NIGHT, 11_000, songAt = { shown.song((n.night ?: n.morning)?.songId) })) }
        r.streak?.let { s -> add(Slide(SlideKind.STREAK, 10_000, songAt = { shown.song(s.songId) })) }
        if (moments) r.onRepeat?.let { w -> add(Slide(SlideKind.WEEK, 10_000, songAt = { shown.song(w.songId) })) }
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
        if (moments) r.cameBack?.let { b -> add(Slide(SlideKind.CAME_BACK, 11_000, songAt = { shown.song(b.songId) })) }
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
