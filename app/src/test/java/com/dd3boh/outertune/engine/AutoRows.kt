/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.engine

import com.dd3boh.outertune.constants.EndReason
import com.dd3boh.outertune.constants.PlayOrigin
import java.security.MessageDigest
import java.util.Locale
import kotlin.random.Random

/**
 * Quick picks with no lean, written out: two made-up libraries, a big one and a small one, and a
 * list of builds over them, one line a build, so the rows of today can be held against the rows the
 * engine gave before it could lean (see [AutoUnchangedTest]).
 *
 * Nothing here names a lean, and the build itself is handed in, so the same file compiles against
 * the engine as it was and wrote the lines kept in `engine/auto-rows.txt`.
 */
internal object AutoRows {
    const val NOW = 1_789_135_200_000L
    private const val HOUR = 3_600_000L
    private const val DAY = 86_400_000L

    /** A build as the app asks for it: input, weights, parameters, dial, New songs only, never played, dice, Last.fm share. */
    fun interface Build {
        fun row(input: EngineInput, weights: Weights, p: EngineParams, dial: Double, newOnly: Boolean, neverPlayed: Boolean, random: Random, lastFmShare: Double?): BuiltRow
    }

    /** One build's settings; every case is built once for each of its [dice]. */
    class Case(
        val name: String,
        val chip: Int = ContextChip.AUTO,
        val newOnly: Boolean = false,
        val neverPlayed: Boolean = false,
        val dial: Double = 0.5,
        val familiarity: Double = 0.25,
        val lastFmShare: Double? = null,
        /** A pull to refresh: the row before it sits this build out and its seeds are damped. */
        val pull: Boolean = false,
        val learned: Boolean = false,
        /** Three hours on, with nothing playing and no session under way. */
        val later: Boolean = false,
        val notSeeds: Set<String> = emptySet(),
        val dice: Int = 4,
        /** Built over the small library, where lanes run short, instead of the big one. */
        val small: Boolean = false,
    )

    val cases = listOf(
        Case("auto", dice = 12),
        Case("discover", chip = ContextChip.DISCOVER),
        Case("favourites", chip = ContextChip.FAVOURITES),
        Case("focus", chip = ContextChip.FOCUS),
        Case("chill", chip = ContextChip.CHILL),
        Case("party", chip = ContextChip.PARTY),
        Case("new songs only", newOnly = true),
        Case("new songs only, discover", chip = ContextChip.DISCOVER, newOnly = true),
        Case("new songs only, chill", chip = ContextChip.CHILL, newOnly = true),
        Case("never played", neverPlayed = true),
        Case("both", lastFmShare = 0.5),
        Case("both at four in five", lastFmShare = 0.8),
        Case("both, chill", chip = ContextChip.CHILL, lastFmShare = 0.5),
        Case("both, never played", neverPlayed = true, lastFmShare = 0.5),
        Case("adventurous and familiar", dial = 0.9, familiarity = 0.45),
        Case("neither", dial = 0.0, familiarity = 0.0),
        Case("after a pull", pull = true),
        Case("learned weights", learned = true),
        Case("nothing playing", later = true),
        Case("seeds turned down", notSeeds = setOf("a0s1", "a5s2", "a1s1")),
        // The small library, after everything above so that the lines of the big one keep their places.
        Case("small library", small = true),
        Case("small library, discover", chip = ContextChip.DISCOVER, small = true),
        Case("small library, new songs only", newOnly = true, small = true),
        Case("small library, both", lastFmShare = 0.5, small = true),
    )

    private class World(val now: Long) {
        val songs = LinkedHashMap<String, SongRow>()
        val listens = ArrayList<ListenRow>()
        val edges = ArrayList<Edge>()
        fun song(id: String, artist: String, title: String, likedDaysAgo: Int? = null, inLibrary: Boolean = true) {
            songs[id] = SongRow(id, title, "art_$artist", artist, likedDaysAgo != null, likedDaysAgo?.let { NOW - it * DAY - 7 * HOUR }, inLibrary)
        }
        fun play(id: String, hoursAgo: Double, session: Long, ratio: Double = 1.0, origin: PlayOrigin = PlayOrigin.SEARCH, ended: Int = EndReason.ENDED, depth: Int = 0, chip: Int = 0) {
            val start = NOW - (hoursAgo * HOUR).toLong()
            val dur = 200_000L; val played = (dur * ratio).toLong()
            val end = if (ended == EndReason.OPEN) now else start + played
            listens += ListenRow(id, start, end, played, dur, ended, origin.code, depth, session, 120, contextChip = chip, id = listens.size + 1L)
        }
        fun edge(seed: String, to: String, sources: Int) { edges += Edge(seed, to, sources) }
    }

