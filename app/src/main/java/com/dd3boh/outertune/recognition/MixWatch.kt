/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.recognition

import com.zionhuang.innertube.models.SongItem

/**
 * Tells a mashup from a song by the order Shazam names things in.
 *
 * Shazam only knows the pieces. On 24 Sep a Keep listening run over "Linkin Park / Slipknot /
 * Eminem - Damage [MASHUP]" named Faint seven times, then No Love once, then something it was unsure
 * of, then Faint again, and added Faint, which is not what was playing. That order is the tell. A
 * playlist moving on goes from one song to the next and never comes back; a mashup leaves its main
 * song for another and returns to it. So a song that is interrupted by something else and then
 * heard again, within a few minutes, is a piece of something bigger.
 *
 * How sure that makes it depends on the interruption. One window of something else could be Shazam
 * getting a single window wrong, which does happen on a noisy room, so that alone only earns a look
 * for the mashup. Two windows of it in a row, or a second interruption, is not a mistake repeated,
 * and the pieces come out of the list whether or not the mashup is found.
 *
 * Pure and fed by the engine, one call per window Shazam named, so it can be tested on the timeline
 * the real run produced.
 */
internal class MixWatch(private val spanMs: Long = SPAN_MS) {

    /**
     * One window Shazam named, by its key, whether or not it was confident on YouTube, and where in
     * the song it landed.
     */
    data class Sighting(
        val key: String,
        val title: String,
        val artist: String?,
        val atMs: Long,
        val offsetSeconds: Double = 0.0,
        /** Shazam's speed estimate for this one window. */
        val skew: Double = 0.0,
    )

    /**
     * A song heard, left for something else, and heard again.
     *
     * @param pieces every different song seen from the first sighting of the returning one, the most
     * often heard first and otherwise oldest first. The search is built from the head of this, so a
     * one-window oddity in the middle, which the 24 Sep run had in "Lliving Life Mix", does not end
     * up in the query.
     * @param strong whether the interruption is too long, too often, or the return too far into the
     * song to be one wrong window or somebody going back a track: enough to ask about.
     * @param sure whether the songs have interleaved, more than once or two of them in one gap:
     * enough to act on. A single return partway into a song is also somebody skipping about a
     * playlist and scrubbing back into a song, so on its own it only asks.
     */
    data class Mix(val pieces: List<Sighting>, val strong: Boolean, val sure: Boolean = strong)

    private val seen = ArrayDeque<Sighting>()

    /** A return held back because another song was playing steadily through it. See [observe]. */
    private class Deferred(val mix: Mix, val hostKey: String, val atMs: Long)
    private var deferred: Deferred? = null

