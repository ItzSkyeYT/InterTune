/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.playback.downloadManager

import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * Not a test: the two writers fed real Opus and real AAC, for ffprobe and ffmpeg to judge. For every
 * .opus (Ogg Opus) and .aac (ADTS) in the folder SONG_FILES names, the same packets written again,
 * with tags and the folder's cover.jpg, as name.out.opus and name.out.m4a:
 *
 *     ffmpeg -f lavfi -i sine=440:duration=5 -ac 2 -c:a libopus -b:a 96k in.opus
 *     ffmpeg -f lavfi -i sine=440:duration=5 -ac 2 -c:a aac -b:a 128k in.aac
 *     ffmpeg -f lavfi -i color=c=orange:s=544x544 -frames:v 1 cover.jpg
 */
class SongFileTrial {
    private val rates = intArrayOf(96000, 88200, 64000, 48000, 44100, 32000, 24000, 22050, 16000, 12000, 11025, 8000, 7350)

    @Test
    fun rewrap() {
        val folder = System.getenv("SONG_FILES"); assumeTrue("set SONG_FILES to run", !folder.isNullOrBlank())
        val jpeg = File(folder!!, "cover.jpg").readBytes()
        val tags = SongTags("dQw4w9WgXcQ", "A tone", listOf("The Trial", "Sine"), "Tests", 2026, 2, Cover.of(jpeg))
        for (file in File(folder).listFiles()!!.filter { !it.name.contains(".out.") }) {
            when (file.extension) {
                "opus" -> {
                    val ogg = OggOpusWriterTest.OggRead(file.readBytes())
                    val out = File(file.parentFile, file.nameWithoutExtension + ".out.opus")
                    out.outputStream().buffered().use { stream ->
                        OggOpusWriter(stream, 77).apply {
                            writeHeaders(ogg.packets[0], OggOpusWriter.opusTags("InterTune", tags.vorbisComments()))
                            for (p in ogg.packets.drop(2)) writeAudio(p, OpusPackets.samples(p))
                            // Where the source said its last page ends, as a WebM's DiscardPadding would.
                            val total = ogg.packets.drop(2).sumOf { OpusPackets.samples(it).toLong() }
                            finish(total - ogg.pages.last().granule)
                        }
                    }
                    println("${out.name}: ${ogg.packets.size - 2} packets, id read back ${SongFileIds.read(out.name, out.inputStream())}")
                }
                "aac" -> {
                    val bytes = file.readBytes()
                    val frames = ArrayList<ByteArray>()
                    var at = 0
                    var asc = ByteArray(0)
                    var rate = 0
                    var channels = 0
                    while (at + 7 <= bytes.size) {
                        check((bytes[at].toInt() and 0xFF) == 0xFF && (bytes[at + 1].toInt() and 0xF0) == 0xF0) { "no ADTS sync at $at" }
                        val crcAbsent = bytes[at + 1].toInt() and 1
                        val profile = (bytes[at + 2].toInt() and 0xFF) shr 6
                        val rateIndex = (bytes[at + 2].toInt() shr 2) and 0x0F
                        channels = ((bytes[at + 2].toInt() and 1) shl 2) or ((bytes[at + 3].toInt() and 0xFF) shr 6)
                        val length = ((bytes[at + 3].toInt() and 3) shl 11) or ((bytes[at + 4].toInt() and 0xFF) shl 3) or ((bytes[at + 5].toInt() and 0xFF) shr 5)
                        val header = if (crcAbsent == 1) 7 else 9
                        frames += bytes.copyOfRange(at + header, at + length)
                        rate = rates[rateIndex]
                        val config = ((profile + 1) shl 11) or (rateIndex shl 7) or (channels shl 3)
                        asc = byteArrayOf((config shr 8).toByte(), config.toByte())
                        at += length
                    }
                    val track = M4aTrack(asc, rate, channels, frames.map { it.size }.toIntArray(), IntArray(frames.size) { 1024 }, priming = 1024)
                    val out = File(file.parentFile, file.nameWithoutExtension + ".out.m4a")
                    out.outputStream().buffered().use { stream -> M4aWriter.write(stream, track, tags) { o -> frames.forEach(o::write) } }
                    println("${out.name}: ${frames.size} frames at $rate Hz, id read back ${SongFileIds.read(out.name, out.inputStream())}")
                }
            }
        }
    }
}
