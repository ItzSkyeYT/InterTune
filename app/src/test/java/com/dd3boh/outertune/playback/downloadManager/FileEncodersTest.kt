/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.playback.downloadManager

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import kotlin.math.PI
import kotlin.math.sin
import kotlin.random.Random

/**
 * The files the formats of Unreleased.DOWNLOAD_FORMATS are written as (MP3, FLAC, WAV): their
 * structure, their tags, and that what comes out holds what went in. MediaCodec's AAC encoder cannot
 * run here; it is proven on the emulator. The files of the last test are left under build/ for the
 * PC's own tools (ffprobe, flac -t) to read.
 */
class FileEncodersTest {
    @get:Rule
    val folder = TemporaryFolder()

    private val tags = SongTags(
        videoId = "dQw4w9WgXcQ",
        title = "Never Gonna Give You Up",
        artists = listOf("Rick Astley"),
        album = "Whenever You Need Somebody",
        year = 1987,
        track = 1,
        cover = Cover.of(jpeg())!!,
    )

    /** A JPEG that is no picture but has a frame header with a size, which is all a cover is read for. */
    private fun jpeg(width: Int = 544, height: Int = 544): ByteArray {
        val body = ByteArray(3000) { (it * 7).toByte() }
        val head = byteArrayOf(
            0xFF.toByte(), 0xD8.toByte(),
            0xFF.toByte(), 0xC0.toByte(), 0, 17, 8, (height shr 8).toByte(), height.toByte(), (width shr 8).toByte(), width.toByte(), 3,
            1, 0x22, 0, 2, 0x11, 1, 3, 0x11, 1,
        )
        return head + body
    }

    /** [seconds] of a different tone in each ear, at [rate] Hz, interleaved. */
    private fun tone(seconds: Double, rate: Int, channels: Int = 2): ShortArray {
        val frames = (seconds * rate).toInt()
        return ShortArray(frames * channels) { i ->
            val frame = i / channels
            val hz = if (i % channels == 0) 440.0 else 660.0
            (sin(2 * PI * hz * frame / rate) * 12000).toInt().toShort()
        }
    }

    private fun feed(encoder: FileEncoder, rate: Int, channels: Int, samples: ShortArray, piece: Int = 960) {
        encoder.start(rate, channels)
        var at = 0
        val frames = samples.size / channels
        while (at < frames) {
            val n = minOf(piece, frames - at)
            // A piece is a reused buffer, as a decoder's are: copy out to a fresh one and use the head of a bigger one.
            val buffer = ShortArray(piece * channels)
            System.arraycopy(samples, at * channels, buffer, 0, n * channels)
            encoder.add(buffer, n)
            at += n
        }
        encoder.finish()
    }

    // ---- WAV

    @Test
    fun `a WAV is PCM in a RIFF file with the tags before the audio`() {
        val file = folder.newFile("song.wav")
        val pcm = tone(1.0, 44100)
        feed(WavFileEncoder(file, tags), 44100, 2, pcm)
        val bytes = file.readBytes()
        val b = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals("RIFF", String(bytes, 0, 4))
        assertEquals("the size of the rest", bytes.size - 8, b.getInt(4))
        assertEquals("WAVE", String(bytes, 8, 4))
        assertEquals("fmt ", String(bytes, 12, 4))
        assertEquals(16, b.getInt(16))
        assertEquals("PCM", 1, b.getShort(20).toInt())
        assertEquals("channels", 2, b.getShort(22).toInt())
        assertEquals("rate", 44100, b.getInt(24))
        assertEquals("bytes a second", 44100 * 4, b.getInt(28))
        assertEquals("bytes a frame", 4, b.getShort(32).toInt())
        assertEquals("bits", 16, b.getShort(34).toInt())
        // The chunks after fmt: the tags, then the data, which is what the file ends with.
        var at = 36
        val chunks = mutableListOf<String>()
        while (at + 8 <= bytes.size) {
            val id = String(bytes, at, 4)
            val size = b.getInt(at + 4)
            chunks += id
            // An even size, so that no pad byte follows: Android's own WAV reader does not skip one.
            assertEquals("the size of the $id chunk", 0, size % 2)
            if (id == "data") {
                assertEquals("the data is the PCM", pcm.size * 2, size)
                assertEquals("the file ends with the data", bytes.size, at + 8 + size)
                val data = ByteBuffer.wrap(bytes, at + 8, size).order(ByteOrder.LITTLE_ENDIAN)
                val read = ShortArray(pcm.size) { data.getShort() }
                assertArrayEquals(pcm, read)
                break
            }
            at += 8 + size + (size and 1)
        }
        assertEquals(listOf("LIST", "id3 ", "data"), chunks)
    }

