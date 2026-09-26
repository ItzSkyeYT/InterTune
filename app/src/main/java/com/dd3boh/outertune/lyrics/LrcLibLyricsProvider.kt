package com.dd3boh.outertune.lyrics

import android.content.Context
import com.dd3boh.lrclib.LrcLib
import com.dd3boh.outertune.constants.EnableLrcLibKey
import com.dd3boh.outertune.utils.dataStore
import com.dd3boh.outertune.utils.get

/**
 * Source: https://github.com/Malopieds/InnerTune
 */
object LrcLibLyricsProvider : LyricsProvider {
    override val name = "LrcLib"

    override fun isEnabled(context: Context): Boolean =
        context.dataStore[EnableLrcLibKey] ?: true

    /**
     * LRCLIB's exact lookup, then its search, both with the cleaned title, first for all the artists
     * and then for the first alone. Every answer goes through [LyricsMatch.chooseLrcLib], since the
     * search matches loosely and the exact lookup allows two seconds either way.
     *
     * The exact lookup can return an entry with plain words only while the search holds timed ones
     * for the same song, as it did for "Samba de Janeiro" at 169 s, so plain words from it are kept
     * aside and the search is still asked.
     */
    override suspend fun getLyrics(query: LyricsQuery): Result<String> = runCatching {
        var plain: String? = null
        for (artist in query.artistVariants) {
            // The exact lookup refuses an empty artist with a 400, where the search takes the title alone.
            if (query.knowsDuration && artist.isNotBlank()) {
                LrcLib.get(query.searchTitle, artist, query.duration)?.let { track ->
                    LyricsMatch.chooseLrcLib(listOf(track), query)?.let { text ->
                        if (LyricsMatch.isSynced(text)) return@runCatching text
                        if (plain == null) plain = text
                    }
                }
            }
            LyricsMatch.chooseLrcLib(LrcLib.search(query.searchTitle, artist), query)?.let { text ->
                if (LyricsMatch.isSynced(text)) return@runCatching text
                if (plain == null) plain = text
            }
        }
        plain ?: throw IllegalStateException("Lyrics unavailable")
    }

    override suspend fun getAllLyrics(
        id: String,
        title: String,
        artist: String,
        duration: Int,
        callback: (String) -> Unit,
    ) {
        LrcLib.getAllLyrics(title, artist, duration, null, callback)
    }
}
