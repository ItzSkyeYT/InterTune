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
        var best = -1
        var bestScore = -1.0
        var bestSum = 0.0
        var quietest = Double.MAX_VALUE
        for (i in first..last) {
            var sum = 0.0
            for (k in i until i + window) sum += envelope[k]
            // Of runs as loud, the one that begins on a loud place: where the loud part starts, not before it.
            val score = sum + envelope[i] * 0.5
            if (score > bestScore) {
                bestScore = score
                bestSum = sum
                best = i
            }
            quietest = minOf(quietest, sum)
        }
        if (best < 0 || quietest <= 0.0 && bestSum <= 0.0) return null
        if (quietest > 0.0 && bestSum / quietest < MIN_CONTRAST) return null
        return best * stepMs
    }

    /**
     * The loudness of the file at [path] in each [STEP_MS] from its start (the root mean square of what
     * was decoded in it), and its length; null where it cannot be read. The file is decoded straight
     * through, with no seeks: the first walk on the Pixel 5 (22 Nov 2026) read it a moment every two
     * seconds after a seek each, and every start came 2 to 8 seconds late, Android's MP3 seeks landing
     * elsewhere than they say. A four-minute song decodes in about a second. Off the main thread.
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
            val blocks = (durationUs / 1000 / STEP_MS).toInt() + 1
            val sums = DoubleArray(blocks)
            val counts = LongArray(blocks)
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
                    // A buffer is a few tens of milliseconds: all of it counts in the block it begins in.
                    val block = (info.presentationTimeUs / 1000 / STEP_MS).toInt().coerceIn(0, blocks - 1)
                    val float = codec.outputFormat.let { it.containsKey(MediaFormat.KEY_PCM_ENCODING) && it.getInteger(MediaFormat.KEY_PCM_ENCODING) == AudioFormat.ENCODING_PCM_FLOAT }
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
                }
                codec.releaseOutputBuffer(o, false)
                if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break
            }
            val out = FloatArray(blocks) { if (counts[it] > 0) sqrt(sums[it] / counts[it]).toFloat() else 0f }
            return out to durationUs / 1000
        } catch (e: Exception) {
            return null
        } finally {
            runCatching { codec?.stop() }
            runCatching { codec?.release() }
            runCatching { extractor.release() }
        }
    }
}
