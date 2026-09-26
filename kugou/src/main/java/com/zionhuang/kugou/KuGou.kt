package com.zionhuang.kugou

import com.zionhuang.kugou.models.DownloadLyricsResponse
import com.zionhuang.kugou.models.Keyword
import com.zionhuang.kugou.models.SearchLyricsResponse
import com.zionhuang.kugou.models.SearchSongResponse
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.compression.ContentEncoding
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import io.ktor.http.ContentType
import io.ktor.http.encodeURLParameter
import io.ktor.serialization.kotlinx.json.json
import io.ktor.util.decodeBase64String
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.Json
import java.lang.Integer.min
import kotlin.math.abs

@OptIn(ExperimentalSerializationApi::class)
private val client = HttpClient {
    expectSuccess = true

    install(ContentNegotiation) {
        val json = Json {
            ignoreUnknownKeys = true
            explicitNulls = false
            encodeDefaults = true
        }
        json(json)
        json(json, ContentType.Text.Html)
        json(json, ContentType.Text.Plain)
    }

    install(ContentEncoding) {
        gzip()
        deflate()
    }
}

private const val PAGE_SIZE = 8
private const val HEAD_CUT_LIMIT = 30

/**
 * KuGou Lyrics Library
 * Modified from [ViMusic](https://github.com/vfsfitvnm/ViMusic)
 */
object KuGou {
    var useTraditionalChinese: Boolean = false

    suspend fun getLyrics(title: String, artist: String, duration: Int): Result<String> =
        runCatching {
            val keyword = generateKeyword(title, artist)
            getLyricsCandidate(keyword, duration)?.let { candidate ->
                downloadLyrics(candidate.id, candidate.accesskey).content.decodeBase64String()
                    .normalize().let {
                        if ("纯音乐，请欣赏" in it || "酷狗音乐  就是歌多" in it) {
                            // instrumental tracks should report as having no lyrics
                            throw IllegalStateException("No lyrics candidate")
                        }
                        it
                    }
            } ?: throw IllegalStateException("No lyrics candidate")
        }

    suspend fun getAllPossibleLyricsOptions(
        title: String, artist: String, duration: Int, callback: (String) -> Unit
    ) {
        val keyword = generateKeyword(title, artist)
        searchSongs(keyword).data.info.forEach {
            if (duration == -1 || abs(it.duration - duration) <= DURATION_TOLERANCE) {
                searchLyricsByHash(it.hash).candidates.firstOrNull()?.let { candidate ->
                    downloadLyrics(candidate.id, candidate.accesskey).content.decodeBase64String()
                        .normalize().let(callback)
                }
            }
        }
        searchLyricsByKeyword(keyword, duration).candidates.forEach { candidate ->
            downloadLyrics(candidate.id, candidate.accesskey).content.decodeBase64String()
                .normalize().let(callback)
        }
    }

    /** The songs KuGou's search lists for this title and artist, with their names and lengths. */
    suspend fun songs(title: String, artist: String): List<SearchSongResponse.Data.Info> =
        searchSongs(generateKeyword(title, artist)).data.info

    /** The lyrics KuGou keeps for one song from [songs], found by its audio hash. */
    suspend fun candidatesForHash(hash: String): List<SearchLyricsResponse.Candidate> =
        searchLyricsByHash(hash).candidates

    /** KuGou's lyrics search by name and length in seconds, for when no listed song has any. */
    suspend fun candidatesForKeyword(title: String, artist: String, duration: Int): List<SearchLyricsResponse.Candidate> =
        searchLyricsByKeyword(generateKeyword(title, artist), duration).candidates

    /** One candidate's lyrics, or null when they are empty or only KuGou's note that the track has no words. */
    suspend fun download(candidate: SearchLyricsResponse.Candidate): String? =
        downloadLyrics(candidate.id, candidate.accesskey).content.decodeBase64String().normalize()
            .takeUnless { it.isBlank() || "纯音乐，请欣赏" in it || "酷狗音乐  就是歌多" in it }

