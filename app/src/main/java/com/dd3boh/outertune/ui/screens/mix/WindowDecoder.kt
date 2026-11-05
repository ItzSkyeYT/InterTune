/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.ui.screens.mix

import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaFormat
import android.net.Uri
import androidx.annotation.OptIn
import androidx.core.net.toUri
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import androidx.media3.exoplayer.MediaExtractorCompat
import androidx.media3.exoplayer.mediacodec.MediaCodecUtil
import androidx.media3.extractor.DefaultExtractorsFactory
import java.io.BufferedOutputStream
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Decodes a stretch of a song through the player's own way to its bytes (MusicService.songSource:
 * its download, the cache, or the stream), with media3's extractors and the decoder the player
 * would choose, into a file of 16-bit PCM. Blocking: call it on Dispatchers.IO.
 *
 * The opening is read from the song's first frame with the encoder delay trimmed, as the sink
 * trims it; an end is sought to and the frames before the asked start are dropped by their time.
 */
@OptIn(UnstableApi::class)
class WindowDecoder(private val source: DataSource.Factory) {

    /**
     * Decodes [lengthMs] of [mediaId] from the time [startOf] gives for the song's length (ms),
     * into [out], and says what was written. [measure] sees every block as it is written.
     */
    fun decode(
        mediaId: String,
        knownDurationMs: Double,
        startOf: (durationMs: Double) -> Double,
        lengthMs: Double,
        out: File,
        cancelled: () -> Boolean,
        measure: (WindowInfo, ShortArray, Int) -> Unit = { _, _, _ -> },
    ): WindowInfo {
        val extractor = MediaExtractorCompat(DefaultExtractorsFactory(), KeyedSource(source, mediaId))
        var codec: MediaCodec? = null
        try {
            extractor.setDataSource(mediaId.toUri(), 0)
            val track = (0 until extractor.trackCount).firstOrNull {
                extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
            } ?: throw IOException("no audio track")
            extractor.selectTrack(track)
            val format = extractor.getTrackFormat(track)
            val mime = format.getString(MediaFormat.KEY_MIME)!!
            val durationMs = if (format.containsKey(MediaFormat.KEY_DURATION)) format.getLong(MediaFormat.KEY_DURATION) / 1000.0 else knownDurationMs
            if (durationMs <= 0) throw IOException("the song's length is not known")
            val startMs = startOf(durationMs).coerceIn(0.0, max(0.0, durationMs - 1000))
            val startUs = (startMs * 1000).toLong()
            // From the first frame the sink trims the encoder delay; anywhere else the times say where a frame is.
            var toTrim = if (startUs == 0L && format.containsKey(KEY_ENCODER_DELAY)) format.getInteger(KEY_ENCODER_DELAY).coerceAtLeast(0) else 0
            if (startUs > 0) extractor.seekTo(startUs, MediaExtractorCompat.SEEK_TO_PREVIOUS_SYNC)

            val decoder = runCatching { MediaCodecUtil.getDecoderInfo(mime, false, false)?.name }.getOrNull()
            codec = if (decoder != null) MediaCodec.createByCodecName(decoder) else MediaCodec.createDecoderByType(mime)
            val config = MediaFormat.createAudioFormat(mime, format.getInteger(MediaFormat.KEY_SAMPLE_RATE), format.getInteger(MediaFormat.KEY_CHANNEL_COUNT))
            for (i in 0..2) format.getByteBuffer("csd-$i")?.let { config.setByteBuffer("csd-$i", it) }
            if (format.containsKey(MediaFormat.KEY_MAX_INPUT_SIZE)) config.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, format.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE))
            codec.configure(config, null, null, 0)
            codec.start()

