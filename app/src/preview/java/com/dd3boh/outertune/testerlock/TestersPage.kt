/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.testerlock

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.text.format.DateUtils
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ExitToApp
import androidx.compose.material.icons.automirrored.rounded.Rule
import androidx.compose.material.icons.rounded.BugReport
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Event
import androidx.compose.material.icons.rounded.Forum
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Key
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material.icons.rounded.Science
import androidx.compose.material.icons.rounded.WarningAmber
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarScrollBehavior
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.navigation.NavController
import com.dd3boh.outertune.BuildConfig
import com.dd3boh.outertune.R
import com.dd3boh.outertune.ui.component.ColumnWithContentPadding
import com.dd3boh.outertune.ui.component.FloatingTopBar
import com.dd3boh.outertune.ui.component.PreferenceEntry
import com.dd3boh.outertune.ui.component.PreferenceGroupTitle
import com.dd3boh.outertune.ui.component.ProblemReportDialog
import com.dd3boh.outertune.ui.screens.settings.ExtraSettingsPage
import com.dd3boh.outertune.ui.screens.settings.SettingEntry
import com.dd3boh.outertune.ui.screens.settings.SettingsCategory
import com.dd3boh.outertune.ui.screens.settings.fragments.FamiliarFirstRows
import com.dd3boh.outertune.ui.screens.settings.fragments.familiarFirstOffered
import com.dd3boh.outertune.ui.screens.settings.settingRow
import java.time.ZoneId

/**
 * The Testers page of Settings, in the builds testers are given (TesterLock.testers): this build, what to try in it, the
 * switches to try, a way to report what breaks, the tester's code and its checks, and where the other testers are.
 *
 * What the lock says is [TestersPageScreen]'s, worked out from what the lock remembers, and drawn here; this decides
 * nothing. The rows are the app's own (PreferenceEntry, SwitchPreference) in its own cards, as every other page of
 * Settings has them, and the ones a search can find are listed in [entries], held to the rows below by a test.
 *
 * The code is shown once, with all but its last four symbols hidden, and goes nowhere: it is not copied, logged, sent or
 * put in a report. The report carries the tester's number instead (TesterLock.testerNumber), which names the tester to
 * staff and opens nothing.
 */
internal object TestersPage : ExtraSettingsPage {
    private const val ROUTE = "settings/testers"

    override val category = SettingsCategory(ROUTE, R.string.testers_page_title, Icons.Rounded.Science)

    /** The rows a search finds. The readings (version, dates, the code, the rules) are left out: they are not settings. */
    override val entries = listOf(
        SettingEntry(ROUTE, R.string.familiar_first, R.string.familiar_first_description, there = familiarFirstOffered),
        SettingEntry(ROUTE, R.string.familiar_mix, R.string.familiar_mix_description, under = R.string.familiar_first, there = familiarFirstOffered),
        SettingEntry(ROUTE, R.string.problem_report_title, R.string.testers_page_report_description, words = listOf(R.string.search_words_bug)),
        SettingEntry(ROUTE, R.string.testers_page_check_now, words = listOf(R.string.testers_page_code)),
        SettingEntry(ROUTE, R.string.testers_page_channel, R.string.testers_page_channel_description),
        SettingEntry(ROUTE, R.string.testers_page_give_back, R.string.testers_page_give_back_text),
    )

    @Composable
    override fun ListCard(navController: NavController) {
        ElevatedCard(modifier = Modifier.fillMaxWidth()) {
            PreferenceEntry(
                title = { Text(stringResource(R.string.testers_page_title)) },
                description = stringResource(R.string.testers_page_entry),
                icon = { Icon(category.icon, null) },
                onClick = { navController.navigate(ROUTE) },
            )
        }
    }

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    override fun Screen(navController: NavController, scrollBehavior: TopAppBarScrollBehavior) {
        Testers(navController)
    }
}

