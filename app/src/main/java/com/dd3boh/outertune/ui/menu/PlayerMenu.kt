package com.dd3boh.outertune.ui.menu

import com.dd3boh.outertune.ui.component.rememberSleepTimerState
import com.dd3boh.outertune.ui.component.SleepTimerDialog
import com.dd3boh.outertune.constants.SignalKind
import com.dd3boh.outertune.utils.ActivityLog
import android.content.Intent
import android.media.audiofx.AudioEffect
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.PlaylistAdd
import androidx.compose.material.icons.automirrored.rounded.QueueMusic
import androidx.compose.material.icons.automirrored.rounded.VolumeUp
import androidx.compose.material.icons.rounded.AddCircleOutline
import androidx.compose.material.icons.rounded.Equalizer
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.LibraryAdd
import androidx.compose.material.icons.rounded.LibraryAddCheck
import androidx.compose.material.icons.rounded.Lyrics
import androidx.compose.material.icons.rounded.FilterCenterFocus
import androidx.compose.material.icons.rounded.Radio
import androidx.compose.material.icons.rounded.RemoveCircleOutline
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material.icons.rounded.SlowMotionVideo
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.fastSumBy
import androidx.compose.ui.window.DialogProperties
import androidx.media3.common.PlaybackParameters
import androidx.media3.exoplayer.offline.DownloadService
import androidx.navigation.NavController
import com.dd3boh.outertune.LocalDatabase
import com.dd3boh.outertune.LocalDownloadUtil
import com.dd3boh.outertune.LocalPlayerConnection
import androidx.compose.foundation.lazy.items
import com.dd3boh.outertune.ui.dialog.ListDialog
import com.dd3boh.outertune.ui.component.items.ListItem
import com.dd3boh.outertune.constants.ListThumbnailSize
import com.dd3boh.outertune.db.MusicDatabase
import com.dd3boh.outertune.db.entities.Playlist
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.first
import androidx.compose.material.icons.rounded.PlaylistRemove
import androidx.compose.foundation.Image
import androidx.compose.ui.graphics.ColorFilter
import com.dd3boh.outertune.R
import com.dd3boh.outertune.constants.MAX_PLAYER_VOLUME
import com.dd3boh.outertune.constants.ShowLyricsKey
import com.dd3boh.outertune.models.MediaMetadata
import com.dd3boh.outertune.playback.ExoDownloadService
import com.dd3boh.outertune.playback.queues.YouTubeQueue
import com.dd3boh.outertune.ui.component.BigSeekBar
import com.dd3boh.outertune.ui.component.BottomSheetState
import com.dd3boh.outertune.ui.component.button.IconButton
import com.dd3boh.outertune.ui.dialog.AddToPlaylistDialog
import com.dd3boh.outertune.ui.dialog.AddToQueueDialog
import com.dd3boh.outertune.ui.dialog.ArtistDialog
import com.dd3boh.outertune.ui.dialog.DetailsDialog
import com.dd3boh.outertune.utils.rememberPreference
import com.zionhuang.innertube.YouTube
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.time.LocalDateTime
import kotlin.math.log2
import kotlin.math.pow
import kotlin.math.round

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlayerMenu(
    mediaMetadata: MediaMetadata?,
    navController: NavController,
    playerBottomSheetState: BottomSheetState,
    onDismiss: () -> Unit,
) {
    mediaMetadata ?: return
    val context = LocalContext.current
    val database = LocalDatabase.current
    val downloadUtil = LocalDownloadUtil.current
    val clipboardManager = LocalClipboard.current

    var showLyrics by rememberPreference(ShowLyricsKey, defaultValue = false)

    val playerConnection = LocalPlayerConnection.current ?: return
    val playerVolume = playerConnection.service.playerVolume.collectAsState()
    val currentFormatState = database.format(mediaMetadata.id).collectAsState(initial = null)
    val currentFormat = currentFormatState.value
    val librarySong by database.song(mediaMetadata.id).collectAsState(initial = null)
    val coroutineScope = rememberCoroutineScope()

    val download by LocalDownloadUtil.current.getDownload(mediaMetadata.id).collectAsState(initial = null)

    // Which editable playlists this song sits in, so it can be taken out from here. Removing was
    // only ever offered from the song list, so getting a track out of the playlist you were
    // listening to meant leaving the player, finding it again and opening a second menu.
    val inPlaylists by database.playlistsContaining(mediaMetadata.id).collectAsState(initial = emptyList())
    var showRemoveFromPlaylist by rememberSaveable { mutableStateOf(false) }

    val activityResultLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { }

    var showChooseQueueDialog by rememberSaveable {
        mutableStateOf(false)
    }
    var showChoosePlaylistDialog by rememberSaveable {
        mutableStateOf(false)
    }
    var showSelectArtistDialog by rememberSaveable {
        mutableStateOf(false)
    }


    var showPitchTempoDialog by rememberSaveable {
        mutableStateOf(false)
    }

    if (showPitchTempoDialog) {
        PitchTempoDialog(
            onDismiss = { showPitchTempoDialog = false }
        )
    }

    val (sleepTimerEnabled, sleepTimerTimeLeft) = rememberSleepTimerState(playerConnection)

    var showSleepTimerDialog by remember {
        mutableStateOf(false)
    }

    if (showSleepTimerDialog) {
        SleepTimerDialog(playerConnection) { showSleepTimerDialog = false }
    }

    var showDetailsDialog by rememberSaveable {
        mutableStateOf(false)
    }

    if (showDetailsDialog) {
        DetailsDialog(
            mediaMetadata = mediaMetadata,
            currentFormat = currentFormat,
            currentPlayCount = librarySong?.playCount?.fastSumBy { it.count } ?: 0,
            clipboardManager = clipboardManager,
            setVisibility = { showDetailsDialog = it }
        )
    }

    Row(
        horizontalArrangement = Arrangement.spacedBy(24.dp),
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp)
            .padding(top = 24.dp, bottom = 6.dp)
    ) {
        Icon(
            imageVector = Icons.AutoMirrored.Rounded.VolumeUp,
            contentDescription = null,
            modifier = Modifier.size(28.dp)
        )

        BigSeekBar(
            progressProvider = playerVolume::value,
            onProgressChange = { playerConnection.service.playerVolume.value = it },
            // Past 100% the gain is applied to the samples rather than through player.volume,
            // which cannot exceed unity. Soft clipped, so pushing it hard compresses peaks
            // instead of tearing them.
            maxProgress = MAX_PLAYER_VOLUME,
            modifier = Modifier.weight(1f)
        )
    }

    GridMenu(
        contentPadding = PaddingValues(
            start = 8.dp,
            top = 8.dp,
            end = 8.dp,
            bottom = 8.dp + WindowInsets.systemBars.asPaddingValues().calculateBottomPadding()
        )
    ) {
        if (!mediaMetadata.isLocal)
            GridMenuItem(
                icon = Icons.Rounded.Radio,
                title = R.string.start_radio
            ) {
                playerConnection.playQueue(YouTubeQueue.radio(mediaMetadata), isRadio = true)
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
        // Only when there is somewhere to remove it from. With one playlist it acts straight away;
        // with several it has to ask, because guessing which one somebody meant would be a silent
        // edit to a playlist they were not looking at.
        if (inPlaylists.isNotEmpty()) {
            GridMenuItem(
                icon = Icons.Rounded.PlaylistRemove,
                title = R.string.remove_from_playlist
            ) {
                if (inPlaylists.size == 1) {
                    removeFromPlaylist(database, coroutineScope, inPlaylists.first(), mediaMetadata.id)
                    onDismiss()
                } else {
                    showRemoveFromPlaylist = true
                }
            }
        }
        if (!mediaMetadata.isLocal)
            DownloadGridMenu(
                localDateTime = download,
                onDownload = {
                    database.transaction {
                        insert(mediaMetadata)
                    }
                    ActivityLog.note(context, database, mediaMetadata.id, SignalKind.DOWNLOAD)
                    downloadUtil.download(mediaMetadata)
                },
                onRemoveDownload = {
                    DownloadService.sendRemoveDownload(
                        context,
                        ExoDownloadService::class.java,
                        mediaMetadata.id,
                        false
                    )
                }
            )
        if (librarySong?.song?.inLibrary != null && !librarySong!!.song.isLocal) {
            GridMenuItem(
                icon = Icons.Rounded.LibraryAddCheck,
                title = R.string.remove_from_library,
            ) {
                database.query {
                    toggleInLibrary(mediaMetadata.id, null)
                }
            }
        } else if (!mediaMetadata.isLocal) {
            GridMenuItem(
                icon = Icons.Rounded.LibraryAdd,
                title = R.string.add_to_library,
            ) {
                database.transaction {
                    insert(mediaMetadata)
                    toggleInLibrary(mediaMetadata.id, LocalDateTime.now())
                }
            }
        }
        GridMenuItem(
            icon = R.drawable.artist,
            title = R.string.view_artist
        ) {
            if (mediaMetadata.artists.size == 1) {
                ActivityLog.note(context, database, mediaMetadata.id, SignalKind.ARTIST_PAGE)
                navController.navigate("artist/${mediaMetadata.artists[0].id}")
                playerBottomSheetState.collapseSoft()
                onDismiss()
            } else {
                showSelectArtistDialog = true
            }
        }
        if (mediaMetadata.album != null && !mediaMetadata.isLocal) {
            GridMenuItem(
                icon = R.drawable.album,
                title = R.string.view_album
            ) {
                navController.navigate("album/${mediaMetadata.album.id}")
                playerBottomSheetState.collapseSoft()
                onDismiss()
            }
        }

        if (!mediaMetadata.isLocal)
            GridMenuItem(
                icon = Icons.Rounded.Share,
                title = R.string.share
            ) {
                ActivityLog.note(context, database, mediaMetadata.id, SignalKind.SHARE)
                val intent = Intent().apply {
                    action = Intent.ACTION_SEND
                    type = "text/plain"
                    putExtra(Intent.EXTRA_TEXT, "https://music.youtube.com/watch?v=${mediaMetadata.id}")
                }
                context.startActivity(Intent.createChooser(intent, null))
                onDismiss()
            }
        GridMenuItem(
            icon = Icons.Rounded.Lyrics,
            title = R.string.toggle_lyrics
        ) {
            onDismiss()
            if (!showLyrics) ActivityLog.note(context, database, mediaMetadata.id, SignalKind.LYRICS)
            showLyrics = !showLyrics
        }
        GridMenuItem(
            icon = Icons.Rounded.Info,
            title = R.string.details
        ) {
            showDetailsDialog = true
        }
        SleepTimerGridMenu(
            sleepTimerTimeLeft = sleepTimerTimeLeft,
            enabled = sleepTimerEnabled
        ) {
            if (sleepTimerEnabled) playerConnection.service.sleepTimer.clear()
            else showSleepTimerDialog = true
        }
        GridMenuItem(
            icon = Icons.Rounded.Equalizer,
            title = R.string.equalizer
        ) {
            val intent = Intent(AudioEffect.ACTION_DISPLAY_AUDIO_EFFECT_CONTROL_PANEL).apply {
                putExtra(AudioEffect.EXTRA_AUDIO_SESSION, playerConnection.player.audioSessionId)
                putExtra(AudioEffect.EXTRA_PACKAGE_NAME, context.packageName)
                putExtra(AudioEffect.EXTRA_CONTENT_TYPE, AudioEffect.CONTENT_TYPE_MUSIC)
            }
            if (intent.resolveActivity(context.packageManager) != null) {
                activityResultLauncher.launch(intent)
            }
            onDismiss()
        }
        GridMenuItem(
            icon = Icons.Rounded.Tune,
            title = R.string.advanced
        ) {
            showPitchTempoDialog = true
        }
        // Last, and only while something is actually tracking. It belongs in this menu because the
        // moment you want it is the moment you are listening, but it does not belong above items
        // people already know the position of: a menu that moves under someone is worse than one
        // that is missing something.
        if (playerConnection.service.headTracking.isRunning) {
            GridMenuItem(
                icon = Icons.Rounded.FilterCenterFocus,
                title = R.string.head_tracking_recentre
            ) {
                playerConnection.service.headTracking.recentre()
                onDismiss()
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
                    listOf(mediaMetadata),
                    forceInsert = true,
                    delta = false
                )
                q?.let {
                    playerConnection.service.queueBoard.setCurrQueue(it)
                }
            },
            onDismiss = {
                showChooseQueueDialog = false
                onDismiss() // here we dismiss since we switch to the queue anyways
            }
        )
    }

    if (showRemoveFromPlaylist) {
        ListDialog(onDismiss = { showRemoveFromPlaylist = false }) {
            items(inPlaylists) { playlist ->
                ListItem(
                    title = playlist.playlist.name,
                    thumbnailContent = {
                        Image(
                            imageVector = Icons.Rounded.PlaylistRemove,
                            contentDescription = null,
                            colorFilter = ColorFilter.tint(MaterialTheme.colorScheme.onBackground),
                            modifier = Modifier.size(ListThumbnailSize)
                        )
                    },
                    modifier = Modifier.clickable {
                        showRemoveFromPlaylist = false
                        removeFromPlaylist(database, coroutineScope, playlist, mediaMetadata.id)
                        onDismiss()
                    }
                )
            }
        }
    }

    if (showChoosePlaylistDialog) {
        AddToPlaylistDialog(
            navController = navController,
            songIds = listOf(mediaMetadata.id),
            onPreAdd = { playlist ->
                database.transaction {
                    insert(mediaMetadata)
                }
                ActivityLog.note(context, database, mediaMetadata.id, SignalKind.ADD_TO_PLAYLIST)

                playlist.playlist.browseId?.let { YouTube.addToPlaylist(it, mediaMetadata.id) }

                listOf(mediaMetadata.id)
            },
            onDismiss = {
                showChoosePlaylistDialog = false
            }
        )
    }

    if (showSelectArtistDialog) {
        ArtistDialog(
            navController = navController,
            artists = mediaMetadata.artists,
            onDismiss = {
                playerBottomSheetState.collapseSoft()
                showSelectArtistDialog = false
            }
        )
    }

}

