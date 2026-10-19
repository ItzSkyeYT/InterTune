/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.playback.automix

import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaFormat
import android.net.Uri
import android.os.SystemClock
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import androidx.media3.exoplayer.MediaExtractorCompat
import androidx.media3.exoplayer.mediacodec.MediaCodecUtil
import androidx.media3.extractor.DefaultExtractorsFactory
import com.dd3boh.outertune.mix.FrontEnd
import com.dd3boh.outertune.mix.FrontEndResult
import java.io.IOException
import java.nio.ByteOrder
import kotlin.math.roundToInt

/**
 * Reads the opening of the song that comes next, the way the player will play it, for its
 * analysis: its first [AutomixSchedule.HEAD_SECONDS] seconds, decoded, downmixed and decimated as
 * they come (FrontEnd), so no full-rate copy is ever kept.
 *
 * Through the player's own data source, the one instance the player reads every song through, so
 * the song is resolved once: its address is kept for the player, and with the player's cache on
 * its first bytes are already there when the player gets to it. The opening has to line up with
 * the player's to the frame, so it goes through media3's own extractors and the decoder the player
 * would pick, and the encoder delay the sink trims at the start of a stream is trimmed here too.
 *
 * Blocking: called on Dispatchers.IO. [cancelled] is asked between buffers.
 */
@UnstableApi
class HeadDecoder(private val source: DataSource.Factory) {

    class Head(val front: FrontEndResult, val readMs: Long)

    fun read(mediaId: String, cancelled: () -> Boolean): Head {
        val started = SystemClock.elapsedRealtime()
        val extractor = MediaExtractorCompat(DefaultExtractorsFactory(), KeyedSource(source, mediaId))
        var codec: MediaCodec? = null
        try {
            extractor.setDataSource(Uri.parse(mediaId), 0)
            val track = (0 until extractor.trackCount).firstOrNull {
                extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
            } ?: throw IOException("no audio track")
            extractor.selectTrack(track)
            val format = extractor.getTrackFormat(track)
            val mime = format.getString(MediaFormat.KEY_MIME)!!
            // The frames the sink drops at the start of the stream (TrimmingAudioProcessor), which
            // media3 reads from the container and hands on in the track's format.
            var toTrim = if (format.containsKey(KEY_ENCODER_DELAY)) format.getInteger(KEY_ENCODER_DELAY).coerceAtLeast(0) else 0
            val decoder = runCatching { MediaCodecUtil.getDecoderInfo(mime, false, false)?.name }.getOrNull()
            codec = if (decoder != null) MediaCodec.createByCodecName(decoder) else MediaCodec.createDecoderByType(mime)
            // A fresh format with what a decoder needs, as the renderer gives it: the encoder delay
            // left out, since it is trimmed here and some decoders would trim it themselves.
            val config = MediaFormat.createAudioFormat(mime, format.getInteger(MediaFormat.KEY_SAMPLE_RATE), format.getInteger(MediaFormat.KEY_CHANNEL_COUNT))
            for (i in 0..2) format.getByteBuffer("csd-$i")?.let { config.setByteBuffer("csd-$i", it) }
            if (format.containsKey(MediaFormat.KEY_MAX_INPUT_SIZE)) config.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, format.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE))
            codec.configure(config, null, null, 0)
            codec.start()

            var rate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            var channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            var float = false
            var front: FrontEnd? = null
            var wanted = (AutomixSchedule.HEAD_SECONDS * rate).toLong()
            var taken = 0L
            var shorts = ShortArray(0)
            val info = MediaCodec.BufferInfo()
            var inputDone = false
            var outputDone = false
            while (!outputDone && taken < wanted) {
                if (cancelled()) throw IOException("cancelled")
                if (SystemClock.elapsedRealtime() - started > TIMEOUT_MS) throw IOException("timed out")
                if (!inputDone) {
                    val i = codec.dequeueInputBuffer(WAIT_US)
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
                    }
                }
                val o = codec.dequeueOutputBuffer(info, WAIT_US)
                when {
                    o == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        val out = codec.outputFormat
                        rate = out.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                        channels = out.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                        float = out.containsKey(MediaFormat.KEY_PCM_ENCODING) && out.getInteger(MediaFormat.KEY_PCM_ENCODING) == AudioFormat.ENCODING_PCM_FLOAT
                        if (front == null) wanted = (AutomixSchedule.HEAD_SECONDS * rate).toLong()
                    }
                    o >= 0 -> {
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outputDone = true
                        val buffer = codec.getOutputBuffer(o)
                        if (buffer != null && info.size > 0) {
                            buffer.position(info.offset)
                            buffer.limit(info.offset + info.size)
                            buffer.order(ByteOrder.nativeOrder())
                            val samples = if (float) info.size / 4 else info.size / 2
                            if (shorts.size < samples) shorts = ShortArray(samples)
                            if (float) {
                                val f = buffer.asFloatBuffer()
                                for (k in 0 until samples) shorts[k] = (f.get(k).coerceIn(-1f, 1f) * 32767f).roundToInt().toShort()
                            } else {
                                buffer.asShortBuffer().get(shorts, 0, samples)
                            }
                            val frames = samples / channels
                            val skip = minOf(toTrim, frames)
                            toTrim -= skip
                            val n = minOf(frames - skip, (wanted - taken).toInt())
                            if (n > 0) {
                                val f = front ?: FrontEnd(rate, channels).also { front = it }
                                f.push(shorts, skip, n)
                                taken += n
                            }
                        }
                        codec.releaseOutputBuffer(o, false)
                    }
                }
            }
            val result = front?.result() ?: throw IOException("nothing was decoded")
            // The extractor takes a failed read for the end of the song and says so only in its own
            // log, so an opening cut short by the network would pass for a short song.
            if (result.seconds < MIN_SECONDS) throw IOException("the opening came short, %.1f s".format(result.seconds))
            return Head(result, SystemClock.elapsedRealtime() - started)
        } finally {
            runCatching { codec?.stop() }
            runCatching { codec?.release() }
            runCatching { extractor.release() }
        }
    }

    /**
     * The player's data source, handed requests as the player makes them: an extractor of its own
     * gives no key, and the player's source finds a song by the key, which is its id.
     */
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
        const val WAIT_US = 10_000L
        const val TIMEOUT_MS = 30_000L

        /** Less than this of a song a minute long or more is a read that failed. */
        const val MIN_SECONDS = 20.0
    }
}