    @Test
    fun `a WAV carries its tags twice, as INFO and as an ID3 tag with the cover, and the app reads the id back`() {
        val file = folder.newFile("song.wav")
        feed(WavFileEncoder(file, tags), 48000, 2, tone(0.2, 48000))
        val text = String(file.readBytes(), Charsets.ISO_8859_1)
        assertTrue(text.contains("INAM") && text.contains("Never Gonna Give You Up"))
        assertTrue(text.contains("IART") && text.contains("Rick Astley"))
        assertTrue(text.contains("IPRD") && text.contains("Whenever You Need Somebody"))
        assertTrue(text.contains("ICRD") && text.contains("1987"))
        assertTrue(text.contains("ITRK") && text.contains("ICMT"))
        assertTrue("the cover is in the ID3 tag", text.contains("APIC") && text.contains("image/jpeg"))
        assertEquals("dQw4w9WgXcQ", file.inputStream().buffered().use { SongFileIds.read("Rick Astley - Never Gonna Give You Up.wav", it) })
    }

    @Test
    fun `no chunk of a WAV comes to an odd number of bytes, whatever the tags are`() {
        // Titles of every length: Latin-1 and UTF-8, odd and even, with and without a cover.
        for ((title, cover) in listOf("A" to null, "AB" to null, "ABC" to tags.cover, "Été – œuvre" to tags.cover, "Ünï" to null, "x".repeat(33) to tags.cover)) {
            val file = folder.newFile("odd-${title.length}-${cover != null}.wav")
            feed(WavFileEncoder(file, tags.copy(title = title, cover = cover, artists = listOf("Rick Astley"), album = "Whenever You Need Somebody")), 44100, 2, tone(0.1, 44100))
            val bytes = file.readBytes()
            val b = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
            var at = 12
            while (at + 8 <= bytes.size) {
                val id = String(bytes, at, 4)
                val size = b.getInt(at + 4)
                assertEquals("$title: the $id chunk", 0, size % 2)
                if (id == "data") break
                at += 8 + size
            }
            // The tag is still read back, and the audio is where the data chunk says.
            assertEquals("dQw4w9WgXcQ", file.inputStream().buffered().use { SongFileIds.read("a.wav", it) })
        }
    }

    @Test
    fun `a WAV of a mono song is mono, and one over what 16 bits and RIFF hold is refused`() {
        val file = folder.newFile("mono.wav")
        feed(WavFileEncoder(file, tags), 22050, 1, tone(0.5, 22050, channels = 1))
        val b = ByteBuffer.wrap(file.readBytes()).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals(1, b.getShort(22).toInt())
        assertEquals(22050, b.getInt(24))
        assertEquals(2, b.getShort(32).toInt())
        val refused = runCatching { WavFileEncoder(folder.newFile("six.wav"), tags).start(48000, 6) }.exceptionOrNull()
        assertTrue(refused is IllegalArgumentException)
    }

    // ---- MP3

    /** One MPEG audio frame header: what it says of the frame that follows it. */
    private class Mp3Frame(val version: Int, val kbps: Int, val rate: Int, val channelMode: Int, val length: Int, val samples: Int)

    private val kbpsV1 = intArrayOf(0, 32, 40, 48, 56, 64, 80, 96, 112, 128, 160, 192, 224, 256, 320, 0)
    private val kbpsV2 = intArrayOf(0, 8, 16, 24, 32, 40, 48, 56, 64, 80, 96, 112, 128, 144, 160, 0)
    private val ratesV1 = intArrayOf(44100, 48000, 32000, 0)

