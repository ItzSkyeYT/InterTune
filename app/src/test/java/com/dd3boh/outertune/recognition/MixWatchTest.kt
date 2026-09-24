/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.recognition

import com.zionhuang.innertube.models.Artist
import com.zionhuang.innertube.models.SongItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The mashup check, on the run that asked for it.
 *
 * The timeline below is the phone's log of 24 Sep, 10:13 to 10:16, while "Linkin Park / Slipknot /
 * Eminem - Damage [MASHUP]" played: twelve second windows, one of them unmatched and so absent.
 * The search results are what MixSearchProbe got back for the two queries the same day, in France.
 */
class MixWatchTest {

    private val faint = "faint" to ("Faint" to "Linkin Park")
    private val noLove = "nolove" to ("No Love (feat. Lil Wayne)" to "Eminem")
    private val lliving = "lliving" to ("Lliving Life Mix" to null)

    private fun sighting(song: Pair<String, Pair<String, String?>>, second: Int) =
        MixWatch.Sighting(song.first, song.second.first, song.second.second, second * 1000L)

    /** Seconds after 10:13:06 at which each window was heard. */
    private val run = listOf(
        faint to 0, faint to 12,
        // 10:13:54 matched nothing.
        faint to 60, faint to 72, faint to 84, faint to 96, faint to 108, faint to 120,
        noLove to 133, lliving to 144,
        faint to 157, faint to 168, faint to 180,
    )

    @Test
    fun theDamageRunIsAMixOnceFaintComesBack() {
        val watch = MixWatch()
        val verdicts = run.map { (song, at) -> watch.observe(sighting(song, at)) }
        // Nothing until Faint returns after No Love and the unsure window.
        assertTrue(verdicts.subList(0, 10).all { it == null })
        val mix = verdicts[10]
        assertNotNull(mix)
        // Two windows of something else in a row is not one bad window.
        assertTrue(mix!!.strong)
        assertEquals(listOf("faint", "nolove", "lliving"), mix.pieces.map { it.key })
    }

    @Test
    fun aPlaylistMovingOnIsNeverAMix() {
        val watch = MixWatch()
        val a = "a" to ("A" to "x"); val b = "b" to ("B" to "y"); val c = "c" to ("C" to "z")
        val timeline = listOf(a to 0, a to 12, a to 24, b to 36, b to 48, b to 60, c to 72, c to 84)
        assertTrue(timeline.all { (song, at) -> watch.observe(sighting(song, at)) == null })
    }

    @Test
    fun oneOddWindowIsOnlyWorthALook() {
        val watch = MixWatch()
        val a = "a" to ("A" to "x"); val odd = "odd" to ("Odd" to "y")
        listOf(a to 0, a to 12, odd to 24).forEach { (s, at) -> assertNull(watch.observe(sighting(s, at))) }
        val mix = watch.observe(sighting(a, 36))
        assertNotNull(mix)
        assertFalse(mix!!.strong)
    }

    @Test
    fun aSecondInterruptionMakesItSure() {
        val watch = MixWatch()
        val a = "a" to ("A" to "x"); val b = "b" to ("B" to "y"); val c = "c" to ("C" to "z")
        listOf(a to 0, b to 12).forEach { (s, at) -> watch.observe(sighting(s, at)) }
        assertFalse(watch.observe(sighting(a, 24))!!.strong)
        watch.observe(sighting(c, 36))
        assertTrue(watch.observe(sighting(a, 48))!!.strong)
    }

    @Test
    fun skippingBackToThePreviousSongIsNotSure() {
        // Two windows of one other song and then the first again is also someone going back a track.
        val watch = MixWatch()
        val a = "a" to ("A" to "x"); val b = "b" to ("B" to "y")
        listOf(a to 0, a to 12, a to 24, b to 36, b to 48).forEach { (s, at) -> watch.observe(sighting(s, at)) }
        assertFalse(watch.observe(sighting(a, 60))!!.strong)
    }

    @Test
    fun oneSongUnderTwoKeysIsOneSong() {
        val twin = "faint2" to ("Faint" to "Linkin Park")
        val pieces = listOf(sighting(faint, 0), sighting(twin, 12))
        assertEquals(1, MixSearch.distinctSongs(pieces).size)
        assertTrue(MixSearch.queries(MixSearch.distinctSongs(pieces)).isEmpty())
    }

