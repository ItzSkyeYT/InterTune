/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.parts

/**
 * What one run of the parts bench does (PartsBench in the debug app), from the words after `parts`
 * in the debug hook, each `key=value`:
 *
 * - `model`: the model file, a name in the app's files/parts or a full path (scnet.ort);
 * - `in`: the song, a 44.1 kHz WAV, a name in the app's files/parts or a full path (in.wav);
 * - `segments`: how many 11 s segments to separate (10), from the start of the WAV;
 * - `overlap`: segments per sample, as stemlib counts it (1.33);
 * - `threads`: the runtime's threads in each session (1);
 * - `sessions`: sessions side by side, each on its own thread with segments of its own (1);
 * - `lowmem`: 1 for no arena and no planned buffers (0);
 * - `spin`: 1 to let idle runtime threads spin (0);
 * - `nice`: the workers' Linux priority, 0 as the app's threads, 9 as AutoMix's worker (0);
 * - `keep`: the segment whose parts are written out (1, the first one with no padding in it).
 */
data class BenchArgs(
    val model: String = "scnet.ort",
    val input: String = "in.wav",
    val segments: Int = 10,
    val overlap: Double = 1.33,
    val threads: Int = 1,
    val sessions: Int = 1,
    val lowMemory: Boolean = false,
    val spinning: Boolean = false,
    val nice: Int = 0,
    val keep: Int = 1,
) {
    companion object {
        fun parse(words: List<String>): BenchArgs {
            var args = BenchArgs()
            for (word in words) {
                val key = word.substringBefore('=', "")
                val value = word.substringAfter('=', "")
                require(key.isNotEmpty() && value.isNotEmpty()) { "not key=value: $word" }
                fun int() = requireNotNull(value.toIntOrNull()) { "$key wants a whole number: $value" }
                fun flag() = when (value) {
                    "1", "true", "on" -> true
                    "0", "false", "off" -> false
                    else -> throw IllegalArgumentException("$key wants 1 or 0: $value")
                }
                args = when (key) {
                    "model" -> args.copy(model = value)
                    "in" -> args.copy(input = value)
                    "segments" -> args.copy(segments = int())
                    "overlap" -> args.copy(overlap = requireNotNull(value.toDoubleOrNull()) { "overlap wants a number: $value" })
                    "threads" -> args.copy(threads = int())
                    "sessions" -> args.copy(sessions = int())
                    "lowmem" -> args.copy(lowMemory = flag())
                    "spin" -> args.copy(spinning = flag())
                    "nice" -> args.copy(nice = int())
                    "keep" -> args.copy(keep = int())
                    else -> throw IllegalArgumentException("unknown: $key")
                }
            }
            require(args.segments >= 1) { "segments must be 1 or more" }
            require(args.overlap > 1.0) { "overlap must be over 1" }
            require(args.threads >= 1 && args.sessions >= 1) { "threads and sessions must be 1 or more" }
            require(args.nice in -20..19) { "nice runs from -20 to 19" }
            return args
        }
    }
}
