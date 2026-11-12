/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.playback.downloadManager

import com.dd3boh.outertune.constants.DownloadFormat
import de.sciss.jump3r.mp3.BitStream
import de.sciss.jump3r.mp3.GainAnalysis
import de.sciss.jump3r.mp3.GetAudio
import de.sciss.jump3r.mp3.ID3Tag
import de.sciss.jump3r.mp3.Lame
import de.sciss.jump3r.mp3.LameGlobalFlags
import de.sciss.jump3r.mp3.Parse
import de.sciss.jump3r.mp3.Presets
import de.sciss.jump3r.mp3.Quantize
import de.sciss.jump3r.mp3.QuantizePVT
import de.sciss.jump3r.mp3.Reservoir
import de.sciss.jump3r.mp3.Takehiro
import de.sciss.jump3r.mp3.VBRTag
import de.sciss.jump3r.mp3.Version
import de.sciss.jump3r.mpg.Common
import de.sciss.jump3r.mpg.Interface
import de.sciss.jump3r.mpg.MPGLib
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.OutputStream
import java.io.RandomAccessFile

/**
 * An MP3 file (Unreleased.DOWNLOAD_FORMATS), encoded at a constant [kbps] at the song's own sample
 * rate, stereo as joint stereo and mono as mono, with the song's ID3 tag and cover in front.
 *
 * Android has no MP3 encoder, so this is jump3r, a Java port of the LAME encoder (LGPL 2.1 or
 * later, from Maven Central): pure Java, no native code. Its wrapper class, LameEncoder, needs
 * javax.sound, which Android lacks, so the encoder is driven here through the modules LameEncoder
 * wires together, the way it does. LAME's quality setting is 3, the one under its slowest and best
 * pair: the sound is a lossy stream's already and the rate is high, so the last bit of quality
 * costs more time than it is worth.
 *
 * LAME's Info frame is written, as LAME writes it for a constant rate: the first frame after the
 * ID3 tag holds the number of frames, the size, and the encoder's delay and padding, which players
 * that know the tag (ffmpeg, ExoPlayer, Android's own extractor, foobar2000) use to start and end
 * the song where it starts and ends, and to time it exactly. LAME writes it empty as the first
 * frame and fills it in when the stream is over, which is why the file is opened again then.
 * No Android in here, so it is tested on the JVM.
 */
