/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.models

import androidx.datastore.preferences.core.preferencesOf
import com.dd3boh.outertune.constants.AiFilterKey
import com.dd3boh.outertune.constants.AiMarkedArtistsKey
import com.dd3boh.outertune.constants.AiNotArtistsKey
import com.dd3boh.outertune.db.entities.ArtistEntity
import com.dd3boh.outertune.db.entities.Song
import com.dd3boh.outertune.db.entities.SongEntity
import com.zionhuang.innertube.models.AlbumItem
import com.zionhuang.innertube.models.Artist
import com.zionhuang.innertube.models.ArtistItem
import com.zionhuang.innertube.models.PlaylistItem
import com.zionhuang.innertube.models.SongItem
import com.zionhuang.innertube.pages.HomePage
import com.zionhuang.innertube.pages.SearchSummary
import com.zionhuang.innertube.pages.SearchSummaryPage
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime

/** What the AI filter hides in each of its three states, and what it never touches. */
class AiFilterTest {

    // Channel ids as YouTube writes them: UC and 22 more characters.
    private val velvet = "UCLeR-eGh2OFy9ydX7IUcOTw"
    private val rust = "UCTH2Iy0erLYQSPRILNaG2sg"
    private val human = "UCxxxxxxxxxxxxxxxxxxxxx1"
    private val other = "UCxxxxxxxxxxxxxxxxxxxxx2"

    private val listed = setOf(velvet, rust)

    private fun filter(mode: AiFilterMode, markedAi: Set<String> = emptySet(), notAi: Set<String> = emptySet(), chosen: Set<String> = emptySet()) =
        AiFilter(mode, listed, markedAi, notAi, chosen)

    private val recommendations = filter(AiFilterMode.RECOMMENDATIONS)
    private val everywhere = filter(AiFilterMode.EVERYWHERE)

    private fun song(id: String, vararg artists: String?) =
        SongItem(id = id, title = id, artists = artists.map { Artist(it ?: "?", it) }, thumbnail = "")

    private fun media(id: String, vararg artists: String, inLibrary: Boolean = false, liked: Boolean = false, isLocal: Boolean = false) = MediaMetadata(
        id = id, title = id, artists = artists.map { MediaMetadata.Artist(it, it) }, duration = 200, genre = null,
        inLibrary = if (inLibrary) LocalDateTime.of(2026, 10, 1, 12, 0) else null, liked = liked, isLocal = isLocal,
    )

    private fun stored(id: String, vararg artists: String, inLibrary: Boolean = false, liked: Boolean = false, downloaded: Boolean = false) = Song(
        song = SongEntity(
            id = id, title = id, localPath = null, liked = liked,
            inLibrary = if (inLibrary) LocalDateTime.of(2026, 10, 1, 12, 0) else null,
            dateDownload = if (downloaded) LocalDateTime.of(2026, 10, 2, 12, 0) else null,
        ),
        artists = artists.map { ArtistEntity(id = it, name = it) },
    )

    private fun album(id: String, vararg artists: String) =
        AlbumItem(browseId = id, playlistId = "OLAK$id", title = id, artists = artists.map { Artist(it, it) }, thumbnail = "")

    private fun artist(id: String) = ArtistItem(id = id, title = id, thumbnail = null, shuffleEndpoint = null, radioEndpoint = null)

    private fun playlist(id: String, author: String?, editable: Boolean = false) = PlaylistItem(
        id = id, title = id, author = author?.let { Artist(it, it) }, songCountText = null, thumbnail = null,
        playEndpoint = null, shuffleEndpoint = null, radioEndpoint = null, isEditable = editable,
    )

    @After
    fun forgetTheQueue() = AiFilter.startedFrom(emptyList())

    // The three states

    @Test
    fun `off by default, and off hides nothing and hands back the same lists`() {
        val off = AiFilter()
        assertTrue(off.isOff)
        val items = listOf(song("a", velvet), song("b", human))
        assertSame(items, off.recommendations(items))
        assertSame(items, off.search(items).shown)
        assertEquals(0, off.search(items).hidden)
        assertFalse(off.hidesInRecommendations(song("a", velvet)))
        assertFalse(off.skips(media("a", velvet)))
        // Off with the list loaded is as off.
        assertFalse(filter(AiFilterMode.OFF).hidesInRecommendations(song("a", velvet)))
    }