@Composable
fun PitchTempoDialog(
    onDismiss: () -> Unit,
) {
    val playerConnection = LocalPlayerConnection.current ?: return
    var tempo by remember {
        mutableFloatStateOf(playerConnection.player.playbackParameters.speed)
    }
    var transposeValue by remember {
        mutableIntStateOf(round(12 * log2(playerConnection.player.playbackParameters.pitch)).toInt())
    }
    val updatePlaybackParameters = {
        playerConnection.player.playbackParameters = PlaybackParameters(tempo, 2f.pow(transposeValue.toFloat() / 12))
    }

    AlertDialog(
        properties = DialogProperties(usePlatformDefaultWidth = false),
        onDismissRequest = onDismiss,
        dismissButton = {
            TextButton(
                onClick = {
                    tempo = 1f
                    transposeValue = 0
                    updatePlaybackParameters()
                }
            ) {
                Text(stringResource(R.string.reset))
            }
        },
        confirmButton = {
            TextButton(
                onClick = onDismiss
            ) {
                Text(stringResource(android.R.string.ok))
            }
        },
        text = {
            Column {
                ValueAdjuster(
                    icon = Icons.Rounded.SlowMotionVideo,
                    currentValue = tempo,
                    values = (0..35).map { round((0.25f + it * 0.05f) * 100) / 100 },
                    onValueUpdate = {
                        tempo = it
                        updatePlaybackParameters()
                    },
                    valueText = { "x$it" },
                    modifier = Modifier.padding(bottom = 12.dp)
                )
                ValueAdjuster(
                    icon = Icons.Rounded.Tune,
                    currentValue = transposeValue,
                    values = (-12..12).toList(),
                    onValueUpdate = {
                        transposeValue = it
                        updatePlaybackParameters()
                    },
                    valueText = { "${if (it > 0) "+" else ""}$it" }
                )
            }
        }
    )
}

