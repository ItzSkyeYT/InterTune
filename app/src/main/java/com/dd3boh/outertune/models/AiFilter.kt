/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.models

import androidx.datastore.preferences.core.Preferences
import com.dd3boh.outertune.constants.AiFilterKey
import com.dd3boh.outertune.constants.AiMarkedArtistsKey
import com.dd3boh.outertune.constants.AiNotArtistsKey
import com.dd3boh.outertune.constants.Unreleased
import com.dd3boh.outertune.db.entities.Song
import com.dd3boh.outertune.extensions.toEnum
import com.zionhuang.innertube.models.AlbumItem
import com.zionhuang.innertube.models.ArtistItem
import com.zionhuang.innertube.models.PlaylistItem
import com.zionhuang.innertube.models.SongItem
import com.zionhuang.innertube.models.YTItem
import com.zionhuang.innertube.pages.HomePage
import com.zionhuang.innertube.pages.SearchSummaryPage
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** The three states of the AI filter's setting. */
enum class AiFilterMode { OFF, RECOMMENDATIONS, EVERYWHERE }

/**
 * What the AI filter under Settings > Library and content keeps out (Unreleased.AI_FILTER).
 *
 * No AI label can be read from YouTube's answers yet (TODO section 17), so this goes by a list: the
 * YouTube channel ids of artists on hand-made lists of AI music ([listed], AiArtistList), and what
 * the listener says of an artist in its long-press menu, Mark as AI ([markedAi]) or Not AI
 * ([notAi]), which wins over the list. Artist ids are YouTube channel ids, the namespace of
 * SongItem.artists[].id and of a YouTube ArtistEntity (an id starting with "UC"), so this is a set
 * lookup on ids the app already holds.
 *
 * Off, the default, hides nothing. Hide from recommendations keeps AI songs out of Home and its
 * rows, Quick picks among them, and out of what a radio or autoplay adds to the queue. Block
 * everywhere also keeps them out of search results, with a line saying how many and a tap to show
 * them, and a listed song that reaches the queue all the same is skipped when the player comes to
 * it by itself.
 *
 * What the listener chose is never touched, in any state: songs in the library, liked or
 * downloaded, the songs of the playlists they made ([chosenSongs]), the song a radio is started
 * from, and in a queue the artists of the song it was started from ([forQueue]). An artist page
 * opened by name is not filtered either, and a playlist or an album they play joins the queue whole
 * ([forQueuePages]), Block everywhere passing over its AI songs as the player comes to them.
 *
 * A song by several artists is hidden when any one of them is an AI artist: listed or marked as AI,
 * and not marked Not AI. A featured AI voice makes the song an AI song; one artist marked Not AI
 * does not clear a song another listed artist is on.
 */
