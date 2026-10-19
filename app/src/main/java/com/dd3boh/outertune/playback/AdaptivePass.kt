/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.playback

import com.dd3boh.outertune.engine.SkipHabits
import com.dd3boh.outertune.engine.SongTags
import com.dd3boh.outertune.engine.TagFit
import com.dd3boh.outertune.models.MediaMetadata
import com.dd3boh.outertune.utils.SongVersions
import java.util.Locale

/**
 * One pass of the adaptive queue over a radio, from what the service took on its own thread
 * (the tail, what the run heard, the taste, the related lists) to what should change: the places
 * of the tail read as QueuePlanner reads them, the fresh picks that may take their place, and the
 * plan. It reads nothing and changes nothing itself; MusicService reads the inputs on the adaptive
 * queue's own thread and applies the plan on the player's, after checking again that the queue is
 * the one the plan was made for.
 *
 * Fresh picks come from the related lists of the run's seeds (AdaptiveQueue.View.seeds), newest
 * first and in YouTube's order within each. A pick must be a song the next pass would keep, or the
 * queue would argue with itself: not in the queue or reached in this run by id or by version
 * ([RadioPage.fresh]), not taken out by an earlier pass, not turned down or out of place by its
 * words, not a preview, not banned, not a lasting dislike, playable (offline: on the phone), not
 * heard in this listening session or well in the last hour, and with nothing from this sitting
 * against it: no artist skipped once, no words against the run, not on the skipped song's list.
 *
 * No Android in here, so it is tested.
 */
object AdaptivePass {

    /**
     * Which pass: after a song simply moved on, after a skip (next pressed before half the song),
     * or after a like. The last two lock two songs rather than four and may change three quarters
     * of the tail rather than two fifths (AdaptiveQueue.STRONG_LOCKED, STRONG_DROP_SHARE): the
     * listener just said something clear.
     */
    enum class Kind(val code: String) { ORDINARY("ordinary"), SKIP("skip"), LIKE("like") }

    /** One place of the tail as the service found it: where it is in play order, its song, and whether it was put there by hand. */
    data class Place(val index: Int, val song: MediaMetadata, val byHand: Boolean = false)

    /** One seed's related list: stored on the phone, or the seed's radio page ([page]). */
    data class Source(val seed: String, val songs: List<MediaMetadata>, val page: Boolean = false)

    class Input(
        val kind: Kind,
        val tail: List<Place>,
        /** Every song from the one playing to the start of the tail. */
        val head: List<MediaMetadata>,
        /** The whole queue, for what a pick may not repeat. */
        val queue: List<MediaMetadata>,
        val view: AdaptiveQueue.View,
        val taste: QueueFit.Taste,
        val sources: List<Source>,
        /** The songs on the stored related list of the song just skipped, after a skip. */
        val skippedRelated: Set<String> = emptySet(),
        val offline: Boolean = false,
        /** Whether a song plays with no network: on the phone, downloaded or wholly cached. */
        val playsOffline: (MediaMetadata) -> Boolean = { true },
        /** Whether a place of the near part may be chosen again for its score alone. */
        val byScore: Boolean = true,
    ) {
        val share: Double get() = if (kind == Kind.ORDINARY) AdaptiveQueue.MAX_DROP_SHARE else AdaptiveQueue.STRONG_DROP_SHARE
    }

    class Result(
        val kind: Kind,
        val plan: QueuePlanner.Plan,
        val slots: List<QueuePlanner.Slot>,
        /** The usable picks' songs, by id. */
        val songs: Map<String, MediaMetadata>,
        /** Songs on the lists read, the page's included, and how many of them came from a page. */
        val candidates: Int,
        val fromPage: Int,
        val usable: Int,
        /** How many picks the pass had places for: the places a rule or a score asked to change, within the share, and a like's two. */
        val asking: Int,
    )

