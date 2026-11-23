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
import androidx.media3.extractor.ExtractorInput
import androidx.media3.extractor.ExtractorOutput
import androidx.media3.extractor.PositionHolder
import androidx.media3.extractor.SeekMap
import androidx.media3.extractor.TrackOutput
import androidx.media3.extractor.mp3.Mp3Extractor
import org.junit.Assert.assertEquals
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
    private fun run(data: ByteArray, flags: Int, readTo: Long = Long.MAX_VALUE): Result {
        val extractor = Mp3Extractor(flags)
        var map: SeekMap? = null
        val output = object : ExtractorOutput {
            override fun track(id: Int, type: Int): TrackOutput = SinkTrack()
            override fun endTracks() {}
            override fun seekMap(seekMap: SeekMap) { map = seekMap }
        }
        extractor.init(output)
        val input = MemoryInput(data)
        val holder = PositionHolder()
        while (input.pos < readTo) {
            val r = extractor.read(input, holder)
            if (r == androidx.media3.extractor.Extractor.RESULT_END_OF_INPUT) break
            if (r == androidx.media3.extractor.Extractor.RESULT_SEEK) input.pos = holder.position
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

    @Test
    fun thePlayersFactoryTurnsIndexSeekingOn() {
        val factory = seekableMp3Extractors()
        // What the flag does is the test above; here the factory is checked by the field it was given.
        val field = factory.javaClass.getDeclaredField("mp3Flags").apply { isAccessible = true }
        assertTrue(field.getInt(factory) and Mp3Extractor.FLAG_ENABLE_INDEX_SEEKING != 0)
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
