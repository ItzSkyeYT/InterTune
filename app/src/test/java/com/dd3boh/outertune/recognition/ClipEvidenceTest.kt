/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.recognition

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * One window is a guess; a song is what the windows of a listen agree on (his reel of an organ
 * playing a dance tune, 31 Oct 2026: fourteen songs named, none twice in the same place). The skews
 * and offsets below are the ones Shazam gave for that reel and for the songs that were really playing
 * in the earlier replays (experiments/features/recognition-reels/probe, experiments/recognition).
 */
class ClipEvidenceTest {

    private fun track(
        title: String, key: String, offset: Double, time: Double = 0.0001, frequency: Double = 0.0002,
        artist: String? = "Calvin Harris",
    ) = Recognised(title, artist, null, null, key, offset, time, frequency)

    private fun seen(window: Int, from: Double, track: Recognised, rate: Double = 1.0, length: Double = 12.0) =
        ClipEvidence.Sighting(window, from, length, rate, track.copy(heardAtRate = rate))

    private fun evidence(windows: Int, vararg sightings: ClipEvidence.Sighting) = ClipEvidence().also { e ->
        repeat(windows) { e.nextWindow() }
        sightings.forEach { e.note(it) }
    }

    @Test
    fun `two windows of different sound that put a song where the time between them says agree`() {
        // Blame, replayed: 49.2 s in at the first window, six seconds of sound later 55.2.
        val e = evidence(
            2,
            seen(0, 0.0, track("Blame", "1", 49.2)),
            seen(1, 6.0, track("Blame", "1", 55.2)),
        )
        val song = e.agreed()
        assertNotNull(song)
        assertEquals(2, song!!.chain.size)
        assertTrue(song.convincing)
    }

    @Test
    fun `a song that lands somewhere else the second time does not agree with itself`() {
        // 4.8 s of the song in 6 s of sound is no copy of it; nor is 10 s.
        assertNull(evidence(2, seen(0, 0.0, track("Blame", "1", 49.2)), seen(1, 6.0, track("Blame", "1", 60.0))).agreed())
        assertNull(evidence(2, seen(0, 0.0, track("Blame", "1", 49.2)), seen(1, 6.0, track("Blame", "1", 30.0))).agreed())
    }

    @Test
    fun `the reel's Nobody Told Me came back in four windows and agreed with nothing`() {
        // 223 s in at the first window (a second match, not the top), 63 at 48 s, 77 at 60 s, 87 at 96 s.
        // 63 to 77 in twelve seconds is 14: a second off what the clock says, not the second and a half allowed.
        fun nobody(offset: Double, time: Double) = track("Nobody Told Me", "831441507", offset, time, 0.005, "Notre Dame")
        val e = evidence(
            9,
            seen(0, 0.0, nobody(222.9, -0.0004)),
            seen(4, 48.0, nobody(63.3, -0.0074)),
            seen(5, 60.0, nobody(77.4, 0.0017)),
            seen(8, 96.0, nobody(87.0, 0.0263)),
        )
        assertNull(e.agreed())
        assertNull(e.last())
    }

    @Test
    fun `windows that share more than half of their sound are not evidence of each other`() {
        // Four seconds apart, twelve long: eight seconds in common.
        val e = evidence(2, seen(0, 0.0, track("Blame", "1", 49.2)), seen(1, 4.0, track("Blame", "1", 53.2)))
        assertNull(e.agreed())
        // Six seconds apart is half: the next window of a listen.
        val half = evidence(2, seen(0, 0.0, track("Blame", "1", 49.2)), seen(1, 6.0, track("Blame", "1", 55.2)))
        assertNotNull(half.agreed())
    }

