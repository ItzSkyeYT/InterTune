/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.ui.dialog

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AudioFile
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.Speed
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.dd3boh.outertune.LocalDownloadUtil
import com.dd3boh.outertune.R
import com.dd3boh.outertune.constants.DownloadBitrate
import com.dd3boh.outertune.constants.DownloadBitrateKey
import com.dd3boh.outertune.constants.DownloadFormat
import com.dd3boh.outertune.constants.DownloadFormatKey
import com.dd3boh.outertune.constants.DownloadPathKey
import com.dd3boh.outertune.playback.DownloadUtil.ExportState
import com.dd3boh.outertune.playback.downloadManager.DownloadFormats
import com.dd3boh.outertune.playback.downloadManager.ExportItem
import com.dd3boh.outertune.playback.downloadManager.ExportResult
import com.dd3boh.outertune.playback.downloadManager.ExportSummary
import com.dd3boh.outertune.playback.downloadManager.SkipReason
import com.dd3boh.outertune.playback.downloadManager.exportLine
import com.dd3boh.outertune.ui.component.EnumListPreference
import com.dd3boh.outertune.ui.component.PreferenceEntry
import com.dd3boh.outertune.ui.utils.LocalLandscape
import com.dd3boh.outertune.utils.dataStore
import com.dd3boh.outertune.utils.get
import com.dd3boh.outertune.utils.rememberEnumPreference
import com.dd3boh.outertune.utils.scanners.absoluteFilePathFromUri
import com.dd3boh.outertune.utils.scanners.uriListFromString

