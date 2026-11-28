/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.ui.player

import com.dd3boh.outertune.constants.Unreleased
import androidx.compose.material.icons.rounded.CastConnected
import androidx.compose.material.icons.rounded.Cast
import android.content.Intent
import android.media.audiofx.AudioEffect
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.PlaylistAdd
import androidx.compose.material.icons.automirrored.rounded.QueueMusic
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Equalizer
import androidx.compose.material.icons.rounded.FilterCenterFocus
import androidx.compose.material.icons.rounded.FolderOpen
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.alpha
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
import com.dd3boh.outertune.models.viewRoute
import com.dd3boh.outertune.models.viewable
import com.dd3boh.outertune.utils.urlEncode
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
import com.dd3boh.outertune.ui.menu.OpenFileLocation
import com.dd3boh.outertune.ui.menu.PitchTempoDialog
import com.dd3boh.outertune.ui.menu.removeFromPlaylist
import com.dd3boh.outertune.utils.ActivityLog
import com.dd3boh.outertune.utils.getDownloadState
import com.dd3boh.outertune.utils.rememberEnumPreference
import com.dd3boh.outertune.utils.Toaster
import com.zionhuang.innertube.YouTube
import kotlinx.coroutines.flow.flowOf
import java.time.LocalDateTime

/**
 * What a pinned button does for the entries that live in the player's menu (issue #27): the same
 * as the entry there, with the dialogs it opens shown over the player. Share, lyrics, the sleep
 * timer and like are the player's own and drawn by it.
 */
internal class PinActions(
    /** Whether the button is there at all: Listen together only where rooms are. */
    val shown: (PlayerPin) -> Boolean,
    /**
     * Why the button can do nothing for the song playing, or null when it can: a local file has no radio, link,
     * download or album. Such a button keeps its place, greyed, and a tap says why ([PinGreyed]).
     */
    val unavailable: (PlayerPin) -> Int?,
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
    PlayerPin.OPEN_FILE_LOCATION -> rememberVectorPainter(Icons.Rounded.FolderOpen)
    PlayerPin.DETAILS -> rememberVectorPainter(Icons.Rounded.Info)
    PlayerPin.EQUALIZER -> rememberVectorPainter(Icons.Rounded.Equalizer)
    PlayerPin.ADVANCED -> rememberVectorPainter(Icons.Rounded.Tune)
    PlayerPin.SIGNAL_PATH -> rememberVectorPainter(Icons.Rounded.Route)
    PlayerPin.RECENTRE -> rememberVectorPainter(Icons.Rounded.FilterCenterFocus)
    PlayerPin.LISTEN_TOGETHER -> rememberVectorPainter(Icons.Rounded.Group)
    PlayerPin.CAST -> rememberVectorPainter(if (on) Icons.Rounded.CastConnected else Icons.Rounded.Cast)
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
        PlayerPin.OPEN_FILE_LOCATION -> R.string.open_file_location
        PlayerPin.DETAILS -> R.string.details
        PlayerPin.EQUALIZER -> R.string.equalizer
        PlayerPin.ADVANCED -> R.string.advanced
        PlayerPin.SIGNAL_PATH -> R.string.signal_path
        PlayerPin.RECENTRE -> R.string.head_tracking_recentre
        PlayerPin.LISTEN_TOGETHER -> R.string.together_title
        PlayerPin.CAST -> R.string.cast
    }
)

