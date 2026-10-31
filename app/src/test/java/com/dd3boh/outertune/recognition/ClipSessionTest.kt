/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.recognition

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random
import kotlin.math.PI
import kotlin.math.sin

/**
 * A listen of the phone's own sound, as a whole, with Shazam's answers scripted (his reel of an
 * organ playing a dance tune, 31 Oct 2026: named "Sunshine" by the first twelve seconds, which were
 * only the first guess). The real Shazam over the real reel is experiments/features/recognition-reels.
 */
class ClipSessionTest {
    private val rate = 16_000

    /** Something like music: a chord that changes every fifth of a second or so, never the same twice. */
    private fun music(seconds: Double, seed: Long): ShortArray {
        val random = Random(seed)
        val out = ShortArray((seconds * rate).toInt())
        var i = 0
        while (i < out.size) {
            val length = minOf(((0.12 + random.nextDouble() * 0.16) * rate).toInt(), out.size - i)
            val tones = DoubleArray(3) { 150.0 + random.nextDouble() * 3500 }
            val loudness = DoubleArray(3) { 1500.0 + random.nextDouble() * 5000 }
            for (k in 0 until length) {
                var v = 0.0
                for (j in 0 until 3) v += loudness[j] * sin(2 * PI * tones[j] * (i + k) / rate)
                out[i + k] = v.coerceIn(-32000.0, 32000.0).toInt().toShort()
            }
            i += length
        }
        return out
    }

    private fun looped(clip: ShortArray, seconds: Double) = ShortArray((seconds * rate).toInt()) { clip[it % clip.size] }

    private fun pieces(sound: ShortArray): List<ShortArray> =
        (sound.indices step rate).map { sound.copyOfRange(it, minOf(it + rate, sound.size)) }

    private fun match(
        title: String, key: String, offset: Double, time: Double = 0.0001, frequency: Double = 0.0002,
        artist: String? = "Calvin Harris",
    ): RecognitionOutcome = RecognitionOutcome.Match(Recognised(title, artist, null, null, key, offset, time, frequency))

    /** A guess like the reel's: a song, a skew of 0.01 and more, somewhere it is not. */
    private fun junk(n: Int) = match("Junk $n", "j$n", 100.0 + n, 0.0108, 0.0035, "Nobody $n")

    /** Shazam's answers in the order they are asked for; [rest] once they run out. */
    private class Script(private val answers: List<RecognitionOutcome>, private val rest: RecognitionOutcome = RecognitionOutcome.NoMatch) {
        val sizes = mutableListOf<Int>()
        private var next = 0
        fun ask(samples: ShortArray): RecognitionOutcome {
            sizes += samples.size
            return answers.getOrNull(next++) ?: rest
        }
    }

    /** [sound] a second at a time to [session]: how the listen ended, and after how many seconds. */
    private fun run(session: ClipSession, sound: ShortArray): Pair<ClipSession.Verdict?, Int> = runBlocking {
        var second = 0
        for (piece in pieces(sound)) {
            second++
            session.hear(piece, (second - 1) * 1000L)?.let { return@runBlocking it to second }
        }
        null to second
    }

    private fun session(script: Script) = ClipSession(ask = { script.ask(it) })

    @Test
    fun `a song two windows agree on is the answer at the second window, eighteen seconds in`() {
        val script = Script(listOf(match("Blame", "1", 49.2), match("Blame", "1", 55.2)))
        val (verdict, second) = run(session(script), music(60.0, 1))
        val answer = verdict as ClipSession.Verdict.Answer
        assertEquals("Blame", answer.track.title)
        assertEquals(18, second)
        assertEquals(2, script.sizes.size)
    }

