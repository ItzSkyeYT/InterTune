/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.ui.player

import android.content.Intent
import android.media.audiofx.AudioEffect
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.PlaylistAdd
import androidx.compose.material.icons.automirrored.rounded.QueueMusic
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Equalizer
import androidx.compose.material.icons.rounded.FilterCenterFocus
import androidx.compose.material.icons.rounded.Group
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.LibraryAdd
import androidx.compose.material.icons.rounded.LibraryAddCheck
import androidx.compose.material.icons.rounded.Lyrics
import androidx.compose.material.icons.rounded.OfflinePin
import androidx.compose.material.icons.rounded.PlaylistRemove
import androidx.compose.material.icons.rounded.Radio
import androidx.compose.material.icons.rounded.Route
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material.icons.rounded.Timer
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.util.fastSumBy
import androidx.media3.exoplayer.offline.Download
import androidx.navigation.NavController
import com.dd3boh.outertune.LocalDatabase
import com.dd3boh.outertune.LocalDownloadUtil
import com.dd3boh.outertune.LocalPlayerConnection
import com.dd3boh.outertune.R
import com.dd3boh.outertune.constants.ListThumbnailSize
import com.dd3boh.outertune.constants.PlayOrigin
import com.dd3boh.outertune.constants.SignalKind
import com.dd3boh.outertune.constants.SyncMode
import com.dd3boh.outertune.constants.YtmSyncModeKey
import com.dd3boh.outertune.models.MediaMetadata
import com.dd3boh.outertune.models.withArtistIds
import com.dd3boh.outertune.playback.queues.YouTubeQueue
import com.dd3boh.outertune.ui.component.BottomSheetState
import com.dd3boh.outertune.ui.component.items.ListItem
import com.dd3boh.outertune.ui.dialog.AddToPlaylistDialog
import com.dd3boh.outertune.ui.dialog.AddToQueueDialog
import com.dd3boh.outertune.ui.dialog.ArtistDialog
import com.dd3boh.outertune.ui.dialog.DetailsDialog
import com.dd3boh.outertune.ui.dialog.ListDialog
import com.dd3boh.outertune.ui.dialog.SignalPathDialog
import com.dd3boh.outertune.ui.dialog.togetherAvailable
import com.dd3boh.outertune.ui.menu.PitchTempoDialog
import com.dd3boh.outertune.ui.menu.removeFromPlaylist
import com.dd3boh.outertune.utils.ActivityLog
import com.dd3boh.outertune.utils.getDownloadState
import com.dd3boh.outertune.utils.rememberEnumPreference
import com.zionhuang.innertube.YouTube
import kotlinx.coroutines.flow.flowOf
import java.time.LocalDateTime

/**
 * What a pinned button does for the entries that live in the player's menu (issue #27): the same
 * as the entry there, with the dialogs it opens shown over the player. Share, lyrics, the sleep
 * timer and like are the player's own and drawn by it.
 */
internal class PinActions(
    /** Whether the button applies to the song playing, as the menu offers it: a local file has no radio, link, download or album. */
    val shown: (PlayerPin) -> Boolean,
    /** Whether what it stands for is on, for the buttons that are a state: downloaded, in the library, in a room. */
    val on: (PlayerPin) -> Boolean,
    val run: (PlayerPin) -> Unit,
)

