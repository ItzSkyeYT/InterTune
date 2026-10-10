/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.playback

import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Random
import kotlin.math.PI
import kotlin.math.log10
import kotlin.math.sin

/**
 * Not a test: numbers to read and files to listen to. With AMBIENT_SPLIT naming a folder, every 16 bit
 * stereo WAV in it is taken apart and its two parts written beside it, "<name> direct.wav" and
 * "<name> ambient.wav": the second played by itself says at once whether the split is right (a hall
 * and no singer) or wrong. With no WAV there it prints what it makes of a few made-up signals.
 */
class DirectAmbientTrial {

    private fun noise(seed: Long, n: Int, level: Float = 0.25f) = Random(seed).let { r -> FloatArray(n) { (r.nextGaussian() * level).toFloat() } }
    private fun settled(a: FloatArray) = (24000 until a.size).sumOf { a[it].toDouble() * a[it] }
    private fun db(part: Double, whole: Double) = if (part <= 0.0) Double.NEGATIVE_INFINITY else 10 * log10(part / whole)

    private fun say(name: String, left: FloatArray, right: FloatArray) {
        val s = DirectAmbientSplit()
        val al = FloatArray(left.size); val ar = FloatArray(left.size)
        for (n in left.indices) { s.process(left[n], right[n]); al[n] = s.ambientLeft; ar[n] = s.ambientRight }
        println("%-52s ambient beside the channel: left %+6.1f dB, right %+6.1f dB".format(name, db(settled(al), settled(left)), db(settled(ar), settled(right))))
    }

    @Test
    fun split() {
        val folder = System.getenv("AMBIENT_SPLIT"); assumeTrue("set AMBIENT_SPLIT to run", !folder.isNullOrBlank())
        val n = 96000
        val a = noise(1, n); val b = noise(2, n); val c = noise(3, n)
        say("the same in both", a, a)
        say("panned 0.9 and 0.3", FloatArray(n) { a[it] * 0.9f }, FloatArray(n) { a[it] * 0.3f })
        say("one channel only", a, FloatArray(n))
        say("nothing shared", a, b)
        say("a sound in the middle, as much unrelated in each", FloatArray(n) { c[it] + a[it] }, FloatArray(n) { c[it] + b[it] })
        say("the same, the unrelated part 12 dB down", FloatArray(n) { c[it] + a[it] * 0.25f }, FloatArray(n) { c[it] + b[it] * 0.25f })
        say("one channel a thousandth of a second late", a, FloatArray(n) { if (it >= 48) a[it - 48] else 0f })
        say("a tone, a quarter turn apart", FloatArray(n) { (0.3 * sin(2 * PI * 1000 * it / 48000)).toFloat() }, FloatArray(n) { (0.3 * sin(2 * PI * 1000 * it / 48000 + PI / 2)).toFloat() })

        File(folder!!).listFiles { f -> f.extension == "wav" && !f.name.endsWith(" direct.wav") && !f.name.endsWith(" ambient.wav") }!!.sortedBy { it.name }.forEach { file ->
            val bytes = file.readBytes()
            val header = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
            val channels = header.getShort(22).toInt()
            val rate = header.getInt(24)
            val bits = header.getShort(34).toInt()
            var at = 12
            while (!(bytes[at] == 'd'.code.toByte() && bytes[at + 1] == 'a'.code.toByte() && bytes[at + 2] == 't'.code.toByte() && bytes[at + 3] == 'a'.code.toByte())) {
                at += 8 + header.getInt(at + 4)
            }
            val frames = header.getInt(at + 4) / 4
            if (bits != 16 || channels != 2) return@forEach
            val source = ByteBuffer.wrap(bytes, at + 8, frames * 4).order(ByteOrder.LITTLE_ENDIAN)
            val s = DirectAmbientSplit()
            val direct = ByteBuffer.allocate(frames * 4).order(ByteOrder.LITTLE_ENDIAN)
            val ambient = ByteBuffer.allocate(frames * 4).order(ByteOrder.LITTLE_ENDIAN)
            var whole = 0.0
            var air = 0.0
            fun pcm(v: Float) = (v * 32767f).coerceIn(-32768f, 32767f).toInt().toShort()
            repeat(frames) {
                val l = source.short / 32768f
                val r = source.short / 32768f
                s.process(l, r)
                direct.putShort(pcm(s.directLeft)).putShort(pcm(s.directRight))
                ambient.putShort(pcm(s.ambientLeft)).putShort(pcm(s.ambientRight))
                whole += (l * l + r * r).toDouble()
                air += (s.ambientLeft * s.ambientLeft + s.ambientRight * s.ambientRight).toDouble()
            }
            for ((part, data) in listOf("direct" to direct.array(), "ambient" to ambient.array())) {
                val wav = ByteBuffer.allocate(44 + data.size).order(ByteOrder.LITTLE_ENDIAN)
                wav.put("RIFF".toByteArray()).putInt(36 + data.size).put("WAVEfmt ".toByteArray()).putInt(16)
                    .putShort(1).putShort(2).putInt(rate).putInt(rate * 4).putShort(4).putShort(16)
                    .put("data".toByteArray()).putInt(data.size).put(data)
                File(file.parentFile, "${file.nameWithoutExtension} $part.wav").writeBytes(wav.array())
            }
            println("%s: %d frames at %d Hz, the ambience is %.1f dB beside the whole".format(file.name, frames, rate, db(air, whole)))
        }
    }
}
