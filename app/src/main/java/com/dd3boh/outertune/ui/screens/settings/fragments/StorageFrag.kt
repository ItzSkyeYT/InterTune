package com.dd3boh.outertune.ui.screens.settings.fragments

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.rounded.Backup
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Downloading
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.FolderCopy
import androidx.compose.material.icons.rounded.Output
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material.icons.rounded.Restore
import androidx.compose.material.icons.rounded.Sync
import androidx.compose.material.icons.rounded.Wifi
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.documentfile.provider.DocumentFile
import coil3.annotation.ExperimentalCoilApi
import coil3.imageLoader
import com.dd3boh.outertune.LocalDatabase
import com.dd3boh.outertune.LocalDownloadUtil
import com.dd3boh.outertune.LocalPlayerConnection
import com.dd3boh.outertune.R
import com.dd3boh.outertune.constants.AutoBackupEnabledKey
import com.dd3boh.outertune.constants.AutoBackupFolderKey
import com.dd3boh.outertune.constants.AutoBackupIntervalHoursKey
import com.dd3boh.outertune.constants.AutoBackupKeepKey
import com.dd3boh.outertune.constants.AutoBackupLastResultKey
import com.dd3boh.outertune.constants.AutoBackupLastRunKey
import com.dd3boh.outertune.constants.DownloadExtraPathKey
import com.dd3boh.outertune.constants.DownloadPathKey
import com.dd3boh.outertune.constants.DownloadOnWifiOnlyKey
import com.dd3boh.outertune.constants.MaxImageCacheSizeKey
import com.dd3boh.outertune.constants.MaxSongCacheSizeKey
import com.dd3boh.outertune.constants.PlaylistFilter
import com.dd3boh.outertune.constants.PlaylistSortType
import com.dd3boh.outertune.constants.ScanPathsKey
import com.dd3boh.outertune.constants.ThumbnailCornerRadius
import com.dd3boh.outertune.db.MusicDatabase
import com.dd3boh.outertune.extensions.tryOrNull
import com.dd3boh.outertune.ui.component.ListPreference
import com.dd3boh.outertune.ui.component.PreferenceEntry
import com.dd3boh.outertune.ui.component.SettingsClickToReveal
import com.dd3boh.outertune.ui.component.SwitchPreference
import com.dd3boh.outertune.ui.component.button.IconButton
import com.dd3boh.outertune.ui.component.button.ResizableIconButton
import com.dd3boh.outertune.ui.dialog.ActionPromptDialog
import com.dd3boh.outertune.ui.dialog.DefaultDialog
import com.dd3boh.outertune.ui.dialog.InfoLabel
import com.dd3boh.outertune.utils.AutoBackup
import com.dd3boh.outertune.utils.M3u
import com.dd3boh.outertune.utils.dlCoroutine
import com.dd3boh.outertune.utils.formatFileSize
import com.dd3boh.outertune.utils.rememberPreference
import com.dd3boh.outertune.utils.reportException
import com.dd3boh.outertune.utils.scanners.absoluteFilePathFromUri
import com.dd3boh.outertune.utils.scanners.stringFromUriList
import com.dd3boh.outertune.utils.scanners.uriListFromString
import com.dd3boh.outertune.viewmodels.BackupRestoreViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.io.IOException
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Date
import java.util.Locale
import android.content.Context
import android.widget.Toast
import androidx.compose.material.icons.rounded.Favorite
import com.dd3boh.outertune.constants.LikedAutoDownloadKey
import com.dd3boh.outertune.constants.LikedAutodownloadMode
import com.dd3boh.outertune.playback.DownloadUtil
import com.dd3boh.outertune.ui.component.EnumListPreference
import com.dd3boh.outertune.utils.rememberEnumPreference
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.withContext