    private fun mp3Frame(b: ByteArray, at: Int): Mp3Frame? {
        if (at + 4 > b.size) return null
        val h = ((b[at].toInt() and 0xFF) shl 24) or ((b[at + 1].toInt() and 0xFF) shl 16) or ((b[at + 2].toInt() and 0xFF) shl 8) or (b[at + 3].toInt() and 0xFF)
        if (h ushr 21 != 0x7FF) return null
        val version = h ushr 19 and 3          // 3 is MPEG 1, 2 is MPEG 2, 0 is MPEG 2.5
        val layer = h ushr 17 and 3            // 1 is layer III
        if (layer != 1 || version == 1) return null
        val rateIndex = h ushr 10 and 3
        val kbps = (if (version == 3) kbpsV1 else kbpsV2)[h ushr 12 and 15]
        val rate = ratesV1[rateIndex] / (if (version == 3) 1 else if (version == 2) 2 else 4)
        val padding = h ushr 9 and 1
        val samples = if (version == 3) 1152 else 576
        val length = (if (version == 3) 144_000 else 72_000) * kbps / rate + padding
        if (kbps == 0 || rate == 0) return null
        return Mp3Frame(version, kbps, rate, h ushr 6 and 3, length, samples)
    }

    /** Where the MP3 frames start in a file that begins with an ID3 tag, and what the walk through them found. */
    private fun mp3Walk(bytes: ByteArray): Triple<Int, List<Mp3Frame>, Int> {
        assertEquals("ID3", String(bytes, 0, 3))
        val tagSize = 10 + ((bytes[6].toInt() and 0x7F) shl 21 or ((bytes[7].toInt() and 0x7F) shl 14) or ((bytes[8].toInt() and 0x7F) shl 7) or (bytes[9].toInt() and 0x7F))
        var at = tagSize
        val frames = mutableListOf<Mp3Frame>()
        while (true) {
            val frame = mp3Frame(bytes, at) ?: break
            if (at + frame.length > bytes.size) break
            frames += frame
            at += frame.length
        }
        return Triple(tagSize, frames, at)
    }

    @Test
    fun `an MP3 at 256 kbps from 44100 Hz stereo is a tag then nothing but frames of that rate`() {
        val file = folder.newFile("song.mp3")
        feed(Mp3FileEncoder(file, tags, 256), 44100, 2, tone(3.0, 44100))
        val bytes = file.readBytes()
        val (tagSize, frames, end) = mp3Walk(bytes)
        assertEquals("the frames end with the file", bytes.size, end)
        // The audio, the frame LAME adds after it to flush, and the Info frame in front of them all.
        assertTrue("${frames.size} frames", frames.size >= 3 * 44100 / 1152 && frames.size <= 3 * 44100 / 1152 + 5)
        assertTrue(frames.all { it.version == 3 && it.rate == 44100 && it.kbps == 256 })
        assertTrue("a stereo song is not mono", frames.all { it.channelMode != 3 })
        assertEquals("the first frame follows the tag at once", tagSize, tagSize.also { assertNotNull(mp3Frame(bytes, it)) })
        assertEquals("dQw4w9WgXcQ", file.inputStream().buffered().use { SongFileIds.read("a.mp3", it) })
    }

    private fun assertNotNull(frame: Mp3Frame?) = assertTrue(frame != null)