    @Test
    fun `the first guess is not the answer, a poor match is heard again and the song that agrees wins`() {
        // The reel: twelve seconds named "Sunshine", at a skew of 0.0108. Nothing at 1.25 or 0.8. Then the song.
        val script = Script(listOf(junk(1), RecognitionOutcome.NoMatch, RecognitionOutcome.NoMatch, match("Blame", "1", 50.0), match("Blame", "1", 56.0)))
        val (verdict, second) = run(session(script), music(60.0, 2))
        val answer = verdict as ClipSession.Verdict.Answer
        assertEquals("Blame", answer.track.title)
        // Not at twelve: the window after it names the song once, the one after that agrees.
        assertEquals(24, second)
        assertEquals(5, script.sizes.size)
    }

    @Test
    fun `songs that agree with nothing end the listen with no answer, and within the requests Shazam allows`() {
        // Six different songs, once each, none clean, the rest of the speeds nothing: the reel.
        val answers = (1..5).flatMap { listOf(junk(it), RecognitionOutcome.NoMatch, RecognitionOutcome.NoMatch) } + junk(6)
        val script = Script(answers)
        val session = session(script)
        val (verdict, second) = run(session, music(75.0, 3))
        val nothing = verdict as ClipSession.Verdict.NoSong
        assertEquals(false, nothing.silent)
        assertNull(nothing.refused)
        assertEquals(ClipSession.MAX_REQUESTS, session.requests)
        assertTrue("went on to $second s", second <= ClipListen.MOST_HEARD_SECONDS)
        // No request after the ones allowed.
        assertEquals(ClipSession.MAX_REQUESTS, script.sizes.size)
    }

    @Test
    fun `a clip named at one speed is asked about at that speed from then on`() {
        // His reel of 18 Oct 2026, I'm Not Alone slowed to 0.8: nothing as heard, nothing at 1.25, the song at 0.8.
        fun slowed(offset: Double) = match("I'm Not Alone (Extended Mix)", "7", offset, -6.9E-5, 0.0)
        val script = Script(listOf(RecognitionOutcome.NoMatch, RecognitionOutcome.NoMatch, slowed(139.07), slowed(143.87)))
        val session = session(script)
        val (verdict, second) = run(session, music(40.0, 4))
        val answer = verdict as ClipSession.Verdict.Answer
        assertEquals("I'm Not Alone (Extended Mix)", answer.track.title)
        assertEquals(0.8, answer.track.heardAtRate, 1e-9)
        assertEquals(0.8, session.rate!!, 1e-9)
        assertEquals(18, second)
        // The second window was put back from 0.8 before it was asked: 12 s at 0.8 is 9.6 s of the song.
        assertEquals(4, script.sizes.size)
        assertEquals((12 * rate * 0.8).toInt(), script.sizes[3])
    }

    @Test
    fun `a guess a few percent off is asked once more one step closer, and that is the speed`() {
        // Put back from 1.25 the song is named at a skew of +0.036: it plays at 1.25 * 1.036.
        val near = match("Hello", "9", 30.0, 0.036, 0.036, "Adele")
        val script = Script(listOf(RecognitionOutcome.NoMatch, near, match("Hello", "9", 30.0, 0.0001, 0.0001, "Adele")))
        val session = session(script)
        run(session, music(14.0, 5))
        assertEquals(1.25 * 1.036, session.rate!!, 1e-9)
    }

    @Test
    fun `two clean windows of one song in different places are the answer at the second window`() {
        // A house track that loops its sample, or a remix that keeps the singer: named in both, at 49 and at 120 s.
        val script = Script(listOf(match("Blame", "1", 49.2), match("Blame", "1", 120.0)))
        val (verdict, second) = run(session(script), music(60.0, 6))
        assertEquals("Blame", (verdict as ClipSession.Verdict.Answer).track.title)
        assertEquals(18, second)
    }

    @Test
    fun `one clean window and nothing like it after is the answer only at the end`() {
        // The original named once, then the rest of the clip is something it does not know: a remix.
        val script = Script(listOf(match("Blame", "1", 49.2)))
        val (verdict, second) = run(session(script), music(75.0, 13))
        assertEquals("Blame", (verdict as ClipSession.Verdict.Answer).track.title)
        assertTrue("answered at $second s", second >= 40)
    }

