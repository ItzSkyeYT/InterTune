/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.playback

import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaDataSource
import android.media.MediaExtractor
import android.media.MediaFormat
import android.util.Log
import com.dd3boh.outertune.cast.CastReader
import com.dd3boh.outertune.cast.CastSong
import com.dd3boh.outertune.cast.CastUnavailableException
import com.dd3boh.outertune.cast.DecodedAudio
import com.dd3boh.outertune.cast.DecodedPiece
import com.dd3boh.outertune.cast.PcmDecoder
import com.dd3boh.outertune.cast.PcmSource
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.roundToInt

/**
 * Decodes a song for the cast server's raw audio (PcmCastSong) with Android's own MediaExtractor
 * and MediaCodec: Opus from WebM, AAC from MP4, and whatever a file on the phone holds.
 *
 * The song is read through [CastSongDataSource] from the very CastSong the server would send as
 * it is, so a cached copy, a download, a file and a stream are each read the way they are served,
 * and a stream is cached on the phone as it would be if it played there. Everything here blocks,
 * on the server's worker for the connection, never on the main thread.
 */
class MediaCodecPcmDecoder : PcmDecoder {
    override fun probe(song: CastSong): PcmSource {
        val data = CastSongDataSource(song)
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(data)
            val track = audioTrack(extractor) ?: throw CastUnavailableException("The song holds no audio to decode")
            val format = extractor.getTrackFormat(track)
            val duration = if (format.containsKey(MediaFormat.KEY_DURATION)) format.getLong(MediaFormat.KEY_DURATION) else -1L
            val source = PcmSource(format.getInteger(MediaFormat.KEY_SAMPLE_RATE), format.getInteger(MediaFormat.KEY_CHANNEL_COUNT), duration)
            Log.i(TAG, "The song is ${format.getString(MediaFormat.KEY_MIME)}: $source")
            return source
        } catch (e: CastUnavailableException) {
            throw e
        } catch (e: Exception) {
            throw data.failure ?: IOException("The song could not be read for decoding: ${e.message}", e)
        } finally {
            runCatching { extractor.release() }
            data.close()
        }
    }

    override fun open(song: CastSong, fromUs: Long): DecodedAudio {
        val data = CastSongDataSource(song)
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        try {
            extractor.setDataSource(data)
            val track = audioTrack(extractor) ?: throw CastUnavailableException("The song holds no audio to decode")
            extractor.selectTrack(track)
            val format = extractor.getTrackFormat(track)
            if (fromUs > 0) extractor.seekTo(fromUs, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
            val mime = format.getString(MediaFormat.KEY_MIME) ?: throw CastUnavailableException("The song's audio has no type")
            Log.i(TAG, "Decoding $mime from ${fromUs / 1000} ms")
            // A WAV file on the phone is raw audio already, which no codec decodes.
            if (mime == MediaFormat.MIMETYPE_AUDIO_RAW) return RawAudio(extractor, data, format)
            codec = MediaCodec.createDecoderByType(mime)
            codec.configure(format, null, null, 0)
            codec.start()
            return CodecAudio(extractor, codec, data, format)
        } catch (e: Exception) {
            runCatching { codec?.release() }
            runCatching { extractor.release() }
            data.close()
            if (e is CastUnavailableException) throw e
            throw data.failure ?: IOException("The song could not be decoded: ${e.message}", e)
        }
    }

    private fun audioTrack(extractor: MediaExtractor): Int? =
        (0 until extractor.trackCount).firstOrNull { extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true }

    /** What a codec gives, piece by piece; each piece's samples are copied out before its buffer goes back. */
    private class CodecAudio(
        private val extractor: MediaExtractor,
        private val codec: MediaCodec,
        private val data: CastSongDataSource,
        format: MediaFormat,
    ) : DecodedAudio {
        private val info = MediaCodec.BufferInfo()
        private val pcm = PcmSamples(format)
        private var inputDone = false
        private var outputDone = false

        override fun next(): DecodedPiece? {
            var idleSince = System.nanoTime()
            while (!outputDone) {
                if (!inputDone) feed()
                val index = codec.dequeueOutputBuffer(info, TIMEOUT_US)
                when {
                    index >= 0 -> {
                        val ends = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                        val buffer = codec.getOutputBuffer(index)
                        val piece = if (info.size > 0 && buffer != null) pcm.take(buffer, info.offset, info.size, info.presentationTimeUs) else null
                        codec.releaseOutputBuffer(index, false)
                        if (ends) outputDone = true
                        if (piece != null) return piece
                        idleSince = System.nanoTime()
                    }
                    index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> pcm.formatChanged(codec.outputFormat)
                    System.nanoTime() - idleSince > STALL_NS -> throw IOException("The decoder stopped giving audio")
                }
            }
            return null
        }

        /** Hands the codec every sample it has room for now. */
        private fun feed() {
            while (true) {
                val index = codec.dequeueInputBuffer(0)
                if (index < 0) return
                val buffer = codec.getInputBuffer(index) ?: return
                val size = extractor.readSampleData(buffer, 0)
                if (size < 0) {
                    // The end, or a read that failed, which the extractor cannot tell apart.
                    data.failure?.let { throw it }
                    codec.queueInputBuffer(index, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                    inputDone = true
                    return
                }
                codec.queueInputBuffer(index, 0, size, extractor.sampleTime, 0)
                extractor.advance()
            }
        }

        override fun close() {
            runCatching { codec.stop() }
            runCatching { codec.release() }
            runCatching { extractor.release() }
            data.close()
        }
    }

    /** A track that is raw audio already: its samples as they are read. */
    private class RawAudio(
        private val extractor: MediaExtractor,
        private val data: CastSongDataSource,
        format: MediaFormat,
    ) : DecodedAudio {
        private val pcm = PcmSamples(format)
        private val buffer = ByteBuffer.allocateDirect(
            if (format.containsKey(MediaFormat.KEY_MAX_INPUT_SIZE)) format.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE).coerceIn(4096, 4 shl 20) else 256 * 1024,
        )

        override fun next(): DecodedPiece? {
            buffer.clear()
            val size = extractor.readSampleData(buffer, 0)
            if (size < 0) {
                data.failure?.let { throw it }
                return null
            }
            val time = extractor.sampleTime
            extractor.advance()
            return pcm.take(buffer, 0, size, time)
        }

        override fun close() {
            runCatching { extractor.release() }
            data.close()
        }
    }

    /** Decoded bytes as 16-bit samples, whatever sample format the decoder gives them in. */
    private class PcmSamples(format: MediaFormat) {
        private var rate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
        private var channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
        private var encoding = encodingOf(format)
        private var samples = ShortArray(0)

        fun formatChanged(format: MediaFormat) {
            rate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            encoding = encodingOf(format)
        }

        fun take(buffer: ByteBuffer, offset: Int, size: Int, timeUs: Long): DecodedPiece {
            val view = buffer.duplicate().order(ByteOrder.nativeOrder())
            view.limit(offset + size)
            view.position(offset)
            val bytesPerSample = when (encoding) {
                AudioFormat.ENCODING_PCM_8BIT -> 1
                AudioFormat.ENCODING_PCM_16BIT -> 2
                ENCODING_PCM_24BIT_PACKED -> 3
                AudioFormat.ENCODING_PCM_FLOAT, ENCODING_PCM_32BIT -> 4
                else -> throw IOException("The decoder gives samples in a form this cannot read ($encoding)")
            }
            val count = size / bytesPerSample / channels * channels
            if (samples.size < count) samples = ShortArray(count)
            when (encoding) {
                AudioFormat.ENCODING_PCM_16BIT -> view.asShortBuffer().get(samples, 0, count)
                AudioFormat.ENCODING_PCM_8BIT -> for (index in 0 until count) samples[index] = (((view.get().toInt() and 0xFF) - 128) shl 8).toShort()
                ENCODING_PCM_24BIT_PACKED -> for (index in 0 until count) {
                    view.get()
                    val middle = view.get().toInt() and 0xFF
                    val high = view.get().toInt()
                    samples[index] = ((high shl 8) or middle).toShort()
                }
                ENCODING_PCM_32BIT -> {
                    val ints = view.asIntBuffer()
                    for (index in 0 until count) samples[index] = (ints.get(index) shr 16).toShort()
                }
                else -> {
                    val floats = view.asFloatBuffer()
                    for (index in 0 until count) {
                        samples[index] = (floats.get(index) * 32767f).roundToInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
                    }
                }
            }
            return DecodedPiece(timeUs, rate, channels, samples, count / channels)
        }

        private fun encodingOf(format: MediaFormat): Int =
            if (format.containsKey(MediaFormat.KEY_PCM_ENCODING)) format.getInteger(MediaFormat.KEY_PCM_ENCODING) else AudioFormat.ENCODING_PCM_16BIT
    }

    private companion object {
        const val TAG = "MediaCodecPcm"
        const val TIMEOUT_US = 10_000L

        /** A decoder that gives nothing for this long is taken to have stopped. */
        const val STALL_NS = 15_000_000_000L

        /** AudioFormat's, from API 31, written out so the older phones read the same numbers. */
        const val ENCODING_PCM_24BIT_PACKED = 21
        const val ENCODING_PCM_32BIT = 22
    }
}

