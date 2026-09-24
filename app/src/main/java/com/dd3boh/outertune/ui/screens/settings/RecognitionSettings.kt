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
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.Headphones
import androidx.compose.material.icons.rounded.Timer
import androidx.compose.material.icons.rounded.History
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarScrollBehavior
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import com.dd3boh.outertune.R
import com.dd3boh.outertune.constants.RecogniseAutoAddKey
import com.dd3boh.outertune.constants.RecogniseKeepAwakeKey
import com.dd3boh.outertune.constants.RecogniseListenSecondsKey
import com.dd3boh.outertune.constants.RecogniseKeepListeningKey
import com.dd3boh.outertune.constants.RecognisePauseOnSpeakerKey
import androidx.hilt.navigation.compose.hiltViewModel
import com.dd3boh.outertune.recognition.MicrophoneListener
import com.dd3boh.outertune.recognition.RecognitionViewModel
import com.dd3boh.outertune.ui.component.ColumnWithContentPadding
import com.dd3boh.outertune.ui.component.ExplainedPreference
import com.dd3boh.outertune.ui.component.ExplainedSwitchPreference
import com.dd3boh.outertune.ui.component.FloatingTopBar
import com.dd3boh.outertune.ui.component.ListPreference
import com.dd3boh.outertune.ui.component.PreferenceGroupTitle
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
    val (autoAdd, onAutoAddChange) =
        rememberPreference(RecogniseAutoAddKey, defaultValue = true)
    val (keepAwake, onKeepAwakeChange) =
        rememberPreference(RecogniseKeepAwakeKey, defaultValue = false)
    val (listenSeconds, onListenSecondsChange) =
        rememberPreference(RecogniseListenSecondsKey, defaultValue = MicrophoneListener.DEFAULT_SECONDS)

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
            ExplainedSwitchPreference(
                title = stringResource(R.string.recognise_auto_add),
                explanation = stringResource(R.string.recognise_auto_add_explain),
                description = stringResource(R.string.recognise_auto_add_desc),
                checked = autoAdd,
                onCheckedChange = onAutoAddChange,
            )
            ExplainedSwitchPreference(
                title = stringResource(R.string.recognise_keep_awake),
                explanation = stringResource(R.string.recognise_keep_awake_explain),
                description = stringResource(R.string.recognise_keep_awake_desc),
                checked = keepAwake,
                onCheckedChange = onKeepAwakeChange,
            )
        }

        Spacer(Modifier.height(16.dp))

        ElevatedCard(modifier = Modifier.fillMaxWidth()) {
            ListPreference(
                title = { Text(stringResource(R.string.recognise_listen_seconds)) },
                icon = { Icon(Icons.Rounded.Timer, null) },
                selectedValue = listenSeconds,
                values = listOf(8, 12, 16, 20),
                valueText = { stringResource(R.string.recognise_seconds, it) },
                onValueSelected = onListenSecondsChange,
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
                onClick = { viewModel.reset(); viewModel.clearRecognised(); viewModel.clearHistory() },
            )
        }
    }

    FloatingTopBar(title = stringResource(R.string.recognise_settings), navController = navController)
}