    @Test
    fun `hide from recommendations hides a listed artist's songs there, and not in search or the queue`() {
        assertTrue(recommendations.hidesInRecommendations(song("a", velvet)))
        assertFalse(recommendations.hidesInRecommendations(song("b", human)))
        assertEquals(listOf("b"), recommendations.recommendations(listOf(song("a", velvet), song("b", human))).map { it.id })
        assertFalse(recommendations.hidesInSearch(song("a", velvet)))
        assertEquals(0, recommendations.search(listOf(song("a", velvet))).hidden)
        assertFalse(recommendations.skips(media("a", velvet)))
    }

    @Test
    fun `block everywhere hides them in recommendations and search, and the queue passes over them`() {
        assertTrue(everywhere.hidesInRecommendations(song("a", velvet)))
        assertTrue(everywhere.hidesInSearch(song("a", velvet)))
        val kept = everywhere.search(listOf(song("a", velvet), song("b", human), song("c", rust)))
        assertEquals(listOf("b"), kept.shown.map { it.id })
        assertEquals(2, kept.hidden)
        assertTrue(everywhere.skips(media("a", velvet)))
        assertFalse(everywhere.skips(media("b", human)))
    }

    // The rule

    @Test
    fun `a song by several artists is hidden when any one of them is an AI artist`() {
        assertTrue(recommendations.isAi(song("feat", human, velvet)))
        assertFalse(recommendations.isAi(song("duet", human, other)))
        // Not AI on one artist does not clear a song another listed artist is on.
        assertTrue(filter(AiFilterMode.RECOMMENDATIONS, notAi = setOf(velvet)).isAi(song("both", velvet, rust)))
        assertFalse(filter(AiFilterMode.RECOMMENDATIONS, notAi = setOf(velvet, rust)).isAi(song("both", velvet, rust)))
        // An artist without a link is no AI artist.
        assertFalse(recommendations.isAi(song("unlinked", null)))
    }

    @Test
    fun `the listener's marks win over the list, both ways`() {
        val marked = filter(AiFilterMode.RECOMMENDATIONS, markedAi = setOf(human))
        assertTrue(marked.isAiArtist(human))
        assertTrue(marked.hidesInRecommendations(song("h", human)))
        val notAi = filter(AiFilterMode.EVERYWHERE, notAi = setOf(velvet))
        assertFalse(notAi.isAiArtist(velvet))
        assertFalse(notAi.hidesInSearch(song("v", velvet)))
        assertFalse(notAi.skips(media("v", velvet)))
        // Not AI wins over a mark too, should both ever be stored.
        assertFalse(filter(AiFilterMode.RECOMMENDATIONS, markedAi = setOf(human), notAi = setOf(human)).isAiArtist(human))
    }

    @Test
    fun `albums and artists go by their artists, a playlist by its author unless it is the listener's own`() {
        assertTrue(everywhere.hidesInSearch(album("MPREa", velvet)))
        assertFalse(everywhere.hidesInSearch(album("MPREb", human)))
        assertTrue(everywhere.hidesInSearch(artist(rust)))
        assertFalse(everywhere.hidesInSearch(artist(human)))
        assertTrue(everywhere.hidesInSearch(playlist("PLa", velvet)))
        assertFalse(everywhere.hidesInSearch(playlist("PLmine", velvet, editable = true)))
        assertFalse(everywhere.hidesInSearch(playlist("PLnobody", null)))
    }

    // What the listener chose

