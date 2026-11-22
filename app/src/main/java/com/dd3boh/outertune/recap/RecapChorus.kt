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
 * The file is not decoded whole: a short stretch every [STEP_MS] ([LISTEN_MS] of it), about a second
 * for a four-minute song; [startMs] then picks the loudest run as long as the slide's piece, away from
 * the opening and the fade at the end. Plain sums over an envelope, tested on the JVM; the reading,
 * [envelope], needs the phone's decoders.
 */
object RecapChorus {
    /** How far apart the loudness is read, and how much is heard at each place. */
    const val STEP_MS = 2_000L
    const val LISTEN_MS = 400L

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
     * The loudness of the file at [path] every [STEP_MS] from its start (root mean square of [LISTEN_MS]
     * decoded at each place), and its length; null where it cannot be read. Off the main thread.
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
            val points = (durationUs / 1000 / STEP_MS).toInt()
            val out = FloatArray(points)
            val info = MediaCodec.BufferInfo()
            for (p in 0 until points) {
                val atUs = p * STEP_MS * 1000
                extractor.seekTo(atUs, MediaExtractor.SEEK_TO_CLOSEST_SYNC)
                codec.flush()
                var sum = 0.0
                var count = 0L
                var inputDone = false
                var guard = 0
                while (guard++ < 400) {
                    if (!inputDone) {
                        val i = codec.dequeueInputBuffer(5_000)
                        if (i >= 0) {
                            val size = extractor.readSampleData(codec.getInputBuffer(i)!!, 0)
                            if (size < 0 || extractor.sampleTime > atUs + (LISTEN_MS + 200) * 1000) {
                                codec.queueInputBuffer(i, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                                inputDone = true
                            } else {
                                codec.queueInputBuffer(i, 0, size, extractor.sampleTime, 0)
                                extractor.advance()
                            }
                        }
                    }
                    val o = codec.dequeueOutputBuffer(info, 5_000)
                    if (o >= 0) {
                        val float = codec.outputFormat.let { it.containsKey(MediaFormat.KEY_PCM_ENCODING) && it.getInteger(MediaFormat.KEY_PCM_ENCODING) == AudioFormat.ENCODING_PCM_FLOAT }
                        if (info.size > 0 && info.presentationTimeUs >= atUs) {
                            val buf = codec.getOutputBuffer(o)!!.order(ByteOrder.nativeOrder())
                            buf.position(info.offset).limit(info.offset + info.size)
                            if (float) {
                                val f = buf.asFloatBuffer()
                                while (f.hasRemaining()) { val v = f.get().toDouble(); sum += v * v; count++ }
                            } else {
                                val s = buf.asShortBuffer()
                                while (s.hasRemaining()) { val v = s.get() / 32768.0; sum += v * v; count++ }
                            }
                        }
                        codec.releaseOutputBuffer(o, false)
                        val heardUs = info.presentationTimeUs - atUs
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0 || heardUs >= LISTEN_MS * 1000) break
                    } else if (inputDone && o == MediaCodec.INFO_TRY_AGAIN_LATER) {
                        break
                    }
                }
                out[p] = if (count > 0) sqrt(sum / count).toFloat() else 0f
            }
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
