package com.zionhuang.innertube.pages

import com.zionhuang.innertube.models.Artist
import com.zionhuang.innertube.models.BrowseEndpoint.BrowseEndpointContextSupportedConfigs.BrowseEndpointContextMusicConfig.Companion.MUSIC_PAGE_TYPE_ARTIST
import com.zionhuang.innertube.models.BrowseEndpoint.BrowseEndpointContextSupportedConfigs.BrowseEndpointContextMusicConfig.Companion.MUSIC_PAGE_TYPE_LIBRARY_ARTIST
import com.zionhuang.innertube.models.BrowseEndpoint.BrowseEndpointContextSupportedConfigs.BrowseEndpointContextMusicConfig.Companion.MUSIC_PAGE_TYPE_USER_CHANNEL
import com.zionhuang.innertube.models.MusicResponsiveListItemRenderer.FlexColumn
import com.zionhuang.innertube.models.Run
import com.zionhuang.innertube.models.oddElements
import com.zionhuang.innertube.models.splitBySeparator

object PageHelper {
    fun extractRuns(columns: List<FlexColumn>, typeLike: String): List<Run> {
        val filteredRuns = mutableListOf<Run>()
        for (column in columns) {
            val runs = column.musicResponsiveListItemFlexColumnRenderer.text?.runs
                ?: continue

            for (run in runs) {
                val typeStr = run.navigationEndpoint?.watchEndpoint?.watchEndpointMusicSupportedConfigs?.watchEndpointMusicConfig?.musicVideoType
                    ?: run.navigationEndpoint?.browseEndpoint?.browseEndpointContextSupportedConfigs?.browseEndpointContextMusicConfig?.pageType
                    ?: continue

                if (typeLike in typeStr) {
                    filteredRuns.add(run)
                }
            }
        }
        return filteredRuns
    }

    private val ARTIST_PAGE_TYPES = setOf(
        MUSIC_PAGE_TYPE_ARTIST,
        MUSIC_PAGE_TYPE_LIBRARY_ARTIST,
        MUSIC_PAGE_TYPE_USER_CHANNEL,
    )

    private val DURATION = Regex("""^\d{1,2}(:\d{2}){1,2}$""")
    private val YEAR = Regex("""^(19|20)\d{2}$""")
    private val CARRIES_A_YEAR = Regex("""(^|\D)(19|20)\d{2}(\D|$)""")

    private val Run.isArtistLink: Boolean
        get() {
            val pageType = navigationEndpoint?.browseEndpoint
                ?.browseEndpointContextSupportedConfigs?.browseEndpointContextMusicConfig?.pageType
                ?: return false
            return pageType in ARTIST_PAGE_TYPES
        }

    /**
     * Text that states a fact about the row rather than naming anybody.
     *
     * Structural on purpose. "431K plays" is "431 k lectures" in French and "431 Tsd.
     * Wiedergaben" in German, so a list of English words would go quietly wrong the moment the
     * phone is not set to English, whereas "starts with a digit" survives translation.
     */
    private fun statesAFact(run: Run): Boolean {
        val text = run.text.trim()
        if (text.isEmpty()) return true
        if (DURATION.matches(text) || YEAR.matches(text)) return true
        if (text.first().isDigit()) return true
        // "Aug 25, 2016". Upload dates turned out to be as common a wrong artist as play counts.
        return CARRIES_A_YEAR.containsMatchIn(text) && text.any { it.isLetter() }
    }

    private fun isFact(group: List<Run>): Boolean {
        if (group.isEmpty()) return true
        // A group that is entirely links to somewhere other than an artist is the album or the
        // playlist the row belongs to, never the credit.
        if (group.all { it.navigationEndpoint != null } && group.none { it.isArtistLink }) return true
        val lone = group.singleOrNull() ?: return false
        return lone.navigationEndpoint == null && statesAFact(lone)
    }

    /**
     * [oddElements] keeps every other run, the ones in between being the separators. A link that
     * does not land on an even index would be thrown away by that, so in that case keep the links
     * and nothing else.
     */
    private fun credited(group: List<Run>): List<Run> {
        val everyOther = group.oddElements()
        val links = group.filter { it.isArtistLink }
        return if (links.any { it !in everyOther }) links else everyOther
    }

    /**
     * The artists named in one byline, or none.
     *
     * Every parser in this module used to answer this question by index, taking whatever sat in
     * the artist's usual place. When YouTube put something else there, which it does for video
     * rows whose byline reads "Video • 16M views" and for rows carrying an upload date, that
     * something else was stored as the artist. A library ends up with artists called "431K plays"
     * and "Aug 25, 2016".
     *
     * Two things this deliberately does not do. It does not demand a link, because the tracks on
     * albums like "Forza Horizon 6 (Original Soundtrack)" are plain text everywhere YouTube serves
     * them, and a linked-only filter stores that whole album with no artist at all. And it does
     * not guess: a video row whose byline is only a type label and a view count genuinely has no
     * artist, and saying so is better than inventing one.
     */
    fun artistRuns(byline: List<Run>?): List<Run> {
        if (byline.isNullOrEmpty()) return emptyList()
        val groups = byline.splitBySeparator().filter { it.isNotEmpty() }
        if (groups.isEmpty()) return emptyList()

        // 1. YouTube told us who the artist is. Take the whole group, so an unlinked co-artist
        //    beside a linked one survives.
        groups.firstOrNull { group -> group.any { it.isArtistLink } }?.let { return credited(it) }

        // 2. It did not, so read the byline's shape. Facts run to the end once they start.
        val firstFact = groups.indexOfFirst { isFact(it) }
        val kept = if (firstFact == -1) groups else groups.subList(0, firstFact)
        val credit = kept.lastOrNull() ?: return emptyList()

        // 3. One group left, it was the first, and only facts followed it: that is the type label
        //    ("Song", "Video", "Artist"), not a name. This is the shape that wrote "431K plays".
        val trimmed = groups.drop(kept.size)
        if (kept.size == 1 &&
            trimmed.isNotEmpty() &&
            trimmed.all { group -> group.all { it.navigationEndpoint == null } } &&
            credit.size == 1 && credit[0].navigationEndpoint == null
        ) return emptyList()

        return credit.oddElements()
    }

    fun artists(byline: List<Run>?): List<Artist> = artistRuns(byline).map {
        Artist(name = it.text, id = it.navigationEndpoint?.browseEndpoint?.browseId)
    }

    /**
     * The artists of a list row.
     *
     * Never reads past `flexColumns[1]`: the columns after it hold the album and the play count,
     * and reaching into them is how a count becomes an artist. The fallback is the old behaviour,
     * kept as a backstop for shelves that put the artist link in a later column.
     */
    fun songArtists(columns: List<FlexColumn>): List<Artist> {
        val fromByline = artists(
            columns.getOrNull(1)?.musicResponsiveListItemFlexColumnRenderer?.text?.runs
        )
        if (fromByline.isNotEmpty()) return fromByline
        return extractRuns(columns, MUSIC_PAGE_TYPE_ARTIST).map {
            Artist(name = it.text, id = it.navigationEndpoint?.browseEndpoint?.browseId)
        }
    }
}
