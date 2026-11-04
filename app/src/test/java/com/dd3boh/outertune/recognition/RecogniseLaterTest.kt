/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.recognition

import com.dd3boh.outertune.fingerprint.DATA_URI_PREFIX
import com.dd3boh.outertune.fingerprint.SIGNATURE_SAMPLE_RATE_HZ
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import kotlin.math.PI
import kotlin.math.sin

/**
 * What's playing with no connection (issue #48): the listens kept for later, what is left of them
 * after a restart, and what becomes of each once Shazam is asked: see [RecogniseLater].
 */
class RecogniseLaterTest {

    @get:Rule
    val folder = TemporaryFolder()

    private val day = 24 * 60 * 60 * 1000L

    /** A store over [file], as the app makes one at every start. */
    private fun store(file: File = File(folder.root, RecogniseLater.FILE)) = RecogniseLater(file)

    private fun print(n: Int, rate: Double = 1.0) = Fingerprint("${DATA_URI_PREFIX}print$n", 12_000L, rate)

    private fun listen(heardAt: Long, vararg rates: Double = doubleArrayOf(1.0)) =
        KeptListen(heardAt, rates.mapIndexed { i, rate -> print(heardAt.toInt() * 10 + i, rate) })

    private fun match(title: String, timeSkew: Double = 0.0) = RecognitionOutcome.Match(
        Recognised(title = title, artist = "Artist", artworkUrl = null, isrc = null, shazamKey = "k", timeSkew = timeSkew)
    )

    // ---- what is kept, and what survives a restart

    @Test
    fun `a listen kept offline is still there after a restart, fingerprints and all`() = runBlocking {
        val now = 100 * day
        store().keep(listen(now - 5_000, 1.0, 1.25, 0.8), now)
        // The app is closed and opened again: a new store over the same file.
        val again = store().due(now)
        assertEquals(1, again.size)
        assertEquals(now - 5_000, again[0].heardAt)
        assertEquals(listOf(1.0, 1.25, 0.8), again[0].prints.map { it.rate })
        assertEquals(listen(now - 5_000, 1.0, 1.25, 0.8).prints, again[0].prints)
        assertEquals(0, again[0].tries)
    }

    @Test
    fun `a try that counted is still counted after a restart`() = runBlocking {
        val now = 100 * day
        store().run {
            keep(listen(now - 1_000), now)
            settle(now - 1_000, RecogniseLater.Settled.Failed)
        }
        assertEquals(1, store().due(now).single().tries)
    }

    @Test
    fun `the list is oldest first, and a listen kept twice is kept once`() = runBlocking {
        val now = 100 * day
        val later = store()
        later.keep(listen(now - 1_000), now)
        later.keep(listen(now - 9_000), now)
        later.keep(listen(now - 1_000), now)
        assertEquals(listOf(now - 9_000, now - 1_000), later.due(now).map { it.heardAt })
    }

    @Test
    fun `past the most it keeps, the oldest goes`() = runBlocking {
        val now = 100 * day
        val later = store()
        for (i in 1..RecogniseLater.MOST + 3) later.keep(listen(now - 1_000_000 + i * 1_000L), now)
        val kept = later.due(now)
        assertEquals(RecogniseLater.MOST, kept.size)
        assertEquals(now - 1_000_000 + 4 * 1_000L, kept.first().heardAt)
    }

    @Test
    fun `a listen that waited thirty days is dropped`() = runBlocking {
        val now = 100 * day
        val later = store()
        later.keep(listen(now - 31 * day), now - 31 * day)
        later.keep(listen(now - 29 * day), now - 29 * day)
        assertEquals(listOf(now - 29 * day), later.due(now).map { it.heardAt })
        assertEquals(listOf(now - 29 * day), store().due(now).map { it.heardAt })
    }

    @Test
    fun `a file that will not parse gives nothing, and the next listen starts it over`() = runBlocking {
        val file = File(folder.root, RecogniseLater.FILE)
        file.writeText("{\"v\":1,\"waiting\":[{\"heardAt\":12")
        val now = 100 * day
        assertTrue(store(file).due(now).isEmpty())
        val later = store(file)
        later.keep(listen(now - 1_000), now)
        assertEquals(listOf(now - 1_000), store(file).due(now).map { it.heardAt })
    }

    @Test
    fun `a write the phone died in leaves the list as it was`() = runBlocking {
        val now = 100 * day
        store().keep(listen(now - 1_000), now)
        // Written whole beside the file and renamed into place: a phone that dies before the
        // rename leaves a part file and the old list.
        File(folder.root, RecogniseLater.FILE + ".new").writeText("{\"v\":1,\"wai")
        assertEquals(listOf(now - 1_000), store().due(now).map { it.heardAt })
    }

