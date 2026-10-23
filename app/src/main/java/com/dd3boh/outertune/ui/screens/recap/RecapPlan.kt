/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.ui.screens.recap

import com.dd3boh.outertune.recap.RecapPiece
import com.dd3boh.outertune.recap.RecapShown
import com.dd3boh.outertune.recap.RecapSongShown

/** The slides, in the mock's order. A slide the year has nothing for is left out. */
enum class SlideKind { SINCE, MINUTES, HOUR, STREAK, ARTISTS, ONLY_HERE, COUNTDOWN, NUMBER_ONE, CARD, THANKS }

/**
 * One slide: how long it stays up for each of its [steps] (0, the last one, stays until left),
 * the song under it at each step, and the cover its colours come from.
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
        }
        if (r.topSongs.size >= 2) {
            // Step 0 is the last of the top songs, the last step number two.
            add(Slide(SlideKind.COUNTDOWN, 6_500, steps = r.topSongs.size - 1, songAt = { step -> shown.song(r.topSongs[r.topSongs.lastIndex - step].songId) }))
        }
        add(Slide(SlideKind.NUMBER_ONE, 12_000, songAt = { shown.song(top.songId) }))
        add(Slide(SlideKind.CARD, 12_000, songAt = { shown.song(top.songId) }))
        add(Slide(SlideKind.THANKS, 0, songAt = { shown.song(r.firstSong.songId) }, coverAt = { null }))
    }
}
