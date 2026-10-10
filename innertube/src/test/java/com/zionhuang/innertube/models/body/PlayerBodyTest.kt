package com.zionhuang.innertube.models.body

import com.zionhuang.innertube.models.YouTubeClient
import com.zionhuang.innertube.models.YouTubeLocale
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** What a player request says, written out as InnerTube's own client writes it. */
@OptIn(ExperimentalSerializationApi::class)
class PlayerBodyTest {
    /** The settings of InnerTube.createClient. */
    private val json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
        encodeDefaults = true
    }

    private val context = YouTubeClient.WEB_REMIX.toContext(YouTubeLocale(gl = "FR", hl = "en"), "CgtWSVNJVE9S", null)

    @Test
    fun `a request as the app has always made it says nothing new`() {
        val body = PlayerBody(
            context = context,
            videoId = "TESTVIDEO01",
            playlistId = null,
            playbackContext = PlayerBody.PlaybackContext(PlayerBody.PlaybackContext.ContentPlaybackContext(20_381)),
        )
        val written = json.encodeToString(body)
        assertTrue(written, written.endsWith(""""videoId":"TESTVIDEO01","playbackContext":{"contentPlaybackContext":{"signatureTimestamp":20381}},"contentCheckOk":true,"racyCheckOk":true}"""))
        for (new in listOf("videoCheckOk", "html5Preference", "adPlaybackContext", "pyv")) {
            assertFalse(new, new in written)
        }
    }

    @Test
    fun `a request as the page makes it asks for a play without the ad before it`() {
        val body = PlayerBody(
            context = context,
            videoId = "TESTVIDEO01",
            playlistId = null,
            playbackContext = PlayerBody.PlaybackContext(
                PlayerBody.PlaybackContext.ContentPlaybackContext(20_381, html5Preference = "HTML5_PREF_WANTS"),
                adPlaybackContext = PlayerBody.PlaybackContext.AdPlaybackContext(),
            ),
            videoCheckOk = true,
        )
        val written = json.encodeToString(body)
        assertTrue(
            written,
            written.endsWith(
                """"playbackContext":{"contentPlaybackContext":{"signatureTimestamp":20381,"html5Preference":"HTML5_PREF_WANTS"},""" +
                    """"adPlaybackContext":{"pyv":true}},"contentCheckOk":true,"racyCheckOk":true,"videoCheckOk":true}""",
            ),
        )
        assertFalse("no po token unless one is given", "serviceIntegrityDimensions" in written)
    }

    @Test
    fun `an answer keeps the ads it schedules, and one without any reads as before`() {
        val plain = """{"responseContext":{},"playabilityStatus":{"status":"OK"}}"""
        val withAds = """{"responseContext":{},"playabilityStatus":{"status":"OK"},
            "adPlacements":[{"adPlacementRenderer":{"config":{"adPlacementConfig":{"kind":"AD_PLACEMENT_KIND_START"}}}}],
            "adSlots":[{"adSlotRenderer":{"adSlotMetadata":{"triggerEvent":"SLOT_TRIGGER_EVENT_BEFORE_CONTENT"}}}]}"""
        val response = com.zionhuang.innertube.models.response.PlayerResponse.serializer()
        assertEquals(null, json.decodeFromString(response, plain).adPlacements)
        val read = json.decodeFromString(response, withAds)
        assertEquals(1, read.adPlacements?.size)
        assertEquals(1, read.adSlots?.size)
    }
}
