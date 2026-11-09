/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.cast

/**
 * The app's own sound on raw audio the phone makes for a receiver: its equalizer and its loudness
 * normalisation, as the phone's player puts them on a song it plays itself (playback/CastSound in
 * the app). Only raw audio can have them. A receiver handed the song as it is, or as AAC, decodes it
 * itself, and nothing on the phone reaches what it plays.
 *
 * Put on once, where the last samples are made: by the AirPlay link on each packet as it sends it,
 * so a band moved is heard as soon as AirPlay's own delay allows, and by the cast server on the raw
 * audio a renderer fetches (PcmCastSong).
 */
interface PcmSound {
    /** A stage for one stream of samples at [rate] Hz in [channels] channels; each keeps its own filters' memory. */
    fun stage(rate: Int, channels: Int): PcmStage

    /** Looks up what song [mediaId] needs from the app (its loudness). Blocking: never on a thread that sends. */
    fun prepare(mediaId: String)
}

/** One stream's stage, used from one thread at a time. */
interface PcmStage {
    /**
     * [frames] interleaved 16-bit frames of song [mediaId], from [samples] at [from] into [out] at
     * [to], which may be the very same place. Never blocks and never allocates.
     */
    fun shape(mediaId: String, samples: ShortArray, from: Int, out: ShortArray, to: Int, frames: Int)

    /** What comes next does not carry on from what came before: a seek, a pause, another song. */
    fun reset()
}

/**
 * How the song playing went to the receiver, for Signal path: decoded on the phone into raw audio
 * at [rate] Hz ([raw]), which the app's sound is put on, or as the app has it or as AAC
 * ([mimeType]), which the receiver decodes itself, out of the phone's reach.
 */
data class SentForm(val mediaId: String, val mimeType: String, val raw: Boolean, val rate: Int? = null)
