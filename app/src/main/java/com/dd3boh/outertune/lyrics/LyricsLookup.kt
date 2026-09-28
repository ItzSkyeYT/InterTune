/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.lyrics

import com.dd3boh.outertune.models.MediaMetadata
import java.io.IOException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChangedBy
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.withTimeoutOrNull

/** The automatic lookup's walk through the providers, apart from Android so it can be tested. */
object LyricsLookup {

    /**
     * The lyrics for each song [metadata] names, looked up again only when the song changes.
     *
     * By song rather than by metadata object. Every MediaMetadata carries a random field, so a second
     * copy of the song already playing, from a queue rebuilt around it for instance, never compares
     * equal, and would cancel the lookup in progress only to start the same one again.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    fun <T> bySong(metadata: Flow<MediaMetadata?>, lookUp: suspend (MediaMetadata) -> T): Flow<T> =
        metadata.distinctUntilChangedBy { it?.id }.flatMapLatest { song ->
            if (song != null) flowOf(lookUp(song)) else emptyFlow()
        }

    /**
     * The song's length in seconds: [known] when the player has it, or else the first real length
     * [lengths] gives within [waitMs], or -1 when none comes.
     */
    suspend fun awaitLength(known: Int, lengths: Flow<Int>, waitMs: Long): Int {
        if (known > 0) return known
        return withTimeoutOrNull(waitMs) { lengths.firstOrNull { it > 0 } } ?: -1
    }

    /**
     * What [firstFound] came back with.
     *
     * [answered] says whether any provider actually said something about this song, rather than
     * simply being unreachable. A song looked up while offline gets a failure from every provider,
     * indistinguishable by shape from "this song has no lyrics" unless something keeps the two
     * apart, and [lyrics] alone cannot: both are null. Only [answered] lets the caller tell the two
     * apart and skip remembering "not found" for a song nobody was actually asked about yet.
     */
    data class Outcome(val lyrics: String?, val answered: Boolean)

    /**
     * A provider's failure that means it never actually asked anywhere: a precondition it needs
     * was not met, such as KuGou with no known length to check its timings against (see
     * [KuGouLyricsProvider.getLyrics]). Distinct from an ordinary failure exactly so [firstFound]
     * does not count it as the provider having answered, the same reason an [IOException] does not.
     */
    class NotAsked(message: String) : Exception(message)

    /**
     * Asks each provider in turn and returns the first timed lyrics any of them has.
     *
     * Plain words do not end the search, since a later provider may have the same song timed; the
     * first plain words found are returned only when nobody has timed ones. Every provider is
     * asked until then, whatever the earlier ones did, including throwing.
     */
    suspend fun firstFound(
        providers: List<LyricsProvider>,
        query: LyricsQuery,
        onFailure: (LyricsProvider, Throwable) -> Unit,
    ): Outcome {
        var plain: String? = null
        var answered = false
        for (provider in providers) {
            currentCoroutineContext().ensureActive()
            if (plain != null && !provider.offersSynced) continue
            val result = try {
                provider.getLyrics(query)
            } catch (e: Exception) {
                Result.failure(e)
            }
            // The providers wrap their requests in runCatching, which also catches this lookup being
            // cancelled and hands it back as an ordinary failure. Without this check a cancelled
            // lookup went on to every later provider, each of which failed at once and was logged,
            // and then stored "not found" for a song nobody had finished looking up.
            currentCoroutineContext().ensureActive()
            val failure = result.exceptionOrNull()
            // A network failure (no connection, DNS, a timeout: the IOException family) is the
            // provider never being reached, not it answering "no lyrics". A NotAsked failure is a
            // provider skipping itself outright because a precondition it needs was not met, which
            // made no request either. Anything else, success or not, is a real answer.
            if (failure == null || (failure !is IOException && failure !is NotAsked)) answered = true
            val text = result.getOrNull()
            if (text.isNullOrBlank()) {
                onFailure(provider, failure ?: IllegalStateException("${provider.name} gave empty lyrics"))
                continue
            }
            if (LyricsMatch.isSynced(text)) return Outcome(text, answered)
            if (plain == null) plain = text
        }
        return Outcome(plain, answered)
    }
}