    @Test
    fun `the first frame of an MP3 is LAME's Info frame, with the number of frames and the encoder's delay`() {
        val file = folder.newFile("info.mp3")
        feed(Mp3FileEncoder(file, tags, 192), 48000, 2, tone(2.0, 48000))
        val bytes = file.readBytes()
        val (tagSize, frames, end) = mp3Walk(bytes)
        assertEquals(bytes.size, end)
        val first = frames.first()
        // "Info" for a constant rate, "Xing" for a variable one, after the header and the side information.
        val head = String(bytes, tagSize, first.length, Charsets.ISO_8859_1)
        assertTrue("no Info in the first frame", head.substring(4, 60).contains("Info"))
        assertFalse(head.substring(4, 60).contains("Xing"))
        assertTrue("LAME names itself in it", head.contains("LAME"))
        val at = tagSize + head.indexOf("Info")
        val b = ByteBuffer.wrap(bytes)
        assertEquals("the flags: frames, bytes, TOC and quality", 0xF, b.getInt(at + 4))
        assertEquals("the frames after the Info frame", frames.size - 1, b.getInt(at + 8))
        assertEquals("the size of the stream, the Info frame in it", bytes.size - tagSize, b.getInt(at + 12))
        // After "Info", the flags, the frames, the bytes and the 100-entry TOC: the quality (4 bytes), the encoder's name (9),
        // the revision and method, the lowpass, the peak (4), the two replay gains (2 each), a flags byte, the ABR rate (1),
        // and then the delay and the padding, 12 bits each.
        val delayAt = at + 4 + 4 + 4 + 4 + 100 + 4 + 9 + 1 + 1 + 4 + 2 + 2 + 1 + 1
        val packed = ((bytes[delayAt].toInt() and 0xFF) shl 16) or ((bytes[delayAt + 1].toInt() and 0xFF) shl 8) or (bytes[delayAt + 2].toInt() and 0xFF)
        val delay = packed shr 12
        val padding = packed and 0xFFF
        assertEquals("LAME's own delay", 576, delay)
        // What the last frame holds beyond the audio: the audio is the frames less the delay, the padding and the Info frame.
        val audio = (frames.size - 1) * 1152 - delay - padding
        assertTrue("$audio samples for ${2 * 48000}", audio in (2 * 48000 - 1152)..(2 * 48000 + 1152))
    }

    @Test
    fun `an MP3 keeps the rate of the song, 48000 as well as 44100`() {
        for ((rate, kbps) in listOf(48000 to 128, 44100 to 320, 32000 to 192)) {
            val file = folder.newFile("song-$rate.mp3")
            feed(Mp3FileEncoder(file, tags, kbps), rate, 2, tone(1.0, rate))
            val (_, frames, end) = mp3Walk(file.readBytes())
            assertEquals(file.length().toInt(), end)
            assertTrue("$rate Hz at $kbps", frames.isNotEmpty() && frames.all { it.rate == rate && it.kbps == kbps })
        }
    }

    @Test
    fun `a mono song is a mono MP3`() {
        val file = folder.newFile("mono.mp3")
        feed(Mp3FileEncoder(file, tags, 128), 44100, 1, tone(1.0, 44100, channels = 1))
        val (_, frames, end) = mp3Walk(file.readBytes())
        assertEquals(file.length().toInt(), end)
        assertTrue(frames.isNotEmpty() && frames.all { it.channelMode == 3 })
    }

    @Test
    fun `a low sample rate takes the rates MPEG 2 has, so 256 kbps becomes the most it offers`() {
        val file = folder.newFile("low.mp3")
        val encoder = Mp3FileEncoder(file, tags, 256)
        feed(encoder, 22050, 2, tone(1.0, 22050))
        val (_, frames, end) = mp3Walk(file.readBytes())
        assertEquals(file.length().toInt(), end)
        assertTrue(frames.isNotEmpty() && frames.all { it.version == 2 && it.rate == 22050 })
        assertTrue("${frames.first().kbps} kbps", frames.all { it.kbps in 8..160 })
        assertEquals(frames.first().kbps, encoder.effectiveKbps)
    }

    @Test
    fun `an MP3 is as long as the song`() {
        val file = folder.newFile("long.mp3")
        val seconds = 2.5
        feed(Mp3FileEncoder(file, tags, 192), 48000, 2, tone(seconds, 48000), piece = 1000)
        val (_, frames, _) = mp3Walk(file.readBytes())
        // The Info frame is no audio: what is left of the frames is the song, a frame or two over, which the tag trims.
        val encoded = (frames.size - 1) * 1152 / 48000.0
        assertTrue("$encoded s for $seconds s", encoded >= seconds && encoded < seconds + 0.1)
    }

    @Test
    fun `more than two channels is refused by the MP3 writer`() {
        val failed = runCatching { Mp3FileEncoder(folder.newFile("x.mp3"), tags, 128).start(48000, 3) }.exceptionOrNull()
        assertTrue(failed is IllegalArgumentException)
    }

    // ---- FLAC

