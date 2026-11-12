/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.playback

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import com.dd3boh.outertune.cast.PcmStage
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * The equalizer and the levelling on raw audio cast from the phone, measured: what goes to an
 * AirPlay speaker must sound as the phone does, and a stage that sends audio in real time must not
 * click or allocate.
 */
class CastSoundTest {
    private val rate = 44_100

    private fun gains(vararg pairs: Pair<Int, Float>): List<Float> =
        List(EqualizerAudioProcessor.BANDS.size) { i -> pairs.firstOrNull { it.first == i }?.second ?: 0f }

    private fun eq(vararg pairs: Pair<Int, Float>) = EqualizerAudioProcessor.Settings(true, gains(*pairs))

    /** A sound whose settings a test moves as a listener would, with every song of known [loudnessDb]. */
    private inner class Settings(
        @Volatile var equalizer: EqualizerAudioProcessor.Settings = EqualizerAudioProcessor.Settings.OFF,
        @Volatile var normalize: Boolean = false,
        val loudnessDb: Double? = 3.0,
        val local: Boolean = false,
    ) {
        val sound = CastSound({ equalizer }, { normalize }, { CastSound.Loudness(loudnessDb, local) })
    }

    /** Stereo samples of a sine at [hz] and [amplitude] of full scale, the same in both channels. */
    private fun sine(hz: Double, frames: Int, amplitude: Double = 0.5): ShortArray =
        ShortArray(frames * 2) { (amplitude * 32_767 * sin(2 * PI * hz * (it / 2) / rate)).toInt().toShort() }

    /** [input] through [stage] in packets of [packet] frames, as the AirPlay link sends it, in place; [before] runs ahead of each. */
    private fun through(stage: PcmStage, input: ShortArray, packet: Int = 352, song: String = "song", before: (frame: Int) -> Unit = {}): ShortArray {
        val samples = input.copyOf()
        val frames = samples.size / 2
        var at = 0
        while (at < frames) {
            before(at)
            val n = minOf(packet, frames - at)
            stage.shape(song, samples, at * 2, samples, at * 2, n)
            at += n
        }
        return samples
    }

    /** The left channel's level against the input's, in dB, over the second half once the filters have settled. */
    private fun gainDb(input: ShortArray, output: ShortArray): Double {
        fun rms(a: ShortArray): Double {
            val half = a.size / 2
            var sum = 0.0
            var i = half - half % 2
            var n = 0
            while (i < a.size) {
                sum += a[i].toDouble() * a[i]
                n++
                i += 2
            }
            return sqrt(sum / n)
        }
        return 20 * log10(rms(output) / rms(input))
    }

    /** The largest step from one left sample to the next over [range] of frames. */
    private fun largestStep(samples: ShortArray, range: IntRange): Int = range.maxOf { abs(samples[it * 2] - samples[(it - 1) * 2]) }

    @Test
    fun `flat, off, or nothing to level, every sample passes untouched`() {
        val input = ShortArray(4_000) { ((it * 7_919) % 65_536 - 32_768).toShort() }
        for (settings in listOf(EqualizerAudioProcessor.Settings.OFF, eq(), EqualizerAudioProcessor.Settings(false, gains(3 to 9f)))) {
            val sound = Settings(equalizer = settings).sound
            assertArrayEquals("$settings, in place", input, through(sound.stage(rate, 2), input))
            val out = ShortArray(input.size)
            sound.stage(rate, 2).shape("song", input, 0, out, 0, input.size / 2)
            assertArrayEquals("$settings, into another array", input, out)
        }
        // A song normalisation leaves where it is, a file on the phone, passes untouched too.
        val local = Settings(normalize = true, local = true)
        local.sound.prepare("song")
        assertArrayEquals(input, through(local.sound.stage(rate, 2), input))
    }

    @Test
    fun `a 1 kHz tone through a band raised 6 dB at 1 kHz comes out twice as loud as the rest`() {
        val settings = Settings(equalizer = eq(5 to 6f))
        val at1k = sine(1_000.0, rate).let { gainDb(it, through(settings.sound.stage(rate, 2), it)) }
        val at250 = sine(250.0, rate).let { gainDb(it, through(settings.sound.stage(rate, 2), it)) }
        assertEquals("twice as loud: 6 dB", 6.0, at1k - at250, 0.5)
        // As on the phone, the boost is the rest turned down, never anything louder than it came.
        assertEquals("the raised band at the level it came in", 0.0, at1k, 0.3)
        assertEquals("the rest 6 dB under it", -6.0, at250, 0.5)
    }

