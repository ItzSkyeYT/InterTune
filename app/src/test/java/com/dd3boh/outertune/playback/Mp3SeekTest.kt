/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.playback

import androidx.media3.common.C
import androidx.media3.common.DataReader
import androidx.media3.common.Format
import androidx.media3.common.util.ParsableByteArray
import androidx.media3.common.util.UnstableApi
import androidx.media3.extractor.Extractor
import androidx.media3.extractor.ExtractorInput
import androidx.media3.extractor.ExtractorOutput
import androidx.media3.extractor.PositionHolder
import androidx.media3.extractor.SeekMap
import androidx.media3.extractor.TrackOutput
import androidx.media3.extractor.flac.FlacExtractor
import androidx.media3.extractor.mp3.Mp3Extractor
import androidx.media3.extractor.ogg.OggExtractor
import androidx.media3.extractor.ts.AdtsExtractor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.EOFException
import java.io.File

/**
 * A variable-bitrate MP3 with no Xing or VBRI header, seen through media3's own Mp3Extractor. The
 * two files hold the same 180 s of audio, one with the header and one without. The test runs the
 * extractor with and without index seeking, and the player's own factory (seekableMp3Extractors).
 */
@UnstableApi
class Mp3SeekTest {

    private fun bytes(name: String): ByteArray =
        File(javaClass.classLoader!!.getResource("mp3/$name")!!.toURI()).readBytes()

    private class Result(val seekMap: SeekMap, val read: Long)

    /** Reads the whole file through an Mp3Extractor, then returns what it ended up knowing. */
    private fun run(data: ByteArray, flags: Int, readTo: Long = Long.MAX_VALUE): Result =
        run(data, Mp3Extractor(flags), readTo)

    /** The same through any extractor; [sniffFirst] sniffs it first, as media3 does when it has several. */
    private fun run(data: ByteArray, extractor: Extractor, readTo: Long = Long.MAX_VALUE, sniffFirst: Boolean = false): Result {
        var map: SeekMap? = null
        val output = object : ExtractorOutput {
            override fun track(id: Int, type: Int): TrackOutput = SinkTrack()
            override fun endTracks() {}
            override fun seekMap(seekMap: SeekMap) { map = seekMap }
        }
        val input = MemoryInput(data)
        if (sniffFirst) {
            assertTrue(extractor.sniff(input))
            input.resetPeekPosition()
        }
        extractor.init(output)
        val holder = PositionHolder()
        while (input.pos < readTo) {
            val r = extractor.read(input, holder)
            if (r == Extractor.RESULT_END_OF_INPUT) break
            if (r == Extractor.RESULT_SEEK) input.pos = holder.position
        }
        return Result(map!!, input.pos)
    }

    @Test
    fun evidence() {
        for (name in listOf("chorus-vbr-xing.mp3", "chorus-vbr-noxing.mp3")) {
            for (flags in listOf(0, Mp3Extractor.FLAG_ENABLE_INDEX_SEEKING)) {
                val r = run(bytes(name), flags)
                val at120 = r.seekMap.getSeekPoints(120_000_000L).first
                println("EVIDENCE $name flags=$flags seekable=${r.seekMap.isSeekable} duration=${r.seekMap.durationUs} us seek(120s)->time=${at120.timeUs} pos=${at120.position}")
            }
        }
    }

    @Test
    fun evidenceBeforeTheWholeFileIsRead() {
        val data = bytes("chorus-vbr-noxing.mp3")
        for (readTo in listOf(20_000L, 200_000L, 600_000L)) {
            val r = run(data, Mp3Extractor.FLAG_ENABLE_INDEX_SEEKING, readTo)
            val p = r.seekMap.getSeekPoints(120_000_000L).first
            println("EVIDENCE-PARTIAL read to ${r.read} of ${data.size} bytes: duration=${r.seekMap.durationUs} us seek(120s)->time=${p.timeUs} pos=${p.position}")
        }
    }

    @Test
    fun withHeaderTheFileIsSeekableAndFullLength() {
        val r = run(bytes("chorus-vbr-xing.mp3"), 0)
        assertTrue(r.seekMap.isSeekable)
        assertEquals(180_000_000.0, r.seekMap.durationUs.toDouble(), 1_000_000.0)
    }

    @Test
    fun withoutHeaderAndWithoutTheFlagTheLengthIsGuessedWrongAndSeeksGoNowhere() {
        val r = run(bytes("chorus-vbr-noxing.mp3"), 0)
        // 48 s for a 180 s song, and a seek to 2:00 lands at the guessed end.
        assertTrue(r.seekMap.durationUs < 60_000_000L)
        assertTrue(r.seekMap.getSeekPoints(120_000_000L).first.timeUs < 60_000_000L)
    }