internal class Mp3FileEncoder(
    override val file: File,
    private val tags: SongTags,
    private val kbps: Int,
) : FileEncoder {
    override val format = DownloadFormat.MP3

    private var out: OutputStream? = null
    private var lame: Lame? = null
    private var vbrTag: VBRTag? = null
    private var flags: LameGlobalFlags? = null

    /** The bytes of ID3 tag at the start of the file, which the Info frame comes after. */
    private var id3Size = 0
    private var channels = 0
    private var left = IntArray(0)
    private var right = IntArray(0)
    private var mp3 = ByteArray(0)

    /** The rate the file is encoded at, once started: [kbps], or the nearest the sample rate's MPEG version has. */
    var effectiveKbps = 0
        private set

    override fun start(rate: Int, channels: Int) {
        require(channels in 1..2) { "an MP3 of $channels channels" }
        require(rate in 8000..192_000) { "an MP3 at $rate Hz" }
        this.channels = channels
        // The modules LAME is made of, wired together as jump3r's LameEncoder wires them.
        val lame = Lame()
        val getAudio = GetAudio()
        val gain = GainAnalysis()
        val bitStream = BitStream()
        val presets = Presets()
        val quantizePvt = QuantizePVT()
        val quantize = Quantize()
        val vbr = VBRTag()
        val version = Version()
        val id3 = ID3Tag()
        val reservoir = Reservoir()
        val takehiro = Takehiro()
        val parse = Parse()
        val mpg = MPGLib()
        val decoder = Interface()
        val common = Common()
        lame.setModules(gain, bitStream, presets, quantizePvt, quantize, vbr, version, id3, mpg)
        bitStream.setModules(gain, mpg, version, vbr)
        id3.setModules(bitStream, version)
        presets.setModules(lame)
        quantize.setModules(bitStream, reservoir, quantizePvt, takehiro)
        quantizePvt.setModules(takehiro, reservoir, lame.enc.psy)
        reservoir.setModules(bitStream)
        takehiro.setModules(quantizePvt)
        vbr.setModules(lame, bitStream, version)
        getAudio.setModules(parse, mpg)
        parse.setModules(version, id3, presets)
        mpg.setModules(decoder, common)
        decoder.setModules(vbr, common)

        val flags = lame.lame_init()
        flags.num_channels = channels
        flags.in_samplerate = rate
        flags.brate = kbps
        flags.quality = QUALITY
        flags.bWriteVbrTag = true
        id3.id3tag_init(flags)
        // Before the parameters are set: the tag is ours, written in front of the first frame.
        flags.write_id3tag_automatic = false
        flags.findReplayGain = false
        val result = lame.lame_init_params(flags)
        if (result < 0) throw IOException("LAME refused $kbps kbps at $rate Hz ($result)")
        effectiveKbps = flags.brate
        this.lame = lame
        this.vbrTag = vbr
        this.flags = flags
        val tagBytes = Id3v2.tag(tags)
        id3Size = tagBytes.size
        out = BufferedOutputStream(FileOutputStream(file), 64 * 1024).also { it.write(tagBytes) }
    }

    override fun add(samples: ShortArray, frames: Int) {
        if (frames <= 0) return
        val lame = lame!!
        val flags = flags!!
        if (left.size < frames) {
            left = IntArray(frames)
            right = if (channels == 2) IntArray(frames) else left
        }
        // LAME's int input is scaled to 32 bits: a 16-bit sample is shifted up by 16.
        if (channels == 2) {
            for (i in 0 until frames) {
                left[i] = samples[2 * i].toInt() shl 16
                right[i] = samples[2 * i + 1].toInt() shl 16
            }
        } else {
            for (i in 0 until frames) left[i] = samples[i].toInt() shl 16
        }
        // LAME's own rule for the room a call may fill.
        val room = (1.25 * frames + 7200).toInt()
        if (mp3.size < room) mp3 = ByteArray(room)
        val written = lame.lame_encode_buffer_int(flags, left, right, frames, mp3, 0, mp3.size)
        if (written < 0) throw IOException("LAME failed to encode ($written)")
        out!!.write(mp3, 0, written)
    }

    override fun finish() {
        val lame = lame!!
        if (mp3.size < FLUSH_ROOM) mp3 = ByteArray(FLUSH_ROOM)
        val written = lame.lame_encode_flush(flags!!, mp3, 0, mp3.size)
        if (written < 0) throw IOException("LAME failed to finish ($written)")
        out!!.write(mp3, 0, written)
        out!!.flush()
        out!!.close()
        out = null
        // The Info frame LAME left empty in front of the audio, now that its numbers are known. Put where it was, after our
        // ID3 tag: jump3r's own putVbrTag looks for a tag the wrong way round (it takes a file that has one for a file that
        // has none) and would write over it.
        val frame = ByteArray(VBRTag.MAXFRAMESIZE)
        val size = vbrTag!!.getLameTagFrame(flags!!, frame)
        if (size < 1 || size > frame.size) throw IOException("LAME gave no Info frame ($size)")
        RandomAccessFile(file, "rw").use {
            it.seek(id3Size.toLong())
            it.write(frame, 0, size)
        }
    }

    override fun close() {
        runCatching { out?.close() }
        out = null
        runCatching { lame?.lame_close(flags) }
        lame = null
        flags = null
    }

    private companion object {
        /** LAME's -q 3. */
        const val QUALITY = 3

        /** What the last call may need: LAME's own 7200 bytes and a frame or two. */
        const val FLUSH_ROOM = 16 * 1024
    }
}
