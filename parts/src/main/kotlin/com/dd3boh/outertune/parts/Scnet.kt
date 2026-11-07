/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.parts

/**
 * The shapes SCNet works in, as Music-Source-Separation-Training runs it and
 * experiments/features/automix/stems/tools/stemlib.py (split_scnet) after it: 44.1 kHz stereo,
 * 11 s segments, each turned into a spectrogram outside the network (4096-point frames every 1024
 * samples, centred, no window, scaled by 1/64) and four stems' spectrograms back.
 */
object Scnet {
    const val RATE = 44_100

    /** One segment, 11 s: the model's chunk. */
    const val SEGMENT = 485_100

    const val N_FFT = 4096
    const val HOP = 1024
    const val BINS = N_FFT / 2 + 1

    /**
     * Zeros after a segment, to a whole and odd number of hops: stemlib adds 1024 - 485100 % 1024
     * (276), then a hop more because 474 hops is even. ScnetStftTest works it out again.
     */
    const val TAIL = 1_300
    const val PADDED = SEGMENT + TAIL
    const val FRAMES = PADDED / HOP + 1

    /** The model's stems, in the order of its output. "other" is what the app calls melody. */
    val STEMS = listOf("drums", "bass", "other", "vocals")

    /** One spectrogram of both channels: rows left real, left imaginary, right real, right imaginary, each BINS by FRAMES. */
    const val SPEC_SIZE = 4 * BINS * FRAMES

    /** The model's output: four stems, each a [SPEC_SIZE] spectrogram in the input's layout. */
    const val OUTPUT_SIZE = 4 * SPEC_SIZE

    /** The names of the network's input and output in the ONNX file. */
    const val INPUT_NAME = "mix_spec"
    const val OUTPUT_NAME = "stems_spec"
}

/**
 * The network between the two transforms: a [Scnet.SPEC_SIZE] spectrogram in, four stems' out,
 * kept by the runner until they are read. ONNX Runtime's is [OrtSpecModel]; the tests use plain
 * ones that need no model.
 */
interface SpecModel {
    /** Runs the network on [spec]. */
    fun run(spec: FloatArray)

    /** Copies stem [stem]'s spectrogram from the last run into [into], in the input's layout. */
    fun readStem(stem: Int, into: FloatArray)
}
