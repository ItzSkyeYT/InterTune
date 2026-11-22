/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.ui.dialog

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.dd3boh.outertune.R

/**
 * A guest behind the room on a line that can't keep up (TogetherSession's DataWait): said in one plain line, in place of
 * "Catching up, N ms apart" for good.
 */
@Composable
internal fun RoomTooSlow() {
    Text(
        stringResource(R.string.together_too_slow),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}
