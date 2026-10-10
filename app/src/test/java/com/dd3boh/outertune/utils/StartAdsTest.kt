/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.utils

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Test

/** How long the ads before a song hold a web client's address back. Every answer here is made up, in the shape yt-dlp reads. */
class StartAdsTest {
    private fun list(json: String): List<JsonObject> = Json.parseToJsonElement(json).jsonArray.map { it.jsonObject }

    private fun placement(kind: String, ad: String) =
        """{"adPlacementRenderer":{"config":{"adPlacementConfig":{"kind":"$kind"}},"renderer":{"instreamVideoAdRenderer":$ad}}}"""

    private fun slot(trigger: String, content: String) =
        """{"adSlotRenderer":{"adSlotMetadata":{"triggerEvent":"$trigger"},"fulfillmentContent":{"fulfilledLayout":{"playerBytesAdLayoutRenderer":{"renderingContent":$content}}}}}"""

    private val fifteenSeconds = """{"playerVars":"autoplay=1&length_seconds=15&video_id=AD"}"""
    private val skippableAfterFive = """{"playerVars":"length_seconds=30","skipOffsetMilliseconds":5000}"""

    @Test
    fun `an answer with no ad holds nothing back`() {
        assertEquals(0L, StartAds.waitMs(null, null))
        assertEquals(0L, StartAds.waitMs(emptyList(), emptyList()))
    }

    @Test
    fun `an ad at the start counts for its length, and one that can be skipped for the time until it can`() {
        assertEquals(15_000L, StartAds.waitMs(list("[" + placement("AD_PLACEMENT_KIND_START", fifteenSeconds) + "]"), null))
        assertEquals(5_000L, StartAds.waitMs(list("[" + placement("AD_PLACEMENT_KIND_START", skippableAfterFive) + "]"), null))
    }

    @Test
    fun `an ad in the middle or at the end of the song is not waited for`() {
        val later = list("[" + placement("AD_PLACEMENT_KIND_MILLISECONDS", fifteenSeconds) + "," + placement("AD_PLACEMENT_KIND_END", fifteenSeconds) + "]")
        assertEquals(0L, StartAds.waitMs(later, null))
    }

    @Test
    fun `ads in a slot before the content count, alone or several in a row, and add up with the placed ones`() {
        val alone = slot("SLOT_TRIGGER_EVENT_BEFORE_CONTENT", """{"instreamVideoAdRenderer":$fifteenSeconds}""")
        val inARow = slot(
            "SLOT_TRIGGER_EVENT_BEFORE_CONTENT",
            """{"playerBytesSequentialLayoutRenderer":{"sequentialLayouts":[
                {"playerBytesAdLayoutRenderer":{"renderingContent":{"instreamVideoAdRenderer":$fifteenSeconds}}},
                {"playerBytesAdLayoutRenderer":{"renderingContent":{"instreamVideoAdRenderer":$skippableAfterFive}}}]}}""",
        )
        val afterwards = slot("SLOT_TRIGGER_EVENT_LAYOUT_ID_EXITED", """{"instreamVideoAdRenderer":$fifteenSeconds}""")
        assertEquals(15_000L, StartAds.waitMs(null, list("[$alone]")))
        assertEquals(20_000L, StartAds.waitMs(null, list("[$inARow]")))
        assertEquals(0L, StartAds.waitMs(null, list("[$afterwards]")))
        assertEquals(35_000L, StartAds.waitMs(list("[" + placement("AD_PLACEMENT_KIND_START", fifteenSeconds) + "]"), list("[$inARow]")))
    }

    @Test
    fun `an ad that says nothing of its length, or says it in a shape not known here, is not waited for`() {
        val silent = list("[" + placement("AD_PLACEMENT_KIND_START", """{"playerVars":"video_id=AD"}""") + "]")
        val odd = list("""[{"adPlacementRenderer":{"config":"START","renderer":["instreamVideoAdRenderer"]}},{"adPlacementRenderer":null}]""")
        assertEquals(0L, StartAds.waitMs(silent, null))
        assertEquals(0L, StartAds.waitMs(odd, null))
    }

    @Test
    fun `an address is tried at once and six seconds on, and a third time only when the ad runs longer`() {
        assertEquals(listOf(0L, 6_000L), StartAds.pausesMs(0))
        assertEquals("skippable after five seconds: over before the second try", listOf(0L, 6_000L), StartAds.pausesMs(5_000))
        assertEquals("a second past its end", listOf(0L, 6_000L, 10_000L), StartAds.pausesMs(15_000))
        assertEquals("never held longer than thirty seconds in all", listOf(0L, 6_000L, 24_000L), StartAds.pausesMs(120_000))
        assertEquals(StartAds.LONGEST_MS, StartAds.pausesMs(120_000).sum())
    }
}
