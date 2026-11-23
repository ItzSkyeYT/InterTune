/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.playback

import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.extractor.DefaultExtractorsFactory
import androidx.media3.extractor.Extractor
import androidx.media3.extractor.ExtractorInput
import androidx.media3.extractor.ExtractorOutput
import androidx.media3.extractor.ExtractorsFactory
import androidx.media3.extractor.PositionHolder
import androidx.media3.extractor.mp3.Mp3Extractor
import androidx.media3.extractor.text.SubtitleParser
import java.io.EOFException

/**
 * The extractors the player reads local files with: media3's own, except that an MP3 is read by
 * [PerFileMp3Extractor]. A variable-bitrate MP3 with no Xing, Info or VBRI header has no table of
 * where each second sits, so by default media3 calls it unseekable and Android guesses its length
 * from the first frames (48 s for a 3-minute song). Index seeking builds that table as the file is
 * read, so the song can be sought and its length is right.
 *
 * Index seeking costs a read: a jump far ahead reads everything before it once. media3 uses it for
 * every MP3 once its flag is set, even one whose header already says where each second sits, so
 * the flag is only given to the files that lack such a header; every other MP3 is read as before.
 * YouTube's streams are not MP3 and are unaffected.
 */
@OptIn(UnstableApi::class)
fun seekableMp3Extractors(): ExtractorsFactory = SeekableMp3ExtractorsFactory()

@OptIn(UnstableApi::class)
private class SeekableMp3ExtractorsFactory : ExtractorsFactory {
    private val inner = DefaultExtractorsFactory()

    override fun createExtractors(): Array<Extractor> = swap(inner.createExtractors())

    override fun createExtractors(uri: Uri, responseHeaders: Map<String, List<String>>): Array<Extractor> =
        swap(inner.createExtractors(uri, responseHeaders))

    // Passed on, so media3 sets up the other extractors exactly as with its own factory.
    @Suppress("OVERRIDE_DEPRECATION", "DEPRECATION")
    override fun experimentalSetTextTrackTranscodingEnabled(textTrackTranscodingEnabled: Boolean): ExtractorsFactory {
        inner.experimentalSetTextTrackTranscodingEnabled(textTrackTranscodingEnabled)
        return this
    }

    override fun setSubtitleParserFactory(subtitleParserFactory: SubtitleParser.Factory): ExtractorsFactory {
        inner.setSubtitleParserFactory(subtitleParserFactory)
        return this
    }

    private fun swap(extractors: Array<Extractor>): Array<Extractor> = swapMp3Extractor(extractors)
}

/** media3's extractors in their order, with its Mp3Extractor replaced by a [PerFileMp3Extractor]. */
@OptIn(UnstableApi::class)
internal fun swapMp3Extractor(extractors: Array<Extractor>): Array<Extractor> =
    Array(extractors.size) { i -> if (extractors[i] is Mp3Extractor) PerFileMp3Extractor() else extractors[i] }

/**
 * media3's Mp3Extractor, made for each file once its start has been looked at: with index seeking
 * when the first frame names no seek table (no Xing, Info or VBRI header), plain otherwise. The
 * look happens in [sniff] when media3 sniffs, or at the first [read] when it does not (a single
 * extractor for a file is not sniffed).
 */
@OptIn(UnstableApi::class)
internal class PerFileMp3Extractor : Extractor {
    private var delegate: Mp3Extractor? = null
    private var output: ExtractorOutput? = null

    override fun sniff(input: ExtractorInput): Boolean {
        val chosen = Mp3Extractor(flagsFor(input))
        if (!chosen.sniff(input)) return false
        delegate = chosen
        return true
    }

    override fun init(output: ExtractorOutput) {
        this.output = output
        delegate?.init(output)
    }

    override fun read(input: ExtractorInput, seekPosition: PositionHolder): Int {
        val reader = delegate ?: Mp3Extractor(flagsFor(input)).also {
            it.init(checkNotNull(output) { "read before init" })
            delegate = it
        }
        return reader.read(input, seekPosition)
    }

    override fun seek(position: Long, timeUs: Long) {
        delegate?.seek(position, timeUs)
    }

    override fun release() {
        delegate?.release()
    }

    override fun getUnderlyingImplementation(): Extractor = delegate ?: this
}

