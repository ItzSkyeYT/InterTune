package com.zionhuang.innertube

import com.zionhuang.innertube.models.WatchEndpoint
import com.zionhuang.innertube.models.YouTubeClient.Companion.WEB_REMIX
import io.ktor.client.statement.bodyAsText
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * The shape of the "related" page, established 11 Sep 2026 for the recommendation engine.
 *
 * Five carousel shelves: songs you might also like, recommended playlists, OTHER PERFORMANCES OF
 * THE SAME SONG, similar artists, and the artist. The parser in YouTube.related folds every song
 * from every shelf into one list, so the other-performances shelf (live cuts, remixes, covers)
 * lands in related_song_map as if those were recommendations. No song repeats across shelves.
 *
 * Titles are localised, so this prints each shelf's header keys to find something stable.
 * Skipped unless RELATED_PROBE=1 so an ordinary test run never touches the network.
 */
class RelatedShelvesProbe {
    @Test
    fun probe() = runBlocking {
        assumeTrue(System.getenv("RELATED_PROBE") == "1")
        for ((gl, hl) in listOf("US" to "en")) {
        YouTube.locale = com.zionhuang.innertube.models.YouTubeLocale(gl = gl, hl = hl)
        val ep = YouTube.next(WatchEndpoint(videoId = System.getenv("RELATED_VIDEO") ?: "fJ9rUzIMcZQ")).getOrThrow().relatedEndpoint!!
        val it = InnerTube(); it.locale = YouTube.locale
        val raw = it.browse(WEB_REMIX, ep.browseId).bodyAsText()
        println("RELPROBE locale=$hl-$gl")
        val sections = Json.parseToJsonElement(raw).jsonObject["contents"]!!.jsonObject["sectionListRenderer"]!!
            .jsonObject["contents"]!!.jsonArray
        sections.forEachIndexed { i, s ->
            val shelf = s.jsonObject["musicCarouselShelfRenderer"]?.jsonObject ?: return@forEachIndexed
            val header = shelf["header"]?.jsonObject?.get("musicCarouselShelfBasicHeaderRenderer")?.jsonObject
            fun keys(o: JsonObject?, depth: Int = 0): String = o?.entries?.joinToString(",") { (k, v) ->
                if (depth < 2 && v is JsonObject) "$k{${keys(v, depth + 1)}}" else k } ?: "none"
            val title = header?.get("title")?.jsonObject?.get("runs")?.jsonArray?.joinToString("") { r -> r.jsonObject["text"].toString().trim('"') }
            val songs = shelf["contents"]!!.jsonArray.count { c -> c.jsonObject.containsKey("musicResponsiveListItemRenderer") }
            println("RELPROBE   shelf $i '$title' songRows=$songs grid=${shelf.containsKey("numItemsPerColumn")}")
            shelf["contents"]!!.jsonArray.mapNotNull { c -> c.jsonObject["musicResponsiveListItemRenderer"]?.jsonObject }.forEach { r ->
                val t = r["flexColumns"]?.jsonArray?.firstOrNull()?.jsonObject?.get("musicResponsiveListItemFlexColumnRenderer")?.jsonObject?.get("text")?.jsonObject?.get("runs")?.jsonArray?.joinToString("") { x -> x.jsonObject["text"].toString().trim('"') }
                val atv = r.toString().contains("MUSIC_VIDEO_TYPE_ATV")
                println("RELPROBE       ${if (atv) "ATV " else "vid "} $t")
            }
        }
        }
    }

    /** What YouTube.related itself now returns: the related shelf and the other performances, apart. */
    @Test
    fun parserSplit() = runBlocking {
        assumeTrue(System.getenv("RELATED_PROBE") == "1")
        YouTube.locale = com.zionhuang.innertube.models.YouTubeLocale(gl = "US", hl = "en")
        val ep = YouTube.next(WatchEndpoint(videoId = System.getenv("RELATED_VIDEO") ?: "HzdD8kbDzZA")).getOrThrow().relatedEndpoint!!
        val page = YouTube.related(ep).getOrThrow()
        println("RELSPLIT songs=${page.songs.size} otherPerformances=${page.otherPerformances.size}")
        println("RELSPLIT other: ${page.otherPerformances.map { it.title }}")
        println("RELSPLIT seed-titled left in songs: ${page.songs.filter { it.title.lowercase().startsWith("take on me") }.map { it.title }}")
    }
}
