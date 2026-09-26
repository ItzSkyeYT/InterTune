package com.dd3boh.outertune.lyrics

import android.content.Context
import com.dd3boh.outertune.constants.EnableKugouKey
import com.dd3boh.outertune.utils.dataStore
import com.dd3boh.outertune.utils.get
import com.zionhuang.kugou.KuGou

object KuGouLyricsProvider : LyricsProvider {
    override val name = "Kugou"
    override fun isEnabled(context: Context): Boolean =
        context.dataStore[EnableKugouKey] ?: true

    /**
     * The closest songs in KuGou's search that are this song by this artist, then its lyrics search
     * by name, first for all the artists and then for the first alone.
     *
     * KuGou's own choice looked at length alone, and with no length it took the first song listed,
     * whatever it was: "DAYS LATER FUNK - SPED UP" got the lyrics of a sped up "Careless Whisper".
     * KuGou only has timed lyrics, so with no length to check them against it is not asked at all.
     */
    override suspend fun getLyrics(query: LyricsQuery): Result<String> = runCatching {
        check(query.knowsDuration) { "No length to check KuGou's timings against" }
        for (artist in query.artistVariants) {
            val songs = KuGou.songs(query.searchTitle, artist)
            val closest = LyricsMatch.ranked(
                songs, query, LyricsMatch.SYNCED_TOLERANCE_SEC,
                title = { it.songname }, artist = { it.singername }, duration = { it.duration.toDouble() },
            )
            for (song in closest.take(2)) {
                val candidate = KuGou.candidatesForHash(song.hash).firstOrNull() ?: continue
                KuGou.download(candidate)?.let { return@runCatching it }
            }
            val byName = LyricsMatch.ranked(
                KuGou.candidatesForKeyword(query.searchTitle, artist, query.duration), query, LyricsMatch.SYNCED_TOLERANCE_SEC,
                title = { it.song }, artist = { it.singer }, duration = { it.duration / 1000.0 },
            )
            for (candidate in byName.take(2)) {
                KuGou.download(candidate)?.let { return@runCatching it }
            }
        }
        throw IllegalStateException("No lyrics candidate")
    }

    override suspend fun getAllLyrics(id: String, title: String, artist: String, duration: Int, callback: (String) -> Unit) {
        KuGou.getAllPossibleLyricsOptions(title, artist, duration, callback)
    }
}
