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
import android.util.Log
import android.widget.Toast
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Update
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import com.dd3boh.outertune.utils.BuiltInKeys
import com.dd3boh.outertune.R
import com.dd3boh.outertune.BuildConfig
import com.dd3boh.outertune.constants.Links
import com.dd3boh.outertune.constants.DismissedUpdateCodeKey
import com.dd3boh.outertune.constants.LastVersionCodeKey
import com.dd3boh.outertune.constants.LastVersionKey
import com.dd3boh.outertune.constants.ReportWayKey
import com.dd3boh.outertune.constants.Unreleased
import com.dd3boh.outertune.ui.screens.settings.SETTINGS_INDEX
import com.dd3boh.outertune.ui.screens.settings.SettingJump
import com.dd3boh.outertune.ui.screens.settings.jump
import com.dd3boh.outertune.utils.CrashLog
import com.dd3boh.outertune.utils.InstallSource
import com.dd3boh.outertune.utils.ProblemLog
import com.dd3boh.outertune.utils.ReportRelay
import com.dd3boh.outertune.utils.ReportTrail
import com.dd3boh.outertune.utils.ReportWay
import com.dd3boh.outertune.utils.fdroidPageUrl
import com.dd3boh.outertune.utils.installSource
import com.dd3boh.outertune.utils.rememberPreference
import com.dd3boh.outertune.utils.rememberEnumPreference
import kotlinx.coroutines.launch
import com.dd3boh.outertune.utils.Toaster

/**
 * The trace's characters looked at for the issue's link, at most. The link itself is cut to FORM_LINK_LIMIT
 * once escaped (GitHub's sign-in page drops a longer one), keeping the top of the trace, the part that matters.
 */
private const val ISSUE_BODY_LIMIT = 6000

/**
 * Offers the crash CrashLog kept, once, on the launch after it. Every button forgets it, so the
 * dialog never comes back for the same crash. Nothing is sent unless the person chooses to.
 *
 * With Unreleased.REPORT_RELAY, Send to InterTune sends it the way Report a problem does (ReportRelay):
 * no GitHub account needed, and the issue's link comes back. A fork keeps Copy only (FORKS.md).
 */
