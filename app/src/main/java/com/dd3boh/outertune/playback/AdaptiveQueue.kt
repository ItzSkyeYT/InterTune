/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.playback

import com.dd3boh.outertune.engine.Features
import com.dd3boh.outertune.engine.SongTags
import com.dd3boh.outertune.engine.TagFit
import com.dd3boh.outertune.utils.SongVersions

/**
 * Keeps the far end of the queue honest while the near end stays put.
 *
 * Half of this listener's plays are six or more songs deep in an autoplay chain, and that band is
 * the worst engaged of the lot: radio runs at an 84 percent skip rate against 64 for Quick picks.
 * The reason is structural rather than bad luck. A radio page is fetched once, when the queue is
 * five from the end, and then played to the end whatever happens in between. Twenty minutes later
 * the listener has moved on and the queue has not.
 *
 * So the tail is provisional. Whatever has actually been playing lately decides which of the
 * songs still ahead belong there, and the ones that plainly do not are dropped before they are
 * ever reached.
 *
 * Three rules keep it from being annoying, which is the whole risk:
 *
 *  - [LOCKED] songs after the current one are never touched. What is next stays next. Somebody
 *    who looked at the queue thirty seconds ago must not find it rearranged under them.
 *  - Order is preserved inside each group. This is a filter, not a re-sort: a queue that
 *    reshuffled itself continuously would be unreadable.
 *  - It never removes more than [MAX_DROP_SHARE] of the tail at once, and never empties it.
 *    A wrong call costs a few songs, not the queue.
 *
 * No Android in here, so it is tested.
 */
object AdaptiveQueue {

    /** Songs after the current one that are never reconsidered. */
    const val LOCKED = 4

    /** At most this share of the provisional tail may go in one pass. */
    const val MAX_DROP_SHARE = 0.4

    /**
     * The share allowed when the listener has just said something clear.
     *
     * Skipping a song is the strongest opinion anyone offers without pressing anything, and liking
     * one is the strongest they offer by pressing something. Both mean the picture just changed,
     * so the tail should move further than it does when a song merely ended.
     */
    const val STRONG_DROP_SHARE = 0.75

    /** Songs kept untouched after a clear signal. Fewer, because the listener just acted. */
    const val STRONG_LOCKED = 2

    /** Below this fit, a song is considered wrong for what is happening now. */
    const val DROP_BELOW = -0.25

    /** What a pass decided: the tail to keep, in order, and what it took out. */
    data class Plan<T>(val keep: List<T>, val dropped: List<T>) {
        val changed: Boolean get() = dropped.isNotEmpty()
    }

    /**
     * Which of the songs still ahead should stay: the pass a release build runs while
     * Unreleased.ADAPTIVE_PASS is held back, which only ever takes songs out (QueuePlanner is the
     * one that also puts fresh picks in).
     *
     * [tail] is everything after the locked head, in play order. [context] is what the last few
     * listens sounded like; when it is not known, nothing is dropped, because the honest answer to
     * "what does this listener want right now" after a long gap is that we have no idea.
     */
    fun <T> plan(
        tail: List<T>,
        title: (T) -> String?,
        context: TagFit.Context,
        dropBelow: Double = DROP_BELOW,
        dropShare: Double = MAX_DROP_SHARE,
        artists: (T) -> List<String> = { emptyList() },
        turnedDown: TurnedDown = TurnedDown(),
        plainRun: Boolean = false,
    ): Plan<T> {
        if (tail.isEmpty() || !context.known) return Plan(tail, emptyList())

        // Only songs that say something about themselves are candidates for removal.
        //
        // TagFit scores an untagged song against a fully treated run at minus one, which is right
        // for ranking: a plain recording really is jarring in the middle of a slowed set. It is
        // wrong for deletion. Most music carries no qualifier at all, so acting on it here would
        // empty the queue of everything except the labelled quarter of the library, which is not
        // a rebalance, it is a purge.
        //
        // What the listener turned down in this queue goes first, ahead of anything a title says:
        // a skip is an opinion about this song, a title only a guess about how it sounds.
        val scored = tail.map { item ->
            val name = title(item)
            val by = artists(item)
            item to when {
                by.any { it.trim().lowercase() in turnedDown.artists } -> TURNED_DOWN_FIT
                name != null && turnedDown.songs.isNotEmpty() &&
                    SongVersions.baseTitle(name, by.firstOrNull()) in turnedDown.songs -> TURNED_DOWN_FIT
                else -> verdict(name, context, plainRun)
            }
        }
        val budget = (tail.size * dropShare).toInt()
        if (budget <= 0) return Plan(tail, emptyList())

        // The worst offenders first, but only up to the budget, and only ones actually below the
        // line. Taking the N worst regardless would drop songs that were perfectly fine whenever
        // the whole tail happened to fit.
        val doomed = scored.asSequence()
            .filter { it.second < dropBelow }
            .sortedBy { it.second }
            .take(budget)
            .map { it.first }
            .toCollection(HashSet())

        if (doomed.isEmpty()) return Plan(tail, emptyList())

        // Order preserved: this removes songs, it does not rearrange them.
        return Plan(tail.filterNot { it in doomed }, tail.filter { it in doomed })
    }

