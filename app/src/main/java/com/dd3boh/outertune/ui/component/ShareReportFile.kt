/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.ui.component

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.dd3boh.outertune.R
import com.dd3boh.outertune.utils.ReportFile
import kotlinx.coroutines.launch

/**
 * "Share as a file" under a report's text (Report a problem, the crash page): the report as
 * InterTune-report-<day>-<time>.txt to any app, Discord included, which takes a file without
 * Nitro. The dialog's own buttons stay as they are.
 */
@Composable
internal fun ShareReportFile(text: String) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    TextButton(onClick = { scope.launch { ReportFile.share(context, text) } }) {
        Icon(Icons.Rounded.Share, contentDescription = null, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(8.dp))
        Text(stringResource(R.string.problem_report_share_file))
    }
}
