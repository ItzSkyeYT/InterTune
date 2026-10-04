/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.engine

import com.dd3boh.outertune.constants.EndReason

/**
 * The engine's view of the world: plain rows, no Android and no Room, so the JVM tests run the
 * same code the phone runs. The loader on the Android side fills these from the database.
 */
data class ListenRow(
    val songId: String,
    val startedAt: Long,
    val endedAt: Long,
    val playedMs: Long,
    /** -1 when the length was never learned. */
    val durationMs: Long,
    /** A [com.dd3boh.outertune.constants.EndReason] code. */
    val endReason: Int,
    /** A [com.dd3boh.outertune.constants.PlayOrigin] code. */
    val origin: Int,
    val autoplayDepth: Int,
    val sessionId: Long,
    val tzOffsetMin: Int,
    val learn: Boolean = true,
    val runId: Long = 0,
    val queueId: Long = 0,
    /** The Quick picks impression this play came from, when it was a tap on a card. */
    val impressionId: Long? = null,
    /** The [ContextChip] on when the play started. */
    val contextChip: Int = 0,
    /** The listen's own row id, so a grade can record which play it came from. */
    val id: Long = 0,
    /** The row this one carries on: the same play, stopped or failed and picked up where it stood. */
    val continuesListenId: Long? = null,
)

/**
 * Which listens the engine reads, in one place, so the loader, the learning loop and the JVM
 * trials all leave out the same rows.
 *
 * A play that failed (it stopped on a playback error and was left that way: a stream that died, an
 * expired url, a file that was gone) teaches the recommendations nothing. It is not the listener
 * turning the song down, so it is never counted against the song or a card, and it is not the
 * listener choosing it either, however much of it played before it died. History, Last.fm and
 * YouTube history are not the engine and keep their own rule, the share heard.
 */
object EngineListens {
    fun failed(l: ListenRow): Boolean = l.endReason == EndReason.ERROR

    /**
     * The log as a build reads it at [now]: failed plays left out, and a row still open, the song
     * playing now, ending now.
     */
    fun forBuild(rows: List<ListenRow>, now: Long): List<ListenRow> = rows.mapNotNull { l ->
        when {
            failed(l) -> null
            l.endReason == EndReason.OPEN -> l.copy(endedAt = now)
            else -> l
        }
    }

    /**
     * The log as grading reads it at [now]: every row, a row still open ending now. Failed plays
     * stay, because a card whose play failed has to be settled (at no weight, see [Grading]) rather
     * than graded as a card nobody played.
     */
    fun forGrading(rows: List<ListenRow>, now: Long): List<ListenRow> =
        rows.map { if (it.endReason == EndReason.OPEN) it.copy(endedAt = now) else it }

    /**
     * The listener's own picks a built row is scored against: chosen rather than autoplayed, meant
     * to teach, closed, and not failed.
     */
    fun picks(rows: List<ListenRow>): List<ListenRow> =
        rows.filter { it.learn && it.autoplayDepth == 0 && it.endReason != EndReason.OPEN && !failed(it) }
}

data class SongRow(
    val id: String,
    val title: String,
    val artistId: String?,
    val artistName: String?,
    val liked: Boolean = false,
    /** The like's instant, already corrected for how Converters stores it; null when not liked. */
    val likedAt: Long? = null,
    val inLibrary: Boolean = false,
    val isLocal: Boolean = false,
    /** False for a local file that is gone, or anything that cannot play right now. */
    val playable: Boolean = true,
)

/** Seed to candidate, with the [Provenance] bits of the lists that hold it: YouTube's, Last.fm's or both. */
data class Edge(val seedId: String, val songId: String, val sources: Int = Provenance.YOUTUBE)

/** YouTube's word that two ids are performances of one song. */
data class VersionLink(val songId: String, val versionId: String)

/** A card that was seen and not played, for the impression penalty. */
data class SeenCard(val songId: String, val seenAt: Long)

