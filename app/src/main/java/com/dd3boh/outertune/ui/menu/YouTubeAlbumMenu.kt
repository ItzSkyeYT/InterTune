package com.dd3boh.outertune.ui.menu

import android.content.Intent
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.systemBars
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.PlaylistAdd
import androidx.compose.material.icons.automirrored.rounded.PlaylistPlay
import androidx.compose.material.icons.automirrored.rounded.QueueMusic
import androidx.compose.material.icons.rounded.Radio
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.media3.exoplayer.offline.Download
import androidx.navigation.NavController
import com.dd3boh.outertune.LocalDatabase
import com.dd3boh.outertune.constants.PlayOrigin
import com.dd3boh.outertune.db.AlbumRows
import com.dd3boh.outertune.LocalDownloadUtil
import com.dd3boh.outertune.LocalPlayerConnection
import com.dd3boh.outertune.R
import com.dd3boh.outertune.extensions.toMediaItem
import com.dd3boh.outertune.models.MediaMetadata
import com.dd3boh.outertune.models.toMediaMetadata
import com.dd3boh.outertune.models.withArtistIds
import com.dd3boh.outertune.playback.queues.YouTubeAlbumRadio
import com.dd3boh.outertune.ui.component.button.IconButton
import com.dd3boh.outertune.ui.component.items.YouTubeListItem
import com.dd3boh.outertune.ui.dialog.AddToPlaylistDialog
import com.dd3boh.outertune.ui.dialog.AddToQueueDialog
import com.dd3boh.outertune.ui.dialog.ArtistDialog
import com.dd3boh.outertune.utils.getDownloadState
import com.dd3boh.outertune.utils.reportException
import com.zionhuang.innertube.YouTube
import com.zionhuang.innertube.models.AlbumItem
import kotlinx.coroutines.flow.first

