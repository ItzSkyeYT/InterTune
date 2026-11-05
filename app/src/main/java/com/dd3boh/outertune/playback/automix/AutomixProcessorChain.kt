/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.playback.automix

import androidx.media3.common.PlaybackParameters
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.AudioProcessorChain

/**
 * The chain the app already has, with AutoMix's stage after all of it, Sonic included.
 *
 * DefaultAudioSink puts a chain's processors after its own channel mapping and trimming, so the
 * stage is the last of the pipeline, the one place a song's end can be held across the drain and
 * the flush the sink makes at every change of song. Speed, skip silence and the position are the
 * wrapped chain's business and go straight to it: the stage gives out one frame for every frame it
 * takes, but while it holds an end, and then it gives out the very frames it took.
 */
class AutomixProcessorChain(
    private val chain: AudioProcessorChain,
    stage: AutomixAudioProcessor,
) : AudioProcessorChain {

    private val processors: Array<AudioProcessor> = chain.audioProcessors + stage

    override fun getAudioProcessors(): Array<AudioProcessor> = processors

    override fun applyPlaybackParameters(playbackParameters: PlaybackParameters): PlaybackParameters =
        chain.applyPlaybackParameters(playbackParameters)

    override fun applySkipSilenceEnabled(skipSilenceEnabled: Boolean): Boolean =
        chain.applySkipSilenceEnabled(skipSilenceEnabled)

    override fun getMediaDuration(playoutDuration: Long): Long = chain.getMediaDuration(playoutDuration)

    override fun getSkippedOutputFrameCount(): Long = chain.skippedOutputFrameCount
}