    @Test
    fun `the curve is the phone's own, to the sample`() {
        val curve = EqualizerPreset.MORE_BASS.gainsDb
        val frames = rate
        // Three tones at once, a bass, a voice and a cymbal.
        val input = ShortArray(frames * 2) {
            val t = (it / 2).toDouble() / rate
            (9_000 * sin(2 * PI * 55 * t) + 6_000 * sin(2 * PI * 700 * t) + 3_000 * sin(2 * PI * 9_000 * t)).toInt().toShort()
        }
        val cast = through(Settings(equalizer = EqualizerAudioProcessor.Settings(true, curve)).sound.stage(rate, 2), input)
        val phone = EqualizerAudioProcessor().apply {
            settings = EqualizerAudioProcessor.Settings(true, curve)
            configure(AudioProcessor.AudioFormat(rate, 2, C.ENCODING_PCM_16BIT))
            flush()
        }
        val buffer = ByteBuffer.allocateDirect(input.size * 2).order(ByteOrder.nativeOrder())
        input.forEach { buffer.putShort(it) }
        buffer.flip()
        phone.queueInput(buffer)
        val out = phone.output
        val worst = (input.indices).maxOf { abs(out.getShort() - cast[it]) }
        assertTrue("$worst apart", worst <= 1)
    }

    @Test
    fun `a band moved while it plays does not click`() {
        // Each from one curve to the next half way through: a step up, a jump to the top, the
        // equalizer turned on, and turned off again.
        val moves = listOf(
            eq(5 to 3f) to eq(5 to 4f),
            eq() to eq(5 to 12f),
            EqualizerAudioProcessor.Settings.OFF to EqualizerAudioProcessor.Settings(true, EqualizerPreset.MORE_BASS.gainsDb),
            EqualizerAudioProcessor.Settings(true, EqualizerPreset.MORE_BASS.gainsDb) to EqualizerAudioProcessor.Settings.OFF,
            eq(0 to 12f, 1 to 12f) to eq(0 to -12f, 1 to -12f),
        )
        val frames = rate
        for ((first, second) in moves) {
            val settings = Settings(equalizer = first)
            val out = through(settings.sound.stage(rate, 2), sine(1_000.0, frames)) { at -> if (at >= frames / 2) settings.equalizer = second }
            // A sine at 1 kHz moves at most about 2,300 from one sample to the next at this level; a
            // filter restarted from silence, or a coefficient that jumps, steps by far more.
            val steady = maxOf(largestStep(out, frames / 4 until frames / 2 - 400), largestStep(out, frames * 3 / 4 until frames))
            val across = largestStep(out, frames / 2 - 400 until frames / 2 + 2_000)
            assertTrue("$first to $second: $across across the change against $steady", across <= steady * 1.15)
        }
    }

    @Test
    fun `turned off again, it goes back to passing the samples untouched`() {
        val settings = Settings(equalizer = eq(2 to 6f))
        val stage = settings.sound.stage(rate, 2)
        through(stage, sine(125.0, rate))
        settings.equalizer = EqualizerAudioProcessor.Settings.OFF
        // A quarter of a second for the filters' memory to die away, then the samples as they are.
        through(stage, sine(125.0, rate / 2))
        val input = sine(125.0, rate)
        assertArrayEquals(input, through(stage, input))
    }

    @Test
    fun `a song is turned down by its own level, and the switch is heard at once`() {
        val settings = Settings(normalize = true, loudnessDb = 2.0)
        settings.sound.prepare("song")
        val expected = 20 * log10(Normalization.factor(2.0, false, true).toDouble())
        assertTrue("the level is a real cut", expected < -3)
        val stage = settings.sound.stage(rate, 2)
        val input = sine(440.0, rate)
        assertEquals(expected, gainDb(input, through(stage, input)), 0.05)
        settings.normalize = false
        val after = through(stage, input)
        // One packet's glide, then the song as it is.
        assertArrayEquals(input.copyOfRange(704, input.size), after.copyOfRange(704, after.size))
        // A song never looked up is taken as loud, as on the phone before its loudness is known.
        settings.normalize = true
        val unknown = through(settings.sound.stage(rate, 2), input, song = "another")
        assertEquals(20 * log10(Normalization.factor(null, false, true).toDouble()), gainDb(input, unknown), 0.05)
    }

    @Test
    fun `a reset starts the filters from silence and the next song at its own level`() {
        val settings = Settings(equalizer = eq(0 to 9f), normalize = true)
        settings.sound.prepare("song")
        val stage = settings.sound.stage(rate, 2)
        through(stage, sine(31.0, rate))
        stage.reset()
        val fresh = settings.sound.stage(rate, 2)
        val input = sine(62.0, rate / 4)
        assertArrayEquals(through(fresh, input), through(stage, input))
    }