@Composable
fun YouTubeAlbumMenu(
    albumItem: AlbumItem,
    navController: NavController,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val database = LocalDatabase.current
    val downloadUtil = LocalDownloadUtil.current
    val playerConnection = LocalPlayerConnection.current ?: return
    val album by database.albumWithSongs(albumItem.id).collectAsState(initial = null)
    val coroutineScope = rememberCoroutineScope()

    var showChooseQueueDialog by rememberSaveable {
        mutableStateOf(false)
    }

    var showChoosePlaylistDialog by rememberSaveable {
        mutableStateOf(false)
    }
    var showSelectArtistDialog by rememberSaveable {
        mutableStateOf(false)
    }
    val artists = remember(albumItem) { albumItem.artists.orEmpty().withArtistIds() }

    // The page's songs, for Play next and Add to queue while the album is not stored yet: they
    // acted on the stored album alone, and did nothing until the write below had landed.
    var pageSongs by remember { mutableStateOf<List<MediaMetadata>>(emptyList()) }
    val songsToQueue = album?.songs?.takeIf { it.isNotEmpty() }?.map { it.toMediaMetadata() } ?: pageSongs

    LaunchedEffect(Unit) {
        // Fetched even when the album is stored: a saved album is stored with no songs, and one
        // played from holds only the songs played, and Play next, Add to queue and Download act
        // on what is stored. It used to be fetched only when missing.
        YouTube.album(albumItem.id).onSuccess { albumPage ->
            pageSongs = albumPage.songs.map { it.toMediaMetadata() }
            val stored = database.albumWithSongs(albumItem.id).first()
            val mapped = stored?.songs.orEmpty().map { it.id }
            if (AlbumRows.pageAddsSongs(stored?.album, mapped, albumPage.songs.map { it.id })) {
                database.transaction {
                    val current = albumById(albumItem.id)
                    if (current == null) insert(albumPage) else update(current, albumPage)
                }
            }
        }.onFailure {
            reportException(it)
        }
    }

    var downloadState by remember {
        mutableIntStateOf(Download.STATE_STOPPED)
    }

    LaunchedEffect(album) {
        val songs = album?.songs?.map { it.id } ?: return@LaunchedEffect
        downloadUtil.downloads.collect { downloads ->
            downloadState = getDownloadState(songs.map { downloads[it] })
        }
    }

    YouTubeListItem(
        item = albumItem,
        badges = {},
        trailingContent = {
            IconButton(
                onClick = {
                    database.query {
                        album?.album?.toggleLike()?.let(::update)
                    }
                }
            ) {
                Icon(
                    painter = painterResource(if (album?.album?.bookmarkedAt != null) R.drawable.favorite else R.drawable.favorite_border),
                    tint = if (album?.album?.bookmarkedAt != null) MaterialTheme.colorScheme.error else LocalContentColor.current,
                    contentDescription = null
                )
            }
        }
    )

    HorizontalDivider()

    GridMenu(
        contentPadding = PaddingValues(
            start = 8.dp,
            top = 8.dp,
            end = 8.dp,
            bottom = 8.dp + WindowInsets.systemBars.asPaddingValues().calculateBottomPadding()
        )
    ) {
        GridMenuItem(
            icon = Icons.Rounded.Radio,
            title = R.string.start_radio
        ) {
            playerConnection.playQueue(YouTubeAlbumRadio(albumItem.playlistId), origin = PlayOrigin.ALBUM)
            onDismiss()
        }
        GridMenuItem(
            icon = Icons.AutoMirrored.Rounded.PlaylistPlay,
            title = R.string.play_next
        ) {
            songsToQueue.takeIf { it.isNotEmpty() }
                ?.map { it.toMediaItem() }
                ?.let(playerConnection::enqueueNext)
            onDismiss()
        }
        GridMenuItem(
            icon = Icons.AutoMirrored.Rounded.QueueMusic,
            title = R.string.add_to_queue
        ) {
            showChooseQueueDialog = true
        }
        GridMenuItem(
            icon = Icons.AutoMirrored.Rounded.PlaylistAdd,
            title = R.string.add_to_playlist
        ) {
            showChoosePlaylistDialog = true
        }
        DownloadGridMenu(
            state = downloadState,
            onDownload = {
                val _songs = album?.songs?.map { it.toMediaMetadata() } ?: emptyList()
                downloadUtil.download(_songs)
            },
            onRemoveDownload = {
                album?.songs?.forEach { song ->
                    downloadUtil.removeDownload(song.id)
                }
            }
        )
        if (artists.isNotEmpty()) {
            GridMenuItem(
                icon = R.drawable.artist,
                title = R.string.view_artist
            ) {
                if (artists.size == 1) {
                    navController.navigate("artist/${artists[0].id}")
                    onDismiss()
                } else {
                    showSelectArtistDialog = true
                }
            }
        }
        GridMenuItem(
            icon = Icons.Rounded.Share,
            title = R.string.share
        ) {
            val intent = Intent().apply {
                action = Intent.ACTION_SEND
                type = "text/plain"
                putExtra(Intent.EXTRA_TEXT, albumItem.shareLink)
            }
            context.startActivity(Intent.createChooser(intent, null))
            onDismiss()
        }
    }

    /**
     * ---------------------------
     * Dialogs
     * ---------------------------
     */

    if (showChooseQueueDialog) {
        AddToQueueDialog(
            onAdd = { queueName ->
                songsToQueue.takeIf { it.isNotEmpty() }?.let { songs ->
                    val q = playerConnection.service.queueBoard.addQueue(
                        queueName, songs,
                        forceInsert = true, delta = false
                    )
                    q?.let {
                        playerConnection.service.queueBoard.setCurrQueue(it)
                    }
                }
            },
            onDismiss = {
                showChooseQueueDialog = false
            }
        )
    }

    if (showChoosePlaylistDialog) {
        AddToPlaylistDialog(
            navController = navController,
            songIds = album?.songs?.map { it.id }.orEmpty(),
            onPreAdd = { playlist ->
                playlist.playlist.browseId?.let { playlistId ->
                    album?.album?.playlistId?.let { addPlaylistId ->
                        YouTube.addPlaylistToPlaylist(playlistId, addPlaylistId)
                    }
                }
                emptyList()
            },
            onDismiss = { showChoosePlaylistDialog = false }
        )
    }

    if (showSelectArtistDialog) {
        ArtistDialog(
            navController = navController,
            artists = artists,
            onDismiss = { showSelectArtistDialog = false }
        )
    }
}
