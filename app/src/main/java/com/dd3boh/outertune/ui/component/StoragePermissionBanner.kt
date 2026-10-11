/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.ui.component

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.res.stringResource
import com.dd3boh.outertune.R

/**
 * The banner over Folders and the Library lists while local media is on and the app may not read
 * the music on the device. What a tap does is the screen's own: each asks for the permission.
 *
 * Square, the way it is drawn. A text button is a pill and cuts its label to that shape: once
 * the sentence ran to four lines, in French at a large font size, the pill's round ends took
 * the first letters of its first and last lines.
 */
@Composable
fun StoragePermissionBanner(onClick: () -> Unit) {
    TextButton(
        onClick = onClick,
        shape = RectangleShape,
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.error)
    ) {
        Text(
            text = stringResource(R.string.missing_media_permission_warning),
            color = Color.White,
            style = MaterialTheme.typography.bodyLarge
        )
    }
}
