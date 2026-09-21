package com.zionhuang.innertube

import com.zionhuang.innertube.models.YouTubeClient.Companion.WEB_REMIX
import com.zionhuang.innertube.models.YouTubeLocale
import io.ktor.client.statement.bodyAsText
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * What the search response is actually shaped like, because the parser bets on its last section.
 *
 * YouTube.search reads tabs.firstOrNull().content.sectionListRenderer.contents.LASTORNULL()
 * .musicShelfRenderer, then .orEmpty() on the way out. So if the final section of a response is
 * anything other than a musicShelfRenderer the whole search returns an empty list and throws
 * nothing, which is indistinguishable in a log from YouTube not knowing the song.
 *
 * That matters because a signed-in account can be served sections an anonymous request is not.
 * This prints, per query, how many sections came back and what each one is, so the bet can be
 * judged rather than assumed.
 *
 * Skipped unless SHAPE_PROBE=1 so an ordinary test run and CI never touch the network.
 *
 *     SHAPE_PROBE=1 ./gradlew :innertube:test --tests "*SearchShapeProbe*" -i
 */
class SearchShapeProbe {

    private val queries = listOf(
        "Wind Resistance (Southbound Edit) Southbound",
        "Blinding Lights The Weeknd",
        // A query with no plausible answer: if a zero-result search still ends in a musicShelf,
        // the parser is safe for that case; if it ends in something else, the bet is already lost
        // for every miss, signed in or not.
        "zzqqxx no such song exists anywhere 40414",
    )

    @Test
    fun probe() = runBlocking {
        assumeTrue(System.getenv("SHAPE_PROBE") == "1")
        val filter = YouTube.SearchFilter.FILTER_SONG.value

        for ((gl, hl) in listOf("US" to "en", "FR" to "fr")) {
            val inner = InnerTube()
            inner.locale = YouTubeLocale(gl = gl, hl = hl)
            println("")
            println("SHAPE ######## locale $hl-$gl ########")

            for (q in queries) {
                val raw = inner.search(WEB_REMIX, q, filter).bodyAsText()
                val root = Json.parseToJsonElement(raw).jsonObject
                val tabs = root["contents"]?.jsonObject
                    ?.get("tabbedSearchResultsRenderer")?.jsonObject
                    ?.get("tabs")?.jsonArray
                if (tabs == null) {
                    println("SHAPE q=\"$q\" -> no tabbedSearchResultsRenderer at all, keys=${root.keys}")
                    continue
                }
                val sections = tabs.firstOrNull()?.jsonObject
                    ?.get("tabRenderer")?.jsonObject
                    ?.get("content")?.jsonObject
                    ?.get("sectionListRenderer")?.jsonObject
                    ?.get("contents")?.jsonArray
                if (sections == null) {
                    println("SHAPE q=\"$q\" -> no sectionListRenderer contents")
                    continue
                }
                val kinds = sections.map { it.jsonObject.keys.joinToString("+") }
                val last = kinds.lastOrNull()
                val shelves = kinds.count { it.contains("musicShelfRenderer") }
                println("SHAPE q=\"$q\"")
                println("SHAPE   ${sections.size} sections: $kinds")
                println("SHAPE   last=$last  musicShelves=$shelves  parserWouldSeeItems=${last?.contains("musicShelfRenderer")}")
            }
        }
    }
}
