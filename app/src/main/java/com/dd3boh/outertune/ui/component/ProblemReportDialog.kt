/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.ui.component

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import android.os.Build
import android.text.format.DateUtils
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import android.util.Log
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.runtime.rememberCoroutineScope
import com.dd3boh.outertune.utils.ReportRelay
import com.dd3boh.outertune.utils.ReportWay
import com.dd3boh.outertune.utils.rememberEnumPreference
import kotlinx.coroutines.launch
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import com.dd3boh.outertune.utils.BuiltInKeys
import com.dd3boh.outertune.BuildConfig
import com.dd3boh.outertune.R
import com.dd3boh.outertune.constants.Links
import com.dd3boh.outertune.constants.ReportWayKey
import com.dd3boh.outertune.constants.Unreleased
import com.dd3boh.outertune.playback.PlayHealth
import com.dd3boh.outertune.playback.StopReasons
import com.dd3boh.outertune.utils.PhoneState
import com.dd3boh.outertune.utils.ProblemLog
import com.dd3boh.outertune.utils.RunLogs
import com.dd3boh.outertune.utils.installedFrom
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** GitHub turns down a very long address, and what led up to the fault is what the form is for. */
private const val FORM_LOG_LIMIT = 5000

/**
 * The whole link to the bug form at most. Signed out of GitHub, the form sends you to its sign-in page with the
 * whole link escaped once more inside it. Measured on 27 Oct 2026: a link of 4,851 characters still opens; 5,081
 * gets "Whoops, something went wrong!" from the sign-in page; from about 5,500 GitHub drops the link and the form
 * comes up empty after signing in. 6,000, right for someone signed in (26 Oct), failed in both ways.
 */
internal const val FORM_LINK_LIMIT = 4500

/** What happened, as typed, in the form's link at most: the rest of the link is the log's. */
internal const val FORM_WHAT_LIMIT = 1000

/** Logs listed before "Show older": this run's and the three before it. */
private const val FIRST_SHOWN = 4

/**
 * One log to choose from: this run's, read from the phone's log as the page opens, or an earlier
 * run's, read from its file ([RunLogs]). Only this run's carries the stops nobody asked for: they
 * are dated, and they outlive the run they ended. Where the app came from is asked once for all
 * of them: Android keeps only the latest install's.
 */
private class LogChoice(
    val run: RunLogs.Run?,
    val excerpt: ProblemLog.Excerpt,
    val stops: List<String>,
    val installedFrom: String,
    val phoneNow: String,
    val playback: String? = null,
) {
    val version: String get() = run?.versionName ?: BuildConfig.VERSION_NAME
    val build: String get() = run?.build ?: ProblemLog.build()
    fun report(): String = ProblemLog.report(
        excerpt, build = build, installedFrom = installedFrom, phoneNow = phoneNow, playback = playback, stops = stops, started = run?.started,
    )
}

/**
 * The last thing that went wrong, as the app noted it, to copy or to take to the bug form.
 *
 * Opened from About. The log is read when this opens and shown before anything is done with it:
 * it names the songs that were playing, and the person decides whether it leaves the phone. See
 * [ProblemLog]. With [Unreleased.PREVIOUS_LOGS], the earlier runs the app kept are listed above it,
 * the newest first and picked, so a fault from before an update can be sent too.
 */
