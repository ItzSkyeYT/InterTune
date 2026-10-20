/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.utils

import java.util.Locale

/**
 * The lines the log gets about how a song was found and kept playing, written here so that a test
 * can read them. No Android in here.
 *
 * A slow start could not be explained from the log: no step carried its time, and a check that
 * hung said nothing until its second try failed (his phone, 20 Oct 2026, 11 s on the first song
 * with nothing written between two lines). So each step says how long it took, and every decision
 * the player makes on its own says what it did and why.
 *
 * Nothing here takes an address. A stream url names the listener's own address, and logcat is
 * pasted into issues as it is: see [StreamCheck.urlForLog]. Every line is made of a client's name,
 * a status, a time and words of our own.
 */
object StepLog {
    /** A time a step took, to the hundredth: "0.21 s". */
    fun seconds(ms: Long): String = String.format(Locale.ROOT, "%.2f s", ms.coerceAtLeast(0) / 1000.0)

    /** A limit, to the tenth: "1.5 s". */
    fun limit(ms: Long): String = String.format(Locale.ROOT, "%.1f s", ms.coerceAtLeast(0) / 1000.0)

    /** Where in a song, as a player shows it: "1:23". */
    fun at(positionMs: Long): String {
        val s = positionMs.coerceAtLeast(0) / 1000
        return String.format(Locale.ROOT, "%d:%02d", s / 60, s % 60)
    }

    /** One client's /player step: "VISIONOS OK 0.21 s", or "VISIONOS no answer 5.00 s". */
    fun asked(client: String, status: String?, ms: Long): String = "$client ${status ?: "no answer"} ${seconds(ms)}"

    /** The n and signature step: "n 0.04 s", or "n no answer 8.00 s". */
    fun deciphered(ms: Long, answered: Boolean = true): String = if (answered) "n ${seconds(ms)}" else "n no answer ${seconds(ms)}"

    /** The stream check: "HEAD 200 0.31 s", or "HEAD no answer 3.00 s". */
    fun checked(status: Int?, ms: Long): String = "HEAD ${status ?: "no answer"} ${seconds(ms)}"

    /** One line a song: "[id] resolved in 1.32 s: VISIONOS OK 0.21 s, n 0.04 s, HEAD 200 0.31 s". */
    fun resolved(videoId: String, ms: Long, steps: List<String>): String =
        "[$videoId] resolved in ${seconds(ms)}: ${steps.joinToString(", ").ifEmpty { "nothing asked" }}"

    /** The same for a walk that found nothing. */
    fun unresolved(videoId: String, ms: Long, steps: List<String>): String =
        "[$videoId] no stream after ${seconds(ms)}: ${steps.joinToString(", ").ifEmpty { "nothing asked" }}"

    fun freshTry(videoId: String, client: String, step: String, afterMs: Long): String =
        "[$videoId] $client $step no answer in ${limit(afterMs)}, second try on a fresh connection"

    fun freshAnswered(videoId: String, client: String, step: String, ms: Long): String =
        "[$videoId] $client $step answered on the fresh connection in ${seconds(ms)}"

    fun alongside(videoId: String, client: String, next: String, afterMs: Long): String =
        "[$videoId] $client no answer in ${limit(afterMs)}, $next asked alongside"

    fun passedOver(videoId: String, client: String, afterMs: Long, next: String): String =
        "[$videoId] $client no answer in ${limit(afterMs)}, passed over: $next answered first"

    fun playedUnchecked(videoId: String, client: String, waitedMs: Long): String =
        "[$videoId] $client HEAD no answer in ${limit(waitedMs)}, played unchecked"

    /** [rest] is how long the route now rests, in words, when it was struck. */
    fun lateRefusal(videoId: String, client: String, status: Int, rest: String?): String =
        "[$videoId] $client late HEAD $status: address forgotten" + (rest?.let { ", $client rests $it" } ?: "")

    fun lateAnswer(videoId: String, client: String, status: Int): String = "[$videoId] $client late HEAD $status"

    fun slowNetwork(videoId: String, capMs: Long): String =
        "[$videoId] slow network: /player limit ${limit(capMs)} for the rest of this walk"

    fun timedOut(videoId: String, client: String, step: String, capMs: Long): String =
        "[$videoId] $client $step no answer in ${limit(capMs)}, the next client is asked"

    fun stallCut(videoId: String, silentMs: Long, positionMs: Long, n: Int, of: Int): String =
        "PLAYING: [$videoId] no byte for ${limit(silentMs)} at ${at(positionMs)}, connection cut ($n of $of)"

    fun stallRoute(videoId: String, silentMs: Long, positionMs: Long, without: String?, n: Int, of: Int): String =
        "PLAYING: [$videoId] no byte for ${limit(silentMs)} at ${at(positionMs)}, new route" +
            (without?.let { " without $it" } ?: "") + " ($n of $of)"

    fun loadFailed(videoId: String, why: String, without: String?): String =
        "PLAYING: [$videoId] $why, re-resolving" + (without?.let { " without $it" } ?: "")

    fun quietRetry(videoId: String, what: String, delayMs: Long, n: Int, of: Int): String =
        "PLAYING: [$videoId] $what, tried again quietly in ${(delayMs / 1000).coerceAtLeast(1)} s ($n of $of)"

    fun gaveUp(videoId: String, tries: Int, reason: String): String =
        "PLAYING: gave up on [$videoId] after $tries ${if (tries == 1) "try" else "tries"}: $reason"

    fun rests(client: String, until: String, strikes: Int, why: String): String =
        "routes: $client rests until $until ($strikes ${if (strikes == 1) "strike" else "strikes"}: $why)"

    fun remoteApplied(id: String, order: List<String>, disabled: List<String>): String =
        "routes: remote block $id applied" +
            (if (order.isNotEmpty()) ", order ${order.joinToString(" ")}" else "") +
            (if (disabled.isNotEmpty()) ", disabled ${disabled.joinToString(" ")}" else "")

    fun remoteIgnored(why: String): String = "routes: remote block ignored: $why"
}