    @Test
    fun `a FLAC decodes to exactly what went in, stereo`() {
        val file = folder.newFile("song.flac")
        // A tone, some noise, a stretch of silence, a stretch of full scale: every kind of block there is.
        val random = Random(7)
        val frames = 4096 * 5 + 1234
        val pcm = ShortArray(frames * 2) { i ->
            val f = i / 2
            when {
                f < 4096 -> (sin(2 * PI * 440 * f / 44100) * 9000).toInt().toShort()
                f < 8192 -> random.nextInt(-20000, 20000).toShort()
                f < 12288 -> 0
                f < 16384 -> if ((f / 3) % 2 == 0) Short.MAX_VALUE else Short.MIN_VALUE
                else -> ((sin(2 * PI * 330 * f / 44100) * 5000) + (i % 2) * 400 + random.nextInt(-30, 30)).toInt().toShort()
            }
        }
        feed(FlacFileEncoder(file, tags), 44100, 2, pcm, piece = 1500)
        val decoded = FlacDecoder(file.readBytes())
        assertEquals(44100, decoded.rate)
        assertEquals(2, decoded.channels)
        assertEquals(frames.toLong(), decoded.totalSamples)
        assertArrayEquals(pcm, decoded.samples)
    }

    @Test
    fun `a FLAC of a mono song decodes to what went in`() {
        val file = folder.newFile("mono.flac")
        val pcm = tone(1.5, 48000, channels = 1)
        feed(FlacFileEncoder(file, tags), 48000, 1, pcm, piece = 777)
        val decoded = FlacDecoder(file.readBytes())
        assertEquals(1, decoded.channels)
        assertArrayEquals(pcm, decoded.samples)
    }

    @Test
    fun `a FLAC predicts a tone with a linear predictor where it pays, and decodes to what went in`() {
        val file = folder.newFile("lpc.flac")
        val random = Random(11)
        // Two harmonics and a little noise in each ear, the shape music has and a fixed predictor handles poorly.
        val frames = 4096 * 6
        val pcm = ShortArray(frames * 2) { i ->
            val f = i / 2
            val x = 7000 * sin(2 * PI * 220 * f / 44100) + 3000 * sin(2 * PI * 1330 * f / 44100 + (i % 2)) + 800 * sin(2 * PI * 5100 * f / 44100)
            (x + random.nextInt(-25, 25)).toInt().toShort()
        }
        feed(FlacFileEncoder(file, tags.copy(cover = null)), 44100, 2, pcm, piece = 1000)
        val decoded = FlacDecoder(file.readBytes())
        assertArrayEquals(pcm, decoded.samples)
        assertTrue("${decoded.lpcSubframes} linear, ${decoded.fixedSubframes} fixed", decoded.lpcSubframes > 0 && decoded.lpcSubframes >= decoded.fixedSubframes)
        // The noise alone, 50 values wide, takes 5.6 bits of the 16 in a sample, a third; the tones and the headers are the rest.
        assertTrue("${file.length()} bytes for ${pcm.size * 2}", file.length() < pcm.size * 2 * 0.47)
    }

    @Test
    fun `a song shorter than a block, and one that is a whole number of blocks, are whole`() {
        for (frames in listOf(1, 17, 4095, 4096, 8192)) {
            val file = folder.newFile("short-$frames.flac")
            val pcm = ShortArray(frames * 2) { ((it * 31) % 2000 - 1000).toShort() }
            feed(FlacFileEncoder(file, tags), 44100, 2, pcm, piece = 5000)
            val decoded = FlacDecoder(file.readBytes())
            assertEquals("$frames frames", frames.toLong(), decoded.totalSamples)
            assertArrayEquals("$frames frames", pcm, decoded.samples)
        }
    }

