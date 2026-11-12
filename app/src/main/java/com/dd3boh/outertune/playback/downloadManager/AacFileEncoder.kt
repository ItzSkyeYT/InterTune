/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.playback.downloadManager

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import com.dd3boh.outertune.constants.DownloadFormat
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.OutputStream

/**
 * An M4A of AAC (Unreleased.DOWNLOAD_FORMATS), encoded at a constant [kbps] by the phone's own AAC
 * encoder, MediaCodec's, and put in an .m4a by M4aWriter, the very writer Original's .m4a goes
 * through, so that the tags, the cover and the song's link are the same and the app finds the file
 * the same way. MediaMuxer is not used: it writes no iTunes tags, and M4aWriter does.
 *
 * AAC-LC, 1024 samples a frame. The encoder's frames are kept in a second temporary file beside
 * the target while they are made, since the index of an .m4a has to come first and needs every
 * frame's size.
 *
 * Only for a song that is not AAC already (SongConversion): an AAC stream is Original's .m4a, never
 * encoded twice.
 */
internal class AacFileEncoder(
    override val file: File,
    private val tags: SongTags,
    private val kbps: Int,
) : FileEncoder {
    override val format = DownloadFormat.M4A

    private val framesFile = File(file.path + ".frames")
    private var codec: MediaCodec? = null
    private var frames: OutputStream? = null
    private var rate = 0
    private var channels = 0
    private var config: ByteArray? = null
    private var sizes = IntArray(4096)
    private var count = 0
    private var queued = 0L
    private var ended = false
    private val info = MediaCodec.BufferInfo()
    private var bytes = ByteArray(0)

    override fun start(rate: Int, channels: Int) {
        require(channels in 1..2) { "an AAC of $channels channels" }
        require(rate in 8000..96_000) { "an AAC at $rate Hz" }
        this.rate = rate
        this.channels = channels
        val media = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, rate, channels).apply {
            setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            setInteger(MediaFormat.KEY_BIT_RATE, kbps * 1000)
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, INPUT_BYTES)
        }
        val made = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
        codec = made
        made.configure(media, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        made.start()
        frames = BufferedOutputStream(FileOutputStream(framesFile), 64 * 1024)
    }

    override fun add(samples: ShortArray, frames: Int) {
        if (frames <= 0) return
        val total = frames * channels
        if (bytes.size < total * 2) bytes = ByteArray(total * 2)
        for (i in 0 until total) {
            bytes[2 * i] = samples[i].toInt().toByte()
            bytes[2 * i + 1] = (samples[i].toInt() shr 8).toByte()
        }
        val codec = codec!!
        var at = 0
        val frameBytes = channels * 2
        while (at < total * 2) {
            val index = codec.dequeueInputBuffer(WAIT_US)
            if (index < 0) {
                // No room: the encoder wants its output taken before it takes more.
                drain()
                continue
            }
            val buffer = codec.getInputBuffer(index) ?: throw IOException("the AAC encoder has no input buffer")
            buffer.clear()
            val n = minOf(total * 2 - at, buffer.capacity() / frameBytes * frameBytes)
            buffer.put(bytes, at, n)
            codec.queueInputBuffer(index, 0, n, queued * 1_000_000L / rate, 0)
            queued += n / frameBytes
            at += n
            drain()
        }
    }

    override fun finish() {
        val codec = codec!!
        var sent = false
        var idle = System.nanoTime()
        while (!sent) {
            val index = codec.dequeueInputBuffer(WAIT_US)
            if (index >= 0) {
                codec.queueInputBuffer(index, 0, 0, queued * 1_000_000L / rate, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                sent = true
            } else {
                drain()
                if (System.nanoTime() - idle > STALL_NS) throw IOException("the AAC encoder stopped taking audio")
            }
        }
        idle = System.nanoTime()
        while (!ended) {
            val before = count
            drain()
            if (count != before) idle = System.nanoTime()
            else if (System.nanoTime() - idle > STALL_NS) throw IOException("the AAC encoder stopped giving frames")
        }
        runCatching { codec.stop() }
        codec.release()
        this.codec = null
        frames!!.flush()
        frames!!.close()
        frames = null
        if (count == 0) throw IOException("the AAC encoder gave no frames")
        val track = M4aTrack(
            audioSpecificConfig = config ?: throw IOException("the AAC encoder gave no AudioSpecificConfig"),
            timescale = rate,
            channels = channels,
            sizes = sizes.copyOf(count),
            durations = IntArray(count) { FRAME_SAMPLES },
            priming = ENCODER_DELAY,
        )
        BufferedOutputStream(FileOutputStream(file), 64 * 1024).use { out ->
            M4aWriter.write(out, track, tags) { sink ->
                FileInputStream(framesFile).use { it.copyTo(sink, 64 * 1024) }
            }
        }
        framesFile.delete()
    }

    override fun close() {
        runCatching { codec?.stop() }
        runCatching { codec?.release() }
        codec = null
        runCatching { frames?.close() }
        frames = null
        framesFile.delete()
    }

    /** Takes what the encoder has ready: its frames into the frames file, and its AudioSpecificConfig once. */
    private fun drain() {
        val codec = codec!!
        while (true) {
            val index = codec.dequeueOutputBuffer(info, 0)
            when {
                index >= 0 -> {
                    val buffer = codec.getOutputBuffer(index)
                    val isConfig = info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0
                    if (buffer != null && info.size > 0) {
                        buffer.position(info.offset)
                        buffer.limit(info.offset + info.size)
                        if (isConfig) {
                            if (config == null) config = ByteArray(info.size).also { buffer.get(it) }
                        } else {
                            // A new array for each frame: [bytes] holds audio still to be queued.
                            val frame = ByteArray(info.size)
                            buffer.get(frame)
                            frames!!.write(frame)
                            if (count == sizes.size) sizes = sizes.copyOf(count * 2)
                            sizes[count++] = frame.size
                        }
                    }
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) ended = true
                    codec.releaseOutputBuffer(index, false)
                    if (ended) return
                }
                index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    if (config == null) {
                        codec.outputFormat.getByteBuffer("csd-0")?.let { csd ->
                            val copy = csd.duplicate()
                            config = ByteArray(copy.remaining()).also { copy.get(it) }
                        }
                    }
                }
                else -> return
            }
        }
    }

    private companion object {
        /** AAC-LC: a frame is 1024 samples. */
        const val FRAME_SAMPLES = 1024

        /** The samples the phone's AAC encoder (FDK) puts in front of the audio, which the edit list tells a player to skip. */
        const val ENCODER_DELAY = 2048L

        const val INPUT_BYTES = 32 * 1024
        const val WAIT_US = 10_000L
        const val STALL_NS = 15_000_000_000L
    }
}
