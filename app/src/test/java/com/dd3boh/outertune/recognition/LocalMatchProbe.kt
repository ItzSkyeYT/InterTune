/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.recognition

import com.dd3boh.outertune.fingerprint.DecodedSignature
import com.dd3boh.outertune.fingerprint.SIGNATURE_SAMPLE_RATE_HZ
import com.dd3boh.outertune.fingerprint.SignatureGenerator
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.random.Random

/**
 * Whether the room's audio can be checked against a candidate recording on the phone, with no
 * Shazam in between: the "compare it to the actual recording" idea of 24 Sep, for telling which of
 * several uploads is the one playing.
 *
 * The app's fingerprints are Shazam's peaks: a time, a frequency and a magnitude per peak. Pairs of
 * nearby peaks make hashes that survive noise, and a window from the same recording lines its
 * hashes up at one time offset against the recording's, where another recording's scatter. That is
 * Wang's 2003 method, the one Shazam itself grew from. This measures, on files from this machine,
 * how far apart the same recording and a different one score.
 *
 * Measured 25 Sep, as how far the best offset stands out from the next: the same recording 1.7x
 * to 41x, 4.4x through a simulated room with noise, and always at the right place to 0.1 s; other
 * songs 1.0x to 1.5x; the same recording 2 % and 4 % faster, as a version at another tempo would
 * be, 1.1x and 1.3x. So it can tell the upload that is playing from another version of the song.
 * Weakest: a quiet stretch of How Deep Is Your Love at 1.7x, near the other songs' ceiling, which
 * two windows agreeing on one offset would settle.
 *
 * Skipped unless LOCAL_MATCH=1, since it reads music from disk.
 *
 *     LOCAL_MATCH=1 ./gradlew :app:testCoreDebugUnitTest --tests "*LocalMatchProbe*" -i
 */
class LocalMatchProbe {

    private val dir = File(System.getProperty("user.home"), "Downloads/240gig_Backup/10_trash_recovered/files")

    @Test
    fun probe() {
        assumeTrue(System.getenv("LOCAL_MATCH") == "1")
        val hello = decode(File(dir, "Adele - Hello (Radio Edit).mp3"))
        val helloOther = decode(File(dir, "Adele - Hello (Radio Edit).2.mp3"))
        val deep = decode(File(dir, "Calvin Harris & Disciples - How Deep Is Your Love.mp3"))
        val deepEdit = decode(File(dir, "Calvin Harris & Disciples - How Deep Is Your Love (Radio Edit).mp3"))
        val lean = decode(File(dir, "Major Lazer And Dj Snake - Lean on (Averez Remix).mp3"))
        val cheer = decode(File(dir, "OMI - Cheerleader (Felix Jaehn Remix Radio Edit).mp3"))
        val references = listOf("Hello" to hello, "How Deep (album)" to deep, "Lean On (Averez)" to lean, "Cheerleader" to cheer)
            .map { (name, audio) -> name to Index(SignatureGenerator.makeSignature(audio)) }

        fun room(name: String, audio: ShortArray, fromS: Int, noiseDb: Double = -12.0) {
            val window = noisy(audio.copyOfRange(fromS * SIGNATURE_SAMPLE_RATE_HZ, (fromS + 12) * SIGNATURE_SAMPLE_RATE_HZ), noiseDb)
            val query = SignatureGenerator.makeSignature(window)
            val line = references.joinToString("  ") { (ref, index) ->
                val m = index.match(query)
                "%s %5.1fx @%.1fs".format(ref, m.score, m.offsetS)
            }
            println("LOCAL %-44s %s".format("$name @$fromS s", line))
        }
        room("Hello, another rip", helloOther, 60)
        room("Hello, another rip", helloOther, 150)
        room("Hello, another rip, louder room", helloOther, 150, noiseDb = -3.0)
        room("How Deep radio edit", deepEdit, 20)
        room("How Deep radio edit", deepEdit, 100)
        room("How Deep radio edit", deepEdit, 160)
        room("Lean On (Averez)", lean, 90)
        room("Cheerleader", cheer, 45)
        room("Hello through a room", room(helloOther), 60)
        room("Hello through a room, noisy", room(helloOther), 60, noiseDb = -6.0)
        room("Lean On through a room, noisy", room(lean), 90, noiseDb = -6.0)
        room("How Deep edit through a room, noisy", room(deepEdit), 100, noiseDb = -6.0)
        room("Hello 4% faster (another version)", tempo(helloOther, 1.04), 60)
        room("Hello 2% faster", tempo(helloOther, 1.02), 60)
    }

    class Match(val score: Double, val offsetS: Double)

    /** A recording's hashes, each with where in it the hash starts. */
    class Index(signature: DecodedSignature) {
        private val table = HashMap<Long, MutableList<Int>>()
        init { hashes(signature).forEach { (hash, t) -> table.getOrPut(hash) { ArrayList() }.add(t) } }