    @Test
    fun twoKeysForOneSongInTheGapAreStillOneSong() {
        val watch = MixWatch()
        val a = "a" to ("A" to "x"); val b1 = "b1" to ("B" to "y"); val b2 = "b2" to ("B" to "y")
        listOf(a to 0, a to 12, a to 24, b1 to 36, b2 to 48).forEach { (s, at) -> watch.observe(sighting(s, at)) }
        assertFalse(watch.observe(sighting(a, 60))!!.strong)
    }

    @Test
    fun aSongComingRoundAgainMuchLaterIsNotAMix() {
        val watch = MixWatch()
        val a = "a" to ("A" to "x"); val b = "b" to ("B" to "y")
        watch.observe(sighting(a, 0))
        watch.observe(sighting(b, 200))
        assertNull(watch.observe(sighting(a, 400)))
    }

    @Test
    fun theQueriesAreTheTwoMostHeardPieces() {
        val watch = MixWatch()
        val mix = run.map { (song, at) -> watch.observe(sighting(song, at)) }[10]!!
        assertEquals(listOf("Faint No Love mashup", "Linkin Park Eminem mashup"), MixSearch.queries(mix.pieces))
    }

    private fun video(id: String, title: String, channel: String) =
        SongItem(id = id, title = title, artists = listOf(Artist(name = channel, id = null)), thumbnail = "")

    private val damage = video("-R0eNs3xMGs", "Linkin Park / Slipknot / Eminem - Damage [OFFICIAL MUSIC VIDEO] [FULL-HD] [MASHUP]", "XYClanKILLER2")
    private val damageLyrics = video("ZlWH8zZQYcs", "Linkin Park x Slipknot x Eminem - Damage (Faint x Nero Forte x No Love) (Mashup) (Lyrics)", "WiFiIsMyWiFe")
    private val otherMashup = video("Y_fjMQ6DtGg", "Eminem & LINKIN PARK - No Love / Faint (feat. Lil Wayne) (No Love x Faint Mashup) (Explicit) (2160p)", "Radioactive Foxy")
    private val psychofaint = video("hyGjjy2N2z0", "Linkin Park / Slipknot - Psychofaint 2.0 [OFFICIAL MUSIC VIDEO] [FULL-HD] [MASHUP]", "XYClanKILLER2")
    private val breakingTheHabit = video("pjGW3r3u_0g", "Linkin Park, Eminem & Evanescence - Breaking the Habit (Pxndo & Echale Mojo Remix)", "PXNDO REMIX")

    @Test
    fun twoMashupsOfTheSameSongsAreAChoiceNotAGuess() {
        val watch = MixWatch()
        val pieces = run.map { (song, at) -> watch.observe(sighting(song, at)) }[10]!!.pieces
        val byTitles = listOf(damage, damageLyrics, otherMashup, psychofaint)
        val byArtists = listOf(damage, breakingTheHabit)
        val ranked = MixSearch.rank(pieces, listOf(byTitles, byArtists))
        val ids = ranked.map { it.first.id }
        // Psychofaint names one piece, which is not enough to count.
        assertFalse(psychofaint.id in ids)
        // The upload that was playing is among the choices...
        assertTrue(damage.id in ids.take(3))
        // ...but tied at the top with a different mashup of the same two songs, so nothing is taken.
        assertNull(MixSearch.clearWinner(ranked))
    }

    @Test
    fun oneMashupWellAheadIsTaken() {
        val pieces = listOf(sighting(faint, 0), sighting(noLove, 12))
        val ranked = MixSearch.rank(pieces, listOf(listOf(damageLyrics, breakingTheHabit)))
        assertEquals(damageLyrics.id, MixSearch.clearWinner(ranked)?.id)
    }

    /** Faint's windows from the second Damage run, 24 Sep 12:56: offset, skew, seconds in. */
    private val faintCuts = listOf(
        Triple(-2.2, -0.0016, 0), Triple(31.1, -0.0002, 12), Triple(43.1, 0.0003, 24),
        Triple(12.4, 0.0008, 36), Triple(24.4, 0.0018, 48), Triple(36.5, 0.0005, 60),
        Triple(87.5, 0.0008, 72), Triple(60.4, 0.0018, 84),
    )

    @Test
    fun theDamageRunIsCutUpAMinuteIn() {
        val watch = CutWatch()
        val verdicts = faintCuts.map { (offset, skew, at) -> watch.observe("faint", offset, skew, at * 1000L) }
        // Two cuts that held, from 31 s and from 12 s: known at the fifth window, 48 s in, where the
        // mashup check needed three and a half minutes and a second song.
        assertEquals(CutWatch.Verdict.FIRST, verdicts[4])
        assertTrue(verdicts.subList(0, 4).all { it == CutWatch.Verdict.NONE })
    }

