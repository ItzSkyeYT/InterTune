/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.utils

import java.io.IOException

/**
 * Which client a song is asked of first, kept out of YTPlayerUtils so it can be tested without a
 * network.
 *
 * The chain is written in the order its clients are preferred, and its first client has been
 * refused on every song for weeks: ANDROID_VR answered "Sign in to confirm you're not a bot" 16
 * times out of 16 on one phone on 7 Oct 2026, signed in, and on every song of a factory-fresh one
 * the day after, signed out, and VISIONOS then served each of them. So every song began with a
 * request YouTube refused, which cannot help what it makes of the connection, and waited for it.
 *
 * So the client that last gave a working stream is asked first, and the rest of the chain only
 * when it gives none. Nothing is given up for it: a song the first client cannot serve is asked of
 * every other client, in the order written, as before.
 *
 * The order written is still the one preferred, so a refused client gets its place back after
 * [RETRY_REFUSED_MS]: one song is asked in the order written, and the client is either refused
 * again, which costs that song what every song used to cost, or serves, and is first from then on.
 */
object StreamOrder {
    /**
     * How long a client that gave no working stream is passed over before it is asked in its
     * written place again.
     *
     * Six hours, as FamilyChoice.REMEMBER_MS for the same kind of knowledge. Each try that is
     * refused is one more request for YouTube to count against the connection, so the shorter this
     * is the less is gained: at six hours a day of listening sends four of them where it sent one
     * for every song. Waiting loses nothing, because the client asked in the meantime serves, and
     * the day it stops serving the whole chain is asked at once whatever this says. All a longer
     * wait puts off is the return to the order as written.
     */
    const val RETRY_REFUSED_MS = 6 * 60 * 60 * 1000L

    /**
     * What is known of the chain's clients, by the names the trail gives them.
     *
     * [worked] is the client that last gave a stream that passed its check. [refusedAt] is when
     * each client was last asked and gave none, in wall clock milliseconds: this is kept between
     * launches, and elapsed realtime starts again when the phone does.
     *
     * When [worked] last served is not kept, because nothing would read it. What makes it worth
     * asking first is that the clients written before it were refused not long ago, and what ends
     * that is one of those refusals growing old or [worked] failing, either of which asks the
     * chain.
     */
    data class Memory(val worked: String? = null, val refusedAt: Map<String, Long> = emptyMap())

    /** One client's part in a walk: whether its stream passed the check, and whether it was asked as the account. */
    class Asked(val client: String, val worked: Boolean, val asAccount: Boolean = false)

    /**
     * The order to ask [chain] in: as written, or with the client that worked last moved to the
     * front. Only the first place is ever given away, and no client is left out.
     *
     * As written unless every client written before the one that worked was refused less than
     * [RETRY_REFUSED_MS] ago. That covers the cases that must not take the short way without
     * naming them: a client whose wait is over, a client new in this version, and the account's
     * client when it stands ahead (asked as the account always, or while YouTube refuses the
     * connection), of which nothing is ever remembered. A refusal dated after [now] is not
     * believed: the clock was wrong then or has been set back since, and believing it would hold
     * the client back until the clock caught up.
     *
     * [hasVisitorData] false asks as written too. VISIONOS is refused without a visitorData, and
     * the main client's answer is where the app takes one from when it has none (issue #17).
     */
    fun order(chain: List<String>, memory: Memory, now: Long, hasVisitorData: Boolean = true): List<String> {
        val worked = memory.worked?.takeIf { it in chain } ?: return chain
        if (!hasVisitorData) return chain
        val resting = chain.takeWhile { it != worked }.all { client ->
            memory.refusedAt[client]?.let { now - it in 0 until RETRY_REFUSED_MS } == true
        }
        return if (resting) listOf(worked) + (chain - worked) else chain
    }

    /**
     * What is known once [asked] have answered, in the order they were asked.
     *
     * A client that gave no working stream is noted as refused whatever its reason. The bot check
     * cannot be told from a song that is simply unavailable in every language (the main client is
     * asked in the app's own), and being wrong only means that a client which might have served
     * waits [RETRY_REFUSED_MS] behind one that does serve. When no client served, [Memory.worked]
     * stays: it is still the best guess, and asking it first costs nothing the walk would not have.
     *
     * Nothing is remembered of a client asked as the account. Signed in, the account is asked when
     * the anonymous clients refuse, which is what one age gated song does, and a win remembered
     * there would send every song after it as the account.
     */
    fun remember(memory: Memory, asked: List<Asked>, now: Long): Memory {
        var worked = memory.worked
        val refusedAt = memory.refusedAt.toMutableMap()
        for (step in asked) {
            if (step.asAccount) continue
            if (step.worked) {
                worked = step.client
                refusedAt.remove(step.client)
            } else {
                refusedAt[step.client] = now
            }
        }
        return Memory(worked, refusedAt)
    }

    private val CLIENT_NAME = Regex("""[A-Za-z0-9_]{1,40}""")

    /** [memory] as one line for the settings: the client that worked, then "name=time" for each refusal. */
    fun encode(memory: Memory): String {
        if (memory.worked == null && memory.refusedAt.isEmpty()) return ""
        val refusals = memory.refusedAt.toSortedMap().map { (client, at) -> "$client=$at" }
        return (listOf(memory.worked.orEmpty()) + refusals).joinToString(";")
    }

    /** What [encode] wrote. A part that does not read as a client's name or a time is dropped, not guessed at. */
    fun decode(stored: String?): Memory {
        val fields = stored?.split(';') ?: return Memory()
        val refusedAt = fields.drop(1).mapNotNull { field ->
            val client = field.substringBefore('=', "").takeIf { CLIENT_NAME.matches(it) }
            val at = field.substringAfter('=', "").toLongOrNull()
            if (client != null && at != null) client to at else null
        }.toMap()
        return Memory(fields.first().takeIf { CLIENT_NAME.matches(it) }, refusedAt)
    }

    /**
     * Whether a /player request failed without an answer from YouTube: no connection, no address
     * for the name, a timeout.
     *
     * The main client's request failing has always ended the song at once, and offline that is
     * what brings the error quickly, a request being allowed thirty seconds. Whichever client is
     * asked first inherits that, for this kind of failure only. A request YouTube did answer, with
     * a status or a body the app cannot use, says something about that client and nothing about
     * the connection, so the rest of the chain is still asked. Nor is a client the network never
     * reached remembered as refused.
     */
    fun networkDidNotAnswer(failure: Throwable?): Boolean = failure is IOException
}
