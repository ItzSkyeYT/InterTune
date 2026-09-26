/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.ui.screens.settings.fragments

import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Campaign
import androidx.compose.material.icons.rounded.HistoryToggleOff
import androidx.compose.material.icons.rounded.Poll
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.datastore.preferences.core.edit
import com.dd3boh.outertune.LocalPollChecker
import com.dd3boh.outertune.R
import com.dd3boh.outertune.constants.AnnouncementsEnabledKey
import com.dd3boh.outertune.constants.Polls
import com.dd3boh.outertune.constants.PollsEnabledKey
import com.dd3boh.outertune.ui.component.ExplainedSwitchPreference
import com.dd3boh.outertune.ui.component.PreferenceEntry
import com.dd3boh.outertune.utils.PollChecker
import com.dd3boh.outertune.utils.dataStore
import com.dd3boh.outertune.utils.rememberPreference
import kotlinx.coroutines.launch

/**
 * Where the occasional question is turned on, off, and inspected.
 *
 * This used to be its own screen. It now sits with the rest of the settings about what the app
 * records and sends, because that is what somebody looking for it is actually looking for.
 */
@Composable
fun ColumnScope.PollsFrag() {
    // Same reasoning as the Last.fm section: a build with no gist and no Umami endpoint cannot
    // ever ask a question, and PollChecker returns early on exactly this check. Offering the
    // switch anyway asks somebody to consent to answers being sent, and then does nothing with
    // that consent forever. Say so rather than leaving a live-looking control.
    if (!Polls.isConfigured) {
        PreferenceEntry(
            title = { Text(stringResource(R.string.polls_unavailable)) },
            description = stringResource(R.string.polls_unavailable_description),
            icon = { Icon(Icons.Rounded.Poll, null) },
            isEnabled = false,
            onClick = null,
        )
        return
    }

    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val pollChecker = LocalPollChecker.current

    val (enabled, onEnabledChange) = rememberPreference(PollsEnabledKey, defaultValue = false)
    val poll: PollChecker.Poll? by pollChecker.current.collectAsState()
    var checking by remember { mutableStateOf(false) }

    // One line under the switch; what is fetched and sent is behind the i, for whoever asks.
    ExplainedSwitchPreference(
        title = stringResource(R.string.polls_enabled),
        explanation = stringResource(R.string.polls_enabled_info),
        description = stringResource(R.string.polls_enabled_description),
        icon = { Icon(Icons.Rounded.Poll, null) },
        checked = enabled,
        onCheckedChange = {
            onEnabledChange(it)
            coroutineScope.launch {
                // Wait for the preference to actually land before looking. The setter is
                // fire and forget, so checking straight after it read the old value and
                // reported "no question right now" to somebody who had just switched it on.
                // Switching off looks again too, without fetching, so the banner goes at once.
                context.dataStore.edit { prefs -> prefs[PollsEnabledKey] = it }
                pollChecker.check(force = it)
            }
        }
    )

    // Its own switch since 0.10.9.5: somebody can want the news without being asked questions.
    // Same document, so the same fetch serves both.
    val (newsOn, onNewsChange) = rememberPreference(AnnouncementsEnabledKey, defaultValue = false)
    ExplainedSwitchPreference(
        title = stringResource(R.string.news_enabled),
        explanation = stringResource(R.string.news_enabled_info),
        description = stringResource(R.string.news_enabled_description),
        icon = { Icon(Icons.Rounded.Campaign, null) },
        checked = newsOn,
        onCheckedChange = {
            onNewsChange(it)
            coroutineScope.launch {
                context.dataStore.edit { prefs -> prefs[AnnouncementsEnabledKey] = it }
                pollChecker.check(force = it)
            }
        }
    )

    if (enabled) {
        PreferenceEntry(
            title = {
                Text(
                    stringResource(
                        if (poll != null) R.string.polls_one_waiting
                        else R.string.polls_none
                    )
                )
            },
            description = poll?.banner,
            icon = { Icon(Icons.Rounded.Refresh, null) },
            isEnabled = !checking,
            onClick = {
                checking = true
                coroutineScope.launch {
                    pollChecker.check(force = true)
                    checking = false
                }
            }
        )
        PreferenceEntry(
            title = { Text(stringResource(R.string.polls_forget)) },
            description = stringResource(R.string.polls_forget_description),
            icon = { Icon(Icons.Rounded.HistoryToggleOff, null) },
            onClick = {
                coroutineScope.launch {
                    pollChecker.forgetAll()
                    pollChecker.check(force = true)
                }
            }
        )
    }
}