/** A pin's icon, the one its entry wears in the menu, [on] for the buttons that are a state. */
@Composable
internal fun pinPainter(pin: PlayerPin, on: Boolean = false): Painter = when (pin) {
    PlayerPin.SHARE -> rememberVectorPainter(Icons.Rounded.Share)
    PlayerPin.LYRICS -> rememberVectorPainter(Icons.Rounded.Lyrics)
    PlayerPin.TIMER -> rememberVectorPainter(Icons.Rounded.Timer)
    PlayerPin.LIKE -> painterResource(if (on) R.drawable.favorite else R.drawable.favorite_border)
    PlayerPin.RADIO -> rememberVectorPainter(Icons.Rounded.Radio)
    PlayerPin.ADD_TO_QUEUE -> rememberVectorPainter(Icons.AutoMirrored.Rounded.QueueMusic)
    PlayerPin.ADD_TO_PLAYLIST -> rememberVectorPainter(Icons.AutoMirrored.Rounded.PlaylistAdd)
    PlayerPin.REMOVE_FROM_PLAYLIST -> rememberVectorPainter(Icons.Rounded.PlaylistRemove)
    PlayerPin.DOWNLOAD -> rememberVectorPainter(if (on) Icons.Rounded.OfflinePin else Icons.Rounded.Download)
    PlayerPin.LIBRARY -> rememberVectorPainter(if (on) Icons.Rounded.LibraryAddCheck else Icons.Rounded.LibraryAdd)
    PlayerPin.ARTIST -> painterResource(R.drawable.artist)
    PlayerPin.ALBUM -> painterResource(R.drawable.album)
    PlayerPin.DETAILS -> rememberVectorPainter(Icons.Rounded.Info)
    PlayerPin.EQUALIZER -> rememberVectorPainter(Icons.Rounded.Equalizer)
    PlayerPin.ADVANCED -> rememberVectorPainter(Icons.Rounded.Tune)
    PlayerPin.SIGNAL_PATH -> rememberVectorPainter(Icons.Rounded.Route)
    PlayerPin.RECENTRE -> rememberVectorPainter(Icons.Rounded.FilterCenterFocus)
    PlayerPin.LISTEN_TOGETHER -> rememberVectorPainter(Icons.Rounded.Group)
}

/** A pin's name, its entry's in the menu, [on] for the buttons that are a state. */
@Composable
internal fun pinLabel(pin: PlayerPin, on: Boolean = false): String = stringResource(
    when (pin) {
        PlayerPin.SHARE -> R.string.share
        PlayerPin.LYRICS -> R.string.player_pin_lyrics
        PlayerPin.TIMER -> R.string.sleep_timer
        PlayerPin.LIKE -> if (on) R.string.action_remove_like else R.string.action_like
        PlayerPin.RADIO -> R.string.start_radio
        PlayerPin.ADD_TO_QUEUE -> R.string.add_to_queue
        PlayerPin.ADD_TO_PLAYLIST -> R.string.add_to_playlist
        PlayerPin.REMOVE_FROM_PLAYLIST -> R.string.remove_from_playlist
        PlayerPin.DOWNLOAD -> if (on) R.string.remove_download else R.string.action_download
        PlayerPin.LIBRARY -> if (on) R.string.remove_from_library else R.string.add_to_library
        PlayerPin.ARTIST -> R.string.view_artist
        PlayerPin.ALBUM -> R.string.view_album
        PlayerPin.DETAILS -> R.string.details
        PlayerPin.EQUALIZER -> R.string.equalizer
        PlayerPin.ADVANCED -> R.string.advanced
        PlayerPin.SIGNAL_PATH -> R.string.signal_path
        PlayerPin.RECENTRE -> R.string.head_tracking_recentre
        PlayerPin.LISTEN_TOGETHER -> R.string.together_title
    }
)

/** Whether [pin] can be offered at all in this build: Listen together is only where it is built. */
internal fun pinOffered(pin: PlayerPin): Boolean = pin != PlayerPin.LISTEN_TOGETHER || togetherAvailable

/**
 * The menu's entries for the player's pinned buttons, watching in the database only what one of
 * the [pinned] needs for the song playing.
 */