    @Test
    fun `the stream info says what is in the file, and the MD5 is the audio's`() {
        val file = folder.newFile("info.flac")
        val pcm = tone(2.0, 48000)
        feed(FlacFileEncoder(file, tags), 48000, 2, pcm)
        val decoded = FlacDecoder(file.readBytes())
        assertEquals(4096, decoded.minBlock)
        assertEquals(4096, decoded.maxBlock)
        assertTrue("frame sizes ${decoded.minFrame}..${decoded.maxFrame}", decoded.minFrame in 1..decoded.maxFrame && decoded.maxFrame > 0)
        assertEquals(decoded.frameSizes.min(), decoded.minFrame)
        assertEquals(decoded.frameSizes.max(), decoded.maxFrame)
        val md5 = MessageDigest.getInstance("MD5")
        md5.update(ByteBuffer.allocate(pcm.size * 2).order(ByteOrder.LITTLE_ENDIAN).also { b -> pcm.forEach { b.putShort(it) } }.array())
        assertArrayEquals(md5.digest(), decoded.md5)
    }

    @Test
    fun `a FLAC compresses a tone, and noise is no bigger than the audio plus a little`() {
        val tonal = folder.newFile("tonal.flac")
        val pcm = tone(2.0, 44100)
        feed(FlacFileEncoder(tonal, tags.copy(cover = null)), 44100, 2, pcm)
        assertTrue("${tonal.length()} bytes for ${pcm.size * 2}", tonal.length() < pcm.size * 2 / 3)
        val noisy = folder.newFile("noisy.flac")
        val random = Random(3)
        val noise = ShortArray(44100 * 2) { random.nextInt(-32768, 32768).toShort() }
        feed(FlacFileEncoder(noisy, tags.copy(cover = null)), 44100, 2, noise)
        assertTrue("${noisy.length()} bytes for ${noise.size * 2}", noisy.length() < noise.size * 2 * 1.02 + 2000)
    }

    @Test
    fun `a FLAC holds the tags as Vorbis comments and the cover as a picture, the link before the cover`() {
        val file = folder.newFile("tagged.flac")
        feed(FlacFileEncoder(file, tags), 44100, 2, tone(0.3, 44100))
        val decoded = FlacDecoder(file.readBytes())
        assertEquals(listOf(0, 4, 6), decoded.blockTypes)
        assertEquals(
            listOf(
                "TITLE=Never Gonna Give You Up", "ARTIST=Rick Astley", "ALBUM=Whenever You Need Somebody", "DATE=1987", "YEAR=1987",
                "TRACKNUMBER=1", "COMMENT=https://music.youtube.com/watch?v=dQw4w9WgXcQ",
            ),
            decoded.comments,
        )
        assertEquals("the picture is the front cover", 3, decoded.pictureType)
        assertEquals("image/jpeg", decoded.pictureMime)
        assertEquals(544, decoded.pictureWidth)
        assertTrue(file.inputStream().buffered().use { SongFileIds.read("a.flac", it) } == "dQw4w9WgXcQ")
    }

    @Test
    fun `a FLAC with no cover has no picture block, and one with no link has no comment of it`() {
        val file = folder.newFile("plain.flac")
        feed(FlacFileEncoder(file, tags.copy(cover = null, keepLink = false)), 44100, 2, tone(0.3, 44100))
        val decoded = FlacDecoder(file.readBytes())
        assertEquals(listOf(0, 4), decoded.blockTypes)
        assertFalse(decoded.comments.any { it.startsWith("COMMENT=") })
        assertNull(file.inputStream().buffered().use { SongFileIds.read("a.flac", it) })
    }

    @Test
    fun `a FLAC refuses more than two channels`() {
        val failed = runCatching { FlacFileEncoder(folder.newFile("x.flac"), tags).start(48000, 3) }.exceptionOrNull()
        assertTrue(failed is IllegalArgumentException)
    }

    // ---- the tags

    @Test
    fun `an ID3 tag has the title, the artists, the album, the year, the track, the link and the cover, in that order`() {
        val bytes = Id3v2.tag(tags)
        assertEquals("ID3", String(bytes, 0, 3))
        assertEquals(3, bytes[3].toInt())
        val size = ((bytes[6].toInt() and 0x7F) shl 21) or ((bytes[7].toInt() and 0x7F) shl 14) or ((bytes[8].toInt() and 0x7F) shl 7) or (bytes[9].toInt() and 0x7F)
        assertEquals(bytes.size - 10, size)
        val ids = mutableListOf<String>()
        var at = 10
        while (at < bytes.size) {
            ids += String(bytes, at, 4)
            at += 10 + ByteBuffer.wrap(bytes, at + 4, 4).int
        }
        assertEquals(bytes.size, at)
        assertEquals(listOf("TIT2", "TPE1", "TALB", "TYER", "TRCK", "COMM", "APIC"), ids)
        val short = Id3v2.tag(SongTags("dQw4w9WgXcQ", "Song", keepLink = false))
        assertEquals("TIT2", String(short, 10, 4))
        assertFalse(String(short, Charsets.ISO_8859_1).contains("COMM"))
    }