    /**
     * A song that came back with something else in between is only a mashup if nothing was playing
     * straight through it. A remix Shazam knows keeps its own timeline from start to end, and Shazam
     * names the original in it now and then, or a sample; on 24 Sep the R3hab remix of Pray to God
     * came through as the remix with the original and Better Off Alone in between, five "returns" in
     * three minutes, while the remix never missed a second of its timeline. In the three mashups
     * played that day nothing kept a timeline: Faint, Party Rock Anthem and Memories all jumped.
     *
     * So when some song has been playing steadily, a return waits for it. If the steady song goes on
     * where it should, what came back was part of it. If it does not within [DEFER_MS], something
     * switched, and the return counts after all.
     */
    fun observe(sighting: Sighting): Mix? {
        seen.addLast(sighting)
        while (sighting.atMs - seen.first().atMs > spanMs) seen.removeFirst()

        deferred?.let { held ->
            val previous = seen.toList().dropLast(1).lastOrNull { it.key == held.hostKey }
            when {
                // The steady song went on where it should have: the return was part of it.
                sighting.key == held.hostKey && previous != null && Timeline.continues(previous, sighting) ->
                    deferred = null
                // Something new altogether: the track changed, and nothing held is about it.
                held.mix.pieces.none { it.key == sighting.key } && sighting.key != held.hostKey ->
                    deferred = null
                // Long gone by now, a pause or a quiet spell: whatever it was about is over.
                sighting.atMs - held.atMs > DEFER_MS + DEFER_GRACE_MS ->
                    deferred = null
                sighting.atMs - held.atMs >= DEFER_MS -> {
                    deferred = null
                    return held.mix
                }
            }
        }

        val list = seen.toList()
        val previous = list.subList(0, list.size - 1).indexOfLast { it.key == sighting.key }
        if (previous < 0) return null
        // What came between this sighting and the last one of the same song. Empty is the song
        // simply carrying on.
        val between = list.subList(previous + 1, list.size - 1)
        if (between.isEmpty()) return null

        val first = list.indexOfFirst { it.key == sighting.key }
        val allOfSpan = list.subList(first, list.size)
        // Only songs heard more than once count as pieces. A mashup comes back to its pieces: No
        // Love twice in Damage, Memories three times in Memories Anthem. Songs Shazam names for one
        // window and never again are noise; 2 Faced Funks' Powerbass had five of them in a row in
        // its breakdown, each a different track, and read as a mashup of all five.
        val heard = allOfSpan.groupingBy { it.key }.eachCount()
        val recurring = heard.filter { (key, times) -> key != sighting.key && times >= 2 }.keys
        if (recurring.isEmpty()) return null
        val span = allOfSpan.filter { it.key == sighting.key || it.key in recurring }
        val interruption = between.filter { it.key in recurring }
        if (interruption.isEmpty()) return null
        // Runs of other songs between sightings of this one, this interruption included.
        val interruptions = span.indices.count { i ->
            i > 0 && span[i].key != sighting.key && span[i - 1].key == sighting.key
        }
        val counts = span.groupingBy { it.key }.eachCount()
        // Sure takes a second gap: songs taking turns. Two songs in one gap, or a return partway
        // into a song, is also somebody skipping through a playlist and back, which is enough to
        // ask about and not enough to take anything out of the list.
        val interleaved = interruptions >= 2
        val twoInOneGap = interruption.size >= 2 && MixSearch.distinctSongs(interruption).size >= 2
        val found = Mix(
            // sortedByDescending is stable, so equal counts keep the order they were heard in.
            pieces = span.distinctBy { it.key }.sortedByDescending { counts[it.key] ?: 0 },
            // Two different songs in one gap, or a second gap, or a return partway into the song.
            // Two windows of one other song is also what skipping back to the previous song looks
            // like, but going back a track starts it again from the top; a mashup cuts back in
            // wherever it likes. The second run of 24 Sep came back to Party Rock Anthem 76 s in.
            // Counted as songs, not keys: Shazam can give one recording two.
            strong = interleaved || twoInOneGap || sighting.offsetSeconds > RESTART_S,
            sure = interleaved,
        )
        // The song that came back never left its own timeline: what came between was inside it,
        // or two songs blending, each on its own timeline, as a DJ does from one to the next.
        if (sighting.key in steadyKeys(sighting.atMs)) return null
        return when (val host = steadyHost(sighting.atMs)) {
            null -> {
                deferred = null
                found
            }
            else -> {
                deferred = deferred?.let { Deferred(found, it.hostKey, it.atMs) } ?: Deferred(found, host, sighting.atMs)
                null
            }
        }
    }

    /** When [key] was last heard, if it is still in view. */
    fun lastHeard(key: String): Long? = seen.lastOrNull { it.key == key }?.atMs

    /** Every song playing straight through the last [HOST_SPAN_MS]; see [steadyHost]. */
    private fun steadyKeys(atMs: Long): Set<String> = seen
        .filter { atMs - it.atMs <= HOST_SPAN_MS }
        .groupBy { it.key }
        .filterValues { windows -> windows.size >= 3 && windows.zipWithNext().all { (a, b) -> Timeline.continues(a, b) } }
        .keys

    /**
     * The song, if any, playing straight through the last [HOST_SPAN_MS]: heard at least three
     * times in that time, every window where the one before says it should be. The most heard, if
     * more than one.
     */
    fun steadyHost(atMs: Long): String? {
        val recent = seen.filter { atMs - it.atMs <= HOST_SPAN_MS }
        val steady = steadyKeys(atMs)
        // The most heard; between two heard as often, the one heard last, so the answer does not
        // depend on which came first.
        return recent.filter { it.key in steady }
            .groupBy { it.key }
            .maxWithOrNull(compareBy<Map.Entry<String, List<Sighting>>> { it.value.size }.thenBy { it.value.last().atMs })
            ?.key
    }

    /** Forgets everything, for a new run or after silence. */
    fun clear() {
        seen.clear()
        deferred = null
    }

