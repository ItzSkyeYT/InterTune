/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.parts

import com.dd3boh.outertune.parts.Scnet.SEGMENT
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.log10

/**
 * The Kotlin side of separation against what the PC does with SCNet (stems/tools/stemlib.py), on
 * Starboy: one 11 s segment from 60 s, step by step, and three whole windows through stemlib's
 * split at overlap 1.33. The reference is made by experiments/features/automix/parts/tools/
 * make_reference.py and found by walking up from here; without it these tests are skipped.
 *
 * The bar, in dB of error under the signal: two honest versions of the same maths, torch's float32
 * STFT and a float64 one around the same model, land 123 to 130 dB apart on the parts (floor.json).
 * The ORT-format files the phone loads differ from the .onnx run by 124 dB. So the transforms must
 * agree with torch's by 120 dB, the model run by 110, and a whole segment or window by 100 dB per
 * part with no sample off by more than half a step of 16-bit sound (1.5e-5): what is left then is
 * 20 dB under the noise of the 16-bit files the parts are kept in, and never reaches a stored sample
 * by more than one step.
 */
class ScnetReferenceTest {

    @Before
    fun reference() {
        assumeTrue("no reference: run experiments/features/automix/parts/tools/make_reference.py", dir != null)
    }

    @Test
    fun `the STFT is torch's`() {
        val mix = read("seg.mix")
        val spec = FloatArray(Scnet.SPEC_SIZE)
        ScnetStft().forward(mix.copyOfRange(0, SEGMENT), mix.copyOfRange(SEGMENT, 2 * SEGMENT), spec)
        val db = snrDb(read("seg.spec"), spec)
        report("STFT against torch.stft: %.1f dB", db)
        assertTrue("STFT $db dB", db > 120)
    }

    @Test
    fun `the inverse is torch's`() {
        val out = read("seg.out")
        val parts = read("seg.parts")
        val stft = ScnetStft()
        val left = FloatArray(SEGMENT)
        val right = FloatArray(SEGMENT)
        for ((s, name) in Scnet.STEMS.withIndex()) {
            stft.inverse(out, s * Scnet.SPEC_SIZE, left, right)
            val db = minOf(snrDb(parts, left, (2 * s) * SEGMENT), snrDb(parts, right, (2 * s + 1) * SEGMENT))
            report("inverse STFT against torch.istft, %s: %.1f dB", name, db)
            assertTrue("inverse $name $db dB", db > 120)
        }
    }

    @Test
    fun `the model the phone loads gives what Python's gives`() {
        for (file in listOf("scnet.ort", "scnet-int8.ort")) {
            model(file).use { model ->
                val start = System.nanoTime()
                model.run(read("seg.spec"))
                val seconds = (System.nanoTime() - start) / 1e9
                val out = FloatArray(Scnet.OUTPUT_SIZE)
                val stem = FloatArray(Scnet.SPEC_SIZE)
                for (s in Scnet.STEMS.indices) {
                    model.readStem(s, stem)
                    stem.copyInto(out, s * Scnet.SPEC_SIZE)
                }
                val db = snrDb(read("seg.out"), out)
                report("%s on Python's input against Python's .onnx output: %.1f dB (%.1f s, %d threads)", file, db, seconds, THREADS)
                assertTrue("$file $db dB", db > 110)
            }
        }
    }

    @Test
    fun `one segment end to end is stemlib's`() {
        val mix = read("seg.mix")
        val want = read("seg.parts")
        for (file in listOf("scnet.ort", "scnet-int8.ort")) {
            model(file).use { model ->
                val segment = ScnetSegment(model)
                val parts = Array(8) { FloatArray(SEGMENT) }
                segment.separate(mix.copyOfRange(0, SEGMENT), mix.copyOfRange(SEGMENT, 2 * SEGMENT), parts)
                report(
                    "%s one segment: STFT %.2f s, model %.2f s, inverse %.2f s",
                    file, segment.forwardNanos / 1e9, segment.modelNanos / 1e9, segment.inverseNanos / 1e9,
                )
                check("$file segment", want, parts, SEGMENT)
            }
        }
    }

    @Test
    fun `whole windows are stemlib's split at overlap 1_33`() {
        model("scnet.ort").use { model ->
            for (name in listOf("win19", "win7", "win5")) {
                val mix = read("$name.mix")
                val frames = mix.size / 2
                val start = System.nanoTime()
                val parts = ScnetSegment.separate(mix.copyOfRange(0, frames), mix.copyOfRange(frames, 2 * frames), 1.33, model)
                report("%s: %d frames, %d segments, %.1f s", name, frames, ScnetPlan(frames, 1.33).count, (System.nanoTime() - start) / 1e9)
                check(name, read("$name.parts"), parts, frames)
            }
        }
    }

