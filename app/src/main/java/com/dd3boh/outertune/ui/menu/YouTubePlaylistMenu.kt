package com.dd3boh.outertune.ui.menu

import android.content.Intent
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBars
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.PlaylistAdd
import androidx.compose.material.icons.automirrored.rounded.PlaylistPlay
import androidx.compose.material.icons.automirrored.rounded.QueueMusic
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.PlaylistRemove
import androidx.compose.material.icons.rounded.Radio
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.media3.exoplayer.offline.Download
import androidx.navigation.NavController
import com.dd3boh.outertune.LocalDatabase
import com.dd3boh.outertune.constants.PlayOrigin
import com.dd3boh.outertune.LocalDownloadUtil
import com.dd3boh.outertune.LocalPlayerConnection
import com.dd3boh.outertune.R
import com.dd3boh.outertune.db.entities.PlaylistEntity
import com.dd3boh.outertune.db.entities.PlaylistSongMap
import com.dd3boh.outertune.extensions.toMediaItem
import com.dd3boh.outertune.models.toMediaMetadata
import com.dd3boh.outertune.playback.queues.ListQueue
import com.dd3boh.outertune.playback.queues.YouTubeQueue
import com.dd3boh.outertune.ui.component.button.IconButton
import com.dd3boh.outertune.ui.component.items.YouTubeListItem
import com.dd3boh.outertune.ui.dialog.AddToPlaylistDialog
import com.dd3boh.outertune.ui.dialog.AddToQueueDialog
import com.dd3boh.outertune.ui.dialog.DefaultDialog
import com.dd3boh.outertune.utils.getDownloadState
import com.dd3boh.outertune.utils.mayPushToYouTube
import com.dd3boh.outertune.utils.syncCoroutine
import com.zionhuang.innertube.YouTube
import com.zionhuang.innertube.models.PlaylistItem
import com.zionhuang.innertube.models.SongItem
import com.zionhuang.innertube.utils.completed
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun YouTubePlaylistMenu(
    navController: NavController,
    playlist: PlaylistItem,
    songs: List<SongItem> = emptyList(),
    coroutineScope: CoroutineScope,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val database = LocalDatabase.current
    val downloadUtil = LocalDownloadUtil.current
    val playerConnection = LocalPlayerConnection.current ?: return
    val dbPlaylist by database.playlistByBrowseId(playlist.id).collectAsState(initial = null)

    var showChoosePlaylistDialog by rememberSaveable {
        mutableStateOf(false)
    }
    var showChooseQueueDialog by rememberSaveable {
        mutableStateOf(false)
    }
    var showRemoveDownloadDialog by remember {
        mutableStateOf(false)
    }
    var showDeletePlaylistDialog by remember {
        mutableStateOf(false)
    }

    var downloadState by remember {
        mutableIntStateOf(Download.STATE_STOPPED)
    }

    LaunchedEffect(songs) {
        if (songs.isEmpty()) return@LaunchedEffect
        downloadUtil.downloads.collect { downloads ->
            downloadUtil.downloads.collect { downloads ->
                downloadState = getDownloadState(songs.map { downloads[it.id] })
            }
        }
    }


    YouTubeListItem(
        item = playlist,
        trailingContent = {
            if (playlist.id != "LM" && !playlist.isEditable) {
                IconButton(
                    onClick = {
                        if (dbPlaylist?.playlist == null) {
                            database.transaction {
                                val playlistEntity = PlaylistEntity(
                                    name = playlist.title,
                                    browseId = playlist.id,
                                    isEditable = false,
                                    thumbnailUrl = playlist.thumbnail,
                                    remoteSongCount = playlist.songCountText?.let { Regex("""\d+""").find(it)?.value?.toIntOrNull() },
                                    playEndpointParams = playlist.playEndpoint?.params,
                                    shuffleEndpointParams = playlist.shuffleEndpoint?.params,
                                    radioEndpointParams = playlist.radioEndpoint?.params
                                ).toggleLike()

                                insert(playlistEntity)
                                coroutineScope.launch(Dispatchers.IO) {
                                    songs.ifEmpty {
                                        YouTube.playlist(playlist.id).completed()
                                            .getOrNull()?.songs.orEmpty()
                                    }.map { it.toMediaMetadata() }
                                        .onEach(::insert)
                                        .mapIndexed { index, song ->
                                            PlaylistSongMap(
                                                songId = song.id,
                                                playlistId = playlistEntity.id,
                                                position = index
                                            )
                                        }
                                        .forEach(::insert)
                                }
                            }
                        } else {
                            database.transaction {
                                update(dbPlaylist!!.playlist.toggleLike())
                            }
                        }
                    }
                ) {
                    Icon(
                        painter = painterResource(if (dbPlaylist?.playlist?.bookmarkedAt != null) R.drawable.favorite else R.drawable.favorite_border),
                        tint = if (dbPlaylist?.playlist?.bookmarkedAt != null) MaterialTheme.colorScheme.error else LocalContentColor.current,
                        contentDescription = null
                    )
                }
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
        playlist.playEndpoint?.let { playEndpoint ->
            GridMenuItem(
                icon = Icons.Rounded.PlayArrow,
                title = R.string.play
            ) {
                // Only the playlist's own screen passes the songs it has loaded. Opened anywhere
                // else (Home, Library, search, an artist) there are none, and a queue of nothing
                // played nothing, leaving the old queue going. The playlist's endpoint plays it
                // from YouTube instead, fetching the rest as it goes.
                playerConnection.playQueue(
                    if (songs.isEmpty()) YouTubeQueue(playEndpoint) else ListQueue(
                        playlistId = playEndpoint.playlistId,
                        title = playlist.title,
                        items = songs.map { it.toMediaMetadata() },
                    ),
                    origin = PlayOrigin.PLAYLIST,
                )

                onDismiss()
            }
        }

        playlist.shuffleEndpoint?.let { shuffleEndpoint ->
            GridMenuItem(
                icon = Icons.Rounded.Shuffle,
                title = R.string.shuffle
            ) {
                // As Play: with no songs passed, YouTube's own shuffle of the playlist.
                playerConnection.playQueue(
                    if (songs.isEmpty()) YouTubeQueue(shuffleEndpoint) else ListQueue(
                        playlistId = playlist.playEndpoint?.playlistId ?: shuffleEndpoint.playlistId,
                        title = playlist.title,
                        items = songs.map { it.toMediaMetadata() },
                        startShuffled = true,
                    ),
                    origin = PlayOrigin.PLAYLIST,
                )
                onDismiss()
            }
        }

        playlist.radioEndpoint?.let { radioEndpoint ->
            GridMenuItem(
                icon = Icons.Rounded.Radio,
                title = R.string.start_radio
            ) {
                playerConnection.playQueue(YouTubeQueue(radioEndpoint), isRadio = true)
                onDismiss()
            }
        }

        GridMenuItem(
            icon = Icons.AutoMirrored.Rounded.PlaylistPlay,
            title = R.string.play_next
        ) {
            coroutineScope.launch {
                songs.ifEmpty {
                    withContext(Dispatchers.IO) {
                        YouTube.playlist(playlist.id).completed().getOrNull()?.songs.orEmpty()
                    }
                }.let { songs ->
                    playerConnection.enqueueNext(songs.map { it.toMediaItem() })
                }
            }
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

        if (songs.isNotEmpty()) {
            DownloadGridMenu(
                state = downloadState,
                onDownload = {
                    val _songs = songs.map { it.toMediaMetadata() }
                    // Into the library's song table first, as the song menu does. A finished
                    // download is recorded by updating the song's row, and these songs had none,
                    // so after a restart nothing knew they had been downloaded.
                    database.transaction {
                        _songs.forEach { insert(it) }
                    }
                    downloadUtil.download(_songs)
                },
                onRemoveDownload = {
                    showRemoveDownloadDialog = true
                }
            )
        }

        GridMenuItem(
            icon = Icons.Rounded.Share,
            title = R.string.share
        ) {
            val intent = Intent().apply {
                action = Intent.ACTION_SEND
                type = "text/plain"
                putExtra(Intent.EXTRA_TEXT, playlist.shareLink)
            }
            context.startActivity(Intent.createChooser(intent, null))
            onDismiss()
        }

        // Only for a playlist that is in the library: there is nothing here to delete otherwise.
        if (dbPlaylist?.playlist != null) {
            GridMenuItem(
                icon = Icons.Rounded.PlaylistRemove,
                title = R.string.delete
            ) {
                showDeletePlaylistDialog = true
            }
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
                coroutineScope.launch {
                    songs.ifEmpty {
                        withContext(Dispatchers.IO) {
                            YouTube.playlist(playlist.id).completed().getOrNull()?.songs.orEmpty()
                        }
                    }.let { songs ->
                        val q = playerConnection.service.queueBoard.addQueue(
                            queueName, songs.map { it.toMediaMetadata() },
                            forceInsert = true, delta = false, origin = PlayOrigin.QUEUE
                        )
                        q?.let {
                            playerConnection.service.queueBoard.setCurrQueue(it)
                        }
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
            songIds = null,
            onPreAdd = { targetPlaylist ->
                // This playlist's songs, the one the menu is for. Both calls used the id of the
                // playlist picked instead, a local one YouTube does not know: the fetch failed,
                // nothing was added, and the picker closed as if it had worked.
                val allSongs = songs
                    .ifEmpty {
                        YouTube.playlist(playlist.id).completed().getOrNull()?.songs.orEmpty()
                    }.map {
                        it.toMediaMetadata()
                    }
                database.transaction {
                    allSongs.forEach(::insert)
                }

                targetPlaylist.playlist.browseId?.let { playlistId ->
                    YouTube.addPlaylistToPlaylist(playlistId, playlist.id)
                }

                allSongs.map { it.id }
            },
            onDismiss = { showChoosePlaylistDialog = false }
        )
    }

    if (showRemoveDownloadDialog) {
        DefaultDialog(
            onDismiss = { showRemoveDownloadDialog = false },
            content = {
                Text(
                    text = stringResource(R.string.remove_download_playlist_confirm, playlist.title),
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.padding(horizontal = 18.dp)
                )
            },
            buttons = {
                TextButton(
                    onClick = {
                        showRemoveDownloadDialog = false
                    }
                ) {
                    Text(text = stringResource(android.R.string.cancel))
                }

                TextButton(
                    onClick = {
                        showRemoveDownloadDialog = false
                        songs.forEach { song ->
                            downloadUtil.removeDownload(song.id)
                        }
                    }
                ) {
                    Text(text = stringResource(android.R.string.ok))
                }
            }
        )
    }

    if (showDeletePlaylistDialog) {
        DefaultDialog(
            onDismiss = { showDeletePlaylistDialog = false },
            content = {
                Text(
                    text = stringResource(R.string.delete_playlist_confirm, playlist.title),
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.padding(horizontal = 18.dp)
                )
            },
            buttons = {
                TextButton(
                    onClick = {
                        showDeletePlaylistDialog = false
                    }
                ) {
                    Text(text = stringResource(android.R.string.cancel))
                }

                TextButton(
                    onClick = {
                        showDeletePlaylistDialog = false
                        onDismiss()
                        // What the library's own Delete does. This used to delete by the YouTube
                        // id, which is no playlist's id here (the library gives its playlists ids
                        // of their own and keeps YouTube's as browseId), so it did nothing at all.
                        val saved = dbPlaylist?.playlist ?: return@TextButton
                        database.query {
                            delete(saved)
                        }
                        if (!saved.isLocal) {
                            coroutineScope.launch(syncCoroutine) {
                                if (context.mayPushToYouTube()) {
                                    saved.browseId?.let { browseId ->
                                        // Someone else's playlist cannot be deleted, only taken
                                        // out of the library, as the heart does.
                                        if (saved.isEditable) YouTube.deletePlaylist(browseId)
                                        else YouTube.likePlaylist(browseId, false)
                                    }
                                }
                            }
                        }
                    }
                ) {
                    Text(text = stringResource(android.R.string.ok))
                }
            }
        )
    }
}