    @Test
    fun `nothing is left on the phone once the list is empty or cleared`() = runBlocking {
        val now = 100 * day
        val file = File(folder.root, RecogniseLater.FILE)
        val later = store(file)
        later.keep(listen(now - 1_000), now)
        later.keep(listen(now - 2_000), now)
        assertTrue(file.exists())
        later.settle(now - 1_000, RecogniseLater.Settled.Unknown)
        later.settle(now - 2_000, RecogniseLater.Settled.Named(match("Song").track))
        assertFalse(file.exists())
        later.keep(listen(now - 3_000), now)
        later.clear()
        assertFalse(file.exists())
        assertTrue(later.waiting.first().isEmpty())
    }

    @Test
    fun `clearing forgets even a file it could not read`() = runBlocking {
        val file = File(folder.root, RecogniseLater.FILE)
        file.writeText("not a list")
        store(file).clear()
        assertFalse(file.exists())
    }

    @Test
    fun `the screen sees a listen the moment it is kept`() = runBlocking {
        val now = 100 * day
        val later = store()
        assertTrue(later.waiting.first().isEmpty())
        later.keep(listen(now - 1_000), now)
        assertEquals(listOf(now - 1_000), later.waiting.first().map { it.heardAt })
    }

    // ---- what is kept of a window

    @Test
    fun `Listen once keeps the window as heard and put back from the two speeds it would try next`() {
        val samples = song(seconds = 12)
        val heard = Fingerprint.of(samples)
        val prints = RecogniseLater.printsFor(samples, heard, once = true)
        assertEquals(listOf(1.0) + SpeedRetry.ratesFor(0), prints.map { it.rate })
        assertEquals(heard, prints.first())
        // Keep listening never tries speeds, so it keeps the window as it was heard.
        assertEquals(listOf(heard), RecogniseLater.printsFor(samples, heard, once = false))
    }

    @Test
    fun `what is kept is the fingerprint, a few kilobytes, never the sound`() {
        val samples = song(seconds = 12)
        val print = Fingerprint.of(samples)
        assertEquals(12_000L, print.sampleMs)
        assertTrue(print.uri.startsWith(DATA_URI_PREFIX))
        // Twelve seconds of sound are 384,000 bytes.
        assertEquals(384_000, samples.size * 2)
        assertTrue("a fingerprint of ${print.uri.length} characters", print.uri.length < 32_000)
    }

    // ---- once Shazam is asked

    @Test
    fun `named as heard takes one request`() = runBlocking {
        val asked = mutableListOf<Double>()
        val outcome = RecogniseLater.lookUp(listen(1_000, 1.0, 1.25, 0.8)) { asked += it.rate; match("Song") }
        assertEquals(listOf(1.0), asked)
        assertEquals(RecogniseLater.Settled.Named(match("Song").track), RecogniseLater.verdict(outcome, tries = 0))
    }

    @Test
    fun `named only once put back from a speed, it carries that speed`() = runBlocking {
        val asked = mutableListOf<Double>()
        val outcome = RecogniseLater.lookUp(listen(1_000, 1.0, 1.25, 0.8)) {
            asked += it.rate
            if (it.rate == 0.8) match("I'm Not Alone") else RecognitionOutcome.NoMatch
        }
        assertEquals(listOf(1.0, 1.25, 0.8), asked)
        assertEquals(0.8, ((outcome as RecognitionOutcome.Match).track.heardAtRate), 0.0)
    }

    @Test
    fun `a match that needed stretching to fit a guessed speed is not taken`() = runBlocking {
        val outcome = RecogniseLater.lookUp(listen(1_000, 1.0, 1.25)) {
            if (it.rate == 1.25) match("A radio edit", timeSkew = 0.035) else RecognitionOutcome.NoMatch
        }
        assertEquals(RecognitionOutcome.NoMatch, outcome)
    }

    @Test
    fun `nothing Shazam knows at any speed is taken off the list`() = runBlocking {
        val now = 100 * day
        val later = store()
        later.keep(listen(now - 1_000, 1.0, 1.25, 0.8), now)
        val outcome = RecogniseLater.lookUp(later.due(now).single()) { RecognitionOutcome.NoMatch }
        val verdict = RecogniseLater.verdict(outcome, tries = 0)
        assertEquals(RecogniseLater.Settled.Unknown, verdict)
        later.settle(now - 1_000, verdict)
        assertTrue(store().due(now).isEmpty())
    }

