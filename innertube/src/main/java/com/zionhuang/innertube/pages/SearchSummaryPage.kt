package com.zionhuang.innertube.pages

import com.zionhuang.innertube.models.Album
import com.zionhuang.innertube.models.AlbumItem
import com.zionhuang.innertube.models.Artist
import com.zionhuang.innertube.models.ArtistItem
import com.zionhuang.innertube.models.MusicCardShelfRenderer
import com.zionhuang.innertube.models.MusicResponsiveListItemRenderer
import com.zionhuang.innertube.models.PlaylistItem
import com.zionhuang.innertube.models.SongItem
import com.zionhuang.innertube.models.YTItem
import com.zionhuang.innertube.models.clean
import com.zionhuang.innertube.models.oddElements
import com.zionhuang.innertube.models.splitBySeparator
import com.zionhuang.innertube.utils.parseTime

data class SearchSummary(
    val title: String,
    val items: List<YTItem>,
)

data class SearchSummaryPage(
    val summaries: List<SearchSummary>,
) {
    companion object {
        fun fromMusicCardShelfRenderer(renderer: MusicCardShelfRenderer): YTItem? {
            val subtitle = renderer.subtitle.runs?.splitBySeparator()
            return when {
                renderer.onTap.watchEndpoint != null -> {
                    SongItem(
                        id = renderer.onTap.watchEndpoint.videoId ?: return null,
                        title = renderer.title.runs?.firstOrNull()?.text ?: return null,
                        artists = subtitle?.getOrNull(1)?.oddElements()?.map {
                            Artist(
                                name = it.text,
                                id = it.navigationEndpoint?.browseEndpoint?.browseId
                            )
                        } ?: return null,
                        album = subtitle.getOrNull(2)?.firstOrNull()?.takeIf { it.navigationEndpoint?.browseEndpoint != null }?.let {
                            Album(
                                name = it.text,
                                id = it.navigationEndpoint?.browseEndpoint?.browseId!!
                            )
                        },
                        duration = subtitle.lastOrNull()?.firstOrNull()?.text?.parseTime(),
                        thumbnail = renderer.thumbnail.musicThumbnailRenderer?.getThumbnailUrl() ?: return null,
                        explicit = renderer.subtitleBadges?.find {
                            it.musicInlineBadgeRenderer?.icon?.iconType == "MUSIC_EXPLICIT_BADGE"
                        } != null
                    )
                }

                renderer.onTap.browseEndpoint?.isArtistEndpoint == true -> {
                    ArtistItem(
                        id = renderer.onTap.browseEndpoint.browseId,
                        title = renderer.title.runs?.firstOrNull()?.text ?: return null,
                        thumbnail = renderer.thumbnail.musicThumbnailRenderer?.getThumbnailUrl() ?: return null,
                        shuffleEndpoint = renderer.buttons
                            .find { it.buttonRenderer.icon?.iconType == "MUSIC_SHUFFLE" }
                            ?.buttonRenderer?.command?.watchPlaylistEndpoint ?: return null,
                        radioEndpoint = renderer.buttons
                            .find { it.buttonRenderer.icon?.iconType == "MIX" }
                            ?.buttonRenderer?.command?.watchPlaylistEndpoint ?: return null,
                    )
                }

                renderer.onTap.browseEndpoint?.isAlbumEndpoint == true -> {
                    AlbumItem(
                        browseId = renderer.onTap.browseEndpoint.browseId,
                        playlistId = renderer.buttons.firstOrNull()?.buttonRenderer?.command?.anyWatchEndpoint?.playlistId ?: return null,
                        title = renderer.title.runs?.firstOrNull()?.text ?: return null,
                        artists = subtitle?.getOrNull(1)?.oddElements()?.map {
                            Artist(
                                name = it.text,
                                id = it.navigationEndpoint?.browseEndpoint?.browseId
                            )
                        } ?: return null,
                        year = null,
                        thumbnail = renderer.thumbnail.musicThumbnailRenderer?.getThumbnailUrl() ?: return null,
                        explicit = renderer.subtitleBadges?.find {
                            it.musicInlineBadgeRenderer?.icon?.iconType == "MUSIC_EXPLICIT_BADGE"
                        } != null
                    )
                }

                renderer.onTap.browseEndpoint?.isPlaylistEndpoint == true -> {
                    PlaylistItem(
                        id = renderer.onTap.browseEndpoint.browseId.removePrefix("VL"),
                        title = renderer.header?.musicCardShelfHeaderBasicRenderer?.title?.runs?.joinToString(separator = "") { it.text }
                            ?: return null,
                        author = Artist(
                            id = null,
                            name = renderer.subtitle.runs?.joinToString { it.text } ?: return null
                        ),
                        songCountText = null,
                        thumbnail = renderer.thumbnail.musicThumbnailRenderer?.getThumbnailUrl() ?: return null,
                        playEndpoint = renderer.buttons.find { it.buttonRenderer.icon?.iconType == "PLAY_ARROW" }
                            ?.buttonRenderer?.command?.watchPlaylistEndpoint
                            ?: return null,
                        shuffleEndpoint = renderer.buttons.find { it.buttonRenderer.icon?.iconType == "MUSIC_SHUFFLE" }
                            ?.buttonRenderer?.command?.watchPlaylistEndpoint
                            ?: return null,
                        radioEndpoint = null
                    )
                }

                else -> null
            }
        }

        /** A song's length as YouTube writes it: 3:22, or 1:02:45. */
        private val LENGTH = Regex("\\d{1,2}(:\\d{2}){1,2}")

        /**
         * @param cardArtist the artist a top result card is about, for the songs listed under it.
         *   Those rows leave the artist out because the card already names it, so their second
         *   column is only "Song • 5:38". With the type label cleaned off, the length was all that
         *   was left in the artist's place, and every one read "5:38 • 5:38" (seen 25 Sep).
         */
        fun fromMusicResponsiveListItemRenderer(renderer: MusicResponsiveListItemRenderer, cardArtist: Artist? = null): YTItem? {
            val secondaryLine = renderer.flexColumns.getOrNull(1)
                ?.musicResponsiveListItemFlexColumnRenderer?.text?.runs?.splitBySeparator()
                ?: return null
            val thirdLine = renderer.flexColumns.getOrNull(2)
                ?.musicResponsiveListItemFlexColumnRenderer?.text?.runs?.splitBySeparator()
                ?: emptyList()
            val listRun = (secondaryLine + thirdLine).clean()
            return when {
                renderer.isSong -> {
                    val first = listRun.getOrNull(0)
                    val noArtist = first != null && first.size == 1 && first[0].navigationEndpoint == null &&
                        LENGTH.matches(first[0].text.trim())
                    val episode = renderer.isPodcastEpisode
                    SongItem(
                        id = renderer.playlistItemData?.videoId ?: return null,
                        title = renderer.flexColumns.firstOrNull()
                            ?.musicResponsiveListItemFlexColumnRenderer?.text?.runs
                            ?.firstOrNull()?.text ?: return null,
                        artists = when {
                            // The show, not the date that comes first and read as the artist.
                            episode -> listRun.drop(1).flatten().filter { it.navigationEndpoint?.browseEndpoint != null }.take(1).map {
                                Artist(name = it.text, id = it.navigationEndpoint?.browseEndpoint?.browseId)
                            }
                            noArtist -> listOfNotNull(cardArtist)
                            else -> first?.oddElements()?.map {
                                Artist(
                                    name = it.text,
                                    id = it.navigationEndpoint?.browseEndpoint?.browseId
                                )
                            } ?: return null
                        },
                        album = listRun.getOrNull(1)?.firstOrNull()?.takeIf { !episode && it.navigationEndpoint?.browseEndpoint != null }?.let {
                            Album(
                                name = it.text,
                                id = it.navigationEndpoint?.browseEndpoint?.browseId!!
                            )
                        },
                        duration = secondaryLine.lastOrNull()?.firstOrNull()?.text?.parseTime(),
                        thumbnail = renderer.thumbnail?.musicThumbnailRenderer?.getThumbnailUrl() ?: return null,
                        explicit = renderer.badges?.find {
                            it.musicInlineBadgeRenderer?.icon?.iconType == "MUSIC_EXPLICIT_BADGE"
                        } != null
                    )
                }

                renderer.isArtist -> {
                    ArtistItem(
                        id = renderer.navigationEndpoint?.browseEndpoint?.browseId ?: return null,
                        title = renderer.flexColumns.firstOrNull()?.musicResponsiveListItemFlexColumnRenderer?.text?.runs?.firstOrNull()?.text ?: return null,
                        thumbnail = renderer.thumbnail?.musicThumbnailRenderer?.getThumbnailUrl() ?: return null,
                        shuffleEndpoint = renderer.menu?.menuRenderer?.items
                            ?.find { it.menuNavigationItemRenderer?.icon?.iconType == "MUSIC_SHUFFLE" }
                            ?.menuNavigationItemRenderer?.navigationEndpoint?.watchPlaylistEndpoint ?: return null,
                        radioEndpoint = renderer.menu.menuRenderer.items
                            .find { it.menuNavigationItemRenderer?.icon?.iconType == "MIX" }
                            ?.menuNavigationItemRenderer?.navigationEndpoint?.watchPlaylistEndpoint ?: return null
                    )
                }

                renderer.isAlbum -> {
                    AlbumItem(
                        browseId = renderer.navigationEndpoint?.browseEndpoint?.browseId ?: return null,
                        playlistId = renderer.overlay?.musicItemThumbnailOverlayRenderer?.content?.musicPlayButtonRenderer?.playNavigationEndpoint?.watchPlaylistEndpoint?.playlistId ?: return null,
                        title = renderer.flexColumns.firstOrNull()
                            ?.musicResponsiveListItemFlexColumnRenderer?.text?.runs
                            ?.firstOrNull()?.text ?: return null,
                        artists = secondaryLine.getOrNull(1)?.oddElements()?.map {
                            Artist(
                                name = it.text,
                                id = it.navigationEndpoint?.browseEndpoint?.browseId
                            )
                        } ?: return null,
                        year = secondaryLine.getOrNull(2)?.firstOrNull()?.text?.toIntOrNull(),
                        thumbnail = renderer.thumbnail?.musicThumbnailRenderer?.getThumbnailUrl() ?: return null,
                        explicit = renderer.badges?.find {
                            it.musicInlineBadgeRenderer?.icon?.iconType == "MUSIC_EXPLICIT_BADGE"
                        } != null
                    )
                }

                renderer.isPlaylist -> {
                    PlaylistItem(
                        id = renderer.navigationEndpoint?.browseEndpoint?.browseId?.removePrefix("VL") ?: return null,
                        title = renderer.flexColumns.firstOrNull()
                            ?.musicResponsiveListItemFlexColumnRenderer?.text?.runs
                            ?.firstOrNull()?.text ?: return null,
                        author = secondaryLine.getOrNull(1)?.firstOrNull()?.let {
                            Artist(
                                name = it.text,
                                id = it.navigationEndpoint?.browseEndpoint?.browseId
                            )
                        } ?: return null,
                        songCountText = renderer.flexColumns.getOrNull(1)
                            ?.musicResponsiveListItemFlexColumnRenderer?.text?.runs
                            ?.lastOrNull()?.text ?: return null,
                        thumbnail = renderer.thumbnail?.musicThumbnailRenderer?.getThumbnailUrl() ?: return null,
                        playEndpoint = renderer.overlay?.musicItemThumbnailOverlayRenderer?.content
                            ?.musicPlayButtonRenderer?.playNavigationEndpoint?.watchPlaylistEndpoint ?: return null,
                        shuffleEndpoint = renderer.menu?.menuRenderer?.items
                            ?.find { it.menuNavigationItemRenderer?.icon?.iconType == "MUSIC_SHUFFLE" }
                            ?.menuNavigationItemRenderer?.navigationEndpoint?.watchPlaylistEndpoint ?: return null,
                        radioEndpoint = renderer.menu.menuRenderer.items
                            .find { it.menuNavigationItemRenderer?.icon?.iconType == "MIX" }
                            ?.menuNavigationItemRenderer?.navigationEndpoint?.watchPlaylistEndpoint ?: return null
                    )
                }

                else -> null
            }
        }
    }
}
