/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.recognition

/**
 * What listening to the room produced.
 *
 * [NoMatch] and [Failed] are kept apart on purpose. "That is not a song I know" and "the request
 * never arrived" call for different words on screen, and collapsing them into one failure makes
 * every network problem look like an unknown song.
 */
sealed interface RecognitionResult {

    data class Match(
        val title: String,
        val artist: String,
        val artworkUrl: String? = null,
        /** Shazam hands this over when it has one, and it saves a search. */
        val youtubeId: String? = null,
        val isrc: String? = null,
        /** The Shazam track id, only useful for telling two matches apart in a list. */
        val key: String? = null,
    ) : RecognitionResult {
        val subtitle: String get() = artist
    }

    data object NoMatch : RecognitionResult

    data class Failed(val reason: String) : RecognitionResult
}

/** How long to listen for. Ten seconds is what the fingerprinter was proven against. */
const val RECOGNITION_SECONDS = 10