    /**
     * How well a song's own words suit the run [context], from minus one to one: zero for a title
     * that claims no treatment, since most music carries none (see [plan]). After a [plainRun] a
     * treated version is out of place: TagFit weighs a treated candidate by how treated the run is,
     * and a plain run is not treated at all, so a slowed edit in the middle of plain recordings came
     * out at nothing. Below [DROP_BELOW] a song is out of place by its words.
     */
    fun verdict(title: String?, context: TagFit.Context, plainRun: Boolean): Double {
        if (!context.known) return 0.0
        val tags = SongTags.of(title)
        return when {
            tags.isEmpty() -> 0.0
            plainRun && tags.any { it in TREATMENTS } -> PLAIN_RUN_FIT
            else -> TagFit.score(tags, context)
        }
    }

    /**
     * The index the tail starts at, given where playback is.
     *
     * Returns null when there is no tail worth planning, which is the common case: a queue with
     * nothing much left in it is about to be topped up anyway.
     */
    fun tailStart(currentIndex: Int, size: Int, locked: Int = LOCKED): Int? {
        val start = currentIndex + 1 + locked
        return if (start in 0 until size) start else null
    }

    /**
     * The furthest place in [queue] that playback has reached, and never one before [currentIndex].
     *
     * Going back is not a new queue. Somebody who played eight songs and went back to the first has
     * heard the seven after it, and saw the few after those waiting in the queue: that is history,
     * not a provisional tail. Planning from the song playing treated them as upcoming and judged
     * them against that one song, and the queue sheet folded them away, so a listener who went back
     * watched what they had just played disappear, which looked like adapting and was not.
     *
     * [reached] holds the songs that have been current in this queue (see [Heard]), so a place is
     * found by its song wherever removals and moves have left it.
     */
    fun <T> settledThrough(currentIndex: Int, queue: List<T>, id: (T) -> String?, reached: Set<String>): Int {
        if (reached.isEmpty()) return currentIndex
        for (i in queue.indices.reversed()) {
            if (i <= currentIndex) break
            if (id(queue[i])?.let { it in reached } == true) return i
        }
        return currentIndex
    }

    /**
     * How a song that stopped being the one playing was left, as far as the listener's taste goes.
     *
     * [RAN_OUT] it played to its end by itself, or came round again on repeat. [KEPT] next was
     * pressed with half of it or more heard. [SKIPPED] next was pressed before half. [NEITHER] says
     * nothing: going back, a tap on another song, a new queue, skip on error moving past a song that
     * failed, or a leave whose place is not known.
     */
    enum class Leave { RAN_OUT, KEPT, NEITHER, SKIPPED }

