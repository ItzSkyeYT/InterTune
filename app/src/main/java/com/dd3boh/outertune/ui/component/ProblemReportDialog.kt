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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import com.dd3boh.outertune.playback.StopReasons
import com.dd3boh.outertune.utils.ProblemLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** GitHub turns down a very long address, and what led up to the fault is what the form is for. */
private const val FORM_LOG_LIMIT = 5000

/**
 * The last thing that went wrong, as the app noted it, to copy or to take to the bug form.
 *
 * Opened from About. The log is read when this opens and shown before anything is done with it:
 * it names the songs that were playing, and the person decides whether it leaves the phone. See
 * [ProblemLog].
 */
@Composable
fun ProblemReportDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    var excerpt by remember { mutableStateOf<ProblemLog.Excerpt?>(null) }
    var stops by remember { mutableStateOf(emptyList<String>()) }
    LaunchedEffect(Unit) {
        val (read, kept) = withContext(Dispatchers.IO) { ProblemLog.excerpt(ProblemLog.read()) to StopReasons.read(context) }
        stops = kept
        excerpt = read
    }
    val found = excerpt
    val text = remember(found, stops) { found?.let { ProblemLog.report(it, stops = stops) } }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.problem_report_title)) },
        text = {
            // One scroll for the words and the log together, so the buttons stay on a short
            // screen. The log's long lines scroll sideways by themselves.
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text(
                    stringResource(
                        when {
                            found == null -> R.string.problem_report_reading
                            found.lines.isEmpty() -> R.string.problem_report_empty
                            found.faultAt == null -> R.string.problem_report_none
                            else -> R.string.problem_report_found
                        }
                    )
                )
                if (text != null && found?.lines?.isNotEmpty() == true) {
                    Spacer(Modifier.height(12.dp))
                    Text(
                        text = text,
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                        modifier = Modifier.horizontalScroll(rememberScrollState()),
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = text != null,
                onClick = {
                    // The form's own boxes, by their ids in .github/ISSUE_TEMPLATE/bug_report.yml.
                    val uri = "${Links.ISSUES}/new".toUri().buildUpon()
                        .appendQueryParameter("template", "bug_report.yml")
                        .appendQueryParameter("build", ProblemLog.build())
                        .appendQueryParameter("device", ProblemLog.device())
                        .appendQueryParameter("logs", ProblemLog.fitted(text.orEmpty(), FORM_LOG_LIMIT))
                        .build()
                    runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                    onDismiss()
                }
            ) { Text(stringResource(R.string.problem_report_form)) }
        },
        dismissButton = {
            Row {
                TextButton(
                    enabled = text != null,
                    onClick = {
                        context.getSystemService(ClipboardManager::class.java)
                            ?.setPrimaryClip(ClipData.newPlainText("InterTune log", text.orEmpty()))
                        // Android 13 and later show their own confirmation for every copy.
                        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
                            Toast.makeText(context, R.string.problem_report_copied, Toast.LENGTH_SHORT).show()
                        }
                    }
                ) { Text(stringResource(R.string.problem_report_copy)) }
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.problem_report_close)) }
            }
        },
    )
}
