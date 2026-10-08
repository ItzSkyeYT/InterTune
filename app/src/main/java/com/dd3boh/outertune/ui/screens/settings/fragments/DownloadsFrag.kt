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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Downloading
import androidx.compose.material.icons.rounded.FolderCopy
import androidx.compose.material.icons.rounded.Sync
import androidx.compose.material.icons.rounded.Wifi
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
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
import com.dd3boh.outertune.LocalDownloadUtil
import com.dd3boh.outertune.LocalPlayerConnection
import com.dd3boh.outertune.R
import com.dd3boh.outertune.constants.DownloadExtraPathKey
import com.dd3boh.outertune.constants.DownloadPathKey
import com.dd3boh.outertune.constants.DownloadOnWifiOnlyKey
import com.dd3boh.outertune.constants.ScanPathsKey
import com.dd3boh.outertune.constants.ThumbnailCornerRadius
import com.dd3boh.outertune.extensions.tryOrNull
import com.dd3boh.outertune.ui.component.PreferenceEntry
import com.dd3boh.outertune.ui.component.SettingsClickToReveal
import com.dd3boh.outertune.ui.component.SwitchPreference
import com.dd3boh.outertune.ui.component.button.IconButton
import com.dd3boh.outertune.ui.component.button.ResizableIconButton
import com.dd3boh.outertune.ui.dialog.ActionPromptDialog
import com.dd3boh.outertune.ui.dialog.DefaultDialog
import com.dd3boh.outertune.ui.dialog.InfoLabel
import com.dd3boh.outertune.utils.dlCoroutine
import com.dd3boh.outertune.utils.formatFileSize
import com.dd3boh.outertune.utils.rememberPreference
import com.dd3boh.outertune.utils.scanners.absoluteFilePathFromUri
import com.dd3boh.outertune.utils.scanners.FolderNesting
import com.dd3boh.outertune.utils.scanners.stringFromUriList
import com.dd3boh.outertune.utils.scanners.uriListFromString
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import androidx.compose.material.icons.rounded.Favorite
import com.dd3boh.outertune.constants.LikedAutoDownloadKey
import com.dd3boh.outertune.constants.LikedAutodownloadMode
import com.dd3boh.outertune.playback.DownloadUtil
import com.dd3boh.outertune.ui.component.EnumListPreference
import com.dd3boh.outertune.utils.rememberEnumPreference
import com.dd3boh.outertune.ui.screens.walkthrough.Tour
import com.dd3boh.outertune.ui.screens.walkthrough.tourTarget

/**
 * Whether [a] and [b] are the same folder, or one sits inside the other, checked both ways. A
 * scan folder can hold a candidate extra download folder, or the candidate can hold a scan folder
 * (adding "Music" when "Music/WhatsApp Audio" is a scan folder); either way the two are not
 * allowed together. Text `.contains()` used to stand in for this, and refused any folder whose
 * name only began the same, such as "MusicVideos" against "Music".
 */
private fun foldersOverlap(a: Uri, b: Uri): Boolean = FolderNesting.overlaps(a.toString(), b.toString())

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
        modifier = Modifier.tourTarget(Tour.SETTING_LIKED_AUTODOWNLOAD),
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
            // Failures are said out loud. A run with some failed ends short of its total, and
            // without the count that reads as the app having lost track of them.
            is DownloadUtil.LikedDownloadState.Running ->
                if (st.failed > 0) {
                    stringResource(R.string.liked_autodownload_running_failed, st.done, st.total, st.failed)
                } else {
                    stringResource(R.string.liked_autodownload_running, st.done, st.total)
                }

            is DownloadUtil.LikedDownloadState.Finished -> when {
                st.stoppedEarly && st.failed > 0 ->
                    stringResource(R.string.liked_autodownload_stopped_failed, st.done, st.failed)
                st.stoppedEarly -> stringResource(R.string.liked_autodownload_stopped, st.done)
                st.failed > 0 ->
                    stringResource(R.string.liked_autodownload_finished_failed, st.done, st.total, st.failed)
                else -> stringResource(R.string.liked_autodownload_finished, st.done)
            }

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
        modifier = Modifier.tourTarget(Tour.SETTING_DOWNLOAD_FOLDER),
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
                // download path cannot a scan path, or a subdir of a scan path. The text
                // comparison this replaced only caught the same folder twice.
                FolderNesting.isSameOrInside(tempFilePath.toString(), it.toString())
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
                FolderNesting.isSameOrInside(tempFilePath.toString(), it.toString())
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
                        // Through media3, so its index, the database and the map of downloads
                        // all hear about it. Deleting the cache's files directly left every song
                        // marked as downloaded, pinned offline and skipped by the Download buttons,
                        // with nothing left to play. Songs in a download folder are not touched.
                        downloadUtil.removeAllInternalDownloads()
                        coroutineScope.launch(Dispatchers.IO) {
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
        // Only a folder picked in this run of the dialog is checked against the overlap rule
        // below; a path already saved is kept as it is. Otherwise an install that already had a
        // scan folder inside one of its extra download folders (accepted by older versions) would
        // open this dialog with OK disabled until that folder was removed, with nothing on screen
        // explaining why.
        val sessionAddedPaths = remember { mutableStateListOf<Uri>() }
        LaunchedEffect(dlPathExtra) {
            sessionAddedPaths.clear()
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
            isInputValid = tempScanPaths.toList().all { path ->
                // an extra folder picked in this run cannot overlap a scan folder, either way round
                path !in sessionAddedPaths ||
                    uriListFromString(scanPaths).toList().none { scanPath -> foldersOverlap(path, scanPath) }
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
                sessionAddedPaths.add(uri)
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
                    val overlaps = uriListFromString(scanPaths).toList().any { foldersOverlap(tmpPath, it) }
                    // Only a row picked in this run of the dialog can block OK; a saved row that
                    // overlaps is shown, not enforced.
                    val blocking = overlaps && tmpPath in sessionAddedPaths
                    val rowColor = when {
                        blocking -> MaterialTheme.colorScheme.errorContainer
                        overlaps -> MaterialTheme.colorScheme.surfaceVariant
                        else -> Color.Transparent
                    }
                    val textColor =
                        if (blocking) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant
                    Row(
                        modifier = Modifier
                            .padding(horizontal = 8.dp)
                            .background(rowColor)
                            .clickable { }) {
                        Column(
                            modifier = Modifier
                                .weight(1f)
                                .align(Alignment.CenterVertically)
                        ) {
                            Text(
                                text = absoluteFilePathFromUri(context, tmpPath) ?: tmpPath.toString(),
                                style = MaterialTheme.typography.bodySmall,
                            )
                            if (overlaps) {
                                Text(
                                    text = stringResource(
                                        if (blocking) R.string.scanner_rejected_dir else R.string.dl_extra_path_overlap_kept
                                    ),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = textColor,
                                )
                            }
                        }
                        IconButton(
                            onClick = {
                                tempScanPaths.remove(tmpPath)
                                sessionAddedPaths.remove(tmpPath)
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
                        // scan path cannot overlap any dl extras path added this run, either way
                        // round; a saved one is not a blocking error, so not counted here.
                        tempScanPaths.toList().any { it in sessionAddedPaths && foldersOverlap(it, scanPath) }
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