    /**
     * [leftAtMs] is where the song was when next was pressed, [heardThroughMs] how far into it the
     * play really got (where it started plus the time it was heard), null when that is not known.
     *
     * Half is where a skip ends ([leftEarly]) and where a keep begins: the engine counts a listen as
     * heard well from about the same place (EngineParams.justPlayedEngagement on its ramp), and a
     * band between the two would leave the listener who moves on at two thirds with no seeds at
     * all. A song scrubbed to its end and then left was not heard, so it is [NEITHER]; so is one of
     * unknown length left after its first half minute, which could be anywhere in it.
     */
    fun leave(leftAtMs: Long?, durationSec: Int, heardThroughMs: Long? = null): Leave {
        if (leftAtMs == null || leftAtMs < 0) return Leave.NEITHER
        if (leftEarly(leftAtMs, durationSec)) return Leave.SKIPPED
        if (durationSec <= 0) return Leave.NEITHER
        if (heardThroughMs != null && heardThroughMs < durationSec * 500L) return Leave.NEITHER
        return Leave.KEPT
    }

    /**
     * What has played in the current queue: each song that became current, newest first, and how
     * each was left, for one run at a time.
     *
     * The session's character used to be read from the places just before the song playing, and
     * those are what was heard only while playback moves forward. After going back to the first
     * song there was nothing before it but itself, and the whole tail was judged against one song.
     * This keeps the order songs were actually heard in.
     *
     * A run is one play of one queue from the tap that started it (MultiQueueObject.runId). A radio
     * begins in a queue of one and goes on in the queue YouTube's answer names, which is another
     * queue when one of that title is on the board already: keyed on the queue, the seed song was
     * forgotten the moment the second song started. A run of 0, a queue saved before runs were
     * kept, is keyed on its queue.
     *
     * The last few radio runs are kept beside the current one ([EARLIER_RUNS]), so a radio that
     * gives way to a playlist for two songs and comes back picks up where it was, and so are the
     * runs a previous process saved ([offer], AdaptiveMemory), until their queue plays again. A run
     * left alone for longer than the engine's run window ([Features.RUN_WINDOW_MS]) comes back with
     * what it played and none of what it learned: that sitting is over.
     *
     * Only the player's thread touches it.
     */
    class Heard(
        private val recentCap: Int = RECENT_CAP,
        private val reachedCap: Int = REACHED_CAP,
        private val clock: () -> Long = System::currentTimeMillis,
    ) {
        private var run = Run("", 0L, 0L)
        private val earlier = LinkedHashMap<String, Run>()
        private val stored = HashMap<String, AdaptiveMemory.Run>()

        /** The songs that have been current in this run. A view, not a copy. */
        val reached: Set<String> get() = run.ids

        /**
         * The versions of those songs ([RadioPage.versionOf]), so that a radio's next page knows
         * another cut of a song heard in this queue for that song (RadioPage.fresh). A view.
         */
        val reachedVersions: Set<String> get() = run.versions

        /** The song noted last: the one playing, until the next is noted. */
        val playing: Song? get() = run.recent.firstOrNull()?.song

        /** Which run is current: "run:" and its id, or "queue:" and the queue's for a run of 0. */
        val runKey: String get() = run.key

        /** How the song before the one playing was left, null before any was. */
        val lastLeave: Leave? get() = run.lastLeave

        /**
         * Whether any of this run has been heard: a song of it left, or a second one begun. A queue
         * just loaded, or loaded under the song already playing, has only the song it began on, and
         * a pass then had that one song to judge the whole queue by.
         */
        val begun: Boolean get() = run.played >= 2 || run.lastLeave != null

        /** The songs put in or moved by hand in this run, which no pass touches. A view. */
        val byHand: Set<String> get() = run.byHand

        /** What the queue sheet says about the last change in this run, null for nothing. */
        var note: AdaptiveNote.Note?
            get() = run.note
            set(value) { run.note = value; touch() }

        /**
         * [id], titled [title], became the song playing in the queue numbered [queue], in run
         * [runId]; [radio] when that queue is a radio or a mix, whose runs are the ones kept.
         *
         * A song noted while it is already the one playing is not noted again: the song brought
         * back after the app was closed, and repeat one, would otherwise fill the run with copies
         * of itself. A song that comes back later in the run is heard again ([Entry.replay]).
         */
        fun note(
            queue: Long, id: String, title: String?, artists: List<String> = emptyList(), durationSec: Int = -1,
            runId: Long = 0L, radio: Boolean = false,
        ) {
            val key = keyOf(queue, runId)
            if (key != run.key) switchTo(key, queue, runId)
            if (radio) run.radio = true
            touch()
            val first = run.recent.firstOrNull()
            if (first?.song?.id == id) return
            // What a run saved without its evidence (over an hour and a half old) still knows which
            // song was playing: that one is not heard again for being noted on the way back in.
            val restoredPlaying = first == null && run.playingId == id
            val entry = Entry(Song(id, title, artists, durationSec))
            entry.replay = id in run.ids && !restoredPlaying
            run.recent.addFirst(entry)
            while (run.recent.size > recentCap) run.recent.removeLast()
            if (run.start == null) run.start = entry
            run.ids.remove(id)
            run.ids.add(id)
            while (run.ids.size > reachedCap) run.ids.remove(run.ids.first())
            RadioPage.versionOf(title, artists.firstOrNull())?.let { version ->
                run.versions.remove(version)
                run.versions.add(version)
                while (run.versions.size > reachedCap) run.versions.remove(run.versions.first())
            }
            run.playingId = id
            if (!restoredPlaying) run.played++
            // Going back to a song, or tapping it, is asking for it: whatever it was skipped for
            // in this run is forgiven.
            if (entry.replay) kept(entry)
        }

        /**
         * [id], the song playing, goes on in the run of [queue] and [runId] with no song change: a
         * queue loaded under it, as Start radio on the song playing loads its radio. Noted there as
         * [note] notes a song, and liked there when it was liked where it came from, so leaving it
         * early is no verdict against a song liked. The like's picks were the run before's to put in.
         */
        fun carriedOn(
            queue: Long, id: String, title: String?, artists: List<String> = emptyList(), durationSec: Int = -1,
            runId: Long = 0L, radio: Boolean = false,
        ) {
            val liked = run.recent.firstOrNull()?.takeIf { it.song.id == id }?.liked == true
            note(queue, id, title, artists, durationSec, runId, radio)
            if (liked) run.recent.firstOrNull()?.takeIf { it.song.id == id && !it.liked }?.let { entry ->
                entry.liked = true
                kept(entry)
            }
        }

        /** The titles of the last [n] songs heard, newest first, the one playing included. */
        fun titles(n: Int): List<String?> = run.recent.take(n).map { it.song.title }

        /**
         * The titles of the last [n] songs of the run as the listener kept it, newest first: what
         * the run sounds like for the pass that also puts songs in.
         *
         * Not the songs skipped or swiped away, which are what the listener is turning from, and
         * not the song that has just started, which is the radio's choice until it is judged
         * (unless it is liked or heard again). Counted with them, a listener moving from slowed
         * edits to plain recordings by skipping the edits still read as on a slowed run, and plain
         * picks lost to slowed ones.
         */
        fun keptTitles(n: Int): List<String?> = run.recent.asSequence()
            .filterIndexed { i, e -> if (i == 0) e.positive else !e.turnedAway }
            .take(n).map { it.song.title }.toList()

        /** The song playing was left as [how] says ([leave]). Called before the next is noted. */
        fun left(how: Leave) {
            val entry = run.recent.firstOrNull() ?: return
            touch()
            // A song liked while it played was answered by the like: leaving it early after that
            // says nothing new against it.
            run.lastLeave = if (how == Leave.SKIPPED && entry.liked) Leave.NEITHER else how
            when (run.lastLeave) {
                Leave.RAN_OUT, Leave.KEPT -> { entry.kept = true; kept(entry) }
                Leave.SKIPPED -> {
                    // Heard again and then skipped soon after: it leaves the positives.
                    entry.skipped = true
                    run.lastSkipped = entry.song.id
                    skipped(entry.song)
                }
                else -> {}
            }
        }

        /** [song] was left early, by the listener moving on to a later one. */
        fun skipped(song: Song) {
            touch()
            for (artist in song.artists.map { it.trim().lowercase() }.distinct()) {
                run.skipsByArtist[artist] = (run.skipsByArtist[artist] ?: 0) + 1
            }
            val base = song.title?.let { SongVersions.baseTitle(it, song.artists.firstOrNull()) }
            if (!base.isNullOrBlank()) {
                run.skippedSongs.remove(base)
                run.skippedSongs.add(base)
                while (run.skippedSongs.size > recentCap) run.skippedSongs.remove(run.skippedSongs.first())
            }
        }

        /** [song] was heard to its end: whatever its artists were skipped for is forgiven. */
        fun finished(song: Song) {
            song.artists.forEach { run.skipsByArtist.remove(it.trim().lowercase()) }
        }

        /** The song playing, [id], was liked: a positive at once, and two picks like it to come. */
        fun liked(id: String) {
            val entry = run.recent.firstOrNull()?.takeIf { it.song.id == id } ?: return
            entry.liked = true
            kept(entry)
            if (id !in run.likeAnswered) run.likePending = id
            touch()
        }

        /** The like on [id] was taken back: nothing is put in for it after all. */
        fun unliked(id: String) {
            run.recent.firstOrNull()?.takeIf { it.song.id == id }?.liked = false
            if (run.likePending == id) run.likePending = null
            touch()
        }

        /** The like on [id] has had its picks put in. Once per liked song in a run. */
        fun likeAnswered(id: String) {
            run.likeAnswered.add(id)
            if (run.likePending == id) run.likePending = null
            touch()
        }

        /**
         * [id] was swiped out of the queue: its other versions go with it, and its artists take a
         * skip each. A pick the listener took out is never picked again in this run.
         */
        fun removedByHand(id: String, title: String?, artists: List<String>) {
            skipped(Song(id, title, artists, -1))
            run.recent.filter { it.song.id == id }.forEach { it.removed = true }
            if (run.picks.remove(id) != null) run.dismissed.add(id)
            run.byHand.remove(id)
            touch()
        }

        /**
         * [ids] were put into the queue [queue] by hand (Play next, Add to queue), in run [runId],
         * which need not be the run playing: before play is pressed after a restart, the songs go
         * into the saved queue. A pass never touches them.
         */
        fun putInByHand(queue: Long, runId: Long, ids: Collection<String>, radio: Boolean) {
            val target = runFor(keyOf(queue, runId), queue, runId)
            if (radio) target.radio = true
            target.byHand.addAll(ids)
            target.changedAt = clock()
        }

        /** [id] was dragged to another place in the queue sheet: it is where the listener wants it. */
        fun movedByHand(id: String) {
            run.byHand.add(id)
            touch()
        }

        /** [picks] (song to the seed it was picked for) were put into this run's queue by a pass. */
        fun picked(picks: Map<String, String>) {
            run.picks.putAll(picks)
            touch()
        }

        /** [ids], picks a later pass took out again, are not picked again in this run. */
        fun dismiss(ids: Collection<String>) {
            for (id in ids) if (run.picks.remove(id) != null) run.dismissed.add(id)
            touch()
        }

        /** Whether a pass put [id] into this run. */
        fun isPick(id: String): Boolean = id in run.picks

        /** This run's picks that have not played yet: the songs the queue sheet marks as new. */
        fun unreachedPicks(): Set<String> = run.picks.keys.filterTo(LinkedHashSet()) { it !in run.ids }

        /** How many songs have become current in this run, the one playing included. */
        val played: Int get() = run.played

        /** What a pass should take out first. */
        fun turnedDown(): TurnedDown = TurnedDown(
            artists = run.skipsByArtist.filterValues { it >= ARTIST_SKIPS }.keys.toSet(),
            songs = run.skippedSongs.toSet(),
        )

        /** All a pass reads, copied, so it can be read on another thread. */
        fun view(): View {
            val r = run
            val kept = keptTitles(TagFit.WINDOW)
            // Each song by how it went the last time it played: heard again and skipped soon
            // after, it is no positive any more.
            val positives = r.recent.asSequence().distinctBy { it.song.id }.filter { it.positive }.map { it.song.id }.take(SEEDS).toList()
            val start = r.start
            // The song the run began with stands in only while nothing is kept and it has not been
            // turned away: picks like a song just skipped are what the listener turned from.
            val seeds = positives.ifEmpty { listOfNotNull(start?.takeIf { !it.turnedAway }?.song?.id) }
            val positiveArtists = LinkedHashSet(r.positiveArtists)
            if (start != null && !start.turnedAway) start.song.artists.mapTo(positiveArtists) { it.trim().lowercase() }
            return View(
                key = r.key, queueId = r.queueId, runId = r.runId,
                reached = r.ids.toSet(), reachedVersions = r.versions.toSet(),
                keptTitles = kept, plainRun = plainRun(keptTitles(PLAIN_RUN)),
                turnedDown = turnedDown(),
                artistSkips = r.skipsByArtist.filterValues { it >= 1 }.keys.toSet(),
                seeds = seeds, positiveArtists = positiveArtists,
                byHand = r.byHand.toSet(), picks = r.picks.filterKeys { it !in r.ids },
                dismissed = r.dismissed.toSet(), likePending = r.likePending,
                lastSkipped = r.lastSkipped, playingId = r.recent.firstOrNull()?.song?.id,
                played = r.played,
            )
        }

        /**
         * The radio runs worth keeping across a force close, the one playing first, then those kept
         * beside it, then those the last process saved that have not played again yet, newest
         * first. See AdaptiveMemory. Without the last, the first write of a new process took them
         * out of the file, and a second close before their queue played again lost what they had
         * played.
         */
        fun saved(): List<AdaptiveMemory.Run> {
            val live = (listOf(run) + earlier.values.reversed()).filter { it.radio && it.key.isNotEmpty() }
                .take(AdaptiveMemory.RUNS).map { it.toSaved() }
            return (live + stored.values.sortedByDescending { it.savedAt }).take(AdaptiveMemory.RUNS)
        }

        /**
         * Runs read back from the last process, to be picked up when their queue plays again. A run
         * already here, the one playing or one kept beside it, is newer than any file and stays.
         */
        fun offer(runs: List<AdaptiveMemory.Run>) {
            for (r in runs) {
                if (r.key == run.key || r.key in earlier) continue
                stored[r.key] = r
            }
        }

        private fun touch() {
            run.changedAt = clock()
        }

        private fun kept(entry: Entry) {
            finished(entry.song)
            entry.song.artists.mapTo(run.positiveArtists) { it.trim().lowercase() }
            entry.song.title?.let { SongVersions.baseTitle(it, entry.song.artists.firstOrNull()) }
                ?.let { run.skippedSongs.remove(it) }
        }

        private fun keyOf(queue: Long, runId: Long) = runKey(queue, runId)

        /** The run named [key], from here, from what was saved, or new, without making it current. */
        private fun runFor(key: String, queue: Long, runId: Long): Run {
            if (key == run.key) return run
            earlier[key]?.let { return it }
            val found = stored.remove(key)?.let { restore(it) } ?: Run(key, queue, runId)
            earlier[key] = found
            while (earlier.size > EARLIER_RUNS) earlier.remove(earlier.keys.first())
            return found
        }

        private fun switchTo(key: String, queue: Long, runId: Long) {
            val old = run
            if (old.radio && old.key.isNotEmpty()) {
                earlier.remove(old.key)
                earlier[old.key] = old
                while (earlier.size > EARLIER_RUNS) earlier.remove(earlier.keys.first())
            }
            run = earlier.remove(key)?.also { if (clock() - it.changedAt > Features.RUN_WINDOW_MS) it.forgetEvidence() }
                ?: stored.remove(key)?.let { restore(it) }
                ?: Run(key, queue, runId)
        }

        /** A run brought back from what the last process saved, for the log, read once ([takeRestored]). */
        data class Restored(val key: String, val reached: Int, val picks: Int, val evidence: Boolean)

        private var restored: Restored? = null

        /** The run [note] or [putInByHand] last brought back from the last process, once. */
        fun takeRestored(): Restored? = restored.also { restored = null }

        private fun restore(saved: AdaptiveMemory.Run): Run {
            val s = AdaptiveMemory.fresh(saved, clock())
            restored = Restored(s.key, s.reached.size, s.picks.size, s.evidence != null)
            val r = Run(s.key, s.queueId, s.runId)
            r.radio = true
            r.changedAt = s.savedAt
            r.ids.addAll(s.reached)
            r.versions.addAll(s.versions)
            r.byHand.addAll(s.byHand)
            r.picks.putAll(s.picks)
            r.playingId = s.playing
            r.played = s.played
            r.note = s.note
            s.evidence?.let { e ->
                e.recent.forEach { r.recent.addLast(it.toEntry()) }
                r.skipsByArtist.putAll(e.skipsByArtist)
                r.skippedSongs.addAll(e.skippedSongs)
                r.positiveArtists.addAll(e.positiveArtists)
                // The start song is the same entry as the oldest kept, where it still is one.
                r.start = e.start?.let { st -> r.recent.lastOrNull()?.takeIf { it.song.id == st.id && r.recent.size < recentCap } ?: st.toEntry() }
                r.lastLeave = e.lastLeave
            }
            return r
        }

        private fun Run.toSaved() = AdaptiveMemory.Run(
            key = key, queueId = queueId, runId = runId, savedAt = changedAt,
            reached = ids.toList(), versions = versions.toList(), byHand = byHand.toList(),
            picks = LinkedHashMap(picks), playing = playingId, played = played,
            evidence = AdaptiveMemory.Evidence(
                recent = recent.map { it.toSaved() },
                skipsByArtist = HashMap(skipsByArtist),
                skippedSongs = skippedSongs.toList(),
                positiveArtists = positiveArtists.toList(),
                start = start?.toSaved(),
                lastLeave = lastLeave,
            ),
            note = note,
        )

        private fun Entry.toSaved() = AdaptiveMemory.Entry(
            song.id, song.title, song.artists, song.durationSec, kept = kept, skipped = skipped, liked = liked,
            replay = replay, removed = removed,
        )

        private fun AdaptiveMemory.Entry.toEntry() = Entry(Song(id, title, artists, durationSec)).also {
            it.kept = kept; it.skipped = skipped; it.liked = liked; it.replay = replay; it.removed = removed
        }
    }