@Composable
private fun Testers(navController: NavController) {
    val context = LocalContext.current
    val session = remember { TesterLockSession.of(context.applicationContext) }
    val memory by session.memory.collectAsState()
    val checking by session.busy.collectAsState()
    var reporting by rememberSaveable { mutableStateOf(false) }
    if (reporting) ProblemReportDialog(onDismiss = { reporting = false })

    // The clock is read when the page is drawn and when the lock's memory changes: a check just made moves both times.
    val screen = remember(memory) { TestersPageScreen.of(BuildConfig.VERSION_NAME, memory, System.currentTimeMillis()) }
    val tryLines = remember { TestersPageScreen.tryLines(readTryFile(context)) }
    val built = remember { TestersPageScreen.builtAt(BuildConfig.VERSION_NAME) }

    ColumnWithContentPadding(
        modifier = Modifier.fillMaxHeight(),
        columnModifier = Modifier.padding(horizontal = 16.dp),
    ) {
        PreferenceGroupTitle(title = stringResource(R.string.testers_page_build))
        ElevatedCard(modifier = Modifier.fillMaxWidth()) {
            PreferenceEntry(
                title = { Text(stringResource(R.string.testers_page_version)) },
                description = BuildConfig.VERSION_NAME,
                icon = { Icon(Icons.Rounded.Info, null) },
                onClick = null,
            )
            if (built != null) {
                PreferenceEntry(
                    title = { Text(stringResource(R.string.testers_page_built)) },
                    description = time(context, built.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli(), withYear = true),
                    icon = { Icon(Icons.Rounded.Schedule, null) },
                    onClick = null,
                )
            }
            PreferenceEntry(
                title = { Text(stringResource(R.string.testers_page_status)) },
                description = stringResource(screen.result.words()),
                icon = { Icon(if (screen.result == CheckResult.OPENS) Icons.Rounded.CheckCircle else Icons.Rounded.WarningAmber, null) },
                onClick = null,
            )
        }
        Spacer(modifier = Modifier.height(16.dp))

        // Nothing at all without a list: a build made by hand carries none.
        if (tryLines.isNotEmpty()) {
            PreferenceGroupTitle(title = stringResource(R.string.testers_page_try))
            ElevatedCard(modifier = Modifier.fillMaxWidth()) {
                Column(
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier.padding(16.dp),
                ) {
                    for (line in tryLines) TryLine(line)
                }
            }
            Spacer(modifier = Modifier.height(16.dp))
        }

        // The developer block's own rows, drawn by the code it draws them with.
        if (familiarFirstOffered) {
            PreferenceGroupTitle(title = stringResource(R.string.testers_page_switches))
            ElevatedCard(modifier = Modifier.fillMaxWidth()) {
                FamiliarFirstRows()
            }
            Spacer(modifier = Modifier.height(16.dp))
        }

        ElevatedCard(modifier = Modifier.fillMaxWidth()) {
            // The report page the app has, with this build's name in it and the tester's number, never the code.
            PreferenceEntry(
                modifier = Modifier.settingRow(R.string.problem_report_title),
                title = { Text(stringResource(R.string.problem_report_title)) },
                description = stringResource(R.string.testers_page_report_description),
                icon = { Icon(Icons.Rounded.BugReport, null) },
                onClick = { reporting = true },
            )
        }
        Spacer(modifier = Modifier.height(16.dp))

        PreferenceGroupTitle(title = stringResource(R.string.testers_page_code))
        ElevatedCard(modifier = Modifier.fillMaxWidth()) {
            val ends = if (screen.codeEnd.isEmpty()) null else stringResource(R.string.testers_page_code_ends, screen.codeEnd)
            PreferenceEntry(
                title = {
                    Text(
                        text = screen.maskedCode ?: stringResource(R.string.testers_page_no_code),
                        fontFamily = if (screen.maskedCode != null) FontFamily.Monospace else null,
                        // The dots are not what a screen reader should say.
                        modifier = if (ends != null) Modifier.semantics { contentDescription = ends } else Modifier,
                    )
                },
                description = stringResource(R.string.testers_page_code_used),
                icon = { Icon(Icons.Rounded.Key, null) },
                onClick = null,
            )
            PreferenceEntry(
                title = { Text(stringResource(R.string.testers_page_last_check)) },
                description = screen.lastCheck?.let { time(context, it) } ?: stringResource(R.string.testers_page_never),
                icon = { Icon(Icons.Rounded.Schedule, null) },
                onClick = null,
            )
            PreferenceEntry(
                title = { Text(stringResource(R.string.testers_page_next_check)) },
                description = when (val next = screen.next) {
                    NextCheck.AtNextOpening -> stringResource(R.string.testers_page_next_now)
                    is NextCheck.After -> stringResource(R.string.testers_page_next_after, time(context, next.time))
                },
                icon = { Icon(Icons.Rounded.Event, null) },
                onClick = null,
            )
            PreferenceEntry(
                modifier = Modifier.settingRow(R.string.testers_page_check_now),
                title = { Text(stringResource(R.string.testers_page_check_now)) },
                description = if (checking) stringResource(R.string.testers_page_checking) else null,
                icon = { Icon(Icons.Rounded.Refresh, null) },
                isEnabled = !checking,
                onClick = { session.checkNow() },
            )
        }
        Spacer(modifier = Modifier.height(16.dp))

        ElevatedCard(modifier = Modifier.fillMaxWidth()) {
            PreferenceEntry(
                modifier = Modifier.settingRow(R.string.testers_page_channel),
                title = { Text(stringResource(R.string.testers_page_channel)) },
                description = stringResource(R.string.testers_page_channel_description),
                icon = { Icon(Icons.Rounded.Forum, null) },
                onClick = { openTesters(context) },
            )
            PreferenceEntry(
                title = { Text(stringResource(R.string.testers_page_rules)) },
                description = stringResource(R.string.testers_page_rules_text),
                icon = { Icon(Icons.AutoMirrored.Rounded.Rule, null) },
                onClick = null,
            )
            PreferenceEntry(
                modifier = Modifier.settingRow(R.string.testers_page_give_back),
                title = { Text(stringResource(R.string.testers_page_give_back)) },
                description = stringResource(R.string.testers_page_give_back_text),
                icon = { Icon(Icons.AutoMirrored.Rounded.ExitToApp, null) },
                onClick = { openTesters(context) },
            )
        }
    }

    FloatingTopBar(title = stringResource(R.string.testers_page_title), navController = navController)
}