@Composable
fun ColumnScope.BackupAndRestoreFrag(viewModel: BackupRestoreViewModel) {
    val context = LocalContext.current

    val backupLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
            if (uri != null) {
                viewModel.backup(uri)
            }
        }
    val restoreLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            viewModel.restore(uri)
        }
    }

    // Every library playlist as an .m3u in a folder of the user's choosing. A one-off write, so
    // the picker's grant is used here and now and never persisted.
    val database = LocalDatabase.current
    val exportPlaylistsLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
            if (uri == null) return@rememberLauncherForActivityResult
            val appContext = context.applicationContext
            // Its own scope rather than the screen's: leaving Settings must not stop the writes
            // half way through the library.
            CoroutineScope(Dispatchers.IO).launch {
                val exported = try {
                    exportPlaylistsAsM3u(appContext, database, uri)
                } catch (e: Exception) {
                    reportException(e)
                    0
                }
                withContext(Dispatchers.Main) {
                    Toast.makeText(
                        appContext,
                        if (exported == 0) appContext.getString(R.string.no_playlists_to_export)
                        else appContext.getString(R.string.exported_playlists, exported),
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }
        }

    // Automatic backups. Inert until the switch is on and a folder is chosen: AutoBackup.schedule
    // cancels rather than enqueues in that state, so nothing runs for anyone who has not opted in.
    val (autoBackupEnabled, onAutoBackupEnabledChange) =
        rememberPreference(AutoBackupEnabledKey, defaultValue = false)
    val (autoBackupFolder, onAutoBackupFolderChange) =
        rememberPreference(AutoBackupFolderKey, defaultValue = "")
    val (autoBackupHours, onAutoBackupHoursChange) =
        rememberPreference(AutoBackupIntervalHoursKey, defaultValue = AutoBackup.DEFAULT_INTERVAL_HOURS)
    val (autoBackupKeep, onAutoBackupKeepChange) =
        rememberPreference(AutoBackupKeepKey, defaultValue = AutoBackup.DEFAULT_KEEP)
    val autoBackupLastRun by rememberPreference(AutoBackupLastRunKey, defaultValue = 0L)
    val autoBackupLastResult by rememberPreference(AutoBackupLastResultKey, defaultValue = "")

    // Set when the switch is what opened the picker, so that choosing a folder is what turns it
    // on. A switch that is on with nowhere to write would sit there doing nothing.
    var turnOnAfterPick by rememberSaveable { mutableStateOf(false) }

    val folderLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
            if (uri == null) {
                turnOnAfterPick = false
                return@rememberLauncherForActivityResult
            }
            // The grant from the picker dies with this activity. Persisting it is what lets the
            // worker write there next week, and after a reboot.
            // A third party picker can hand back a grant that cannot be persisted. Not a reason
            // to crash the settings screen: the worker reports the folder as unavailable instead.
            runCatching {
                context.contentResolver.takePersistableUriPermission(
                    uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                )
            }.onFailure { reportException(it) }
            val folder = uri.toString()
            onAutoBackupFolderChange(folder)
            val enabled = turnOnAfterPick || autoBackupEnabled
            if (turnOnAfterPick) onAutoBackupEnabledChange(true)
            turnOnAfterPick = false
            // Handed the values just chosen rather than left to re-read preferences that have
            // not landed yet, the same trap BackgroundCheckWorker.schedule documents.
            AutoBackup.schedule(context, enabled = enabled, folder = folder)
        }

    // The display name is a content provider query, so it stays off the main thread and is only
    // asked again when the folder changes.
    val folderName by produceState<String?>(initialValue = null, autoBackupFolder) {
        value = if (autoBackupFolder.isBlank()) null
        else withContext(Dispatchers.IO) {
            tryOrNull { DocumentFile.fromTreeUri(context, autoBackupFolder.toUri())?.name }
                ?: autoBackupFolder
        }
    }

    ElevatedCard(
        modifier = Modifier.fillMaxWidth()
    ) {
        PreferenceEntry(
            title = { Text(stringResource(R.string.action_backup)) },
            icon = { Icon(Icons.Rounded.Backup, null) },
            onClick = {
                val formatter = DateTimeFormatter.ofPattern("yyyyMMddHHmmss")
                backupLauncher.launch(
                    "${context.getString(R.string.app_name)}_${MusicDatabase.MUSIC_DATABASE_VERSION}_${
                        LocalDateTime.now().format(formatter)
                    }.backup"
                )
            }
        )
    }
    Spacer(modifier = Modifier.height(16.dp))

    ElevatedCard(
        modifier = Modifier.fillMaxWidth()
    ) {
        PreferenceEntry(
            title = { Text(stringResource(R.string.action_restore)) },
            icon = { Icon(Icons.Rounded.Restore, null) },
            onClick = {
                restoreLauncher.launch(arrayOf("application/octet-stream"))
            }
        )
    }
    Spacer(modifier = Modifier.height(16.dp))

    ElevatedCard(
        modifier = Modifier.fillMaxWidth()
    ) {
        SwitchPreference(
            title = { Text(stringResource(R.string.auto_backup)) },
            description = stringResource(R.string.auto_backup_description),
            icon = { Icon(Icons.Rounded.Schedule, null) },
            checked = autoBackupEnabled,
            onCheckedChange = { on ->
                if (on && autoBackupFolder.isBlank()) {
                    // Ask where first. The picker's result is what flips the switch.
                    turnOnAfterPick = true
                    folderLauncher.launch(null)
                } else {
                    onAutoBackupEnabledChange(on)
                    AutoBackup.schedule(context, enabled = on)
                }
            }
        )

        PreferenceEntry(
            title = { Text(stringResource(R.string.auto_backup_folder)) },
            description = folderName ?: stringResource(R.string.auto_backup_choose_folder),
            icon = { Icon(Icons.Rounded.Folder, null) },
            onClick = {
                turnOnAfterPick = false
                folderLauncher.launch(null)
            }
        )

        ListPreference(
            title = { Text(stringResource(R.string.auto_backup_interval)) },
            selectedValue = autoBackupHours,
            values = AutoBackup.INTERVAL_CHOICES,
            valueText = {
                when (it) {
                    24 -> stringResource(R.string.auto_backup_every_day)
                    else -> stringResource(R.string.auto_backup_every_week)
                }
            },
            onValueSelected = {
                onAutoBackupHoursChange(it)
                AutoBackup.schedule(context, hours = it)
            }
        )

        ListPreference(
            title = { Text(stringResource(R.string.auto_backup_keep)) },
            selectedValue = autoBackupKeep,
            values = AutoBackup.KEEP_CHOICES,
            valueText = { stringResource(R.string.auto_backup_keep_count, it) },
            onValueSelected = {
                onAutoBackupKeepChange(it)
                // The worker reads this when it runs; re-applying the schedule keeps the rule
                // that every change here goes through the same door.
                AutoBackup.schedule(context)
            }
        )

        PreferenceEntry(
            title = { Text(stringResource(R.string.auto_backup_now)) },
            icon = { Icon(Icons.Rounded.Backup, null) },
            isEnabled = autoBackupFolder.isNotBlank(),
            onClick = { AutoBackup.runNow(context) }
        )

        Column(
            modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 6.dp, bottom = 12.dp)
        ) {
            Text(
                text = if (autoBackupLastRun > 0L) {
                    stringResource(
                        R.string.auto_backup_last,
                        DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT, Locale.getDefault())
                            .format(Date(autoBackupLastRun))
                    )
                } else {
                    stringResource(R.string.auto_backup_last_never)
                },
                style = MaterialTheme.typography.bodyMedium,
            )
            // "ok" is the worker's own word for a clean run and is not worth a line; anything
            // else is the reason the last run did not happen, in the worker's words.
            if (autoBackupLastResult.isNotBlank() && autoBackupLastResult != AutoBackup.RESULT_OK) {
                Text(
                    text = autoBackupLastResult,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
    }
    Spacer(modifier = Modifier.height(16.dp))

    ElevatedCard(
        modifier = Modifier.fillMaxWidth()
    ) {
        PreferenceEntry(
            title = { Text(stringResource(R.string.export_playlists_m3u)) },
            description = stringResource(R.string.export_playlists_m3u_description),
            icon = { Icon(Icons.Rounded.Output, null) },
            onClick = { exportPlaylistsLauncher.launch(null) }
        )
    }
}

