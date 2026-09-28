package com.dd3boh.outertune.ui.menu

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBars
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.PlaylistAdd
import androidx.compose.material.icons.automirrored.rounded.PlaylistPlay
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.FavoriteBorder
import androidx.compose.material.icons.rounded.LibraryAdd
import androidx.compose.material.icons.rounded.LibraryAddCheck
import androidx.compose.material.icons.rounded.PlaylistRemove
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.media3.exoplayer.offline.Download
import androidx.navigation.NavController
import com.dd3boh.outertune.LocalDatabase
import com.dd3boh.outertune.constants.PlayOrigin
import com.dd3boh.outertune.LocalDownloadUtil
import com.dd3boh.outertune.LocalPlayerConnection
import com.dd3boh.outertune.R
import com.dd3boh.outertune.extensions.toMediaItem
import com.dd3boh.outertune.models.MediaMetadata
import com.dd3boh.outertune.playback.DownloadUtil
import com.dd3boh.outertune.playback.queues.ListQueue
import com.dd3boh.outertune.ui.dialog.AddToPlaylistDialog
import com.dd3boh.outertune.ui.dialog.AddToQueueDialog
import com.dd3boh.outertune.ui.dialog.DefaultDialog
import com.dd3boh.outertune.utils.getDownloadState
import java.time.LocalDateTime

/**
 * Generic song menu
 */