    @Test
    fun aSongPlayedStraightIsNeverCut() {
        val watch = CutWatch()
        assertTrue((0..15).all { i -> watch.observe("a", 10.0 + i * 12, 0.0, i * 12_000L) == CutWatch.Verdict.NONE })
    }

    @Test
    fun aChorusPlacedAtTheWrongRepeatIsNotACut() {
        val watch = CutWatch()
        // 60, 72, then the second chorus matched as the first at 45, then back on time at 96.
        val windows = listOf(60.0 to 0, 72.0 to 12, 45.0 to 24, 96.0 to 36, 108.0 to 48)
        assertTrue(windows.all { (offset, at) -> watch.observe("a", offset, 0.0, at * 1000L) == CutWatch.Verdict.NONE })
    }

    @Test
    fun aRadioEditsTwoCutsMinutesApartAreNotAnEdit() {
        val watch = CutWatch()
        val windows = listOf(10.0 to 0, 22.0 to 12, 60.0 to 24, 72.0 to 36, 84.0 to 48, 96.0 to 60,
            108.0 to 72, 120.0 to 84, 132.0 to 96, 144.0 to 108, 156.0 to 120, 200.0 to 132, 212.0 to 144)
        assertTrue(windows.all { (offset, at) -> watch.observe("a", offset, 0.0, at * 1000L) == CutWatch.Verdict.NONE })
    }

    @Test
    fun aSpedUpCopyMovesFasterWithoutCutting() {
        val watch = CutWatch()
        // Nightcore at 1.25: Shazam matches the original and reports the speed as its skew.
        assertTrue((0..10).all { i -> watch.observe("a", 5.0 + i * 15, 0.25, i * 12_000L) == CutWatch.Verdict.NONE })
    }

    @Test
    fun remixesAndMashupsThatNameTheSongAreOffered() {
        val piece = sighting(faint, 0)
        assertEquals(listOf("Faint LINKIN PARK mashup", "Faint LINKIN PARK remix").map { it.lowercase() },
            MixSearch.singleQueries(piece.copy(artist = "LINKIN PARK")).map { it.lowercase() })
        val plain = video("2mXNRsyTitA", "Faint", "Linkin Park")
        val skillet = video("2rE9qdUKAGM", "Skillet X Linkin Park - Monster/Faint [MASHUP]", "BlueDragonCody Productions")
        val choices = MixSearch.rankSingle(piece, listOf(listOf(damage, skillet, plain), listOf(damage)))
        // The plain song is not a remix of itself; the rest keep YouTube's order, each once.
        assertEquals(listOf(damage.id, skillet.id), choices.map { it.id })
    }

    @Test
    fun aReturnPartwayIntoTheSongIsSure() {
        // The second run of 24 Sep, 13:04:50 on: Party Rock Anthem, Memories three times, and Party
        // Rock Anthem back 76 s in. One other song in the gap, which alone would be a skip back.
        val watch = MixWatch()
        val party = "party" to ("Party Rock Anthem (feat. Lauren Bennett & GoonRock)" to "LMFAO")
        val memories = "memories" to ("Memories (feat. Kid Cudi)" to "David Guetta")
        fun at(song: Pair<String, Pair<String, String?>>, second: Int, offset: Double) =
            MixWatch.Sighting(song.first, song.second.first, song.second.second, second * 1000L, offset)
        listOf(at(party, 0, 23.5), at(party, 12, 35.5), at(memories, 24, 91.8), at(memories, 36, 36.4),
            at(memories, 48, 43.1)).forEach { assertNull(watch.observe(it)) }
        assertTrue(watch.observe(at(party, 60, 76.2))!!.strong)
    }

    @Test
    fun goingBackATrackStartsItAgain() {
        val watch = MixWatch()
        val a = "a" to ("A" to "x"); val b = "b" to ("B" to "y")
        listOf(a to 0, a to 12, b to 24, b to 36).forEach { (s, at) -> watch.observe(sighting(s, at)) }
        val back = MixWatch.Sighting("a", "A", "x", 48_000L, offsetSeconds = 4.0)
        assertFalse(watch.observe(back)!!.strong)
    }

