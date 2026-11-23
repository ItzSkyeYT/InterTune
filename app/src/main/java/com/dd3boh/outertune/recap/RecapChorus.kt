/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.recap

import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import java.nio.ByteOrder
import kotlin.math.sqrt

/**
 * Where a file on the phone is loudest, for the Recap to start it there, as a song from YouTube starts
 * at its heat map's peak (RecapHeat) and a file used to start a third of the way in. His pick for
 * listeners of their own files, 22 Nov 2026 ("Chorus for local songs"): the loudest stretch of a song
 * is most often its chorus.
 *
 * The file is decoded once, straight through, its loudness kept for each [STEP_MS] ([envelope]);
 * [startMs] then picks the loudest run as long as the slide's piece, away from the opening and the
 * fade at the end. Plain sums over an envelope, tested on the JVM; the reading needs the phone's
 * decoders.
 */
object RecapChorus {
    /** How long each stretch of the loudness is. */
    const val STEP_MS = 2_000L

    /** The loudest run starts after this part of the song and ends before the last of it. */
    const val SKIP_START = 0.08
    const val SKIP_END = 0.06

    /** A song whose loudest run is not this much louder than its quietest is flat: a third of the way in then. */
    const val MIN_CONTRAST = 1.25f

    /** Runs this close to the loudest count as loud as it: the earliest of them is taken. */
    const val NEAR = 0.03

    /**
     * Where the loudest run of [pieceMs] begins, from [envelope] (one loudness every [stepMs] from the
     * start of a song of [durationMs]): of runs as loud, the one beginning on a loud place, then the earlier;
     * null where the song is too short, too little was read or it is flat.
     */
    fun startMs(envelope: FloatArray, stepMs: Long, pieceMs: Long, durationMs: Long): Long? {
        if (envelope.size < 4 || stepMs <= 0 || pieceMs <= 0 || durationMs <= pieceMs) return null
        val window = ((pieceMs + stepMs - 1) / stepMs).toInt().coerceAtLeast(1)
        val first = (durationMs * SKIP_START / stepMs).toInt()
        val last = minOf(envelope.size - window, ((durationMs * (1 - SKIP_END) - pieceMs) / stepMs).toInt())
        if (last < first) return null
        val sums = DoubleArray(last - first + 1)
        val scores = DoubleArray(last - first + 1)
        for (i in first..last) {
            var sum = 0.0
            for (k in i until i + window) sum += envelope[k]
            sums[i - first] = sum
            // Of runs as loud, the one that begins on a loud place: where the loud part starts, not before it.
            scores[i - first] = sum + envelope[i] * 0.5
        }
        val loudest = sums.max()
        val quietest = sums.min()
        if (loudest <= 0.0) return null
        if (quietest > 0.0 && loudest / quietest < MIN_CONTRAST) return null
        // Runs within NEAR of the best are as loud to the ear: the earliest of them, the loud part's start. The
        // strictly loudest landed anywhere in it, by a third decimal (22 Nov 2026: 2 to 4 s late on the Pixel).
        val top = scores.max()
        return (first + scores.indexOfFirst { it >= top * (1 - NEAR) }) * stepMs
    }

    /** The block, of [stepMs] each, that a buffer starting after [frames] samples (per channel) at [sampleRate] falls in. */
    internal fun blockAt(frames: Long, sampleRate: Int, stepMs: Long): Int = (frames * 1000 / sampleRate / stepMs).toInt()

