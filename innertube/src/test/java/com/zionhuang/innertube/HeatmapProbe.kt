package com.zionhuang.innertube

import com.zionhuang.innertube.models.YouTubeClient.Companion.WEB
import io.ktor.client.statement.bodyAsText
import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * Where the most-replayed heatmap lives, established 10 Sep 2026.
 *
 * It is NOT in YouTube Music's responses: next and player on WEB_REMIX carry none of the marker
 * keys. It IS in the plain YouTube next response (WEB client) for the same track, under
 * frameworkUpdates -> macroMarkersListEntity -> markersList, as 100 equal segments each with
 * startMillis, durationMillis and intensityScoreNormalized. Segment 0 always scores 1.0, because
 * every viewer starts there, so the hook is the highest interior peak, not the maximum.
 *
 * Costs one extra request of about 330 KB per track, against 20 KB for the music next call.
 *
 * Skipped unless HEATMAP_PROBE=1 so an ordinary test run never touches the network:
 *
 *     HEATMAP_PROBE=1 ./gradlew :innertube:test --tests '*HeatmapProbe*' -i
 */
class HeatmapProbe {
    @Test
    fun probe() = runBlocking {
        assumeTrue(System.getenv("HEATMAP_PROBE") == "1")
        val body = InnerTube().next(WEB, "qivRUhepWVA", null, null, null, null, null).bodyAsText()
        val i = body.indexOf("macroMarkersListEntity")
        println("HEATMAP_PROBE index=$i")
        if (i >= 0) println("HEATMAP_PROBE shape=" + body.substring(i, minOf(body.length, i + 700)))
        val re = Regex("\"startMillis\":\"(\\d+)\",\"durationMillis\":\"(\\d+)\",\"intensityScoreNormalized\":([0-9.]+)")
        val all = re.findAll(body).toList()
        println("HEATMAP_PROBE markerCount=" + all.size)
        println("HEATMAP_PROBE first3=" + all.take(3).map { it.groupValues.drop(1).joinToString("/") })
        println("HEATMAP_PROBE peak=" + all.maxByOrNull { it.groupValues[3].toDouble() }?.groupValues?.drop(1)?.joinToString("/"))
        Unit
    }
}