/**
 * Writes one .m3u per library playlist into the folder at [treeUri] and returns how many were
 * written. Playlists with no songs are skipped: an empty file would only be clutter. A playlist
 * whose songs cannot be read or whose file cannot be created or written is reported and skipped
 * so that one bad playlist does not stop the rest, and the count stays true to the files that did
 * land in the folder. Room is read through its flows, which run on the query executor, so the
 * caller only has to be off the main thread for the file writes.
 */
private suspend fun exportPlaylistsAsM3u(context: Context, database: MusicDatabase, treeUri: Uri): Int {
    val folder = DocumentFile.fromTreeUri(context, treeUri) ?: return 0
    val playlists = database.playlists(PlaylistFilter.LIBRARY, PlaylistSortType.NAME, true).first()
    val used = HashSet<String>()
    var exported = 0
    for (playlist in playlists) {
        // Everything for one playlist sits in one try, and the catch is wide on purpose: a grant
        // that died with the activity surfaces as a SecurityException, not an IOException, and
        // letting that escape would throw away the count of files already written.
        try {
            val songs = database.playlistSongs(playlist.id).first().map { it.song }
            if (songs.isEmpty()) continue
            val name = M3u.fileName(playlist.playlist.name, used)
            used += name
            // DocumentFile swallows whatever the provider threw and hands back null, so the null
            // is the only trace of a refused file and has to be reported here or it is lost.
            val file = folder.createFile("audio/x-mpegurl", name)
            if (file == null) {
                reportException(IOException("Could not create $name in $treeUri"))
                continue
            }
            val out = context.contentResolver.openOutputStream(file.uri)
            if (out == null) {
                reportException(IOException("Could not open $name for writing"))
                continue
            }
            out.use { it.write(M3u.playlist(songs).toByteArray(Charsets.UTF_8)) }
            exported++
        } catch (e: Exception) {
            reportException(e)
        }
    }
    return exported
}

