/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Groups
import androidx.compose.material.icons.rounded.Poll
import androidx.compose.material.icons.rounded.Update
import androidx.compose.material3.Button
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.dd3boh.outertune.utils.InstallSource
import com.dd3boh.outertune.utils.installSource
import com.dd3boh.outertune.LocalPollChecker
import com.dd3boh.outertune.LocalUpdateChecker
import com.dd3boh.outertune.R
import com.dd3boh.outertune.constants.AutoInstallUpdatesKey
import com.dd3boh.outertune.constants.PollsEnabledKey
import com.dd3boh.outertune.constants.UsageCountEnabledKey
import androidx.datastore.preferences.core.edit
import com.dd3boh.outertune.constants.UpdateCheckEnabledKey
import com.dd3boh.outertune.utils.dataStore
import com.dd3boh.outertune.ui.component.SwitchPreference
import com.dd3boh.outertune.utils.rememberPreference
import com.dd3boh.outertune.utils.rememberNullablePreference
import kotlinx.coroutines.launch

/**
 * Asks, once, whether to check for updates, and afterwards shows the answer.
 *
 * While the question is open it is two buttons rather than a switch. A switch has a default, and a
 * default is an answer nobody gave: the preference stays unset and there is no way to tell "left it
 * alone" from "said no". Both buttons write the preference, so afterwards it is set either way and
 * nothing asks again. That distinction is what lets the app ask a second time after restoring a
 * backup from a version that predates this setting, without pestering anyone who already declined.
 *
 * Once answered the card does not disappear, it becomes the setting. Disappearing was the bug: this
 * page is what "Enter configurator" replays, so on any install that had already answered, which is
 * every install more than five minutes old, the final page silently dropped the one thing it
 * offered and read as broken. Showing the current value is what the rest of the wizard already
 * does, since steps 1 to 4 embed the real settings fragments rather than onboarding copies.
 */
@Composable
fun UpdateOptInCard() {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val updateChecker = LocalUpdateChecker.current

    // Nullable on purpose. null is "never asked", which is not "said no".
    val choice by rememberNullablePreference(UpdateCheckEnabledKey)
    val (autoInstall, onAutoInstallChange) = rememberPreference(AutoInstallUpdatesKey, defaultValue = false)

    // Opting in checks straight away, otherwise the answer appears to do nothing for hours.
    // Same reason as the switch in Settings > Updates.
    fun answer(enabled: Boolean) {
        coroutineScope.launch {
            context.dataStore.edit { it[UpdateCheckEnabledKey] = enabled }
            if (enabled) updateChecker.check(force = true)
        }
    }

    ElevatedCard(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp, vertical = 8.dp)
    ) {
        val answered = choice
        if (answered == null) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    text = stringResource(R.string.oobe_update_check_title),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    // The default copy opens with "InterTune is not on an app store", which stops
                    // being true the moment somebody installs it from one. Asking an F-Droid user
                    // to accept a GitHub updater on that reasoning is asking them to agree to
                    // something false.
                    text = stringResource(
                        if (LocalContext.current.installSource() == InstallSource.F_DROID)
                            R.string.oobe_update_check_description_fdroid
                        else R.string.oobe_update_check_description
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp)
                )
                Row(
                    horizontalArrangement = Arrangement.End,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 12.dp)
                ) {
                    TextButton(onClick = { answer(false) }) {
                        Text(stringResource(R.string.oobe_update_check_no))
                    }

                    Spacer(Modifier.width(8.dp))

                    Button(onClick = { answer(true) }) {
                        Text(stringResource(R.string.oobe_update_check_yes))
                    }
                }
            }
        } else {
            // Deliberately the same component and title string as the row in Settings > Updates, so
            // it reads as "this is that setting" rather than a copy of it.
            SwitchPreference(
                title = { Text(stringResource(R.string.update_check)) },
                description = stringResource(R.string.oobe_update_check_answered),
                icon = { Icon(Icons.Rounded.Update, null) },
                checked = answered,
                onCheckedChange = { answer(it) }
            )

            // A dependent row rather than a card of its own, the same shape Settings > Updates
            // uses. Offering to download updates automatically to somebody who has just declined
            // update checking is incoherent, so it only exists once they have said yes.
            AnimatedVisibility(visible = answered) {
                SwitchPreference(
                    title = { Text(stringResource(R.string.update_auto)) },
                    description = stringResource(R.string.oobe_update_auto_description),
                    icon = { Icon(Icons.Rounded.Download, null) },
                    checked = autoInstall,
                    onCheckedChange = onAutoInstallChange,
                )
            }
        }
    }
}

