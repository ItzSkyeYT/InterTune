/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.playback

import kotlin.math.roundToInt

/**
 * The equalizer's ready-made curves, and how a curve is kept: the ten gains in dB, joined by
 * commas, so a store from a version with other bands reads as flat rather than as nonsense.
 */
enum class EqualizerPreset(val gainsDb: List<Float>) {
    FLAT(List(10) { 0f }),
    MORE_BASS(listOf(6f, 5f, 4f, 2f, 0f, 0f, 0f, 0f, 0f, 0f)),
    MORE_TREBLE(listOf(0f, 0f, 0f, 0f, 0f, 0f, 2f, 4f, 5f, 6f)),
    VOCALS(listOf(-2f, -2f, -1f, 0f, 2f, 4f, 4f, 2f, 0f, -1f)),
    LOUDNESS(listOf(6f, 4f, 2f, 0f, -1f, -1f, 0f, 2f, 4f, 5f)),
    SMALL_SPEAKERS(listOf(-6f, -4f, -2f, 0f, 1f, 2f, 3f, 2f, 1f, 0f));

    companion object {
        /** The preset a curve is, or null when it is one of the listener's own. */
        fun of(gainsDb: List<Float>): EqualizerPreset? = entries.firstOrNull { it.gainsDb == gainsDb }

        fun format(gainsDb: List<Float>): String = gainsDb.joinToString(",") { ((it * 10).roundToInt() / 10f).toString() }

        /** A kept curve: flat when there is none or it does not have ten bands, each band kept within reach. */
        fun parse(kept: String?): List<Float> {
            val gains = kept.orEmpty().split(',').mapNotNull { it.trim().toFloatOrNull() }
            if (gains.size != EqualizerAudioProcessor.BANDS.size) return FLAT.gainsDb
            return gains.map { it.coerceIn(-EqualizerAudioProcessor.MAX_DB, EqualizerAudioProcessor.MAX_DB) }
        }
    }
}