            var rate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            var channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            var float = false
            var info: WindowInfo? = null
            var wanted = (lengthMs / 1000 * rate).toLong()
            var taken = 0L
            var shorts = ShortArray(0)
            val bytes = ByteBuffer.allocate(64 * 1024).order(ByteOrder.LITTLE_ENDIAN)
            val buffered = BufferedOutputStream(out.outputStream(), 256 * 1024)
            buffered.use { sink ->
                val bufferInfo = MediaCodec.BufferInfo()
                var inputDone = false
                var outputDone = false
                val started = System.nanoTime()
                while (!outputDone && taken < wanted) {
                    if (cancelled()) throw IOException("cancelled")
                    if ((System.nanoTime() - started) / 1_000_000 > TIMEOUT_MS) throw IOException("timed out")
                    // Feed what the decoder takes without waiting, and wait for output only when
                    // nothing could be fed: one wait a packet made a 30 s opening take 15 s.
                    var fed = false
                    if (!inputDone) {
                        val i = codec.dequeueInputBuffer(0)
                        if (i >= 0) {
                            val buffer = codec.getInputBuffer(i)!!
                            val size = extractor.readSampleData(buffer, 0)
                            if (size < 0) {
                                codec.queueInputBuffer(i, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                                inputDone = true
                            } else {
                                codec.queueInputBuffer(i, 0, size, extractor.sampleTime, 0)
                                extractor.advance()
                            }
                            fed = true
                        }
                    }
                    val o = codec.dequeueOutputBuffer(bufferInfo, if (fed) 0 else WAIT_US)
                    when {
                        o == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                            val f = codec.outputFormat
                            rate = f.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                            channels = f.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                            float = f.containsKey(MediaFormat.KEY_PCM_ENCODING) && f.getInteger(MediaFormat.KEY_PCM_ENCODING) == AudioFormat.ENCODING_PCM_FLOAT
                            if (info == null) wanted = (lengthMs / 1000 * rate).toLong()
                        }
                        o >= 0 -> {
                            if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outputDone = true
                            val buffer = codec.getOutputBuffer(o)
                            if (buffer != null && bufferInfo.size > 0) {
                                buffer.position(bufferInfo.offset)
                                buffer.limit(bufferInfo.offset + bufferInfo.size)
                                buffer.order(ByteOrder.nativeOrder())
                                val samples = if (float) bufferInfo.size / 4 else bufferInfo.size / 2
                                if (shorts.size < samples) shorts = ShortArray(samples)
                                if (float) {
                                    val fb = buffer.asFloatBuffer()
                                    for (k in 0 until samples) shorts[k] = (fb.get(k).coerceIn(-1f, 1f) * 32767f).roundToInt().toShort()
                                } else {
                                    buffer.asShortBuffer().get(shorts, 0, samples)
                                }
                                val frames = samples / channels
                                var skip = minOf(toTrim, frames)
                                toTrim -= skip
                                if (startUs > 0 && info == null) {
                                    // Frames before the asked start, by the buffer's own time.
                                    val behind = ((startUs - bufferInfo.presentationTimeUs) * rate / 1_000_000).toInt()
                                    skip = max(skip, behind.coerceIn(0, frames))
                                }
                                val n = minOf(frames - skip, (wanted - taken).toInt())
                                if (n > 0) {
                                    val window = info ?: WindowInfo(
                                        rate, channels,
                                        if (startUs > 0) max(startMs, (bufferInfo.presentationTimeUs + skip * 1_000_000L / rate) / 1000.0) else 0.0,
                                        0, durationMs,
                                    ).also { info = it }
                                    write(sink, bytes, shorts, skip * channels, n * channels)
                                    if (skip > 0) System.arraycopy(shorts, skip * channels, shorts, 0, n * channels)
                                    measure(window, shorts, n)
                                    taken += n
                                }
                            }
                            codec.releaseOutputBuffer(o, false)
                        }
                    }
                }
            }
            val written = info ?: throw IOException("nothing was decoded")
            if (taken < MIN_FRAMES_S * written.sampleRate) throw IOException("the window came short, ${taken / written.sampleRate} s")
            return written.copy(frames = taken)
        } finally {
            runCatching { codec?.stop() }
            runCatching { codec?.release() }
            runCatching { extractor.release() }
        }
    }

    private fun write(sink: BufferedOutputStream, bytes: ByteBuffer, samples: ShortArray, from: Int, count: Int) {
        var i = from
        val end = from + count
        while (i < end) {
            bytes.clear()
            val n = minOf(end - i, bytes.capacity() / 2)
            bytes.asShortBuffer().put(samples, i, n)
            sink.write(bytes.array(), 0, n * 2)
            i += n
        }
    }

    /** The player's data source, handed the song's id as its key, as HeadDecoder does. */
    private class KeyedSource(private val upstream: DataSource.Factory, private val key: String) : DataSource.Factory {
        override fun createDataSource(): DataSource {
            val inner = upstream.createDataSource()
            return object : DataSource {
                override fun addTransferListener(transferListener: TransferListener) = inner.addTransferListener(transferListener)
                override fun open(dataSpec: DataSpec): Long = inner.open(dataSpec.buildUpon().setKey(key).build())
                override fun read(buffer: ByteArray, offset: Int, length: Int): Int = inner.read(buffer, offset, length)
                override fun getUri(): Uri? = inner.uri
                override fun getResponseHeaders(): Map<String, List<String>> = inner.responseHeaders
                override fun close() = inner.close()
            }
        }
    }

    private companion object {
        const val KEY_ENCODER_DELAY = "encoder-delay"
        const val WAIT_US = 5_000L
        const val TIMEOUT_MS = 60_000L

        /** Less than this was a read that failed, not a window. */
        const val MIN_FRAMES_S = 10
    }
}