    @Test
    fun kendricksDnaPlayedStraightIsNotCut() {
        // 13:01 to 13:04 on 24 Sep, including two windows Shazam read at -3 % speed, the first one
        // 2 s off at the very start, and a restart from the top at 13:03:26.
        val windows = listOf(
            Triple(-5.9, -0.0294, 0), Triple(3.8, 0.0005, 12), Triple(15.9, -0.0004, 24),
            Triple(29.1, -0.0273, 36), Triple(39.9, 0.0001, 48), Triple(51.9, -0.0002, 60),
            Triple(63.8, -0.0006, 72), Triple(75.8, 0.0002, 84), Triple(87.9, 0.0003, 96),
            Triple(99.8, 0.0001, 108), Triple(2.1, -0.0001, 120), Triple(14.1, 0.0002, 132),
            Triple(26.1, 0.0, 144), Triple(38.1, -0.0003, 156), Triple(50.1, -0.0006, 168),
        )
        val watch = CutWatch()
        assertTrue(windows.all { (offset, skew, at) -> watch.observe("dna", offset, skew, at * 1000L) == CutWatch.Verdict.NONE })
    }

    @Test
    fun memoriesInTheSecondMashupIsCutUpAtItsThirdWindow() {
        val watch = CutWatch()
        assertEquals(CutWatch.Verdict.NONE, watch.observe("m", 91.8, 0.0001, 0))
        assertEquals(CutWatch.Verdict.NONE, watch.observe("m", 36.4, 0.0011, 12_000))
        assertEquals(CutWatch.Verdict.FIRST, watch.observe("m", 43.1, 0.0001, 24_000))
    }

    /**
     * The R3hab remix of Pray to God, replayed from a file on 24 Sep: Shazam named the remix, with
     * the original (5 to 7 % faster) and Better Off Alone, a sample, in between. Seconds in, key,
     * offset, skew.
     */
    private val r3hab = listOf(
        Triple(0, "remix", 9.6 to -0.0013), Triple(12, "remix", 21.6 to -0.0002), Triple(24, "remix", 33.6 to 0.0),
        Triple(36, "original", 12.9 to 0.0533), Triple(48, "remix", 57.6 to -0.0001), Triple(60, "original", 38.4 to 0.0484),
        Triple(72, "remix", 81.6 to 0.0), Triple(84, "remix", 93.6 to 0.0), Triple(96, "remix", 105.6 to 0.0001),
        Triple(108, "remix", 117.6 to 0.0), Triple(120, "sample", 128.1 to -0.0183), Triple(132, "original", 79.2 to 0.0668),
        Triple(144, "original", 92.0 to 0.0669), Triple(156, "original", 125.7 to 0.0313), Triple(168, "remix", 177.6 to 0.0001),
        Triple(180, "remix", 189.6 to 0.0002),
    )

    private fun r3habSighting(at: Int, key: String, offset: Double, skew: Double) = MixWatch.Sighting(
        key, when (key) { "remix" -> "Pray to God (feat. HAIM) [R3hab Remix]"; "original" -> "Pray to God (feat. HAIM)"; else -> "Better Off Alone" },
        if (key == "sample") "Alice Deejay" else "Calvin Harris", at * 1000L, offset, skew,
    )

    @Test
    fun aRemixPlayingStraightIsNotAMashupOfWhatIsInIt() {
        val watch = MixWatch()
        val verdicts = r3hab.map { (at, key, os) -> watch.observe(r3habSighting(at, key, os.first, os.second)) }
        assertTrue(verdicts.all { it == null })
    }

    @Test
    fun theRemixIsTheSteadySongUnderTheOriginal() {
        val watch = MixWatch()
        r3hab.take(13).forEach { (at, key, os) -> watch.observe(r3habSighting(at, key, os.first, os.second)) }
        // At 132 s, when the original is back: the remix has played straight for the last minute.
        assertEquals("remix", watch.steadyHost(132_000))
    }

    @Test
    fun aSwitchUnderASteadySongCountsOnceItDoesNotGoOn() {
        // A for two windows, B straight for four, then A back 90 s in and on from there. B never
        // goes on, so after the wait the return is a switch after all.
        val watch = MixWatch()
        val a = listOf(0 to 10.0, 12 to 22.0)
        val b = listOf(24 to 30.0, 36 to 42.0, 48 to 54.0, 60 to 66.0)
        a.forEach { (at, o) -> assertNull(watch.observe(MixWatch.Sighting("a", "A", "x", at * 1000L, o))) }
        b.forEach { (at, o) -> assertNull(watch.observe(MixWatch.Sighting("b", "B", "y", at * 1000L, o))) }
        val after = listOf(72 to 90.0, 84 to 102.0, 96 to 114.0, 108 to 126.0, 120 to 138.0)
            .map { (at, o) -> watch.observe(MixWatch.Sighting("a", "A", "x", at * 1000L, o)) }
        assertTrue(after.take(4).all { it == null })
        assertNotNull(after[4])
    }