data class AiFilter(
    val mode: AiFilterMode = AiFilterMode.OFF,
    val listed: Set<String> = emptySet(),
    val markedAi: Set<String> = emptySet(),
    val notAi: Set<String> = emptySet(),
    /** Ids of the songs the listener chose: in the library, liked, downloaded, or in a playlist they made. */
    val chosenSongs: Set<String> = emptySet(),
) {
    val isOff: Boolean
        get() = mode == AiFilterMode.OFF

    val everywhere: Boolean
        get() = mode == AiFilterMode.EVERYWHERE

    /** Whether the artist of channel id [id] counts as AI: listed or marked, and not marked Not AI. */
    fun isAiArtist(id: String?): Boolean = id != null && id !in notAi && (id in markedAi || id in listed)

    /** The rule for a song by several artists: AI when any of them is. */
    fun isAiSong(artistIds: Iterable<String?>): Boolean = artistIds.any(::isAiArtist)

    /** Whether [item] is by an AI artist, whatever the state. A song the listener chose never is. */
    fun isAi(item: YTItem): Boolean = when (item) {
        is SongItem -> item.id !in chosenSongs && isAiSong(item.artists.map { it.id })
        is AlbumItem -> isAiSong(item.artists.orEmpty().map { it.id })
        is ArtistItem -> isAiArtist(item.id)
        // A playlist of the listener's own account is theirs, whoever else is in it.
        is PlaylistItem -> !item.isEditable && isAiArtist(item.author?.id)
    }

    fun isAi(song: MediaMetadata): Boolean =
        !song.isLocal && song.inLibrary == null && !song.liked && song.id !in chosenSongs && isAiSong(song.artists.map { it.id })

    fun isAi(song: Song): Boolean = !song.song.isLocal && song.song.inLibrary == null && !song.song.liked &&
        song.song.dateDownload == null && song.id !in chosenSongs && isAiSong(song.artists.map { it.id })

    // ---- recommendations: Home, Quick picks, radio and autoplay, in both states that are on ----

    fun hidesInRecommendations(item: YTItem): Boolean = !isOff && isAi(item)

    fun hidesInRecommendations(song: Song): Boolean = !isOff && isAi(song)

    fun <T : YTItem> recommendations(items: List<T>): List<T> = if (isOff) items else items.filterNot(::isAi)

    fun recommendedMedia(items: List<MediaMetadata>): List<MediaMetadata> = if (isOff) items else items.filterNot(::isAi)

    fun recommendedSongs(items: List<Song>): List<Song> = if (isOff) items else items.filterNot(::isAi)

    /** Home's rows from YouTube without AI songs; a row left empty goes, as ContentFilter.home does. */
    fun home(page: HomePage): HomePage = if (isOff) page else
        page.copy(sections = page.sections.map { it.copy(items = recommendations(it.items)) }.filter { it.items.isNotEmpty() })

    /**
     * A radio's first page without AI songs, but with the song at [start] whatever it is, since the
     * listener chose it, as ContentFilter.radio. Gives the songs and where that song now stands.
     */
    fun radio(items: List<SongItem>, start: Int): Pair<List<SongItem>, Int> {
        if (isOff) return items to start
        val kept = ArrayList<SongItem>(items.size)
        var at = 0
        items.forEachIndexed { index, item ->
            if (index == start) at = kept.size
            if (index == start || !isAi(item)) kept += item
        }
        return kept to at
    }

    // ---- search: Block everywhere only, never silently ----

    /** What a search shows, and how many results were hidden from it. */
    data class Kept<T>(val shown: T, val hidden: Int)

    fun hidesInSearch(item: YTItem): Boolean = everywhere && isAi(item)

    fun <T : YTItem> search(items: List<T>): Kept<List<T>> {
        if (!everywhere) return Kept(items, 0)
        val shown = items.filterNot(::isAi)
        return Kept(shown, items.size - shown.size)
    }

    /** The first page of a search; a part left empty goes, as ContentFilter.search does. */
    fun search(page: SearchSummaryPage): Kept<SearchSummaryPage> {
        if (!everywhere) return Kept(page, 0)
        var hidden = 0
        val summaries = page.summaries.map { summary ->
            val kept = search(summary.items)
            hidden += kept.hidden
            summary.copy(items = kept.shown)
        }.filter { it.items.isNotEmpty() }
        return Kept(page.copy(summaries = summaries), hidden)
    }

    // ---- the queue: Block everywhere only ----

    /** Whether the player passes over [song] when it comes to it by itself. */
    fun skips(song: MediaMetadata): Boolean = everywhere && isAi(song)

    companion object {
        /**
         * The listener's two lists after one tap on the artist menu's row for [id]: Not AI on an
         * artist that counts as AI now, Mark as AI on one that does not. Each undoes the other, so
         * the row is one switch: a listed artist marked Not AI and then Mark as AI is back to the
         * list alone, and an artist only the listener had marked is simply unmarked.
         * Gives the marked and the Not AI sets, in that order.
         */
        fun toggle(id: String, listed: Boolean, markedAi: Set<String>, notAi: Set<String>): Pair<Set<String>, Set<String>> {
            val aiNow = id !in notAi && (id in markedAi || listed)
            return when {
                aiNow && listed -> (markedAi - id) to (notAi + id)
                aiNow -> (markedAi - id) to (notAi - id)
                listed -> (markedAi - id) to (notAi - id)
                else -> (markedAi + id) to (notAi - id)
            }
        }

        /** The setting as stored, Off for a build without the flag: a restored backup can bring it on. */
        fun modeOf(preferences: Preferences, enabled: Boolean = Unreleased.AI_FILTER): AiFilterMode =
            if (!enabled) AiFilterMode.OFF else preferences[AiFilterKey].toEnum(AiFilterMode.OFF)

        fun of(
            preferences: Preferences,
            listed: Set<String>,
            chosenSongs: Set<String>,
            enabled: Boolean = Unreleased.AI_FILTER,
        ): AiFilter {
            val mode = modeOf(preferences, enabled)
            if (mode == AiFilterMode.OFF) return AiFilter()
            return AiFilter(
                mode = mode,
                listed = listed,
                markedAi = preferences[AiMarkedArtistsKey].orEmpty(),
                notAi = preferences[AiNotArtistsKey].orEmpty(),
                chosenSongs = chosenSongs,
            )
        }

        private val state = MutableStateFlow(AiFilter())

        /** The filter as it stands, followed by App from the settings, the list and the library. */
        val changes: StateFlow<AiFilter> = state

        /** Read by the queues and the pages, as ContentFilter.current is. Set by App. */
        var current: AiFilter
            get() = state.value
            set(value) {
                state.value = value
            }

        /** The artists of the song the queue playing was started from. See [forQueue]. */
        @Volatile
        var startedFrom: Set<String> = emptySet()
            private set

        /** A queue begins from a song with these artists: set where a queue starts (QueueStart, YouTubeQueue). */
        fun startedFrom(artistIds: Iterable<String?>) {
            startedFrom = artistIds.filterNotNull().toSet()
        }

        /**
         * The filter for what joins or plays in the queue: [current], with the artists of the song
         * the queue was started from counted as Not AI. Shuffling an AI artist's page, or a radio
         * started from their song, is the listener choosing that artist, and their songs stay in
         * that queue; another AI artist's do not.
         */
        fun forQueue(): AiFilter {
            val filter = current
            val chosen = startedFrom
            return if (filter.isOff || chosen.isEmpty()) filter else filter.copy(notAi = filter.notAi + chosen)
        }

        /**
         * Whether a YouTube queue of [playlistId] is a list the listener chose to play, a playlist
         * or an album, and not a radio or a mix YouTube made: those have ids starting with "RD" (a
         * song's radio RDAMVM, a playlist's RDAMPL, an artist's RDEM, YouTube's own mixes), or no
         * list at all for a song's radio asked by its video alone.
         */
        fun isChosenList(playlistId: String?): Boolean = playlistId != null && !playlistId.startsWith("RD")

        /**
         * The filter for the songs a YouTube queue of [playlistId] brings in. A list the listener
         * chose ([isChosenList]) joins whole, as it does when played from its own page; with Block
         * everywhere the player still passes over its AI songs as it comes to them ([skips]). A
         * radio or a mix is a recommendation: [forQueue].
         */
        fun forQueuePages(playlistId: String?): AiFilter = if (isChosenList(playlistId)) AiFilter() else forQueue()
    }
}