/**
 * Export to folder (Unreleased.DOWNLOAD_FORMATS), a sheet that comes up from the bottom as the sleep
 * timer's does: the File format and the Bitrate as Settings has them, the setting's own choice
 * first, the folder (the download folder, or one picked through the system), and Export. Then the
 * progress of the export, and at its end a line of what was exported and what was not, and why.
 * Songs that are not on the phone are named and left out; nothing is fetched.
 *
 * The export runs in the app (DownloadUtil.startExport), not in the sheet: leaving the sheet does not
 * stop it, and a result nobody is looking at is told in a toast. [onDismiss] closes the sheet.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ExportSheet(items: List<ExportItem>, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val downloadUtil = LocalDownloadUtil.current
    val landscape = LocalLandscape.current
    val state by downloadUtil.exportState.collectAsState()

    // A result left by an earlier export is not this one's: the sheet starts from the choices.
    DisposableEffect(Unit) {
        downloadUtil.exportWatchers.incrementAndGet()
        onDispose { downloadUtil.exportWatchers.decrementAndGet() }
    }
    LaunchedEffect(Unit) { downloadUtil.acknowledgeExport() }

    // The setting's choices to start from, and the sheet's own from then on: the setting is not written.
    val (savedFormat, _) = rememberEnumPreference(DownloadFormatKey, DownloadFormat.ORIGINAL)
    val (savedBitrate, _) = rememberEnumPreference(DownloadBitrateKey, DownloadBitrate.AUTO)
    var format by remember { mutableStateOf(savedFormat) }
    var bitrate by remember { mutableStateOf(savedBitrate) }
    val downloadFolder = remember { uriListFromString(context.dataStore.get(DownloadPathKey, "")).firstOrNull() }
    var target by remember { mutableStateOf<Uri?>(downloadFolder) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            // Kept, so that an export still running when the screen is left can write to it.
            runCatching {
                context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            }
            target = uri
        }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        // On its side a sheet is no wider than a menu is there, and the sides of the window are left to the camera.
        sheetMaxWidth = landscape.panelWidth(BottomSheetDefaults.SheetMaxWidth),
        contentWindowInsets = {
            if (landscape.active) WindowInsets.safeDrawing.only(WindowInsetsSides.Vertical) else WindowInsets.safeDrawing
        },
    ) {
        // What does not fit a short window scrolls.
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(bottom = 16.dp)
        ) {
            when (val now = state) {
                is ExportState.Running -> Running(now, onStop = { downloadUtil.cancelExport() })
                is ExportState.Finished -> Finished(now.result, onDone = {
                    downloadUtil.acknowledgeExport()
                    onDismiss()
                })
                ExportState.Idle -> {
                    Text(
                        text = stringResource(R.string.export_to_folder),
                        style = MaterialTheme.typography.titleLarge,
                        modifier = Modifier
                            .padding(horizontal = 24.dp)
                            .semantics { heading() }
                    )
                    Text(
                        text = stringResource(R.string.export_note),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = 24.dp, end = 24.dp, top = 4.dp, bottom = 8.dp)
                    )
                    EnumListPreference(
                        title = { Text(stringResource(R.string.download_format)) },
                        icon = { Icon(Icons.Rounded.AudioFile, null) },
                        selectedValue = format,
                        onValueSelected = { format = it },
                        valueText = {
                            when (it) {
                                DownloadFormat.ORIGINAL -> stringResource(R.string.download_format_original)
                                DownloadFormat.MP3 -> "MP3"
                                DownloadFormat.M4A -> "M4A (AAC)"
                                DownloadFormat.FLAC -> "FLAC"
                                DownloadFormat.WAV -> "WAV"
                            }
                        }
                    )
                    if (DownloadFormats.bitrateRowShown(format)) {
                        EnumListPreference(
                            title = { Text(stringResource(R.string.download_bitrate)) },
                            icon = { Icon(Icons.Rounded.Speed, null) },
                            selectedValue = bitrate,
                            onValueSelected = { bitrate = it },
                            valueText = { choice ->
                                choice.kbps?.let { stringResource(R.string.download_bitrate_kbps, it) } ?: stringResource(R.string.download_bitrate_auto)
                            }
                        )
                    }
                    PreferenceEntry(
                        title = { Text(stringResource(R.string.export_folder)) },
                        description = target?.let { absoluteFilePathFromUri(context, it) ?: it.toString() } ?: stringResource(R.string.export_folder_none),
                        icon = { Icon(Icons.Rounded.Folder, null) },
                        onClick = { picker.launch(null) },
                    )
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 24.dp, vertical = 16.dp)
                    ) {
                        TextButton(onClick = onDismiss) {
                            Text(stringResource(android.R.string.cancel))
                        }
                        Button(
                            enabled = target != null,
                            onClick = { target?.let { downloadUtil.startExport(items, format, bitrate, it) } },
                        ) {
                            Text(stringResource(R.string.export_action))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun Running(state: ExportState.Running, onStop: () -> Unit) {
    Text(
        text = stringResource(R.string.export_progress, (state.done + 1).coerceAtMost(state.total), state.total),
        style = MaterialTheme.typography.titleLarge,
        modifier = Modifier
            .padding(horizontal = 24.dp)
            .semantics { heading() }
    )
    Text(
        text = state.title.orEmpty(),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = 1,
        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
        modifier = Modifier.padding(start = 24.dp, end = 24.dp, top = 4.dp)
    )
    LinearProgressIndicator(
        progress = { state.done.toFloat() / state.total.coerceAtLeast(1) },
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp, vertical = 16.dp)
    )
    Row(
        horizontalArrangement = Arrangement.End,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp)
    ) {
        TextButton(onClick = onStop) {
            Text(stringResource(android.R.string.cancel))
        }
    }
}

@Composable
private fun Finished(result: ExportResult, onDone: () -> Unit) {
    val context = LocalContext.current
    Text(
        text = stringResource(if (result.stopped) R.string.export_stopped_title else R.string.export_done_title),
        style = MaterialTheme.typography.titleLarge,
        modifier = Modifier
            .padding(horizontal = 24.dp)
            .semantics { heading() }
    )
    Text(
        text = exportLine(context, result),
        style = MaterialTheme.typography.bodyLarge,
        modifier = Modifier.padding(start = 24.dp, end = 24.dp, top = 8.dp)
    )
    // The songs left out, by name, a few of them for each reason.
    for (reason in SkipReason.entries) {
        val (names, more) = ExportSummary.named(result, reason)
        if (names.isEmpty()) continue
        val joined = names.joinToString(", ")
        Text(
            text = if (more > 0) stringResource(R.string.export_names_more, joined, more) else joined,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 24.dp, end = 24.dp, top = 4.dp)
        )
    }
    Row(
        horizontalArrangement = Arrangement.End,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp, vertical = 16.dp)
    ) {
        Button(onClick = onDone) {
            Text(stringResource(android.R.string.ok))
        }
    }
}