    @Test
    fun `the phone bench's WAV holds the reference segment as segment 1, and its float WAVs keep every bit`() {
        val wavFile = File(dir!!.parentFile, "bench/starboy-10.wav")
        assumeTrue("no bench WAV: run parts/tools/make_bench_input.py", wavFile.isFile)
        // What PartsBench does with it: the plan for 10 segments at 1.33, segment 1 kept.
        val wav = Wav.read(wavFile)
        val frames = minOf(wav.frames, ScnetPlan.framesFor(10, 1.33))
        val plan = ScnetPlan(frames, 1.33)
        assertTrue(plan.count == 10 && frames == wav.frames)
        val left = FloatArray(SEGMENT)
        val right = FloatArray(SEGMENT)
        plan.input(1, wav.left, wav.right, left, right)
        val mix = read("seg.mix")
        for (p in 0 until SEGMENT) {
            assertTrue("left $p", left[p] == mix[p])
            assertTrue("right $p", right[p] == mix[SEGMENT + p])
        }
        // The kept segment as the bench writes it, for parts/tools/compare_phone.py to read like a phone's.
        val out = File("build/parts-bench-pc").apply { mkdirs() }
        model("scnet-int8.ort").use { model ->
            val parts = Array(8) { FloatArray(SEGMENT) }
            ScnetSegment(model).separate(left, right, parts)
            Wav.writeFloat(File(out, "seg1-mix.wav"), Scnet.RATE, left, right)
            for ((s, name) in Scnet.STEMS.withIndex()) Wav.writeFloat(File(out, "seg1-$name.wav"), Scnet.RATE, parts[2 * s], parts[2 * s + 1])
            val back = Wav.read(File(out, "seg1-vocals.wav"))
            assertTrue(back.left.contentEquals(parts[6]) && back.right.contentEquals(parts[7]))
            check("bench WAV segment 1", read("seg.parts"), parts, SEGMENT)
        }
    }

    /** Each part against the reference's: dB of error under it and the worst sample. */
    private fun check(label: String, want: FloatArray, parts: Array<FloatArray>, frames: Int) {
        for ((s, name) in Scnet.STEMS.withIndex()) {
            var worst = 0f
            var db = Double.POSITIVE_INFINITY
            for (c in 0..1) {
                val row = parts[2 * s + c]
                val from = (2 * s + c) * frames
                db = minOf(db, snrDb(want, row, from, frames))
                for (p in 0 until frames) worst = maxOf(worst, abs(row[p] - want[from + p]))
            }
            report("%s %s: %.1f dB, worst sample %.2e (%.3f of a 16-bit step)", label, name, db, worst, worst * 32768)
            assertTrue("$label $name $db dB", db > 100)
            assertTrue("$label $name worst sample $worst", worst < 1.5e-5f)
        }
    }

    private fun model(file: String) =
        OrtSpecModel(OrtSpecModel.environment(), File(dir!!.parentFile, "models/$file").path, OrtSpecModel.Options(threads = THREADS))

    companion object {
        /** The model's threads: its output is the same at 1 and 4 (timing.json), and 4 is quicker. */
        private const val THREADS = 4

        private val dir: File? by lazy {
            System.getProperty("parts.reference")?.let { return@lazy File(it).takeIf(File::isDirectory) }
            var at: File? = File("").absoluteFile
            while (at != null) {
                val candidate = File(at, "experiments/features/automix/parts/reference")
                if (File(candidate, "seg.mix.f32").isFile) return@lazy candidate
                at = at.parentFile
            }
            null
        }

        private fun read(name: String): FloatArray {
            val bytes = ByteBuffer.wrap(File(dir, "$name.f32").readBytes()).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
            return FloatArray(bytes.remaining()).also { bytes.get(it) }
        }

        /** Energy of [ref] over the energy of [x] minus it, in dB, over [length] samples of [x] against [ref] from [from]. */
        private fun snrDb(ref: FloatArray, x: FloatArray, from: Int = 0, length: Int = x.size): Double {
            var signal = 0.0
            var error = 0.0
            for (p in 0 until length) {
                val r = ref[from + p].toDouble()
                val d = x[p] - r
                signal += r * r
                error += d * d
            }
            return if (error == 0.0) Double.POSITIVE_INFINITY else 10 * log10(signal / error)
        }

        private fun report(format: String, vararg args: Any) = println("parts reference: " + String.format(format, *args))
    }
}
