/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.models

/**
 * What one tap on a song's Dislike, or on Don't play this song, does (Unreleased.DISLIKE, #171): in
 * plain Kotlin, so the rules are tested on the JVM.
 *
 * Without the flag, and for a local file, which YouTube cannot hear, it is Don't play this song as it
 * always was: the song kept from playing, or let play again. With it, a song from YouTube Music is
 * disliked: kept from playing the same way, its like taken back here (YouTube Music's thumbs down takes
 * the place of a like, and a song both liked and kept from playing says two things), and the thumbs down
 * sent when the account may be changed (signed in, sync on read and write). Lifting it sends removelike,
 * which takes a thumbs down away, but never for a song liked here: there removelike would take the like.
 *
 * The like here goes only where nothing gives it back: once YouTube Music has the thumbs down, which
 * took the like there; at once signed out; never with sync on read only, where YouTube Music keeps the
 * like and the next sync of liked songs would like the song here again.
 */
object DislikePlan {
    enum class Tell { DISLIKE, REMOVE, NOTHING }

    /** When the song's like is taken back in the app, without a removelike: the thumbs down replaces it there. */
    enum class Unlike {
        /** Not at all: not liked, or sync on read only, which would like it again from YouTube Music. */
        NO,

        /** At once: signed out, nothing syncs the like back. */
        NOW,

        /** Once YouTube Music has taken the thumbs down. Kept when it has not, as YouTube Music keeps it. */
        AFTER_DISLIKE,
    }

    /**
     * @param keep true to keep the song from playing, false to let it play again.
     * @param unlike when the song's like is taken back here.
     * @param tell what goes to YouTube Music.
     */
    data class Plan(val keep: Boolean, val unlike: Unlike, val tell: Tell)

    /** Whether the song's menus say Dislike for it, rather than Don't play this song. */
    fun shown(flag: Boolean, local: Boolean): Boolean = flag && !local

    /**
     * @param held the song is kept from playing already, so the tap lifts it.
     * @param liked the song is liked in the app.
     * @param mayPush the account may be changed (mayPushToYouTube).
     * @param signedIn someone is signed in, so a sync of liked songs reads YouTube Music's.
     */
    fun of(flag: Boolean, held: Boolean, local: Boolean, liked: Boolean, mayPush: Boolean, signedIn: Boolean): Plan {
        val dislike = shown(flag, local)
        if (held) {
            val tell = if (dislike && mayPush && !liked) Tell.REMOVE else Tell.NOTHING
            return Plan(keep = false, unlike = Unlike.NO, tell = tell)
        }
        val unlike = when {
            !dislike || !liked -> Unlike.NO
            mayPush -> Unlike.AFTER_DISLIKE
            signedIn -> Unlike.NO
            else -> Unlike.NOW
        }
        return Plan(keep = true, unlike = unlike, tell = if (dislike && mayPush) Tell.DISLIKE else Tell.NOTHING)
    }
}
