package com.dd3boh.outertune.ui.component.items

import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Album
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.media3.exoplayer.offline.Download
import com.dd3boh.outertune.constants.PlayOrigin
import com.dd3boh.outertune.LocalDatabase
import com.dd3boh.outertune.LocalDownloadUtil
import com.dd3boh.outertune.LocalPlayerConnection
import com.dd3boh.outertune.constants.ListThumbnailSize
import com.dd3boh.outertune.constants.ThumbnailCornerRadius
import com.dd3boh.outertune.db.entities.Album
import com.dd3boh.outertune.playback.queues.ListQueue
import com.dd3boh.outertune.ui.utils.getNSongsString
import com.dd3boh.outertune.utils.albumSongsToPlay
import com.dd3boh.outertune.utils.getDownloadState
import com.dd3boh.outertune.utils.joinByBullet
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import java.time.ZoneOffset

@Composable
fun AlbumListItem(
    album: Album,
    modifier: Modifier = Modifier,
    showLikedIcon: Boolean = true,
    badges: @Composable RowScope.() -> Unit = {
        val database = LocalDatabase.current
        val downloadUtil = LocalDownloadUtil.current
        // The ids of its songs that can be downloaded, and nothing else about them: the songs
        // themselves were read for each tile, with their artists, album and the rest, and read
        // again at every change to any of those tables, for a mark drawn from their downloads.
        val toDownload by remember(album.id) { database.albumSongsToDownload(album.id) }.collectAsState(initial = emptyList())

        var downloadState by remember {
            mutableIntStateOf(Download.STATE_STOPPED)
        }

        LaunchedEffect(toDownload) {
            if (toDownload.isEmpty()) return@LaunchedEffect
            downloadUtil.downloads.collect { downloads ->
                downloadState = getDownloadState(toDownload.map { downloads[it] })
            }
        }

        if (showLikedIcon && album.album.bookmarkedAt != null) {
            Icon.Favorite()
        }

        Icon.Download(downloadState)
    },
    isActive: Boolean = false,
    isPlaying: Boolean = false,
    trailingContent: @Composable RowScope.() -> Unit = {},
) = ListItem(
    title = album.album.title,
    subtitle = joinByBullet(
        album.artists.joinToString { it.name },
        album.takeIf { it.album.songCount != 0 }?.let { album ->
            getNSongsString(album.album.songCount, album.downloadCount)
        },
        album.album.year?.toString()
    ),
    badges = badges,
    thumbnailContent = {
        ItemThumbnail(
            thumbnailUrl = album.album.thumbnailUrl,
            placeholderIcon = Icons.Outlined.Album,
            isActive = isActive,
            isPlaying = isPlaying,
            shape = RoundedCornerShape(ThumbnailCornerRadius),
            modifier = Modifier.size(ListThumbnailSize)
        )
    },
    trailingContent = trailingContent,
    modifier = modifier
)

@Composable
fun AlbumGridItem(
    album: Album,
    modifier: Modifier = Modifier,
    coroutineScope: CoroutineScope,
    badges: @Composable RowScope.() -> Unit = {
        val database = LocalDatabase.current
        val downloadUtil = LocalDownloadUtil.current
        // As AlbumListItem's: the ids of its songs that can be downloaded, and nothing else.
        val toDownload by remember(album.id) { database.albumSongsToDownload(album.id) }.collectAsState(initial = emptyList())

        var downloadState by remember {
            mutableIntStateOf(Download.STATE_STOPPED)
        }

        LaunchedEffect(toDownload) {
            if (toDownload.isEmpty()) return@LaunchedEffect
            downloadUtil.downloads.collect { downloads ->
                downloadState = getDownloadState(toDownload.map { downloads[it] })
            }
        }

        if (album.album.bookmarkedAt != null) {
            Icon.Favorite()
        }

        Icon.Download(downloadState)
    },
    isActive: Boolean = false,
    isPlaying: Boolean = false,
    fillMaxWidth: Boolean = false,
) = GridItem(
    title = album.album.title,
    subtitle = album.artists.joinToString { it.name },
    badges = badges,
    thumbnailContent = {
        val database = LocalDatabase.current
        val playerConnection = LocalPlayerConnection.current ?: return@GridItem

        ItemThumbnail(
            thumbnailUrl = album.album.thumbnailUrl,
            placeholderIcon = Icons.Outlined.Album,
            isActive = isActive,
            isPlaying = isPlaying,
            shape = androidx.compose.foundation.shape.RoundedCornerShape(ThumbnailCornerRadius),
        )

        AlbumPlayButton(
            visible = !isActive,
            onClick = {
                coroutineScope.launch {
                    albumSongsToPlay(database, album.id)
                        .takeIf { it.isNotEmpty() }
                        ?.let {
                            playerConnection.playQueue(
                                ListQueue(
                                    title = album.album.title,
                                    items = it
                                ),
                                origin = PlayOrigin.ALBUM,
                            )
                        }
                }
            }
        )
    },
    fillMaxWidth = fillMaxWidth,
    modifier = modifier
)