package com.zionhuang.innertube

import com.zionhuang.innertube.models.Artist
import com.zionhuang.innertube.models.MusicResponsiveListItemRenderer
import com.zionhuang.innertube.models.SongItem
import com.zionhuang.innertube.pages.SearchSummaryPage
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The songs under an artist's top result card, which leave the artist out. Shapes copied from what
 * SummaryCardProbe printed on 25 Sep. Offline.
 */
@OptIn(ExperimentalSerializationApi::class)
class SearchSummaryCardTest {

    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false }

    private fun row(second: String, third: String = "1.2bn plays") = json.decodeFromString<MusicResponsiveListItemRenderer>(
        """
        {"flexColumns": [
          {"musicResponsiveListItemFlexColumnRenderer": {"text": {"runs": [{"text": "Instant Crush (feat. Julian Casablancas)"}]}}},
          {"musicResponsiveListItemFlexColumnRenderer": {"text": {"runs": $second}}},
          {"musicResponsiveListItemFlexColumnRenderer": {"text": {"runs": [{"text": "$third"}]}}}
        ],
        "playlistItemData": {"videoId": "a5uQMwRMHcs"},
        "thumbnail": {"musicThumbnailRenderer": {"thumbnail": {"thumbnails": [{"url": "https://i/x.jpg", "width": 60, "height": 60}]}}}}
        """.trimIndent()
    )

    private val lengthOnly = """[{"text": "Song"}, {"text": " • "}, {"text": "5:38"}]"""

    @Test
    fun `a card's song takes the card's artist, not its own length`() {
        val daftPunk = Artist(name = "Daft Punk", id = "UC_kRDKYrUlrbtrSiyu5Tflg")
        val song = SearchSummaryPage.fromMusicResponsiveListItemRenderer(row(lengthOnly), daftPunk) as SongItem
        assertEquals(listOf("Daft Punk"), song.artists.map { it.name })
        assertEquals(338, song.duration)
    }

    @Test
    fun `without a card it has no artist rather than a length for one`() {
        val song = SearchSummaryPage.fromMusicResponsiveListItemRenderer(row(lengthOnly)) as SongItem
        assertEquals(emptyList<String>(), song.artists.map { it.name })
        assertEquals(338, song.duration)
    }

    @Test
    fun `an ordinary row keeps its own artist`() {
        val withArtist = """[{"text": "Song"}, {"text": " • "}, {"text": "The Weeknd", "navigationEndpoint": {"browseEndpoint": {"browseId": "UClYV6hHlupm_S_ObS1W-DYw"}}}, {"text": " • "}, {"text": "3:22"}]"""
        val song = SearchSummaryPage.fromMusicResponsiveListItemRenderer(row(withArtist), Artist("Daft Punk", null)) as SongItem
        assertEquals(listOf("The Weeknd"), song.artists.map { it.name })
        assertEquals(202, song.duration)
    }
}
