/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.playback

import com.dd3boh.outertune.utils.WhyStopped
import com.zionhuang.innertube.models.WatchEndpoint
import com.zionhuang.innertube.pages.NextResult
import io.ktor.client.plugins.ResponseException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withTimeoutOrNull
import java.util.EnumMap

/**
 * How a radio asks YouTube for its songs, and what it tries before giving up when the usual way is
 * refused (Unreleased.RADIO_FALLBACK), the way the stream walk tries its clients (YTPlayerUtils).
 *
 * A radio used to be one request, YouTube Music's watch-next as the app always asked it, and a
 * refusal left the song alone in its queue: Val's radio on 4 Nov 2026, refused with 401 at every
 * start because the app named a visitor's account id; his own try through Orbot the same day.
 * Through three Tor exits that day (experiments/features/radio-fallback/probe) YouTube Music
 * answered its radio with the first song alone or 403, and other doors answered:
 *
 *  1. [Rung.MUSIC]: YouTube Music, as the app always asked, as the account when there is one.
 *  2. [Rung.MUSIC_AS_VISITOR]: the same as a visitor (no cookie, no account named) with a
 *     visitorData YouTube issues now to this connection: a sign-in YouTube no longer takes, an
 *     account id sent with no cookie, a visitorData YouTube distrusts.
 *  3. [Rung.WEB_MIX]: YouTube's own mix of the song, from its web client at www.youtube.com, the
 *     way NewPipe asks for one: 25 songs from all three Tor exits.
 *  4. [Rung.VR_MIX]: the same from its VR client, for YouTube Music's radio list: two in three.
 *
 * Only an answer with a song besides the one the radio is of counts: YouTube Music's answer through
 * Tor held that song alone (twice, once for the page and once for its automix). When none counts,
 * the first answer that held the song alone is given back for its link to the related songs
 * (QueueStart fills a radio of one from them), or else the first failure.
 *
 * Kept cheap. Each step but the first has a time of its own ([Wire.capMs]); the first is asked as
 * it always was. A step YouTube refused (401, 403, 429) is not asked again for [REFUSED_MS], so a
 * radio's top-ups go straight to the step that answered last, and when every step was refused they
 * ask nothing until then and the radio goes on from the phone (LocalRadio). No answer at all is the
 * network's, which every step shares, and ends the walk at once. The visitorData that got an
 * answer is kept for the next walks, and a mint that failed is not tried again for [REFUSED_MS].
 * [forget] starts over: another network, another sign-in.
 *
 * The YouTube mixes are videos, with no mark of what is explicit or a music video, so the steps that
 * ask for them are left out while either content switch is on ([Wire.mixesAllowed]), and they stand
 * in only for the radio of a song ([isSongRadio]), never for a list that starts on one.
 *
 * No Android in here, so the order is tested with stand-ins for the network (RadioLadderTest).
 */