    @Test
    fun `no connection again leaves it as it was, and the rest waits for the next run`() = runBlocking {
        val now = 100 * day
        val later = store()
        later.keep(listen(now - 1_000, 1.0, 1.25, 0.8), now)
        val asked = mutableListOf<Double>()
        val outcome = RecogniseLater.lookUp(later.due(now).single()) {
            asked += it.rate
            if (it.rate == 1.0) RecognitionOutcome.NoMatch else RecognitionOutcome.Failed("network", unsent = it)
        }
        // The first failure ends it: nothing more is asked of a connection that is not there.
        assertEquals(listOf(1.0, 1.25), asked)
        val verdict = RecogniseLater.verdict(outcome, tries = 0)
        assertEquals(RecogniseLater.Settled.Unanswered, verdict)
        later.settle(now - 1_000, verdict)
        assertEquals(0, store().due(now).single().tries)
    }

    @Test
    fun `Shazam refusing or down is not held against the listen`() {
        for (reason in listOf("network", "http 429", "http 500", "http 503")) {
            assertEquals(reason, RecogniseLater.Settled.Unanswered, RecogniseLater.verdict(RecognitionOutcome.Failed(reason), tries = 4))
        }
    }

    @Test
    fun `an answer that cannot be read counts, and the fifth gives up`() = runBlocking {
        val now = 100 * day
        val later = store()
        later.keep(listen(now - 1_000), now)
        repeat(RecogniseLater.MAX_TRIES) { i ->
            val waiting = later.due(now).single()
            assertEquals(i, waiting.tries)
            val verdict = RecogniseLater.verdict(RecognitionOutcome.Failed("parse"), waiting.tries)
            assertEquals(
                if (i < RecogniseLater.MAX_TRIES - 1) RecogniseLater.Settled.Failed else RecogniseLater.Settled.GivenUp,
                verdict,
            )
            later.settle(waiting.heardAt, verdict)
        }
        assertTrue(store().due(now).isEmpty())
    }

    @Test
    fun `a listen named while others wait leaves only the others`() = runBlocking {
        val now = 100 * day
        val later = store()
        later.keep(listen(now - 3_000), now)
        later.keep(listen(now - 2_000), now)
        later.keep(listen(now - 1_000), now)
        later.settle(now - 2_000, RecogniseLater.Settled.Named(match("Song").track))
        assertEquals(listOf(now - 3_000, now - 1_000), store().due(now).map { it.heardAt })
    }

    // ---- what Keep listening says while it keeps what it hears

    private val noConnection = RecognitionOutcome.Failed("network")

    @Test
    fun `Keep listening says it is keeping what it hears from the window that was kept`() {
        assertTrue(RecogniseLater.offlineAfter(was = false, outcome = noConnection, kept = true))
        assertTrue(RecogniseLater.offlineAfter(was = true, outcome = noConnection, kept = true))
    }

    @Test
    fun `it stops saying so once Shazam answers, with a song or without one`() {
        assertFalse(RecogniseLater.offlineAfter(was = true, outcome = match("Song"), kept = false))
        assertFalse(RecogniseLater.offlineAfter(was = true, outcome = RecognitionOutcome.NoMatch, kept = false))
    }

    @Test
    fun `silence, a refusal or a window that could not be kept change nothing`() {
        for (outcome in listOf(RecognitionOutcome.Failed("silence"), RecognitionOutcome.Failed("http 429"), noConnection)) {
            assertTrue(outcome.toString(), RecogniseLater.offlineAfter(was = true, outcome = outcome, kept = false))
            assertFalse(outcome.toString(), RecogniseLater.offlineAfter(was = false, outcome = outcome, kept = false))
        }
    }

    /**
     * Something with peaks in it to fingerprint: a tune of tones that changes every half second,
     * at the fingerprint's own rate.
     */
    private fun song(seconds: Int): ShortArray {
        val notes = doubleArrayOf(440.0, 523.3, 659.3, 784.0, 587.3, 880.0, 698.5, 493.9)
        return ShortArray(seconds * SIGNATURE_SAMPLE_RATE_HZ) { i ->
            val t = i.toDouble() / SIGNATURE_SAMPLE_RATE_HZ
            val f = notes[(t * 2).toInt() % notes.size]
            (6000 * sin(2 * PI * f * t) + 3000 * sin(2 * PI * f * 1.5 * t)).toInt().toShort()
        }
    }
}
