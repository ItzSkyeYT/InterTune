/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.ui.screens.settings.fragments

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.dd3boh.outertune.R
import com.dd3boh.outertune.playback.downloadManager.DownloadFolderState
import com.dd3boh.outertune.playback.downloadManager.DownloadsSimpleLook
import com.dd3boh.outertune.ui.component.PreferenceEntry
import com.dd3boh.outertune.ui.dialog.InfoLabel
import com.dd3boh.outertune.ui.dialog.ListDialog
import com.dd3boh.outertune.ui.screens.settings.settingRow
import com.dd3boh.outertune.utils.joinByBullet

/*
 * The pieces of the simpler ways to choose where downloads go (DownloadsSimpleLook, looks B and C),
 * drawn by DownloadsFrag in the debug app only while the choice between them is open. Each is made
 * of what the page already has: a settings row, the list of choices a setting opens, the line and
 * the colour a folder that cannot be opened has today.
 */

/**
 * One row for where downloads go: where they are now and how many, the move while songs are still
 * inside the app with a folder chosen, and the way out when the folder cannot be opened.
 *
 * [folder] is the saved folder's path as the page writes it, null when none is saved. [downloads]
 * counts every download and [inside] the ones still inside the app, which take [insideSize]; below
 * zero while it is not counted yet.
 */
@Composable
internal fun WhereDownloadsGoRow(
    folder: String?,
    state: DownloadFolderState,
    downloads: Int,
    inside: Int,
    insideSize: String,
    moving: Boolean,
    onClick: () -> Unit,
    onMove: () -> Unit,
) {
    val cannotOpen = folder != null && state == DownloadFolderState.CANNOT_OPEN
    val offersMove = folder != null && state == DownloadFolderState.OPEN && inside > 0
    val counted = inside >= 0
    val name = stringResource(R.string.dl_simple_where_title)
    PreferenceEntry(
        // The mark of today's folder row, whose place it takes, so Settings' search finds it as it finds that one. The
        // tour's own mark stays on today's row, where it is written once: it moves here with the look that is chosen.
        modifier = Modifier.settingRow(R.string.dl_main_path_title),
        title = { Text(name) },
        icon = { Icon(Icons.Rounded.Folder, null) },
        // "Inside the app • 5 songs • 23 MB", or "Music/InterTune • 5 songs" once a folder holds them.
        description = when {
            folder == null -> joinByBullet(
                stringResource(R.string.dl_simple_where_inside),
                if (counted) pluralStringResource(R.plurals.n_song, inside, inside) else null,
                insideSize.takeIf { counted && inside > 0 },
            )

            cannotOpen -> DownloadsSimpleLook.folderName(folder)

            else -> DownloadsSimpleLook.inFolder(downloads, inside).let { there ->
                joinByBullet(
                    DownloadsSimpleLook.folderName(folder),
                    if (counted) pluralStringResource(R.plurals.n_song, there, there) else null,
                )
            }
        },
        content = if (cannotOpen || offersMove) {
            {
                Column {
                    Text(
                        text = if (cannotOpen) stringResource(R.string.dl_folder_cannot_open)
                        else pluralStringResource(R.plurals.dl_simple_still_inside, inside, inside),
                        style = MaterialTheme.typography.bodySmall,
                        color = if (cannotOpen) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 2.dp)
                    )
                    // Under the line it answers, its words in line with the row's: the button keeps its own padding to be easy to hit.
                    TextButton(
                        onClick = if (cannotOpen) onClick else onMove,
                        enabled = cannotOpen || !moving,
                        modifier = Modifier.offset(x = (-12).dp)
                    ) {
                        Text(stringResource(if (cannotOpen) R.string.dl_simple_choose_again else R.string.dl_move_here_move))
                    }
                }
            }
        } else null,
        onClick = onClick,
    )
}

/**
 * Where downloads go, asked as the page's other settings ask: two choices in a list, each with a
 * line on what it means. Inside the app is chosen at once; In a folder opens the phone's folder
 * picker ([onFolder]). [refused]: the folder just picked is part of a scan folder, said under the
 * choices as the folder dialog says it.
 */
@Composable
internal fun WhereDownloadsGoDialog(
    inFolder: Boolean,
    refused: Boolean,
    onInside: () -> Unit,
    onFolder: () -> Unit,
    onDismiss: () -> Unit,
) {
    ListDialog(onDismiss = onDismiss) {
        item {
            WhereChoice(
                selected = !inFolder,
                name = stringResource(R.string.dl_simple_where_inside),
                line = stringResource(R.string.dl_simple_inside_line),
                onClick = onInside,
            )
        }
        item {
            WhereChoice(
                selected = inFolder,
                name = stringResource(R.string.dl_simple_in_folder),
                line = stringResource(R.string.dl_simple_in_folder_line),
                onClick = onFolder,
            )
        }
        if (refused) {
            item {
                InfoLabel(
                    text = stringResource(R.string.dl_main_path_part_of_scan),
                    isError = true,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 8.dp)
                )
            }
        }
    }
}

/** A choice as ListPreference draws one that has a line under it: the button by the name, at the top. */
@Composable
private fun WhereChoice(selected: Boolean, name: String, line: String, onClick: () -> Unit) {
    Row(
        verticalAlignment = Alignment.Top,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp)
    ) {
        RadioButton(selected = selected, onClick = null)
        Column(modifier = Modifier.padding(start = 16.dp)) {
            Text(text = name, style = MaterialTheme.typography.bodyLarge)
            Text(
                text = line,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * The phone's folder picker, opened by what it returns: [onPicked] gets the folder once its
 * permission is taken, for good and to write, as the folder dialog's picker takes it. Made by the
 * page, not by the choice that opens it, so it is still there when the picker comes back.
 */
@Composable
internal fun rememberFolderPicker(onPicked: (Uri) -> Unit): () -> Unit {
    val context = LocalContext.current
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { picked ->
        val uri = picked?.takeIf { it.path != null }
        if (uri != null) {
            context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            onPicked(uri)
        }
    }
    return { launcher.launch(null) }
}
