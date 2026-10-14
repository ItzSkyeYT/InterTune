package com.dd3boh.outertune.lyrics

import com.dd3boh.outertune.constants.EnableYouTubeCaptionsKey
import com.dd3boh.outertune.utils.dataStore
import com.dd3boh.outertune.utils.get
import android.content.Context
import com.zionhuang.innertube.YouTube

object YouTubeSubtitleLyricsProvider : LyricsProvider {
    override val name = "YouTube Subtitle"
    override fun isEnabled(context: Context) = context.dataStore[EnableYouTubeCaptionsKey] ?: true
    override suspend fun getLyrics(query: LyricsQuery): Result<String> =
        YouTube.transcript(query.id)
}
