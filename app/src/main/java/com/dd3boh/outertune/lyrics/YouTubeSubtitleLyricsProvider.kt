package com.dd3boh.outertune.lyrics

import android.content.Context
import com.zionhuang.innertube.YouTube

object YouTubeSubtitleLyricsProvider : LyricsProvider {
    override val name = "YouTube Subtitle"
    override fun isEnabled(context: Context) = true
    override suspend fun getLyrics(query: LyricsQuery): Result<String> =
        YouTube.transcript(query.id)
}