    @Test
    fun aNewSongAfterTheReturnDropsWhatWasHeld() {
        val watch = MixWatch()
        listOf(0 to 10.0, 12 to 22.0).forEach { (at, o) -> watch.observe(MixWatch.Sighting("a", "A", "x", at * 1000L, o)) }
        listOf(24 to 30.0, 36 to 42.0, 48 to 54.0, 60 to 66.0).forEach { (at, o) -> watch.observe(MixWatch.Sighting("b", "B", "y", at * 1000L, o)) }
        watch.observe(MixWatch.Sighting("a", "A", "x", 72_000, 90.0))
        // The next track starts; whatever was waiting on A and B is not about it.
        val next = (0..5).map { i -> watch.observe(MixWatch.Sighting("c", "C", "z", 84_000L + i * 12_000, 2.0 + i * 12)) }
        assertTrue(next.all { it == null })
    }

    @Test
    fun lengthPutsTheUploadThatWasPlayingFirst() {
        fun v(id: String, seconds: Int) = SongItem(id = id, title = id, artists = emptyList(), thumbnail = "", duration = seconds)
        // Memories Anthem, heard for about 192 s, against mashups of the same two songs.
        val candidates = listOf(v("rosac", 342), v("aethel", 515), v("paris", 140), v("mix2026", 350), v("dasonrz", 207))
        val ordered = MixSearch.byLength(candidates, 192.0).map { it.id }
        assertEquals("dasonrz", ordered.first())
        assertFalse("paris" in ordered)
        // An hour-long compilation is never what was playing.
        assertFalse(MixSearch.couldBe(v("best of", 6144), 60.0))
    }

    @Test
    fun beggingForDnaGivesItselfAwayByGoingBackToTheTop() {
        // "I'm Beggin' For DNA" as the phone heard it: DNA. from its start to 99.8 s, twelve seconds
        // a window, then back to 2.1 s. DNA. is 186 s long, so this is not a replay at its end.
        val straight = listOf(-5.9, 3.8, 15.9, 29.1, 39.9, 51.9, 63.8, 75.8, 87.9, 99.8)
        val watch = CutWatch()
        assertTrue(straight.withIndex().all { (i, offset) -> watch.observe("dna", offset, 0.0, i * 12_000L, 186) == CutWatch.Verdict.NONE })
        // Known once the restart holds for a second window.
        assertEquals(CutWatch.Verdict.NONE, watch.observe("dna", 2.1, 0.0, 120_000, 186))
        assertEquals(CutWatch.Verdict.FIRST, watch.observe("dna", 14.1, 0.0, 132_000, 186))
    }

    @Test
    fun aDanceTrackPlacedOnePhraseEarlyIsNotAnEdit() {
        // Blasterjaxx & DBSTF, Beautiful World, replayed from a file on 24 Sep: a radio edit of the
        // version Shazam knows (one real cut at 60 s), and twice a window placed one 29 s phrase
        // early, each put right by the window after.
        val windows = listOf(26.6, 38.6, 50.6, 62.6, 74.6, 94.0, 106.0, 88.7, 130.0, 112.7, 154.0, 166.0, 178.0, 190.0, 202.0, 214.0)
        val watch = CutWatch()
        assertTrue(windows.withIndex().all { (i, offset) -> watch.observe("bw", offset, 0.0, i * 12_000L, 214) == CutWatch.Verdict.NONE })
    }

    @Test
    fun aSongReplayedAtItsEndIsNotAnEdit() {
        val watch = CutWatch()
        val offsets = (0..15).map { 2.0 + it * 12 }  // 2 to 182 s of a 186 s song
        assertTrue(offsets.withIndex().all { (i, offset) -> watch.observe("a", offset, 0.0, i * 12_000L, 186) == CutWatch.Verdict.NONE })
        assertEquals(CutWatch.Verdict.NONE, watch.observe("a", 3.0, 0.0, 192_000, 186))
    }

    @Test
    fun creditsComeDownToTheFirstName() {
        assertEquals("Eminem", MixSearch.primaryArtist("Eminem feat. Lil Wayne"))
        assertEquals("Linkin Park", MixSearch.primaryArtist("Linkin Park & Jay-Z"))
        assertEquals("No Love", MixSearch.bareTitle("No Love (feat. Lil Wayne)"))
    }
}
