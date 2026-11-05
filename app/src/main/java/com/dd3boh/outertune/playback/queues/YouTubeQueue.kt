package com.dd3boh.outertune.playback.queues

import com.dd3boh.outertune.constants.Unreleased
import android.util.Log
import com.dd3boh.outertune.models.AiFilter
import com.dd3boh.outertune.models.ContentFilter
import com.dd3boh.outertune.models.MediaMetadata
import com.dd3boh.outertune.models.toMediaMetadata
import com.dd3boh.outertune.playback.RadioAnswers
import com.dd3boh.outertune.playback.RadioLadder
import com.dd3boh.outertune.utils.YouTubeTrouble
import com.zionhuang.innertube.YouTube
import com.zionhuang.innertube.models.WatchEndpoint
import com.zionhuang.innertube.pages.NextResult
import kotlinx.coroutines.Dispatchers.IO
import kotlinx.coroutines.withContext

class YouTubeQueue(
    private var endpoint: WatchEndpoint,
    override val preloadItem: MediaMetadata? = null,
    override val playlistId: String? = endpoint.playlistId,
    override val startShuffled: Boolean = false,
    private var continuation: String? = null,
) : Queue {

    override suspend fun getInitialStatus(): Queue.Status {
        val (nextResult, fallback) = withContext(IO) {
            if (Unreleased.RADIO_FALLBACK) fromLadder()
            // Asked as the account whenever there is one, so an answer says the sign-in still
            // works (YouTubeTrouble); a failure is told by MusicService, which says why.
            else YouTube.next(endpoint, continuation)
                .onSuccess { YouTubeTrouble.answered(asAccount = true) }
                .getOrThrow() to false
        }
        endpoint = nextResult.endpoint
        continuation = nextResult.continuation
        // The song it was started from stays even when it is one the switches hide: it was chosen.
        val first = nextResult.currentIndex ?: 0
        // And with the AI filter its artists stay for the rest of this queue (AiFilter.forQueue).
        nextResult.items.getOrNull(first)?.let { song -> AiFilter.startedFrom(song.artists.map { it.id }) }
        val (kept, at) = ContentFilter.current.radio(nextResult.items, first)
        // A playlist or an album played from YouTube joins whole; a radio is thinned (AiFilter.forQueuePages).
        val (items, start) = AiFilter.forQueuePages(playlistId).radio(kept, at)
        if (items.size < kept.size) Log.d(TAG, "AI filter: ${kept.size - items.size} of ${kept.size} songs left out of the first page")
        val songs = items.map { it.toMediaMetadata() }
        // A mix of YouTube's that does not hold the song tapped would lose its first song to it.
        if (fallback && preloadItem != null && songs.none { it.id == preloadItem.id }) {
            return Queue.Status(nextResult.title, listOf(preloadItem) + songs, 0, relatedEndpoint = nextResult.relatedEndpoint)
        }
        return Queue.Status(
            title = nextResult.title,
            items = songs,
            mediaItemIndex = start,
            relatedEndpoint = nextResult.relatedEndpoint,
        )
    }

    /**
     * The radio through RadioAnswers: YouTube Music as the app always asked it, and the steps after
     * it when that is refused or holds the song alone. With it, whether a step after the first gave
     * the answer. Throws what failed when no step gave anything.
     */
    private suspend fun fromLadder(): Pair<NextResult, Boolean> {
        val answer = RadioAnswers.radio(endpoint, continuation)
        // The sign-in YouTube refused is still the note's to tell, whoever answered after it.
        answer.musicFailure?.let(YouTubeTrouble::failed)
        val result = answer.result ?: throw answer.failure ?: RadioLadder.Refused()
        // Only YouTube Music's own answer was asked as the account.
        if (answer.from != null) YouTubeTrouble.answered(asAccount = answer.from == RadioLadder.Rung.MUSIC)
        return result to (answer.from != null && answer.from != RadioLadder.Rung.MUSIC)
    }

    override fun hasNextPage(): Boolean = continuation != null

    override suspend fun nextPage(): List<MediaMetadata> {
        val nextResult = withContext(IO) {
            if (Unreleased.RADIO_FALLBACK) RadioAnswers.radio(endpoint, continuation).result
            else YouTube.next(endpoint, continuation).getOrNull()
        }
        if (nextResult != null) {
            endpoint = nextResult.endpoint
        }
        continuation = nextResult?.continuation
        val kept = nextResult?.items?.let(ContentFilter.current::apply) ?: return emptyList()
        val items = AiFilter.forQueuePages(playlistId).recommendations(kept)
        if (items.size < kept.size) Log.d(TAG, "AI filter: ${kept.size - items.size} of ${kept.size} songs left out of a next page")
        return items.map { it.toMediaMetadata() }
    }

//    fun getContinuationEndpoint(): String? {
//        return if (endpoint.videoId != null && continuation != null) {
//            "${endpoint.videoId}\n$continuation"
//        } else {
//            null
//        }
//    }

    companion object {
        private const val TAG = "YouTubeQueue"

        fun radio(song: MediaMetadata) = YouTubeQueue(WatchEndpoint(song.id), song)
    }
}
