package com.dd3boh.outertune.lyrics

import android.content.Context

interface LyricsProvider {
    val name: String

    /**
     * Whether this provider can ever give timed lyrics. One that cannot is skipped once plain words
     * are already in hand, since it could only offer more plain words.
     */
    val offersSynced: Boolean get() = true

    fun isEnabled(context: Context): Boolean

    /** Lyrics for the song playing, checked against it; a failure when none fit. */
    suspend fun getLyrics(query: LyricsQuery): Result<String>

    /**
     * Everything this provider offers for a title and artist typed into the lyrics search, for the
     * listener to choose from. Nothing is filtered by name here, since the listener is the judge.
     */
    suspend fun getAllLyrics(id: String, title: String, artist: String, duration: Int, callback: (String) -> Unit) {
        getLyrics(LyricsQuery(id = id, title = title, artists = listOf(artist), duration = duration)).onSuccess(callback)
    }
}

/**
 * The song the automatic lookup is for.
 *
 * @param artists each artist on its own, as the player has them, not joined into one string
 * @param duration in seconds, or -1 when it is not known
 */
data class LyricsQuery(
    val id: String,
    val title: String,
    val artists: List<String>,
    val album: String? = null,
    val duration: Int,
) {
    /** All the artists as one string, the way the app shows them. */
    val artist: String get() = artists.joinToString()

    /**
     * The title to search with: version tags such as "(Video Edit)" taken off, and a leading
     * "Artist - " dropped when it names one of the artists, as video titles often have.
     */
    val searchTitle: String by lazy { LyricsMatch.cleanTitle(LyricsMatch.dropArtistPrefix(title, artists)) }

    /**
     * The artist strings to try, in order: the whole list, then the first artist alone. LRCLIB's
     * exact lookup found nothing for "Paradisio, Marisa" that it found for "Paradisio". A song with
     * no artist, such as an untagged file, is searched for by title alone.
     */
    val artistVariants: List<String>
        get() = listOfNotNull(artist, artists.firstOrNull()).filter { it.isNotBlank() }.distinct().ifEmpty { listOf("") }

    val knowsDuration: Boolean get() = duration > 0
}