/**
 * The other question worth asking during setup.
 *
 * A sibling of [UpdateOptInCard] rather than a variation of it: same nullable preference trick, so
 * "never asked" stays distinguishable from "said no", and the same two shapes. Someone who skips
 * the wizard has not answered, and can still be asked later.
 */
@Composable
fun PollsOptInCard() {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val pollChecker = LocalPollChecker.current

    val choice by rememberNullablePreference(PollsEnabledKey)

    fun answer(enabled: Boolean) {
        coroutineScope.launch {
            // Write first, then look. The setter is fire and forget, so checking immediately after
            // it reads the old value and reports that there is nothing to ask.
            context.dataStore.edit { it[PollsEnabledKey] = enabled }
            if (enabled) pollChecker.check(force = true)
        }
    }

    ElevatedCard(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp, vertical = 8.dp)
    ) {
        val answered = choice
        if (answered == null) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    text = stringResource(R.string.polls_opt_in_title),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    text = stringResource(R.string.polls_opt_in_body),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp)
                )
                Row(
                    horizontalArrangement = Arrangement.End,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 12.dp)
                ) {
                    TextButton(onClick = { answer(false) }) {
                        Text(stringResource(R.string.polls_opt_in_no))
                    }

                    Spacer(Modifier.width(8.dp))

                    Button(onClick = { answer(true) }) {
                        Text(stringResource(R.string.polls_opt_in_yes))
                    }
                }
            }
        } else {
            SwitchPreference(
                title = { Text(stringResource(R.string.polls_enabled)) },
                description = stringResource(R.string.oobe_polls_answered),
                icon = { Icon(Icons.Rounded.Poll, null) },
                checked = answered,
                onCheckedChange = { answer(it) }
            )
        }
    }
}


/**
 * The third and last of these, and the only one that sends anything without being looked at.
 *
 * Its own card rather than a line inside the questions one, because they are different bargains:
 * a poll is a thing you are shown and may answer, this is a thing that happens quietly once a day.
 * Folding them into one yes would be the kind of consent that is technically obtained and actually
 * not, and the poll modal's promise that nothing identifies anybody has to stay true on its own.
 *
 * No ping is fired on saying yes. [com.dd3boh.outertune.utils.ActiveCount] runs at launch, so the
 * first count lands on the next open, which is soon enough and keeps this card free of a checker.
 */
@Composable
fun UsageCountOptInCard() {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()

    val choice by rememberNullablePreference(UsageCountEnabledKey)

    fun answer(enabled: Boolean) {
        coroutineScope.launch {
            context.dataStore.edit { it[UsageCountEnabledKey] = enabled }
        }
    }

    ElevatedCard(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp, vertical = 8.dp)
    ) {
        val answered = choice
        if (answered == null) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    text = stringResource(R.string.usage_count_opt_in_title),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    text = stringResource(R.string.usage_count_opt_in_body),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp)
                )
                Row(
                    horizontalArrangement = Arrangement.End,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 12.dp)
                ) {
                    TextButton(onClick = { answer(false) }) {
                        Text(stringResource(R.string.polls_opt_in_no))
                    }

                    Spacer(Modifier.width(8.dp))

                    Button(onClick = { answer(true) }) {
                        Text(stringResource(R.string.usage_count_opt_in_yes))
                    }
                }
            }
        } else {
            SwitchPreference(
                title = { Text(stringResource(R.string.usage_count_enabled)) },
                description = stringResource(R.string.oobe_usage_count_answered),
                icon = { Icon(Icons.Rounded.Groups, null) },
                checked = answered,
                onCheckedChange = { answer(it) }
            )
        }
    }
}
