package com.zionhuang.innertube

import com.zionhuang.innertube.models.SongItem
import com.zionhuang.innertube.models.YouTubeClient.Companion.WEB_REMIX
import io.ktor.client.statement.bodyAsText
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * The songs under the top result card of an unfiltered search, as sent and as parsed.
 *
 * Seen on the emulator on 25 Sep: under the Daft Punk card every song read "5:38 • 5:38", the
 * length where the artist should be and again as the length. This prints each card song's flex
 * columns run by run beside what searchSummary made of it, so the parser can be fixed against what
 * YouTube actually sends.
 *
 * Skipped unless SUMMARY_PROBE=1, since it goes to the network.
 *
 *     SUMMARY_PROBE=1 ./gradlew :innertube:test --tests "*SummaryCardProbe*" -i
 */
class SummaryCardProbe {

    @Test
    fun probe() = runBlocking {
        assumeTrue(System.getenv("SUMMARY_PROBE") == "1")
        for (q in listOf("daft punk", "the weeknd", "blinding lights")) {
            val raw = InnerTube().search(WEB_REMIX, q).bodyAsText()
            val sections = Json.parseToJsonElement(raw).jsonObject["contents"]!!.jsonObject["tabbedSearchResultsRenderer"]!!
                .jsonObject["tabs"]!!.jsonArray[0].jsonObject["tabRenderer"]!!.jsonObject["content"]!!
                .jsonObject["sectionListRenderer"]!!.jsonObject["contents"]!!.jsonArray
            println("CARD ######## q=\"$q\" sections=${sections.map { it.jsonObject.keys.first() }}")
            for (s in sections) {
                val card = s.jsonObject["musicCardShelfRenderer"]?.jsonObject ?: continue
                card["contents"]?.jsonArray?.forEach { c ->
                    val r = c.jsonObject["musicResponsiveListItemRenderer"]?.jsonObject ?: return@forEach
                    val cols = r["flexColumns"]!!.jsonArray.map { col ->
                        (col.jsonObject["musicResponsiveListItemFlexColumnRenderer"]!!.jsonObject["text"] as? JsonObject)
                            ?.get("runs")?.jsonArray?.joinToString("") { run ->
                                val t = run.jsonObject["text"]!!.jsonPrimitive.content
                                if (run.jsonObject["navigationEndpoint"] != null) "[$t]" else t
                            }
                    }
                    println("CARD   cols=$cols")
                }
            }
            YouTube.searchSummary(q).getOrThrow().summaries.forEach { sum ->
                sum.items.filterIsInstance<SongItem>().forEach {
                    println("CARD   parsed [${sum.title}] ${it.title} | artists=${it.artists.map { a -> a.name }} album=${it.album?.name} duration=${it.duration}")
                }
            }
        }
    }
}
