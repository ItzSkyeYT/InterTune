/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.lyrics

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** The automatic lookup's walk through the providers, apart from Android so it can be tested. */
object LyricsLookup {

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
