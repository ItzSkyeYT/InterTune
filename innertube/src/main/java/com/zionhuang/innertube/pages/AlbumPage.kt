package com.zionhuang.innertube.pages

import com.zionhuang.innertube.models.Album
import com.zionhuang.innertube.models.AlbumItem
import com.zionhuang.innertube.models.Artist
import com.zionhuang.innertube.models.MusicPlaylistShelfRenderer
import com.zionhuang.innertube.models.MusicResponsiveHeaderRenderer
import com.zionhuang.innertube.models.MusicResponsiveListItemRenderer
import com.zionhuang.innertube.models.SongItem
import com.zionhuang.innertube.models.getItems
import com.zionhuang.innertube.models.oddElements
import com.zionhuang.innertube.models.response.BrowseResponse
import com.zionhuang.innertube.models.splitBySeparator
import com.zionhuang.innertube.utils.parseTime

data class AlbumPage(
    val album: AlbumItem,
    val songs: List<SongItem>,
    val otherVersions: List<AlbumItem>,
) {
    companion object {
        fun getPlaylistId(response: BrowseResponse): String? {
            var playlistId = response.microformat?.microformatDataRenderer?.urlCanonical?.substringAfterLast('=')
            if (playlistId == null)
            {
                playlistId = response.header?.musicDetailHeaderRenderer?.menu?.menuRenderer?.topLevelButtons?.firstOrNull()
                    ?.buttonRenderer?.navigationEndpoint?.watchPlaylistEndpoint?.playlistId
            }
            return playlistId
        }

        fun getTitle(response: BrowseResponse): String? {
            val title = getHeader(response)?.title ?: response.header?.musicDetailHeaderRenderer?.title
            return title?.runs?.firstOrNull()?.text
        }

        fun getYear(response: BrowseResponse): Int? {
            val title = getHeader(response)?.subtitle ?: response.header?.musicDetailHeaderRenderer?.subtitle
            return title?.runs?.lastOrNull()?.text?.toIntOrNull()
        }

        fun getThumbnail(response: BrowseResponse): String? {
            return response.background?.musicThumbnailRenderer?.getThumbnailUrl() ?: response.header?.musicDetailHeaderRenderer?.thumbnail
                ?.croppedSquareThumbnailRenderer?.getThumbnailUrl()
        }

        fun getArtists(response: BrowseResponse): List<Artist> {
            val artists = getHeader(response)?.straplineTextOne?.runs?.oddElements()?.map {
                Artist(
                    name = it.text,
                    id = it.navigationEndpoint?.browseEndpoint?.browseId
                )
            } ?: response.header?.musicDetailHeaderRenderer?.subtitle?.runs?.splitBySeparator()?.getOrNull(1)?.oddElements()?.map {
                Artist(
                    name = it.text,
                    id = it.navigationEndpoint?.browseEndpoint?.browseId
                )
            } ?: emptyList()

            return artists
        }

        private fun getHeader(response: BrowseResponse): MusicResponsiveHeaderRenderer? {
            val tabs = response.contents?.singleColumnBrowseResultsRenderer?.tabs
                ?: response.contents?.twoColumnBrowseResultsRenderer?.tabs
            val section =
                tabs?.firstOrNull()?.tabRenderer?.content?.sectionListRenderer?.contents?.firstOrNull()
            val header = section?.musicResponsiveHeaderRenderer
            return header
        }

        /**
         * The shelf of songs on an album's playlist page (VL + its playlist id), or null when the
         * page has none, which some albums' pages now do: see YouTube.albumPage.
         */
        fun playlistShelf(response: BrowseResponse): MusicPlaylistShelfRenderer? =
            response.contents?.twoColumnBrowseResultsRenderer?.secondaryContents?.sectionListRenderer
                ?.contents?.firstOrNull()?.musicPlaylistShelfRenderer

        /**
         * The songs an album's own page lists, under its header, for when its playlist page lists
         * none. Its rows often name no artist, where the playlist page names one on every row (a
         * compilation's name theirs, a single's and THE BOOK 2's do not, not even the featured
         * one), and have no cover of their own, so a row that names nobody takes the album's
         * artists and every row the album's cover.
         */
        fun getSongs(response: BrowseResponse, album: AlbumItem): List<SongItem> {
            val tabs = response.contents?.singleColumnBrowseResultsRenderer?.tabs ?: response.contents?.twoColumnBrowseResultsRenderer?.tabs
            val shelfRenderer = tabs?.firstOrNull()?.tabRenderer?.content?.sectionListRenderer?.contents
                ?.firstNotNullOfOrNull { it.musicShelfRenderer }
                ?: response.contents?.twoColumnBrowseResultsRenderer?.secondaryContents?.sectionListRenderer?.contents
                    ?.firstNotNullOfOrNull { it.musicShelfRenderer }

            val songs = shelfRenderer?.contents?.getItems()?.mapNotNull {
                getSong(it, album)
            }?.map { song ->
                if (song.artists.isEmpty()) song.copy(artists = album.artists.orEmpty()) else song
            }
            return songs ?: emptyList()
        }

        fun getSong(renderer: MusicResponsiveListItemRenderer, album: AlbumItem? = null): SongItem? {
            return SongItem(
                // Some albums return tracks without playlistItemData; the videoId then lives only
                // in the overlay play button (or navigationEndpoint). Ported from ArchiveTune.
                id = renderer.playlistItemData?.videoId
                    ?: renderer.overlay?.musicItemThumbnailOverlayRenderer?.content?.musicPlayButtonRenderer?.playNavigationEndpoint?.watchEndpoint?.videoId
                    ?: renderer.navigationEndpoint?.watchEndpoint?.videoId
                    ?: return null,
                title = PageHelper.extractRuns(renderer.flexColumns, "MUSIC_VIDEO").firstOrNull()?.text ?: return null,
                artists = PageHelper.extractRuns(renderer.flexColumns, "MUSIC_PAGE_TYPE_ARTIST").map{
                    Artist(
                        name = it.text,
                        id = it.navigationEndpoint?.browseEndpoint?.browseId
                    )
                },
                album = album?.let {
                    Album(it.title, it.browseId)
                } ?: renderer.flexColumns.getOrNull(2)?.musicResponsiveListItemFlexColumnRenderer?.text?.runs?.firstOrNull()?.let { run ->
                    run.navigationEndpoint?.browseEndpoint?.browseId?.let { browseId ->
                        Album(
                            name = run.text,
                            id = browseId
                        )
                    }
                },
                duration = renderer.fixedColumns?.firstOrNull()
                    ?.musicResponsiveListItemFlexColumnRenderer?.text?.runs?.firstOrNull()
                    ?.text?.parseTime() ?: return null,
                thumbnail = renderer.thumbnail?.musicThumbnailRenderer?.getThumbnailUrl()
                    ?: album?.thumbnail
                    ?: return null,
                explicit = renderer.badges?.find {
                    it.musicInlineBadgeRenderer?.icon?.iconType == "MUSIC_EXPLICIT_BADGE"
                } != null
            )
        }
    }
}