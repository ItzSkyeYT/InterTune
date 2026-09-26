package com.dd3boh.outertune.lyrics

import android.content.Context
import com.zionhuang.innertube.YouTube
import com.zionhuang.innertube.models.WatchEndpoint

object YouTubeLyricsProvider : LyricsProvider {
    override val name = "YouTube Music"

    // The lyrics tab is plain words.
    override val offersSynced = false

    override fun isEnabled(context: Context) = true
    override suspend fun getLyrics(query: LyricsQuery): Result<String> = runCatching {
        val nextResult = YouTube.next(WatchEndpoint(videoId = query.id)).getOrThrow()
        YouTube.lyrics(
            endpoint = nextResult.lyricsEndpoint ?: throw IllegalStateException("Lyrics endpoint not found")
        ).getOrThrow() ?: throw IllegalStateException("Lyrics unavailable")
    }
}