    /**
     * Forgets [keys], the pieces of a mashup that is over, and keeps the rest: what ended it, a new
     * song from its top or the first pieces of the next mashup, is what the next verdict needs.
     */
    fun forget(keys: Set<String>) {
        seen.removeAll { it.key in keys }
        deferred?.let { held -> if (held.hostKey in keys || held.mix.pieces.any { it.key in keys }) deferred = null }
    }

    companion object {
        /**
         * How far back a return still counts. A mashup cuts back to its main song within a minute or
         * two; a song that comes round again after a whole other song has played is a playlist that
         * repeats, and the next song along would have ended the first long before this.
         */
        const val SPAN_MS = 150_000L

        /**
         * Further into the song than this, a return is not a restart. A window of a song that has
         * just been started again lands in its first few seconds; twenty leaves room for a listen
         * that began a little after the song did.
         */
        const val RESTART_S = 20.0

        /** How far back a song has to have been playing straight to count as what is playing. */
        const val HOST_SPAN_MS = 72_000L

        /**
         * How long a return waits for the steady song to go on. The original ran for three windows
         * inside the R3hab remix before the remix was named again.
         */
        const val DEFER_MS = 48_000L

        /** Past the wait by more than this, what was held is dropped rather than counted. */
        const val DEFER_GRACE_MS = 24_000L
    }
}

/**
 * Whether one window of a song carries on from an earlier one: as far into the song as the clock
 * has moved on, at the ordinary speed or at the speed Shazam read off the later window. Shazam's
 * offsets for a song played straight agree to a tenth of a second or two, and to about two when a
 * window catches the very start of a song; two and a half is room for that and nowhere near a cut,
 * which in the mashups heard so far moved by twenty or more. The share per second covers a speed
 * slightly off, over a long gap.
 */
internal object Timeline {
    private const val TOLERANCE_S = 2.5
    private const val TOLERANCE_PER_S = 0.01

    fun continues(earlierOffset: Double, earlierAtMs: Long, offset: Double, atMs: Long, skew: Double): Boolean {
        val apart = (atMs - earlierAtMs) / 1000.0
        val tolerance = TOLERANCE_S + apart * TOLERANCE_PER_S
        return kotlin.math.abs(offset - (earlierOffset + apart)) <= tolerance ||
                kotlin.math.abs(offset - (earlierOffset + apart * (1.0 + skew))) <= tolerance
    }

    fun continues(earlier: MixWatch.Sighting, later: MixWatch.Sighting): Boolean =
        continues(earlier.offsetSeconds, earlier.atMs, later.offsetSeconds, later.atMs, later.skew)
}

/**
 * Tells an edit, a remix or a mashup from the song itself, by checking where in the song each
 * window lands against where it should.
 *
 * Shazam says where in its recording every window matched. Played straight through, twelve seconds
 * of listening moves that on by twelve seconds (times the speed, which Shazam also reports as its
 * time skew), to within a fraction of a second. On 24 Sep the Damage mashup put Faint at -2 s, then
 * 31 s, 43 s, 12 s, 24 s, 36 s, 87 s, 60 s: its sections cut up and rearranged. The first two cuts
 * came within a minute of it starting, two and a half minutes before the first window of another
 * song gave it away, and a remix or an edit of one song never offers another song at all.
 *
 * A song Shazam places wrongly for a window or two, a repeated chorus matched to the first one,
 * comes back to its own timeline afterwards, and a return like that undoes the detour rather than
 * counting it. A radio edit that drops the intro and a bridge cuts twice, minutes apart. So what
 * counts is two cuts within a minute, or three at all.
 */
internal class CutWatch {

    /**
     * RESTART is a song gone back to its top partway through, on its own: somebody replaying it, or
     * an edit. Which, only shows later: replayed, it plays on to its end; in "I'm Beggin' For DNA"
     * DNA. stopped sixty seconds into its second time round. The engine keeps an eye on it.
     */
    enum class Verdict { NONE, FIRST, AGAIN, RESTART }

    /**
     * One place in the song, followed in order: where it last was, how many windows in a row have
     * been on it now, whether it has counted as a cut, and whether it began as a restart.
     */
    private class Place(var offset: Double, var atMs: Long, val restart: Boolean) {
        var inARow = 1
        var runStartedMs = atMs
        var counted = false
        var countedMs = 0L
    }