    @Test
    fun `a band above what the rate can carry is left out, as on the phone`() {
        // At 22.05 kHz the 16 kHz band is past the top: raised alone, nothing changes.
        val input = ShortArray(2_000) { ((it * 104_729) % 65_536 - 32_768).toShort() }
        val sound = Settings(equalizer = eq(9 to 12f)).sound
        assertArrayEquals(input, through(sound.stage(22_050, 2), input))
        // One channel works the same way.
        val mono = Settings(equalizer = eq(5 to 6f)).sound.stage(rate, 1)
        val tone = ShortArray(rate) { (16_000 * sin(2 * PI * 1_000.0 * it / rate)).toInt().toShort() }
        val out = tone.copyOf().also { mono.shape("song", it, 0, it, 0, it.size) }
        assertTrue(out.drop(rate / 2).maxOf { abs(it.toInt()) } in 15_000..16_500)
    }

    /**
     * The bytes this thread has allocated so far, from the JVM the tests run on (by reflection: the
     * classes the tests are compiled against are Android's, which has no java.lang.management), or
     * null where it cannot say.
     */
    private fun allocatedSoFar(): (() -> Long)? {
        try {
            val bean = Class.forName("java.lang.management.ManagementFactory").getMethod("getThreadMXBean").invoke(null)
            val type = Class.forName("com.sun.management.ThreadMXBean")
            if (!type.isInstance(bean) || type.getMethod("isThreadAllocatedMemorySupported").invoke(bean) != true) return null
            val read = type.getMethod("getThreadAllocatedBytes", Long::class.javaPrimitiveType)
            val id = Thread.currentThread().id
            val reader: () -> Long = { read.invoke(bean, id) as Long }
            return reader
        } catch (e: ReflectiveOperationException) {
            return null
        }
    }

    @Test
    fun `nothing is allocated, a band moved and a new song included`() {
        val allocated = allocatedSoFar()
        assumeTrue("this JVM does not count what a thread allocates", allocated != null)
        val settings = Settings(normalize = true)
        settings.sound.prepare("a")
        settings.sound.prepare("b")
        val stage = settings.sound.stage(rate, 2)
        val samples = sine(1_000.0, 352)
        // Every curve made beforehand: the app makes one per change, on another thread.
        val curves = List(40) { i -> eq(5 to (i % 13).toFloat(), 0 to (i % 7 - 3).toFloat()) } + EqualizerAudioProcessor.Settings.OFF
        fun run() {
            for (packet in 0 until 2_000) {
                if (packet % 50 == 0) settings.equalizer = curves[packet / 50 % curves.size]
                if (packet == 1_000) stage.reset()
                stage.shape(if (packet < 1_000) "a" else "b", samples, 0, samples, 0, 352)
            }
        }
        run() // Loaded and compiled.
        allocated!!()
        val before = allocated()
        run()
        // The reading itself costs a few dozen bytes; one array or box a packet would be tens of thousands.
        val spent = allocated() - before
        assertTrue("$spent bytes for 2,000 packets", spent < 1_024)
    }

    @Test
    fun `the arrays hold the very coefficients and curve the phone works out`() {
        for (rate in listOf(44_100.0, 48_000.0)) {
            for ((band, hz) in EqualizerAudioProcessor.BANDS.withIndex()) {
                val db = (band - 4) * 2.5
                val biquad = EqualizerAudioProcessor.Eq.peaking(hz, db, EqualizerAudioProcessor.Eq.Q, rate)
                val array = DoubleArray(5).also { EqualizerAudioProcessor.Eq.peakingInto(it, 0, hz, db, EqualizerAudioProcessor.Eq.Q, rate) }
                assertArrayEquals(doubleArrayOf(biquad.b0, biquad.b1, biquad.b2, biquad.a1, biquad.a2), array, 0.0)
            }
            val curve = EqualizerPreset.LOUDNESS.gainsDb
            val filters = EqualizerAudioProcessor.Eq.filters(curve, rate)
            val array = DoubleArray(filters.size * 5)
            filters.forEachIndexed { i, f -> doubleArrayOf(f.b0, f.b1, f.b2, f.a1, f.a2).copyInto(array, i * 5) }
            assertEquals(EqualizerAudioProcessor.Eq.headroomDb(filters, rate), EqualizerAudioProcessor.Eq.headroomDb(array, filters.size, rate), 0.0)
            assertTrue(10.0.pow(EqualizerAudioProcessor.Eq.headroomDb(array, filters.size, rate) / 20) > 1.5)
        }
    }
}
