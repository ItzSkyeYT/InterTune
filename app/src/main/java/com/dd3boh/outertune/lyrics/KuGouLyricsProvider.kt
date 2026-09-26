package com.dd3boh.outertune.lyrics

import android.content.Context
import com.dd3boh.outertune.constants.EnableKugouKey
import com.dd3boh.outertune.utils.dataStore
import com.dd3boh.outertune.utils.get
import com.zionhuang.kugou.KuGou
import com.zionhuang.kugou.models.SearchLyricsResponse
import com.zionhuang.kugou.models.SearchSongResponse

object KuGouLyricsProvider : LyricsProvider {
    override val name = "Kugou"
    override fun isEnabled(context: Context): Boolean =
        context.dataStore[EnableKugouKey] ?: true

    /** The requests the lookup makes to KuGou, apart so that the choice between its answers can be tested. */
    internal interface Requests {
        suspend fun songs(title: String, artist: String): List<SearchSongResponse.Data.Info>
        suspend fun candidatesForHash(hash: String): List<SearchLyricsResponse.Candidate>
        suspend fun candidatesForKeyword(title: String, artist: String, duration: Int): List<SearchLyricsResponse.Candidate>
        suspend fun download(candidate: SearchLyricsResponse.Candidate): String?
    }

    private object KuGouRequests : Requests {
        override suspend fun songs(title: String, artist: String) = KuGou.songs(title, artist)
        override suspend fun candidatesForHash(hash: String) = KuGou.candidatesForHash(hash)
        override suspend fun candidatesForKeyword(title: String, artist: String, duration: Int) =
            KuGou.candidatesForKeyword(title, artist, duration)
        override suspend fun download(candidate: SearchLyricsResponse.Candidate) = KuGou.download(candidate)
    }

    /**
     * The closest songs in KuGou's search that are this song by this artist, then its lyrics search
     * by name, first for all the artists and then for the first alone.
     *
     * KuGou's own choice looked at length alone, and with no length it took the first song listed,
     * whatever it was: "DAYS LATER FUNK - SPED UP" got the lyrics of a sped up "Careless Whisper".
     * KuGou only has timed lyrics, so with no length to check them against it is not asked at all.
     */
    override suspend fun getLyrics(query: LyricsQuery): Result<String> = lookUp(query, KuGouRequests)

    internal suspend fun lookUp(query: LyricsQuery, requests: Requests): Result<String> = runCatching {
        check(query.knowsDuration) { "No length to check KuGou's timings against" }
        for (artist in query.artistVariants) {
            val songs = requests.songs(query.searchTitle, artist)
            val closest = LyricsMatch.ranked(
                songs, query, LyricsMatch.SYNCED_TOLERANCE_SEC,
                title = { it.songname }, artist = { it.singername }, duration = { it.duration.toDouble() },
            )
            for (song in closest.take(2)) {
                val candidate = requests.candidatesForHash(song.hash).firstOrNull() ?: continue
                requests.download(candidate)?.let { return@runCatching it }
            }
            val byName = LyricsMatch.ranked(
                requests.candidatesForKeyword(query.searchTitle, artist, query.duration), query, LyricsMatch.SYNCED_TOLERANCE_SEC,
                title = { it.song }, artist = { it.singer }, duration = { it.duration / 1000.0 },
            )
            for (candidate in byName.take(2)) {
                requests.download(candidate)?.let { return@runCatching it }
            }
        }
        throw IllegalStateException("No lyrics candidate")
    }

    override suspend fun getAllLyrics(id: String, title: String, artist: String, duration: Int, callback: (String) -> Unit) {
        KuGou.getAllPossibleLyricsOptions(title, artist, duration, callback)
    }
}
