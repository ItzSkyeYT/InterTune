package com.zionhuang.innertube

import com.zionhuang.innertube.models.MusicPlaylistShelfRenderer
import com.zionhuang.innertube.models.getContinuation
import com.zionhuang.innertube.models.getItems
import com.zionhuang.innertube.models.response.BrowseResponse
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A playlist that is empty on YouTube. Offline.
 *
 * On 30 Sep the release build's sync logged "Field 'contents' is required for type with serial
 * name MusicPlaylistShelfRenderer, but it was missing at path:
 * $.contents.twoColumnBrowseResultsRenderer.secondaryContents.sectionListRenderer.contents[0]
 * .musicPlaylistShelfRenderer" for six of the maintainer's playlists, all with a song count of 0 on
 * YouTube. They are private, so the response could not be saved signed out: the fixture is written
 * by hand to that path, with only the fields the error shows were there.
 */
@OptIn(ExperimentalSerializationApi::class)
class EmptyPlaylistShelfTest {

    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false }

    private fun shelf(): MusicPlaylistShelfRenderer? {
        val raw = javaClass.getResource("/playlist/empty-playlist-shelf.json")!!.readText()
        val response = json.decodeFromString<BrowseResponse>(raw)
        return response.contents?.twoColumnBrowseResultsRenderer?.secondaryContents?.sectionListRenderer
            ?.contents?.firstOrNull()?.musicPlaylistShelfRenderer
    }

    @Test
    fun `an empty playlist reads as no songs instead of failing`() {
        val shelf = shelf()!!
        assertEquals("PLtest_empty_playlist", shelf.playlistId)
        assertTrue(shelf.contents.isEmpty())
        assertTrue(shelf.contents.getItems().isEmpty())
        assertNull(shelf.contents.getContinuation())
    }
}