    private var key: String? = null
    private val places = mutableListOf<Place>()
    private var home: Place? = null
    private var current: Place? = null
    /** Windows in a row that each landed somewhere never heard before. */
    private var loose = 0
    private val cuts = mutableListOf<Place>()
    private var reported = false

    /**
     * One window of [key], matched at [offset] seconds with Shazam's time [skew], heard at [atMs],
     * of a song [durationS] long if that is known. FIRST the moment the song turns out to be cut up,
     * AGAIN on every cut after that, NONE otherwise. Another song resets it: consecutive windows of
     * one song are what it compares.
     *
     * Every place in the song that has been heard is kept. Home is the first one the song holds for
     * two windows in a row, not simply the first window, which in a repetitive song can be a wrong
     * repeat: Drake's Hotline Bling bounced between three places twenty and two hundred seconds
     * apart for the whole of a file that played straight. A cut is a new place held for two windows
     * in a row, counted once: going back to a place already heard is a repeat, which is what Shazam
     * does with a chorus that comes round, a dance track's sixteen bars, or a round like White
     * Winter Hymnal. A song chopped so hard that nothing holds, as Memories was in Memories Anthem,
     * is caught another way: four windows in a row that each land somewhere new.
     */
    fun observe(key: String, offset: Double, skew: Double, atMs: Long, durationS: Int? = null): Verdict {
        if (key != this.key) {
            clear()
            this.key = key
        }
        val previous = current
        val place = places.sortedByDescending { it.atMs }
            .firstOrNull { Timeline.continues(it.offset, it.atMs, offset, atMs, skew) }
            // A little behind where it should be is the same place after a stall or a pause: the
            // song carries on from where it stopped while the clock did not. An edit that cuts back
            // a few seconds looks the same, and is let go.
            ?: previous?.takeIf { stalled(it, offset, atMs) }
        if (place != null) {
            if (place === previous) place.inARow++ else { place.inARow = 1; place.runStartedMs = atMs }
            place.offset = offset
            place.atMs = atMs
            loose = 0
            current = place
        } else {
            // Back to the top partway through. The second mashup of 24 Sep, "I'm Beggin' For DNA",
            // was DNA. to Shazam from start to end, and gave itself away only by going back to
            // DNA.'s first seconds a hundred seconds into a 186 second song. Somebody replaying a
            // song does it at the end, not two thirds of the way in.
            val reached = previous?.let { it.offset + (atMs - it.atMs) / 1000.0 }
            val restart = offset < MixWatch.RESTART_S && durationS != null && reached != null &&
                    reached > MixWatch.RESTART_S + 10 && reached < durationS - 20
            current = Place(offset, atMs, restart).also { places += it }
            loose++
        }

        val held = current!!
        var restarted = false
        var justCounted = false
        if (held.inARow >= 2) {
            if (home == null && !held.restart) home = held
            else if (held !== home && !held.counted) {
                held.counted = true
                held.countedMs = held.runStartedMs
                justCounted = true
                cuts += held
                restarted = held.restart
            }
        }
        // A restart counts as a cut like any other once there is another cut; on its own it is
        // reported as what it is, and the engine waits to see whether the song plays through.
        // Three within a few minutes, not three over a whole song: Delirious goes back to its drop
        // and to a repeat of it every minute or so, two held stretches that are neither.
        val latest = cuts.lastOrNull()?.countedMs
        val cutUp = loose >= 4 || (latest != null && cuts.count { latest - it.countedMs <= TRIPLE_MS } >= 3) ||
                (cuts.size >= 2 && cuts[cuts.lastIndex].countedMs - cuts[cuts.lastIndex - 1].countedMs <= PAIR_MS)
        val fresh = justCounted || loose == 4
        return when {
            !cutUp -> if (restarted) Verdict.RESTART else Verdict.NONE
            !reported -> { reported = true; Verdict.FIRST }
            fresh -> Verdict.AGAIN
            else -> Verdict.NONE
        }
    }

    private fun stalled(place: Place, offset: Double, atMs: Long): Boolean {
        val behind = place.offset + (atMs - place.atMs) / 1000.0 - offset
        return behind > 0 && behind <= STALL_S
    }

    /** Forgets the song it follows if it is one of [keys]. */
    fun forget(keys: Set<String>) {
        if (key in keys) clear()
    }

    fun clear() {
        key = null
        places.clear()
        home = null
        current = null
        loose = 0
        cuts.clear()
        reported = false
    }