@Composable
fun CrashReportDialog() {
    val context = LocalContext.current
    var report by remember { mutableStateOf(CrashLog.read(context)) }
    val relay = Unreleased.REPORT_RELAY && BuiltInKeys.official
    // Anonymous, or with the person's GitHub account, as in Report a problem (ReportWayKey).
    var way by rememberEnumPreference(ReportWayKey, ReportWay.ANONYMOUS)
    val withGitHub = relay && way == ReportWay.GITHUB
    // A newer version the update check already found (it is opt in, and nothing is asked from here):
    // the crash may be fixed there, so the page says so first.
    val foundCode by rememberPreference(LastVersionCodeKey, -1)
    val foundName by rememberPreference(LastVersionKey, "")
    val skippedCode by rememberPreference(DismissedUpdateCodeKey, -1)
    val newer = crashNewerVersion(foundCode, foundName, BuildConfig.VERSION_CODE, skippedCode)
    val fromFdroid = remember { context.installSource() == InstallSource.F_DROID }
    // What happened and how to reach them, kept until a report is sent: see ReportDraft.
    val words = rememberReportWords()
    var sending by remember { mutableStateOf(false) }
    var outcome by remember { mutableStateOf<ReportRelay.Outcome?>(null) }
    val scope = rememberCoroutineScope()
    val forget = {
        CrashLog.clear(context)
        report = null
    }
    when (val done = outcome) {
        is ReportRelay.Outcome.Sent -> return SentDialog(done) { outcome = null; forget() }
        ReportRelay.Outcome.Held -> return HeldDialog { outcome = null; forget() }
        else -> Unit
    }
    val text = report ?: return
    // The exception line, the first one after the device header, makes the title.
    val cause = text.substringAfter("\n\n").lineSequence().firstOrNull().orEmpty().take(120)
    val openGitHub = {
        val base = "${Links.ISSUES}/new".toUri().buildUpon()
            .appendQueryParameter("title", "Crash: $cause")
            .build()
        // What they typed, then the trace from its top, as much as the link holds for someone signed out.
        val said = if (relay && words.what.isNotBlank()) words.what.trim().take(FORM_WHAT_LIMIT) + "\n\n" else ""
        val room = FORM_LINK_LIMIT - base.toString().length - "&body=".length - ProblemLog.linkLength("$said```\n\n```")
        val uri = base.buildUpon()
            .appendQueryParameter("body", "$said```\n${ProblemLog.startForLink(text.take(ISSUE_BODY_LIMIT), room)}\n```")
            .build()
        runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
        forget()
    }

    AlertDialog(
        onDismissRequest = forget,
        title = { Text(stringResource(R.string.crash_report_title)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text(stringResource(R.string.crash_report_text))
                if (newer != null) {
                    NoteCard(
                        icon = Icons.Rounded.Update,
                        title = stringResource(R.string.crash_newer_title, newer),
                        body = stringResource(R.string.crash_newer_body),
                        margin = 0.dp,
                    ) {
                        NoteButton(stringResource(if (fromFdroid) R.string.update_prompt_fdroid else R.string.crash_newer_update)) {
                            if (fromFdroid) {
                                runCatching {
                                    context.startActivity(
                                        Intent(Intent.ACTION_VIEW, fdroidPageUrl(context.packageName).toUri())
                                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                    )
                                }
                            } else {
                                SettingJump.go(SETTINGS_INDEX.first { it.title == R.string.check_for_update }.jump())
                            }
                            // Update first, report if it comes back: this crash is not offered again.
                            forget()
                        }
                    }
                }
                // The steps and settings are in the text below, so they are shown before anything is sent.
                if (ReportTrail.HEADER_START in text) {
                    Spacer(Modifier.height(8.dp))
                    Text(stringResource(R.string.problem_report_extras))
                }
                if (relay && words.back) {
                    Spacer(Modifier.height(12.dp))
                    ReportWayChoice(way, onPick = { way = it })
                    Spacer(Modifier.height(12.dp))
                    OutlinedTextField(
                        value = words.what,
                        onValueChange = words::typeWhat,
                        label = { Text(stringResource(R.string.problem_report_what)) },
                        minLines = 2,
                        maxLines = 5,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    // GitHub knows who files there: the way to reach someone is for anonymous reports only.
                    if (!withGitHub) {
                        Spacer(Modifier.height(8.dp))
                        val failed = outcome is ReportRelay.Outcome.Failed
                        OutlinedTextField(
                            value = words.contact,
                            onValueChange = words::typeContact,
                            label = { Text(stringResource(R.string.problem_report_contact)) },
                            placeholder = { Text(stringResource(R.string.problem_report_contact_hint)) },
                            supportingText = { Text(stringResource(if (failed) R.string.problem_report_send_failed else R.string.problem_report_contact_note)) },
                            isError = failed,
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
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
                if (Unreleased.REPORT_FILE) ShareReportFile(text)
            }
        },
        confirmButton = {
            if (withGitHub) Button(onClick = openGitHub) { Text(stringResource(R.string.crash_report_github)) }
            else if (relay) Button(
                enabled = !sending,
                onClick = {
                    sending = true
                    scope.launch {
                        val result = ReportRelay.send(
                            build = ProblemLog.build(),
                            device = ProblemLog.device(),
                            report = text,
                            title = "Crash: $cause",
                            contact = words.contact,
                            what = words.what,
                        )
                        if (result is ReportRelay.Outcome.Failed) Log.w("CrashReport", "Not sent: ${result.why}")
                        // Filed, or kept by the site for a look: the words went with it.
                        else words.sent()
                        outcome = result
                        sending = false
                    }
                },
            ) {
                if (sending) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                else Text(stringResource(R.string.problem_report_send))
            }
            // A fork's crashes are its own: no form on InterTune's tracker (FORKS.md).
            else if (BuiltInKeys.official) TextButton(onClick = openGitHub) { Text(stringResource(R.string.crash_report_github)) }
        },
        dismissButton = {
            Row {
                TextButton(
                    onClick = {
                        context.getSystemService(ClipboardManager::class.java)
                            ?.setPrimaryClip(ClipData.newPlainText("InterTune crash", text))
                        // Android 13 and later show their own confirmation for every copy.
                        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
                            Toaster.show(context, R.string.crash_report_copied, Toast.LENGTH_SHORT)
                        }
                        forget()
                    }
                ) { Text(stringResource(R.string.crash_report_copy)) }
                if (!relay) TextButton(onClick = forget) { Text(stringResource(R.string.crash_report_dismiss)) }
            }
        },
    )
}

/**
 * The newer version to name on the crash page: the one the update check stored, when it is newer than
 * the one running. Null when the check never ran (it is opt in), found nothing newer, or kept no name,
 * and for the version the person skipped: the Updates page no longer offers it ("No updates
 * available"), so the note would send them nowhere.
 */
internal fun crashNewerVersion(foundCode: Int, foundName: String, running: Int, skipped: Int = -1): String? =
    foundName.takeIf { foundCode > running && foundCode != skipped && it.isNotBlank() }