@Composable
fun ColumnScope.DownloadsFrag() {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    // Nullable rather than an early return. This used to bail before rendering anything, so
    // opening Settings > Storage on a cold start, before the player service had bound, showed no
    // Downloads card at all and any setting in it looked missing.
    val downloadCache = LocalPlayerConnection.current?.service?.downloadCache
    val downloadUtil = LocalDownloadUtil.current

    val (downloadPath, onDownloadPathChange) = rememberPreference(DownloadPathKey, "")
    val (downloadOnWifiOnly, onDownloadOnWifiOnlyChange) = rememberPreference(DownloadOnWifiOnlyKey, defaultValue = false)
    val (scanPaths, onScanPathsChange) = rememberPreference(ScanPathsKey, defaultValue = "")
    val (likedAutodownload, onLikedAutodownloadChange) =
        rememberEnumPreference(LikedAutoDownloadKey, LikedAutodownloadMode.OFF)

    // size stats
    var downloadCacheSize by remember {
        mutableLongStateOf(tryOrNull { downloadCache?.cacheSpace } ?: 0)
    }
    var downloadMainPathSize by remember {
        mutableLongStateOf(-2L)
    }
    var downloadExtraPathSize by remember {
        mutableLongStateOf(-2L)
    }

    // downloads dialogs
    var showDlPathDialog: Boolean by remember {
        mutableStateOf(false)
    }
    var showClearConfirmDialog by remember {
        mutableStateOf(false)
    }
    var showDlInfoDialog by remember {
        mutableStateOf(false)
    }

    // advanced
    val (dlPathExtra, onDlPathExtraChange) = rememberPreference(DownloadExtraPathKey, "")
    val isLoading by downloadUtil.isProcessingDownloads.collectAsState()
    var showMigrationDialog by rememberSaveable {
        mutableStateOf(false)
    }
    var showImportDialog by rememberSaveable {
        mutableStateOf(false)
    }
    var showPathsDialog by rememberSaveable {
        mutableStateOf(false)
    }

    LaunchedEffect(downloadCache) {
        while (isActive) {
            delay(2000)
            downloadCacheSize = tryOrNull { downloadCache?.cacheSpace } ?: 0
        }
    }

    SwitchPreference(
        title = { Text(stringResource(R.string.download_on_wifi_only_title)) },
        description = stringResource(R.string.download_on_wifi_only_description),
        icon = { Icon(Icons.Rounded.Wifi, null) },
        checked = downloadOnWifiOnly,
        onCheckedChange = {
            onDownloadOnWifiOnlyChange(it)
            // Push it to the running service too, so queued and in-flight downloads follow the new
            // rule rather than waiting for the next app start.
            downloadUtil.setDownloadRequirements(it)
        }
    )

    EnumListPreference(
        title = { Text(stringResource(R.string.like_autodownload)) },
        icon = { Icon(Icons.Rounded.Favorite, null) },
        selectedValue = likedAutodownload,
        valueText = {
            when (it) {
                LikedAutodownloadMode.OFF -> stringResource(androidx.compose.ui.R.string.state_off)
                LikedAutodownloadMode.ON -> stringResource(androidx.compose.ui.R.string.state_on)
                LikedAutodownloadMode.WIFI_ONLY -> stringResource(R.string.wifi_only)
            }
        },
        onValueSelected = { mode ->
            onLikedAutodownloadChange(mode)
            // Switching it on is itself the catch-up trigger, which is what "download all my liked
            // songs" means. Not rememberCoroutineScope: that dies when this screen leaves the
            // composition, which would abandon a few-hundred-song enqueue halfway.
            if (mode != LikedAutodownloadMode.OFF) {
                downloadUtil.startLikedDownloads(mode)
            }
        },
    )

    val likedDownloadState by downloadUtil.likedDownloadState.collectAsState()

    // The state lives on a singleton, so without this a finished run would still be reported the
    // next time this screen is opened, against a count that has moved on since.
    DisposableEffect(Unit) {
        onDispose { downloadUtil.acknowledgeLikedDownloads() }
    }

    PreferenceEntry(
        title = { Text(stringResource(R.string.liked_autodownload_backfill_title)) },
        description = when (val st = likedDownloadState) {
            is DownloadUtil.LikedDownloadState.Running ->
                stringResource(R.string.liked_autodownload_running, st.done, st.total)

            is DownloadUtil.LikedDownloadState.Finished ->
                if (st.stoppedEarly) stringResource(R.string.liked_autodownload_stopped, st.done)
                else stringResource(R.string.liked_autodownload_finished, st.done)

            DownloadUtil.LikedDownloadState.NeedsWifi ->
                stringResource(R.string.liked_autodownload_needs_wifi)

            DownloadUtil.LikedDownloadState.NothingToDo ->
                stringResource(R.string.liked_autodownload_nothing_to_do)

            DownloadUtil.LikedDownloadState.Blocked ->
                stringResource(R.string.liked_autodownload_blocked)

            DownloadUtil.LikedDownloadState.Idle ->
                stringResource(R.string.liked_autodownload_backfill_description)
        },
        icon = { Icon(Icons.Rounded.Downloading, null) },
        isEnabled = likedAutodownload != LikedAutodownloadMode.OFF ||
                likedDownloadState is DownloadUtil.LikedDownloadState.Running,
        onClick = {
            // Same shape as the loudness repair row: while it runs, the button stops it.
            if (downloadUtil.isDownloadingLiked) downloadUtil.cancelLikedDownloads()
            else downloadUtil.startLikedDownloads(likedAutodownload)
        }
    )

    PreferenceEntry(
        title = { Text(stringResource(R.string.dl_main_path_title)) },
        description = if (downloadPath != "") uriListFromString(downloadPath).firstOrNull()
            ?.let { absoluteFilePathFromUri(context, it) } ?: downloadPath else null,
        onClick = {
            showDlPathDialog = true
        },
    )

    Text(
        text = stringResource(R.string.dl_size_used_cache, formatFileSize(downloadCacheSize)),
        style = MaterialTheme.typography.bodyMedium,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp)
    )

    if (downloadMainPathSize == -2L && downloadExtraPathSize == -2L) {
        PreferenceEntry(
            title = {
                Row(
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(stringResource(R.string.dl_calculate_size))
                    ResizableIconButton(
                        icon = Icons.Outlined.Info,
                        onClick = { showDlInfoDialog = true },
                    )
                }
            },
            onClick = {
                downloadMainPathSize = -1
                downloadCacheSize = -1
                coroutineScope.launch(Dispatchers.IO) {
                    downloadMainPathSize = downloadUtil.localMgr.getMainDlStorageUsage()
                    downloadExtraPathSize = downloadUtil.localMgr.getExtraDlStorageUsage()
                }
            },
        )
    } else {
        Row(
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = stringResource(
                    R.string.dl_size_used_main,
                    formatFileSize(downloadMainPathSize.coerceIn(0, Long.MAX_VALUE))
                ),
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp)
            )
            if (downloadMainPathSize < 0L) {
                CircularProgressIndicator(
                    modifier = Modifier.size(16.dp),
                    color = MaterialTheme.colorScheme.secondary,
                    trackColor = MaterialTheme.colorScheme.surfaceVariant,
                )
            }
        }
        Row(
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = stringResource(
                    R.string.dl_size_used_extra,
                    formatFileSize(downloadExtraPathSize.coerceIn(0, Long.MAX_VALUE))
                ),
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp)
            )
            if (downloadExtraPathSize < 0L) {
                CircularProgressIndicator(
                    modifier = Modifier.size(16.dp),
                    color = MaterialTheme.colorScheme.secondary,
                    trackColor = MaterialTheme.colorScheme.surfaceVariant,
                )
            }
        }
    }

    PreferenceEntry(
        title = { Text(stringResource(R.string.clear_all_downloads)) },
        onClick = {
            showClearConfirmDialog = true
        },
    )

    SettingsClickToReveal(stringResource(R.string.advanced)) {
        PreferenceEntry(
            title = { Text(stringResource(R.string.dl_extra_path_title)) },
            description = stringResource(R.string.dl_extra_path_description),
            icon = { Icon(Icons.Rounded.FolderCopy, null) },
            onClick = {
                showPathsDialog = true
            },
            isEnabled = !isLoading
        )

        PreferenceEntry(
            title = { Text(stringResource(R.string.dl_rescan_title)) },
            description = stringResource(R.string.dl_rescan_description),
            icon = {
                if (isLoading) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(28.dp),
                        color = MaterialTheme.colorScheme.secondary,
                        trackColor = MaterialTheme.colorScheme.surfaceVariant,
                    )
                } else {
                    Icon(Icons.Rounded.Sync, null)
                }
            },
            onClick = {
                showImportDialog = true
            },
            isEnabled = !isLoading && !(downloadPath.isEmpty() && dlPathExtra.isEmpty())
        )

        PreferenceEntry(
            title = { Text(stringResource(R.string.dl_migrate_title)) },
            description = stringResource(R.string.dl_migrate_description),
            icon = {
                if (isLoading) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(28.dp),
                        color = MaterialTheme.colorScheme.secondary,
                        trackColor = MaterialTheme.colorScheme.surfaceVariant,
                    )
                } else {
                    Icon(Icons.Rounded.Downloading, null)
                }
            },
            onClick = {
                showMigrationDialog = true
            },
            isEnabled = !isLoading && !downloadPath.isEmpty()
        )
    }


    /**
     * ---------------------------
     * Dialogs
     * ---------------------------
     */
    if (showDlPathDialog) {
        var tempFilePath by remember {
            mutableStateOf<Uri?>(null)
        }
        LaunchedEffect(downloadPath) {
            tempFilePath = uriListFromString(downloadPath).firstOrNull()
        }

        ActionPromptDialog(
            titleBar = {
                Text(
                    text = stringResource(R.string.dl_main_path_title),
                    style = MaterialTheme.typography.titleLarge,
                )
            },
            onDismiss = {
                showDlPathDialog = false
                tempFilePath = null
            },
            onConfirm = {
                val uris = stringFromUriList(listOfNotNull(tempFilePath))
                onDownloadPathChange(uris)

                showDlPathDialog = false
                tempFilePath = null

                coroutineScope.launch {
                    delay(1000)
                    downloadUtil.cd()
                }
            },
            onReset = {
                tempFilePath = null
            },
            onCancel = {
                showDlPathDialog = false
                tempFilePath = null
            },
            isInputValid = uriListFromString(scanPaths).none {
                // download path cannot a scan path, or a subdir of a scan path
                tempFilePath.toString().length <= it.toString().length && tempFilePath.toString()
                    .contains(it.toString())
            }
        ) {

            val dirPickerLauncher = rememberLauncherForActivityResult(
                ActivityResultContracts.OpenDocumentTree()
            ) { uri ->
                if (tempFilePath.toString() == uri.toString()) return@rememberLauncherForActivityResult
                if (uri?.path != null) {
                    // Take persistable URI permission
                    val contentResolver = context.contentResolver
                    val takeFlags: Int = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                    contentResolver.takePersistableUriPermission(uri, takeFlags)

                    tempFilePath = uri
                }
            }

            val valid = uriListFromString(scanPaths).none {
                // download path cannot a scan path, or a subdir of a scan path
                tempFilePath.toString().length <= it.toString().length && tempFilePath.toString()
                    .contains(it.toString())
            }

            Text(
                text = stringResource(R.string.dl_main_path_description),
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(horizontal = 4.dp)
            )
            Spacer(Modifier.padding(vertical = 8.dp))

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp)
                    .border(
                        2.dp,
                        MaterialTheme.colorScheme.primary.copy(alpha = 0.5f),
                        RoundedCornerShape(ThumbnailCornerRadius)
                    )
                    .background(if (valid) Color.Transparent else MaterialTheme.colorScheme.errorContainer)
            ) {
                tempFilePath?.let {
                    Text(
                        text = absoluteFilePathFromUri(context, it) ?: it.toString(),
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(8.dp)
                    )
                }
            }

            // add folder button
            Column {
                Button(onClick = { dirPickerLauncher.launch(null) }) {
                    Text(stringResource(R.string.scan_paths_add_folder))
                }

                InfoLabel(
                    text = stringResource(R.string.scan_paths_tooltip),
                    modifier = Modifier.padding(vertical = 16.dp)
                )

                if (!valid) {
                    InfoLabel(
                        text = stringResource(R.string.scanner_rejected_dir),
                        isError = true,
                        modifier = Modifier.padding(top = 8.dp)
                    )
                }
            }
        }
    }

    if (showClearConfirmDialog) {
        DefaultDialog(
            onDismiss = { showClearConfirmDialog = false },
            content = {
                Text(
                    text = stringResource(R.string.clear_downloads_confirm),
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.padding(horizontal = 18.dp)
                )
            },
            buttons = {
                TextButton(
                    onClick = { showClearConfirmDialog = false }
                ) {
                    Text(text = stringResource(android.R.string.cancel))
                }

                TextButton(
                    onClick = {
                        showClearConfirmDialog = false
                        coroutineScope.launch(Dispatchers.IO) {
                            // clear internal downloads
                            downloadCache?.keys?.forEach { key ->
                                downloadCache.removeResource(key)
                            }

                            // TODO: Delete external downloads. Rememebr to exclude extra paths
                            // clear external downloads
//                            database.downloadSongs(SongSortType.NAME, true).collect { songs ->
//                                songs.forEach { song ->
//                                    downloadUtil.delete(song)
//                                }
//                            }

                            downloadMainPathSize = downloadUtil.localMgr.getMainDlStorageUsage()
                            downloadExtraPathSize = downloadUtil.localMgr.getExtraDlStorageUsage()
                        }
                    }
                ) {
                    Text(text = stringResource(android.R.string.ok))
                }
            }
        )
    }

    if (showDlInfoDialog) {
        DefaultDialog(
            onDismiss = { showDlInfoDialog = false },
            content = {
                Column(
                    modifier = Modifier
                        .weight(1f, false)
                        .verticalScroll(rememberScrollState())
                ) {
                    Text(
                        text = stringResource(R.string.dl_storage_tooltip),
                        style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier.padding(horizontal = 18.dp)
                    )
                }
            },
            buttons = {
                TextButton(
                    onClick = {
                        showDlInfoDialog = false
                    }
                ) {
                    Text(text = stringResource(android.R.string.ok))
                }
            }
        )
    }

    if (showPathsDialog) {
        var tempScanPaths = remember { mutableStateListOf<Uri>() }
        LaunchedEffect(dlPathExtra) {
            tempScanPaths.addAll(uriListFromString(dlPathExtra))
        }

        ActionPromptDialog(
            titleBar = {
                Row(
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = stringResource(R.string.scan_paths_incl),
                        style = MaterialTheme.typography.titleLarge,
                    )
                }
            },
            onDismiss = {
                showPathsDialog = false
                tempScanPaths.clear()
            },
            onConfirm = {
                onDlPathExtraChange(stringFromUriList(tempScanPaths.toList()))
                coroutineScope.launch(dlCoroutine) {
                    delay(1000)
                    downloadUtil.cd()
                    downloadUtil.scanDownloads()
                }

                showPathsDialog = false
                tempScanPaths.clear()
            },
            onReset = {
                // reset to whitespace so not empty
                tempScanPaths.clear()
            },
            onCancel = {
                showPathsDialog = false
                tempScanPaths.clear()
            },
            isInputValid = uriListFromString(scanPaths).toList().none { scanPath ->
                // scan path cannot be contain any dl extras path
                tempScanPaths.toList().any { it.toString().contains(scanPath.toString()) }
            }
        ) {
            val dirPickerLauncher = rememberLauncherForActivityResult(
                ActivityResultContracts.OpenDocumentTree()
            ) { uri ->
                if (uri == null) return@rememberLauncherForActivityResult
                if (tempScanPaths.any { it.toString() == uri.toString() }) return@rememberLauncherForActivityResult
                val contentResolver = context.contentResolver
                val takeFlags: Int = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                contentResolver.takePersistableUriPermission(uri, takeFlags)
                tempScanPaths.add(uri)
            }

            // folders list
            Column(
                modifier = Modifier
                    .padding(vertical = 12.dp)
                    .border(
                        2.dp,
                        MaterialTheme.colorScheme.primary.copy(alpha = 0.5f),
                        RoundedCornerShape(ThumbnailCornerRadius)
                    )
            ) {
                tempScanPaths.forEach { tmpPath ->
                    val valid = uriListFromString(scanPaths).toList().none {
                        tmpPath.toString().contains(it.toString())
                    }
                    Row(
                        modifier = Modifier
                            .padding(horizontal = 8.dp)
                            .background(if (valid) Color.Transparent else MaterialTheme.colorScheme.errorContainer)
                            .clickable { }) {
                        Text(
                            text = absoluteFilePathFromUri(context, tmpPath) ?: tmpPath.toString(),
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier
                                .weight(1f)
                                .align(Alignment.CenterVertically)
                        )
                        IconButton(
                            onClick = {
                                tempScanPaths.remove(tmpPath)
                            },
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.Close,
                                contentDescription = null,
                            )
                        }
                    }
                }
            }

            // add folder button
            Column {
                Button(onClick = { dirPickerLauncher.launch(null) }) {
                    Text(stringResource(R.string.scan_paths_add_folder))
                }

                InfoLabel(
                    text = stringResource(R.string.scan_paths_tooltip),
                    modifier = Modifier.padding(top = 8.dp)
                )

                if (uriListFromString(scanPaths).toList().any { scanPath ->
                        // scan path cannot be contain any dl extras path
                        tempScanPaths.toList().any { it.toString().contains(scanPath.toString()) }
                    }) {
                    InfoLabel(
                        text = stringResource(R.string.scanner_rejected_dir),
                        isError = true,
                        modifier = Modifier.padding(top = 8.dp)
                    )
                }
            }
        }
    }

    if (showImportDialog) {
        DefaultDialog(
            onDismiss = { showImportDialog = false },
            content = {
                Text(
                    text = stringResource(R.string.dl_rescan_confirm),
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.padding(horizontal = 18.dp)
                )
            },
            buttons = {
                TextButton(
                    onClick = {
                        showImportDialog = false
                    }
                ) {
                    Text(text = stringResource(android.R.string.cancel))
                }

                TextButton(
                    onClick = {
                        showImportDialog = false
                        coroutineScope.launch(dlCoroutine) {
                            downloadUtil.scanDownloads()
                        }
                    }
                ) {
                    Text(text = stringResource(android.R.string.ok))
                }
            }
        )
    }

    if (showMigrationDialog) {
        DefaultDialog(
            onDismiss = { showMigrationDialog = false },
            content = {
                Text(
                    text = stringResource(
                        R.string.dl_migrate_confirm,
                        absoluteFilePathFromUri(context, downloadPath.toUri()) ?: downloadPath
                    ),
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.padding(horizontal = 18.dp)
                )
            },
            buttons = {
                TextButton(
                    onClick = {
                        showMigrationDialog = false
                    }
                ) {
                    Text(text = stringResource(android.R.string.cancel))
                }

                TextButton(
                    onClick = {
                        showMigrationDialog = false

                        coroutineScope.launch(dlCoroutine) {
                            downloadUtil.migrateDownloads()
                        }
                    }
                ) {
                    Text(text = stringResource(android.R.string.ok))
                }
            }
        )
    }

}