    /**
     * Twenty artists of eight songs and a live cut each. Artists 0 to 7 were heard daily over the
     * last month, 8 to 11 two months ago and not since, 12 to 19 never, and those are only in the
     * table because related lists named them. Artists 0 to 7 also have four songs never heard, and
     * twenty-four more artists nobody has played hang off their related lists. Some titles carry a
     * treatment, some songs are liked, a fortnight was spent on Chill, and a session is under way
     * with a song ten seconds in. Edges come from both sources, so Both has something to split.
     */
    private fun world(later: Boolean): World {
        val w = World(if (later) NOW + 3 * HOUR else NOW)
        val liked = mapOf("a0s0" to 9, "a1s2" to 20, "a4s1" to 3, "a6s3" to 40, "a9s3" to 80, "a10s5" to 75, "a2x1" to 5, "a13s2" to 30)
        val treated = mapOf("a2s1" to " (Slowed)", "a3s2" to " (Nightcore)", "a5s3" to " (Instrumental)", "a14s1" to " (Slowed + Reverb)", "a15s4" to " (Sped Up)")
        for (a in 0 until 20) for (i in 0 until 8) w.song("a${a}s$i", "artist$a", "Song $a $i" + treated["a${a}s$i"].orEmpty(), liked["a${a}s$i"], inLibrary = a < 12)
        for (a in 0 until 20) w.song("a${a}live", "artist$a", "Song $a 0 (Live)", inLibrary = a < 12)
        for (a in 0 until 8) for (i in 0 until 4) w.song("a${a}x$i", "artist$a", "Extra $a $i", liked["a${a}x$i"], inLibrary = false)
        for (k in 0 until 24) for (i in 0 until 2) w.song("n${k}s$i", "newartist$k", "Fresh $k $i", inLibrary = false)
        // YouTube lists the next artist's songs, Last.fm the one after, and both have a list for every seed.
        for (a in 0 until 20) for (i in 0 until 8) for (t in 0 until 8) {
            w.edge("a${a}s$i", "a${(a + 1) % 20}s$t", if (t < 2) Provenance.SOURCES or Provenance.CONTESTED else Provenance.YOUTUBE or Provenance.CONTESTED)
            w.edge("a${a}s$i", "a${(a + 2) % 20}s$t", Provenance.LASTFM or Provenance.CONTESTED)
        }
        for (a in 0 until 8) for (i in 0 until 4) {
            for (t in 0 until 4) w.edge("a${a}s$i", "a${(a + 1) % 8}x$t", Provenance.YOUTUBE or Provenance.CONTESTED)
            for (j in 0 until 3) for (t in 0 until 2) w.edge("a${a}s$i", "n${a + 8 * j}s$t", if (j == 1) Provenance.LASTFM or Provenance.CONTESTED else Provenance.YOUTUBE or Provenance.CONTESTED)
        }
        var session = 1L
        for (d in 1..30) {
            session++
            // Days 3 to 14: artists 2 and 3 were played with Chill on.
            for (a in 0 until 8) w.play("a${a}s${d % 4}", hoursAgo = d * 24.0 + a, session = session, chip = if (d in 3..14 && a in 2..3) ContextChip.CHILL else 0)
            if (d % 5 == 0) w.play("a${d % 8}s5", hoursAgo = d * 24.0 + 9, session = session, ratio = 0.05, ended = EndReason.SKIPPED)
            if (d % 6 == 0) w.play("a${d % 8}s6", hoursAgo = d * 24.0 + 9.5, session = session, origin = PlayOrigin.QUICK_PICKS, depth = 2)
        }
        for (d in 50..70) { session++; for (a in 8..11) w.play("a${a}s${d % 8}", hoursAgo = d * 24.0 + a, session = session) }
        w.play("a1s1", hoursAgo = 20.0, session = 998); w.play("a2s2", hoursAgo = 19.5, session = 998); w.play("a3s3", hoursAgo = 19.0, session = 998)
        // The session under way: six songs heard well, then one ten seconds in.
        w.play("a0s1", hoursAgo = 0.5, session = 999); w.play("a5s2", hoursAgo = 0.44, session = 999)
        for ((k, a) in listOf(1, 2, 6, 7).withIndex()) w.play("a${a}s${k % 4}", hoursAgo = 0.38 - k * 0.06, session = 999)
        if (later) w.play("a7s6", hoursAgo = 10.0 / 3600, session = 999, ratio = 0.05, ended = EndReason.SKIPPED)
        else w.play("a7s6", hoursAgo = 10.0 / 3600, session = 999, ratio = 0.05, ended = EndReason.OPEN)
        return w
    }

