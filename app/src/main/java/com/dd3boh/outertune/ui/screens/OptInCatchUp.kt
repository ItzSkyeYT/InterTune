/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.dd3boh.outertune.R
import com.dd3boh.outertune.constants.PollsEnabledKey
import com.dd3boh.outertune.constants.UpdateCheckEnabledKey
import com.dd3boh.outertune.utils.rememberNullablePreference

/**
 * The questions somebody was never asked, asked.
 *
 * [SetupWizard] is the only place that raises the update and questions opt ins, and it runs once,
 * which leaves two ways to hold an install whose answers were never given:
 *
 *  - updating from a build whose onboarding predates the card, so the wizard ran before there was
 *    anything to answer
 *  - restoring a backup taken before the answer was stored, since an absent key restores as absent
 *
 * Both preferences read as off in that state. That is the right default but a silent one: no update
 * check ever runs, no question is ever fetched, and nothing says so. The first poll went out to an
 * install base where almost nobody had been asked, so almost nobody answered.
 *
 * A slice of onboarding rather than a second copy of it. The same two cards the wizard shows, in
 * the same order, with none of the setup around them, so it is over in one screen. Somebody who has
 * already answered one of them sees it as the switch it becomes rather than as a question, which
 * keeps the screen honest about how little it is actually asking for.
 *
 * Not dismissable, for the reason the update dialog before it was not: a prompt that can be waved
 * away without answering leaves the preference unset, and then it is owed all over again on the next
 * launch. [onDone] only unlocks once both have a value, so the screen is left by answering it.
 */
@Composable
fun OptInCatchUp(onDone: () -> Unit) {
    val updateChoice by rememberNullablePreference(UpdateCheckEnabledKey)
    val pollChoice by rememberNullablePreference(PollsEnabledKey)

    Dialog(
        onDismissRequest = { },
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            dismissOnBackPress = false,
            dismissOnClickOutside = false,
        )
    ) {
        Surface(modifier = Modifier.fillMaxSize()) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(vertical = 32.dp)
            ) {
                Icon(
                    imageVector = Icons.Rounded.Tune,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier
                        .size(80.dp)
                        .padding(16.dp),
                )
                Text(
                    text = stringResource(R.string.catch_up_title),
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp)
                )
                Text(
                    text = stringResource(R.string.catch_up_body),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp)
                )

                UpdateOptInCard()

                PollsOptInCard()

                Button(
                    enabled = updateChoice != null && pollChoice != null,
                    onClick = onDone,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 24.dp, vertical = 16.dp)
                ) {
                    Text(stringResource(R.string.catch_up_done))
                }
            }
        }
    }
}
