package com.dd3boh.outertune.lyrics

import android.content.Context
import android.util.LruCache
import com.dd3boh.outertune.constants.LyricSourcePrefKey
import com.dd3boh.outertune.constants.LyricTrimKey
import com.dd3boh.outertune.constants.MultilineLrcKey
import com.dd3boh.outertune.db.MusicDatabase
import com.dd3boh.outertune.db.entities.LyricsEntity
import com.dd3boh.outertune.db.entities.LyricsEntity.Companion.LYRICS_NOT_FOUND
import com.dd3boh.outertune.models.MediaMetadata
import com.dd3boh.outertune.utils.dataStore
import com.dd3boh.outertune.utils.get
import com.dd3boh.outertune.utils.reportException
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import org.akanework.gramophone.logic.utils.LrcUtils
import org.akanework.gramophone.logic.utils.SemanticLyrics
import org.akanework.gramophone.logic.utils.parseLrc
import javax.inject.Inject
import javax.inject.Singleton

// One instance for the app, so the player and the lyrics menu share the lookups in flight below.
@Singleton
class LyricsHelper @Inject constructor(
    @ApplicationContext private val context: Context,
    val database: MusicDatabase
) {
    private val lyricsProviders =
        listOf(YouTubeSubtitleLyricsProvider, LrcLibLyricsProvider, KuGouLyricsProvider, YouTubeLyricsProvider)
    private val cache = LruCache<String, List<LyricsResult>>(MAX_CACHE_SIZE)

    /**
     * One lookup per song at a time.
     *
     * Everything that shows the lyrics collects PlayerConnection.currentLyrics on its own, and each
     * collection ran a lookup of its own: on 26 Sep 2026 every provider's failure for a song was
     * logged twice, two full lookups side by side. Callers for the same song now wait on one run,
     * and one of them going away no longer cuts it short for the others.
     */
    private val lookups = SharedLookup<String, SemanticLyrics?>(CoroutineScope(SupervisorJob() + Dispatchers.IO))

    /**
     * Retrieve lyrics from all sources
     *
     * How lyrics are resolved are determined by PreferLocalLyrics settings key. If this is true, prioritize local lyric
     * files over all cloud providers, true is vice versa.
     *
     * Lyrics stored in the database are fetched first. If this is not available, it is resolved by other means.
     * If local lyrics are preferred, lyrics from the lrc file is fetched, and then resolve by other means.
     *
     * @param mediaMetadata Song to fetch lyrics for
     * @param database MusicDatabase connection. Database lyrics are prioritized over all sources.
     * If no database is provided, the database source is disabled
     */
    suspend fun getLyrics(mediaMetadata: MediaMetadata): SemanticLyrics? =
        lookups.get(mediaMetadata.id) { resolveLyrics(mediaMetadata) }

    private suspend fun resolveLyrics(mediaMetadata: MediaMetadata): SemanticLyrics? {
        val trim = context.dataStore.get(LyricTrimKey, defaultValue = false)
        val multiline = context.dataStore.get(MultilineLrcKey, defaultValue = true)

        val prefLocal = context.dataStore.get(LyricSourcePrefKey, true)

        val cached = cache.get(mediaMetadata.id)?.firstOrNull()
        if (cached != null) {
            return parseLrc(cached.lyrics, trim, multiline)
        }
        val dbLyrics = database.lyrics(mediaMetadata.id).let { it.first()?.lyrics }
        if (dbLyrics != null && !prefLocal) {
            return parseLrc(dbLyrics, trim, multiline)
        }

        val localLyrics: SemanticLyrics? =
            getLocalLyrics(mediaMetadata, LrcUtils.LrcParserOptions(trim, multiline, "Unable to parse lyrics"))
        val remoteLyrics: String?

        // fallback to secondary provider when primary is unavailable
        if (prefLocal) {
            if (localLyrics != null) {
                return localLyrics
            }
            if (dbLyrics != null) {
                return parseLrc(dbLyrics, trim, multiline)
            }

            // "lazy eval" the remote lyrics cuz it is laughably slow
            remoteLyrics = getRemoteLyrics(mediaMetadata)
            if (remoteLyrics != null) {
                database.query {
                    upsert(
                        LyricsEntity(
                            id = mediaMetadata.id,
                            lyrics = remoteLyrics
                        )
                    )
                }
                return parseLrc(remoteLyrics, trim, multiline)
            }
        } else {
            remoteLyrics = getRemoteLyrics(mediaMetadata)
            if (remoteLyrics != null) {
                database.query {
                    upsert(
                        LyricsEntity(
                            id = mediaMetadata.id,
                            lyrics = remoteLyrics
                        )
                    )
                }
                return parseLrc(remoteLyrics, trim, multiline)
            } else if (localLyrics != null) {
                return localLyrics
            }

        }

        // The write below runs on Room's executor whether or not this coroutine is still wanted, so
        // a lookup cancelled partway must stop here rather than record the song as having none.
        currentCoroutineContext().ensureActive()
        database.query {
            upsert(
                LyricsEntity(
                    id = mediaMetadata.id,
                    lyrics = LYRICS_NOT_FOUND
                )
            )
        }
        return null
    }

    /**
     * Lookup lyrics from remote providers
     */
    private suspend fun getRemoteLyrics(mediaMetadata: MediaMetadata): String? {
        val query = LyricsQuery(
            id = mediaMetadata.id,
            title = mediaMetadata.title,
            artists = mediaMetadata.artists.map { it.name }.filter { it.isNotBlank() },
            album = mediaMetadata.album?.title,
            duration = knownDuration(mediaMetadata),
        )
        return LyricsLookup.firstFound(lyricsProviders.filter { it.isEnabled(context) }, query) { _, e ->
            reportException(e)
        }
    }

    /**
     * The song's length in seconds, waiting a little for it when the player does not have it yet.
     *
     * A song tapped in search results reaches the player with -1, because search rows carry no
     * length. LRCLIB's choice needed an entry within two seconds of that, so it never found one:
     * "Bailando (Video Edit)" by Paradisio failed there although LRCLIB has it timed at 230 s, and
     * KuGou, which ignored length when it had none, was left to take the first song its search
     * listed. recoverSong writes the real length to the database from the stream's own details as
     * soon as playback starts, so the lookup waits for that.
     */
    private suspend fun knownDuration(mediaMetadata: MediaMetadata): Int = LyricsLookup.awaitLength(
        known = mediaMetadata.duration,
        lengths = database.song(mediaMetadata.id).map { it?.song?.duration ?: -1 },
        waitMs = DURATION_WAIT_MS,
    )

    /**
     * Lookup lyrics from local disk (.lrc) file
     */
    private fun getLocalLyrics(
        mediaMetadata: MediaMetadata,
        parserOptions: LrcUtils.LrcParserOptions
    ): SemanticLyrics? {
        if (LocalLyricsProvider.isEnabled(context) && mediaMetadata.localPath != null) {
            return LocalLyricsProvider.getLyricsNew(
                mediaMetadata.localPath,
                parserOptions
            )
        }

        return null
    }

    suspend fun getAllLyrics(
        mediaId: String,
        songTitle: String,
        songArtists: String,
        duration: Int,
        callback: (LyricsResult) -> Unit,
    ) {
        val cacheKey = "$songArtists-$songTitle".replace(" ", "")
        cache.get(cacheKey)?.let { results ->
            results.forEach {
                callback(it)
            }
            return
        }
        val allResult = mutableListOf<LyricsResult>()
        lyricsProviders.forEach { provider ->
            if (provider.isEnabled(context)) {
                provider.getAllLyrics(mediaId, songTitle, songArtists, duration) { lyrics ->
                    val result = LyricsResult(provider.name, lyrics)
                    allResult += result
                    callback(result)
                }
            }
        }
        cache.put(cacheKey, allResult)
    }

    companion object {
        private const val MAX_CACHE_SIZE = 3

        // Long enough for the stream to be resolved on a slow connection, short enough that lyrics
        // without a length still arrive while the song plays.
        private const val DURATION_WAIT_MS = 10_000L
    }
}

data class LyricsResult(
    val providerName: String,
    val lyrics: String,
)
