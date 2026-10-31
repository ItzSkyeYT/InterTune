/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.recognition

import com.dd3boh.outertune.fingerprint.SIGNATURE_SAMPLE_RATE_HZ

/**
 * One listen of the phone's own sound, from the first second of sound to the answer (31 Oct 2026):
 * [ClipListen] hears the clip and says when there is a window to ask about, Shazam is asked, and
 * [ClipEvidence] says whether the windows so far agree. The listen goes on until they do, or until
 * the clip is over and the best supported song, if there is one, is the answer.
 *
 * The question is [ask], so that this runs anywhere: the engine gives it Shazam, a test a script
 * of answers, and a probe on a desktop the real Shazam over a reel it was sent.
 *
 * Speed, which reels change (a song slowed to 0.8, sped up by a quarter): a window that was named
 * by nothing, or only by a guess that does not hold, is heard again at two of [SpeedRetry.RATES],
 * two others for the next window. A rate that names a window cleanly is the speed of this clip:
 * the windows after it are asked about at that speed first, which saves a request each and makes
 * their answers comparable.
 *
 * Shazam answers 429 after about a dozen requests in quick succession, so a listen asks at most
 * [MAX_REQUESTS] times and tries other speeds only for the first [RETRY_UNTIL] of them.
 */
internal class ClipSession(
    private val ask: suspend (ShortArray) -> RecognitionOutcome,
    private val log: (String) -> Unit = {},
    private val listen: ClipListen = ClipListen(),
    internal val evidence: ClipEvidence = ClipEvidence(),
) {

    /** How the listen ended. */
    sealed interface Verdict {
        /** The song, as [track] says it, from the window that began at [heardAtMs] (at [Recognised.heardAtRate] times its speed). */
        class Answer(val track: Recognised, val heardAtMs: Long, val why: String) : Verdict

        /**
         * No song. [silent]: nothing sounded at all. [refused]: Shazam never answered, and why. Neither:
         * it was asked, and what it named did not hold together.
         */
        class NoSong(val why: String, val silent: Boolean, val refused: String?) : Verdict
    }

    /** Shazam asked so far, speed guesses included. */
    var requests = 0
        private set

    private var misses = 0
    private var lockedRate: Double? = null
    private var lockedMisses = 0
    private var failures = 0
    private var failedInARow = 0
    private var answered = 0
    private var lastFailure: String? = null

    /** The rate the clip has proved to play at, or null. */
    val rate: Double? get() = lockedRate

    /**
     * One more piece of sound, [piece], that began at [startedAtMs]. Null while the listen goes on.
     * Asks Shazam about the window that falls due, so it can take a while; a cancelled coroutine
     * stops it.
     */
    suspend fun hear(piece: ShortArray, startedAtMs: Long): Verdict? {
        val question = listen.add(piece)
        evidence.loopSeconds = listen.loopSeconds?.takeIf { listen.loopHeld }
        if (question != null) {
            val endedAtMs = startedAtMs + piece.size * 1000L / SIGNATURE_SAMPLE_RATE_HZ
            val heardAtMs = endedAtMs - (question.seconds * 1000).toLong()
            askAbout(question, heardAtMs)
            evidence.agreed()?.let { return answer(it, "${it.chain.size} windows of different sound agree") }
            evidence.settled()?.let { return answer(it, "a clean match that ${it.windows} of ${evidence.windows} windows back up") }
        }
        if (over()) return finish()
        return null
    }

    private fun over(): Boolean = when {
        listen.over -> true
        requests >= MAX_REQUESTS -> true
        failedInARow >= MAX_FAILED_IN_A_ROW -> true
        // A clip that loops is all heard once the repeat has held for a turn. A song it names is the
        // answer then; with none yet, the repeat may be two bars of a long song, so not before LOOP_OVER_SECONDS.
        listen.loopHeld && (listen.heardSeconds >= LOOP_OVER_SECONDS || evidence.last() != null) -> true
        else -> false
    }

    /** The sound ran out while the listen was still going, as a recording standing in for a video does: what was heard is the answer. */
    fun end(why: String): Verdict = finish(why)

    private fun finish(ended: String? = null): Verdict {
        val why = when {
            ended != null -> "$ended after ${"%.0f".format(listen.heardSeconds)} s of sound"
            listen.over -> "the clip is over after ${"%.0f".format(listen.heardSeconds)} s of sound"
            requests >= MAX_REQUESTS -> "$requests requests asked"
            failedInARow >= MAX_FAILED_IN_A_ROW -> "Shazam did not answer ($lastFailure)"
            else -> "the clip is a loop of ${"%.1f".format(listen.loopSeconds)} s and all of it was heard"
        }
        log("Heard all there is to hear: $why; ${evidence.summary()}")
        evidence.last()?.let { return answer(it, why) }
        return Verdict.NoSong(
            why = why + "; " + evidence.summary(),
            silent = !listen.heardAnything,
            refused = lastFailure.takeIf { answered == 0 && failures > 0 },
        )
    }

    private fun answer(song: ClipEvidence.Song, why: String): Verdict.Answer {
        val sighting = song.best
        log(
            "Answer: '${sighting.track.title}' by '${sighting.track.artist}': $why " +
                    "(${evidence.summary()}; from ${"%.1f".format(sighting.fromS)} s at ${sighting.rate}x)"
        )
        return Verdict.Answer(sighting.track, sighting.heardAtMs, why)
    }

    /** Asks about one window, and again at other speeds when it is not named cleanly; notes what was named. */
    private suspend fun askAbout(question: ClipListen.Question, heardAtMs: Long) {
        val window = evidence.nextWindow()
        log(
            "Asking about ${"%.1f".format(question.seconds)} s of the phone's sound, from " +
                    "${"%.1f".format(question.fromSeconds)} s: ${question.why}, closest repeat ${listen.closestRepeat}" +
                    (listen.loopSeconds?.let { ", repeats every ${"%.1f".format(it)} s" + if (listen.loopHeld) " (held)" else "" } ?: "")
        )

        /** Notes a match as a sighting when it counts: as heard always, at another speed only when it holds. */
        fun take(rate: Double, outcome: RecognitionOutcome): ClipEvidence.Quality? {
            val track = (outcome as? RecognitionOutcome.Match)?.track ?: return null
            if (rate != 1.0 && !SpeedRetry.fits(track)) return null
            val sighting = ClipEvidence.Sighting(
                window, question.fromSeconds, question.seconds, rate, track.copy(heardAtRate = rate), heardAtMs,
            )
            evidence.note(sighting)
            return sighting.quality
        }

        val locked = lockedRate
        val first = askAt(question, locked ?: 1.0) ?: return
        log((if (locked == null) "As heard: " else "At ${"%.3f".format(locked)}x: ") + describe(first))
        if (first is RecognitionOutcome.Failed) {
            failed(first)
            return
        }
        succeeded()
        val quality = take(locked ?: 1.0, first)
        if (quality == ClipEvidence.Quality.CONVINCING) {
            lockedMisses = 0
            return
        }
        // Named within a hundredth of the recording's own speed: nothing to find at another one. The
        // speeds are for what is named by nothing, or only by a match that needed stretching to fit.
        if (quality == ClipEvidence.Quality.GOOD) return
        if (locked != null) {
            // The speed this clip plays at is known: a window it does not name cleanly is a quiet
            // stretch or talk over the song. Twice running, and the clip may have changed.
            if (++lockedMisses >= 2) {
                log("Not named at ${locked}x twice running, so not the speed of this clip any more")
                lockedRate = null
                lockedMisses = 0
            }
            return
        }
        if (requests > RETRY_UNTIL) return

        for (guess in SpeedRetry.ratesFor(misses)) {
            var rate = guess
            for (step in 0..1) {
                val again = askAt(question, rate) ?: return
                if (again is RecognitionOutcome.Failed) {
                    failed(again)
                    misses++
                    return
                }
                val match = (again as? RecognitionOutcome.Match)?.track
                log(
                    "Put back from ${"%.3f".format(rate)}x: " + when {
                        match == null -> again::class.simpleName
                        SpeedRetry.fits(match) -> "'${match.title}' (${ClipEvidence.qualityOf(match).name.lowercase()})"
                        else -> "'${match.title}' at skew ${match.timeSkew}/${match.frequencySkew}, not taken"
                    }
                )
                if (match == null) break
                if (SpeedRetry.fits(match)) {
                    if (take(rate, again) == ClipEvidence.Quality.CONVINCING) {
                        lockedRate = rate
                        lockedMisses = 0
                        misses++
                        log("This clip plays at ${"%.3f".format(rate)}x: asked about at that speed from now on")
                        return
                    }
                    break
                }
                if (step == 1) break
                rate = SpeedRetry.closer(rate, match) ?: break
            }
        }
        misses++
    }

    /** One request about [question] heard at [rate] times the recording's speed, or null when there are no more to spend. */
    private suspend fun askAt(question: ClipListen.Question, rate: Double): RecognitionOutcome? {
        if (requests >= MAX_REQUESTS) return null
        requests++
        val samples = if (rate == 1.0) question.samples else SpeedRetry.putBack(question.samples, rate)
        return ask(samples)
    }

    private fun describe(outcome: RecognitionOutcome): String = when (outcome) {
        is RecognitionOutcome.Match -> outcome.track.let {
            "'${it.title}' by '${it.artist}' key=${it.shazamKey} offset=${"%.1f".format(it.offsetSeconds)} " +
                    "skew=${"%.4f".format(it.timeSkew)}/${"%.4f".format(it.frequencySkew)} " +
                    ClipEvidence.qualityOf(it).name.lowercase()
        }
        is RecognitionOutcome.NoMatch -> "nothing known"
        is RecognitionOutcome.Failed -> "failed (${outcome.reason})"
    }

    /** A request that got no answer from Shazam. Silence is no failure: the question had no sound in it. */
    private fun failed(outcome: RecognitionOutcome.Failed) {
        log("Failed: ${outcome.reason}")
        if (outcome.reason == "silence") return
        failures++
        failedInARow++
        lastFailure = outcome.reason
    }

    private fun succeeded() {
        failedInARow = 0
        answered++
    }

    companion object {
        /** Requests in one listen, speed guesses included: Shazam refuses after about a dozen in quick succession. */
        const val MAX_REQUESTS = 16

        /** Other speeds are tried only while this few have been asked. */
        const val RETRY_UNTIL = 12

        /** Requests refused in a row that end the listen. */
        const val MAX_FAILED_IN_A_ROW = 3

        /** A clip that is a loop, with no song named yet, is not taken for heard through before this much sound. */
        const val LOOP_OVER_SECONDS = 24.0
    }
}