/** An exclusion in force: kind 1 song, 2 artist. */
data class ExclusionRow(val kind: Int, val targetId: String)

/** The seeds of a row built recently, so the next build drifts away from them. */
data class PastSeeds(val builtAt: Long, val seedIds: List<String>)

data class EngineInput(
    val now: Long,
    val songs: Map<String, SongRow>,
    val listens: List<ListenRow>,
    val edges: List<Edge>,
    val versionLinks: List<VersionLink> = emptyList(),
    val seen: List<SeenCard> = emptyList(),
    val exclusions: List<ExclusionRow> = emptyList(),
    val pastSeeds: List<PastSeeds> = emptyList(),
    /** The day-part bucket the row is built for, 0 to 7; see [dayPartBucket]. */
    val bucket: Int = 0,
    /** The device's offset from UTC in minutes now, for local midnight. */
    val tzOffsetMin: Int = 0,
    /** Songs never to offer as candidates, whatever the lanes say (the Tidy pass handles the rest). */
    val banned: Set<String> = emptySet(),
    /** Songs the listener turned down as seeds ("Not this one"); they may still be cards. */
    val notSeeds: Set<String> = emptySet(),
    /** The [ContextChip] the row is built for. */
    val chip: Int = ContextChip.AUTO,
)

/**
 * The declared context: a chip above the engine row, kept until changed, stamped on every listen
 * that starts while it is on. Auto is the ordinary row; Discover raises the explore share;
 * Favourites seeds from likes only; Focus, Chill and Party learn from the listener alone, from
 * what was played while each was on.
 */
object ContextChip {
    const val AUTO = 0
    const val DISCOVER = 1
    const val FAVOURITES = 2
    const val FOCUS = 3
    const val CHILL = 4
    const val PARTY = 5
    val MOODS = setOf(FOCUS, CHILL, PARTY)
    /**
     * Below this many listens tagged with a mood, the chip leans on [ChipPrior] instead of on
     * what it has learned.
     *
     * Was twenty, which is a long time to press Chill and be handed the same row. The prior now
     * covers the gap, so this only needs to be enough listens to be worth learning from.
     */
    const val MIN_TAGGED = 8
}

/** How a card got into the row. Appended in order: the code stored on an impression is ordinal + 1. */
enum class Lane {
    RELATED, ARTIST, REDISCOVER, EXPLORE, AGAIN;

    companion object {
        /**
         * The lane a stored impression came from, or null when it was not the engine's card.
         *
         * Impressions keep the lane as `ordinal + 1`, so zero means no lane. The two readers of
         * that column both wrote the decode by hand, back when there were four lanes, and both
         * still said `in 1..4`. AGAIN is the fifth, so every AGAIN card has been silently dropped
         * from the learner since the day that lane was added, and b_again has sat at exactly zero
         * ever since, which looked like an opinion and was an absence.
         */
        fun ofCode(code: Int): Lane? = entries.getOrNull(code - 1)
    }
}

data class Card(
    val songId: String,
    val lane: Lane,
    /** The ranking score, `b + Σ w x`, without position. */
    val z: Double,
    /** The predicted chance of a play in its slot. */
    val p: Double,
    val features: DoubleArray,
    /** Feature names of the two largest positive terms, or a placement reason. */
    val reasons: List<String>,
    /** The seed that referred it, when the related lane placed it. */
    val seedId: String? = null,
    /** True when temperature sampling rather than rank placed it. */
    val sampled: Boolean = false,
    /** [Provenance] bits of a related or explore card; 0 for every other card. */
    val sources: Int = 0,
)

