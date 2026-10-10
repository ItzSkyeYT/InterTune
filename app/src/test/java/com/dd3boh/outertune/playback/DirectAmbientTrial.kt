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

    private fun ears(share: Float, third: Boolean, rate: Int, left: FloatArray, right: FloatArray): Pair<FloatArray, FloatArray> {
        val p = BinauralAudioProcessor().apply {
            enabled = true
            thirdOrder = third
            ambience = share
            configure(androidx.media3.common.audio.AudioProcessor.AudioFormat(rate, 2, androidx.media3.common.C.ENCODING_PCM_FLOAT))
            flush()
        }
        val input = ByteBuffer.allocateDirect(8 * left.size).order(ByteOrder.nativeOrder())
        for (n in left.indices) input.putFloat(left[n]).putFloat(right[n])
        input.flip()
        p.queueInput(input)
        val out = p.output
        val l = FloatArray(left.size)
        val r = FloatArray(left.size)
        for (n in left.indices) { l[n] = out.float; r[n] = out.float }
        return l to r
    }

    /** Noise with most of its energy low down, as music has: white noise through one pole at 800 Hz. */
    private fun warm(seed: Long, n: Int, rate: Int): FloatArray {
        val white = noise(seed, n, 0.5f)
        val by = (1.0 - Math.exp(-2.0 * PI * 800 / rate)).toFloat()
        var y = 0f
        return FloatArray(n) { y += by * (white[it] - y); y }
    }

    /** The energy in each third of an octave, averaged over the settled part of [a]. */
    private fun thirds(a: FloatArray, rate: Int, centres: List<Double>): DoubleArray {
        val size = DirectAmbientSplit.SIZE
        val power = DoubleArray(size / 2 + 1)
        val re = FloatArray(size)
        val im = FloatArray(size)
        var at = 24000
        while (at + size <= a.size) {
            for (n in 0 until size) { re[n] = a[at + n] * (0.5f - 0.5f * Math.cos(2 * PI * n / size).toFloat()); im[n] = 0f }
            DirectAmbientSplit.fft(re, im, false)
            for (k in 0..size / 2) power[k] += (re[k] * re[k] + im[k] * im[k]).toDouble()
            at += size / 2
        }
        return DoubleArray(centres.size) { c ->
            val lo = centres[c] / Math.pow(2.0, 1.0 / 6); val hi = centres[c] * Math.pow(2.0, 1.0 / 6)
            var sum = 0.0
            for (k in 0..size / 2) { val hz = k * rate.toDouble() / size; if (hz >= lo && hz < hi) sum += power[k] }
            sum
        }
    }

    private fun alike(left: FloatArray, right: FloatArray): Double {
        var l = 0.0; var r = 0.0
        for (n in 24000 until left.size - 64) { l += left[n].toDouble() * left[n]; r += right[n].toDouble() * right[n] }
        var best = 0.0
        for (lag in -48..48) {
            var sum = 0.0
            for (n in 24000 until left.size - 64) sum += left[n].toDouble() * right[n + lag]
            best = maxOf(best, Math.abs(sum))
        }
        return best / Math.sqrt(l * r)
    }

    @Test
    fun moved() {
        assumeTrue("set AMBIENT_SPLIT to run", !System.getenv("AMBIENT_SPLIT").isNullOrBlank())
        val n = 144000
        println("unrelated noise in the two channels, the air moved out: how loud beside the pair (both ears), white noise then noise spread as music is, and how alike the two ears are")
        for (third in listOf(true, false)) for (rate in listOf(48000, 44100)) {
            val wl = noise(21, n); val wr = noise(22, n)
            val ml = warm(23, n, rate); val mr = warm(24, n, rate)
            fun loud(p: Pair<FloatArray, FloatArray>) = (24000 until n).sumOf { p.first[it].toDouble() * p.first[it] + p.second[it].toDouble() * p.second[it] }
            val w0 = ears(0f, third, rate, wl, wr); val m0 = ears(0f, third, rate, ml, mr)
            val line = StringBuilder("third order %-5s at %d: ".format(third, rate))
            line.append("with the pair alike %.2f; ".format(alike(m0.first, m0.second)))
            for (share in listOf(0.25f, 0.5f, 0.75f, 1f)) {
                val w = ears(share, third, rate, wl, wr); val m = ears(share, third, rate, ml, mr)
                line.append("%3.0f%%: %+5.1f %+5.1f dB, alike %.2f; ".format(share * 100, db(loud(w), loud(w0)), db(loud(m), loud(m0)), alike(m.first, m.second)))
            }
            println(line)
        }
        val centres = listOf(250.0, 315.0, 400.0, 500.0, 630.0, 800.0, 1000.0, 1250.0, 1600.0, 2000.0, 2500.0, 3150.0, 4000.0, 5000.0, 6300.0, 8000.0, 10000.0, 12500.0, 16000.0)
        val wl = noise(31, n); val wr = noise(32, n)
        println("the tone, third of an octave by third, unrelated white noise, third order at 48000: the air at 50% and at 100% beside the pair, dB, both ears together")
        val base = ears(0f, true, 48000, wl, wr)
        val half = ears(0.5f, true, 48000, wl, wr)
        val full = ears(1f, true, 48000, wl, wr)
        fun both(p: Pair<FloatArray, FloatArray>) = thirds(p.first, 48000, centres).zip(thirds(p.second, 48000, centres)) { a, b -> a + b }
        val b0 = both(base); val b5 = both(half); val b1 = both(full)
        for (i in centres.indices) println("%7.0f Hz  %+5.1f %+5.1f".format(centres[i], db(b5[i], b0[i]), db(b1[i], b0[i])))
    }
}