    @Test
    fun `in a clip that loops, the same stretch heard again is one stretch`() {
        // A seven second video heard through windows of twelve: every window holds the whole of it.
        val windows = arrayOf(
            seen(0, 0.0, track("Blame", "1", 49.2)),
            seen(1, 6.0, track("Blame", "1", 55.2)),
        )
        val loop = ClipEvidence().apply { repeat(2) { nextWindow() }; windows.forEach { note(it) }; loopSeconds = 7.3 }
        assertNull(loop.agreed())
        // The same two windows of a clip that is not a loop are two.
        assertNotNull(evidence(2, *windows).agreed())
        // A loop of twenty seconds, windows of twelve: six seconds apart still half, fourteen apart too.
        for (apart in listOf(6.0, 10.0, 14.0)) {
            val long = ClipEvidence().apply {
                repeat(2) { nextWindow() }
                note(seen(0, 0.0, track("Blame", "1", 49.2)))
                note(seen(1, apart, track("Blame", "1", 49.2 + apart)))
                loopSeconds = 20.0
            }
            assertNotNull("windows $apart s apart in a 20 s loop", long.agreed())
        }
        // But two a turn and a bit apart hold the same sound.
        val again = ClipEvidence().apply {
            repeat(2) { nextWindow() }
            note(seen(0, 0.0, track("Blame", "1", 49.2)))
            note(seen(1, 22.0, track("Blame", "1", 51.2)))
            loopSeconds = 20.0
        }
        assertNull(again.agreed())
    }

    @Test
    fun `a match is convincing when both skews are under 0_002, good under 0_01, and poor otherwise`() {
        fun q(time: Double, frequency: Double) = ClipEvidence.qualityOf(track("X", "1", 0.0, time, frequency))
        // Blame put back from 1.25, measured.
        assertEquals(ClipEvidence.Quality.CONVINCING, q(-1.9276142E-4, -8.0525875E-5))
        assertEquals(ClipEvidence.Quality.CONVINCING, q(-0.0019, 0.0))
        // The reel's windows, all of them: an organ tuned a little sharp.
        assertEquals(ClipEvidence.Quality.POOR, q(0.0031, 0.0119))
        assertEquals(ClipEvidence.Quality.GOOD, q(-0.0032, 0.0038))
        assertEquals(ClipEvidence.Quality.POOR, q(0.0108, 0.0035))
        assertEquals(ClipEvidence.Quality.POOR, q(0.0212, 0.0144))
        // One skew clean and the other not is a stretched match.
        assertEquals(ClipEvidence.Quality.GOOD, q(0.0001, 0.005))
        assertEquals(ClipEvidence.Quality.POOR, q(0.0001, 0.02))
    }

    @Test
    fun `a song put back from another speed agrees at that speed and nowhere else`() {
        // The reel of 18 Oct 2026, I'm Not Alone slowed to 0.8: twenty-four seconds of sound is 19.2 of the song.
        fun slowed(offset: Double) = track("I'm Not Alone (Extended Mix)", "7", offset, -6.9E-5, 0.0)
        val yes = evidence(2, seen(0, 0.0, slowed(139.07), 0.8), seen(1, 24.0, slowed(158.27), 0.8))
        assertNotNull(yes.agreed())
        // At one speed and then another it is two guesses, not two windows.
        val no = evidence(2, seen(0, 0.0, slowed(139.07), 0.8), seen(1, 24.0, slowed(158.27), 1.25))
        assertNull(no.agreed())
        // And at 0.8 the song did not move at the speed of the clock.
        assertNull(evidence(2, seen(0, 0.0, slowed(139.07), 0.8), seen(1, 24.0, slowed(163.07), 0.8)).agreed())
    }