    @Test
    fun `a song in the library, liked, downloaded or in a playlist they made is never touched`() {
        val chosen = filter(AiFilterMode.EVERYWHERE, chosen = setOf("saved"))
        // A song from YouTube's answers, known to be theirs by its id.
        assertFalse(chosen.hidesInSearch(song("saved", velvet)))
        assertFalse(chosen.hidesInRecommendations(song("saved", velvet)))
        assertTrue(chosen.hidesInSearch(song("unsaved", velvet)))
        // A song in the queue.
        assertFalse(chosen.skips(media("saved", velvet)))
        assertFalse(chosen.skips(media("lib", velvet, inLibrary = true)))
        assertFalse(chosen.skips(media("liked", velvet, liked = true)))
        assertFalse(chosen.skips(media("file", velvet, isLocal = true)))
        assertTrue(chosen.skips(media("radio", velvet)))
        // A song of the library's own rows.
        assertFalse(chosen.hidesInRecommendations(stored("lib", velvet, inLibrary = true)))
        assertFalse(chosen.hidesInRecommendations(stored("liked", velvet, liked = true)))
        assertFalse(chosen.hidesInRecommendations(stored("dl", velvet, downloaded = true)))
        assertFalse(chosen.hidesInRecommendations(stored("saved", velvet)))
        assertTrue(chosen.hidesInRecommendations(stored("heard", velvet)))
        assertEquals(listOf("lib"), chosen.recommendedSongs(listOf(stored("lib", velvet, inLibrary = true), stored("heard", velvet))).map { it.id })
    }

    @Test
    fun `a radio keeps the song it was started from, even an AI one, and says where it now is`() {
        val items = listOf(song("a", velvet), song("chosen", rust), song("ok", human), song("b", velvet))
        val (kept, at) = recommendations.radio(items, start = 1)
        assertEquals(listOf("chosen", "ok"), kept.map { it.id })
        assertEquals(0, at)
        assertEquals(emptyList<SongItem>() to 0, recommendations.radio(emptyList(), start = 0))
    }

    @Test
    fun `in a queue the artists of the song it was started from stay, another AI artist's songs do not`() {
        AiFilter.current = everywhere
        try {
            AiFilter.startedFrom(listOf(velvet))
            val queue = AiFilter.forQueue()
            assertFalse(queue.skips(media("v2", velvet)))
            assertTrue(queue.skips(media("r", rust)))
            assertEquals(listOf("v2", "h"), queue.recommendations(listOf(song("v2", velvet), song("r", rust), song("h", human))).map { it.id })
            // Elsewhere, search for one, the filter is as it was.
            assertTrue(AiFilter.current.hidesInSearch(song("v2", velvet)))
            // A queue started from another song forgets it.
            AiFilter.startedFrom(listOf(human, null))
            assertTrue(AiFilter.forQueue().skips(media("v2", velvet)))
        } finally {
            AiFilter.current = AiFilter()
        }
    }

    @Test
    fun `a playlist or an album the listener plays joins whole, a radio or a mix of YouTube's is thinned`() {
        AiFilter.current = everywhere
        try {
            AiFilter.startedFrom(listOf(human))
            val songs = listOf(song("v", velvet), song("h", human))
            // Played from a menu, a playlist or an album is a YouTube queue of its own id: chosen.
            for (id in listOf("PLrAXtmErZgOeiKm4sgNOknGvNjby9efdf", "OLAK5uy_kV3ls8v8yhy-UkNHUq5kUfhtE4SrWZEDY", "LM")) {
                assertTrue(id, AiFilter.isChosenList(id))
                assertEquals(id, listOf("v", "h"), AiFilter.forQueuePages(id).recommendations(songs).map { it.id })
            }
            // A song's radio, by its video alone or as RDAMVM, a playlist's (RDAMPL), an artist's
            // (RDEM) and YouTube's own mixes: recommendations.
            for (id in listOf(null, "RDAMVMxXFVEqRNk-s", "RDAMPLPLrAXtmErZgOe", "RDEMhqXuLJmJpLOi6YXdg6Zz8Q", "RDCLAK5uy_kmPRjHDECIcuVwnKsx2Ng7fyNgFKWNJFs")) {
                assertFalse(id.toString(), AiFilter.isChosenList(id))
                assertEquals(id.toString(), listOf("h"), AiFilter.forQueuePages(id).recommendations(songs).map { it.id })
            }
            // Block everywhere still passes over an AI song of a chosen list when the player comes to it.
            assertTrue(AiFilter.forQueue().skips(media("v", velvet)))
        } finally {
            AiFilter.current = AiFilter()
            AiFilter.startedFrom(emptyList())
        }
    }