@Composable
internal fun rememberPinActions(
    mediaMetadata: MediaMetadata?,
    navController: NavController,
    playerBottomSheetState: BottomSheetState,
    pinned: Set<PlayerPin>,
    inRoom: Boolean,
    openRoom: () -> Unit,
): PinActions {
    val context = LocalContext.current
    val database = LocalDatabase.current
    val downloadUtil = LocalDownloadUtil.current
    val playerConnection = LocalPlayerConnection.current
    val clipboardManager = LocalClipboard.current
    val coroutineScope = rememberCoroutineScope()
    val songId = mediaMetadata?.id

    val watchSong = songId != null && (PlayerPin.LIBRARY in pinned || PlayerPin.DETAILS in pinned)
    val librarySong by remember(songId, watchSong) { if (watchSong) database.song(songId) else flowOf(null) }.collectAsState(initial = null)
    val watchDownload = songId != null && PlayerPin.DOWNLOAD in pinned
    val download by remember(songId, watchDownload) { if (watchDownload) downloadUtil.getDownload(songId!!) else flowOf(null) }.collectAsState(initial = null)
    val watchPlaylists = songId != null && PlayerPin.REMOVE_FROM_PLAYLIST in pinned
    val containing by remember(songId, watchPlaylists) { if (watchPlaylists) database.playlistsContaining(songId!!) else flowOf(emptyList()) }.collectAsState(initial = emptyList())
    val watchFormat = songId != null && (PlayerPin.DETAILS in pinned || PlayerPin.SIGNAL_PATH in pinned)
    val currentFormat by remember(songId, watchFormat) { if (watchFormat) database.format(songId) else flowOf(null) }.collectAsState(initial = null)
    // As the menu has it: while sync is read only, only local playlists can be edited.
    val syncMode by rememberEnumPreference(key = YtmSyncModeKey, defaultValue = SyncMode.RW)
    val inPlaylists = if (syncMode == SyncMode.RO) containing.filter { it.playlist.isLocal } else containing
    val artists = remember(mediaMetadata) { mediaMetadata?.artists?.withArtistIds().orEmpty() }
    val equalizerLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { }

    var showQueues by rememberSaveable { mutableStateOf(false) }
    var showPlaylists by rememberSaveable { mutableStateOf(false) }
    var showRemove by rememberSaveable { mutableStateOf(false) }
    var showArtists by rememberSaveable { mutableStateOf(false) }
    var showDetails by rememberSaveable { mutableStateOf(false) }
    var showAdvanced by rememberSaveable { mutableStateOf(false) }
    var showSignalPath by rememberSaveable { mutableStateOf(false) }

    val downloaded = getDownloadState(download) == Download.STATE_COMPLETED
    val inLibrary = librarySong?.song?.inLibrary != null

    if (mediaMetadata != null && playerConnection != null) {
        if (showQueues) {
            AddToQueueDialog(
                onAdd = { queueName ->
                    playerConnection.service.queueBoard.addQueue(
                        queueName, listOf(mediaMetadata), forceInsert = true, delta = false, origin = PlayOrigin.QUEUE,
                    )?.let { playerConnection.service.queueBoard.setCurrQueue(it) }
                },
                onDismiss = { showQueues = false },
            )
        }
        if (showPlaylists) {
            AddToPlaylistDialog(
                navController = navController,
                songIds = listOf(mediaMetadata.id),
                onPreAdd = { playlist ->
                    // Written before this returns, as the player's menu does: the add needs the row (#62).
                    database.transactionNow { insert(mediaMetadata) }
                    ActivityLog.note(context, database, mediaMetadata.id, SignalKind.ADD_TO_PLAYLIST)
                    playlist.playlist.browseId?.let { YouTube.addToPlaylist(it, mediaMetadata.id) }
                    listOf(mediaMetadata.id)
                },
                onDismiss = { showPlaylists = false },
            )
        }
        if (showRemove) {
            ListDialog(onDismiss = { showRemove = false }) {
                items(inPlaylists) { playlist ->
                    ListItem(
                        title = playlist.playlist.name,
                        thumbnailContent = {
                            Image(
                                painter = rememberVectorPainter(Icons.Rounded.PlaylistRemove),
                                contentDescription = null,
                                colorFilter = ColorFilter.tint(MaterialTheme.colorScheme.onBackground),
                                modifier = Modifier.size(ListThumbnailSize),
                            )
                        },
                        modifier = Modifier.clickable {
                            showRemove = false
                            removeFromPlaylist(database, coroutineScope, playlist, mediaMetadata.id)
                        },
                    )
                }
            }
        }
        if (showArtists) {
            ArtistDialog(
                navController = navController,
                artists = artists,
                onDismiss = {
                    playerBottomSheetState.collapseSoft()
                    showArtists = false
                },
            )
        }
        if (showDetails) {
            DetailsDialog(
                mediaMetadata = mediaMetadata,
                currentFormat = currentFormat,
                currentPlayCount = librarySong?.playCount?.fastSumBy { it.count } ?: 0,
                clipboardManager = clipboardManager,
                setVisibility = { showDetails = it },
            )
        }
        if (showAdvanced) PitchTempoDialog(onDismiss = { showAdvanced = false })
        if (showSignalPath) {
            SignalPathDialog(
                currentFormat = currentFormat,
                local = mediaMetadata.isLocal,
                onDismiss = { showSignalPath = false },
            )
        }
    }

    val local = mediaMetadata?.isLocal != false
    return PinActions(
        shown = { pin ->
            pinOffered(pin) && when (pin) {
                PlayerPin.SHARE, PlayerPin.RADIO, PlayerPin.DOWNLOAD, PlayerPin.LIBRARY -> !local
                PlayerPin.ALBUM -> !local && mediaMetadata?.album != null
                PlayerPin.ARTIST -> artists.isNotEmpty()
                PlayerPin.REMOVE_FROM_PLAYLIST -> inPlaylists.isNotEmpty()
                PlayerPin.RECENTRE -> playerConnection?.service?.headTracking?.isRunning == true
                else -> mediaMetadata != null
            }
        },
        on = { pin ->
            when (pin) {
                PlayerPin.DOWNLOAD -> downloaded
                PlayerPin.LIBRARY -> inLibrary
                PlayerPin.LISTEN_TOGETHER -> inRoom
                else -> false
            }
        },
        run = run@{ pin ->
            val song = mediaMetadata ?: return@run
            val connection = playerConnection ?: return@run
            when (pin) {
                PlayerPin.RADIO -> connection.playQueue(YouTubeQueue.radio(song), isRadio = true)
                PlayerPin.ADD_TO_QUEUE -> showQueues = true
                PlayerPin.ADD_TO_PLAYLIST -> showPlaylists = true
                // With one playlist it acts at once; with several it asks, as the menu does.
                PlayerPin.REMOVE_FROM_PLAYLIST ->
                    if (inPlaylists.size == 1) removeFromPlaylist(database, coroutineScope, inPlaylists.first(), song.id)
                    else showRemove = true
                PlayerPin.DOWNLOAD -> if (getDownloadState(download) != Download.STATE_STOPPED) {
                    downloadUtil.removeDownload(song.id)
                } else {
                    database.transaction { insert(song) }
                    ActivityLog.note(context, database, song.id, SignalKind.DOWNLOAD)
                    downloadUtil.download(song)
                }
                PlayerPin.LIBRARY -> if (inLibrary) {
                    database.query { toggleInLibrary(song.id, null) }
                } else {
                    database.transaction {
                        insert(song)
                        toggleInLibrary(song.id, LocalDateTime.now())
                    }
                }
                PlayerPin.ARTIST -> if (artists.size == 1) {
                    ActivityLog.note(context, database, song.id, SignalKind.ARTIST_PAGE)
                    navController.navigate("artist/${artists[0].id}")
                    playerBottomSheetState.collapseSoft()
                } else {
                    showArtists = true
                }
                PlayerPin.ALBUM -> song.album?.let {
                    navController.navigate("album/${it.id}")
                    playerBottomSheetState.collapseSoft()
                }
                PlayerPin.DETAILS -> showDetails = true
                PlayerPin.EQUALIZER -> {
                    val intent = Intent(AudioEffect.ACTION_DISPLAY_AUDIO_EFFECT_CONTROL_PANEL).apply {
                        putExtra(AudioEffect.EXTRA_AUDIO_SESSION, connection.service.exoPlayer.audioSessionId)
                        putExtra(AudioEffect.EXTRA_PACKAGE_NAME, context.packageName)
                        putExtra(AudioEffect.EXTRA_CONTENT_TYPE, AudioEffect.CONTENT_TYPE_MUSIC)
                    }
                    if (intent.resolveActivity(context.packageManager) != null) equalizerLauncher.launch(intent)
                }
                PlayerPin.ADVANCED -> showAdvanced = true
                PlayerPin.SIGNAL_PATH -> showSignalPath = true
                PlayerPin.RECENTRE -> connection.service.headTracking.recentre()
                PlayerPin.LISTEN_TOGETHER -> openRoom()
                // The player's own, drawn and handled by it.
                PlayerPin.SHARE, PlayerPin.LYRICS, PlayerPin.TIMER, PlayerPin.LIKE -> Unit
            }
        },
    )
}