@Composable
fun ProblemReportDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    var choices by remember { mutableStateOf<List<LogChoice>?>(null) }
    var picked by rememberSaveable { mutableIntStateOf(0) }
    var showAll by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        choices = withContext(Dispatchers.IO) {
            // This run's file when one is kept: the phone's log may have turned over since the start.
            val lines = (if (Unreleased.PREVIOUS_LOGS) RunLogs.now() else null) ?: ProblemLog.read()
            val installedFrom = context.installedFrom()
            // The connection and the battery as they are now, asked once: an earlier run's own are in its lines.
            val phoneNow = PhoneState.read(context).describe()
            // How playback has gone since the app started: this run's only, see PlayHealth.
            val playback = PlayHealth.thisRun.describe(SystemClock.elapsedRealtime())
            val now = LogChoice(null, ProblemLog.excerpt(lines), StopReasons.read(context), installedFrom, phoneNow, playback)
            val earlier = if (Unreleased.PREVIOUS_LOGS) {
                RunLogs.earlier(context).map { LogChoice(it, ProblemLog.excerpt(RunLogs.read(it)), emptyList(), installedFrom, phoneNow) }
            } else emptyList()
            listOf(now) + earlier
        }
    }
    val all = choices
    val choice = all?.getOrNull(picked)
    val found = choice?.excerpt
    val text = remember(choice) { choice?.report() }

    // Send to InterTune (Unreleased.REPORT_RELAY): the report goes to intertune.app, which files the issue
    // and gives back its link (ReportRelay). A fork keeps Copy only (FORKS.md).
    val relay = Unreleased.REPORT_RELAY && BuiltInKeys.official
    // Anonymous, or with the person's GitHub account: the way picked last time comes up first (ReportWayKey).
    var way by rememberEnumPreference(ReportWayKey, ReportWay.ANONYMOUS)
    val withGitHub = relay && way == ReportWay.GITHUB
    var what by rememberSaveable { mutableStateOf("") }
    var contact by rememberSaveable { mutableStateOf("") }
    var sending by remember { mutableStateOf(false) }
    var outcome by remember { mutableStateOf<ReportRelay.Outcome?>(null) }
    val scope = rememberCoroutineScope()
    when (val done = outcome) {
        is ReportRelay.Outcome.Sent -> return SentDialog(done, onDismiss)
        ReportRelay.Outcome.Held -> return HeldDialog(onDismiss)
        else -> Unit
    }

    val openForm: () -> Unit = {
        // The form's own boxes, by their ids in .github/ISSUE_TEMPLATE/bug_report.yml.
        val form = "${Links.ISSUES}/new".toUri().buildUpon()
            .appendQueryParameter("template", "bug_report.yml")
            .appendQueryParameter("build", choice?.build ?: ProblemLog.build())
            .appendQueryParameter("device", ProblemLog.device())
            // What they typed above, into the form's own box for it (id what-happens).
            .apply { if (relay && what.isNotBlank()) appendQueryParameter("what-happens", what.trim().take(FORM_WHAT_LIMIT)) }
            .build()
        // The log as much as the link holds: see FORM_LINK_LIMIT.
        val room = FORM_LINK_LIMIT - form.toString().length - "&logs=".length
        val uri = form.buildUpon()
            .appendQueryParameter("logs", ProblemLog.fittedForLink(text.orEmpty(), room, FORM_LOG_LIMIT))
            .build()
        runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
        onDismiss()
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.problem_report_title)) },
        text = {
            // One scroll for the words, the list and the log together, so the buttons stay on a
            // short screen. The log's long lines scroll sideways by themselves.
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text(
                    stringResource(
                        when {
                            found == null -> R.string.problem_report_reading
                            found.lines.isEmpty() -> if (choice?.run == null) R.string.problem_report_empty else R.string.problem_report_nothing_then
                            found.faultAt == null -> if (choice?.run == null) R.string.problem_report_none else R.string.problem_report_none_then
                            else -> R.string.problem_report_found
                        }
                    )
                )
                if (all != null && all.size > 1) {
                    Spacer(Modifier.height(12.dp))
                    LogList(all, picked, showAll, onPick = { picked = it }, onShowAll = { showAll = true })
                }
                if (relay && text != null) {
                    Spacer(Modifier.height(12.dp))
                    ReportWayChoice(way, onPick = { way = it })
                    Spacer(Modifier.height(12.dp))
                    OutlinedTextField(
                        value = what,
                        onValueChange = { what = it.take(2000) },
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
                            value = contact,
                            onValueChange = { contact = it.take(200) },
                            label = { Text(stringResource(R.string.problem_report_contact)) },
                            placeholder = { Text(stringResource(R.string.problem_report_contact_hint)) },
                            supportingText = { Text(stringResource(if (failed) R.string.problem_report_send_failed else R.string.problem_report_contact_note)) },
                            isError = failed,
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
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
            if (withGitHub) Button(enabled = text != null, onClick = openForm) {
                Text(stringResource(R.string.problem_report_form))
            }
            else if (relay) Button(
                enabled = text != null && !sending,
                onClick = {
                    sending = true
                    scope.launch {
                        val result = ReportRelay.send(
                            build = choice?.build ?: ProblemLog.build(),
                            device = ProblemLog.device(),
                            report = text.orEmpty(),
                            title = ReportRelay.title(found) ?: what.lineSequence().firstOrNull()?.trim(),
                            contact = contact,
                            what = what,
                        )
                        if (result is ReportRelay.Outcome.Failed) Log.w("ProblemReport", "Not sent: ${result.why}")
                        outcome = result
                        sending = false
                    }
                },
            ) {
                if (sending) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                else Text(stringResource(R.string.problem_report_send))
            }
            // A fork's reports are its own: no form on InterTune's tracker (FORKS.md). Copy stays.
            else if (BuiltInKeys.official) TextButton(enabled = text != null, onClick = openForm) {
                Text(stringResource(R.string.problem_report_form))
            }
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
                if (!relay) TextButton(onClick = onDismiss) { Text(stringResource(R.string.problem_report_close)) }
            }
        },
    )
}

/**
 * Anonymous or with GitHub, for Report a problem and the crash page: two segments, and under them one
 * line on what the picked way means. The pick is kept by the caller (ReportWayKey).
 */
