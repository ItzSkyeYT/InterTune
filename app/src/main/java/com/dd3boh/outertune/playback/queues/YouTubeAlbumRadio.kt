package com.dd3boh.outertune.playback.queues

import com.dd3boh.outertune.models.MediaMetadata
import com.dd3boh.outertune.models.toMediaMetadata
import com.zionhuang.innertube.YouTube
import com.zionhuang.innertube.models.WatchEndpoint
import kotlinx.coroutines.Dispatchers.IO
import kotlinx.coroutines.withContext

class YouTubeAlbumRadio(
    override val playlistId: String,
    override val startShuffled: Boolean = false
) : Queue {
    override val preloadItem: MediaMetadata? = null
    private val endpoint = WatchEndpoint(
        playlistId = playlistId,
        params = "wAEB"
    )
    private var continuation: String? = null

    override suspend fun getInitialStatus(): Queue.Status = withContext(IO) {
        val albumSongs = YouTube.albumSongs(playlistId).getOrThrow()
        val nextResult = YouTube.next(endpoint, continuation).getOrThrow()
        continuation = nextResult.continuation
        Queue.Status(
            title = nextResult.title,
            // drop, not subList. The intent is "the album, then whatever radio added past it",
            // which assumes the watch page contains at least the whole album. albumSongs follows
            // every continuation and returns all of a 60 track compilation; next returns one page,
            // typically far fewer. subList(60, 25) throws fromIndex > toIndex, playQueue catches
            // it, and starting radio on a long album showed a toast and played nothing while the
            // same action worked on short ones. drop is the total version of the same slice and
            // returns empty instead of throwing, so the album plays and tops up from the
            // continuation on the next page.
            items = (albumSongs + nextResult.items.drop(albumSongs.size)).map { it.toMediaMetadata() },
            mediaItemIndex = nextResult.currentIndex ?: 0
        )
    }

    override fun hasNextPage(): Boolean = continuation != null

    override suspend fun nextPage(): List<MediaMetadata> {
        val nextResult = withContext(IO) {
            YouTube.next(endpoint, continuation).getOrThrow()
        }
        continuation = nextResult.continuation
        return nextResult.items.map { it.toMediaMetadata() }
    }
}
