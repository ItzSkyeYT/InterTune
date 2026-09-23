/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.ui.component

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.os.Build
import android.widget.Toast
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import com.dd3boh.outertune.R
import com.dd3boh.outertune.constants.Links
import com.dd3boh.outertune.utils.CrashLog

/** GitHub rejects very long issue URLs, so the body carries the top of the trace, which is the part that matters. */
private const val ISSUE_BODY_LIMIT = 6000

/**
 * Offers the crash CrashLog kept, once, on the launch after it. Every button forgets it, so the
 * dialog never comes back for the same crash. Nothing is sent unless the person chooses to.
 */
@Composable
fun CrashReportDialog() {
    val context = LocalContext.current
    var report by remember { mutableStateOf(CrashLog.read(context)) }
    val text = report ?: return
    val forget = {
        CrashLog.clear(context)
        report = null
    }

    AlertDialog(
        onDismissRequest = forget,
        title = { Text(stringResource(R.string.crash_report_title)) },
        text = {
            Column {
                Text(stringResource(R.string.crash_report_text))
                Spacer(Modifier.height(12.dp))
                Text(
                    text = text,
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    modifier = Modifier
                        .heightIn(max = 200.dp)
                        .verticalScroll(rememberScrollState())
                        .horizontalScroll(rememberScrollState()),
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    // The exception line, the first one after the device header, makes the title.
                    val cause = text.substringAfter("\n\n").lineSequence().firstOrNull().orEmpty().take(120)
                    val uri = "${Links.ISSUES}/new".toUri().buildUpon()
                        .appendQueryParameter("title", "Crash: $cause")
                        .appendQueryParameter("body", "```\n${text.take(ISSUE_BODY_LIMIT)}\n```")
                        .build()
                    runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                    forget()
                }
            ) { Text(stringResource(R.string.crash_report_github)) }
        },
        dismissButton = {
            Row {
                TextButton(
                    onClick = {
                        context.getSystemService(ClipboardManager::class.java)
                            ?.setPrimaryClip(ClipData.newPlainText("InterTune crash", text))
                        // Android 13 and later show their own confirmation for every copy.
                        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
                            Toast.makeText(context, R.string.crash_report_copied, Toast.LENGTH_SHORT).show()
                        }
                        forget()
                    }
                ) { Text(stringResource(R.string.crash_report_copy)) }
                TextButton(onClick = forget) { Text(stringResource(R.string.crash_report_dismiss)) }
            }
        },
    )
}