    /** The key [Heard] keeps a run under: its run, or its queue for a run of 0. */
    fun runKey(queue: Long, runId: Long): String = if (runId != 0L) "run:$runId" else "queue:$queue"

    /** One run as [Heard] keeps it. */
    private class Run(val key: String, val queueId: Long, val runId: Long) {
        var radio = false
        var changedAt = 0L
        val recent = ArrayDeque<Entry>()
        val ids = LinkedHashSet<String>()
        val versions = LinkedHashSet<String>()
        val skipsByArtist = HashMap<String, Int>()
        val skippedSongs = LinkedHashSet<String>()
        /** Lower-cased artists with a song kept, liked or heard again in this run. */
        val positiveArtists = LinkedHashSet<String>()
        /** The run's first song: what a radio was started from. */
        var start: Entry? = null
        val byHand = LinkedHashSet<String>()
        /** Songs a pass put in, each with the seed it was picked for. */
        val picks = LinkedHashMap<String, String>()
        val dismissed = HashSet<String>()
        var likePending: String? = null
        val likeAnswered = HashSet<String>()
        var lastLeave: Leave? = null
        var lastSkipped: String? = null
        var note: AdaptiveNote.Note? = null
        var played = 0
        var playingId: String? = null

        /** What this run learned, without what it played: the sitting it describes is over. */
        fun forgetEvidence() {
            recent.clear()
            skipsByArtist.clear()
            skippedSongs.clear()
            positiveArtists.clear()
            start = null
            dismissed.clear()
            likePending = null
            lastLeave = null
            lastSkipped = null
            note = null
        }
    }