/** Whether [pin] can be offered at all in this build: Listen together is only where it is built, the cast button where casting is. */
internal fun pinOffered(pin: PlayerPin): Boolean = when (pin) {
    PlayerPin.LISTEN_TOGETHER -> togetherAvailable
    PlayerPin.CAST -> Unreleased.CASTING
    else -> true
}

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
    /** The cast button's look and tap where it can be drawn here ([rememberCastLook]), else null. */
    cast: CastLook? = null,
): PinActions {
    val context = LocalContext.current
    val database = LocalDatabase.current
    val downloadUtil = LocalDownloadUtil.current
    val playerConnection = LocalPlayerConnection.current
    val clipboardManager = LocalClipboard.current
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
    // Every credited artist, one with no page as a search for its name (ArtistLinks), as the player's menu has them.
    val artists = remember(mediaMetadata) { mediaMetadata?.artists?.viewable().orEmpty() }
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
                // As the player's menu does: a guest's song goes to the room's queue, the picker is for everyone else.
                songs = { listOf(mediaMetadata) },
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
                            removeFromPlaylist(database, playlist, mediaMetadata.id)
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
    // A button that cannot act for this song keeps its place, greyed: hidden, it left a gap that moved the row from
    // song to song, and Niko took six pins for broken on a local song (#testers, 18 Nov 2026).
    val why: (PlayerPin) -> Int? = { pin ->
        when (pin) {
            PlayerPin.SHARE, PlayerPin.RADIO, PlayerPin.DOWNLOAD, PlayerPin.LIBRARY -> R.string.pin_not_for_local.takeIf { local }
            PlayerPin.ALBUM -> when {
                local -> R.string.pin_not_for_local
                mediaMetadata?.album == null -> R.string.pin_no_album
                else -> null
            }
            PlayerPin.OPEN_FILE_LOCATION ->
                R.string.pin_only_local.takeIf { mediaMetadata?.isLocal != true || mediaMetadata?.localPath.isNullOrBlank() }
            PlayerPin.ARTIST -> R.string.no_artist_found.takeIf { artists.isEmpty() }
            PlayerPin.REMOVE_FROM_PLAYLIST -> R.string.pin_not_in_playlist.takeIf { inPlaylists.isEmpty() }
            PlayerPin.RECENTRE -> R.string.pin_head_tracking_off.takeIf { playerConnection?.service?.headTracking?.isRunning != true }
            else -> null
        }
    }
    return PinActions(
        shown = { pin -> pinOffered(pin) && (pin != PlayerPin.CAST || cast != null) },
        unavailable = why,
        on = { pin ->
            when (pin) {
                PlayerPin.DOWNLOAD -> downloaded
                PlayerPin.LIBRARY -> inLibrary
                PlayerPin.LISTEN_TOGETHER -> inRoom
                PlayerPin.CAST -> cast?.casting == true
                else -> false
            }
        },
        run = run@{ pin ->
            val song = mediaMetadata ?: return@run
            val connection = playerConnection ?: return@run
            // The greyed button's own action never runs: a tap the grey's overlay misses (the Classic circle sits
            // 5 dp below its box), TalkBack or a keyboard would start a radio on a local file and lose the queue.
            why(pin)?.let { Toaster.show(context, it); return@run }
            when (pin) {
                PlayerPin.RADIO -> connection.playQueue(YouTubeQueue.radio(song), isRadio = true)
                PlayerPin.ADD_TO_QUEUE -> showQueues = true
                PlayerPin.ADD_TO_PLAYLIST -> showPlaylists = true
                // With one playlist it acts at once; with several it asks, as the menu does.
                PlayerPin.REMOVE_FROM_PLAYLIST ->
                    if (inPlaylists.size == 1) removeFromPlaylist(database, inPlaylists.first(), song.id)
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
                    artists[0].viewRoute { it.urlEncode() }?.let { navController.navigate(it) }
                    playerBottomSheetState.collapseSoft()
                } else {
                    showArtists = true
                }
                PlayerPin.ALBUM -> song.album?.let {
                    navController.navigate("album/${it.id}")
                    playerBottomSheetState.collapseSoft()
                }
                PlayerPin.OPEN_FILE_LOCATION -> OpenFileLocation.open(context, song.localPath)
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
                PlayerPin.CAST -> cast?.open?.invoke()
                // The player's own, drawn and handled by it.
                PlayerPin.SHARE, PlayerPin.LYRICS, PlayerPin.TIMER, PlayerPin.LIKE -> Unit
            }
        },
    )
}

/**
 * A pinned button that can do nothing for the song playing ([PinActions.unavailable]): it keeps its place and size,
 * greyed, and a tap says why instead of doing nothing (his pick of 18 Nov 2026).
 */
@Composable
internal fun PinGreyed(why: Int?, content: @Composable () -> Unit) {
    if (why == null) {
        content()
        return
    }
    val context = LocalContext.current
    val reason = stringResource(why)
    Box(contentAlignment = Alignment.Center) {
        Box(modifier = Modifier.alpha(0.38f)) { content() }
        Box(
            modifier = Modifier
                .matchParentSize()
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClickLabel = reason,
                ) { Toaster.show(context, reason) }
        )
    }
}
