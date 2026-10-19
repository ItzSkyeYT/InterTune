/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.playback

/**
 * The one line the queue sheet says about the last change the adaptive queue made, under the
 * songs on show: "3 new picks after your skip", "2 new picks like "Song"", "Took out 2 songs you
 * would likely skip". A queue that edits itself out of sight has to own up to it the moment
 * somebody looks, in words that say why.
 *
 * The newest pass that changed anything chooses it ([of]), and it stands until what it counted has
 * played or left the queue ([stands]). No Android in here, so it is tested.
 */
object AdaptiveNote {

    /** Which sentence, by the code the memory file and the log write. */
    enum class Kind(val code: String) {
        /** Picks like the song just liked. */
        LIKE("like"),
        /** Picks in place of what a skip turned away. */
        AFTER_SKIP("after-skip"),
        /** Picks in place of songs the listener has a habit of skipping, or has banned. */
        HABITS("habits"),
        /** Picks like the songs heard lately. */
        FINISH("finish"),
        /** Songs taken out with nothing good enough to put in their place. */
        DROPPED("dropped");

        companion object {
            fun ofCode(code: String?): Kind? = entries.firstOrNull { it.code == code }
        }
    }

    /**
     * [count] is how many songs the change put in, or took out for [Kind.DROPPED]; [title] the liked
     * song's, for [Kind.LIKE]; [picks] the songs it put in; [setAt] how many songs the run had
     * played when it was made.
     */
    data class Note(
        val kind: Kind,
        val count: Int,
        val title: String? = null,
        val picks: Set<String> = emptySet(),
        val setAt: Int = 0,
    )

    /**
     * Songs that play before a "Took out" note goes: the lock's length, by when what it described
     * has played and the songs on show are ones it never touched.
     */
    const val DROPPED_FOR = AdaptiveQueue.LOCKED

    /**
     * What a pass of [kind] that put in [replaced] and [inserts] and took out [dropped] songs says,
     * or null when it changed nothing, and the note before it stands. [liked] is the song whose
     * picks waited to go in, [likedTitle] its title without the version marks and the uploader's
     * name (SongVersions.plainTitle), so the note stays short; [played] the run's count.
     *
     * In this order: picks like the liked song, counting those alone; songs taken out, when more
     * went than came in (none came in included); picks after a skip; picks all in place of songs
     * the listener habitually skips or has banned; other picks, like the songs heard lately. The
     * count is every song the pass put in, or took out for [Kind.DROPPED].
     *
     * A pass that turned an artist down replaced three of their songs and took nine more out, and
     * the queue said "3 new picks after your skip" while nine songs left it: the larger change is
     * the one to own up to. The new picks still carry their marks.
     */
    fun of(
        kind: AdaptivePass.Kind,
        replaced: List<QueuePlanner.Replace>,
        inserts: List<QueuePlanner.Pick>,
        dropped: Int,
        liked: String?,
        likedTitle: String?,
        played: Int,
    ): Note? {
        val placed = replaced.map { it.pick } + inserts
        val ids = placed.mapTo(LinkedHashSet()) { it.id }
        val likes = if (liked == null) emptyList() else placed.filter { it.seed == liked }
        return when {
            likes.isNotEmpty() -> Note(Kind.LIKE, likes.size, likedTitle, likes.mapTo(LinkedHashSet()) { it.id }, played)
            dropped > placed.size -> Note(Kind.DROPPED, dropped, setAt = played)
            placed.isNotEmpty() && kind == AdaptivePass.Kind.SKIP -> Note(Kind.AFTER_SKIP, placed.size, picks = ids, setAt = played)
            placed.isNotEmpty() && inserts.isEmpty() && replaced.all {
                it.slot.unwanted == QueuePlanner.Unwanted.LASTING_DISLIKE || it.slot.unwanted == QueuePlanner.Unwanted.BANNED
            } -> Note(Kind.HABITS, placed.size, picks = ids, setAt = played)
            placed.isNotEmpty() -> Note(Kind.FINISH, placed.size, picks = ids, setAt = played)
            else -> null
        }
    }

    /**
     * Whether [note] still says something true: while a song it put in is still ahead and unplayed
     * ([picksAhead]), or, for songs taken out, until [DROPPED_FOR] more songs have played ([played],
     * the run's count).
     */
    fun stands(note: Note, picksAhead: Set<String>, played: Int): Boolean =
        if (note.kind == Kind.DROPPED) played < note.setAt + DROPPED_FOR else note.picks.any { it in picksAhead }
}