    companion object {
        /**
         * Two cuts closer together than this are an edit. A radio edit's two, the intro and a
         * bridge, are minutes apart.
         */
        private const val PAIR_MS = 90_000L

        /** How far behind the clock a song can resume and still be the same place, stalled. */
        private const val STALL_S = 15.0

        /** Three cuts within this long are an edit, wherever the pairs between them fall. */
        private const val TRIPLE_MS = 150_000L
    }
}

/**
 * Tells a version of a song that Shazam does not know from the song itself.
 *
 * Shazam matches an unknown remix against the versions it does know, and cannot settle on one:
 * the Averez remix of Lean On, replayed from a file on 24 Sep, came through as the ATAX remix, the
 * Robin Schulz edit twice, the original, and the Pbh and Jack Shizzle remix inside two minutes, each
 * a few percent off speed, with the Robin Schulz edit steady for two windows, which would have added
 * it. So three different versions of one song inside [SPAN_MS] means none of them is playing.
 *
 * Versions are counted by where they land, not by name. Shazam can also know one recording under
 * several entries, an album cut, a radio edit, a remaster, and flipping between those lands every
 * window on one timeline, since it is the same audio. Only matches that disagree on the timeline are
 * different versions.
 */
internal class VersionWatch(private val spanMs: Long = SPAN_MS) {

    private val seen = ArrayDeque<MixWatch.Sighting>()

    /** Songs found to be playing as a version Shazam does not know, and when one of their versions was last heard. */
    private val reported = HashMap<String, Long>()

    /**
     * The versions heard, when this window makes it three of one song, and on every window of that
     * song after, for as long as its versions keep coming: a fourth version, or one of the three
     * again after a quiet stretch, is the same remix and must not be added as itself. Null otherwise.
     */
    fun observe(sighting: MixWatch.Sighting): List<MixWatch.Sighting>? {
        seen.addLast(sighting)
        while (sighting.atMs - seen.first().atMs > spanMs) seen.removeFirst()
        val song = MixSearch.titleOf(sighting)
        if (song.isEmpty()) return null
        val versions = seen.filter { MixSearch.titleOf(it) == song }
        reported[song]?.let { last ->
            if (sighting.atMs - last <= spanMs) {
                reported[song] = sighting.atMs
                return versions.distinctBy { it.key }
            }
            reported.remove(song)
        }
        if (versions.distinctBy { it.key }.size < 3) return null
        // One timeline per version: a window joins the first timeline it carries on from.
        val timelines = mutableListOf<MixWatch.Sighting>()
        for (window in versions) {
            val on = timelines.indexOfFirst { Timeline.continues(it, window) }
            if (on >= 0) timelines[on] = window else timelines += window
        }
        if (timelines.size < 3) return null
        reported[song] = sighting.atMs
        return versions.distinctBy { it.key }
    }

    /**
     * Whether another version of [key]'s song was heard lately somewhere else on its timeline. Two
     * windows of [key] agreeing are then not enough to say it is the one playing: the Robin Schulz
     * edit of Lean On agreed with itself twice inside the Averez remix, a window after the ATAX
     * remix, and the original that would have made three versions came a window later.
     */
    fun rivalled(key: String): Boolean {
        val last = seen.lastOrNull { it.key == key } ?: return false
        val song = MixSearch.titleOf(last)
        if (song.isEmpty()) return false
        return seen.filter { it.key != key && MixSearch.titleOf(it) == song }
            .groupBy { it.key }.values.map { it.last() }
            .any { !Timeline.continues(it, last) }
    }

    /**
     * The key among [keys] that is the same recording as the latest window of [key]: the same
     * song, on the same timeline. Shazam knows some recordings under several entries and flips
     * between them, which is still the one song carrying on.
     */
    fun twinOf(key: String, keys: Set<String>): String? {
        val last = seen.lastOrNull { it.key == key } ?: return null
        val song = MixSearch.titleOf(last)
        if (song.isEmpty()) return null
        return seen.lastOrNull {
            it.key != key && it.key in keys && it.atMs < last.atMs &&
                    MixSearch.titleOf(it) == song && Timeline.continues(it, last)
        }?.key
    }

    fun clear() {
        seen.clear()
        reported.clear()
    }

    fun forget(keys: Set<String>) {
        seen.removeAll { it.key in keys }
    }

    companion object {
        const val SPAN_MS = 150_000L
    }
}

