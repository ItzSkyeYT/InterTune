/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.listenbrainz

import com.dd3boh.listenbrainz.ListenBrainz
import com.dd3boh.listenbrainz.ListenBrainzException
import com.dd3boh.listenbrainz.models.Listen

/**
 * One go at the plays waiting for ListenBrainz, as ListenBrainzWorker makes it: in batches, oldest
 * first, and what each answer means for the plays in it. Kept apart from the worker so it can be
 * tried against a made-up ListenBrainz.
 *
 * - Taken: off the outbox.
 * - Refused (a 400): at least one play in the batch is wrong as it is (a phone clock far off, a
 *   stray character), and sending it again gets the same answer. The batch goes again one by one
 *   to find it; a play refused on its own is dropped, its reason in the log.
 * - The token refused (a 401 or a 403): nothing more goes and everything not taken stays, for a
 *   token that works.
 * - Asked to wait (a 429), ListenBrainz in trouble (5xx), no connection: everything not taken
 *   stays for a later run. The client's gate waits out the pause ListenBrainz asked for anyway.
 */
object OutboxRun {

    /**
     * Plays in one request, at most. ListenBrainz takes up to 1,000 (MAX_LISTENS_PER_REQUEST), but a
     * refused batch is sent again one by one, a second apart, so a smaller one costs less to search.
     */
    const val BATCH = 100

    enum class Next {
        /** Everything went or was dropped. */
        DONE,

        /** Stopped for a reason of the moment: run again later. */
        LATER,

        /** ListenBrainz no longer takes the token: nothing more until a new one. */
        TOKEN_REFUSED,
    }

    /**
     * @param sent taken by ListenBrainz, to come off the outbox.
     * @param dropped refused for good, or no longer readable, to come off it too.
     * @param refusals ListenBrainz's reasons for the plays dropped, for the log.
     * @param stoppedBy what ended the run early, when it did.
     */
    class Outcome(
        val sent: List<WaitingListen>,
        val dropped: List<WaitingListen>,
        val refusals: List<String>,
        val next: Next,
        val stoppedBy: Throwable?,
    )

    /** Sends [waiting] through [send], which is ListenBrainz's import for the token in use. */
    suspend fun run(
        waiting: List<WaitingListen>,
        batch: Int = BATCH,
        send: suspend (List<Listen>) -> Result<Unit>,
    ): Outcome {
        val sent = mutableListOf<WaitingListen>()
        val dropped = mutableListOf<WaitingListen>()
        val refusals = mutableListOf<String>()
        fun stop(e: Throwable) = Outcome(
            sent, dropped, refusals,
            next = if (e is ListenBrainzException.TokenRefused) Next.TOKEN_REFUSED else Next.LATER,
            stoppedBy = e,
        )

        // A kept text that no longer reads as a listen can never be sent.
        val readable = waiting.mapNotNull { kept ->
            runCatching { kept to ListenBrainz.decode(kept.json) }.getOrNull().also { if (it == null) dropped += kept }
        }
        for (part in readable.chunked(batch)) {
            val failure = send(part.map { it.second }).exceptionOrNull()
            when {
                failure == null -> sent += part.map { it.first }
                failure !is ListenBrainzException.Refused -> return stop(failure)
                part.size == 1 -> {
                    dropped += part.single().first
                    refusals += failure.message.orEmpty()
                }
                // One by one, to find the play ListenBrainz refuses.
                else -> for ((kept, listen) in part) {
                    val alone = send(listOf(listen)).exceptionOrNull()
                    when (alone) {
                        null -> sent += kept
                        is ListenBrainzException.Refused -> {
                            dropped += kept
                            refusals += alone.message.orEmpty()
                        }
                        else -> return stop(alone)
                    }
                }
            }
        }
        return Outcome(sent, dropped, refusals, Next.DONE, null)
    }
}
