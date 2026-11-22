/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.recap

/**
 * The piece of a song the mock played under a slide: a third of the way in, as long as the slide
 * stays up and at least six seconds, all of a song shorter than that (setStrip). With sound, its
 * start is where a song with no Most replayed heat map plays from (RecapHeat.startMs), and its
 * length how much of the song a heat map's peak must leave after it. It no longer cuts the song:
 * a song plays on until the next slide's takes over.
 */
data class RecapPiece(val fromMs: Long, val lengthMs: Long) {
    companion object {
        const val SHORTEST_MS = 6_000L

        /** For a song [durationS] long, under a slide up for [slideMs] (0 for one that stays). */
        fun of(durationS: Int, slideMs: Long): RecapPiece {
            val duration = (if (durationS > 0) durationS else 180) * 1000L
            val wanted = maxOf(SHORTEST_MS, if (slideMs > 0) slideMs else 12_000L)
            val length = minOf(duration, wanted)
            val from = minOf(duration - length, duration / 3)
            return RecapPiece(from, length)
        }
    }
}

/** One page of the story as its sound sees it: a slide, or one step of the countdown. */
data class RecapPage(val song: RecapSongShown?, val piece: RecapPiece?)

/**
 * What plays under the Recap's slides, as decided on 25 Oct 2026:
 *  - Sound on. The listener's own music pauses when the story is opened (Open Recap) and carries
 *    on when the Recap is closed; music that was paused, or nothing at all, stays as it was.
 *  - Each page's song plays and keeps playing, not a short clip, until the next page's song takes
 *    over ("exactly like spotify"). It starts at the highest point of YouTube's Most replayed heat
 *    map after the first 5% of the song (RecapHeat), and where its piece would have started
 *    ([RecapPiece.fromMs]) when it has none: under about 50,000 views, a local file.
 *  - The last page's song plays on after the Recap is closed, slid into the listener's queue as
 *    the song playing now; when it ends, their own queue carries on, the song it went in front of
 *    from where it was ("only the last song gets slid into the queue"). Closed before the last
 *    page, their music just carries on.
 *
 * Nothing of it plays outside the Recap's own screen: away from the app, held, or paused, it
 * waits, and it is gone once closed. [RecapSoundPlan] decides each of these; RecapPlayer plays
 * them.
 */
interface RecapSound {
    /** Open Recap: the story starts on [pages], the song under each. */
    fun open(pages: List<RecapPage>)

    /** Page [page] of the story came up. */
    fun show(page: Int)

    /** Held, paused or away: the sound waits where it is. */
    fun pause()

    /** And carries on. */
    fun resume()

    /** The speaker button. */
    fun mute(muted: Boolean)

    /** The cross, Back or Done. */
    fun close()

    /**
     * Told when the sound stopped of itself: headphones taken out, another app's sound taking
     * over. The story pauses with it, as the pause button would.
     */
    var onInterrupted: (() -> Unit)?

    /** Nothing plays, with nothing to play it on. */
    object Silent : RecapSound {
        override fun open(pages: List<RecapPage>) = Unit
        override fun show(page: Int) = Unit
        override fun pause() = Unit
        override fun resume() = Unit
        override fun mute(muted: Boolean) = Unit
        override fun close() = Unit
        override var onInterrupted: (() -> Unit)? = null
    }
}

/** What the listener's own player was doing when the Recap's sound began. */
enum class TheirMusic {
    /** A song playing, or about to. */
    PLAYING,

    /** A song loaded and paused. */
    PAUSED,

    /** Nothing loaded at all. */
    NONE,
}

/** The Recap's song as it sounds when the Recap is closed: [songId], [positionMs] into it. */
data class Sounding(val songId: String, val positionMs: Long)

/**
 * The decisions of [RecapSound], apart from any player so that they can be tested: when the
 * listener's music pauses and carries on, when a page's song takes over, and what happens to the
 * last one. Each call returns the steps to take, in order. Main thread.
 */
class RecapSoundPlan {
    sealed interface Step {
        /** The listener's own music pauses. */
        data object PauseTheirs : Step

        /** The listener's own music carries on where it was. */
        data object ResumeTheirs : Step

        /** [songId] takes over the Recap's sound, from its start point, for page [page]. */
        data class TakeOver(val page: Int, val songId: String) : Step

        /** The Recap's sound stops and lets go of everything it held. */
        data object Stop : Step

        /**
         * [songId] goes into the listener's queue as the song playing now, from [positionMs], in
         * front of the song that was current; their queue carries on after it.
         */
        data class SlideIn(val songId: String, val positionMs: Long) : Step
    }

    /** What the listener's music was doing when the story opened; null before it opened. */
    var theirs: TheirMusic? = null
        private set

    private var pages = 0

    /** The page the story is on, -1 before the first. */
    var page = -1
        private set

    /** The song the Recap is playing, null when none is or it ended. */
    var playing: String? = null
        private set

    private var muted = false
    private var closed = false

    val opened: Boolean get() = theirs != null && !closed

    /** Open Recap, with the listener's music doing [theirs], on a story of [pages] pages. */
    fun open(theirs: TheirMusic, pages: Int): List<Step> {
        if (closed || this.theirs != null) return emptyList()
        this.theirs = theirs
        this.pages = pages
        return if (theirs == TheirMusic.PLAYING) listOf(Step.PauseTheirs) else emptyList()
    }

    /**
     * Page [page] came up, [songId] under it (null for none). The song playing carries on when it
     * is this page's too and the story only moved on or back by one (number one, then the year's
     * card), or not at all; a jump, Watch again from the last page to the first, starts it from its
     * start again.
     * A page without a song leaves the one playing as it is.
     */
    fun show(page: Int, songId: String?): List<Step> {
        if (!opened) return emptyList()
        val before = this.page
        this.page = page
        if (songId == null) return emptyList()
        // The same page again too: the window drawn again after the phone was turned.
        val neighbour = before >= 0 && kotlin.math.abs(page - before) <= 1
        if (songId == playing && neighbour) return emptyList()
        playing = songId
        return listOf(Step.TakeOver(page, songId))
    }

    /** The Recap's song played to its end: shown again, it starts again. */
    fun ended(songId: String) {
        if (playing == songId) playing = null
    }

    fun mute(muted: Boolean) {
        this.muted = muted
    }

    /**
     * The Recap closes, with [now] what its sound is doing (null when nothing sounds: paused,
     * held, failed or run out). On the last page, a song that sounds goes on in the listener's
     * queue; anywhere else, or muted, their music carries on if it was playing. Closed before the
     * story was opened, nothing was touched and nothing is.
     */
    fun close(now: Sounding?): List<Step> {
        if (closed) return emptyList()
        closed = true
        val theirs = theirs ?: return emptyList()
        val last = pages > 0 && page == pages - 1
        if (last && !muted && now != null && now.songId == playing) {
            return listOf(Step.Stop, Step.SlideIn(now.songId, now.positionMs))
        }
        return if (theirs == TheirMusic.PLAYING) listOf(Step.Stop, Step.ResumeTheirs) else listOf(Step.Stop)
    }
}