    // Pages

    @Test
    fun `a row or a part of a search left empty goes, and the parts hidden are counted`() {
        val home = HomePage(
            chips = null,
            sections = listOf(
                HomePage.Section(title = "Only AI", label = null, thumbnail = null, endpoint = null, items = listOf(song("a", velvet))),
                HomePage.Section(title = "Mixed", label = null, thumbnail = null, endpoint = null, items = listOf(song("b", rust), song("ok", human))),
            ),
        )
        val shown = recommendations.home(home)
        assertEquals(listOf("Mixed"), shown.sections.map { it.title })
        assertEquals(listOf("ok"), shown.sections.single().items.map { it.id })
        assertSame(home, AiFilter().home(home))

        val search = SearchSummaryPage(listOf(
            SearchSummary("Top result", listOf(artist(velvet))),
            SearchSummary("Songs", listOf(song("v", velvet), song("ok", human), song("r", rust))),
        ))
        val kept = everywhere.search(search)
        assertEquals(listOf("Songs"), kept.shown.summaries.map { it.title })
        assertEquals(listOf("ok"), kept.shown.summaries.single().items.map { it.id })
        assertEquals(3, kept.hidden)
        assertEquals(0, recommendations.search(search).hidden)
    }

    // The artist menu's row

    @Test
    fun `Not AI and Mark as AI undo each other, for a listed artist and for one the list does not have`() {
        // A listed artist: Not AI, then Mark as AI, back to the list alone.
        val (m1, n1) = AiFilter.toggle(velvet, listed = true, markedAi = emptySet(), notAi = emptySet())
        assertEquals(emptySet<String>() to setOf(velvet), m1 to n1)
        assertFalse(AiFilter(AiFilterMode.RECOMMENDATIONS, listed, m1, n1).isAiArtist(velvet))
        val (m2, n2) = AiFilter.toggle(velvet, listed = true, markedAi = m1, notAi = n1)
        assertEquals(emptySet<String>() to emptySet<String>(), m2 to n2)
        // An artist the list does not have: Mark as AI, then Not AI, nothing stored.
        val (m3, n3) = AiFilter.toggle(human, listed = false, markedAi = emptySet(), notAi = emptySet())
        assertEquals(setOf(human) to emptySet<String>(), m3 to n3)
        assertTrue(AiFilter(AiFilterMode.RECOMMENDATIONS, listed, m3, n3).isAiArtist(human))
        val (m4, n4) = AiFilter.toggle(human, listed = false, markedAi = m3, notAi = n3)
        assertEquals(emptySet<String>() to emptySet<String>(), m4 to n4)
        // The other artists' marks are left as they were.
        assertEquals(setOf(other) to setOf(rust), AiFilter.toggle(human, listed = false, markedAi = setOf(other, human), notAi = setOf(rust)))
    }

    // The setting

    @Test
    fun `the setting is read as stored in a build with the flag, and as off in one without, whatever is stored`() {
        val stored = preferencesOf(AiFilterKey to "EVERYWHERE", AiMarkedArtistsKey to setOf(human), AiNotArtistsKey to setOf(velvet))
        val on = AiFilter.of(stored, listed, setOf("saved"), enabled = true)
        assertEquals(AiFilterMode.EVERYWHERE, on.mode)
        assertEquals(setOf(human), on.markedAi)
        assertEquals(setOf(velvet), on.notAi)
        assertEquals(setOf("saved"), on.chosenSongs)
        // A release: a restored backup can bring the setting on, and nothing is hidden all the same.
        assertEquals(AiFilter(), AiFilter.of(stored, listed, setOf("saved"), enabled = false))
        // Never set, or set to a state this build does not know: off.
        assertEquals(AiFilterMode.OFF, AiFilter.modeOf(preferencesOf(), enabled = true))
        assertEquals(AiFilterMode.OFF, AiFilter.modeOf(preferencesOf(AiFilterKey to "SOMETIMES"), enabled = true))
        assertEquals(AiFilterMode.RECOMMENDATIONS, AiFilter.modeOf(preferencesOf(AiFilterKey to "RECOMMENDATIONS"), enabled = true))
    }
}