@Composable
fun SelectionMediaMetadataMenu(
    navController: NavController,
    selection: List<MediaMetadata>,
    onDismiss: () -> Unit,
    clearAction: () -> Unit,
    onRemoveFromHistory: (() -> Unit)? = null,
    onRemoveFromPlaylist: (() -> Unit)? = null,
) {
    val context = LocalContext.current
    val database = LocalDatabase.current
    val downloadUtil = LocalDownloadUtil.current
    val playerConnection = LocalPlayerConnection.current ?: return

    val allInLibrary by remember(selection) { // exclude local songs
        mutableStateOf(selection.isNotEmpty() && selection.all { !it.isLocal && it.inLibrary != null })
    }
    val allLocal by remember(selection) { // if only local songs in this selection
        mutableStateOf(selection.isNotEmpty() && selection.all { it.isLocal })
    }

    val allLiked by remember(selection) {
        mutableStateOf(selection.isNotEmpty() && selection.all { it.liked })
    }

    var downloadState by remember {
        mutableIntStateOf(Download.STATE_STOPPED)
    }

    var showChooseQueueDialog by rememberSaveable {
        mutableStateOf(false)
    }
    var showChoosePlaylistDialog by rememberSaveable {
        mutableStateOf(false)
    }
    var showRemoveDownloadDialog by remember {
        mutableStateOf(false)
    }

    LaunchedEffect(selection) {
        if (selection.isEmpty()) {
            onDismiss()
        } else {
            val selection = selection.filterNot { it.isLocal }
            if (selection.isEmpty()) return@LaunchedEffect
            downloadUtil.downloads.collect { downloads ->
                downloadState = getDownloadState(selection.map { downloads[it.id] })
            }
        }
    }


    GridMenu(
        contentPadding = PaddingValues(
            start = 8.dp,
            top = 8.dp,
            end = 8.dp,
            bottom = 8.dp + WindowInsets.systemBars.asPaddingValues().calculateBottomPadding()
        )
    ) {
        GridMenuItem(
            icon = R.drawable.play,
            title = R.string.play
        ) {
            onDismiss()
            playerConnection.playQueue(
                ListQueue(
                    title = "Selection",
                    items = selection
                ),
                origin = PlayOrigin.MENU,
            )
            clearAction()
        }

        GridMenuItem(
            icon = Icons.AutoMirrored.Rounded.PlaylistPlay,
            title = R.string.play_next,
        ) {
            onDismiss()
            playerConnection.enqueueNext(selection.map { it.toMediaItem() })
            clearAction()
        }

        GridMenuItem(
            icon = R.drawable.shuffle_on,
            title = R.string.shuffle
        ) {
            onDismiss()
            playerConnection.playQueue(
                ListQueue(
                    title = "Selection",
                    items = selection,
                    startShuffled = true,
                ),
                origin = PlayOrigin.MENU,
            )
            clearAction()
        }

        GridMenuItem(
            icon = R.drawable.queue_music,
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

        if (!allLocal) {
            if (allInLibrary) {
                GridMenuItem(
                    icon = Icons.Rounded.LibraryAddCheck,
                    title = R.string.remove_all_from_library
                ) {
                    database.transaction {
                        selection.forEach { song ->
                            toggleInLibrary(song.id, null)
                        }
                    }
                }
            } else {
                GridMenuItem(
                    icon = Icons.Rounded.LibraryAdd,
                    title = R.string.add_all_to_library
                ) {
                    database.transaction {
                        selection.forEach { song ->
                            if (!song.isLocal) {
                                // A song InterTune has not stored yet (picked on an online
                                // playlist or an artist's page) has no row, and toggleInLibrary is
                                // an update. insert ignores a row that already exists.
                                insert(song)
                                toggleInLibrary(song.id, LocalDateTime.now())
                            }
                        }
                    }
                }
            }
        }

        GridMenuItem(
            icon = if (allLiked) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder,
            tint = { if (allLiked) MaterialTheme.colorScheme.error else LocalContentColor.current },
            title = if (allLiked) R.string.action_remove_like_all else R.string.action_like_all,
        ) {
            database.query {
                // A song not stored yet has no row, so the update below would land on nothing and
                // the like would reach YouTube only. insert ignores rows that already exist.
                selection.filterNot { it.isLocal }.forEach { insert(it) }
                // The stored rows, toggled, and not ones rebuilt from the selection's metadata.
                // Those have no download date and, for a YouTube song, no library date, so
                // writing them back took every song liked this way out of the library and made
                // downloaded ones read as not downloaded. Picked by their stored like as well,
                // so a selection that has gone stale cannot flip one the wrong way.
                val stored = selection.map { it.id }.chunked(500)
                    .flatMap { songEntitiesByIds(it) }
                    .associateBy { it.id }
                val songs = selection.map { stored[it.id] ?: it.toSongEntity() }
                // toggleLike sends each like to YouTube itself. A likeSong after it sent it again.
                if (allLiked) {
                    songs.filter { it.liked }.forEach { song ->
                        update(song.toggleLike())
                    }
                } else {
                    val newlyLiked = songs.filter { !it.liked }
                        .map { it.toggleLike() }
                    newlyLiked.forEach { s ->
                        update(s)
                    }
                    // One batch call, so liking a large selection fires a single Wi-Fi warning
                    // rather than one per song. The branch above is an un-like, so it gets none.
                    downloadUtil.autoDownloadOnLike(newlyLiked)
                }
            }
        }

        DownloadGridMenu(
            state = downloadState,
            onDownload = {
                val songs = selection.filterNot { it.isLocal }
                // A finished download is recorded by an update on the song's row, so a song not
                // stored yet needs one first, as YouTubePlaylistMenu's Download does.
                database.transaction {
                    songs.forEach(::insert)
                }
                downloadUtil.download(songs)
            },
            onRemoveDownload = {
                showRemoveDownloadDialog = true
            }
        )

        if (onRemoveFromHistory != null) {
            GridMenuItem(
                icon = Icons.Rounded.Delete,
                title = R.string.remove_from_history,
            ) {
                onRemoveFromHistory()
                onDismiss()
                clearAction()
            }
        }

        if (onRemoveFromPlaylist != null) {
            GridMenuItem(
                icon = Icons.Rounded.PlaylistRemove,
                title = R.string.remove_from_playlist,
            ) {
                onRemoveFromPlaylist()
                onDismiss()
                clearAction()
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
                val q = playerConnection.service.queueBoard.addQueue(
                    queueName,
                    selection,
                    forceInsert = true,
                    delta = false
                )
                q?.let {
                    playerConnection.service.queueBoard.setCurrQueue(it)
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
            songIds = selection.map { it.id },
            onPreAdd = {
                selection.forEach { s ->
                    database.insert(s)
                }
                emptyList()
            },
            onDismiss = { showChoosePlaylistDialog = false }
        )
    }

    if (showRemoveDownloadDialog) {
        DefaultDialog(
            onDismiss = { showRemoveDownloadDialog = false },
            content = {
                Text(
                    text = stringResource(R.string.remove_download_playlist_confirm, "selection"),
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
                        selection.forEach { song ->
                            downloadUtil.removeDownload(song.id)
                        }
                    }
                ) {
                    Text(text = stringResource(android.R.string.ok))
                }
            }
        )
    }
}