    @Test
    fun withoutHeaderIndexSeekingMakesItSeekableAtTheRightPlace() {
        val data = bytes("chorus-vbr-noxing.mp3")
        val r = run(data, Mp3Extractor.FLAG_ENABLE_INDEX_SEEKING)
        assertTrue(r.seekMap.isSeekable)
        assertEquals(180_000_000.0, r.seekMap.durationUs.toDouble(), 1_000_000.0)
        val p = r.seekMap.getSeekPoints(120_000_000L).first
        assertEquals(120_000_000.0, p.timeUs.toDouble(), 1_000_000.0)
    }

    private fun seekerName(r: Result) = r.seekMap.javaClass.simpleName

    @Test
    fun thePlayersFactoryReadsAnMp3WithoutHeaderByIndexAndFindsItsLength() {
        for (sniff in listOf(false, true)) {
            val r = run(bytes("chorus-vbr-noxing.mp3"), PerFileMp3Extractor(), sniffFirst = sniff)
            assertEquals("IndexSeeker", seekerName(r))
            assertTrue(r.seekMap.isSeekable)
            assertEquals(180_000_000.0, r.seekMap.durationUs.toDouble(), 1_000_000.0)
            assertEquals(120_000_000.0, r.seekMap.getSeekPoints(120_000_000L).first.timeUs.toDouble(), 1_000_000.0)
        }
    }

    @Test
    fun thePlayersFactoryLeavesAnMp3WithAHeaderToItsOwnSeekTable() {
        for (sniff in listOf(false, true)) {
            val r = run(bytes("chorus-vbr-xing.mp3"), PerFileMp3Extractor(), sniffFirst = sniff)
            assertFalse(seekerName(r) == "IndexSeeker")
            assertTrue(r.seekMap.isSeekable)
            assertEquals(180_000_000.0, r.seekMap.durationUs.toDouble(), 1_000_000.0)
        }
    }

    @Test
    fun thePlayersFactorySwapsOnlyTheMp3Extractor() {
        // media3's own list needs a real Uri (Uri.EMPTY is null off a phone), so the swap is checked on a list like it.
        val media3s = arrayOf<Extractor>(FlacExtractor(), Mp3Extractor(), AdtsExtractor(), OggExtractor())
        val ours = swapMp3Extractor(media3s)
        assertEquals(media3s.size, ours.size)
        assertTrue(ours[1] is PerFileMp3Extractor)
        assertEquals(0, ours.count { it is Mp3Extractor })
        assertTrue(ours[0] === media3s[0] && ours[2] === media3s[2] && ours[3] === media3s[3])
        // Both of media3's ways in go through the swap.
        val src = File("src/main/java/com/dd3boh/outertune/playback/SeekableMp3.kt").readText()
        assertTrue(src.contains("override fun createExtractors(): Array<Extractor> = swap(inner.createExtractors())"))
        assertTrue(src.contains("swap(inner.createExtractors(uri, responseHeaders))"))
    }

    /** An MPEG 1 Layer III frame header, 128 kbps at 44.1 kHz, stereo or mono, then zeros. */
    private fun frame(mono: Boolean, size: Int = 417): ByteArray =
        ByteArray(size).also { it[0] = 0xFF.toByte(); it[1] = 0xFB.toByte(); it[2] = 0x90.toByte(); it[3] = (if (mono) 0xC4 else 0x64).toByte() }

    /** The same in MPEG 2 (22.05 kHz). */
    private fun frameMpeg2(mono: Boolean): ByteArray =
        ByteArray(300).also { it[0] = 0xFF.toByte(); it[1] = 0xF3.toByte(); it[2] = 0x90.toByte(); it[3] = (if (mono) 0xC4 else 0x64).toByte() }

    private fun ByteArray.with(at: Int, tag: String) = also { tag.forEachIndexed { k, c -> this[at + k] = c.code.toByte() } }

    @Test
    fun theSeekHeaderIsFoundWhereEachKindOfFrameKeepsIt() {
        assertTrue(hasSeekHeader(frame(mono = false).with(36, "Xing")))
        assertTrue(hasSeekHeader(frame(mono = false).with(36, "Info")))
        assertTrue(hasSeekHeader(frame(mono = true).with(21, "Xing")))
        assertTrue(hasSeekHeader(frameMpeg2(mono = false).with(21, "Info")))
        assertTrue(hasSeekHeader(frameMpeg2(mono = true).with(13, "Xing")))
        assertTrue(hasSeekHeader(frame(mono = false).with(36, "VBRI")))
        // A few stray bytes before the first frame.
        assertTrue(hasSeekHeader(ByteArray(7) + frame(mono = false).with(36, "Xing")))
    }

    @Test
    fun noSeekHeaderMeansIndexSeeking() {
        assertFalse(hasSeekHeader(frame(mono = false)))
        // The tag in the wrong place for its frame (a mono frame's place in a stereo one).
        assertFalse(hasSeekHeader(frame(mono = false).with(21, "Xing")))
        assertFalse(hasSeekHeader(ByteArray(0)))
        assertFalse(hasSeekHeader(ByteArray(400)))
        // Layer II (an MP2) has no such header.
        val mp2 = frame(mono = false).also { it[1] = 0xFD.toByte() }.with(36, "Xing")
        assertFalse(hasSeekHeader(mp2))
    }

