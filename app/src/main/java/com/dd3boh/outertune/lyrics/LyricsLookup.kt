/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.lyrics

import com.dd3boh.outertune.models.MediaMetadata
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
    ): String? {
        var plain: String? = null
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
            val text = result.getOrNull()
            if (text.isNullOrBlank()) {
                onFailure(provider, result.exceptionOrNull() ?: IllegalStateException("${provider.name} gave empty lyrics"))
                continue
            }
            if (LyricsMatch.isSynced(text)) return text
            if (plain == null) plain = text
        }
        return plain
    }
}
