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

    private fun sighting(song: Pair<String, Pair<String, String?>>, second: Int, offset: Double = 0.0, skew: Double = 0.0) =
        MixWatch.Sighting(song.first, song.second.first, song.second.second, second * 1000L, offset, skew)

    /** Every window of [windows], each a song, the seconds in and its offset and skew, in order. */
    private fun MixWatch.observeAll(windows: List<Triple<Pair<String, Pair<String, String?>>, Int, Pair<Double, Double>>>) =
        windows.map { (song, at, os) -> observe(sighting(song, at, os.first, os.second)) }

    /** Seconds after 10:13:06 at which each window was heard. */
    private val run = listOf(
        faint to 0, faint to 12,
        // 10:13:54 matched nothing.
        faint to 60, faint to 72, faint to 84, faint to 96, faint to 108, faint to 120,
        noLove to 133, lliving to 144,
        faint to 157, faint to 168, faint to 180,
    )

    @Test
    fun songsNamedOnceAreNotPieces() {
        // The first Damage run: No Love and an unsure match once each. Songs named for one window
        // and never again are what Shazam does in a busy mix; the return alone proves nothing.
        val watch = MixWatch()
        assertTrue(run.all { (song, at) -> watch.observe(sighting(song, at)) == null })
    }

    /** Faint's windows from the second Damage run, 24 Sep 12:56: offset, skew, seconds in. */
    private val faintCuts = listOf(
        Triple(-2.2, -0.0016, 0), Triple(31.1, -0.0002, 12), Triple(43.1, 0.0003, 24),
        Triple(12.4, 0.0008, 36), Triple(24.4, 0.0018, 48), Triple(36.5, 0.0005, 60),
        Triple(87.5, 0.0008, 72), Triple(60.4, 0.0018, 84), Triple(35.1, 0.0010, 96),
        Triple(47.1, -0.0002, 108), Triple(59.1, -0.0013, 120),
    )

    /**
     * The second Damage run, 24 Sep 12:56 on: seconds after its first window, with the offsets and
     * speeds the phone logged. No Love came back.
     */
    private val damage2 = faintCuts.map { (offset, skew, at) -> Triple(faint, at, offset to skew) } + listOf(
        Triple(noLove, 132, 181.8 to 0.0420), Triple(faint, 144, 31.6 to 0.0008), Triple(faint, 156, 29.4 to 0.0006),
        Triple(faint, 168, 34.3 to 0.0013), Triple(noLove, 180, 231.9 to 0.0419), Triple(faint, 192, 44.1 to 0.0016),
    )

    @Test
    fun theDamageRunIsAMixOnceNoLoveComesBackToo() {
        val verdicts = MixWatch().observeAll(damage2)
        // Nothing while No Love has been heard once.
        assertTrue(verdicts.subList(0, 15).all { it == null })
        // No Love back after Faint is the same thing seen from its side, and only one gap so far:
        // worth asking about, since it came back 232 s into itself, and not yet acting on.
        assertTrue(verdicts[15]!!.strong)
        assertFalse(verdicts[15]!!.sure)
        val mix = verdicts[16]
        assertNotNull(mix)
        // Faint left twice for the same other song.
        assertTrue(mix!!.strong)
        assertEquals(listOf("faint", "nolove"), mix.pieces.map { it.key })
    }

    @Test
    fun skippingThroughAPlaylistAndBackIsWorthAskingNotActingOn() {
        // Two windows each of A, B and C, then back into A partway through: somebody skipping
        // about, or a mashup. It asks, and only songs taking turns a second time make it sure.
        val watch = MixWatch()
        val a = "a" to ("A" to "x"); val b = "b" to ("B" to "y"); val c = "c" to ("C" to "z")
        fun at(song: Pair<String, Pair<String, String?>>, second: Int, offset: Double) =
            MixWatch.Sighting(song.first, song.second.first, song.second.second, second * 1000L, offset)
        listOf(at(a, 0, 60.0), at(a, 12, 72.0), at(b, 24, 30.0), at(b, 36, 42.0), at(c, 48, 80.0), at(c, 60, 92.0))
            .forEach { assertNull(watch.observe(it)) }
        val mix = watch.observe(at(a, 72, 150.0))!!
        assertTrue(mix.strong)
        assertFalse(mix.sure)
    }

    @Test
    fun songsTakingTurnsTwiceAreSure() {
        val mix = MixWatch().observeAll(damage2)[16]!!
        assertTrue(mix.sure)
    }

    @Test
    fun aPickNamesTheSongsItIsMadeOf() {
        val pieces = listOf(sighting(faint, 0), sighting(noLove, 12), sighting(lliving, 24))
        val named = pieces.filter { MixSearch.names(it, damageLyrics) }.map { it.key }
        // Damage (Lyrics) names Faint and No Love, and has no idea what Lliving Life Mix is.
        assertEquals(listOf("faint", "nolove"), named)
    }

    @Test
    fun aBreakdownFullOfOneWindowMatchesIsNotAMashup() {
        // 2 Faced Funks, Powerbass, replayed from a file on 24 Sep: in its breakdown Shazam named
        // five different tracks for a window each before Powerbass came back.
        val watch = MixWatch()
        val sightings = listOf("powerbass" to 0, "powerbass" to 12, "powerbass" to 24, "feel" to 84, "greyhound" to 96,
            "surprise" to 108, "europa" to 120, "reload" to 132, "powerbass" to 144, "powerbass" to 156, "setmix" to 180,
            "powerbass" to 192)
        assertTrue(sightings.all { (key, at) -> watch.observe(MixWatch.Sighting(key, key, "x", at * 1000L)) == null })
    }

    @Test
    fun aPlaylistMovingOnIsNeverAMix() {
        val watch = MixWatch()
        val a = "a" to ("A" to "x"); val b = "b" to ("B" to "y"); val c = "c" to ("C" to "z")
        val timeline = listOf(a to 0, a to 12, a to 24, b to 36, b to 48, b to 60, c to 72, c to 84)
        assertTrue(timeline.all { (song, at) -> watch.observe(sighting(song, at)) == null })
    }

    @Test
    fun oneOddWindowIsNothing() {
        val watch = MixWatch()
        val a = "a" to ("A" to "x"); val odd = "odd" to ("Odd" to "y")
        listOf(a to 0, a to 12, odd to 24, a to 36).forEach { (s, at) -> assertNull(watch.observe(sighting(s, at))) }
    }

    @Test
    fun aSecondInterruptionMakesItSure() {
        val watch = MixWatch()
        val a = "a" to ("A" to "x"); val b = "b" to ("B" to "y")
        // Each window somewhere new in its song, as a mashup cuts about.
        listOf(sighting(a, 0, 30.0), sighting(b, 12, 50.0)).forEach { watch.observe(it) }
        // B once so far: nothing yet.
        assertNull(watch.observe(sighting(a, 24, 70.0)))
        watch.observe(sighting(b, 36, 90.0))
        assertTrue(watch.observe(sighting(a, 48, 110.0))!!.strong)
    }

    @Test
    fun skippingBackToThePreviousSongIsNotSure() {
        // Two windows of one other song and then the first again is also someone going back a track.
        val watch = MixWatch()
        val a = "a" to ("A" to "x"); val b = "b" to ("B" to "y")
        listOf(sighting(a, 0, 60.0), sighting(a, 12, 72.0), sighting(a, 24, 84.0), sighting(b, 36, 2.0), sighting(b, 48, 14.0))
            .forEach { watch.observe(it) }
        assertFalse(watch.observe(sighting(a, 60, 3.0))!!.strong)
    }

    @Test
    fun oneSongUnderTwoKeysIsOneSong() {
        val twin = "faint2" to ("Faint" to "Linkin Park")
        val pieces = listOf(sighting(faint, 0), sighting(twin, 12))
        assertEquals(1, MixSearch.distinctSongs(pieces).size)
        assertTrue(MixSearch.queries(MixSearch.distinctSongs(pieces), 1.0).isEmpty())
    }

    @Test
    fun twoKeysForOneSongInTheGapAreStillOneSong() {
        val watch = MixWatch()
        val a = "a" to ("A" to "x"); val b1 = "b1" to ("B" to "y"); val b2 = "b2" to ("B" to "y")
        listOf(a to 0, a to 12, a to 24, b1 to 36, b2 to 48).forEach { (s, at) -> watch.observe(sighting(s, at)) }
        assertFalse(watch.observe(sighting(a, 60))?.strong ?: false)
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
        val mix = MixWatch().observeAll(damage2)[16]!!
        assertEquals(listOf("Faint No Love mashup", "Linkin Park Eminem mashup"), MixSearch.queries(mix.pieces, 1.0))
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
        val pieces = MixWatch().observeAll(damage2)[16]!!.pieces
        val byTitles = listOf(damage, damageLyrics, otherMashup, psychofaint)
        val byArtists = listOf(damage, breakingTheHabit)
        val ranked = MixSearch.rank(pieces, listOf(byTitles, byArtists), 1.0)
        val ids = ranked.map { it.first.id }
        // Psychofaint names one piece, which is not enough to count.
        assertFalse(psychofaint.id in ids)
        // The upload that was playing is among the choices...
        assertTrue(damage.id in ids.take(3))
        // ...but tied at the top with a different mashup of the same two songs, so nothing is taken.
        assertNull(MixSearch.clearWinner(ranked, pieces))
    }

    @Test
    fun oneMashupWellAheadIsTaken() {
        val pieces = listOf(sighting(faint, 0), sighting(noLove, 12))
        val ranked = MixSearch.rank(pieces, listOf(listOf(damageLyrics, breakingTheHabit)), 1.0)
        assertEquals(damageLyrics.id, MixSearch.clearWinner(ranked, pieces)?.id)
    }

    @Test
    fun aMashupNamingASongNobodyHeardIsNotTakenWithoutAsking() {
        // Hideaway and Real Love cut back and forth, run on the emulator on 25 Sep.
        val pieces = listOf(
            MixWatch.Sighting("hideaway", "Hideaway", "Kiesza", 0L),
            MixWatch.Sighting("reallove", "Real Love", "Clean Bandit & Jess Glynne", 24_000L),
        )
        val ratherBe = video("rb", "Hideaway/Rather Be - Kiesza/Clean Bandit [Mashup]", "someone")
        val giant = video("giant", "Kiesza vs. Clean Bandit ft. Jess Glynne - Rather Be A Giant", "someone")
        assertTrue(MixSearch.namesUnheard(pieces, ratherBe))
        assertFalse("a mashup's own name is not a song", MixSearch.namesUnheard(pieces, giant))
        assertNull(MixSearch.clearWinner(MixSearch.rank(pieces, listOf(listOf(ratherBe, giant), listOf(ratherBe)), 1.0), pieces))
        // The Damage uploads list only artists and pieces that were heard.
        val damagePieces = listOf(sighting(faint, 0), sighting(noLove, 12))
        listOf(damage, damageLyrics, otherMashup).forEach { assertFalse(it.title, MixSearch.namesUnheard(damagePieces, it)) }
    }

    @Test
    fun aPieceLaidOverASongThatPlaysOnStillCounts() {
        // Damage as logged, but with No Love laid over Faint rather than cut in: Faint goes on at
        // 144 s where it was heading, 83.1 s in, across the first single window of No Love, and
        // at 192 s either as logged or across the second one too. No Love moves along in itself,
        // from 181.8 s in to 231.9, so it is a piece all the same.
        for (after in listOf(44.1, 58.3)) {
            val windows = damage2.toMutableList()
            windows[12] = Triple(faint, 144, 83.1 to 0.0008)
            windows[16] = Triple(faint, 192, after to 0.0016)
            val verdicts = MixWatch().observeAll(windows)
            assertTrue(verdicts.subList(0, 15).all { it == null })
            assertTrue(verdicts[15]!!.strong)
            val mix = verdicts[16]!!
            assertTrue(mix.sure)
            assertEquals(listOf("faint", "nolove"), mix.pieces.map { it.key })
        }
    }

    @Test
    fun theDamageRunIsCutUpAMinuteIn() {
        val watch = CutWatch()
        val verdicts = faintCuts.map { (offset, skew, at) -> watch.observe("faint", offset, skew, at * 1000L) }
        // Home is where Faint first held (31 s on), and two new places held after it, from 12 s and
        // from 35 s: known 108 s in, where the mashup check needed three and a half minutes and a
        // second song.
        assertEquals(CutWatch.Verdict.FIRST, verdicts[9])
        assertTrue(verdicts.subList(0, 9).all { it == CutWatch.Verdict.NONE })
    }

    /**
     * Kiesza's Hideaway (Radio Edit), replayed from a file on 25 Sep: offset, skew, seconds in.
     * Shazam matched it against two references of the song, one it runs 1.5 % fast against and
     * one at speed, and placed its repeated phrases one to four phrases off, so it jumps about
     * while the file plays straight.
     */
    private val hideaway = listOf(
        Triple(-0.5, 0.0149, 0), Triple(13.7, 0.0149, 12), Triple(23.9, 0.0149, 24), Triple(36.1, 0.0148, 36),
        Triple(40.6, 0.0151, 48), Triple(62.4, 0.0152, 60), Triple(88.2, 0.0148, 72), Triple(92.8, 0.0147, 84),
        Triple(95.5, 0.0001, 96), Triple(107.5, 0.0, 108), Triple(144.8, 0.0152, 120), Triple(156.9, 0.0150, 132),
        Triple(169.1, 0.0153, 144), Triple(159.8, 0.0149, 156), Triple(172.0, 0.0146, 168), Triple(205.7, 0.0149, 180),
        Triple(191.5, -0.0003, 192), Triple(237.8, 0.0150, 204),
    )

    @Test
    fun aRadioEditMatchedAgainstTwoReferencesIsNotCutUp() {
        val watch = CutWatch()
        val verdicts = hideaway.map { (offset, skew, at) -> watch.observe("hideaway", offset, skew, at * 1000L, durationS = 250) }
        assertTrue(verdicts.toString(), verdicts.none { it == CutWatch.Verdict.FIRST || it == CutWatch.Verdict.AGAIN })
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
            MixSearch.singleQueries(piece.copy(artist = "LINKIN PARK"), 1.0).map { it.lowercase() })
        val plain = video("2mXNRsyTitA", "Faint", "Linkin Park")
        val skillet = video("2rE9qdUKAGM", "Skillet X Linkin Park - Monster/Faint [MASHUP]", "BlueDragonCody Productions")
        val choices = MixSearch.rankSingle(piece, listOf(listOf(damage, skillet, plain), listOf(damage)), remixFirst = false, heard = emptyList(), speed = 1.0)
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
        listOf(sighting(a, 0, 60.0), sighting(a, 12, 72.0), sighting(b, 24, 2.0), sighting(b, 36, 14.0)).forEach { watch.observe(it) }
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
    fun fourWindowsEachSomewhereNewAreChoppedThreeAreNot() {
        // Memories in Memories Anthem landed at 91.8, 36.4 and 43.1 before the mashup moved on; a
        // repetitive song's first three windows can do the same (Hotline Bling), so it takes four.
        val watch = CutWatch()
        assertEquals(CutWatch.Verdict.NONE, watch.observe("m", 91.8, 0.0001, 0))
        assertEquals(CutWatch.Verdict.NONE, watch.observe("m", 36.4, 0.0011, 12_000))
        assertEquals(CutWatch.Verdict.NONE, watch.observe("m", 160.0, 0.0, 24_000))
        assertEquals(CutWatch.Verdict.FIRST, watch.observe("m", 5.0, 0.0, 36_000))
        // 36.4 then 43.1, as Memories actually went, is five seconds behind the clock: the same
        // place resuming after a stall, so it does not count as somewhere new.
        val other = CutWatch()
        listOf(91.8 to 0L, 36.4 to 12_000L, 43.1 to 24_000L, 150.0 to 36_000L)
            .forEach { (offset, at) -> assertEquals(CutWatch.Verdict.NONE, other.observe("m", offset, 0.0, at)) }
    }

    @Test
    fun aRepetitiveSongPlacedInItsOtherRepeatsIsNotCut() {
        // Drake, Hotline Bling, replayed from a file on 24 Sep: played straight, but Shazam put
        // windows in three places 20 s and 213 s apart all the way through, starting with a wrong
        // one.
        val windows = listOf(20.7, 225.9, 24.6, 36.6, 48.5, 80.7, 92.7, 297.9, 96.6, 108.6, 333.9, 132.5, 144.5,
            156.6, 168.5, 180.6, 192.6, 204.6)
        val watch = CutWatch()
        assertTrue(windows.withIndex().all { (i, offset) -> watch.observe("hb", offset, 0.0, i * 12_000L, 267) == CutWatch.Verdict.NONE })
    }

    @Test
    fun aRoundThatAlternatesBetweenTwoPlacesIsNotCut() {
        // Fleet Foxes, White Winter Hymnal: a round, so Shazam flips between two places 8 s apart.
        val windows = listOf(-1.4, 10.6, 14.5, 26.5, 46.6, 58.6, 62.5, 82.6, 94.6, 106.6, 118.6, 130.6)
        val watch = CutWatch()
        assertTrue(windows.withIndex().all { (i, offset) -> watch.observe("wwh", offset, 0.0, i * 12_000L, 147) == CutWatch.Verdict.NONE })
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

    /**
     * Krept & Konan's Freak of the Week (Radio Edit), replayed from a file on 25 Sep: seconds in,
     * key, offset, skew. Played straight, but Shazam named Hello for the first window, named the
     * same passage as Jamie xx's I Know There's Gonna Be at 72 s and at 132 s, and placed the
     * windows at 144 and 156 s elsewhere in the song before it was back on its timeline at 168 s.
     */
    private val freakOfTheWeek = listOf(
        Triple(0, "hello", 245.3 to 0.0007), Triple(12, "freak", 12.6 to 0.0001), Triple(24, "freak", 24.5 to 0.0001),
        Triple(36, "freak", 36.6 to 0.0), Triple(48, "freak", 48.5 to -0.0001), Triple(60, "freak", 60.6 to 0.0001),
        Triple(72, "goodtimes", 185.6 to 0.0503), Triple(84, "freak", 84.5 to 0.0001), Triple(96, "freak", 96.5 to 0.0003),
        Triple(108, "freak", 108.6 to -0.0001), Triple(120, "freak", 120.5 to 0.0), Triple(132, "goodtimes", 185.0 to 0.0456),
        Triple(144, "freak", 185.0 to 0.0), Triple(156, "freak", 136.4 to 0.0002), Triple(168, "freak", 168.6 to 0.0),
    )

    private fun freakSighting(at: Int, key: String, offset: Double, skew: Double) = MixWatch.Sighting(
        key, when (key) {
            "freak" -> "Freak of the Week (feat. Jeremih)"
            "goodtimes" -> "I Know There's Gonna Be (Good Times) [feat. Young Thug & Popcaan] [Rinse Edit]"
            else -> "Hello"
        }, null, at * 1000L, offset, skew,
    )

    @Test
    fun onePassageMisnamedTwiceIsNotAMashup() {
        // The return at 144 s was sure: the other song twice, each time between two windows of this
        // one. Both times Shazam put it 185 s into the other song.
        val watch = MixWatch()
        val verdicts = freakOfTheWeek.map { (at, key, os) -> watch.observe(freakSighting(at, key, os.first, os.second)) }
        assertTrue(verdicts.toString(), verdicts.all { it == null })
    }

    @Test
    fun onePassageMisnamedAThirdTimeIsStillNotAMashup() {
        // A longer song with one more chorus: the passage misnamed again at 180, 192 or 216 s, the
        // song on its own timeline around it. Heard three times, the other song is still at one
        // place in itself.
        for (third in listOf(180, 192, 216)) {
            val more = (180..third + 24 step 12).map { at ->
                if (at == third) Triple(at, "goodtimes", 185.3 to 0.0480) else Triple(at, "freak", at + 0.6 to 0.0)
            }
            val watch = MixWatch()
            val verdicts = (freakOfTheWeek + more).map { (at, key, os) -> watch.observe(freakSighting(at, key, os.first, os.second)) }
            assertTrue("third at $third s: $verdicts", verdicts.all { it == null })
        }
    }

    /**
     * Raveon & Christian Tanz's Take Me Alive, replayed from a file on 25 Sep: seconds in, key,
     * offset, skew. Played straight, but Shazam named other club tracks for three stretches of it,
     * Reload twice at the same place.
     */
    private val takeMeAlive = listOf(
        Triple(0, "tma", -0.7 to 0.0), Triple(12, "tma", 11.3 to -0.0002), Triple(24, "launch", 315.1 to -0.0101),
        Triple(36, "launch", 316.7 to -0.0025), Triple(48, "tma", 47.3 to 0.0002), Triple(60, "tma", 59.3 to 0.0),
        Triple(72, "tma", 71.3 to -0.0001), Triple(84, "tma", 83.3 to 0.0002), Triple(96, "tma", 95.3 to 0.0001),
        Triple(108, "tma", 107.3 to 0.0003), Triple(120, "mount", 319.3 to 0.0061), Triple(132, "somebody", 92.7 to -0.0026),
        Triple(144, "cage", 142.5 to 0.0033), Triple(156, "feel", 84.6 to -0.0087), Triple(168, "tma", 167.3 to -0.0001),
        Triple(180, "tma", 179.3 to 0.0002), Triple(192, "tma", 191.3 to 0.0), Triple(204, "tma", 203.3 to 0.0003),
        Triple(216, "lay", 7.0 to 0.0066), Triple(228, "reload", 195.7 to -0.0035), Triple(240, "titanium", 156.5 to -0.0032),
        Triple(252, "reload", 195.3 to 0.0026), Triple(264, "mariachi", 270.4 to -0.0005), Triple(276, "tma", 275.3 to -0.0001),
        Triple(288, "tma", 287.3 to -0.0001),
    )

    @Test
    fun aBreakdownNamedTheSameWayTwiceIsNotAMashup() {
        // Take Me Alive back at 276 s, 72 s after it was last heard, was a strong return: Reload
        // twice in the gap, and 275 s into the song.
        val watch = MixWatch()
        val verdicts = takeMeAlive.map { (at, key, os) -> watch.observe(MixWatch.Sighting(key, key, "x", at * 1000L, os.first, os.second)) }
        assertTrue(verdicts.toString(), verdicts.all { it == null })
    }

    @Test
    fun aPieceAlreadyHeardCountsForOneWindowWhileTheSongPlaysOn() {
        // A cut to B for two windows and back into A partway, then B again for a single window with
        // A going on where it should: B is a piece by then, and that is the songs taking turns.
        val watch = MixWatch()
        fun at(key: String, second: Int, offset: Double) = MixWatch.Sighting(key, key.uppercase(), key, second * 1000L, offset)
        listOf(at("a", 0, 10.0), at("a", 12, 22.0), at("b", 24, 50.0), at("b", 36, 62.0)).forEach { assertNull(watch.observe(it)) }
        assertFalse(watch.observe(at("a", 48, 100.0))!!.sure)
        assertNull(watch.observe(at("a", 60, 112.0)))
        assertFalse(watch.observe(at("b", 72, 90.0))?.sure ?: false)
        assertTrue(watch.observe(at("a", 84, 136.0))!!.sure)
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
    fun twoSongsBlendingEachOnItsOwnTimelineAreNotAMashup() {
        // A DJ taking one song into the next: Shazam names each in turn, and each keeps its time.
        val watch = MixWatch()
        val windows = listOf("h" to (0 to 150.0), "h" to (12 to 162.0), "h" to (24 to 174.0), "x" to (36 to 2.0),
            "h" to (48 to 198.0), "x" to (60 to 26.0), "h" to (72 to 222.0), "x" to (84 to 50.0), "x" to (96 to 62.0),
            "x" to (108 to 74.0), "x" to (120 to 86.0), "x" to (132 to 98.0), "x" to (144 to 110.0))
        assertTrue(windows.all { (key, w) -> watch.observe(MixWatch.Sighting(key, key, key, w.first * 1000L, w.second)) == null })
    }

    @Test
    fun aPieceNamedOncePerGapOverASongThatPlaysOnIsSure() {
        // Faint straight under a mashup, No Love named for one window at a time, 72 s apart, and
        // Faint back where it was heading after a stretch where nothing matched. Faint keeping its
        // time does not make No Love a passage of it misnamed: No Love moves along its own
        // timeline, 75 s further into itself 72 s later at the 4 % speed Shazam read, as it did in
        // the Damage run of 24 Sep. The songs have taken turns twice.
        val watch = MixWatch()
        fun at(key: String, second: Int, offset: Double, skew: Double = 0.0) = MixWatch.Sighting(key, key, "x", second * 1000L, offset, skew)
        listOf(at("faint", 0, 35.1), at("faint", 12, 47.1), at("faint", 24, 59.1), at("nolove", 36, 181.8, 0.042),
            at("faint", 48, 83.1), at("faint", 60, 95.1)).forEach { assertNull(watch.observe(it)) }
        val fromNoLove = watch.observe(at("nolove", 108, 256.8, 0.042))!!
        assertTrue(fromNoLove.strong)
        assertFalse(fromNoLove.sure)
        val mix = watch.observe(at("faint", 132, 167.1))!!
        assertTrue(mix.sure)
        assertEquals(setOf("faint", "nolove"), mix.pieces.map { it.key }.toSet())
    }

    @Test
    fun aHeldReturnLongGoneIsDroppedNotCounted() {
        val watch = MixWatch()
        listOf(0 to 10.0, 12 to 22.0).forEach { (at, o) -> watch.observe(MixWatch.Sighting("a", "A", "x", at * 1000L, o)) }
        listOf(24 to 30.0, 36 to 42.0, 48 to 54.0, 60 to 66.0).forEach { (at, o) -> watch.observe(MixWatch.Sighting("b", "B", "y", at * 1000L, o)) }
        // A back partway under steady B: held.
        assertNull(watch.observe(MixWatch.Sighting("a", "A", "x", 72_000, 90.0)))
        // Five minutes of nothing, then A from the top: that is not the old return any more.
        assertNull(watch.observe(MixWatch.Sighting("a", "A", "x", 372_000, 2.0)))
    }

    @Test
    fun forgettingAMashupKeepsWhatCameAfterIt() {
        val watch = MixWatch()
        val sightings = listOf("old" to 0, "other" to 12, "old" to 24, "other" to 36, "new" to 48, "next" to 60, "next" to 72)
        sightings.forEach { (key, at) -> watch.observe(MixWatch.Sighting(key, key, key, at * 1000L, at.toDouble())) }
        watch.forget(setOf("old", "other"))
        // The new mashup's own return is still seen.
        assertNotNull(watch.observe(MixWatch.Sighting("new", "new", "n", 84_000, 60.0)))
    }

    @Test
    fun lengthPutsTheUploadThatWasPlayingFirst() {
        fun v(id: String, seconds: Int) = SongItem(id = id, title = id, artists = emptyList(), thumbnail = "", duration = seconds)
        // Memories Anthem, heard for about 192 s, against mashups of the same two songs.
        val candidates = listOf(v("rosac", 342), v("aethel", 515), v("paris", 140), v("mix2026", 350), v("dasonrz", 207))
        val ordered = MixSearch.byLength(candidates, 192.0, 1.0).map { it.id }
        assertEquals("dasonrz", ordered.first())
        assertFalse("paris" in ordered)
        // An hour-long compilation is never what was playing.
        assertFalse(MixSearch.couldBe(v("best of", 6144), 60.0, 1.0))
    }

    @Test
    fun beggingForDnaGivesItselfAwayByGoingBackToTheTop() {
        // "I'm Beggin' For DNA" as the phone heard it: DNA. from its start to 99.8 s, twelve seconds
        // a window, then back to 2.1 s. DNA. is 186 s long, so this is not a replay at its end.
        val straight = listOf(-5.9, 3.8, 15.9, 29.1, 39.9, 51.9, 63.8, 75.8, 87.9, 99.8)
        val watch = CutWatch()
        assertTrue(straight.withIndex().all { (i, offset) -> watch.observe("dna", offset, 0.0, i * 12_000L, 186) == CutWatch.Verdict.NONE })
        // Reported once the restart holds for a second window, as a restart: whether it was an edit
        // shows only when the song stops short of its end, which the engine watches for.
        assertEquals(CutWatch.Verdict.NONE, watch.observe("dna", 2.1, 0.0, 120_000, 186))
        assertEquals(CutWatch.Verdict.RESTART, watch.observe("dna", 14.1, 0.0, 132_000, 186))
        // Reckoned from a place the song held, so it stands however far DNA. then gets.
        assertNull(watch.restartedFrom)
    }

    @Test
    fun oneWindowOfDnaBeforeItsTopIsStillARestart() {
        // The same, with Keep listening catching only one window of DNA. before the jump: 99.8 s
        // in, then from 2.1 s to 62.1 s, where Beggin' took over. The restart rests on that one
        // window, and DNA. never got back to where it said, so it stopped short: an edit.
        val watch = CutWatch()
        assertEquals(CutWatch.Verdict.NONE, watch.observe("dna", 99.8, 0.0, 0, 186))
        assertEquals(CutWatch.Verdict.NONE, watch.observe("dna", 2.1, 0.0, 12_000, 186))
        assertEquals(CutWatch.Verdict.RESTART, watch.observe("dna", 14.1, 0.0, 24_000, 186))
        assertEquals(99.8, watch.restartedFrom!!, 0.01)
        assertTrue((3..6).all { watch.observe("dna", 2.1 + (it - 1) * 12, 0.0, it * 12_000L, 186) == CutWatch.Verdict.NONE })
        assertTrue(RecognitionEngine.stoppedShort(62.1, 12.0, 186, watch.restartedFrom))
    }

    /**
     * Duke Dumont's Won't Look Back (radio edit) and Mr. Probz's Waves (Robin Schulz radio edit),
     * replayed from files on 25 Sep: offset, skew, seconds in. Each first window was placed on a
     * phrase that comes round again later, 30 s and 51 s into the song, and the song's real start
     * came after it. Waves then came through as its remix's own entry once, at 108 s.
     */
    private val wontLookBack = listOf(
        Triple(30.3, 0.0, 0), Triple(13.0, 0.0005, 12), Triple(25.0, 0.0001, 24), Triple(37.0, 0.0001, 36),
        Triple(49.0, -0.0002, 48), Triple(61.0, 0.0001, 60), Triple(73.0, 0.0002, 72), Triple(114.3, 0.0002, 84),
        Triple(126.3, 0.0, 96), Triple(109.0, -0.0002, 108), Triple(121.0, 0.0002, 120), Triple(133.0, 0.0, 132),
        Triple(145.0, 0.0001, 144), Triple(186.3, -0.0001, 156), Triple(169.0, -0.0002, 168), Triple(181.0, 0.0001, 180),
    )
    private val waves = listOf(
        Triple(50.7, -0.0014, 0), Triple(2.7, -0.0018, 12), Triple(14.7, 0.0, 24), Triple(26.7, 0.0002, 36),
        Triple(38.7, 0.0, 48), Triple(50.7, 0.0, 60), Triple(62.7, 0.0, 72), Triple(74.7, 0.0, 84), Triple(86.7, 0.0, 96),
    )

    @Test
    fun aFirstWindowOnALaterRepeatIsNoEdit() {
        // Each reads as going back to the top once its real start holds, with only the first window
        // to say where it had been, and then plays on past that place: that window was the repeat,
        // and the song is no edit, even against an upload long enough that stopping where these
        // did would otherwise be stopping short. Nothing else about either is a cut.
        for ((windows, durationS) in listOf(wontLookBack to 202, waves to 208)) {
            val watch = CutWatch()
            val verdicts = windows.map { (offset, skew, at) -> watch.observe("song", offset, skew, at * 1000L, durationS) }
            assertEquals(verdicts.toString(), listOf(2), verdicts.indices.filter { verdicts[it] != CutWatch.Verdict.NONE })
            assertEquals(CutWatch.Verdict.RESTART, verdicts[2])
            val first = windows.first()
            assertEquals(first.first / (1.0 + first.second), watch.restartedFrom!!, 0.01)
            val furthest = windows.drop(2).maxOf { it.first }
            assertFalse(RecognitionEngine.stoppedShort(furthest, 12.0, 300, watch.restartedFrom))
            assertTrue(RecognitionEngine.stoppedShort(furthest, 12.0, 300, null))
        }
    }

    @Test
    fun aSongPausedTwiceIsNotAnEdit() {
        // Stalls of 5 s at 40 s and at 88 s: the song carries on from where it stopped each time,
        // a little behind the clock.
        val offsets = listOf(10.0, 22.0, 34.0, 41.0, 53.0, 65.0, 77.0, 84.0, 96.0, 108.0, 120.0)
        val watch = CutWatch()
        assertTrue(offsets.withIndex().all { (i, offset) -> watch.observe("p", offset, 0.0, i * 12_000L, 240) == CutWatch.Verdict.NONE })
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
    fun aRemixShazamDoesNotKnowComesThroughAsThreeVersions() {
        // The Averez remix of Lean On, replayed from a file on 24 Sep: Shazam named another song,
        // then the ATAX remix, the Robin Schulz edit twice, and the original.
        fun v(key: String, title: String, at: Int, offset: Double, skew: Double) =
            MixWatch.Sighting(key, title, "Major Lazer & DJ Snake", at * 1000L, offset, skew)
        val watch = VersionWatch()
        val windows = listOf(
            v("wlto", "When Love Takes Over (feat. Kelly Rowland)", 0, 42.0, -0.0002),
            v("atax", "Lean On (ATAX Remix)", 24, 28.3, 0.0154),
            v("rs", "Lean On (feat. MØ) [Robin Schulz Edit]", 36, 44.8, -0.0368),
            v("rs", "Lean On (feat. MØ) [Robin Schulz Edit]", 48, 56.4, -0.0374),
            v("orig", "Lean On", 60, 131.6, 0.0133),
        )
        val verdicts = windows.map { watch.observe(it) }
        assertTrue(verdicts.subList(0, 4).all { it == null })
        assertEquals(setOf("atax", "rs", "orig"), verdicts[4]!!.map { it.key }.toSet())
        // Every version after that is the same remix: a fourth one joins the others.
        assertEquals(setOf("atax", "rs", "orig", "pbh"), watch.observe(v("pbh", "Lean On (Pbh & Jack Shizzle Remix)", 72, 70.3, 0.0295))!!.map { it.key }.toSet())
        // Other songs in between are nothing to do with it.
        assertNull(watch.observe(v("kos", "kOs", 84, 180.6, 0.0148)))
        // Still the remix after a quiet stretch, and not after one longer than the span.
        assertTrue(watch.observe(v("rs", "Lean On (feat. MØ) [Robin Schulz Edit]", 200, 111.5, -0.037))!!.any { it.key == "rs" })
        assertNull(watch.observe(v("rs", "Lean On (feat. MØ) [Robin Schulz Edit]", 400, 111.5, -0.037)))
    }

    @Test
    fun oneRecordingUnderThreeEntriesIsOneVersion() {
        // Album cut, radio edit and remaster of the same audio: every window lands on one timeline.
        val watch = VersionWatch()
        val windows = listOf("album" to "Song", "radio" to "Song (Radio Edit)", "album" to "Song", "remaster" to "Song (Remastered 2011)")
        assertTrue(windows.withIndex().all { (i, e) ->
            watch.observe(MixWatch.Sighting(e.first, e.second, "Artist", i * 12_000L, 50.0 + i * 12)) == null
        })
    }

    @Test
    fun anotherVersionElsewhereOnTheTimelineIsARival() {
        fun v(key: String, title: String, at: Int, offset: Double, skew: Double = 0.0) =
            MixWatch.Sighting(key, title, "Major Lazer & DJ Snake", at * 1000L, offset, skew)
        val watch = VersionWatch()
        watch.observe(v("atax", "Lean On (ATAX Remix)", 24, 28.3, 0.0154))
        watch.observe(v("rs", "Lean On (feat. MØ) [Robin Schulz Edit]", 36, 44.8, -0.0368))
        watch.observe(v("rs", "Lean On (feat. MØ) [Robin Schulz Edit]", 48, 56.4, -0.0374))
        assertTrue(watch.rivalled("rs"))
        assertFalse("nothing else of it heard", VersionWatch().apply { observe(v("rs", "Lean On", 0, 10.0)) }.rivalled("rs"))
    }

    @Test
    fun anotherEntryOnTheSameTimelineIsATwinNotARival() {
        val watch = VersionWatch()
        watch.observe(MixWatch.Sighting("album", "Song", "Artist", 0L, 50.0))
        watch.observe(MixWatch.Sighting("radio", "Song (Radio Edit)", "Artist", 12_000L, 62.1))
        assertFalse(watch.rivalled("radio"))
        assertEquals("album", watch.twinOf("radio", setOf("album")))
        assertNull("not confirmed, so not a twin to carry on from", watch.twinOf("radio", emptySet()))
        watch.observe(MixWatch.Sighting("remix", "Song (Remix)", "Artist", 24_000L, 20.0))
        assertNull(watch.twinOf("remix", setOf("album", "radio")))
    }

    @Test
    fun aSongThatPausesTwiceWithAChorusMisplacedIsNotCutUp() {
        // From the second review of 25 Sep: two six second pauses and a window placed on a chorus.
        val windows = listOf(0 to 30.0, 12 to 42.0, 24 to 54.0, 36 to 60.0, 48 to 72.0, 60 to 84.0, 72 to 40.0,
            84 to 108.0, 96 to 114.0, 108 to 126.0, 120 to 138.0, 132 to 40.0, 144 to 162.0, 156 to 174.0)
        val watch = CutWatch()
        val verdicts = windows.map { (at, offset) -> watch.observe("song", offset, 0.0, at * 1000L, durationS = 240) }
        assertTrue(verdicts.toString(), verdicts.none { it == CutWatch.Verdict.FIRST || it == CutWatch.Verdict.AGAIN })
    }

    @Test
    fun aVersionPlayingStraightIsReleased() {
        fun v(key: String, title: String, at: Int, offset: Double) = MixWatch.Sighting(key, title, "Major Lazer", at * 1000L, offset)
        val watch = VersionWatch()
        watch.observe(v("atax", "Lean On (ATAX Remix)", 0, 28.3))
        watch.observe(v("rs", "Lean On [Robin Schulz Edit]", 12, 44.8))
        assertNotNull(watch.observe(v("orig", "Lean On", 24, 131.6)))
        watch.release(v("orig", "Lean On", 36, 143.6))
        assertNull("the original playing on is the song", watch.observe(v("orig", "Lean On", 48, 155.6)))
        // Released only after about a minute straight: four windows of one version are not enough.
        val straight = VersionWatch()
        (0 until 4).forEach { straight.observe(v("pbh", "Lean On (Pbh Remix)", 100 + it * 12, 70.0 + it * 12)) }
        assertFalse(straight.playsStraight("pbh"))
        straight.observe(v("pbh", "Lean On (Pbh Remix)", 148, 118.0))
        assertTrue(straight.playsStraight("pbh"))
    }

    @Test
    fun namesAreMatchedAsWholeWordsAndALoneXIsOneSong() {
        val pieces = listOf(
            MixWatch.Sighting("hideaway", "Hideaway", "Kiesza", 0L),
            MixWatch.Sighting("reallove", "Real Love", "Clean Bandit & Jess Glynne", 24_000L),
        )
        assertFalse(MixSearch.namesUnheard(pieces, video("m", "Mashup: Hideaway x Real Love", "someone")))
        assertFalse(MixSearch.namesUnheard(pieces, video("k", "Kiesza's Hideaway x Real Love", "someone")))
        assertFalse(MixSearch.namesSeveral(video("a", "Major Lazer x DJ Snake - Lean On (Averez Remix)", "someone")))
        assertTrue(MixSearch.namesSeveral(video("b", "Lean On x Sorry (Mashup)", "someone")))
    }

    @Test
    fun aRemixCreditedToItsRemixerIsStillTheSameSong() {
        val pieces = listOf(
            MixWatch.Sighting("rs", "Lean On (feat. MØ) [Robin Schulz Extended Remix]", "Major Lazer & DJ Snake", 0L),
            MixWatch.Sighting("sunny", "Lean On", "DjSunnymega", 12_000L),
        )
        assertEquals(1, MixSearch.distinctSongs(pieces).size)
        assertEquals(2, MixSearch.distinctSongs(pieces + MixWatch.Sighting("sorry", "Sorry", "Justin Bieber", 24_000L)).size)
    }

    @Test
    fun aVersionShazamDoesNotKnowOffersRemixesBeforeMashups() {
        fun item(id: String, title: String) = SongItem(id = id, title = title, artists = listOf(Artist(name = "someone", id = null)), thumbnail = "", duration = 200)
        val piece = MixWatch.Sighting("lean", "Lean On", "Major Lazer & DJ Snake", 0L)
        val results = listOf(
            listOf(item("a", "LEAN ON X LUSH LIFE (Zara Larsson, Major Lazer) [Jr Stit Mashup]"), item("b", "Lean On x Sorry (Mashup)")),
            listOf(item("c", "Major Lazer & DJ Snake - Lean On (Averez Remix)"), item("d", "Lean On vs Lose Yourself - Gustav Krantz Mashup"), item("e", "Lean On (Tiesto Remix)")),
        )
        assertEquals(listOf("c", "e", "a", "b", "d"), MixSearch.rankSingle(piece, results, remixFirst = true, heard = emptyList(), speed = 1.0).map { it.id })
        // Cut up rather than heard as several versions: as found, since that is as often a mashup.
        assertEquals(listOf("a", "b", "c", "d", "e"), MixSearch.rankSingle(piece, results, remixFirst = false, heard = emptyList(), speed = 1.0).map { it.id })
        assertTrue(MixSearch.namesSeveral(item("x", "Oliver Heldens vs Major Lazer - Lean On Gecko (Sergio Rilo Mashup)")))
        assertFalse(MixSearch.namesSeveral(item("y", "Lean On (Charli XCX Remix)")))
    }

    @Test
    fun aRemixShazamKnowsIsTwoVersionsAtMost() {
        val watch = VersionWatch()
        val sightings = r3hab.map { (at, key, os) -> r3habSighting(at, key, os.first, os.second) }
        assertTrue(sightings.all { watch.observe(it) == null })
    }

    @Test
    fun creditsComeDownToTheFirstName() {
        assertEquals("Eminem", MixSearch.primaryArtist("Eminem feat. Lil Wayne"))
        assertEquals("Linkin Park", MixSearch.primaryArtist("Linkin Park & Jay-Z"))
        assertEquals("No Love", MixSearch.bareTitle("No Love (feat. Lil Wayne)"))
    }

    private fun upload(id: String, title: String, seconds: Int, channel: String = "someone") =
        SongItem(id = id, title = title, artists = listOf(Artist(name = channel, id = null)), thumbnail = "", duration = seconds)

    /**
     * What the one-song search for Faint found on his S25U on 29 Sep, in the order it offered them
     * (the log keeps titles and lengths, not channels). The Faint x No Love upload is made up: the
     * log does not say which upload was playing, and the search for Faint alone did not find one.
     */
    private val faintAlone = listOf(
        upload("vaint", "LINKIN PARK x СМЕШАРИКИ \u2014 VAINT [MASHUP]", 165),
        upload("euphoric", "Faint - Linkin Park (Euphoric Hardstyle Remix)", 130),
        upload("ultimate", "Ultimate Faint Mashup", 165),
        upload("justdance", "Faint by Linkin Park | Just Dance Fanmade Mashup", 174),
        upload("kaaze", "Linkin Park \u2013 Faint (Original vs KAAZE Rework) (NOID Edited Mashup)", 186),
        upload("djorphix", "Linkin Park: Faint Demo DJOrphix Edition [Mashup]", 177),
        upload("cryforme", "Faint x Cry For Me (Full Mashup)", 168),
        upload("extratainment", "The Linkin Park - Faint Remix with lyrics | extratainment remix", 178),
    )
    private val faintNoLove = upload("faintnolove", "Faint x No Love (Mashup)", 230)

    @Test
    fun `the one-song choice puts an upload naming a song heard with it first`() {
        val piece = sighting(faint, 0).copy(artist = "LINKIN PARK")
        val results = listOf(faintAlone.take(7) + faintNoLove, faintAlone.drop(7))
        // Nothing else heard: YouTube's order, as before.
        assertEquals(faintAlone.take(7).map { it.id } + faintNoLove.id + faintAlone[7].id,
            MixSearch.rankSingle(piece, results, remixFirst = false, heard = emptyList(), speed = 1.0).map { it.id })
        // No Love heard as well: the upload naming both comes first, the rest keep their order.
        val withNoLove = MixSearch.rankSingle(piece, results, remixFirst = false, heard = listOf(sighting(noLove, 133, 191.9, 0.0418)), speed = 1.0)
        assertEquals(listOf(faintNoLove.id) + faintAlone.map { it.id }, withNoLove.map { it.id })
    }

    @Test
    fun `length does not put an upload of Faint alone before one of Faint and No Love`() {
        val heard = listOf(sighting(faint, 0), sighting(noLove, 133))
        val candidates = listOf(faintNoLove) + faintAlone
        // Heard for 180 s: the NOID edit, 186 s, fits the length best.
        assertEquals("kaaze", MixSearch.byLength(candidates, 180.0, 1.0).first().id)
        // Still after the one that names both songs.
        val ordered = MixSearch.byNamed(MixSearch.byLength(candidates, 180.0, 1.0), heard, remixFirst = false, speed = 1.0)
        assertEquals(listOf(faintNoLove.id, "kaaze"), ordered.take(2).map { it.id })
    }

    @Test
    fun `an upload naming the songs by title comes before one naming a song and the artists`() {
        val pieces = listOf(sighting(faint, 0), sighting(noLove, 133))
        val credits = upload("credits", "Linkin Park & Eminem - Faint (Mashup)", 200)
        // Found by both queries, the credits one scores more: 6 to 5.
        val ranked = MixSearch.rank(pieces, listOf(listOf(credits, faintNoLove), listOf(credits)), 1.0)
        assertEquals(listOf(6, 5), listOf(credits, faintNoLove).map { c -> ranked.first { it.first.id == c.id }.second })
        assertEquals(faintNoLove.id, ranked.first().first.id)
        // And it is no winner while the one below it scores more.
        assertNull(MixSearch.clearWinner(ranked, pieces))
    }

    @Test
    fun `two songs by one artist are not both named by that artist`() {
        val numb = "numb" to ("Numb" to "Linkin Park")
        val pieces = listOf(sighting(faint, 0), sighting(numb, 30))
        val numbRemix = upload("numbremix", "Linkin Park - Numb (Remix)", 200)
        val faintNumb = upload("faintnumb", "Faint x Numb (Mashup)", 200)
        assertEquals(1, MixSearch.songsNamed(pieces, numbRemix))
        assertEquals(2, MixSearch.songsNamed(pieces, faintNumb))
        // A remix of Numb alone is never taken for a mashup of Numb and Faint...
        assertNull(MixSearch.clearWinner(MixSearch.rank(pieces, listOf(listOf(numbRemix)), 1.0), pieces))
        // ...and comes after one that names both, though they score the same.
        val ranked = MixSearch.rank(pieces, listOf(listOf(numbRemix, faintNumb)), 1.0)
        assertEquals(listOf(faintNumb.id, numbRemix.id), ranked.map { it.first.id })
    }

    @Test
    fun `an upload naming more of three songs heard comes first`() {
        val nero = "nero" to ("Nero Forte" to "Slipknot")
        val pieces = listOf(sighting(faint, 0), sighting(noLove, 133), sighting(nero, 150))
        val three = upload("three", "Faint / No Love / Nero Forte", 250)
        // The other mashup names two of them, with both their artists, and both queries found it:
        // 8 points to 6. The upload naming all three is still the likelier.
        val ranked = MixSearch.rank(pieces, listOf(listOf(otherMashup, three), listOf(otherMashup)), 1.0)
        assertEquals(listOf(6, 8), listOf(three, otherMashup).map { c -> ranked.first { it.first.id == c.id }.second })
        assertEquals(listOf(three.id, otherMashup.id), ranked.map { it.first.id })
        assertEquals(3, MixSearch.songsNamed(pieces, damageLyrics))
    }

    /**
     * His S25U, 29 Sep, 10:47:43 on: the Faint x No Love mashup, seconds in, offset and speed as
     * logged. Faint at speed throughout, No Love twice, 4.2 % fast with its pitch unchanged: fitted
     * to Faint's tempo. One window at 228 s matched nothing.
     */
    private val sep29 = listOf(
        Triple(faint, 0, -4.5 to 0.0003), Triple(faint, 12, 28.8 to 0.0), Triple(faint, 24, 40.8 to -0.0009),
        Triple(faint, 36, 10.2 to -0.0004), Triple(faint, 48, 22.2 to 0.0008), Triple(faint, 60, 34.2 to 0.0012),
        Triple(faint, 72, 46.2 to -0.0003), Triple(faint, 84, 58.2 to 0.0014), Triple(faint, 96, 32.8 to 0.0010),
        Triple(faint, 108, 44.8 to 0.0004), Triple(faint, 120, 56.8 to 0.0006), Triple(faint, 132, 31.5 to 0.0014),
        Triple(noLove, 144, 191.9 to 0.0418), Triple(noLove, 156, 204.4 to 0.0417), Triple(faint, 168, 31.9 to 0.0008),
        Triple(faint, 180, 67.1 to 0.0008), Triple(faint, 192, 41.7 to 0.0007), Triple(faint, 204, 53.7 to -0.0004),
        Triple(faint, 216, 104.9 to 0.0008), Triple(faint, 240, 121.7 to 0.0017), Triple(faint, 252, 133.7 to -0.0010),
        Triple(faint, 264, 145.7 to 0.0013),
    )

    @Test
    fun `No Love fitted to Faint's tempo is no sped-up mashup`() {
        val watch = MixWatch()
        val verdicts = sep29.take(15).map { (song, at, os) -> watch.observe(sighting(song, at, os.first, os.second)) }
        // Faint back after No Love twice: the mashup check fires, as it did on the phone.
        val mix = verdicts.last()!!
        assertEquals(listOf("faint", "nolove"), mix.pieces.map { it.key })
        assertEquals(1.0008, watch.speedOf("faint")!!, 0.0005)
        assertEquals(1.04175, watch.speedOf("nolove")!!, 0.00001)
        // One song a few percent off is how a mashup fits its songs together, not the upload's speed.
        val speed = MixSearch.roomSpeed(mix.pieces.map { watch.speedOf(it.key)!! })
        assertEquals(1.0, speed, 0.0)
        assertEquals(listOf("Faint No Love mashup", "Linkin Park Eminem mashup"), MixSearch.queries(mix.pieces, speed))
    }

    @Test
    fun `a song's speed is the middle of what its windows read`() {
        // DNA. played straight on 24 Sep read 3 % slow twice in fifteen windows.
        val watch = MixWatch()
        listOf(-0.0294, 0.0005, -0.0004, -0.0273, 0.0001, -0.0002, -0.0006, 0.0002, 0.0003)
            .forEachIndexed { i, skew -> watch.observe(MixWatch.Sighting("dna", "DNA.", "Kendrick Lamar", i * 12_000L, i * 12.0, skew)) }
        assertEquals(1.0, watch.speedOf("dna")!!, 0.0005)
        assertNull(watch.speedOf("nothing"))
    }

    @Test
    fun `a mashup is off speed only when all its songs are`() {
        assertEquals(1.25, MixSearch.roomSpeed(listOf(1.30, 1.25)), 0.0)
        assertEquals(0.85, MixSearch.roomSpeed(listOf(0.80, 0.85)), 0.0)
        assertEquals(1.0, MixSearch.roomSpeed(listOf(1.25, 0.80)), 0.0)
        assertEquals(1.0, MixSearch.roomSpeed(listOf(1.25, 1.02)), 0.0)
        assertEquals(1.0, MixSearch.roomSpeed(listOf(1.04)), 0.0)
        assertEquals(1.12, MixSearch.roomSpeed(listOf(1.12)), 0.0)
    }

    @Test
    fun `a room speed nothing could play at is held to one that could`() {
        // Shazam's skew never comes near -1, but a speed of nothing or less would make every upload
        // last forever or for less than nothing.
        assertEquals(0.5, MixSearch.roomSpeed(listOf(0.0, -0.2)), 0.0)
        assertEquals(2.0, MixSearch.roomSpeed(listOf(3.5, 2.6)), 0.0)
        val upload = upload("u", "Faint x No Love (Mashup)", 200)
        assertEquals(400.0, MixSearch.roomLength(upload, MixSearch.roomSpeed(listOf(-1.0)))!!, 0.01)
    }

    @Test
    fun `a mashup heard sped up or slowed is searched for as such too`() {
        val pieces = listOf(sighting(faint, 0), sighting(noLove, 133))
        val plain = listOf("Faint No Love mashup", "Linkin Park Eminem mashup")
        assertEquals(plain + "Faint No Love sped up", MixSearch.queries(pieces, 1.12))
        assertEquals(plain + "Faint No Love sped up" + "Faint No Love nightcore", MixSearch.queries(pieces, 1.25))
        assertEquals(plain + "Faint No Love slowed", MixSearch.queries(pieces, 0.85))
        assertEquals(listOf("Faint Linkin Park mashup", "Faint Linkin Park remix", "Faint Linkin Park slowed"),
            MixSearch.singleQueries(sighting(faint, 0), 0.85))
    }

    @Test
    fun `an upload lasts as long as the room plays it`() {
        val long = upload("long", "Faint x No Love (Mashup)", 200)
        val short = upload("short", "Faint x No Love [Mashup]", 165)
        val sped = upload("sped", "Faint x No Love Mashup (sped up)", 150)
        // At speed, 150 s heard: the 165 s upload fits best.
        assertEquals(listOf("short", "sped", "long"), MixSearch.byLength(listOf(long, short, sped), 150.0, 1.0).map { it.id })
        // A quarter faster, 200 s lasts 160 and 165 lasts 132, over before 150 s had been heard. The
        // sped-up upload already runs that fast, and lasts its own 150.
        assertEquals(160.0, MixSearch.roomLength(long, 1.25)!!, 0.01)
        assertEquals(150.0, MixSearch.roomLength(sped, 1.25)!!, 0.01)
        // The 165 s one could be sped up already without saying so, and last its own 165: kept, but
        // after the ones that fit as the room plays them.
        assertEquals(165.0, MixSearch.longestInRoom(short, 1.25)!!, 0.01)
        assertTrue(MixSearch.couldBe(short, 150.0, 1.25))
        assertEquals(listOf("long", "sped", "short"), MixSearch.byLength(listOf(long, short, sped), 150.0, 1.25).map { it.id })
        // Not once more has been heard than even that.
        assertFalse(MixSearch.couldBe(short, 180.0, 1.25))
    }

    @Test
    fun `an upload that could be sped up already is not dropped or ended early`() {
        // The other mashup of Faint and No Love, 250 s, in a room playing a quarter fast: 200 s if
        // it is being sped up there, 250 if the upload is the sped-up one and does not say so. Heard
        // for 220 s, it is still a candidate, and a mashup taken as it ends no sooner than 250 s on.
        val other = upload("other", "Eminem & LINKIN PARK - No Love / Faint (No Love x Faint Mashup)", 250)
        assertEquals(200.0, MixSearch.roomLength(other, 1.25)!!, 0.01)
        assertEquals(250.0, MixSearch.longestInRoom(other, 1.25)!!, 0.01)
        assertTrue(MixSearch.couldBe(other, 220.0, 1.25))
        // Slowed, the room length is the longer, as before.
        assertEquals(312.5, MixSearch.longestInRoom(other, 0.8)!!, 0.01)
        // An upload saying how fast it runs lasts what it says: its own length when that agrees
        // with the room, the room's when it does not.
        val sped = upload("sped", "Faint x No Love (sped up)", 200)
        assertEquals(200.0, MixSearch.longestInRoom(sped, 1.25)!!, 0.01)
        val slowed = upload("slowed", "Faint x No Love (slowed + reverb)", 250)
        assertEquals(200.0, MixSearch.longestInRoom(slowed, 1.25)!!, 0.01)
        assertFalse(MixSearch.couldBe(slowed, 220.0, 1.25))
        // At speed, its own length.
        assertEquals(250.0, MixSearch.longestInRoom(other, 1.0)!!, 0.01)
        assertNull(MixSearch.longestInRoom(SongItem(id = "x", title = "x", artists = emptyList(), thumbnail = ""), 1.25))
    }

    @Test
    fun `uploads that say they run as fast as the room come first`() {
        val pieces = listOf(sighting(faint, 0), sighting(noLove, 133))
        val plain = upload("plain", "Faint x No Love (Mashup)", 200)
        val sped = upload("sped", "Faint x No Love Mashup (sped up)", 160)
        assertEquals(listOf("sped", "plain"), MixSearch.rank(pieces, listOf(listOf(plain, sped)), 1.25).map { it.first.id })
        assertEquals(listOf("plain", "sped"), MixSearch.rank(pieces, listOf(listOf(sped, plain)), 1.0).map { it.first.id })
        // A quarter fast, an upload at its own speed that scores more, from both artists and both
        // queries, still comes after the sped-up one, and is not taken without asking.
        val fast = MixSearch.rank(pieces, listOf(listOf(otherMashup, sped), listOf(otherMashup)), 1.25)
        assertEquals(listOf(6, 8), listOf(sped, otherMashup).map { c -> fast.first { it.first.id == c.id }.second })
        assertEquals(listOf("sped", otherMashup.id), fast.map { it.first.id })
        assertNull(MixSearch.clearWinner(fast, pieces))
        // One song cut up a quarter fast: its sped-up uploads are versions of it too, and come first.
        val piece = sighting(faint, 0)
        val results = listOf(listOf(faintAlone[1], upload("spedfaint", "Linkin Park - Faint (sped up)", 130)))
        assertEquals(listOf("spedfaint", "euphoric"),
            MixSearch.rankSingle(piece, results, remixFirst = false, heard = emptyList(), speed = 1.25).map { it.id })
        assertEquals(listOf("euphoric"), MixSearch.rankSingle(piece, results, remixFirst = false, heard = emptyList(), speed = 1.0).map { it.id })
    }

    @Test
    fun `the log says why the first mashup was or was not taken`() {
        // The Damage run: two uploads name both songs and score the same.
        val pieces = MixWatch().observeAll(damage2)[16]!!.pieces
        val tie = MixSearch.rank(pieces, listOf(listOf(damage, damageLyrics, otherMashup, psychofaint), listOf(damage, breakingTheHabit)), 1.0)
        val standing = MixSearch.standing(tie, pieces, 1.0)
        assertTrue(standing, standing.startsWith("'${damageLyrics.title}' names 2 of 2 songs, 2 by title, score 7, '"))
        assertTrue(standing, standing.endsWith("' scores 7"))
        assertEquals("'${otherMashup.title}' scores 7, as much or more", MixSearch.notClear(tie, pieces))
        // Well ahead, it is taken, and the line says by how much.
        val clear = MixSearch.rank(pieces, listOf(listOf(damageLyrics, breakingTheHabit)), 1.0)
        assertNull(MixSearch.notClear(clear, pieces))
        assertTrue(MixSearch.standing(clear, pieces, 1.0).endsWith("score 7, 4 ahead of '${breakingTheHabit.title}'"))
        // One point ahead is still too close: 7 against 6, found by both queries.
        val other = upload("close", "Faint x No Love (Mashup)", 200)
        val close = MixSearch.rank(pieces, listOf(listOf(damageLyrics, other), listOf(other)), 1.0)
        assertEquals("'Faint x No Love (Mashup)' scores 6, too close", MixSearch.notClear(close, pieces))
        // A remix of one of two songs by one artist, however it scores.
        val numb = listOf(sighting(faint, 0), sighting("numb" to ("Numb" to "Linkin Park"), 30))
        val numbRemix = MixSearch.rank(numb, listOf(listOf(upload("r", "Linkin Park - Numb (Remix)", 200))), 1.0)
        assertEquals("it names only 1 of the songs", MixSearch.notClear(numbRemix, numb))
        assertTrue(MixSearch.standing(numbRemix, numb, 1.25).endsWith("the only one, the room at x1.25"))
    }

    @Test
    fun `the log says why the one-song choice starts where it does`() {
        val piece = sighting(faint, 0).copy(artist = "LINKIN PARK")
        val results = listOf(faintAlone.take(7) + faintNoLove, faintAlone.drop(7))
        val alone = MixSearch.rankSingle(piece, results, remixFirst = false, heard = emptyList(), speed = 1.0)
        assertEquals("'${faintAlone[0].title}' 165s, YouTube's order", MixSearch.firstBecause(alone, listOf(piece), 1.0, remixFirst = false))
        val heard = listOf(piece, sighting(noLove, 144, 191.9, 0.0418))
        val withNoLove = MixSearch.rankSingle(piece, results, remixFirst = false, heard = heard, speed = 1.0)
        assertEquals("'${faintNoLove.title}' 230s, it names 2 of the 2 songs heard, the next 1",
            MixSearch.firstBecause(withNoLove, heard, 1.0, remixFirst = false))
        val sped = listOf(upload("s", "Linkin Park - Faint (sped up)", 130), faintAlone[1])
        assertEquals("'Linkin Park - Faint (sped up)' 130s, its title says it runs as fast as the room, the room at x1.25",
            MixSearch.firstBecause(sped, listOf(piece), 1.25, remixFirst = false))
        assertEquals("nothing to offer", MixSearch.firstBecause(emptyList(), listOf(piece), 1.0, remixFirst = false))
    }

    @Test
    fun `speed words count only once the room is further off than a DJ plays`() {
        val sped = upload("sped", "Faint x No Love (sped up)", 200)
        val nightcore = upload("nightcore", "Faint x No Love (nightcore)", 200)
        val slowed = upload("slowed", "Faint x No Love (slowed + reverb)", 250)
        val plain = upload("plain", "Faint x No Love (Mashup)", 220)
        // 7 % fast is a DJ edit's tempo; a sped-up upload runs a fifth or more fast. Neither for nor
        // against it, while a slowed one is plainly not it.
        assertEquals(listOf(0, 0, -1, 0), listOf(sped, nightcore, slowed, plain).map { MixSearch.speedFit(it, 1.07) })
        assertEquals(listOf(-1, -1, 0, 0), listOf(sped, nightcore, slowed, plain).map { MixSearch.speedFit(it, 0.93) })
        // Further off, they fit.
        assertEquals(listOf(1, 1, -1, 0), listOf(sped, nightcore, slowed, plain).map { MixSearch.speedFit(it, 1.25) })
        assertEquals(listOf(-1, -1, 1, 0), listOf(sped, nightcore, slowed, plain).map { MixSearch.speedFit(it, 0.85) })

        // So at 7 % fast a nightcore upload no longer comes before a mashup saying it is one...
        val pieces = listOf(sighting(faint, 0), sighting(noLove, 133))
        assertEquals(listOf("plain", "nightcore"), MixSearch.rank(pieces, listOf(listOf(nightcore, plain)), 1.07).map { it.first.id })
        assertEquals(listOf("nightcore", "plain"), MixSearch.rank(pieces, listOf(listOf(plain, nightcore)), 1.25).map { it.first.id })
        // ...and is not searched for.
        val both = listOf("Faint No Love mashup", "Linkin Park Eminem mashup")
        assertEquals(both, MixSearch.queries(pieces, 1.07))
        assertEquals(both, MixSearch.queries(pieces, 0.93))
        assertEquals(both + "Faint No Love slowed", MixSearch.queries(pieces, 0.9))
        // One song cut up at 7 % fast: its sped-up upload, with no word saying it is a mix, is the
        // song and not a version of it.
        val piece = sighting(faint, 0)
        val results = listOf(listOf(faintAlone[1], upload("spedfaint", "Linkin Park - Faint (sped up)", 130)))
        assertEquals(listOf("euphoric"), MixSearch.rankSingle(piece, results, remixFirst = false, heard = emptyList(), speed = 1.07).map { it.id })
        assertEquals(listOf("Faint Linkin Park mashup", "Faint Linkin Park remix"), MixSearch.singleQueries(piece, 1.07))
    }

    @Test
    fun `songs Shazam knows as sped up make sped-up uploads fit at speed`() {
        // Shazam knows official sped-up releases and matches them at their own speed, so a room
        // playing a mashup of them reads as at speed.
        val spedFaint = "spedfaint" to ("Faint (Sped Up)" to "Linkin Park")
        val spedNoLove = "spednolove" to ("No Love (Sped Up)" to "Eminem")
        val pieces = listOf(sighting(spedFaint, 0), sighting(spedNoLove, 133))
        val sped = upload("sped", "Faint x No Love (sped up)", 200)
        val plain = upload("plain", "Faint x No Love (Mashup)", 220)
        val slowed = upload("slowed", "Faint x No Love (slowed + reverb)", 250)
        assertEquals(listOf(1, 0, -1), listOf(sped, plain, slowed).map { MixSearch.speedFit(it, 1.0, pieces) })
        // Songs heard as themselves, or only one of them sped up: a sped-up upload is not what plays.
        val asThemselves = listOf(sighting(faint, 0), sighting(noLove, 133))
        assertEquals(listOf(-1, 0, -1), listOf(sped, plain, slowed).map { MixSearch.speedFit(it, 1.0, asThemselves) })
        val oneOfThem = listOf(sighting(spedFaint, 0), sighting(noLove, 133))
        assertEquals(listOf(-1, 0, -1), listOf(sped, plain, slowed).map { MixSearch.speedFit(it, 1.0, oneOfThem) })
        // Slowed ones the same way.
        val slowedPieces = listOf(sighting("slowedfaint" to ("Faint (Slowed + Reverb)" to "Linkin Park"), 0))
        assertEquals(listOf(-1, 0, 1), listOf(sped, plain, slowed).map { MixSearch.speedFit(it, 1.0, slowedPieces) })
        // Ranked, searched for, and put first in the log's words accordingly.
        assertEquals(listOf("sped", "plain"), MixSearch.rank(pieces, listOf(listOf(plain, sped)), 1.0).map { it.first.id })
        assertEquals(listOf("Faint No Love mashup", "Linkin Park Eminem mashup", "Faint No Love sped up"), MixSearch.queries(pieces, 1.0))
        assertEquals("'Faint x No Love (sped up)' 200s, its title says it runs as fast as the room",
            MixSearch.firstBecause(listOf(sped, plain), pieces, 1.0, remixFirst = false))

        // One song cut up: its sped-up mashups first. The sped-up release itself, with no word saying
        // it is a mix, is the recording Shazam matched and found cut up, so it is not offered.
        val piece = sighting(spedFaint, 0)
        val results = listOf(listOf(
            upload("mash", "Faint Mashup", 180), upload("spedmash", "Faint Mashup (sped up)", 150),
            upload("release", "Linkin Park - Faint (Sped Up)", 130),
        ))
        assertEquals(listOf("spedmash", "mash"), MixSearch.rankSingle(piece, results, remixFirst = false, heard = emptyList(), speed = 1.0).map { it.id })
        assertEquals(listOf("Faint Linkin Park mashup", "Faint Linkin Park remix", "Faint Linkin Park sped up"), MixSearch.singleQueries(piece, 1.0))
    }

    /**
     * An edit of DNA. that goes back to its top and stops short, as I'm Beggin' For DNA does, and
     * then HUMBLE. from its own top. The engine settles DNA. as an edit only once HUMBLE. has played
     * for two windows, and asks what else was heard with it up to DNA.'s last window.
     */
    @Test
    fun `a song that stopped short is not heard with the one after it`() {
        val dna = "dna" to ("DNA." to "Kendrick Lamar")
        val humble = "humble" to ("HUMBLE." to "Kendrick Lamar")
        val watch = MixWatch()
        val dnaOffsets = listOf(5.0, 17.0, 29.0, 41.0, 53.0, 65.0, 77.0, 89.0, 2.0, 14.0, 26.0, 38.0, 50.0, 62.0)
        dnaOffsets.forEachIndexed { i, offset -> watch.observe(sighting(dna, i * 12, offset)) }
        val dnaLastMs = dnaOffsets.lastIndex * 12_000L
        listOf(5.0, 17.0).forEachIndexed { i, offset -> watch.observe(sighting(humble, (dnaOffsets.size + i) * 12, offset)) }

        assertEquals(listOf("dna", "humble"), watch.heardBetween(0, Long.MAX_VALUE).map { it.key })
        val withIt = watch.heardBetween(0, dnaLastMs)
        assertEquals(listOf("dna"), withIt.map { it.key })
        // As last heard up to then: 62 s in.
        assertEquals(62.0, withIt.single().offsetSeconds, 0.0)
        // From partway in, too.
        assertEquals(listOf("humble"), watch.heardBetween(dnaLastMs + 1, Long.MAX_VALUE).map { it.key })

        // Which keeps the choice in YouTube's order, the edit that was playing first, where HUMBLE.
        // counted as heard would put a mashup of the two ahead of it.
        val beggin = upload("beggin", "I'm Beggin' For DNA [Mashup]", 199, "dasonrz")
        val remix = upload("remix", "Kendrick Lamar - DNA. (Remix)", 240)
        val withHumble = upload("dnahumble", "DNA. x HUMBLE. mashup", 200)
        val piece = sighting(dna, 96, 2.0)
        val results = listOf(listOf(beggin, remix, withHumble))
        assertEquals(listOf("beggin", "remix", "dnahumble"),
            MixSearch.rankSingle(piece, results, remixFirst = false, heard = withIt, speed = 1.0).map { it.id })
        assertEquals("dnahumble",
            MixSearch.rankSingle(piece, results, remixFirst = false, heard = watch.heardBetween(0, Long.MAX_VALUE), speed = 1.0).first().id)
    }

    /**
     * The mashup of 29 Sep as far as Faint coming back after No Love, and the songs its return
     * names. The one-song choice for Faint was answered before No Love came, from a search for Faint
     * alone.
     */
    private fun faintNoLoveReturn(): List<MixWatch.Sighting> {
        val watch = MixWatch()
        val mix = sep29.take(15).map { (song, at, os) -> watch.observe(sighting(song, at, os.first, os.second)) }.last()!!
        return MixSearch.distinctSongs(mix.pieces)
    }

    private val faintPiece = sighting(faint, 0)
    private val searchedFaint = setOf(MixSearch.songId(faintPiece))

    @Test
    fun `a second song an answer never looked for is searched for with the first`() {
        val songs = faintNoLoveReturn()
        assertEquals(listOf("faint", "nolove"), songs.map { it.key })
        // Said none of these, or picked an upload of Faint alone: No Love was never searched for.
        assertEquals(listOf("nolove"), MixSearch.uncovered(true, null, searchedFaint, songs, listOf(faintPiece)).map { it.key })
        val faintAloneUpload = faintAlone.first { it.id == "ultimate" }
        assertEquals(listOf("nolove"), MixSearch.uncovered(true, faintAloneUpload, searchedFaint, songs, listOf(faintPiece)).map { it.key })
        // Searched for both already, still open, or answered in an earlier mashup: nothing to search.
        assertTrue(MixSearch.uncovered(true, null, MixSearch.noted(searchedFaint, songs), songs, listOf(faintPiece)).isEmpty())
        assertTrue(MixSearch.uncovered(false, null, searchedFaint, songs, listOf(faintPiece)).isEmpty())
        assertTrue(MixSearch.uncovered(true, null, emptySet(), songs, listOf(faintPiece)).isEmpty())
        // Another version of the same song is not a new song.
        val faintRemix = "faint2" to ("Faint (Euphoric Hardstyle Remix)" to "Linkin Park")
        assertTrue(MixSearch.uncovered(true, null, searchedFaint, listOf(sighting(faintRemix, 200), faintPiece), listOf(faintPiece)).isEmpty())
    }

    @Test
    fun `an upload picked that names the song coming back is not asked about again`() {
        // The one-song choice for Faint can offer the Faint x No Love upload among its three, as
        // YouTube orders it, before No Love has been heard...
        val results = listOf(faintAlone.take(1) + faintNoLove + faintAlone.drop(1))
        val offered = MixSearch.rankSingle(faintPiece, results, remixFirst = false, heard = emptyList(), speed = 1.0)
        assertTrue(faintNoLove in offered.take(3))
        // ...and once it is picked, No Love coming back is what it said: nothing searched, nothing
        // asked. Searching again found the same upload and asked the question just answered.
        val songs = faintNoLoveReturn()
        assertTrue(MixSearch.uncovered(true, faintNoLove, searchedFaint, songs, listOf(faintPiece)).isEmpty())
        // By its artist, when no other song heard is by the same one.
        val byArtists = upload("byartists", "Linkin Park vs Eminem (Mashup)", 200)
        assertTrue(MixSearch.uncovered(true, byArtists, searchedFaint, songs, listOf(faintPiece)).isEmpty())
        // An upload of Faint credited to Linkin Park does not name Numb, by Linkin Park as well, even
        // when Numb comes back with another song and Faint is only in what was heard before.
        val numb = "numb" to ("Numb" to "Linkin Park")
        val faintByLp = upload("faintlp", "Linkin Park - Faint (Mashup)", 200)
        val numbBack = listOf(sighting(numb, 200), sighting(noLove, 212))
        val searched = MixSearch.noted(searchedFaint, listOf(sighting(noLove, 0)))
        assertEquals(listOf("numb"), MixSearch.uncovered(true, faintByLp, searched, numbBack, listOf(faintPiece)).map { it.key })
    }

    @Test
    fun `a third song the upload taken already names is not asked about again`() {
        val nero = "nero" to ("Nero Forte" to "Slipknot")
        val pieces = listOf(sighting(faint, 0), sighting(noLove, 12))
        // Taken from the search for Faint and No Love: the Damage upload that lists Nero Forte too.
        val taken = MixSearch.clearWinner(MixSearch.rank(pieces, listOf(listOf(damageLyrics, breakingTheHabit)), 1.0), pieces)
        assertEquals(damageLyrics.id, taken?.id)
        val searched = MixSearch.noted(emptySet(), pieces)
        val back = listOf(sighting(nero, 150), sighting(faint, 162))
        assertTrue(MixSearch.uncovered(true, taken, searched, back, pieces).isEmpty())
        // Taken as the other mashup of the two, which does not list it: searched for with them.
        assertEquals(listOf("nero"), MixSearch.uncovered(true, otherMashup, searched, back, pieces).map { it.key })
    }

    @Test
    fun `an answer stands unless an upload names the new song with the others`() {
        val songs = faintNoLoveReturn()
        val heard = listOf(faintPiece) + songs
        val uncovered = MixSearch.uncovered(true, null, searchedFaint, songs, heard)

        // Nothing names them together: the answer stands, No Love comes out of the list as one more
        // piece of it, and the two are noted, so the next return does not search YouTube again.
        val nothing = MixSearch.outcome(uncovered, reopened = false, ranked = emptyList(), winner = null, strong = true, heard = heard)
        assertEquals(MixSearch.Outcome.STAND, nothing)
        assertTrue(nothing.stands && nothing.notes && !nothing.goesOn)
        assertTrue(MixSearch.uncovered(true, null, MixSearch.noted(searchedFaint, songs), songs, heard).isEmpty())

        // A failed search leaves it standing without noting them: the next return tries again.
        val failed = MixSearch.outcome(uncovered, false, null, null, true, heard)
        assertEquals(MixSearch.Outcome.STAND_FOR_NOW, failed)
        assertTrue(failed.stands && !failed.notes && !failed.goesOn)

        // An upload naming both: asked again, the answer set aside, and never taken by itself,
        // however clear, even once the songs have taken turns.
        val ranked = MixSearch.rank(songs, listOf(listOf(faintNoLove)), 1.0)
        val winner = MixSearch.clearWinner(ranked, songs)
        assertEquals(faintNoLove.id, winner?.id)
        val reopen = MixSearch.outcome(uncovered, false, ranked, winner, strong = true, heard = heard)
        assertEquals(MixSearch.Outcome.REOPEN, reopen)
        assertTrue(reopen.goesOn && reopen.reopens && reopen.notes && !reopen.mayTake && !reopen.stands)
    }

    @Test
    fun `one odd window with only a toss-up does not reopen an answer`() {
        val songs = faintNoLoveReturn()
        val heard = listOf(faintPiece) + songs
        val uncovered = MixSearch.uncovered(true, null, searchedFaint, songs, heard)
        val tossUp = MixSearch.rank(songs, listOf(listOf(faintNoLove, upload("nolovefaint", "No Love x Faint (Mashup)", 210))), 1.0)
        assertNull(MixSearch.clearWinner(tossUp, songs))
        // Weak: the answer stands, and a return that says more can still ask.
        val weak = MixSearch.outcome(uncovered, false, tossUp, null, strong = false, heard = heard)
        assertEquals(MixSearch.Outcome.STAND_FOR_NOW, weak)
        assertFalse(weak.reopens || weak.goesOn || weak.notes)
        // Strong, the same toss-up is asked about.
        assertEquals(MixSearch.Outcome.REOPEN, MixSearch.outcome(uncovered, false, tossUp, null, strong = true, heard = heard))
    }

    @Test
    fun `uploads that do not name the new song leave the answer standing`() {
        // Faint x No Love picked, and then Numb with them. What the search found names Faint and No
        // Love, which the answer already covers, and not Numb.
        val numb = "numb" to ("Numb" to "Linkin Park")
        val songs = listOf(sighting(faint, 300), sighting(noLove, 288), sighting(numb, 276))
        val searched = MixSearch.noted(searchedFaint, listOf(sighting(noLove, 0)))
        val uncovered = MixSearch.uncovered(true, faintNoLove, searched, songs, songs)
        assertEquals(listOf("numb"), uncovered.map { it.key })
        val ranked = MixSearch.rank(songs, listOf(listOf(faintNoLove, otherMashup)), 1.0)
        assertTrue(ranked.isNotEmpty())
        val outcome = MixSearch.outcome(uncovered, false, ranked, MixSearch.clearWinner(ranked, songs), strong = true, heard = songs)
        assertEquals(MixSearch.Outcome.STAND, outcome)
        // An upload naming Numb with them asks again.
        val three = upload("three", "Faint x No Love x Numb (Mashup)", 260)
        val withNumb = MixSearch.rank(songs, listOf(listOf(three, faintNoLove)), 1.0)
        assertEquals(MixSearch.Outcome.REOPEN, MixSearch.outcome(uncovered, false, withNumb, MixSearch.clearWinner(withNumb, songs), true, songs))
    }

    @Test
    fun `a mashup never answered goes on as before, and one asked again is only asked`() {
        val songs = faintNoLoveReturn()
        val ranked = MixSearch.rank(songs, listOf(listOf(faintNoLove)), 1.0)
        val winner = MixSearch.clearWinner(ranked, songs)
        val tossUp = MixSearch.rank(songs, listOf(listOf(faintNoLove, upload("nolovefaint", "No Love x Faint (Mashup)", 210))), 1.0)
        val none = emptyList<MixWatch.Sighting>()
        assertEquals(MixSearch.Outcome.LEAVE, MixSearch.outcome(none, false, null, null, true, songs))
        assertEquals(MixSearch.Outcome.LEAVE, MixSearch.outcome(none, false, emptyList(), null, true, songs))
        assertEquals(MixSearch.Outcome.LEAVE, MixSearch.outcome(none, false, tossUp, null, false, songs))
        assertEquals(MixSearch.Outcome.ANSWER, MixSearch.outcome(none, false, tossUp, null, true, songs))
        val clear = MixSearch.outcome(none, false, ranked, winner, false, songs)
        assertEquals(MixSearch.Outcome.ANSWER, clear)
        assertTrue(clear.goesOn && clear.mayTake && !clear.reopens)
        // Answered once and asked again: still asked, never taken, and nothing more to set aside.
        val asked = MixSearch.outcome(none, true, ranked, winner, true, songs)
        assertEquals(MixSearch.Outcome.ASK, asked)
        assertTrue(asked.goesOn && !asked.mayTake && !asked.reopens)
        assertEquals(MixSearch.Outcome.LEAVE, MixSearch.outcome(none, true, emptyList(), null, true, songs))
    }
}
