/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.ui.screens.settings

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.Headphones
import androidx.compose.material.icons.rounded.History
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarScrollBehavior
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import com.dd3boh.outertune.R
import com.dd3boh.outertune.constants.RecogniseKeepListeningKey
import com.dd3boh.outertune.constants.RecognisePauseOnSpeakerKey
import com.dd3boh.outertune.constants.TopBarInsets
import androidx.hilt.navigation.compose.hiltViewModel
import com.dd3boh.outertune.recognition.RecognitionViewModel
import com.dd3boh.outertune.ui.component.ColumnWithContentPadding
import com.dd3boh.outertune.ui.component.ExplainedPreference
import com.dd3boh.outertune.ui.component.ExplainedSwitchPreference
import com.dd3boh.outertune.ui.component.PreferenceGroupTitle
import com.dd3boh.outertune.ui.component.button.IconButton
import com.dd3boh.outertune.ui.utils.backToMain
import com.dd3boh.outertune.utils.rememberPreference

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RecognitionSettings(
    navController: NavController,
    scrollBehavior: TopAppBarScrollBehavior,
    viewModel: RecognitionViewModel = hiltViewModel(),
) {
    val (keepListening, onKeepListeningChange) =
        rememberPreference(RecogniseKeepListeningKey, defaultValue = false)
    val (pauseOnSpeaker, onPauseOnSpeakerChange) =
        rememberPreference(RecognisePauseOnSpeakerKey, defaultValue = true)

    ColumnWithContentPadding(
        modifier = Modifier.fillMaxHeight(),
        columnModifier = Modifier.padding(horizontal = 16.dp),
    ) {
        PreferenceGroupTitle(title = stringResource(R.string.recognise_settings))

        ElevatedCard(modifier = Modifier.fillMaxWidth()) {
            ExplainedSwitchPreference(
                title = stringResource(R.string.recognise_keep_listening),
                explanation = stringResource(R.string.recognise_keep_listening_explain),
                description = stringResource(R.string.recognise_keep_listening_desc),
                checked = keepListening,
                onCheckedChange = onKeepListeningChange,
            )
            ExplainedSwitchPreference(
                title = stringResource(R.string.recognise_pause_title),
                explanation = stringResource(R.string.recognise_pause_explain),
                description = stringResource(R.string.recognise_pause_desc),
                checked = pauseOnSpeaker,
                onCheckedChange = onPauseOnSpeakerChange,
            )
        }

        Spacer(Modifier.height(16.dp))

        ElevatedCard(modifier = Modifier.fillMaxWidth()) {
            ExplainedPreference(
                title = stringResource(R.string.recognise_clear_history),
                explanation = stringResource(R.string.recognise_clear_history_explain),
                description = stringResource(R.string.recognise_clear_history_desc),
                // Both: reset empties this run, clearHistory empties what is remembered across
                // runs. The entry says history, so it has to be the second one too.
                onClick = { viewModel.reset(); viewModel.clearHistory() },
            )
        }
    }

    TopAppBar(
        title = { Text(stringResource(R.string.recognise_settings)) },
        navigationIcon = {
            IconButton(
                onClick = navController::navigateUp,
                onLongClick = navController::backToMain,
            ) {
                Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = null)
            }
        },
        windowInsets = TopBarInsets,
        scrollBehavior = scrollBehavior,
    )
}