data class BuiltRow(
    val cards: List<Card>,
    val seeds: List<String>,
    /** Survivors beyond the row, best first, for replacements between builds. */
    val pool: List<Card>,
    val quotas: Map<Lane, Int>,
    /** The Last.fm share this build aimed its similar songs at, or null when it drew on one source. */
    val lastFmShare: Double? = null,
    /**
     * The lean this build applied: AUTO when none is set, when a chip or New songs only set it
     * aside, for Never heard under New songs only, where the whole row is new and no lane leads,
     * and when the lean had too little to give and the row is Auto's (see [gaveWay]).
     */
    val lean: Lean = Lean.AUTO,
    /** How many of the lead lane's cards the build placed, for the heading's "only N right now". */
    val leanPlaced: Int = 0,
    /** Cards one artist may hold in the lead lane: two, up to four for someone with few returning artists. */
    val leadArtistCap: Int = 2,
    /** What each of the lead lane's impressions weighs in learning, see [LeanWeighting]. One with no lean. */
    val leadWeight: Double = 1.0,
    /**
     * Set when the settings asked for a lean and the build gave Auto's row instead. Every other
     * field is then Auto's, [lean] and [leadWeight] included, so the row is tidied, recorded and
     * graded as the Auto row it is; this only tells the heading what to say.
     */
    val gaveWay: LeanGaveWay? = null,
) {
    /** The lean the build was for: the one it followed, or the one it gave way on. */
    val asked: Lean get() = gaveWay?.lean ?: lean
}

/**
 * A lean the build could not follow. Either its lane had no song to give ([nothing]), or the leaned
 * row came out with fewer cards than Quick picks needs to show the engine's row
 * ([EngineParams.minCards]) where Auto's has enough.
 */
data class LeanGaveWay(val lean: Lean, val nothing: Boolean)

/**
 * What Best recommendations leans toward, a standing choice kept in the preferences by name.
 *
 * Auto is the ordinary row. Each other value gives one existing lane most of the row and the
 * whole first column, narrowed to what its label promises, and leaves the rest to the usual mix:
 * Never heard (explore), Your artists (artist), Forgotten (rediscover), Playing now (related). The
 * code is what row_build stores, never the ordinal, as with [Lane] and the play origins.
 */
enum class Lean(val code: Int, val lane: Lane?) {
    AUTO(0, null),
    NEW(1, Lane.EXPLORE),
    ARTIST(2, Lane.ARTIST),
    FORGOTTEN(3, Lane.REDISCOVER),
    SIMILAR(4, Lane.RELATED);

    companion object {
        fun ofCode(code: Int): Lean = entries.firstOrNull { it.code == code } ?: AUTO
        fun ofName(name: String?): Lean = entries.firstOrNull { it.name == name } ?: AUTO

        /**
         * The lean a build follows. A chip or New songs only is a request for right now and wins
         * over the standing choice, except where following it would make the row less of what the
         * lean promises: under Never heard, Discover and New songs only keep the lean and narrow
         * it. A mood under Playing now is a request to break the run the lean follows, so the
         * lean is set aside. The Discover something new row never leans.
         */
        fun applied(stored: Lean, chip: Int, newOnly: Boolean, neverPlayed: Boolean): Lean = when {
            neverPlayed || stored == AUTO -> AUTO
            newOnly -> if (stored == NEW) NEW else AUTO
            chip == ContextChip.FAVOURITES -> AUTO
            chip == ContextChip.DISCOVER -> if (stored == NEW) NEW else AUTO
            chip in ContextChip.MOODS && stored == SIMILAR -> AUTO
            else -> stored
        }
    }
}

/** Weekday or weekend, times night 0 to 5, morning 6 to 11, afternoon 12 to 17, evening 18 to 23. */
fun dayPartBucket(startedAt: Long, tzOffsetMin: Int): Int {
    val localMs = startedAt + tzOffsetMin * 60_000L
    val days = Math.floorDiv(localMs, 86_400_000L)
    val hour = (Math.floorMod(localMs, 86_400_000L) / 3_600_000L).toInt()
    // 1970-01-01 was a Thursday: day 0 -> Thursday, so (days + 3) mod 7 gives Monday = 0.
    val weekday = Math.floorMod(days + 3, 7L).toInt()
    val weekend = weekday >= 5
    return (if (weekend) 4 else 0) + hour / 6
}
