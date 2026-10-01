package com.zionhuang.innertube

import com.zionhuang.innertube.models.getItems
import com.zionhuang.innertube.models.response.BrowseResponse
import com.zionhuang.innertube.pages.AlbumPage
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Albums whose playlist page lists no songs. Offline.
 *
 * The responses in test resources/album were saved by AlbumSongsProbe on 30 Sep 2026, signed out
 * in English, with the tracking fields, the response context and the "Releases for you" shelf
 * taken out. MPREb_dDFLAnEcWVu is one of the two "Five More Hours" albums that would not open from
 * Stats on 28 Sep: its playlist page, VLOLAK5uy_m6It3mo3KWd3MoWrScPP61eQt2x6TZ92o, is a microformat
 * saying noindex and nothing else. MPREb_hLZFwxCac4p is the other one, whose playlist page still
 * lists its song. On youtube.com the first playlist says "1 unavailable video is hidden".
 */
@OptIn(ExperimentalSerializationApi::class)
class AlbumPageFallbackTest {

    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false }

    private fun raw(name: String): String =
        javaClass.getResource("/album/$name.json")!!.readText()

    private fun browse(name: String): BrowseResponse = json.decodeFromString(raw(name))

    private fun listedOn(playlistPage: String) = AlbumPage.playlistShelf(browse(playlistPage))!!
        .contents.getItems().mapNotNull { AlbumPage.getSong(it) }

    @Test
    fun `the empty playlist page has no shelf to read`() {
        // albumSongs read this with a !! and threw a bare NullPointerException, which failed the
        // whole album. It now fails with EmptyPlaylistPage, which album falls back on.
        assertNull(AlbumPage.playlistShelf(browse("VLOLAK5uy_m6It3mo3KWd3MoWrScPP61eQt2x6TZ92o")))
    }

    @Test
    fun `with nothing from the playlist page the album lists the songs on its own page`() {
        val page = YouTube.albumPage("MPREb_dDFLAnEcWVu", browse("MPREb_dDFLAnEcWVu"), listedSongs = null)

        assertEquals("Five More Hours", page.album.title)
        assertEquals("OLAK5uy_m6It3mo3KWd3MoWrScPP61eQt2x6TZ92o", page.album.playlistId)
        assertEquals(listOf("Deorro", "Chris Brown"), page.album.artists?.map { it.name })
        assertEquals(2015, page.album.year)

        val song = page.songs.single()
        assertEquals("j3CaHeakZF4", song.id)
        assertEquals("Five More Hours", song.title)
        assertEquals(212, song.duration)
        // The row names no artist, since it is the album's, and has no cover of its own.
        assertEquals(listOf("Deorro", "Chris Brown"), song.artists.map { it.name })
        assertEquals(listOf("UC89YRh6abbGfAHln2-rGHRA", "UCMXDyVR2tclKWhbqNforSyA"), song.artists.map { it.id })
        assertEquals(page.album.thumbnail, song.thumbnail)
        assertEquals("MPREb_dDFLAnEcWVu", song.album?.id)
        assertEquals("Five More Hours", song.album?.name)

        assertEquals(1, page.otherVersions.size)
    }

    @Test
    fun `the songs the playlist page lists are still the ones used`() {
        val listed = listedOn("VLOLAK5uy_nHlcGzMzrlzyjd82XbcfULZKFjHuNNC_8")
        // The playlist page gives the audio track; the album's own page gives the music video.
        assertEquals(listOf("Syo-kNPln-Q"), listed.map { it.id })

        val page = YouTube.albumPage("MPREb_hLZFwxCac4p", browse("MPREb_hLZFwxCac4p"), listed)
        assertEquals(listed, page.songs)
        assertEquals("OLAK5uy_nHlcGzMzrlzyjd82XbcfULZKFjHuNNC_8", page.album.playlistId)
    }

    @Test
    fun `a playlist page that lists nobody is no better than none`() {
        val page = YouTube.albumPage("MPREb_hLZFwxCac4p", browse("MPREb_hLZFwxCac4p"), listedSongs = emptyList())
        assertEquals(listOf("j3CaHeakZF4"), page.songs.map { it.id })
    }

    @Test
    fun `with no songs on either page the album still comes back, with none`() {
        // The album page as saved, less the shelf of songs under its header.
        val root = json.parseToJsonElement(raw("MPREb_dDFLAnEcWVu")).jsonObject
        val withoutShelf = root.edit("contents", "twoColumnBrowseResultsRenderer", "secondaryContents", "sectionListRenderer") {
            JsonObject(it + ("contents" to JsonArray(it.getValue("contents").jsonArray.filterNot { s -> "musicShelfRenderer" in s.jsonObject })))
        }
        val response = json.decodeFromJsonElement(BrowseResponse.serializer(), withoutShelf)

        val page = YouTube.albumPage("MPREb_dDFLAnEcWVu", response, listedSongs = null)
        assertEquals("Five More Hours", page.album.title)
        assertEquals(listOf("Deorro", "Chris Brown"), page.album.artists?.map { it.name })
        assertTrue(page.songs.isEmpty())
    }

    @Test
    fun `an album asked for without songs still has none`() {
        val page = YouTube.albumPage("MPREb_dDFLAnEcWVu", browse("MPREb_dDFLAnEcWVu"), listedSongs = null, withSongs = false)
        assertTrue(page.songs.isEmpty())
        assertEquals("Five More Hours", page.album.title)
    }

    private fun JsonObject.edit(vararg path: String, change: (JsonObject) -> JsonObject): JsonObject {
        if (path.isEmpty()) return change(this)
        val key = path.first()
        return JsonObject(this + (key to getValue(key).jsonObject.edit(*path.drop(1).toTypedArray(), change = change)))
    }
}