/**
 * A CastSong as MediaExtractor reads it: from any position, through the song's own reader, kept
 * open while the reads follow on from each other and opened afresh where they jump back or far
 * ahead. The last block read is kept, so the small steps back an extractor takes cost nothing.
 *
 * A read that fails is noted in [failure] and answered as the end, which is all an extractor can
 * be told; the decoder then reports the failure rather than an early end.
 */
class CastSongDataSource(private val song: CastSong) : MediaDataSource() {
    @Volatile
    var failure: IOException? = null
        private set

    private var reader: CastReader? = null
    private var readerAt = -1L
    private val block = ByteArray(BLOCK_BYTES)
    private var blockStart = 0L
    private var blockCount = 0
    private var closed = false

    override fun getSize(): Long = song.length

    @Synchronized
    override fun readAt(position: Long, buffer: ByteArray, offset: Int, size: Int): Int {
        if (size <= 0) return 0
        if (failure != null || closed) return -1
        val length = song.length
        if (position < 0 || (length >= 0 && position >= length)) return -1
        if (position < blockStart || position >= blockStart + blockCount) {
            try {
                load(position, size)
            } catch (e: IOException) {
                failure = e
                return -1
            } catch (e: RuntimeException) {
                failure = IOException("The song could not be read: ${e.message}", e)
                return -1
            }
            if (blockCount <= 0) return -1
        }
        val from = (position - blockStart).toInt()
        val count = minOf(size, blockCount - from)
        System.arraycopy(block, from, buffer, offset, count)
        return count
    }

    /** Reads a block from [position]: at least [size] bytes, or what is left, up to a block. */
    private fun load(position: Long, size: Int) {
        var current = reader
        if (current != null && position > readerAt && position - readerAt <= SKIP_BYTES) {
            // A little further on: read past the gap rather than open a new request.
            var gap = position - readerAt
            while (gap > 0) {
                val read = current.read(block, 0, minOf(gap, block.size.toLong()).toInt())
                if (read < 0) break
                gap -= read
                readerAt += read
            }
        }
        if (current == null || position != readerAt) {
            current?.close()
            current = song.openAt(position)
            reader = current
            readerAt = position
        }
        val wanted = minOf(block.size, maxOf(size, MIN_FILL_BYTES))
        var count = 0
        while (count < wanted) {
            val read = current.read(block, count, block.size - count)
            if (read < 0) break
            count += read
        }
        blockStart = position
        blockCount = count
        readerAt = position + count
    }

    @Synchronized
    override fun close() {
        closed = true
        reader?.let { runCatching { it.close() } }
        reader = null
    }

    private companion object {
        const val BLOCK_BYTES = 64 * 1024
        const val MIN_FILL_BYTES = 16 * 1024
        const val SKIP_BYTES = 64 * 1024L
    }
}
