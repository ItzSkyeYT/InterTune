/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.playback

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Not a test: something to listen to. Every 16 bit WAV of six or eight channels in the folder
 * SURROUND_IN names is put through the renderer as the app would, and what comes out for the two
 * ears is written beside it as "<name> for headphones.wav".
 */
class SurroundInTrial {

    @Test
    fun render() {
        val folder = System.getenv("SURROUND_IN"); assumeTrue("set SURROUND_IN to run", !folder.isNullOrBlank())
        File(folder!!).listFiles { f -> f.extension == "wav" && !f.name.endsWith("for headphones.wav") }!!.sortedBy { it.name }.forEach { file ->
            val bytes = file.readBytes()
            val header = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
            val channels = header.getShort(22).toInt()
            val rate = header.getInt(24)
            val bits = header.getShort(34).toInt()
            // The data chunk, wherever it starts.
            var at = 12
            while (!(bytes[at] == 'd'.code.toByte() && bytes[at + 1] == 'a'.code.toByte() && bytes[at + 2] == 't'.code.toByte() && bytes[at + 3] == 'a'.code.toByte())) {
                at += 8 + header.getInt(at + 4)
            }
            val length = header.getInt(at + 4)
            val start = at + 8
            if (bits != 16 || (channels != 6 && channels != 8)) return@forEach

            val p = BinauralAudioProcessor().apply { enabled = true; thirdOrder = true }
            check(p.configure(AudioProcessor.AudioFormat(rate, channels, C.ENCODING_PCM_16BIT)).channelCount == 2)
            p.flush()
            val frameBytes = 2 * channels
            val frames = length / frameBytes
            val out = ByteBuffer.allocate(frames * 4).order(ByteOrder.LITTLE_ENDIAN)
            var done = 0
            while (done < frames) {
                val now = minOf(1024, frames - done)
                val input = ByteBuffer.allocateDirect(now * frameBytes).order(ByteOrder.nativeOrder())
                val source = ByteBuffer.wrap(bytes, start + done * frameBytes, now * frameBytes).order(ByteOrder.LITTLE_ENDIAN)
                repeat(now * channels) { input.putShort(source.short) }
                input.flip()
                p.queueInput(input)
                val heard = p.output
                while (heard.hasRemaining()) out.putShort(heard.short)
                done += now
            }
            val data = out.array().copyOf(out.position())
            val wav = ByteBuffer.allocate(44 + data.size).order(ByteOrder.LITTLE_ENDIAN)
            wav.put("RIFF".toByteArray()).putInt(36 + data.size).put("WAVEfmt ".toByteArray()).putInt(16)
                .putShort(1).putShort(2).putInt(rate).putInt(rate * 4).putShort(4).putShort(16)
                .put("data".toByteArray()).putInt(data.size).put(data)
            val target = File(file.parentFile, file.nameWithoutExtension + " for headphones.wav")
            target.writeBytes(wav.array())
            println("${file.name}: $channels channels, $frames frames at $rate Hz -> ${target.name}")
        }
    }
}
