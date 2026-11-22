/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.recap

import com.zionhuang.innertube.InnerTube
import com.zionhuang.innertube.models.body.NextBody
import io.ktor.client.request.HttpRequestBuilder
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.File

/**
 * Where a song starts under a slide: the highest point of YouTube's Most replayed heat map after
 * the first 5% (RecapHeat), read from the answer one masked WEB `next` gives, and where its piece
 * would have started when there is none. The answers are YouTube's own for public songs, saved by
 * the research of 25 Oct 2026 (experiments/research/ai-label-and-best-parts-1025/raw).
 */
class RecapHeatTest {
    private fun answer(name: String) = File("src/test/resources/recap/$name").readText()

    private fun even(vararg scores: Double, lengthMs: Long = 2_000L) =
        scores.mapIndexed { i, s -> HeatMarker(i * lengthMs, lengthMs, s) }

    @Test
    fun `the markers are read from a real answer, in time order`() {
        // LoveGame's Topic track (EJilqoZ3ExY), 217 s, with the description panels the mask also asked for.
        val markers = RecapHeat.markers(answer("heat_EJilqoZ3ExY.json"))!!
        assertEquals(100, markers.size)
        assertEquals(HeatMarker(0, 2_170, 1.0), markers.first())
        assertEquals(217_000L, markers.last().startMs + markers.last().lengthMs)
        assertEquals(markers.sortedBy { it.startMs }, markers)
    }

    @Test
    fun `its start is its highest point after the first 5 percent, not the start everyone replays nor the label`() {
        val markers = RecapHeat.markers(answer("heat_EJilqoZ3ExY.json"))!!
        // The first marker is the highest of all (everyone replays the start), and YouTube's own
        // "Most replayed" label sits at 4.34 s: neither is the peak. The 22nd marker is, at 45.57 s,
        // 8 s before the first chorus as the research found it.
        assertEquals(45_570L, RecapHeat.peakMs(markers))
        assertEquals(45_570L, RecapHeat.startMs(RecapPiece.of(217, 10_000), local = false, markers = markers))
    }

    @Test
    fun `a song with no heat map comes back empty, and starts a third of the way in`() {
        // XiDQ8k2ceNs, 1,930 views: the masked path is there with nothing in it.
        assertNull(RecapHeat.markers(answer("heat_none_XiDQ8k2ceNs.json")))
        val piece = RecapPiece.of(200, 10_000)
        assertEquals(piece.fromMs, RecapHeat.startMs(piece, local = false, markers = null))
    }

    @Test
    fun `an answer that is not one, or a heat map of another kind, is no heat map`() {
        assertNull(RecapHeat.markers(""))
        assertNull(RecapHeat.markers("<html>"))
        assertNull(RecapHeat.markers("{}"))
        val chapters = """{"frameworkUpdates":{"entityBatchUpdate":{"mutations":[{"payload":{"macroMarkersListEntity":{"markersList":
            {"markerType":"MARKER_TYPE_CHAPTERS","markers":[{"startMillis":"0","durationMillis":"1000","intensityScoreNormalized":1}]}}}}]}}}"""
        assertNull(RecapHeat.markers(chapters))
    }

    @Test
    fun `a local file is never looked up and starts a third of the way in`() {
        val markers = even(1.0, 0.1, 0.1, 0.1, 0.1, 0.1, 0.9, 0.1, 0.1, 0.1)
        val piece = RecapPiece.of(180, 10_000)
        assertEquals(60_000L, RecapHeat.startMs(piece, local = true, markers = markers))
    }

    @Test
    fun `the first 5 percent is passed over`() {
        // 100 markers of 1 s: the highest are in the first five, the best after them at 40 s.
        val scores = DoubleArray(100) { 0.2 }.also { it[0] = 1.0; it[4] = 0.99; it[40] = 0.7; it[60] = 0.5 }
        val markers = even(*scores, lengthMs = 1_000L)
        assertEquals(40_000L, RecapHeat.peakMs(markers))
        // The fifth marker on is in.
        scores[5] = 0.8
        assertEquals(5_000L, RecapHeat.peakMs(even(*scores, lengthMs = 1_000L)))
    }

    @Test
    fun `of equal points the first, and a peak that would leave the slide without its song is passed over`() {
        val scores = DoubleArray(100) { 0.1 }.also { it[30] = 0.9; it[70] = 0.9; it[95] = 1.0 }
        val markers = even(*scores, lengthMs = 2_000L)
        // 200 s: the highest is at 190 s, which leaves 10 s.
        assertEquals(190_000L, RecapHeat.peakMs(markers))
        assertEquals(60_000L, RecapHeat.peakMs(markers, keepMs = 12_000L))
        // Nothing qualifies: the piece's own start.
        val late = even(*DoubleArray(10) { 0.1 }.also { it[9] = 1.0 }, lengthMs = 1_000L)
        val piece = RecapPiece(3_000L, 9_500L)
        assertEquals(3_000L, RecapHeat.startMs(piece, local = false, markers = late))
    }

    @Test
    fun `the request asks for the heat map alone, as a visitor, whatever account the app is signed in to`() {
        val tube = InnerTube().apply {
            visitorData = "visitor-1"
            dataSyncId = "channel-1"
            cookie = "SID=one; SAPISID=two; __Secure-3PAPISID=three"
            useLoginForBrowse = true
        }
        val request = HttpRequestBuilder().apply { with(tube) { heatMapRequest("EJilqoZ3ExY") } }
        assertNull("the cookie is the sign-in", request.headers["cookie"])
        assertNull(request.headers["Authorization"])
        assertEquals(InnerTube.HEAT_MAP_MASK, request.headers["X-Goog-FieldMask"])
        assertEquals("frameworkUpdates.entityBatchUpdate.mutations.payload.macroMarkersListEntity.markersList", InnerTube.HEAT_MAP_MASK)
        // The plain YouTube client: YouTube Music's answers carry no heat map.
        assertEquals("1", request.headers["X-YouTube-Client-Name"])
        val body = Json { explicitNulls = false; encodeDefaults = true }
            .encodeToJsonElement(NextBody.serializer(), request.body as NextBody).jsonObject
        assertEquals("EJilqoZ3ExY", body["videoId"]!!.jsonPrimitive.content)
        val context = body["context"]!!.jsonObject
        assertEquals("WEB", context["client"]!!.jsonObject["clientName"]!!.jsonPrimitive.content)
        assertEquals("visitor-1", context["client"]!!.jsonObject["visitorData"]!!.jsonPrimitive.content)
        assertNull("nobody it is on behalf of", context["user"]?.jsonObject?.get("onBehalfOfUser"))
    }
}