@Composable
fun <T> ValueAdjuster(
    icon: ImageVector,
    currentValue: T,
    values: List<T>,
    onValueUpdate: (T) -> Unit,
    valueText: (T) -> String,
    modifier: Modifier = Modifier,
) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(24.dp),
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            modifier = Modifier.size(28.dp)
        )

        IconButton(
            enabled = currentValue != values.first(),
            onClick = {
                onValueUpdate(values[values.indexOf(currentValue) - 1])
            }
        ) {
            Icon(
                imageVector = Icons.Rounded.RemoveCircleOutline,
                contentDescription = null
            )
        }

        Text(
            text = valueText(currentValue),
            style = MaterialTheme.typography.titleMedium,
            textAlign = TextAlign.Center,
            modifier = Modifier.width(80.dp)
        )

        IconButton(
            enabled = currentValue != values.last(),
            onClick = {
                onValueUpdate(values[values.indexOf(currentValue) + 1])
            }
        ) {
            Icon(
                imageVector = Icons.Rounded.AddCircleOutline,
                contentDescription = null
            )
        }
    }
}

/**
 * Takes one song out of one playlist, locally and remotely.
 *
 * The move to the end before deleting is what SongMenu does and is not decoration: positions are
 * dense and the reorder query shifts everything after the removed row, so deleting in place leaves
 * a gap that later sorts trip over.
 */
private fun removeFromPlaylist(
    database: MusicDatabase,
    scope: CoroutineScope,
    playlist: Playlist,
    songId: String,
) {
    scope.launch(Dispatchers.IO) {
        val map = database.playlistSongMap(playlist.id, songId).first() ?: return@launch
        database.transaction {
            move(map.playlistId, map.position, Int.MAX_VALUE)
            delete(map.copy(position = Int.MAX_VALUE))
        }
        playlist.playlist.browseId?.let { browseId ->
            map.setVideoId?.let { setVideoId ->
                runCatching { YouTube.removeFromPlaylist(browseId, songId, setVideoId) }
            }
        }
    }
}
