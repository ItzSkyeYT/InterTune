package com.dd3boh.outertune.lyrics

import android.content.Context
import com.dd3boh.lrclib.LrcLib
import com.dd3boh.lrclib.models.Track
import com.dd3boh.outertune.constants.EnableLrcLibKey
import com.dd3boh.outertune.utils.dataStore
import com.dd3boh.outertune.utils.get
import kotlinx.coroutines.CancellationException

/**
 * Source: https://github.com/Malopieds/InnerTune
 */
object LrcLibLyricsProvider : LyricsProvider {
    override val name = "LrcLib"

    // LRCLIB's exact lookup answers 400 for a length outside 1 to 3600 seconds.
    private const val EXACT_MAX_DURATION = 3600

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
    override suspend fun getLyrics(query: LyricsQuery): Result<String> = lookUp(query, LrcLib::get, LrcLib::search)

    /** [getLyrics] with LRCLIB's two requests passed in, so that the order they are made in can be tested. */
    internal suspend fun lookUp(
        query: LyricsQuery,
        exact: suspend (title: String, artist: String, duration: Int) -> Track?,
        search: suspend (title: String, artist: String) -> List<Track>,
    ): Result<String> = runCatching {
        var plain: String? = null
        for (artist in query.artistVariants) {
            // The exact lookup refuses an empty artist, and a length it does not take, with a 400, where
            // the search takes the title alone and needs no length.
            if (query.duration in 1..EXACT_MAX_DURATION && artist.isNotBlank()) {
                exactOrNull { exact(query.searchTitle, artist, query.duration) }?.let { track ->
                    LyricsMatch.chooseLrcLib(listOf(track), query)?.let { text ->
                        if (LyricsMatch.isSynced(text)) return@runCatching text
                        if (plain == null) plain = text
                    }
                }
            }
            LyricsMatch.chooseLrcLib(search(query.searchTitle, artist), query)?.let { text ->
                if (LyricsMatch.isSynced(text)) return@runCatching text
                if (plain == null) plain = text
            }
        }
        plain ?: throw IllegalStateException("Lyrics unavailable")
    }

    /**
     * The exact lookup's answer, with any failure counted as a miss. Only a 404 used to count: a 503
     * from a busy server, a 400 or a dropped connection ended LRCLIB for the song without its search
     * ever being asked, though the search is a separate request that may well have had it.
     */
    private suspend fun exactOrNull(request: suspend () -> Track?): Track? = try {
        request()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        null
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
