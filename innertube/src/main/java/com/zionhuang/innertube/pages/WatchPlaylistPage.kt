package com.zionhuang.innertube.pages

import com.zionhuang.innertube.models.Artist
import com.zionhuang.innertube.models.SongItem
import com.zionhuang.innertube.models.WatchEndpoint
import com.zionhuang.innertube.utils.parseTime
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull

/**
 * The mix in an answer of YouTube's own watch-next (www.youtube.com, InnerTube.watchNext): a
 * playlist panel at contents.twoColumnWatchNextResults.playlist.playlist for WEB, and at
 * contents.singleColumnWatchNextResults.playlist.playlist for ANDROID_VR, with the same rows in
 * both. A radio falls back to it when YouTube Music's own answer is refused (the radio ladder in
 * the app). Read from the JSON as it comes rather than through models: the two clients write
 * a title as runs or as simpleText, and nothing else of the answer is wanted.
 *
 * These are YouTube's videos, not YouTube Music's songs: the artist is the channel ("IU - Topic",
 * "이지금 [IU Official]"), with " - Topic" taken off; there is no album, no explicit mark and no
 * music video type. A row with no length (a live stream) or marked unplayable is left out.
 */
object WatchPlaylistPage {
    fun parse(answer: JsonObject, endpoint: WatchEndpoint): NextResult? {
        val contents = answer.obj("contents") ?: return null
        val playlist = (contents.obj("twoColumnWatchNextResults") ?: contents.obj("singleColumnWatchNextResults"))
            ?.obj("playlist")?.obj("playlist") ?: return null
        val rows = (playlist["contents"] as? JsonArray).orEmpty().mapNotNull { (it as? JsonObject)?.obj("playlistPanelVideoRenderer") }
        val songs = rows.mapNotNull { row -> song(row)?.let { it to (row.bool("selected") == true) } }
        if (songs.isEmpty()) return null
        return NextResult(
            title = playlist.text("title") ?: playlist.text("titleText"),
            items = songs.map { it.first },
            currentIndex = songs.indexOfFirst { it.second }.takeIf { it >= 0 }
                ?: songs.indexOfFirst { it.first.id == endpoint.videoId }.takeIf { it >= 0 },
            continuation = null,
            endpoint = endpoint,
        )
    }

    private fun song(row: JsonObject): SongItem? {
        if (row["unplayableText"] != null) return null
        val id = row.string("videoId") ?: return null
        val title = row.text("title")?.takeIf { it.isNotBlank() } ?: return null
        val duration = row.text("lengthText")?.parseTime() ?: return null
        val thumbnail = (row.obj("thumbnail")?.get("thumbnails") as? JsonArray)
            ?.mapNotNull { (it as? JsonObject)?.string("url") }?.lastOrNull() ?: return null
        val byline = (row.obj("longBylineText") ?: row.obj("shortBylineText"))
            ?.let { it["runs"] as? JsonArray }?.firstOrNull() as? JsonObject
        val artist = byline?.string("text")?.let(::channelName)?.takeIf { it.isNotBlank() }
        val artistId = byline?.obj("navigationEndpoint")?.obj("browseEndpoint")?.string("browseId")
        return SongItem(
            id = id,
            title = title,
            artists = listOfNotNull(artist?.let { Artist(name = it, id = artistId) }),
            duration = duration,
            thumbnail = thumbnail,
        )
    }

    /** The artist in a channel's name: YouTube's own artist channels are "<artist> - Topic". */
    fun channelName(name: String): String = name.trim().removeSuffix(" - Topic").trim()

    private fun JsonObject.obj(key: String) = this[key] as? JsonObject
    private fun JsonObject.string(key: String) = (this[key] as? JsonPrimitive)?.contentOrNull
    private fun JsonObject.bool(key: String) = (this[key] as? JsonPrimitive)?.booleanOrNull

    /** A title as runs or as simpleText. */
    private fun JsonObject.text(key: String): String? = when (val v: JsonElement? = this[key]) {
        is JsonPrimitive -> v.contentOrNull
        is JsonObject -> (v["simpleText"] as? JsonPrimitive)?.contentOrNull
            ?: (v["runs"] as? JsonArray)?.joinToString("") { ((it as? JsonObject)?.get("text") as? JsonPrimitive)?.contentOrNull.orEmpty() }
                ?.takeIf { it.isNotEmpty() }
        else -> null
    }
}
