/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.ui.screens.mix

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.dd3boh.outertune.R
import com.dd3boh.outertune.mix.pill.PlainReason

/**
 * The editor's warning that this change will not play as made right now, with the page that changes
 * the setting behind it when there is one (his rule of 2 Nov 2026: say why, and offer the fix). A
 * saved change is kept either way; it plays once the setting changes.
 */
@Composable
internal fun GuardBand(text: String, route: String?, onOpen: (String) -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.errorContainer)
            .padding(start = 16.dp, end = 4.dp),
    ) {
        Text(
            text,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onErrorContainer,
            modifier = Modifier.weight(1f).padding(vertical = 7.dp),
        )
        if (route != null) {
            TextButton(onClick = { onOpen(route) }) {
                Text(stringResource(R.string.settings), color = MaterialTheme.colorScheme.onErrorContainer)
            }
        }
    }
}

/** Where a guard is fixed: the setting behind a blocking reason, Skip silence's page for the editor's own Skip silence guard. */
internal fun guardRoute(skipSilence: Boolean, blocker: PlainReason?): String? = when {
    skipSilence -> MixWords.blockedSetting(PlainReason.SKIP_SILENCE)
    blocker != null -> MixWords.blockedSetting(blocker)
    else -> null
}