internal class RadioLadder(
    private val wire: Wire,
    private val clock: () -> Long,
) {
    enum class Rung(val label: String) {
        MUSIC("YouTube Music"),
        MUSIC_AS_VISITOR("YouTube Music as a visitor"),
        WEB_MIX("YouTube's mix (WEB)"),
        VR_MIX("YouTube's mix (ANDROID_VR)"),
    }

    /** What the steps ask of the network. Each call fails with what the request failed with; none throws. */
    interface Wire {
        /** YouTube Music's watch-next, as the app always asked it (YouTube.next). */
        suspend fun music(endpoint: WatchEndpoint, continuation: String?): Result<NextResult>

        /** The same as a visitor, [visitorData] the visitor's id, null for a new one (YouTube.nextAsVisitor). */
        suspend fun musicAsVisitor(endpoint: WatchEndpoint, visitorData: String?): Result<NextResult>

        /** A visitorData YouTube issues now to this connection, or null (YouTube.issuedVisitorData). */
        suspend fun issuedVisitorData(videoId: String): String?

        /** YouTube's own mix of [videoId], asked by the client [rung] names (YouTube.mix). */
        suspend fun mix(videoId: String, rung: Rung, visitorData: String?): Result<NextResult>

        /** False while a content switch is on, which the mixes' songs cannot be held to. */
        val mixesAllowed: Boolean

        /** How long a step after the first may take. */
        val capMs: Long
    }

    /**
     * [result] the radio, [from] the step it came from; or, when none answered with more than the
     * song, [result] that song alone (or null) and [failure] the first failure. [musicFailure] is
     * what YouTube Music's own request failed with, when it was asked and failed, whatever came
     * after: a sign-in YouTube refused is still the note on Home's to tell (WhyStopped).
     */
    class Answer(
        val result: NextResult?,
        val from: Rung?,
        val musicFailure: Throwable?,
        val failure: Throwable?,
        val trail: List<String>,
    )

    /** Every step was refused lately, or none could be asked: nothing was asked of YouTube. */
    class Refused : IllegalStateException("YouTube refused this radio")

    /** A step that took longer than [Wire.capMs]. */
    class TimedOut(rung: Rung, ms: Long) : IllegalStateException("${rung.label} gave no answer in $ms ms")

    /** A refusal made by the debug switch (RadioAnswers.Test), taken as YouTube's own [status]. */
    class TestRefusal(val status: Int) : IllegalStateException("HTTP $status, made by the debug switch")

    private val refusedUntil = EnumMap<Rung, Long>(Rung::class.java)
    @Volatile private var keptVisitor: String? = null
    @Volatile private var mintFailedAt: Long? = null

    /** Another network or another sign-in: what was refused here may well be answered there. */
    fun forget() {
        synchronized(refusedUntil) { refusedUntil.clear() }
        keptVisitor = null
        mintFailedAt = null
    }

    private fun refused(rung: Rung, now: Long): Boolean = synchronized(refusedUntil) {
        val until = refusedUntil[rung] ?: return false
        if (now < until) true else {
            refusedUntil.remove(rung)
            false
        }
    }

    private fun noteRefused(rung: Rung, now: Long) = synchronized(refusedUntil) { refusedUntil[rung] = now + REFUSED_MS }

    suspend fun radio(endpoint: WatchEndpoint, continuation: String? = null): Answer {
        val seed = endpoint.videoId
        val trail = ArrayList<String>()
        var alone: NextResult? = null
        var firstFailure: Throwable? = null
        var musicFailure: Throwable? = null

        /** A radio: a song besides [seed]; without a seed, any song. */
        fun holdsMore(r: NextResult) = r.items.any { it.id != seed }

        suspend fun step(rung: Rung, capped: Boolean, ask: suspend () -> Result<NextResult>): NextResult? {
            val now = clock()
            if (refused(rung, now)) {
                trail += "${rung.label}: refused lately, not asked"
                return null
            }
            val result = if (!capped) ask() else {
                val cap = wire.capMs
                withTimeoutOrNull(cap) { ask() } ?: Result.failure(TimedOut(rung, cap))
            }
            val answer = result.getOrNull()
            if (answer != null && holdsMore(answer)) {
                trail += "${rung.label}: ${answer.items.size} songs"
                return answer
            }
            val failure = result.exceptionOrNull()
            // A request that swallowed the end of its own wait comes back as one; this walk's own
            // cancellation, a tap elsewhere or the service going, is passed on.
            if (failure is CancellationException) currentCoroutineContext().ensureActive()
            if (answer != null) {
                if (alone == null) alone = answer
                trail += "${rung.label}: the song alone"
            } else if (failure != null) {
                if (firstFailure == null) firstFailure = failure
                if (rung == Rung.MUSIC) musicFailure = failure
                trail += "${rung.label}: ${describe(failure)}"
                if (isRefusal(failure)) noteRefused(rung, clock())
            }
            return null
        }

        fun answered(result: NextResult, rung: Rung) = Answer(result, rung, musicFailure, null, trail)
        fun none() = Answer(alone, null, musicFailure, if (alone != null) null else firstFailure ?: Refused(), trail)

        step(Rung.MUSIC, capped = false) { wire.music(endpoint, continuation) }?.let { return answered(it, Rung.MUSIC) }
        // No answer at all is the network's: every other step would wait for the same nothing.
        if (musicFailure?.let { WhyStopped.kindOf(it) } == WhyStopped.Kind.NO_ANSWER) return none()
        // A continuation is YouTube Music's own, and means nothing to anyone else.
        if (continuation != null) return none()

        // The visitorData first, apart from the request, so that each has its own time.
        val visitor = if (refused(Rung.MUSIC_AS_VISITOR, clock())) null else keptVisitor ?: seed?.let { mint(it) }
        step(Rung.MUSIC_AS_VISITOR, capped = true) {
            wire.musicAsVisitor(endpoint, visitor).onSuccess { r -> if (holdsMore(r) && visitor != null) keptVisitor = visitor }
        }?.let { return answered(it, Rung.MUSIC_AS_VISITOR) }

        // A mix of the song stands in for its radio only: never for a playlist or an album that
        // happens to start on a song, nor for YouTube Music's own mixes, which are lists.
        if (seed == null || !isSongRadio(endpoint)) return none()
        if (!wire.mixesAllowed) {
            trail += "YouTube's mixes: left out, a content switch is on"
            return none()
        }
        for (rung in listOf(Rung.WEB_MIX, Rung.VR_MIX)) {
            step(rung, capped = true) { wire.mix(seed, rung, keptVisitor) }?.let { return answered(it, rung) }
        }
        return none()
    }

    /** A visitorData issued now, or null; not asked again for a while after it failed. */
    private suspend fun mint(videoId: String): String? {
        val failedAt = mintFailedAt
        if (failedAt != null && clock() - failedAt < REFUSED_MS) return null
        val minted = withTimeoutOrNull(wire.capMs) { wire.issuedVisitorData(videoId) }
        if (minted == null) mintFailedAt = clock()
        return minted
    }

    companion object {
        /** How long a step YouTube refused is passed over: about two radios' worth of top-ups. */
        const val REFUSED_MS = 10 * 60 * 1000L

        /** A radio of one song: its id alone, or with YouTube Music's radio list of it ("RDAMVM" + the id). */
        fun isSongRadio(endpoint: WatchEndpoint): Boolean =
            endpoint.videoId != null && endpoint.playlistId.let { it == null || it.startsWith("RDAMVM") }

        /** YouTube said no, as opposed to not answering, failing, or answering with nothing usable. */
        fun isRefusal(failure: Throwable): Boolean {
            var t: Throwable? = failure
            var depth = 0
            while (t != null && depth++ < 8) {
                if (t is ResponseException) return t.response.status.value in REFUSALS
                if (t is TestRefusal) return t.status in REFUSALS
                t = t.cause
            }
            return false
        }

        private val REFUSALS = setOf(401, 403, 429)

        /** A failure in a few words, without addresses, for the log and the report. */
        fun describe(failure: Throwable): String {
            var t: Throwable? = failure
            var depth = 0
            while (t != null && depth++ < 8) {
                if (t is ResponseException) return "HTTP ${t.response.status.value}"
                if (t is TestRefusal) return "HTTP ${t.status} (debug switch)"
                t = t.cause
            }
            return failure::class.java.simpleName.ifEmpty { "failed" }
        }
    }
}