    @Test
    fun `one clean match is not an answer until most of three windows back it up, and no other song is nearly as good`() {
        val a = track("Blame", "1", 49.2)
        // Asked three times, named once: a remix keeping the singer's voice, a quiet stretch.
        assertNull(evidence(3, seen(0, 0.0, a)).settled())
        // Named in two of three, in two places: the song moves about, the song is still it.
        val moved = evidence(3, seen(0, 0.0, a), seen(1, 6.0, track("Blame", "1", 90.0)))
        assertNull("agreed on nothing", moved.agreed())
        assertNotNull(moved.settled())
        // Two asked, and both name it cleanly: a house track that loops its sample is named at 173, 171 and 57 s in.
        assertNotNull(evidence(2, seen(0, 0.0, a), seen(1, 6.0, track("Blame", "1", 90.0))).settled())
        // Two asked and one of them names it: too early.
        assertNull(evidence(2, seen(0, 0.0, a)).settled())
        assertNull(evidence(2, seen(0, 0.0, a), seen(1, 6.0, track("Blame", "1", 90.0, 0.02, 0.02))).settled())
        // A rival named about as well takes it back.
        val rival = evidence(
            3,
            seen(0, 0.0, a), seen(1, 6.0, track("Blame", "1", 90.0)),
            seen(1, 6.0, track("Summertime", "2", 12.0, 0.0001, 0.0001, "K-391")),
            seen(2, 12.0, track("Summertime", "2", 99.0, 0.0001, 0.0001, "K-391")),
        )
        assertNull(rival.settled())
        // A match that is not convincing is no answer on its own, whatever its share.
        val poor = evidence(3, seen(0, 0.0, track("Sunshine", "3", 280.3, 0.0108, 0.0035)), seen(1, 6.0, track("Sunshine", "3", 80.0, 0.0108, 0.0035)))
        assertNull(poor.settled())
    }

    @Test
    fun `when the listen is over only a song two windows agree on, or one named cleanly, is an answer`() {
        // The reel: six songs, once each, none clean.
        val reel = evidence(
            6,
            seen(0, 0.0, track("Sunshine (Original Vocal Mix)", "40534543", 280.3, 0.0108, 0.0035, "Dance Nation")),
            seen(1, 6.0, track("Summertime", "151189185", 194.4, 0.0053, 0.0062, "K-391")),
            seen(2, 12.0, track("You Are the Girl", "11093488", 17.8, -0.0032, 0.0038, "The Cars")),
            seen(3, 18.0, track("Without You (Drove Remix)", "709991400", 185.3, 0.0078, 0.0061, "Kygo")),
            seen(5, 30.0, track("Unity", "154343873", 185.9, 0.0024, 0.0059, "TheFatRat")),
        )
        assertNull(reel.last())
        assertNull(reel.settled())
        assertNull(reel.agreed())
        // A song named cleanly once, and nothing else, is named at the end.
        assertEquals("Blame", evidence(4, seen(1, 6.0, track("Blame", "1", 49.2))).last()!!.best.track.title)
        // And a clean one is the answer over a better supported poor one.
        val mixed = evidence(
            4,
            seen(0, 0.0, track("Blame", "1", 49.2)),
            seen(1, 6.0, track("Sunshine", "3", 100.0, 0.02, 0.02)),
            seen(2, 12.0, track("Sunshine", "3", 112.0, 0.02, 0.02)),
        )
        assertEquals("Blame", mixed.last()!!.best.track.title)
    }

    @Test
    fun `the song is named by its cleanest window, with the speed it was heard at`() {
        val e = evidence(
            2,
            seen(0, 0.0, track("Blame", "1", 49.2, 0.003, 0.003)),
            seen(1, 6.0, track("Blame", "1", 55.2, 0.0001, 0.0001), 1.0),
        )
        val song = e.agreed()!!
        assertEquals(6.0, song.best.fromS, 0.0)
        assertEquals(55.2, song.best.track.offsetSeconds, 0.0)
    }

    @Test
    fun `the same song is told by its key, or by its title and first artist for its other entries`() {
        fun t(title: String, artist: String?, key: String?) = Recognised(title, artist, null, null, key)
        assertTrue(ClipEvidence.same(t("Blame", "Calvin Harris", "1"), t("Blame (Radio Edit)", "Someone", "1")))
        assertTrue(ClipEvidence.same(t("Blame", "Calvin Harris", "1"), t("Blame (feat. John Newman)", "Calvin Harris, John Newman", "2")))
        assertFalse(ClipEvidence.same(t("Blame", "Calvin Harris", "1"), t("Blame", "Jimmy Eat World", "2")))
        assertFalse(ClipEvidence.same(t("Blame", null, null), t("Blame", null, null)))
        assertFalse(ClipEvidence.same(t("Blame", "Calvin Harris", "1"), t("Summertime", "Calvin Harris", "3")))
    }
}
