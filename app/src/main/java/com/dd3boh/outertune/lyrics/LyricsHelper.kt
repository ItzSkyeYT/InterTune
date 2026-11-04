package com.dd3boh.outertune.lyrics

import java.util.Collections
import android.content.Context
import android.util.Log
import android.util.LruCache
import com.dd3boh.outertune.constants.LyricSourcePrefKey
import com.dd3boh.outertune.constants.LyricsSourceOrderKey
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
import javax.inject.Inject
import javax.inject.Singleton

// One instance for the app, so the player and the lyrics menu share the lookups in flight below.
@Singleton
class LyricsHelper @Inject constructor(
    @ApplicationContext private val context: Context,
    val database: MusicDatabase
) {
    private val providersById = mapOf(
        LyricsSources.BETTER_LYRICS to BetterLyricsProvider,
        LyricsSources.YOUTUBE_CAPTIONS to YouTubeSubtitleLyricsProvider,
        LyricsSources.LRCLIB to LrcLibLyricsProvider,
        LyricsSources.KUGOU to KuGouLyricsProvider,
        LyricsSources.YOUTUBE_MUSIC to YouTubeLyricsProvider,
    )

    /** The sources in the order set under Lyrics sources (see LyricsSources), those switched off left out. */
    private fun lyricsProviders(): List<LyricsProvider> =
        LyricsSources.order(context.dataStore.get(LyricsSourceOrderKey, ""))
            .mapNotNull { providersById[it] }
            .filter { it.isEnabled(context) }
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

    /** What [refetch] came back with. */
    sealed interface Refetched {
        /** Lyrics to show: other ones, now stored in place of the old, or the song's own file. */
        data class Found(val lyrics: SemanticLyrics) : Refetched

        /** Every source answered and none had any: what was stored stays. */
        data object NoneFound : Refetched

        /** No source gave an answer (offline, every one failing, the time limit): what was stored stays. */
        data object Failed : Refetched
    }

    /**
     * The lyrics menu's Refetch: the song looked up as [getLyrics] looks up one with nothing
     * stored, with what is stored left as it is until other lyrics come back. Refetch used to
     * delete the stored lyrics first, those picked in Search included, so offline, or when no
     * source had any, the song was left with none (audit of 2 Nov 2026).
     */
    suspend fun refetch(mediaMetadata: MediaMetadata): Refetched {
        val trim = context.dataStore.get(LyricTrimKey, defaultValue = false)
        val multiline = context.dataStore.get(MultilineLrcKey, defaultValue = true)
        val prefLocal = context.dataStore.get(LyricSourcePrefKey, true)

        val localLyrics = getLocalLyrics(mediaMetadata, LrcUtils.LrcParserOptions(trim, multiline, "Unable to parse lyrics"))
        if (prefLocal && localLyrics != null) return Refetched.Found(localLyrics)

        val remote = getRemoteLyrics(mediaMetadata)
        val found = remote.lyrics?.let { parse(it, trim, multiline) }
        if (remote.lyrics != null && found != null) {
            // The write runs on Room's executor whether or not this lookup is still wanted.
            currentCoroutineContext().ensureActive()
            database.query {
                upsert(LyricsEntity(id = mediaMetadata.id, lyrics = remote.lyrics))
            }
            return Refetched.Found(found)
        }
        if (localLyrics != null) return Refetched.Found(localLyrics)
        return if (remote.answered) Refetched.NoneFound else Refetched.Failed
    }

    private suspend fun resolveLyrics(mediaMetadata: MediaMetadata): SemanticLyrics? {
        val trim = context.dataStore.get(LyricTrimKey, defaultValue = false)
        val multiline = context.dataStore.get(MultilineLrcKey, defaultValue = true)

        val prefLocal = context.dataStore.get(LyricSourcePrefKey, true)

        val cached = cache.get(mediaMetadata.id)?.firstOrNull()
        if (cached != null) {
            return parse(cached.lyrics, trim, multiline)
        }
        val dbLyrics = database.lyrics(mediaMetadata.id).let { it.first()?.lyrics }
        if (dbLyrics != null && !prefLocal) {
            return parse(dbLyrics, trim, multiline)
        }

        val localLyrics: SemanticLyrics? =
            getLocalLyrics(mediaMetadata, LrcUtils.LrcParserOptions(trim, multiline, "Unable to parse lyrics"))
        val remote: LyricsLookup.Outcome

        // fallback to secondary provider when primary is unavailable
        if (prefLocal) {
            if (localLyrics != null) {
                return localLyrics
            }
            if (dbLyrics != null) {
                return parse(dbLyrics, trim, multiline)
            }

            // "lazy eval" the remote lyrics cuz it is laughably slow
            remote = getRemoteLyrics(mediaMetadata)
            if (remote.lyrics != null) {
                database.query {
                    upsert(
                        LyricsEntity(
                            id = mediaMetadata.id,
                            lyrics = remote.lyrics
                        )
                    )
                }
                return parse(remote.lyrics, trim, multiline)
            }
        } else {
            remote = getRemoteLyrics(mediaMetadata)
            if (remote.lyrics != null) {
                database.query {
                    upsert(
                        LyricsEntity(
                            id = mediaMetadata.id,
                            lyrics = remote.lyrics
                        )
                    )
                }
                return parse(remote.lyrics, trim, multiline)
            } else if (localLyrics != null) {
                return localLyrics
            }

        }

        // The write below runs on Room's executor whether or not this coroutine is still wanted, so
        // a lookup cancelled partway must stop here rather than record the song as having none.
        currentCoroutineContext().ensureActive()
        // Only when a provider actually answered "no lyrics". Every provider unreachable (no
        // connection, no DNS, a timeout) must not be remembered as this song having none: that
        // stuck for good, since nothing but the manual Refetch action ever asks again.
        if (remote.answered) {
            database.query {
                upsert(
                    LyricsEntity(
                        id = mediaMetadata.id,
                        lyrics = LYRICS_NOT_FOUND
                    )
                )
            }
        }
        return null
    }

    /**
     * Lookup lyrics from remote providers
     */
    private suspend fun getRemoteLyrics(mediaMetadata: MediaMetadata): LyricsLookup.Outcome =
        findRemoteLyrics(mediaMetadata).also { found ->
            // Which kind came back: only Better Lyrics gives words timed one by one (TTML).
            val text = found.lyrics
            val kind = when {
                text == null -> "none"
                LyricsMatch.isTtml(text) -> "timed word by word"
                LyricsMatch.isSynced(text) -> "timed by line"
                else -> "plain"
            }
            Log.i(TAG, "Lyrics for ${mediaMetadata.id}: $kind")
        }

    private suspend fun findRemoteLyrics(mediaMetadata: MediaMetadata): LyricsLookup.Outcome {
        val query = LyricsQuery(
            id = mediaMetadata.id,
            title = mediaMetadata.title,
            artists = mediaMetadata.artists.map { it.name }.filter { it.isNotBlank() },
            album = mediaMetadata.album?.title,
            duration = knownDuration(mediaMetadata),
        )
        return LyricsLookup.firstFound(lyricsProviders(), query, readable = { !LyricsMatch.isTtml(it) || readTtml(it) != null }) { _, e ->
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

    /**
     * Everything the providers offer for the lyrics search's title and artists, each as it arrives,
     * for the listener to choose from: every provider asked on its own ([LyricsLookup.all]), the typed
     * artists split, and the song's real length waited for as the automatic lookup does. Only what was
     * found is kept for the same search again: an empty answer, often one provider failing, was kept
     * as well, so asking again changed nothing until the app was restarted.
     */
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
        val query = LyricsQuery.typed(
            id = mediaId,
            title = songTitle,
            artists = songArtists,
            album = null,
            duration = LyricsLookup.awaitLength(duration, database.song(mediaId).map { it?.song?.duration ?: -1 }, DURATION_WAIT_MS),
        )
        val providers = lyricsProviders()
        val found = Collections.synchronizedList(mutableListOf<LyricsResult>())
        LyricsLookup.all(
            providers,
            query,
            onFailure = { provider, e -> Log.w(TAG, "Lyrics search: ${provider.name} failed: ${e.message}") },
        ) { provider, lyrics ->
            // The sources answer side by side; the list keeps them in the order set under Lyrics sources.
            val result = LyricsResult(provider.name, lyrics, rank = providers.indexOf(provider))
            found += result
            callback(result)
        }
        if (found.isNotEmpty()) cache.put(cacheKey, found.sortedBy { it.rank })
    }

    /** Lyrics as kept, read whatever their form. See [readKeptLyrics]. */
    private fun parse(text: String, trim: Boolean, multiline: Boolean): SemanticLyrics? = readKeptLyrics(text, trim, multiline)

    companion object {
        private const val TAG = "LyricsHelper"
        private const val MAX_CACHE_SIZE = 3

        // Long enough for the stream to be resolved on a slow connection, short enough that lyrics
        // without a length still arrive while the song plays.
        private const val DURATION_WAIT_MS = 10_000L
    }
}

data class LyricsResult(
    val providerName: String,
    val lyrics: String,
    /** The source's place under Lyrics sources: the search's list is kept in that order. */
    val rank: Int = 0,
)