    /**
     * A small library, the kind someone has a week into the app: three artists of six songs, a
     * dozen listens, and the related lists of three of the songs, which name two artists never
     * played. Everything heard well is a seed, and no card may be one, so the Again and Rediscover
     * lanes hold nothing and the related lane less than its seven: the row is made by lanes handing
     * their places on, which the big library never does. Under Discover and New songs only the
     * new-to-you lane runs short too, of the half or the whole of the row it is asked for.
     */
    private fun smallWorld(): World {
        val w = World(NOW)
        // The first three songs of each artist are in the library and were played; the others only sit in the table.
        for (a in 0 until 3) for (i in 0 until 6) w.song("s${a}t$i", "small$a", "Tune $a $i", if (a == 0 && i == 0) 4 else null, inLibrary = i < 3)
        for (k in 0 until 2) for (i in 0 until 2) w.song("o${k}t$i", "other$k", "Other $k $i", inLibrary = false)
        w.play("s0t0", hoursAgo = 9 * 24.0, session = 1); w.play("s0t1", hoursAgo = 9 * 24.0 - 0.1, session = 1); w.play("s1t0", hoursAgo = 9 * 24.0 - 0.2, session = 1)
        w.play("s0t0", hoursAgo = 6 * 24.0, session = 2); w.play("s1t1", hoursAgo = 6 * 24.0 - 0.1, session = 2); w.play("s2t0", hoursAgo = 6 * 24.0 - 0.2, session = 2)
        w.play("s1t0", hoursAgo = 3 * 24.0, session = 3); w.play("s0t2", hoursAgo = 3 * 24.0 - 0.1, session = 3); w.play("s2t1", hoursAgo = 3 * 24.0 - 0.2, session = 3)
        w.play("s0t1", hoursAgo = 20.0, session = 4); w.play("s1t2", hoursAgo = 19.9, session = 4)
        w.play("s2t2", hoursAgo = 19.8, session = 4, ratio = 0.05, ended = EndReason.SKIPPED)
        // YouTube has a list for three songs and Last.fm for one of them, so Both has a place to share.
        for (to in listOf("o0t0", "o0t1", "s1t3")) w.edge("s0t0", to, Provenance.YOUTUBE or Provenance.CONTESTED)
        for (to in listOf("o1t0", "o0t0")) w.edge("s0t0", to, Provenance.LASTFM or Provenance.CONTESTED)
        for (to in listOf("o1t0", "o1t1")) w.edge("s1t0", to, Provenance.YOUTUBE)
        w.edge("s2t0", "s0t4", Provenance.YOUTUBE)
        return w
    }

    private val learnedWeights = Weights(mapOf(
        "x_novel" to -0.5, "x_art" to 0.8, "x_seed" to 0.6, "x_dorm" to 0.45, "x_act" to 1.1, "x_imp" to -0.4,
        "w_pos" to -0.3, "b_again" to 0.5, "b_related" to -0.35, "b_explore" to -0.6, "b_artist" to -0.05, "b_rediscover" to 0.1,
    ))

    private fun num(v: Double): String = String.format(Locale.ROOT, "%.9f", v)

