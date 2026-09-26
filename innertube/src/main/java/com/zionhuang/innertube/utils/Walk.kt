package com.zionhuang.innertube.utils

import com.zionhuang.innertube.YouTube
import com.zionhuang.innertube.models.SongItem
import com.zionhuang.innertube.models.YTItem
import com.zionhuang.innertube.pages.LibraryContinuationPage
import com.zionhuang.innertube.pages.LibraryPage
import com.zionhuang.innertube.pages.PlaylistContinuationPage
import com.zionhuang.innertube.pages.PlaylistPage

/**
 * Everything a paged list gave, and whether that is known to be all of it.
 *
 * For sync, which reads "missing from the list" as "removed from the account". [completed] keeps
 * what it has when a continuation fails, which is right for showing a list and wrong here: a list
 * that stopped early looks exactly like one whose tail was deleted.
 */
data class Walked<T>(val items: List<T>, val complete: Boolean)

/**
 * Follows continuations from a first page. The walk is complete only if every continuation
 * answered and the last one said there was nothing after it. A continuation that fails, or that
 * comes back with nothing in it (what a page in a shape the parser does not know turns into), or
 * that repeats one already followed, ends the walk as incomplete, keeping what was read.
 */
suspend fun <T> walkContinuations(
    firstItems: List<T>,
    firstContinuation: String?,
    fetch: suspend (String) -> Result<Pair<List<T>, String?>>,
): Walked<T> {
    val items = firstItems.toMutableList()
    val followed = HashSet<String>()
    var continuation = firstContinuation
    while (continuation != null) {
        if (!followed.add(continuation)) return Walked(items, complete = false)
        val (pageItems, next) = fetch(continuation).getOrNull() ?: return Walked(items, complete = false)
        if (pageItems.isEmpty()) return Walked(items, complete = false)
        items += pageItems
        continuation = next
    }
    return Walked(items, complete = true)
}

/** Every song of a playlist, and whether that is all of them. See [walkContinuations]. */
suspend fun PlaylistPage.walkSongs(
    fetch: suspend (String) -> Result<PlaylistContinuationPage> = { YouTube.playlistContinuation(it) },
): Walked<SongItem> = walkContinuations(songs, songsContinuation) { c ->
    fetch(c).map { it.songs to it.continuation }
}

/** Every item of a library tab, and whether that is all of them. See [walkContinuations]. */
suspend fun LibraryPage.walkItems(
    fetch: suspend (String) -> Result<LibraryContinuationPage> = { YouTube.libraryContinuation(it) },
): Walked<YTItem> = walkContinuations(items, continuation) { c ->
    fetch(c).map { it.items to it.continuation }
}
