/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.playback.queues

import com.zionhuang.innertube.YouTube
import com.zionhuang.innertube.models.WatchEndpoint
import com.zionhuang.innertube.models.response.NextResponse
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * YouTube's watch-next answer with no queue in it, read as a queue with no songs. Offline.
 *
 * Issue #28 (0.12, a phone signed in): every song tapped showed "plr: null" beside the player's own
 * error. YouTube answered the account's watch-next for the song with an Up next tab that holds no
 * queue, and YouTube.next read it through `!!`: a NullPointerException with no message, which
 * MusicService.playQueue showed as it was. YouTube answers that way for an id that is no video;
 * on that phone it answered so for songs that play anywhere else. The tapped song was already in
 * the player, so the answer only had nothing to add to it (QueueStartTest: an empty answer leaves
 * the tapped song as the whole queue).
 *
 * Both answers are YouTube's of 29 Oct 2026, cut down to what the parser reads: as WEB_REMIX,
 * signed out, for "AAAAAAAAAAA", and for UgmY2sB71Hc, the song in the report, with its first row
 * only.
 */
class NextWithoutQueueTest {

    // As InnerTube's own client decodes every answer.
    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false; encodeDefaults = true }

    private fun answer(text: String) = json.decodeFromString(NextResponse.serializer(), text)

    @Test
    fun `an answer with no queue is a queue with no songs, not a failure`() {
        val endpoint = WatchEndpoint(videoId = "AAAAAAAAAAA")
        val result = YouTube.nextResult(answer(NO_QUEUE), endpoint)

        assertTrue(result.items.isEmpty())
        assertNull(result.currentIndex)
        assertNull("nothing to go on from", result.continuation)
        // Nothing to name the tapped song's queue of one after: YouTube's header over no queue
        // reads "Your Queue", and every such tap would have left one more queue of that name.
        assertNull(result.title)
        assertEquals(endpoint, result.endpoint)
        // The tabs with nothing behind them stay nothing, as before.
        assertNull(result.lyricsEndpoint)
        assertNull(result.relatedEndpoint)
    }

    @Test
    fun `an answer with a queue gives its songs, the one playing and its tabs, as before`() {
        val endpoint = WatchEndpoint(videoId = "UgmY2sB71Hc")
        val result = YouTube.nextResult(answer(WITH_QUEUE), endpoint)

        assertEquals(listOf("UgmY2sB71Hc"), result.items.map { it.id })
        assertEquals("I'm Not Okay (I Promise)", result.items.single().title)
        assertEquals(0, result.currentIndex)
        assertEquals("I'm Not Okay (I Promise) Mix", result.title)
        assertTrue(result.lyricsEndpoint!!.browseId.startsWith("MPLY"))
        assertTrue(result.relatedEndpoint!!.browseId.startsWith("MPTR"))
        assertEquals(endpoint, result.endpoint)
    }

    private companion object {
        val NO_QUEUE = """
            {"contents":{"singleColumnMusicWatchNextResultsRenderer":{"tabbedRenderer":{"watchNextTabbedResultsRenderer":{"tabs":[
              {"tabRenderer":{"title":"Up next","content":{"musicQueueRenderer":{"hack":true,"musicQueueConfig":{},
                "header":{"musicQueueHeaderRenderer":{"title":{"runs":[{"text":"Playing from"}]},"subtitle":{"runs":[{"text":"Your Queue"}]}}}}}}},
              {"tabRenderer":{"title":"Lyrics","unselectable":true}},
              {"tabRenderer":{"title":"Comments","content":{},"unselectable":true}},
              {"tabRenderer":{"title":"Related","unselectable":true}}
            ]}}}},
            "currentVideoEndpoint":{"watchEndpoint":{"videoId":"AAAAAAAAAAA"}}}
        """.trimIndent()

        val WITH_QUEUE = """
            {"contents":{"singleColumnMusicWatchNextResultsRenderer":{"tabbedRenderer":{"watchNextTabbedResultsRenderer":{"tabs":[
              {"tabRenderer":{"title":"Up next","content":{"musicQueueRenderer":{
                "content":{"playlistPanelRenderer":{"isInfinite":true,"contents":[
                  {"playlistPanelVideoRenderer":{
                    "title":{"runs":[{"text":"I'm Not Okay (I Promise)"}]},
                    "longBylineText":{"runs":[
                      {"text":"My Chemical Romance","navigationEndpoint":{"browseEndpoint":{"browseId":"UC527A_XB_c7XftocVOIVNeA","browseEndpointContextSupportedConfigs":{"browseEndpointContextMusicConfig":{"pageType":"MUSIC_PAGE_TYPE_ARTIST"}}}}},
                      {"text":" • "},
                      {"text":"Three Cheers for Sweet Revenge","navigationEndpoint":{"browseEndpoint":{"browseId":"MPREb_4KwRu9kwmAY","browseEndpointContextSupportedConfigs":{"browseEndpointContextMusicConfig":{"pageType":"MUSIC_PAGE_TYPE_ALBUM"}}}}},
                      {"text":" • "},
                      {"text":"2004"}]},
                    "thumbnail":{"thumbnails":[{"url":"https://yt3.googleusercontent.com/cover=w544-h544","width":544,"height":544}]},
                    "lengthText":{"runs":[{"text":"3:07"}]},
                    "selected":true,
                    "navigationEndpoint":{"watchEndpoint":{"videoId":"UgmY2sB71Hc","watchEndpointMusicSupportedConfigs":{"watchEndpointMusicConfig":{"musicVideoType":"MUSIC_VIDEO_TYPE_ATV"}}}},
                    "videoId":"UgmY2sB71Hc",
                    "shortBylineText":{"runs":[{"text":"My Chemical Romance"}]},
                    "badges":[{"musicInlineBadgeRenderer":{"icon":{"iconType":"MUSIC_EXPLICIT_BADGE"}}}]}}]}},
                "header":{"musicQueueHeaderRenderer":{"title":{"runs":[{"text":"Playing from"}]},"subtitle":{"runs":[{"text":"I'm Not Okay (I Promise) Mix"}]}}}}}}},
              {"tabRenderer":{"title":"Lyrics","endpoint":{"browseEndpoint":{"browseId":"MPLYt_4KwRu9kwmAY-5","browseEndpointContextSupportedConfigs":{"browseEndpointContextMusicConfig":{"pageType":"MUSIC_PAGE_TYPE_TRACK_LYRICS"}}}}}},
              {"tabRenderer":{"title":"Comments"}},
              {"tabRenderer":{"title":"Related","endpoint":{"browseEndpoint":{"browseId":"MPTRt_4KwRu9kwmAY-5","browseEndpointContextSupportedConfigs":{"browseEndpointContextMusicConfig":{"pageType":"MUSIC_PAGE_TYPE_TRACK_RELATED"}}}}}}
            ]}}}},
            "currentVideoEndpoint":{"watchEndpoint":{"videoId":"UgmY2sB71Hc"}}}
        """.trimIndent()
    }
}