@Composable
internal fun ReportWayChoice(way: ReportWay, onPick: (ReportWay) -> Unit) {
    Column {
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            ReportWay.entries.forEachIndexed { i, option ->
                SegmentedButton(
                    selected = way == option,
                    onClick = { onPick(option) },
                    shape = SegmentedButtonDefaults.itemShape(i, ReportWay.entries.size),
                ) {
                    Text(
                        stringResource(if (option == ReportWay.ANONYMOUS) R.string.report_way_anonymous else R.string.report_way_github),
                        maxLines = 1,
                    )
                }
            }
        }
        Spacer(Modifier.height(6.dp))
        Text(
            stringResource(if (way == ReportWay.ANONYMOUS) R.string.report_way_anonymous_note else R.string.report_way_github_note),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** After Send to InterTune: the issue's number and its link, which anyone can follow without an account. */
@Composable
internal fun SentDialog(sent: ReportRelay.Outcome.Sent, onDismiss: () -> Unit) {
    val context = LocalContext.current
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Rounded.CheckCircle, contentDescription = null) },
        title = { Text(stringResource(R.string.problem_report_sent, sent.number)) },
        text = {
            Column {
                Text(stringResource(R.string.problem_report_sent_body))
                Spacer(Modifier.height(12.dp))
                Text(sent.url.removePrefix("https://"), style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
            }
        },
        confirmButton = {
            TextButton(onClick = {
                runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, sent.url.toUri()).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                onDismiss()
            }) { Text(stringResource(R.string.problem_report_open_issue, sent.number)) }
        },
        dismissButton = {
            TextButton(onClick = {
                context.getSystemService(ClipboardManager::class.java)?.setPrimaryClip(ClipData.newPlainText("InterTune report", sent.url))
                // Android 13 and later show their own confirmation for every copy.
                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
                    Toast.makeText(context, R.string.problem_report_copied, Toast.LENGTH_SHORT).show()
                }
            }) { Text(stringResource(R.string.problem_report_copy_link)) }
        },
    )
}

/** After Send to InterTune, when the site keeps the report for a look before it goes on GitHub. */
@Composable
internal fun HeldDialog(onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.problem_report_title)) },
        text = { Text(stringResource(R.string.problem_report_held)) },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.problem_report_close)) } },
    )
}

/** The logs to choose from, this run's first; the picked one stands out, and is shown even among the older. */
@Composable
private fun LogList(choices: List<LogChoice>, picked: Int, showAll: Boolean, onPick: (Int) -> Unit, onShowAll: () -> Unit) {
    val context = LocalContext.current
    val shown = shownLogs(choices.size, picked, showAll)
    Column(Modifier.selectableGroup()) {
        shown.forEach { i ->
            val choice = choices[i]
            val selected = i == picked
            val ink = if (selected) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onSurface
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(if (selected) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent)
                    .selectable(selected = selected, onClick = { onPick(i) }, role = Role.RadioButton)
                    .padding(start = 4.dp, end = 12.dp, top = 6.dp, bottom = 6.dp),
            ) {
                RadioButton(selected = selected, onClick = null, modifier = Modifier.padding(8.dp))
                Spacer(Modifier.width(4.dp))
                Column(Modifier.weight(1f)) {
                    Text(title(context, choice), style = MaterialTheme.typography.bodyLarge, color = ink)
                    Text(
                        summary(context, choice),
                        style = MaterialTheme.typography.bodySmall,
                        color = if (selected) ink else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        if (shown.size < choices.size) {
            TextButton(onClick = onShowAll) { Text(stringResource(R.string.problem_report_older)) }
        }
    }
}

/** Which of [count] logs are listed: all of them, or the first [FIRST_SHOWN] and the picked one. */
internal fun shownLogs(count: Int, picked: Int, showAll: Boolean): List<Int> =
    if (showAll || count <= FIRST_SHOWN) (0 until count).toList()
    else (0 until FIRST_SHOWN).toList().let { if (picked in it) it else it + picked }

private fun title(context: Context, choice: LogChoice): String = choice.run?.let {
    DateUtils.formatDateTime(context, it.started.time, DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_SHOW_TIME or DateUtils.FORMAT_ABBREV_MONTH)
} ?: context.getString(R.string.problem_report_now)

/** The version, and whether and when something went wrong: "0.12 · went wrong at 14:02". */
private fun summary(context: Context, choice: LogChoice): String {
    val excerpt = choice.excerpt
    val at = excerpt.faultAt
    return when {
        excerpt.lines.isEmpty() -> context.getString(R.string.problem_report_no_lines, choice.version)
        at == null -> context.getString(R.string.problem_report_no_fault, choice.version)
        else -> context.getString(R.string.problem_report_went_wrong, choice.version, excerpt.lines[at].time.take(5))
    }
}