    /**
     * The loudness of the file at [path] in each [STEP_MS] from its start (the root mean square of what
     * was decoded in it), and its length; null where it cannot be read. The file is decoded straight
     * through, with no seeks: the first walk on the Pixel 5 (22 Nov 2026) read it a moment every two
     * seconds after a seek each, and every start came 2 to 8 seconds late, Android's MP3 seeks landing
     * elsewhere than they say. A four-minute song decodes in about a second. Off the main thread.
     *
     * Time is counted by the decoded samples themselves ([blockAt]), not by the timestamps Android puts on them: for a
     * variable-bitrate MP3 without a header those are guessed from its first frames, and a 3-minute test file read as
     * 48 s long with no loud stretch at all (23 Nov 2026, experiments/features/recap/chorus-1123). Its length comes
     * from the samples too.
     */
    fun envelope(path: String): Pair<FloatArray, Long>? {
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        try {
            extractor.setDataSource(path)
            val track = (0 until extractor.trackCount).firstOrNull {
                extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
            } ?: return null
            val format = extractor.getTrackFormat(track)
            val mime = format.getString(MediaFormat.KEY_MIME) ?: return null
            val durationUs = if (format.containsKey(MediaFormat.KEY_DURATION)) format.getLong(MediaFormat.KEY_DURATION) else return null
            extractor.selectTrack(track)
            codec = MediaCodec.createDecoderByType(mime).apply { configure(format, null, null, 0); start() }
            // Sized from the container's guess and grown as the samples say: the guess can be far short.
            var sums = DoubleArray((durationUs / 1000 / STEP_MS).toInt() + 1)
            var counts = LongArray(sums.size)
            var used = 0
            var frames = 0L
            val info = MediaCodec.BufferInfo()
            var inputDone = false
            var idle = 0
            while (idle < 200) {
                if (!inputDone) {
                    val i = codec.dequeueInputBuffer(10_000)
                    if (i >= 0) {
                        val size = extractor.readSampleData(codec.getInputBuffer(i)!!, 0)
                        if (size < 0) {
                            codec.queueInputBuffer(i, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            codec.queueInputBuffer(i, 0, size, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                    }
                }
                val o = codec.dequeueOutputBuffer(info, 10_000)
                if (o < 0) {
                    if (inputDone) idle++
                    continue
                }
                idle = 0
                if (info.size > 0) {
                    // A buffer is a few tens of milliseconds: all of it counts in the block it begins in, by the samples
                    // decoded before it; the timestamp only where the decoder does not say its rate.
                    val out = codec.outputFormat
                    val rate = if (out.containsKey(MediaFormat.KEY_SAMPLE_RATE)) out.getInteger(MediaFormat.KEY_SAMPLE_RATE) else 0
                    val channels = if (out.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) out.getInteger(MediaFormat.KEY_CHANNEL_COUNT).coerceAtLeast(1) else 1
                    val block = (if (rate > 0) blockAt(frames, rate, STEP_MS) else (info.presentationTimeUs / 1000 / STEP_MS).toInt()).coerceAtLeast(0)
                    if (block >= sums.size) {
                        val size = maxOf(block + 1, sums.size * 2)
                        sums = sums.copyOf(size)
                        counts = counts.copyOf(size)
                    }
                    val float = out.containsKey(MediaFormat.KEY_PCM_ENCODING) && out.getInteger(MediaFormat.KEY_PCM_ENCODING) == AudioFormat.ENCODING_PCM_FLOAT
                    val buf = codec.getOutputBuffer(o)!!.order(ByteOrder.nativeOrder())
                    buf.position(info.offset).limit(info.offset + info.size)
                    var sum = 0.0
                    var count = 0L
                    if (float) {
                        val f = buf.asFloatBuffer()
                        while (f.hasRemaining()) { val v = f.get().toDouble(); sum += v * v; count++ }
                    } else {
                        val sh = buf.asShortBuffer()
                        while (sh.hasRemaining()) { val v = sh.get() / 32768.0; sum += v * v; count++ }
                    }
                    sums[block] += sum
                    counts[block] += count
                    used = maxOf(used, block + 1)
                    if (rate > 0) frames += count / channels
                }
                codec.releaseOutputBuffer(o, false)
                if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break
            }
            val rate = codec.outputFormat.let { if (it.containsKey(MediaFormat.KEY_SAMPLE_RATE)) it.getInteger(MediaFormat.KEY_SAMPLE_RATE) else 0 }
            val blocks = maxOf(used, 1)
            val loudness = FloatArray(blocks) { if (counts[it] > 0) sqrt(sums[it] / counts[it]).toFloat() else 0f }
            return loudness to (if (rate > 0 && frames > 0) frames * 1000 / rate else durationUs / 1000)
        } catch (e: Exception) {
            return null
        } finally {
            runCatching { codec?.stop() }
            runCatching { codec?.release() }
            runCatching { extractor.release() }
        }
    }
}