/** One line of what to try, with its dot. */
@Composable
private fun TryLine(text: String) {
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        // A screen reader reads the line, not the dot.
        Text(text = "•", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.clearAndSetSemantics { })
        Text(text = text, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
    }
}

/** What the lock's last check said, in words. */
private fun CheckResult.words(): Int = when (this) {
    CheckResult.OPENS -> R.string.testers_page_status_opens
    CheckResult.OUT_OF_DATE -> R.string.testers_page_status_outdated
    CheckResult.NOT_REACHED -> R.string.testers_page_status_not_reached
    CheckResult.NOT_YET -> R.string.testers_page_status_not_yet
}

/** A moment in the phone's own date and time style: "31 Oct, 11:38", with the year for the day a build was made. */
private fun time(context: Context, millis: Long, withYear: Boolean = false): String {
    val flags = DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_SHOW_TIME or DateUtils.FORMAT_ABBREV_MONTH or
        (if (withYear) DateUtils.FORMAT_SHOW_YEAR else 0)
    return DateUtils.formatDateTime(context, millis, flags)
}

/** What a build made with -PtesterTry carries in its assets, or null: no file in a build made by hand. */
private fun readTryFile(context: Context): String? = try {
    context.assets.open(TestersPageScreen.TRY_ASSET).bufferedReader().use { it.readText() }
} catch (e: java.io.IOException) {
    null
}

/**
 * The #testers channel, in the Discord app when it is installed and in the browser when it is not: the phone picks, as it
 * does for any link. Nothing is sent from here, and a phone with neither opens nothing.
 */
private fun openTesters(context: Context) {
    try {
        context.startActivity(Intent(Intent.ACTION_VIEW, TestersPageScreen.CHANNEL.toUri()).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    } catch (e: ActivityNotFoundException) {
        // No app and no browser to show a link: nothing to open.
    }
}