    @Test
    fun `text Latin-1 cannot hold is UTF-16, and reads back`() {
        val named = SongTags("dQw4w9WgXcQ", "Été – œuvre “live”", listOf("Sigur Rós"))
        val bytes = Id3v2.tag(named)
        // TIT2: encoding 1, then a byte order mark and the title in UTF-16LE.
        assertEquals(1, bytes[20].toInt())
        assertEquals(0xFF, bytes[21].toInt() and 0xFF)
        assertEquals(0xFE, bytes[22].toInt() and 0xFF)
        assertEquals("Été – œuvre “live”", String(bytes, 23, 2 * "Été – œuvre “live”".length, Charsets.UTF_16LE))
        // Sigur Rós fits in Latin-1: encoding 0.
        val at = 10 + 10 + ByteBuffer.wrap(bytes, 14, 4).int
        assertEquals("TPE1", String(bytes, at, 4))
        assertEquals(0, bytes[at + 10].toInt())
        assertEquals("Sigur Rós", String(bytes, at + 11, 9, Charsets.ISO_8859_1))
        assertEquals("dQw4w9WgXcQ", SongFileIds.fromId3(bytes.inputStream()))
    }

    @Test
    fun `the link is read back from an ID3 tag by the first bytes alone, before the cover`() {
        val bytes = Id3v2.tag(tags.copy(cover = Cover.of(jpeg())))
        val counted = object : java.io.ByteArrayInputStream(bytes) {
            var read = 0
            override fun read(b: ByteArray, off: Int, len: Int): Int = super.read(b, off, len).also { if (it > 0) read += it }
            override fun skip(n: Long): Long = super.skip(n).also { read += it.toInt() }
        }
        assertEquals("dQw4w9WgXcQ", SongFileIds.fromId3(counted))
        assertTrue("read ${counted.read} of ${bytes.size}", counted.read < 400)
    }

    @Test
    fun `an ID3 tag of another program's is read too, a version 4 tag with a user text`() {
        fun frame4(id: String, body: ByteArray) = id.toByteArray() + Id3v2.synchsafe(body.size) + byteArrayOf(0, 0) + body
        val txxx = frame4("TXXX", byteArrayOf(3) + "purl".toByteArray() + byteArrayOf(0) + "https://www.youtube.com/watch?v=abcdefghijk".toByteArray())
        val title = frame4("TIT2", byteArrayOf(3) + "Song".toByteArray())
        val body = title + txxx
        val tag = "ID3".toByteArray() + byteArrayOf(4, 0, 0) + Id3v2.synchsafe(body.size) + body
        assertEquals("abcdefghijk", SongFileIds.fromId3(tag.inputStream()))
        assertNull(SongFileIds.fromId3("ID3".toByteArray().inputStream()))
        assertNull(SongFileIds.fromId3(ByteArray(40) { 1 }.inputStream()))
    }

    // ---- left for the PC's own tools

    @Test
    fun `the files written are kept under build for ffprobe and flac to read`() {
        val out = File("build/format-samples").also { it.mkdirs() }
        val pcm = tone(4.0, 44100)
        feed(Mp3FileEncoder(File(out, "sample.mp3"), tags, 256), 44100, 2, pcm)
        feed(FlacFileEncoder(File(out, "sample.flac"), tags), 44100, 2, pcm)
        feed(WavFileEncoder(File(out, "sample.wav"), tags), 44100, 2, pcm)
        feed(Mp3FileEncoder(File(out, "sample-48k-mono.mp3"), tags.copy(keepLink = false), 128), 48000, 1, tone(2.0, 48000, 1))
        assertTrue(out.listFiles()!!.all { it.length() > 1000 })
    }
}
