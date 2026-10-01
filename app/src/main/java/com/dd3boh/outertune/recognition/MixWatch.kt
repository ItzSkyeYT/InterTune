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
 * and the pieces come out of the list whether or not the mashup is found. A song that lands at one
 * place in itself every time it is named earns nothing, however often it comes: that is one passage
 * of the song playing, which Shazam names the same wrong way each time it comes round.
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
        // Landing at one place in itself every time, however far the clock has moved on, this song
        // is not playing: it is one passage of what is, which Shazam names the same wrong way each
        // time it comes round. See [inOnePlace].
        if (inOnePlace(sighting.key)) return null

        val first = list.indexOfFirst { it.key == sighting.key }
        val allOfSpan = list.subList(first, list.size)
        // Only songs heard more than once count as pieces. A mashup comes back to its pieces: No
        // Love twice in Damage, Memories three times in Memories Anthem. Songs Shazam names for one
        // window and never again are noise; 2 Faced Funks' Powerbass had five of them in a row in
        // its breakdown, each a different track, and read as a mashup of all five.
        val heard = allOfSpan.groupingBy { it.key }.eachCount()
        val recurring = heard.filter { (key, times) -> key != sighting.key && times >= 2 && !inOnePlace(key) }.keys
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

    /**
     * Whether two or more windows of [key] are in view and every one of them landed within
     * [ONE_PLACE_S] of one place in it. A piece of a mashup moves along as the mashup plays: in
     * Damage No Love was 181.8 s in at 132 s and 231.9 s in at 180 s. A passage Shazam misnames
     * lands wherever the song it names has that passage, every time. Krept & Konan's Freak of the
     * Week (Radio Edit), replayed from a file on 25 Sep, was Jamie xx's I Know There's Gonna Be at
     * 72 s and again at 132 s, 185.6 and 185.0 s into it, and the return at 144 s was sure: the
     * other song twice, each time between windows of this one. In Take Me Alive, Reload came at
     * 195.7 and 195.3 s, and the return at 276 s was strong.
     *
     * Not whether the song around it carries on. A mashup can lay one song over another that keeps
     * playing, and a piece that only ever comes one window at a time, as No Love did in Damage, would
     * then never count. A mashup that loops one passage of a song does land at one place, and is
     * missed.
     */
    private fun inOnePlace(key: String): Boolean {
        val offsets = seen.filter { it.key == key }.map { it.offsetSeconds }
        return offsets.size >= 2 && offsets.max() - offsets.min() <= ONE_PLACE_S
    }

    /** When [key] was last heard, if it is still in view. */
    fun lastHeard(key: String): Long? = seen.lastOrNull { it.key == key }?.atMs

    /**
     * Every song still in view that was heard from [sinceMs] to [untilMs], each once, as last heard
     * in that time. Bounded at both ends, since what is in view can run on past what is asked
     * about: a song that went back to its top is only settled as an edit once the next song has
     * played for two windows, and that song was not heard with it.
     */
    fun heardBetween(sinceMs: Long, untilMs: Long): List<Sighting> =
        seen.filter { it.atMs in sinceMs..untilMs }.asReversed().distinctBy { it.key }.asReversed()

    /**
     * How fast [key] has been playing, 1 being as Shazam knows it: the middle of the speeds Shazam
     * read off its windows in view, or null when none are. The middle, since one window can be well
     * off: DNA., played straight on 24 Sep, read 3 % slow twice in fifteen windows.
     */
    fun speedOf(key: String): Double? {
        val skews = seen.filter { it.key == key }.map { it.skew }.sorted()
        if (skews.isEmpty()) return null
        val mid = skews.size / 2
        return 1.0 + if (skews.size % 2 == 1) skews[mid] else (skews[mid - 1] + skews[mid]) / 2
    }

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

        /**
         * How far apart a song's windows can land and still be one place in it. Shazam put the same
         * passage 0.6 s apart in Freak of the Week and 0.4 s apart in Take Me Alive; a song playing
         * on moves twelve seconds a window.
         */
        private const val ONE_PLACE_S = 3.0
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
     * been on it now, whether the song has ever held it for two, whether it has counted as a cut,
     * whether it began as a restart, and where the one window that restart was reckoned from put
     * the song, if the song had not held anywhere before it.
     */
    private class Place(var offset: Double, var atMs: Long, val restart: Boolean, val loneFrom: Double? = null) {
        var inARow = 1
        var runStartedMs = atMs
        var hasHeld = false
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
    /** Where the last window the stall rule let through landed, and when. */
    private var stall: Pair<Double, Long>? = null

    /**
     * Where the window before the last RESTART put the song, when that one window is all the
     * restart was reckoned from, and null when the song had held somewhere first. See [observe].
     */
    var restartedFrom: Double? = null
        private set

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
        // Where in the song itself this window is. Shazam gives the offset in its reference
        // recording, which runs at 1 + skew to the room, and it can match one song against two
        // references at different speeds: Kiesza's Hideaway came through 1.5 % fast against one and
        // at speed against the other, which two minutes in is three seconds apart, beyond the
        // tolerance, and going back to the song's own timeline counted as a cut.
        val at = offset / (1.0 + skew)
        val place = places.sortedByDescending { it.atMs }
            .firstOrNull { Timeline.continues(it.offset, it.atMs, at, atMs, 0.0) }
        // A little behind where it should be is the same place after a stall or a pause: the song
        // carries on from where it stopped while the clock did not. An edit that cuts back a few
        // seconds looks the same, and is let go. It is also what a dance track's repeated phrase
        // matched one phrase early looks like, so it neither moves the place's line nor counts
        // as holding it: moved, Hideaway's line went eight seconds astray and its own timeline
        // then looked like somewhere new.
        // After a real pause the song carries on from where the stalled window landed: the second
        // window to agree on that line moves the place there, as the first alone must not.
        val resumed = if (place == null) previous?.takeIf { stall?.let { (o, t) -> Timeline.continues(o, t, at, atMs, 0.0) } == true } else null
        val stalledOn = if (place == null && resumed == null) previous?.takeIf { stalled(it, at, atMs) } else null
        stall = if (stalledOn != null) at to atMs else null
        if (resumed != null) {
            resumed.inARow++
            resumed.offset = at
            resumed.atMs = atMs
            loose = 0
            current = resumed
        } else if (place != null) {
            if (place === previous) place.inARow++ else { place.inARow = 1; place.runStartedMs = atMs }
            place.offset = at
            place.atMs = atMs
            loose = 0
            current = place
        } else if (stalledOn != null) {
            loose = 0
            current = stalledOn
        } else {
            // Back to the top partway through. The second mashup of 24 Sep, "I'm Beggin' For DNA",
            // was DNA. to Shazam from start to end, and gave itself away only by going back to
            // DNA.'s first seconds a hundred seconds into a 186 second song. Somebody replaying a
            // song does it at the end, not two thirds of the way in.
            // How far it got is reckoned from the last place it held for two windows in a row, not
            // from the window before, which can be a wrong repeat: the radio edit of Duke Dumont's
            // Won't Look Back, replayed on 25 Sep, had its first window placed at 30 s, and its
            // real start twelve seconds later read as going back to the top. Mr. Probz's Waves did
            // the same from 51 s. A song that has held nowhere yet has only that one window to go
            // by, and Keep listening can catch a single window of DNA. before the jump, so that
            // still counts, but whether the window was right shows only later: Won't Look Back
            // played on past 30 s, while DNA. stopped at 62 s, short of the 99.8 s its one window
            // said. So the restart is reported with that window's place for the engine to settle
            // (see RecognitionEngine.stoppedShort), and meanwhile the song's top is its home, not
            // a cut: there was nowhere held to cut from.
            val lastHeld = places.filter { it.hasHeld }.maxByOrNull { it.atMs }
            val from = lastHeld ?: previous
            val reached = from?.let { it.offset + (atMs - it.atMs) / 1000.0 }
            val restart = at < MixWatch.RESTART_S && durationS != null && reached != null &&
                    reached > MixWatch.RESTART_S + 10 && reached < durationS - 20
            val loneFrom = if (restart && lastHeld == null) from?.offset else null
            current = Place(at, atMs, restart, loneFrom).also { places += it }
            loose++
        }

        val held = current!!
        var restarted = false
        var justCounted = false
        if (held.inARow >= 2) {
            held.hasHeld = true
            if (home == null && (!held.restart || held.loneFrom != null)) {
                home = held
                restarted = held.restart
            } else if (held !== home && !held.counted) {
                held.counted = true
                held.countedMs = held.runStartedMs
                justCounted = true
                cuts += held
                restarted = held.restart
            }
            if (restarted) restartedFrom = held.loneFrom
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
        stall = null
        restartedFrom = null
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
        // The report stays for its span: a remix's breakdown, two windows Shazam knows nothing of,
        // ends the mashup but not the remix, and one version steady after it is still the remix.
        // What ends a report is the song playing straight through as itself; see [release].
    }

    /** Whether the last [windows] windows heard were all [key], each where the one before says it should be. */
    fun playsStraight(key: String, windows: Int = STRAIGHT_WINDOWS): Boolean {
        val last = seen.takeLast(windows)
        return last.size == windows && last.all { it.key == key } && last.zipWithNext().all { (a, b) -> Timeline.continues(a, b) }
    }

    /** [sighting]'s song is playing as itself after all: no longer a version Shazam does not know. */
    fun release(sighting: MixWatch.Sighting) {
        val song = MixSearch.titleOf(sighting)
        reported.remove(song)
        seen.removeAll { MixSearch.titleOf(it) == song && it.key != sighting.key }
    }

    companion object {
        const val SPAN_MS = 150_000L
        /** About a minute at the usual twelve second windows. */
        const val STRAIGHT_WINDOWS = 5
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

    /**
     * The queries to run, most specific first. Empty when there is not enough to search with. At a
     * [speed] clearly off, or with [pieces] Shazam knows as sped up or slowed, the titles once more
     * with what uploads that fast or slow call themselves: see [speedWords].
     */
    fun queries(pieces: List<MixWatch.Sighting>, speed: Double): List<String> {
        // The two most heard of each. More only narrows the search onto nothing.
        val titles = titled(pieces).map { bareTitle(it.title) }
        val artists = pieces.mapNotNull { it.artist?.let(::primaryArtist) }.filter { it.isNotBlank() }.distinct().take(2)
        if (titles.size < 2) return listOfNotNull(artists.takeIf { it.size >= 2 }?.joinToString(" ", postfix = " mashup"))
        val both = titles.joinToString(" ")
        return listOfNotNull(
            "$both mashup",
            artists.takeIf { it.size >= 2 }?.joinToString(" ", postfix = " mashup"),
        ) + speedWords(speed, pieces).map { "$both $it" }
    }

    /** The first two of [pieces] with a title, by title once each: the titles [queries] searches with. */
    private fun titled(pieces: List<MixWatch.Sighting>): List<MixWatch.Sighting> =
        pieces.filter { bareTitle(it.title).isNotBlank() }.distinctBy { bareTitle(it.title) }.take(2)

    /**
     * The songs among [pieces] that [queries] looks for, and so the ones noted as searched for once
     * the search goes through ([noted]): those whose titles go into the queries, and those with no
     * title to search with, which searching again cannot look for any better. With fewer than two
     * titles the search goes by the artists alone, and that is every song.
     *
     * Not every song heard: the queries take two titles, so a third song was never looked for. Noted
     * all the same, Numb heard with Faint and No Love counted as searched for, though the queries
     * were "Faint No Love mashup" and "Linkin Park Eminem mashup".
     */
    fun queried(pieces: List<MixWatch.Sighting>): List<MixWatch.Sighting> {
        val titled = titled(pieces)
        if (titled.size < 2) return pieces
        return pieces.filter { it in titled || bareTitle(it.title).isBlank() }
    }

    /**
     * [songs] in the order to search for them, most heard first, as [queries] takes them, except
     * that the songs an answer does not cover ([uncovered]) come first. The queries take only two
     * titles, so a third song coming back after an answer was otherwise never in them, and the
     * search meant to find it with the others looked for the two the answer already covered.
     */
    fun searchOrder(songs: List<MixWatch.Sighting>, uncovered: List<MixWatch.Sighting>): List<MixWatch.Sighting> {
        val first = uncovered.map(::songId).toSet()
        val (new, rest) = songs.partition { songId(it) in first }
        return new + rest
    }

    /**
     * How fast the room plays a mashup whose songs Shazam heard at [speeds], 1 being as recorded.
     * A mashup fits its songs to one tempo, so one of them running a few percent off says nothing
     * about the upload: in the Faint x No Love mashup on his S25U (29 Sep) No Love ran 4.2 % fast,
     * with its pitch where it was, and Faint at speed. Only when every song is off the same way, by
     * more than [PlaybackVariant] takes for jitter, is the whole of it sped up or slowed down, and
     * then by as much as the least off of them, which is the one the others were fitted to.
     *
     * Held between half and twice the speed, which is more than any upload plays at: lengths are
     * divided by it.
     */
    fun roomSpeed(speeds: List<Double>): Double = when {
        speeds.isEmpty() -> 1.0
        speeds.all { it >= 1 + PlaybackVariant.TOLERANCE } -> speeds.min()
        speeds.all { it <= 1 - PlaybackVariant.TOLERANCE } -> speeds.max()
        else -> 1.0
    }.coerceIn(MIN_ROOM_SPEED, MAX_ROOM_SPEED)

    /**
     * What uploads playing at [speed] call themselves, for searching: none at speed, or off by no
     * more than a DJ plays a track ([SPED_UP_SPEED], [SLOWED_SPEED]). Past a fifth faster,
     * nightcore as well as sped up, which is how uploads that fast are as often named.
     */
    fun speedWords(speed: Double, heard: List<MixWatch.Sighting> = emptyList()): List<String> = wordSpeed(speed, heard).let { at ->
        when {
            at >= NIGHTCORE_SPEED -> listOf("sped up", "nightcore")
            at >= SPED_UP_SPEED -> listOf("sped up")
            at <= SLOWED_SPEED -> listOf("slowed")
            else -> emptyList()
        }
    }

    /**
     * The speed that words in titles are judged against: [speed], unless the room plays at speed
     * and every song [heard] is itself a sped-up or slowed recording by the title Shazam knows it
     * under. Shazam knows official sped-up releases, "Faint (Sped Up)", and matches them at their own
     * speed, so a room playing one reads as at speed, and it is uploads saying they are sped up that
     * fit, not those at the original's speed. Lengths still go by [speed], which is what Shazam
     * measured against those recordings.
     */
    private fun wordSpeed(speed: Double, heard: List<MixWatch.Sighting>): Double = when {
        heard.isEmpty() || kotlin.math.abs(speed - 1) >= PlaybackVariant.TOLERANCE -> speed
        heard.all { SPED.containsMatchIn(it.title) } -> SPED_UP_SPEED
        heard.all { SLOWED.containsMatchIn(it.title) } -> SLOWED_SPEED
        else -> speed
    }

    /**
     * Whether [item]'s title agrees with [speed]: 1 when it says it is sped up and the room plays
     * well fast, or slowed and the room plays well slow; -1 when it says the opposite, or says
     * either while the room plays at speed; 0 when it says neither while the room is off, since an
     * upload at its own speed can still be played faster or slower. The words are the ones
     * [pickBest] goes by.
     *
     * Well fast is past [SPED_UP_SPEED]. A room only a few percent off is playing a DJ edit or a
     * track pitched to the next, which a sped-up upload, running a fifth to a third fast, is not
     * either: its words then count neither for it nor against it.
     *
     * With the songs [heard], a room at speed playing recordings Shazam knows as sped up or slowed
     * counts as playing that fast or slow: see [wordSpeed].
     */
    fun speedFit(item: SongItem, speed: Double, heard: List<MixWatch.Sighting> = emptyList()): Int {
        val sped = SPED.containsMatchIn(item.title)
        val slowed = SLOWED.containsMatchIn(item.title)
        val at = wordSpeed(speed, heard)
        return when {
            at >= SPED_UP_SPEED -> if (sped) 1 else if (slowed) -1 else 0
            at <= SLOWED_SPEED -> if (slowed) 1 else if (sped) -1 else 0
            at >= 1 + PlaybackVariant.TOLERANCE -> if (slowed) -1 else 0
            at <= 1 - PlaybackVariant.TOLERANCE -> if (sped) -1 else 0
            else -> if (sped || slowed) -1 else 0
        }
    }

    /**
     * How long [item] lasts in the room, which plays at [speed]: its own length when its title says
     * it is sped up or slowed that way already, and otherwise its length played at that speed. A
     * 200 s mashup played a quarter faster is over in 160 s.
     */
    fun roomLength(item: SongItem, speed: Double): Double? =
        item.duration?.let { if (speedFit(item, speed) > 0) it.toDouble() else it / speed }

    /**
     * The longest [item] can last in the room at [speed]: its [roomLength], or its own length when
     * that is longer and its title says nothing about how fast it runs. A room playing a quarter
     * fast is likelier playing a sped-up upload than one sped up on the spot, and not every sped-up
     * upload says so in its title. So whether it could still be playing, and when a mashup taken
     * has to be over, go by the longer; which fits the time heard best goes by [roomLength].
     */
    fun longestInRoom(item: SongItem, speed: Double): Double? =
        roomLength(item, speed)?.let { room -> if (speedFit(item, speed) == 0) maxOf(room, item.duration!!.toDouble()) else room }

    /**
     * What came back, scored and ranked, best first, each once. Only results naming two pieces.
     * Those naming more of the songs heard come first, whatever their score: see [songsNamed].
     * Then those whose titles agree with how fast the room plays ([speedFit]): a room playing a
     * quarter fast is far likelier to be playing a sped-up upload than one played faster, however
     * many points the other scores, and at speed a sped-up or slowed upload is not what plays.
     * Then those naming more of the songs by title: "Faint x No Love" names both songs, and
     * "Linkin Park & Eminem - Faint" one song and two artists, however many points the credits
     * add. The score counts a point more for agreeing with the speed and one less for not; with the
     * [pieces] as Shazam knows them, a mashup of sped-up releases is sped up too.
     */
    fun rank(pieces: List<MixWatch.Sighting>, results: List<List<SongItem>>, speed: Double): List<Pair<SongItem, Int>> {
        val scored = mutableMapOf<String, Pair<SongItem, Int>>()
        val hits = mutableMapOf<String, Int>()
        for (list in results) for (item in list) {
            val score = (score(pieces, item) ?: continue) + speedFit(item, speed, pieces)
            hits[item.id] = (hits[item.id] ?: 0) + 1
            val best = scored[item.id]
            if (best == null || best.second < score) scored[item.id] = item to score
        }
        // Found by both queries is worth a point: the two ask different questions of the same songs.
        return scored.values
            .map { (item, score) -> item to score + if ((hits[item.id] ?: 0) > 1) 1 else 0 }
            .sortedWith(
                compareByDescending<Pair<SongItem, Int>> { songsNamed(pieces, it.first) }
                    .thenByDescending { speedFit(it.first, speed, pieces) }
                    .thenByDescending { titlesNamed(pieces, it.first) }
                    .thenByDescending { it.second }
            )
    }

    /**
     * The mashup to take without asking, or null when there is none or it is a toss-up. Clear means
     * well ahead of every other one: a tie between two uploads is exactly the Damage case, where one
     * of them was a different mashup of the same songs. And naming two of the songs [heard], as
     * [songsNamed] counts them: two songs by one artist are not both named by that artist's name.
     *
     * Ahead of every other, not only of the next: [rank] puts an upload naming more of the songs
     * first, and one naming fewer can still score more, from both artists and a mix word. That one
     * is no winner, and neither is the one above it while it scores as much.
     */
    fun clearWinner(ranked: List<Pair<SongItem, Int>>, heard: List<MixWatch.Sighting>): SongItem? {
        val (top, score) = ranked.firstOrNull() ?: return null
        val rival = ranked.drop(1).maxOfOrNull { it.second } ?: 0
        return top.takeIf { score >= CLEAR_SCORE && score - rival >= CLEAR_LEAD && songsNamed(heard, it) >= 2 }
    }

    /**
     * Where [ranked]'s first upload stands, for the log: how many of the songs [heard] it names and
     * how many by title, its score, how far ahead of the best of the rest, and the room's [speed]
     * when that is off. The runs of 24 and 29 Sep could only be read back from the scores of the
     * first three, which never said why the first was first or why it was not taken.
     */
    fun standing(ranked: List<Pair<SongItem, Int>>, heard: List<MixWatch.Sighting>, speed: Double): String {
        val (top, score) = ranked.firstOrNull() ?: return "nothing names two of the songs"
        val rival = ranked.drop(1).maxByOrNull { it.second }
        val against = rival?.let { (item, s) -> if (score > s) "${score - s} ahead of '${item.title}'" else "'${item.title}' scores $s" }
        return listOfNotNull(
            "${shown(top)} names ${songsNamed(heard, top)} of ${distinctSongs(heard).size} songs, ${titlesNamed(heard, top)} by title",
            "score $score",
            against ?: "the only one",
            speedNote(speed),
        ).joinToString(", ")
    }

    /** Why [ranked]'s first upload is not [clearWinner], or null when it is. */
    fun notClear(ranked: List<Pair<SongItem, Int>>, heard: List<MixWatch.Sighting>): String? {
        val (top, score) = ranked.firstOrNull() ?: return "nothing names two of the songs"
        val rival = ranked.drop(1).maxByOrNull { it.second }
        return when {
            songsNamed(heard, top) < 2 -> "it names only ${songsNamed(heard, top)} of the songs"
            score < CLEAR_SCORE -> "a score of $score, under $CLEAR_SCORE"
            rival != null && score - rival.second < CLEAR_LEAD ->
                "'${rival.first.title}' scores ${rival.second}, " + if (rival.second >= score) "as much or more" else "too close"
            else -> null
        }
    }

    /**
     * Why the one-song choice [offered] puts its first upload first, for the log: it names more of
     * the songs [heard], or says it runs at the room's [speed], or is a remix while Shazam kept
     * naming versions ([remixFirst]), or none of these and it is YouTube's order.
     */
    fun firstBecause(offered: List<SongItem>, heard: List<MixWatch.Sighting>, speed: Double, remixFirst: Boolean): String {
        val first = offered.firstOrNull() ?: return "nothing to offer"
        val next = offered.getOrNull(1)
        val songs = distinctSongs(heard).size
        val named = songsNamed(heard, first)
        val because = when {
            next == null -> "the only one"
            named > songsNamed(heard, next) -> "it names $named of the $songs songs heard, the next ${songsNamed(heard, next)}"
            speedFit(first, speed, heard) > speedFit(next, speed, heard) -> "its title says it runs as fast as the room"
            remixFirst && !namesSeveral(first) && namesSeveral(next) -> "a remix before mashups, Shazam having named several versions"
            songs > 1 -> "YouTube's order, none naming more of the $songs songs heard"
            else -> "YouTube's order"
        }
        return listOfNotNull(shown(first), because, speedNote(speed)).joinToString(", ")
    }

    /** An upload as the log names it: its title, and its length when known. */
    private fun shown(item: SongItem): String = "'${item.title}'" + item.duration?.let { " ${it}s" }.orEmpty()

    private fun speedNote(speed: Double): String? = if (speed == 1.0) null else "the room at x${"%.2f".format(java.util.Locale.ROOT, speed)}"

    /**
     * How many of the songs [heard] [item] names: by the song's title, or by its artist when no
     * other song heard is by the same artist. An upload named after one of them cannot then come
     * before one that names them all. On his S25U on 29 Sep a Faint x No Love mashup was offered
     * uploads of Faint alone, in YouTube's order, since the search had only Faint to go on; No Love
     * came a minute later. And an upload of Numb credited to Linkin Park names Numb, not a mashup of
     * Numb with Faint.
     */
    fun songsNamed(heard: List<MixWatch.Sighting>, item: SongItem): Int = named(heard, item, byArtist = true)

    /** How many of the songs [heard] [item] names by their own titles, which says more than an artist does. */
    fun titlesNamed(heard: List<MixWatch.Sighting>, item: SongItem): Int = named(heard, item, byArtist = false)

    private fun named(heard: List<MixWatch.Sighting>, item: SongItem, byArtist: Boolean): Int {
        val songs = distinctSongs(heard)
        val text = textOf(item)
        return songs.count { namedIn(songs, it, text, byArtist) }
    }

    /**
     * Whether [item] names [song], one of the songs [heard]: by its title, or by its artist when no
     * other song heard is by the same artist, as [songsNamed] counts them. An upload of Faint
     * credited to Linkin Park does not name Numb as well.
     */
    fun namesSong(heard: List<MixWatch.Sighting>, song: MixWatch.Sighting, item: SongItem): Boolean =
        namedIn(distinctSongs(listOf(song) + heard), song, textOf(item), byArtist = true)

    private fun namedIn(songs: List<MixWatch.Sighting>, song: MixWatch.Sighting, text: String, byArtist: Boolean): Boolean {
        val title = titleOf(song)
        val artist = artistOf(song)
        return (title.length >= 3 && " $title " in text) ||
                (byArtist && artist.length >= 3 && songs.count { artistOf(it) == artist } == 1 && " $artist " in text)
    }

    private fun artistOf(song: MixWatch.Sighting): String = song.artist?.let { words(primaryArtist(it)) }.orEmpty()

    /**
     * [candidates] with those naming more of the songs [heard] first, and otherwise as they were:
     * see [songsNamed]. Then those whose titles agree with the room's [speed], or with the songs
     * [heard] when Shazam knows them as sped up or slowed ([speedFit]), and
     * with [remixFirst], remixes and edits before uploads of several songs, as [rankSingle] explains.
     */
    fun byNamed(candidates: List<SongItem>, heard: List<MixWatch.Sighting>, remixFirst: Boolean, speed: Double): List<SongItem> =
        candidates.sortedWith(
            compareByDescending<SongItem> { songsNamed(heard, it) }
                .thenByDescending { speedFit(it, speed, heard) }
                .thenBy { remixFirst && namesSeveral(it) }
        )

    /**
     * For one song that turned out to be cut up: remixes and mashups that name it, in YouTube's own
     * order, which for "Faint Linkin Park mashup" put the Damage upload first. Never taken without
     * asking, since one song has many of them. At a [speed] clearly off, or for a [piece] Shazam
     * knows as sped up or slowed, the song once more with what uploads that fast or slow call
     * themselves: see [speedWords].
     */
    fun singleQueries(piece: MixWatch.Sighting, speed: Double): List<String> {
        val base = listOfNotNull(bareTitle(piece.title), piece.artist?.let(::primaryArtist))
            .filter { it.isNotBlank() }.joinToString(" ")
        return if (base.isBlank()) emptyList() else listOf("$base mashup", "$base remix") + speedWords(speed, listOf(piece)).map { "$base $it" }
    }

    /**
     * The uploads that could be the one song heard, cut up: named after it, and a remix, an edit or
     * a mashup. With [remixFirst], when Shazam kept naming several versions of the song, remixes
     * and edits come before uploads of several songs: on the emulator on 25 Sep the Averez remix
     * of Lean On was offered three mashups of Lean On with Lush Life, I Took A Pill In Ibiza and
     * Sorry, none of them heard. Not when the song was cut up or went back to its top, which is as
     * often a mashup whose other half Shazam never names: I'm Beggin' For DNA was only ever DNA.
     *
     * Before either, the uploads that also name other songs [heard] meanwhile, as they name more of
     * them: see [songsNamed]. A song heard along with this one says which mashup of it this is.
     * Then those that say they are as fast or as slow as the room's [speed]: an upload of the song
     * sped up is a version of it too, and taken as one when the room plays that fast. Only as
     * Shazam measured it, not as the title it knows the song under says: when that is the song's
     * own sped-up release, matched at speed, the release is the recording found cut up, not what
     * plays, though sped-up mashups of it still come first ([speedFit]).
     */
    fun rankSingle(
        piece: MixWatch.Sighting,
        results: List<List<SongItem>>,
        remixFirst: Boolean,
        heard: List<MixWatch.Sighting>,
        speed: Double,
    ): List<SongItem> {
        val found = results.flatten().distinctBy { it.id }.filter { item ->
            names(piece, item) && (MIX_WORDS.containsMatchIn(item.title) || speedFit(item, speed) > 0)
        }
        return byNamed(found, listOf(piece) + heard, remixFirst, speed)
    }

    /** Whether an upload is of several songs, a mashup or a medley, rather than a remix or edit of one. */
    fun namesSeveral(item: SongItem): Boolean = SEVERAL.containsMatchIn(item.title)

    /**
     * Whether [item] could be what has been playing for [heardS] seconds at [speed]: not an
     * hour-long compilation, which a search for two artists and "mashup" turns up plenty of, and
     * not over sooner than what has already been heard of it, however long it lasts in the room:
     * see [longestInRoom].
     */
    fun couldBe(item: SongItem, heardS: Double, speed: Double): Boolean {
        val length = item.duration ?: return true
        return length <= MAX_LENGTH_S && longestInRoom(item, speed)!! >= heardS - 10
    }

    /**
     * [candidates] reordered by how well their length fits a mashup heard for [heardS] seconds from
     * its first recognised window to its last. What plays before the first window, an intro Shazam
     * does not know, is unknown, so the real length is guessed a little longer, and anything more
     * than a minute longer only follows the ones that fit. On 24 Sep this put the uploads that were
     * actually playing first: Memories Anthem (207 s, heard for 192) over mashups of the same two
     * songs at 140, 342, 350 and 515, and I'm Beggin' For DNA (199 s) over three others.
     *
     * Each as long as it lasts in the room at [speed], which Shazam measures: a mashup sped up a
     * quarter lasts four fifths of its upload's length, unless the upload is the sped-up one
     * ([roomLength]). Only the order goes by that: an upload that could be sped up already without
     * saying so is kept ([couldBe]).
     */
    fun byLength(candidates: List<SongItem>, heardS: Double, speed: Double): List<SongItem> {
        val target = heardS + LEAD_GUESS_S
        val (fit, rest) = candidates.filter { couldBe(it, heardS, speed) }
            .partition { item -> roomLength(item, speed)?.let { it <= heardS + MAX_LEAD_S } == true }
        return fit.sortedBy { kotlin.math.abs(roomLength(it, speed)!! - target) } + rest
    }

    /**
     * The pieces as songs rather than as Shazam keys. Shazam can give one recording two keys, and
     * two keys for one song flipping back and forth is not a mashup of it with itself.
     *
     * By title alone, not title and artist: Shazam credits a remix to whoever made it, and on the
     * emulator on 25 Sep the Averez remix of Lean On, heard as "Lean On" by DjSunnymega beside
     * Major Lazer's Robin Schulz remix, was asked about as a mashup of Lean On with itself. Two
     * different songs of one name in one mashup is far the rarer thing.
     */
    fun distinctSongs(pieces: List<MixWatch.Sighting>): List<MixWatch.Sighting> =
        pieces.distinctBy { words(bareTitle(it.title)).ifEmpty { it.key } }

    /** How a song is told apart from others in a mashup: its title as words, or its key if it has none. */
    fun songId(piece: MixWatch.Sighting): String = titleOf(piece).ifEmpty { piece.key }

    /**
     * The songs among [songs] that a mashup's answer does not cover, and so the mashup is searched
     * for again with: none unless it is [settled], and none when no search of its own went into
     * the answer ([searchedSongs] empty: it was answered in an earlier mashup).
     *
     * A song is covered when a search that went through looked for it ([searchedSongs], as
     * [songId]), or when the upload [chosen], taken or picked, names it ([namesSong], among the
     * songs [heard] in the mashup). With nothing chosen, as after None of these, only the searches
     * cover anything.
     *
     * His S25U, 29 Sep: the one-song choice for Faint was answered from uploads of Faint alone,
     * and No Love, heard a minute later, only came out as one more piece, so the search with both
     * names never ran. But the same choice can offer a Faint x No Love upload, and once that is
     * picked No Love is what it said: searching again for it asked the question just answered.
     */
    fun uncovered(
        settled: Boolean,
        chosen: SongItem?,
        searchedSongs: Set<String>,
        songs: List<MixWatch.Sighting>,
        heard: List<MixWatch.Sighting>,
    ): List<MixWatch.Sighting> {
        if (!settled || searchedSongs.isEmpty()) return emptyList()
        return distinctSongs(songs).filter { song ->
            songId(song) !in searchedSongs && (chosen == null || !namesSong(heard + songs, song, chosen))
        }
    }

    /** [searchedSongs] with [songs] added, once a search for them has gone through. See [uncovered]. */
    fun noted(searchedSongs: Set<String>, songs: List<MixWatch.Sighting>): Set<String> =
        searchedSongs + distinctSongs(songs).map(::songId)

    /**
     * What becomes of a mashup once the search for its songs is back: see [outcome].
     *
     * @param goesOn worked out from what the search found: its uploads become the choice, and it is
     * taken or asked about.
     * @param mayTake taken without asking, when it is a clear winner and the songs have taken turns.
     * @param reopens the answer given is set aside, and the question asked again.
     * @param stands the answer given stands, and the piece that came back comes out of the list as
     * one more of it.
     * @param notes the songs the search looked for ([queried]) are noted ([noted]), so the same songs
     * are not searched for again while it lasts.
     */
    enum class Outcome(val goesOn: Boolean, val mayTake: Boolean, val reopens: Boolean, val stands: Boolean, val notes: Boolean) {
        /**
         * Nothing happens: the search failed, nothing names two of the songs, or one odd window has
         * only a toss-up.
         */
        LEAVE(goesOn = false, mayTake = false, reopens = false, stands = false, notes = false),

        /**
         * Nothing found names an uncovered song with the others, or what does the search thinks no
         * more of than the answer given ([stillGiven]): the answer stands.
         */
        STAND(goesOn = false, mayTake = false, reopens = false, stands = true, notes = true),

        /**
         * The answer stands for now, and the next piece that comes back searches again: the search
         * failed, in whole or in part, or one odd window has only a toss-up.
         */
        STAND_FOR_NOW(goesOn = false, mayTake = false, reopens = false, stands = true, notes = false),

        /**
         * An upload names an uncovered song with the others, and the search does not still say it
         * is the answer given: the answer is set aside and the question asked again.
         */
        REOPEN(goesOn = true, mayTake = false, reopens = true, stands = false, notes = true),

        /** A question asked again and not answered yet: asked, never answered by itself. */
        ASK(goesOn = true, mayTake = false, reopens = false, stands = false, notes = true),

        /** A mashup never answered: taken when clear and sure, otherwise asked. */
        ANSWER(goesOn = true, mayTake = true, reopens = false, stands = false, notes = true),
    }

    /**
     * What becomes of a mashup, given the songs its answer did not cover ([uncovered], empty when it
     * has none), the upload it was answered with ([given], null when there is none), whether it was
     * answered once and is being asked again ([reopened]), what the search for its songs found
     * ([ranked], null when it failed), whether every one of its queries went through ([complete]),
     * its [winner], whether the return was [strong], and every song [heard] in it, which tells which
     * of them an upload names ([namesSong]).
     *
     * With nothing uncovered it goes on as it always has: left alone when the search failed, when
     * nothing names two of the songs, or after one odd window with only a toss-up, and otherwise
     * taken or asked. Once answered and asked again it is only asked, however clear the upload: the
     * person answered it once already.
     *
     * With songs uncovered, the answer is set aside only for an upload naming one of them along
     * with another song heard ([reopening]), and only when the search does not still say it is the
     * answer given ([stillGiven]). When nothing found does, that settles it, and the songs are
     * noted, so the pieces coming back later in the mashup do not search YouTube for them again,
     * inline on the listening loop. A search that failed, in whole or in part, leaves the answer
     * standing without noting them, and so does one odd window where no upload naming them is a
     * clear winner over everything found: a later return that says more can still ask.
     */
    fun outcome(
        uncovered: List<MixWatch.Sighting>,
        given: SongItem?,
        reopened: Boolean,
        ranked: List<Pair<SongItem, Int>>?,
        complete: Boolean,
        winner: SongItem?,
        strong: Boolean,
        heard: List<MixWatch.Sighting>,
    ): Outcome {
        if (uncovered.isEmpty()) return when {
            ranked.isNullOrEmpty() || (winner == null && !strong) -> Outcome.LEAVE
            reopened -> Outcome.ASK
            else -> Outcome.ANSWER
        }
        if (ranked == null) return Outcome.STAND_FOR_NOW
        val naming = reopening(ranked, uncovered, heard)
        return when {
            naming.isEmpty() || stillGiven(ranked, naming, given) -> if (complete) Outcome.STAND else Outcome.STAND_FOR_NOW
            // Clear over everything found, not only over the others naming the new song: on a weak
            // return, an upload of the two songs answered for well ahead of one naming the third
            // says that one is only a toss-up.
            !strong && clearWinner(naming + ranked.filterNot { it in naming }, heard) == null -> Outcome.STAND_FOR_NOW
            else -> Outcome.REOPEN
        }
    }

    /**
     * The uploads among [ranked] that say an answer missed something: those naming one of the songs
     * it did not cover ([uncovered]) along with another song [heard], both as [songsNamed] counts
     * them. Not every upload [rank] keeps: it counts a song as named by its artist's name even when
     * another song heard is by the same artist, and then "Numb (Official Music Video)" on the Linkin
     * Park channel, found for Numb coming back after Faint x No Love was picked, names Numb and,
     * by the channel, Faint, and asked the question just answered all over again.
     */
    fun reopening(
        ranked: List<Pair<SongItem, Int>>,
        uncovered: List<MixWatch.Sighting>,
        heard: List<MixWatch.Sighting>,
    ): List<Pair<SongItem, Int>> =
        ranked.filter { (item, _) -> songsNamed(heard, item) >= 2 && uncovered.any { namesSong(heard, it, item) } }

    /**
     * Whether the search, for all it found, still says the mashup is [given], the upload it was
     * answered with: [ranked] puts that very upload first, or none of the uploads [naming] a song
     * it did not cover ([reopening]) scores more than it does in this search. Asking again then
     * only offers the answer already given first, or something the search thinks less of in its
     * place. In a probe of the review, the other mashup of Faint and No Love, taken without asking
     * at 7, came first again at 8 when Numb came back, and "Numb / Faint" at 4 asked the question
     * all over again, with the answer given at the top of the choice; in a playlist's run that very
     * upload was then listed as heard and not added, though it was in the playlist.
     *
     * Not when the search did not find [given] at all: how it would score is not known, and an
     * upload naming the new song is then asked about as before.
     */
    fun stillGiven(ranked: List<Pair<SongItem, Int>>, naming: List<Pair<SongItem, Int>>, given: SongItem?): Boolean {
        if (given == null) return false
        if (ranked.firstOrNull()?.first?.id == given.id) return true
        val own = ranked.firstOrNull { it.first.id == given.id }?.second ?: return false
        return naming.none { it.second > own }
    }

    /**
     * What a mashup's answer is made of, which [follow] changes once the search for its songs is
     * back. The engine's mashup being heard is one; a test can hold its own.
     */
    interface Answer {
        /** The upload it was answered with, taken or picked. None while open, or after None of these. */
        var found: SongItem?
        /** Answered: a clear winner was taken, or the person picked one or said none of these. */
        var settled: Boolean
        /**
         * The songs, as [songId], that searches which went through looked for ([queried]), whether
         * or not they found anything: see [uncovered].
         */
        var searchedSongs: Set<String>
        /** Answered once and asked again, so never answered by itself: see [outcome]. */
        var reopened: Boolean
        /** When it has to be over, once it is known which upload it is and so how long it runs. */
        var endsAtMs: Long?
        /**
         * The upload it was answered with before it was asked again, which comes out of the list if
         * the person picks another: see [replacedBy].
         */
        var replaced: SongItem?
    }

    /**
     * Brings [answer] up to date with [outcome], the search having looked for the songs [lookedFor]
     * ([queried]). Those are noted as searched for, unless the search failed in some way or only a
     * toss-up came of it. When an upload names a song the answer did not, the answer is set aside:
     * not settled, asked again and never answered by itself from then on, no longer known to be the
     * upload it was nor how long it runs, which is kept as the one it may be replaced by.
     */
    fun follow(outcome: Outcome, answer: Answer, lookedFor: List<MixWatch.Sighting>) {
        if (outcome.notes) answer.searchedSongs = noted(answer.searchedSongs, lookedFor)
        if (outcome.reopens) {
            answer.replaced = answer.found ?: answer.replaced
            answer.settled = false
            answer.reopened = true
            answer.found = null
            answer.endsAtMs = null
        }
    }

    /**
     * Whether [winner] is taken without asking: only for a mashup never answered ([Outcome.mayTake]),
     * once the songs have taken turns ([sure]) and adding without asking is on ([autoAdd]). A question
     * asked again is asked, however clear the upload.
     */
    fun takes(outcome: Outcome, winner: SongItem?, sure: Boolean, autoAdd: Boolean): Boolean =
        winner != null && autoAdd && sure && outcome.mayTake

    /**
     * The upload to take back out of the list once the person, asked again, picks [picked]: the one
     * the question had been answered with before ([Answer.replaced]), unless it is the one picked.
     * One mashup is one upload, and the person has now said which. After None of these the earlier
     * one stays: the question was not answered any other way.
     */
    fun replacedBy(replaced: SongItem?, picked: SongItem): SongItem? = replaced?.takeIf { it.id != picked.id }

    /** A piece's bare title as words: what its versions have in common. */
    fun titleOf(piece: MixWatch.Sighting): String = words(bareTitle(piece.title))

    /** Whether two titles name the same song, whatever edit or entry each is. */
    fun sameSong(a: String, b: String): Boolean = words(bareTitle(a)).let { it.isNotEmpty() && it == words(bareTitle(b)) }

    /** Whether [item] names [piece], by its title or its artist. */
    fun names(piece: MixWatch.Sighting, item: SongItem): Boolean {
        val text = textOf(item)
        val title = words(bareTitle(piece.title))
        val artist = piece.artist?.let { words(primaryArtist(it)) }.orEmpty()
        return (title.length >= 3 && " $title " in text) || (artist.length >= 3 && " $artist " in text)
    }

    /** Two points a title named, one an artist, one for a word saying it is a mix. Null under two pieces. */
    internal fun score(pieces: List<MixWatch.Sighting>, item: SongItem): Int? {
        val text = textOf(item)
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
        if (namesUnheard(pieces, item)) score -= UNHEARD_PENALTY
        return score
    }

    /**
     * Whether [item]'s title lists a song that none of [pieces] is. On the emulator on 25 Sep a
     * mashup of Hideaway and Real Love took "Hideaway/Rather Be - Kiesza/Clean Bandit [Mashup]"
     * without asking: named after Hideaway and after both artists, it scored as the one, though
     * the other song in it is a different Clean Bandit song. Only a list counts, two or more names
     * split by a slash, x, vs or plus: a mashup's own name, Damage or Rather Be A Giant, is not a
     * song anybody could have heard, and neither is a list of artists. Kept as a candidate, since
     * Shazam can miss a piece.
     */
    internal fun namesUnheard(pieces: List<MixWatch.Sighting>, item: SongItem): Boolean {
        val titles = distinctSongs(pieces).map { words(bareTitle(it.title)) }.filter { it.isNotEmpty() }
        val artists = pieces.mapNotNull { it.artist }.flatMap { it.split(CREDIT) }.map(::words).filter { it.isNotEmpty() }
        // As whole words anywhere in the name: "Mashup: Hideaway" and "Kiesza's Hideaway" name it.
        fun has(name: String, part: String) = " $name ".contains(" $part ")
        fun heardTitle(name: String) = titles.any { has(name, it) }
        fun known(name: String) = heardTitle(name) || artists.any { has(name, it) }
        // A list of titles, which has one of the heard songs in it. A list of artists is credits:
        // Damage's upload lists Slipknot beside Linkin Park and Eminem, and Shazam can miss a piece.
        return item.title.replace(Regex("\\([^)]*\\)|\\[[^]]*]"), " ")
            .split(Regex("\\s+-\\s+"))
            .any { side ->
                val names = side.split(LIST).map(::words).filter { it.isNotEmpty() }
                names.size >= 2 && names.any(::heardTitle) && names.any { !known(it) }
            }
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

    /** An upload's title and channel as words, padded with a space, for whole-word lookups. */
    private fun textOf(item: SongItem): String = " " + words(item.title + " " + item.artists.joinToString(" ") { it.name }) + " "

    private fun words(text: String): String = text
        .lowercase()
        .replace(Regex("['’]"), "")
        .replace(Regex("[^\\p{L}\\p{N} ]"), " ")
        .replace(Regex("\\s+"), " ")
        .trim()

    /** Enough to keep an upload that names an unheard song from being taken without asking. */
    private const val UNHEARD_PENALTY = 2
    private val LIST = Regex("\\s*/\\s*|\\s+x\\s+|\\s+vs\\.?\\s+|\\s+\\+\\s+", RegexOption.IGNORE_CASE)
    private val CREDIT = Regex("\\s*(,|&| feat\\.? | ft\\.? | featuring | x | with )\\s*", RegexOption.IGNORE_CASE)

    // Not a lone x: "Major Lazer x DJ Snake - Lean On (Averez Remix)" is one song. Mashups say so.
    private val SEVERAL = Regex("mash ?up|medley|megamix|\\bvs\\.?(\\s|$)", RegexOption.IGNORE_CASE)

    private val MIX_WORDS = Regex("mash ?up|medley|megamix|\\bmix\\b|remix|\\bvs\\.?\\b", RegexOption.IGNORE_CASE)

    /** Two titles and a mix word, or two titles and both artists. */
    private const val CLEAR_SCORE = 5
    private const val CLEAR_LEAD = 2

    /** Faster than this, uploads are as often called nightcore as sped up. */
    private const val NIGHTCORE_SPEED = 1.2

    /**
     * From this fast, an upload saying it is sped up can be what plays. A DJ moves a track by up
     * to 8 %; sped-up uploads run a fifth to a third fast.
     */
    private const val SPED_UP_SPEED = 1.1

    /** From this slow, an upload saying it is slowed can be what plays. Slowed uploads run a sixth or so slow. */
    private const val SLOWED_SPEED = 0.9

    /** The slowest and fastest a room is taken to play: see [roomSpeed]. */
    private const val MIN_ROOM_SPEED = 0.5
    private const val MAX_ROOM_SPEED = 2.0

    /** Longer than this is a compilation or a DJ set, not a mashup. */
    private const val MAX_LENGTH_S = 600
    private const val LEAD_GUESS_S = 10
    private const val MAX_LEAD_S = 60
}