    @Test
    fun anId3TagIsMeasuredWithItsFooter() {
        val tag = byteArrayOf('I'.code.toByte(), 'D'.code.toByte(), '3'.code.toByte(), 4, 0, 0, 0, 0, 2, 1)
        assertEquals(10 + 257, id3TagSize(tag))
        assertEquals(10 + 257 + 10, id3TagSize(tag.copyOf().also { it[5] = 0x10 }))
        assertNull(id3TagSize(tag.copyOf().also { it[8] = 0x80.toByte() }))
        assertNull(id3TagSize(frame(mono = false).copyOf(10)))
    }

    @Test
    fun everyLocalFileReaderUsesTheFactory() {
        val root = File("src/main/java/com/dd3boh/outertune")
        fun src(p: String) = File(root, p).readText()
        assertTrue(src("playback/MusicService.kt").contains("DefaultMediaSourceFactory(playerSource, seekableMp3Extractors())"))
        assertTrue(src("recap/RecapPlayer.kt").contains("seekableMp3Extractors()"))
        assertTrue(src("playback/automix/HeadDecoder.kt").contains("seekableMp3Extractors()"))
        assertTrue(src("ui/screens/mix/WindowDecoder.kt").contains("seekableMp3Extractors()"))
        assertTrue(src("lyrics/LyricsVideo.kt").contains("seekableMp3Extractors()"))
    }

    private class SinkTrack : TrackOutput {
        override fun format(format: Format) {}
        override fun sampleData(input: DataReader, length: Int, allowEndOfInput: Boolean, sampleDataPart: Int): Int {
            val buf = ByteArray(length)
            val n = input.read(buf, 0, length)
            return n
        }
        override fun sampleData(data: ParsableByteArray, length: Int, sampleDataPart: Int) { data.skipBytes(length) }
        override fun sampleMetadata(timeUs: Long, flags: Int, size: Int, offset: Int, cryptoData: TrackOutput.CryptoData?) {}
    }

    private class MemoryInput(private val data: ByteArray) : ExtractorInput {
        var pos = 0L
        private var peek = 0L
        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            if (pos >= data.size) return C.RESULT_END_OF_INPUT
            val n = minOf(length.toLong(), data.size - pos).toInt()
            System.arraycopy(data, pos.toInt(), buffer, offset, n)
            pos += n; peek = pos
            return n
        }
        override fun readFully(target: ByteArray, offset: Int, length: Int, allowEndOfInput: Boolean): Boolean {
            if (pos + length > data.size) {
                if (allowEndOfInput && pos >= data.size) return false
                pos = data.size.toLong(); peek = pos
                throw EOFException()
            }
            System.arraycopy(data, pos.toInt(), target, offset, length)
            pos += length; peek = pos
            return true
        }
        override fun readFully(target: ByteArray, offset: Int, length: Int) { readFully(target, offset, length, false) }
        override fun skip(length: Int): Int {
            if (pos >= data.size) return C.RESULT_END_OF_INPUT
            val n = minOf(length.toLong(), data.size - pos).toInt()
            pos += n; peek = pos
            return n
        }
        override fun skipFully(length: Int, allowEndOfInput: Boolean): Boolean {
            if (pos + length > data.size) {
                if (allowEndOfInput && pos >= data.size) return false
                pos = data.size.toLong(); peek = pos
                throw EOFException()
            }
            pos += length; peek = pos
            return true
        }
        override fun skipFully(length: Int) { skipFully(length, false) }
        override fun peek(target: ByteArray, offset: Int, length: Int): Int {
            if (peek >= data.size) return C.RESULT_END_OF_INPUT
            val n = minOf(length.toLong(), data.size - peek).toInt()
            System.arraycopy(data, peek.toInt(), target, offset, n)
            peek += n
            return n
        }
        override fun peekFully(target: ByteArray, offset: Int, length: Int, allowEndOfInput: Boolean): Boolean {
            if (peek + length > data.size) {
                if (allowEndOfInput && peek >= data.size) return false
                throw EOFException()
            }
            System.arraycopy(data, peek.toInt(), target, offset, length)
            peek += length
            return true
        }
        override fun peekFully(target: ByteArray, offset: Int, length: Int) { peekFully(target, offset, length, false) }
        override fun advancePeekPosition(length: Int, allowEndOfInput: Boolean): Boolean {
            if (peek + length > data.size) {
                if (allowEndOfInput && peek >= data.size) return false
                throw EOFException()
            }
            peek += length
            return true
        }
        override fun advancePeekPosition(length: Int) { advancePeekPosition(length, false) }
        override fun resetPeekPosition() { peek = pos }
        override fun getPeekPosition() = peek
        override fun getPosition() = pos
        override fun getLength() = data.size.toLong()
        override fun <E : Throwable> setRetryPosition(position: Long, e: E) { this.pos = position; peek = position; throw e }
    }
}