@Composable
fun ColumnScope.SongCacheFrag() {
    val coroutineScope = rememberCoroutineScope()
    val playerCache = LocalPlayerConnection.current?.service?.playerCache ?: return

    val (maxSongCacheSize, onMaxSongCacheSizeChange) = rememberPreference(key = MaxSongCacheSizeKey, defaultValue = 0)

    var playerCacheSize by remember {
        mutableLongStateOf(tryOrNull { playerCache.cacheSpace } ?: 0)
    }

    LaunchedEffect(playerCache) {
        while (isActive) {
            delay(2000)
            playerCacheSize = tryOrNull { playerCache.cacheSpace } ?: 0
        }
    }

    var showClearConfirmDialog by remember {
        mutableStateOf(false)
    }

    Spacer(modifier = Modifier.height(16.dp))
    if (maxSongCacheSize != 0) {
        if (maxSongCacheSize == -1) {
            Text(
                text = stringResource(R.string.size_used, formatFileSize(playerCacheSize)),
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp)
            )
        } else {
            LinearProgressIndicator(
                progress = { (playerCacheSize.toFloat() / (maxSongCacheSize * 1024 * 1024L)).coerceIn(0f, 1f) },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 6.dp)
            )

            Text(
                text = stringResource(
                    R.string.size_used,
                    "${formatFileSize(playerCacheSize)} / ${formatFileSize(maxSongCacheSize * 1024 * 1024L)}"
                ),
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp)
            )
        }
    }

    ListPreference(
        title = { Text(stringResource(R.string.song_cache_max_size)) },
        selectedValue = maxSongCacheSize,
        values = listOf(0, 128, 256, 512, 1024, 2048, 4096, 8192, -1),
        valueText = {
            when (it) {
                0 -> stringResource(androidx.compose.ui.R.string.state_off)
                -1 -> stringResource(R.string.unlimited)
                else -> formatFileSize(it * 1024 * 1024L)
            }
        },
        onValueSelected = onMaxSongCacheSizeChange
    )
    InfoLabel(stringResource(R.string.restart_to_apply_changes))

    PreferenceEntry(
        title = { Text(stringResource(R.string.clear_song_cache)) },
        onClick = {
            showClearConfirmDialog = true
        },
    )


    /**
     * ---------------------------
     * Dialogs
     * ---------------------------
     */
    if (showClearConfirmDialog) {
        DefaultDialog(
            onDismiss = { showClearConfirmDialog = false },
            content = {
                Text(
                    text = stringResource(R.string.clear_song_cache_confirm),
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.padding(horizontal = 18.dp)
                )
            },
            buttons = {
                TextButton(
                    onClick = { showClearConfirmDialog = false }
                ) {
                    Text(text = stringResource(android.R.string.cancel))
                }

                TextButton(
                    onClick = {
                        showClearConfirmDialog = false
                        coroutineScope.launch(Dispatchers.IO) {
                            playerCache.keys.forEach { key ->
                                playerCache.removeResource(key)
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

@OptIn(ExperimentalCoilApi::class)
@Composable
fun ColumnScope.ImageCacheFrag() {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val imageDiskCache = context.imageLoader.diskCache ?: return

    val (maxImageCacheSize, onMaxImageCacheSizeChange) = rememberPreference(
        key = MaxImageCacheSizeKey,
        defaultValue = 512
    )

    var imageCacheSize by remember {
        mutableLongStateOf(imageDiskCache.size)
    }

    LaunchedEffect(imageDiskCache) {
        while (isActive) {
            delay(500)
            imageCacheSize = imageDiskCache.size
        }
    }

    // clear caches when turning off
    LaunchedEffect(maxImageCacheSize) {
        if (maxImageCacheSize == 0) {
            coroutineScope.launch(Dispatchers.IO) {
                imageDiskCache.clear()
            }
        }
    }

    var showClearConfirmDialog by remember {
        mutableStateOf(false)
    }

    if (maxImageCacheSize > 0) {
        Spacer(modifier = Modifier.height(16.dp))
        LinearProgressIndicator(
            progress = { (imageCacheSize.toFloat() / imageDiskCache.maxSize).coerceIn(0f, 1f) },
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 6.dp)
        )

        Text(
            text = stringResource(
                R.string.size_used,
                "${formatFileSize(imageCacheSize)} / ${formatFileSize(imageDiskCache.maxSize)}"
            ),
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp)
        )
    }

    ListPreference(
        title = { Text(stringResource(R.string.image_cache_max_size)) },
        selectedValue = maxImageCacheSize,
        values = listOf(0, 128, 256, 512, 1024, 2048, 4096, 8192),
        valueText = {
            when (it) {
                0 -> stringResource(androidx.compose.ui.R.string.state_off)
                else -> formatFileSize(it * 1024 * 1024L)
            }
        },
        onValueSelected = onMaxImageCacheSizeChange
    )
    InfoLabel(stringResource(R.string.restart_to_apply_changes))

    PreferenceEntry(
        title = { Text(stringResource(R.string.clear_image_cache)) },
        onClick = {
            showClearConfirmDialog = true
        },
    )


    /**
     * ---------------------------
     * Dialogs
     * ---------------------------
     */
    if (showClearConfirmDialog) {
        DefaultDialog(
            onDismiss = { showClearConfirmDialog = false },
            content = {
                Text(
                    text = stringResource(R.string.clear_image_cache_confirm),
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.padding(horizontal = 18.dp)
                )
            },
            buttons = {
                TextButton(
                    onClick = { showClearConfirmDialog = false }
                ) {
                    Text(text = stringResource(android.R.string.cancel))
                }

                TextButton(
                    onClick = {
                        showClearConfirmDialog = false
                        coroutineScope.launch(Dispatchers.IO) {
                            imageDiskCache.clear()
                        }
                    }
                ) {
                    Text(text = stringResource(android.R.string.ok))
                }
            }
        )
    }
}