        /**
         * How far the best offset stands out from the next best, and that offset. A different
         * recording's votes scatter, and so do those of the same song at another tempo, whose
         * offsets drift across the window: both come out near 1.
         */
        fun match(query: DecodedSignature): Match {
            val qs = hashes(query)
            if (qs.isEmpty()) return Match(0.0, 0.0)
            val votes = HashMap<Int, Int>()
            for ((hash, tq) in qs) table[hash]?.forEach { tr -> votes.merge((tr - tq) / 4, 1, Int::plus) }
            // Neighbouring offset buckets together, since a peak can land one pass either side.
            val best = votes.keys.maxByOrNull { k -> (votes[k] ?: 0) + (votes[k - 1] ?: 0) + (votes[k + 1] ?: 0) } ?: return Match(0.0, 0.0)
            fun around(k: Int) = (votes[k] ?: 0) + (votes[k - 1] ?: 0) + (votes[k + 1] ?: 0)
            val next = votes.keys.filter { kotlin.math.abs(it - best) > 2 }.maxOfOrNull(::around) ?: 0
            return Match(around(best).toDouble() / maxOf(1, next), best * 4 * PASS_S)
        }
    }

    companion object {
        /** One pass of the generator: 128 samples at 16 kHz. */
        const val PASS_S = 128.0 / SIGNATURE_SAMPLE_RATE_HZ

        /**
         * Anchor and target peaks: frequency in pairs of bins (15.6 Hz), a target up to 0.8 s after
         * in steps of two passes, five per anchor. Twice as coarse as the generator's resolution
         * both ways scored best through a simulated room; finer lost matches to noise.
         */
        fun hashes(signature: DecodedSignature): List<Pair<Long, Int>> {
            val peaks = signature.peaksByBand.flatten()
                .map { it.fftPassNumber to it.correctedPeakFrequencyBin / 128 }
                .sortedWith(compareBy({ it.first }, { it.second }))
            val out = ArrayList<Pair<Long, Int>>()
            for (i in peaks.indices) {
                val (t1, f1) = peaks[i]
                var paired = 0
                var j = i + 1
                while (j < peaks.size && paired < 5) {
                    val (t2, f2) = peaks[j]
                    val dt = t2 - t1
                    if (dt > 100) break
                    if (dt >= 1 && kotlin.math.abs(f2 - f1) <= 200) {
                        out += ((f1.toLong() shl 20) or (f2.toLong() shl 8) or (dt / 2).toLong()) to t1
                        paired++
                    }
                    j++
                }
            }
            return out
        }

        /** White noise at [db] relative to the signal's own level, standing in for a room. */
        fun noisy(samples: ShortArray, db: Double): ShortArray {
            val rms = kotlin.math.sqrt(samples.sumOf { it.toDouble() * it } / samples.size)
            val sd = rms * Math.pow(10.0, db / 20)
            val random = Random(7)
            return ShortArray(samples.size) { i ->
                val gauss = kotlin.math.sqrt(-2 * kotlin.math.ln(random.nextDouble().coerceAtLeast(1e-12))) * kotlin.math.cos(2 * Math.PI * random.nextDouble())
                (samples[i] + gauss * sd).coerceIn(-32768.0, 32767.0).toInt().toShort()
            }
        }

        /** A phone's microphone across a room: no deep bass, no top end, and three early echoes. */
        fun room(samples: ShortArray): ShortArray = ffmpeg(samples, "highpass=f=150,lowpass=f=6000,aecho=0.8:0.5:35|70|110:0.35|0.25|0.15")

        /** Faster by [factor] without a change of pitch, as a remix at another tempo would be. */
        fun tempo(samples: ShortArray, factor: Double): ShortArray = ffmpeg(samples, "atempo=$factor")

        private fun ffmpeg(samples: ShortArray, filter: String): ShortArray {
            val bytes = ByteBuffer.allocate(samples.size * 2).order(ByteOrder.LITTLE_ENDIAN).apply { asShortBuffer().put(samples) }.array()
            val process = ProcessBuilder("ffmpeg", "-v", "error", "-f", "s16le", "-ar", "$SIGNATURE_SAMPLE_RATE_HZ", "-ac", "1", "-i", "-",
                "-af", filter, "-f", "s16le", "-ar", "$SIGNATURE_SAMPLE_RATE_HZ", "-ac", "1", "-").start()
            Thread { process.outputStream.use { it.write(bytes) } }.start()
            return pcm(process.inputStream.readBytes())
        }

        fun decode(file: File): ShortArray {
            val process = ProcessBuilder("ffmpeg", "-v", "error", "-i", file.absolutePath,
                "-f", "s16le", "-ac", "1", "-ar", "$SIGNATURE_SAMPLE_RATE_HZ", "-").start()
            return pcm(process.inputStream.readBytes())
        }

        private fun pcm(raw: ByteArray): ShortArray {
            val shorts = ShortArray(raw.size / 2)
            ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(shorts)
            return shorts
        }
    }
}
