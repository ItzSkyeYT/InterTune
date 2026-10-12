/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.migration

import com.dd3boh.outertune.utils.Throttle
import com.zionhuang.innertube.YouTube
import com.zionhuang.innertube.models.SongItem
import io.ktor.client.plugins.ResponseException
import kotlin.coroutines.cancellation.CancellationException

/**
 * The import's searches as they go to YouTube.
 *
 * As a visitor, whatever "Use login for browsing content" says. An import is hundreds of searches
 * that nobody typed. Sent as the account they would carry its name: whether YouTube then keeps
 * them in the account's search history has not been looked at, and a burst that it takes for a
 * script is better left unsigned. Nothing an import looks up needs the account: it asks for
 * songs by name, and what it makes stays on the phone.
 */
object ImportSearch {
    /**
     * Songs only: what the matcher scores, and what the match rate was measured on. A search that
     * YouTube refused throws [SearchRefused], so that the matcher does not ask again.
     */
    suspend fun songs(query: String): List<SongItem> = try {
        YouTube.search(query, YouTube.SearchFilter.FILTER_SONG, anonymous = true).getOrThrow().items.filterIsInstance<SongItem>()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        if (refused((e as? ResponseException)?.response?.status?.value, e)) throw SearchRefused(e)
        throw e
    }

    /**
     * Whether a search that failed was YouTube refusing it, where a connection that dropped is
     * not. [httpStatus] is the status of the answer when there was one, which is what the
     * client's ResponseException holds.
     *
     * Too many requests (429) and forbidden (403) are how it turns a connection away, and the
     * words of its bot check are the same thing said in full, wherever in the failure they are.
     * Trouble of its own (a 500, a 503) passes by itself, and is tried again like any request
     * that did not get through.
     */
    fun refused(httpStatus: Int?, failure: Throwable): Boolean =
        httpStatus == 429 || httpStatus == 403 || Throttle.isBlock(failure)

    /**
     * Whether a search that failed got an answer from YouTube all the same, only not songs: a
     * 400 or a 500, which the client hands over as a ResponseException. The matcher waits those
     * out longer than a connection that is not there.
     */
    fun answered(failure: Throwable): Boolean = failure is ResponseException
}
