/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.ui.component

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CloudOff
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.dd3boh.outertune.LocalNetworkConnected
import com.dd3boh.outertune.R

/**
 * In place of a page's loading shimmer once its request has failed, which otherwise stays up for
 * as long as the page is open.
 */
@Composable
fun LoadFailed(
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val connected = LocalNetworkConnected.current
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 24.dp)
    ) {
        Icon(
            imageVector = Icons.Rounded.CloudOff,
            contentDescription = null,
            modifier = Modifier.size(64.dp)
        )
        Spacer(Modifier.height(12.dp))
        Text(
            text = stringResource(if (connected) R.string.error_unknown else R.string.error_no_internet),
            style = MaterialTheme.typography.bodyLarge
        )
        TextButton(onClick = onRetry) {
            Text(stringResource(R.string.retry))
        }
    }
}