    fun run(input: Input): Result {
        val view = input.view
        val taste = input.taste
        val tags = TagFit.context(view.keptTitles.map { SongTags.of(it) })
        val referrers = HashMap<String, MutableList<String>>()
        for (source in input.sources) for (song in source.songs) {
            val seeds = referrers.getOrPut(song.id) { ArrayList(2) }
            if (source.seed !in seeds) seeds += source.seed
        }
        val run = QueueFit.Run(tags, referrers, view.artistSkips, view.positiveArtists)

        // Each candidate once, with the newest seed that lists it, nothing the queue or the run
        // already has. By id: two lists can hold the very same song, and one song is one pick.
        val listed = input.sources.flatMap { source -> source.songs.map { it to source.seed } }
        val fresh = RadioPage.fresh(listed.map { it.first }, input.queue, view.reached, view.reachedVersions).mapTo(HashSet()) { it.id }
        val once = HashSet<String>()
        val usable = listed.filter { (song, _) -> song.id in fresh && once.add(song.id) && usable(song, input, tags, run) }
        val picks = usable
            .map { (song, seed) -> QueuePlanner.Pick(song.id, seed, artistOf(song), QueueFit.score(song, taste, run).fit) }
            .sortedByDescending { it.fit }

        val slots = input.tail.mapIndexed { i, place -> slot(i, place, input, tags, run) }
        val inQueue = input.queue.mapTo(HashSet()) { it.id }
        val picksBySeed = view.picks.filterKeys { it in inQueue }.values.groupingBy { it }.eachCount()
        val liked = view.likePending
        val plan = QueuePlanner.plan(slots, input.head.map(::artistOf), picks, input.share, liked, picksBySeed)
        val budget = (slots.size * input.share).toInt()
        val asked = slots.count { !it.byHand && (it.unwanted != null || it.byWords || it.byScore != null) }
        return Result(
            kind = input.kind,
            plan = plan,
            slots = slots,
            songs = usable.associate { it.first.id to it.first },
            candidates = listed.size,
            fromPage = input.sources.filter { it.page }.sumOf { it.songs.size },
            usable = usable.size,
            asking = minOf(asked, budget) + (if (liked != null) QueuePlanner.LIKE_PICKS else 0),
        )
    }

    /** Whether [song] may be picked: see the class note. */
    fun usable(song: MediaMetadata, input: Input, tags: TagFit.Context, run: QueueFit.Run): Boolean {
        val view = input.view
        val taste = input.taste
        if (song.id in view.dismissed) return false
        if (song.artists.any { it.name.trim().lowercase() in view.turnedDown.artists }) return false
        if (view.turnedDown.songs.isNotEmpty() && SongVersions.baseTitle(song.title, song.artists.firstOrNull()?.name) in view.turnedDown.songs) return false
        if (AdaptiveQueue.verdict(song.title, tags, view.plainRun) < AdaptiveQueue.DROP_BELOW) return false
        if (SongTags.isPreview(song.title)) return false
        if (taste.banned(song)) return false
        if (song.id in taste.goneIds) return false
        if (input.offline && !input.playsOffline(song)) return false
        if (taste.justHeard(song)) return false
        val score = QueueFit.score(song, taste, run)
        if (score.habit >= SkipHabits.DISLIKE) return false
        // Nothing of its own against it from this sitting, or the next pass could choose its place
        // again. A milder habit of skipping its artist stays in its fit: in a long history most
        // artists have a skip somewhere, and leaving all of them out would leave few picks.
        if (score.oneSkip || score.tagFit < 0 || song.id in input.skippedRelated) return false
        return true
    }

    private fun slot(i: Int, place: Place, input: Input, tags: TagFit.Context, run: QueueFit.Run): QueuePlanner.Slot {
        val view = input.view
        val taste = input.taste
        val song = place.song
        val score = QueueFit.score(song, taste, run)
        val liked = taste.liked(song)
        val artists = song.artists.map { it.name.trim().lowercase() }
        val unwanted = when {
            place.byHand -> null
            taste.banned(song) -> QueuePlanner.Unwanted.BANNED
            // A song the listener liked goes only when banned.
            liked -> null
            artists.any { it in view.turnedDown.artists } -> QueuePlanner.Unwanted.ARTIST_TURNED_DOWN
            view.turnedDown.songs.isNotEmpty() &&
                SongVersions.baseTitle(song.title, song.artists.firstOrNull()?.name) in view.turnedDown.songs -> QueuePlanner.Unwanted.SONG_TURNED_DOWN
            SongTags.isPreview(song.title) -> QueuePlanner.Unwanted.PREVIEW
            score.habit >= SkipHabits.DISLIKE -> QueuePlanner.Unwanted.LASTING_DISLIKE
            else -> null
        }
        val open = !place.byHand && unwanted == null && !liked
        val byWords = open && AdaptiveQueue.verdict(song.title, tags, view.plainRun) < AdaptiveQueue.DROP_BELOW
        // Chosen again for its score only in the near part, and only with a reason of its own: a
        // queue that re-chose its next five songs after every skip would drift into the library.
        val byScore = if (!open || byWords || !input.byScore || i >= TagFit.WINDOW) null else when {
            score.oneSkip -> "skipped artist"
            // Not a pick of this run: it was the best there was with this same habit counted.
            score.habit > 0 && song.id !in view.picks -> "often skipped"
            score.tagFit < 0 -> "breaks the run"
            song.id in input.skippedRelated -> "like the song skipped"
            else -> null
        }
        return QueuePlanner.Slot(place.index, song.id, artists.firstOrNull(), score.fit, place.byHand, unwanted, byWords, byScore)
    }

