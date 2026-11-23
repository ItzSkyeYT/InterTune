/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.ui.component

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.FileCopy
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.dd3boh.outertune.LocalDownloadUtil
import com.dd3boh.outertune.R
import com.dd3boh.outertune.constants.Unreleased
import com.dd3boh.outertune.playback.DownloadCopiesCleanup.State
import com.dd3boh.outertune.utils.formatFileSize

/**
 * The extra copies of downloads a bug left in the download folder (DownloadCopiesCleanup), at the top
 * of Library > Downloaded songs: why they are there and the room they take, with Remove and Not now,
 * then a progress line while they go. Nothing is deleted before Remove. The look of the app's other
 * notes that say why and give the fix ([NoteCard]). Unreleased.DOWNLOAD_COPIES_CLEANUP.
 */
@Composable
fun DownloadCopiesNote(modifier: Modifier = Modifier) {
    if (!Unreleased.DOWNLOAD_COPIES_CLEANUP) return
    val downloadUtil = LocalDownloadUtil.current
    val context = LocalContext.current
    val state by downloadUtil.copies.state.collectAsState()
    when (val now = state) {
        is State.Found -> NoteCard(
            icon = Icons.Rounded.FileCopy,
            title = stringResource(R.string.dl_copies_title),
            body = pluralStringResource(R.plurals.dl_copies_body, now.files, now.files, formatFileSize(context, now.bytes)),
            modifier = modifier,
        ) {
            NoteButton(stringResource(R.string.dl_copies_not_now), downloadUtil.copies::notNow)
            // In the background, whatever screen comes next: DownloadUtil.removeCopies.
            NoteButton(stringResource(R.string.dl_copies_remove), downloadUtil::removeCopies)
        }

        is State.Removing -> NoteCard(
            icon = Icons.Rounded.FileCopy,
            title = stringResource(R.string.dl_copies_title),
            body = stringResource(R.string.dl_copies_removing, now.done, now.total),
            modifier = modifier,
        ) {
            LinearProgressIndicator(
                progress = { if (now.total > 0) now.done.toFloat() / now.total else 0f },
                color = MaterialTheme.colorScheme.tertiary,
                trackColor = MaterialTheme.colorScheme.onTertiaryContainer.copy(alpha = 0.16f),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 12.dp, bottom = 14.dp, end = 6.dp),
            )
        }

        State.None -> Unit
    }
}