/**
 * Finding the mashup on YouTube from the pieces Shazam heard in it.
 *
 * Searched as videos, because that is where mashups live: under the song filter the same queries
 * return the pieces themselves. Checked against the live search on 24 Sep (MixSearchProbe in
 * innertube): the pieces' artists with "mashup" put the Damage upload first in France and the US,
 * and their titles with "mashup" put it, a lyrics re-upload of it, and a different mashup of the
 * same two songs in the top three. So a result has to name at least two of the pieces to count at
 * all, and when two different uploads score the same the answer is a choice for the person, not a
 * guess.
 */
internal object MixSearch {

    /** The queries to run, most specific first. Empty when there is not enough to search with. */
    fun queries(pieces: List<MixWatch.Sighting>): List<String> {
        // The two most heard of each. More only narrows the search onto nothing.
        val titles = pieces.map { bareTitle(it.title) }.filter { it.isNotBlank() }.distinct().take(2)
        val artists = pieces.mapNotNull { it.artist?.let(::primaryArtist) }.filter { it.isNotBlank() }.distinct().take(2)
        return listOfNotNull(
            titles.takeIf { it.size >= 2 }?.joinToString(" ", postfix = " mashup"),
            artists.takeIf { it.size >= 2 }?.joinToString(" ", postfix = " mashup"),
        )
    }

    /** What came back, scored and ranked, best first, each once. Only results naming two pieces. */
    fun rank(pieces: List<MixWatch.Sighting>, results: List<List<SongItem>>): List<Pair<SongItem, Int>> {
        val scored = mutableMapOf<String, Pair<SongItem, Int>>()
        val hits = mutableMapOf<String, Int>()
        for (list in results) for (item in list) {
            val score = score(pieces, item) ?: continue
            hits[item.id] = (hits[item.id] ?: 0) + 1
            val best = scored[item.id]
            if (best == null || best.second < score) scored[item.id] = item to score
        }
        // Found by both queries is worth a point: the two ask different questions of the same songs.
        return scored.values
            .map { (item, score) -> item to score + if ((hits[item.id] ?: 0) > 1) 1 else 0 }
            .sortedByDescending { it.second }
    }

    /**
     * The mashup to take without asking, or null when there is none or it is a toss-up. Clear means
     * well ahead of the next one: a tie between two uploads is exactly the Damage case, where one
     * of them was a different mashup of the same songs.
     */
    fun clearWinner(ranked: List<Pair<SongItem, Int>>): SongItem? {
        val (top, score) = ranked.firstOrNull() ?: return null
        val next = ranked.getOrNull(1)?.second ?: 0
        return top.takeIf { score >= CLEAR_SCORE && score - next >= CLEAR_LEAD }
    }

    /**
     * For one song that turned out to be cut up: remixes and mashups that name it, in YouTube's own
     * order, which for "Faint Linkin Park mashup" put the Damage upload first. Never taken without
     * asking, since one song has many of them.
     */
    fun singleQueries(piece: MixWatch.Sighting): List<String> {
        val base = listOfNotNull(bareTitle(piece.title), piece.artist?.let(::primaryArtist))
            .filter { it.isNotBlank() }.joinToString(" ")
        return if (base.isBlank()) emptyList() else listOf("$base mashup", "$base remix")
    }

    fun rankSingle(piece: MixWatch.Sighting, results: List<List<SongItem>>): List<SongItem> {
        val title = words(bareTitle(piece.title))
        val artist = piece.artist?.let { words(primaryArtist(it)) }.orEmpty()
        return results.flatten().distinctBy { it.id }.filter { item ->
            val text = " " + words(item.title + " " + item.artists.joinToString(" ") { it.name }) + " "
            val named = (title.length >= 3 && " $title " in text) || (artist.length >= 3 && " $artist " in text)
            named && MIX_WORDS.containsMatchIn(item.title)
        }
    }

    /**
     * Whether [item] could be what has been playing for [heardS] seconds: not an hour-long
     * compilation, which a search for two artists and "mashup" turns up plenty of, and not shorter
     * than what has already been heard of it.
     */
    fun couldBe(item: SongItem, heardS: Double): Boolean {
        val length = item.duration ?: return true
        return length <= MAX_LENGTH_S && length >= heardS - 10
    }