    private fun artistOf(song: MediaMetadata): String? = song.artists.firstOrNull()?.name?.trim()?.lowercase()

    // ---- The log: one line per decision, ids only, never titles. Debug and Preview builds keep
    // Log.d, a release strips it (proguard-rules.pro).

    private fun fit(x: Double) = String.format(Locale.ROOT, "%.2f", x)

    /** Why a place was to change, as the log says it. */
    fun why(slot: QueuePlanner.Slot): String =
        slot.unwanted?.why ?: if (slot.byWords) "out of place by its words" else slot.byScore ?: "unchanged"

    fun summaryLine(r: Result, replaced: Int, dropped: Int, added: Int, kept: Int, note: AdaptiveNote.Note?): String =
        "Adaptive queue: pass ${r.kind.code}, ${r.slots.size} ahead, ${minOf(TagFit.WINDOW, r.slots.size)} near, " +
            "${r.candidates} candidates (${r.candidates - r.fromPage} local, ${r.fromPage} page), ${r.usable} usable: " +
            "replaced $replaced, dropped $dropped, added $added, kept $kept, note ${note?.kind?.code ?: "unchanged"}"

    fun replacedLine(slot: QueuePlanner.Slot, pick: QueuePlanner.Pick, heardBefore: Boolean): String =
        "Adaptive queue: replaced #${slot.index} ${slot.id} (${why(slot)}, fit ${fit(slot.fit)}) with ${pick.id} " +
            "(fit ${fit(pick.fit)}, from ${pick.seed}, ${if (heardBefore) "heard before" else "new to this listener"})"

    fun droppedLine(slot: QueuePlanner.Slot): String =
        "Adaptive queue: dropped #${slot.index} ${slot.id} (${why(slot)}), no pick good enough"

    fun keptLine(slot: QueuePlanner.Slot): String =
        "Adaptive queue: kept #${slot.index} ${slot.id} (${why(slot)}), no pick good enough"

    fun addedLine(pick: QueuePlanner.Pick, at: Int, heardBefore: Boolean): String =
        "Adaptive queue: added ${pick.id} at #$at (like ${pick.seed}, fit ${fit(pick.fit)}, ${if (heardBefore) "heard before" else "new to this listener"})"

    /** A run brought back after the app was closed. */
    fun restoredLine(r: AdaptiveQueue.Heard.Restored): String =
        "Adaptive queue: restored ${r.key}: ${r.reached} reached, ${r.picks} picks" +
            if (r.evidence) "" else ", older than 90 minutes so skips and keeps left out"

    /**
     * How the song just left was heard, for counting on a phone: "heard <id> skipped at 23%", or a
     * pick's own line, "pick <id> skipped at 12%", to set against the radio's own songs.
     */
    fun leftLine(id: String, how: AdaptiveQueue.Leave, leftAtMs: Long?, durationSec: Int, pick: Boolean): String {
        val at = if (leftAtMs != null && leftAtMs >= 0 && durationSec > 0) " at ${(leftAtMs * 100 / (durationSec * 1000L)).coerceIn(0, 100)}%" else ""
        val word = when (how) {
            AdaptiveQueue.Leave.RAN_OUT -> "ran out"
            AdaptiveQueue.Leave.KEPT -> "kept$at"
            AdaptiveQueue.Leave.SKIPPED -> "skipped$at"
            AdaptiveQueue.Leave.NEITHER -> "neither$at"
        }
        return "Adaptive queue: ${if (pick) "pick" else "heard"} $id $word"
    }
}