    /** One song heard in a run, and how it went. */
    private class Entry(val song: Song) {
        var kept = false
        var skipped = false
        var liked = false
        var replay = false
        var removed = false

        /** A song the run counts as wanted: kept, liked, or heard again and not skipped since. */
        val positive: Boolean get() = liked || ((kept || replay) && !skipped && !removed)

        /** Skipped or swiped out, and not liked. */
        val turnedAway: Boolean get() = (skipped || removed) && !liked
    }

    /**
     * Everything a pass reads of the run, copied on the player's thread.
     *
     * [seeds] are the newest positives, newest first ([SEEDS] of them), or the song the run began
     * with while there are none and it was not turned away: the song a radio was started from is
     * the clearest intent there is until the listener skips it, and with no seed a pass only takes
     * out what is unwanted. [positiveArtists] are the artists with a song kept, liked or heard
     * again in the run, and the first song's unless it was turned away: no lasting habit stands
     * against what the listener is doing right now. [artistSkips] are the artists skipped and not
     * forgiven since, once or more; [turnedDown] holds those skipped twice.
     */
    data class View(
        val key: String,
        val queueId: Long,
        val runId: Long,
        val reached: Set<String>,
        val reachedVersions: Set<String>,
        val keptTitles: List<String?>,
        val plainRun: Boolean,
        val turnedDown: TurnedDown,
        val artistSkips: Set<String>,
        val seeds: List<String>,
        val positiveArtists: Set<String>,
        val byHand: Set<String>,
        /** Picks that have not played yet, each with its seed. */
        val picks: Map<String, String>,
        val dismissed: Set<String>,
        val likePending: String?,
        val lastSkipped: String?,
        val playingId: String?,
        val played: Int,
    )