    @Test
    fun `a clip that loops is heard through and then ends without an answer, a whole turn after it was seen`() {
        val script = Script((1..3).flatMap { listOf(junk(it), RecognitionOutcome.NoMatch, RecognitionOutcome.NoMatch) })
        val (verdict, second) = run(session(script), looped(music(7.3, 7), 60.0))
        val nothing = verdict as ClipSession.Verdict.NoSong
        assertTrue(nothing.why, "loop" in nothing.why)
        // Seen at about 11 s, held for a whole turn by 19 s, and not before 24 s.
        assertEquals(ClipSession.LOOP_OVER_SECONDS.toInt(), second)
    }

    @Test
    fun `a clip that stops before twelve seconds is asked about as it stopped, and a clean match is then the answer`() {
        val script = Script(listOf(match("Blame", "1", 49.2)))
        val (verdict, second) = run(session(script), music(8.0, 10) + ShortArray(10 * rate))
        assertEquals("Blame", (verdict as ClipSession.Verdict.Answer).track.title)
        assertEquals(8 + ClipListen.QUIET_END_SECONDS, second)
        assertEquals(1, script.sizes.size)
        assertEquals(8 * rate, script.sizes[0])
    }

    @Test
    fun `a clip that loops and names a song is over as soon as the repeat has held for a turn, not at twenty-four seconds`() {
        // Seen at about 11 s, held a turn later, about 19 s; the song is named at 11 s and again at 17 s.
        val script = Script(listOf(match("Blame", "1", 49.2), RecognitionOutcome.NoMatch, RecognitionOutcome.NoMatch, RecognitionOutcome.NoMatch))
        val (verdict, second) = run(session(script), looped(music(7.3, 11), 60.0))
        assertEquals("Blame", (verdict as ClipSession.Verdict.Answer).track.title)
        assertTrue("answered at $second s", second in 18..21)
    }

    @Test
    fun `a window named at a skew of 0_005 is not heard again at other speeds, one named by nothing or at 0_02 is`() {
        // 0.005 is within a hundredth of the recording's own speed: one request, not three.
        val good = Script(listOf(match("Summertime", "2", 194.4, 0.0053, 0.0062, "K-391")))
        run(session(good), music(12.0, 12))
        assertEquals(1, good.sizes.size)
        val poor = Script(listOf(match("Summertime", "2", 194.4, 0.02, 0.0062, "K-391")))
        run(session(poor), music(12.0, 12))
        assertEquals(3, poor.sizes.size)
    }

    @Test
    fun `Shazam refusing three times running ends the listen and says so`() {
        val script = Script(emptyList(), RecognitionOutcome.Failed("http 429"))
        val (verdict, second) = run(session(script), music(60.0, 8))
        val nothing = verdict as ClipSession.Verdict.NoSong
        assertEquals("http 429", nothing.refused)
        assertEquals(24, second)
        assertEquals(3, script.sizes.size)
    }

    @Test
    fun `only silence, nothing is asked and the answer says nothing sounded`() {
        val script = Script(emptyList())
        val (verdict, second) = run(session(script), ShortArray(80 * rate))
        val nothing = verdict as ClipSession.Verdict.NoSong
        assertTrue(nothing.silent)
        assertEquals(ClipListen.MOST_WAITED_SECONDS, second)
        assertEquals(0, script.sizes.size)
    }

    @Test
    fun `the engine hears the clip through ClipSession, and answers only what it says`() {
        val engine = java.io.File("src/main/java/com/dd3boh/outertune/recognition/RecognitionEngine.kt").readText()
        val clip = engine.substringAfter("private suspend fun listenToClip()").substringBefore("\n    }\n")
        assertTrue("the clip is not heard through ClipSession", "ClipSession(" in clip && "session.hear(" in clip)
        val conclude = engine.substringAfter("private suspend fun concludeClip(").substringBefore("\n    }\n")
        assertTrue("the answer is not shown by respondTo", "respondTo(RecognitionOutcome.Match(verdict.track)" in conclude)
        assertTrue("the listen does not stop on its verdict", "halt()" in conclude)
    }
}