    private fun card(c: Card): String =
        "${c.songId}|${c.lane}|${num(c.z)}|${num(c.p)}|${c.features.joinToString(",") { num(it) }}|${c.reasons}|${c.seedId}|${c.sampled}|${c.sources}"

    /** Everything a row is: its cards in order, its pool, its seeds, its quotas, and what the tidy pass leaves on screen. */
    private fun text(row: BuiltRow, screen: List<String>): String = buildString {
        row.cards.forEach { append(card(it)).append('\n') }
        append("pool\n"); row.pool.forEach { append(card(it)).append('\n') }
        append("seeds ${row.seeds}\n")
        append("quotas ${Lane.entries.map { row.quotas[it] }}\n")
        append("share ${row.lastFmShare?.let(::num)}\n")
        append("screen $screen\n")
    }

    private fun digest(text: String): String =
        MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }

    /**
     * The row as Home shows it with no lean: the cards and then the pool through the tidy pass
     * (nothing just played, two an artist, the Again cards exempt), the first twenty.
     */
    private fun screen(row: BuiltRow, w: World): List<String> {
        val current = w.listens.maxByOrNull { it.endedAt }?.takeIf { w.now - it.endedAt <= EngineParams.DEFAULT.sessionGapMs }?.sessionId ?: -1L
        val played = w.listens.filter { l ->
            (l.startedAt >= w.now - DAY && l.playedMs >= 30_000 && l.playedMs * 20 >= l.durationMs * 9) || l.sessionId == current
        }.map { l -> w.songs[l.songId].let { PlayedSong(l.songId, it?.title ?: "", it?.artistName) } }
        val again = row.cards.filter { it.lane == Lane.AGAIN }.mapTo(HashSet()) { it.songId }
        return TidyPass(played).row(row.cards + row.pool, true, { it.songId }, { w.songs[it.songId]?.title }, { w.songs[it.songId]?.artistName }, { w.songs[it.songId]?.artistId }, { it.songId in again }, 2)
            .take(20).map { it.songId }
    }

    /** One line: the case and its dice, a digest of the whole row, and the cards in order, to read. */
    fun line(case: Case, dice: Int, build: Build): String {
        val w = if (case.small) smallWorld() else world(case.later)
        val p = EngineParams.DEFAULT.withFamiliarity(case.familiarity)
        val weights = if (case.learned) learnedWeights else Weights.PRIORS
        var input = if (case.small) EngineInput(
            w.now, w.songs, w.listens, w.edges,
            seen = listOf(SeenCard("o0t1", NOW - 2 * DAY)),
            bucket = dayPartBucket(w.now, 120), tzOffsetMin = 120,
            notSeeds = case.notSeeds, chip = case.chip,
        ) else EngineInput(
            w.now, w.songs, w.listens, w.edges,
            versionLinks = listOf(VersionLink("a4s0", "a4live"), VersionLink("a12s3", "a12live")),
            seen = listOf(SeenCard("a13s0", NOW - 2 * DAY), SeenCard("a13s0", NOW - 3 * DAY), SeenCard("a0x1", NOW - DAY), SeenCard("n3s0", NOW - 5 * DAY)),
            exclusions = listOf(ExclusionRow(1, "a12s7"), ExclusionRow(2, "art_artist19")),
            pastSeeds = listOf(PastSeeds(NOW - 2 * HOUR, listOf("a3s3", "a2s2"))),
            bucket = dayPartBucket(w.now, 120), tzOffsetMin = 120,
            notSeeds = case.notSeeds, chip = case.chip,
        )
        if (case.pull) {
            val before = build.row(input, weights, p, case.dial, case.newOnly, case.neverPlayed, Random(1000L + dice), case.lastFmShare)
            input = input.copy(pastSeeds = input.pastSeeds + PastSeeds(w.now, before.seeds), banned = before.cards.mapTo(HashSet()) { it.songId })
        }
        val row = build.row(input, weights, p, case.dial, case.newOnly, case.neverPlayed, Random(dice.toLong()), case.lastFmShare)
        return "${case.name} #$dice\t${digest(text(row, screen(row, w)))}\t${row.cards.joinToString(",") { it.songId }}"
    }

    fun lines(build: Build): List<String> = cases.flatMap { c -> (0 until c.dice).map { line(c, it, build) } }
}