    /**
     * [candidates] reordered by how well their length fits a mashup heard for [heardS] seconds from
     * its first recognised window to its last. What plays before the first window, an intro Shazam
     * does not know, is unknown, so the real length is guessed a little longer, and anything more
     * than a minute longer only follows the ones that fit. On 24 Sep this put the uploads that were
     * actually playing first: Memories Anthem (207 s, heard for 192) over mashups of the same two
     * songs at 140, 342, 350 and 515, and I'm Beggin' For DNA (199 s) over three others.
     */
    fun byLength(candidates: List<SongItem>, heardS: Double): List<SongItem> {
        val target = heardS + LEAD_GUESS_S
        val (fit, rest) = candidates.filter { couldBe(it, heardS) }
            .partition { it.duration != null && it.duration!! <= heardS + MAX_LEAD_S }
        return fit.sortedBy { kotlin.math.abs(it.duration!! - target) } + rest
    }

    /**
     * The pieces as songs rather than as Shazam keys. Shazam can give one recording two keys, and
     * two keys for one song flipping back and forth is not a mashup of it with itself.
     */
    fun distinctSongs(pieces: List<MixWatch.Sighting>): List<MixWatch.Sighting> =
        pieces.distinctBy { words(bareTitle(it.title)) to it.artist?.let { a -> words(primaryArtist(a)) } }

    /** A piece's bare title as words: what its versions have in common. */
    fun titleOf(piece: MixWatch.Sighting): String = words(bareTitle(piece.title))

    /** Whether two titles name the same song, whatever edit or entry each is. */
    fun sameSong(a: String, b: String): Boolean = words(bareTitle(a)).let { it.isNotEmpty() && it == words(bareTitle(b)) }

    /** Whether [item] names [piece], by its title or its artist. */
    fun names(piece: MixWatch.Sighting, item: SongItem): Boolean {
        val text = " " + words(item.title + " " + item.artists.joinToString(" ") { it.name }) + " "
        val title = words(bareTitle(piece.title))
        val artist = piece.artist?.let { words(primaryArtist(it)) }.orEmpty()
        return (title.length >= 3 && " $title " in text) || (artist.length >= 3 && " $artist " in text)
    }

    /** Two points a title named, one an artist, one for a word saying it is a mix. Null under two pieces. */
    internal fun score(pieces: List<MixWatch.Sighting>, item: SongItem): Int? {
        val text = " " + words(item.title + " " + item.artists.joinToString(" ") { it.name }) + " "
        var named = 0
        var score = 0
        for (piece in distinctSongs(pieces)) {
            val title = words(bareTitle(piece.title))
            val artist = piece.artist?.let { words(primaryArtist(it)) }.orEmpty()
            val titleHit = title.length >= 3 && " $title " in text
            val artistHit = artist.length >= 3 && " $artist " in text
            if (titleHit || artistHit) named++
            if (titleHit) score += 2
            if (artistHit) score += 1
        }
        if (named < 2) return null
        if (MIX_WORDS.containsMatchIn(item.title)) score += 1
        return score
    }

    /** "No Love (feat. Lil Wayne)" is "No Love", and so on. */
    internal fun bareTitle(title: String): String = title
        .replace(Regex("\\([^)]*\\)|\\[[^]]*]"), " ")
        .replace(Regex("\\s+-\\s+.*$"), " ")
        .replace(Regex("\\s+"), " ")
        .trim()

    /** The first name in a credit: "Eminem feat. Lil Wayne" is Eminem, "A & B" is A. */
    internal fun primaryArtist(artist: String): String =
        artist.split(Regex("\\s*(,|&| feat\\.? | ft\\.? | featuring | x | with )\\s*", RegexOption.IGNORE_CASE))
            .first()
            .trim()

    private fun words(text: String): String = text
        .lowercase()
        .replace(Regex("['’]"), "")
        .replace(Regex("[^\\p{L}\\p{N} ]"), " ")
        .replace(Regex("\\s+"), " ")
        .trim()

    private val MIX_WORDS = Regex("mash ?up|medley|megamix|\\bmix\\b|remix|\\bvs\\.?\\b", RegexOption.IGNORE_CASE)

    /** Two titles and a mix word, or two titles and both artists. */
    private const val CLEAR_SCORE = 5
    private const val CLEAR_LEAD = 2

    /** Longer than this is a compilation or a DJ set, not a mashup. */
    private const val MAX_LENGTH_S = 600
    private const val LEAD_GUESS_S = 10
    private const val MAX_LEAD_S = 60
}