/** Mp3Extractor's flags for this file: index seeking unless its first frame names a seek table. */
@OptIn(UnstableApi::class)
private fun flagsFor(input: ExtractorInput): Int {
    val named = try {
        hasSeekHeader(peekHead(input))
    } catch (_: EOFException) {
        false
    } finally {
        input.resetPeekPosition()
    }
    return if (named) 0 else Mp3Extractor.FLAG_ENABLE_INDEX_SEEKING
}

/** How much of a file's start is looked at for its first frame, after any ID3 tags. */
internal const val MP3_HEAD_BYTES = 4096

/** The first [MP3_HEAD_BYTES] after the ID3v2 tags at the file's start (peeked, nothing read). */
@OptIn(UnstableApi::class)
private fun peekHead(input: ExtractorInput): ByteArray {
    val tag = ByteArray(10)
    var skipped = 0
    while (true) {
        if (!input.peekFully(tag, 0, tag.size, true)) break
        val size = id3TagSize(tag) ?: break
        if (!input.advancePeekPosition(size - tag.size, true)) break
        skipped += size
    }
    input.resetPeekPosition()
    if (skipped > 0 && !input.advancePeekPosition(skipped, true)) return ByteArray(0)
    val head = ByteArray(MP3_HEAD_BYTES)
    var got = 0
    while (got < head.size) {
        val n = input.peek(head, got, head.size - got)
        if (n == C.RESULT_END_OF_INPUT) break
        got += n
    }
    return head.copyOf(got)
}

/** The whole size of an ID3v2 tag that starts these 10 bytes, its footer included; null if none. */
internal fun id3TagSize(header: ByteArray): Int? {
    if (header.size < 10) return null
    if (header[0] != 'I'.code.toByte() || header[1] != 'D'.code.toByte() || header[2] != '3'.code.toByte()) return null
    val size = (6..9).map { header[it].toInt() and 0xFF }
    if (size.any { it and 0x80 != 0 }) return null
    val body = (size[0] shl 21) or (size[1] shl 14) or (size[2] shl 7) or size[3]
    val footer = if (header[5].toInt() and 0x10 != 0) 10 else 0
    return 10 + body + footer
}

/**
 * Whether the first MPEG audio frame in [head] carries a Xing or Info header (where LAME and most
 * encoders write the length and a seek table) or a VBRI header (Fraunhofer's). They sit right
 * after the frame's 4-byte header and its side information, whose size depends on the MPEG
 * version and on mono or not; VBRI always sits 32 bytes after the header.
 */
internal fun hasSeekHeader(head: ByteArray): Boolean {
    for (i in 0..head.size - 4) {
        val frame = mpegFrameAt(head, i) ?: continue
        if (!frame.layer3) return false
        val sideInfo = when {
            frame.mpeg1 && !frame.mono -> 32
            frame.mpeg1 -> 17
            !frame.mono -> 17
            else -> 9
        }
        val at = i + 4 + sideInfo
        return tagAt(head, at, "Xing") || tagAt(head, at, "Info") || tagAt(head, i + 4 + 32, "VBRI")
    }
    return false
}

private class MpegFrame(val mpeg1: Boolean, val mono: Boolean, val layer3: Boolean)

/** The MPEG audio frame header at [i], or null if the four bytes there are not a valid one. */
private fun mpegFrameAt(b: ByteArray, i: Int): MpegFrame? {
    val b0 = b[i].toInt() and 0xFF
    val b1 = b[i + 1].toInt() and 0xFF
    val b2 = b[i + 2].toInt() and 0xFF
    val b3 = b[i + 3].toInt() and 0xFF
    if (b0 != 0xFF || b1 and 0xE0 != 0xE0) return null
    val version = (b1 shr 3) and 3 // 3: MPEG 1, 2: MPEG 2, 0: MPEG 2.5, 1: reserved
    val layer = (b1 shr 1) and 3 // 1: Layer III, 2: Layer II, 3: Layer I, 0: reserved
    val bitrate = (b2 shr 4) and 0xF
    val sampleRate = (b2 shr 2) and 3
    if (version == 1 || layer == 0 || bitrate == 0xF || sampleRate == 3) return null
    return MpegFrame(mpeg1 = version == 3, mono = (b3 shr 6) and 3 == 3, layer3 = layer == 1)
}

private fun tagAt(b: ByteArray, at: Int, tag: String): Boolean =
    at >= 0 && at + tag.length <= b.size && tag.indices.all { b[at + it] == tag[it].code.toByte() }