    suspend fun getLyricsCandidate(
        keyword: Keyword, duration: Int
    ): SearchLyricsResponse.Candidate? {
        searchSongs(keyword).data.info.forEach { song ->
            if (duration == -1 || abs(song.duration - duration) <= DURATION_TOLERANCE) { // if duration == -1, we don't care duration
                val candidate = searchLyricsByHash(song.hash).candidates.firstOrNull()
                if (candidate != null) return candidate
            }
        }
        return searchLyricsByKeyword(keyword, duration).candidates.firstOrNull()
    }

    suspend fun searchSongs(keyword: Keyword) =
        client.get("https://mobileservice.kugou.com/api/v3/search/song") {
            parameter("version", 9108)
            parameter("plat", 0)
            parameter("pagesize", PAGE_SIZE)
            parameter("showtype", 0)
            url.encodedParameters.append(
                "keyword",
                "${keyword.title} - ${keyword.artist}".encodeURLParameter(spaceToPlus = false)
            )
        }.body<SearchSongResponse>()

    private suspend fun searchLyricsByKeyword(keyword: Keyword, duration: Int) =
        client.get("https://lyrics.kugou.com/search") {
            parameter("ver", 1)
            parameter("man", "yes")
            parameter("client", "pc")
            parameter(
                "duration", duration.takeIf { it != -1 }?.times(1000)
            ) // if duration == -1, we don't care duration
            url.encodedParameters.append(
                "keyword",
                "${keyword.title} - ${keyword.artist}".encodeURLParameter(spaceToPlus = false)
            )
        }.body<SearchLyricsResponse>()

    private suspend fun searchLyricsByHash(hash: String) =
        client.get("https://lyrics.kugou.com/search") {
            parameter("ver", 1)
            parameter("man", "yes")
            parameter("client", "pc")
            parameter("hash", hash)
        }.body<SearchLyricsResponse>()

    private suspend fun downloadLyrics(id: Long, accessKey: String) =
        client.get("https://lyrics.kugou.com/download") {
            parameter("fmt", "lrc")
            parameter("charset", "utf8")
            parameter("client", "pc")
            parameter("ver", 1)
            parameter("id", id)
            parameter("accesskey", accessKey)
        }.body<DownloadLyricsResponse>()

    // Lazy, so each bracket goes on its own. Greedy, the first "(" and the last ")" took everything
    // between them, and "(It Goes Like) Nanana (Edit)" was searched for as an empty title.
    private fun normalizeTitle(title: String) =
        title.replace("\\(.*?\\)".toRegex(), "").replace("（.*?）".toRegex(), "")
            .replace("「.*?」".toRegex(), "").replace("『.*?』".toRegex(), "")
            .replace("<.*?>".toRegex(), "").replace("《.*?》".toRegex(), "")
            .replace("〈.*?〉".toRegex(), "").replace("＜.*?＞".toRegex(), "")

    private fun normalizeArtist(artist: String) =
        artist.replace(", ", "、").replace(" & ", "、").replace(".", "").replace("和", "、")
            .replace("\\(.*\\)".toRegex(), "").replace("（.*）".toRegex(), "")

    fun generateKeyword(title: String, artist: String) =
        Keyword(normalizeTitle(title), normalizeArtist(artist))

    private fun String.normalize(): String =
        replace("&apos;", "'").lines().filter { line -> line.matches(ACCEPTED_REGEX) }
            .let { lines ->
                // Remove useless information such as singer, writer, composer, guitar, etc.
                var headCutLine = 0
                for (i in min(HEAD_CUT_LIMIT, lines.lastIndex) downTo 0) {
                    if (lines[i].matches(BANNED_REGEX)) {
                        headCutLine = i + 1
                        break
                    }
                }
                val filteredLines = lines.drop(headCutLine)

                var tailCutLine = 0
                for (i in min(lines.size - HEAD_CUT_LIMIT, lines.lastIndex) downTo 0) {
                    if (lines[lines.lastIndex - i].matches(BANNED_REGEX)) {
                        tailCutLine = i + 1
                        break
                    }
                }
                val finalLines = filteredLines.dropLast(tailCutLine)

                return@let finalLines.joinToString("\n")
            }

    @Suppress("RegExpRedundantEscape")
    private val ACCEPTED_REGEX = "\\[(\\d\\d):(\\d\\d)\\.(\\d{2,3})\\].*".toRegex()
    private val BANNED_REGEX = ".+].+[:：].+".toRegex()

    private const val DURATION_TOLERANCE = 8
}