    /** One song as it was heard: what it was, by whom, and how long it is (-1 when not known). */
    data class Song(val id: String, val title: String?, val artists: List<String>, val durationSec: Int)

    /**
     * What the listener turned down in this queue: the artists skipped [ARTIST_SKIPS] times with
     * none of their songs heard to the end since, in lower case, and the songs skipped, by
     * [SongVersions.baseTitle], so their other versions go with them.
     */
    data class TurnedDown(val artists: Set<String> = emptySet(), val songs: Set<String> = emptySet())

    /**
     * Whether a song left at [leftAtMs] was turned down rather than let run out: less than half of
     * it heard, or less than half a minute when its length is not known. Pressing next during the
     * last chorus is not a verdict on the song.
     */
    fun leftEarly(leftAtMs: Long?, durationSec: Int): Boolean {
        if (leftAtMs == null || leftAtMs < 0) return false
        return if (durationSec > 0) leftAtMs < durationSec * 500L else leftAtMs < 30_000L
    }

    /**
     * Whether the last [PLAIN_RUN] songs heard, newest first in [recentTitles], were all plain
     * recordings: none slowed, sped up, nightcore, reverb, bass boosted, 8D or looped. A remix, an
     * edit or a live take is still a plain recording for this, since it is often the only version.
     */
    fun plainRun(recentTitles: List<String?>): Boolean =
        recentTitles.size >= PLAIN_RUN && recentTitles.take(PLAIN_RUN).none { t -> SongTags.of(t).any { it in TREATMENTS } }

    /** Things done to a recording, as opposed to versions of it. */
    val TREATMENTS = setOf("nightcore", "slowed", "sped", "reverb", "bassboost", "eightd", "loop")

    /** Skips of one artist in a queue before their other songs go. One skip is a mood, two a pattern. */
    const val ARTIST_SKIPS = 2

    /** Songs in a row it takes to call a run plain. */
    const val PLAIN_RUN = 4

    /** The newest positives a pass picks from: TagFit's window, the engine's "now". */
    const val SEEDS = TagFit.WINDOW

    /** Radio runs kept beside the current one, in memory as on disk. */
    const val EARLIER_RUNS = 3

    private const val TURNED_DOWN_FIT = -1.0
    const val PLAIN_RUN_FIT = -0.5

    private const val RECENT_CAP = 16
    private const val REACHED_CAP = 2_000
}
