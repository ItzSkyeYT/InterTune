/*
 * Copyright (C) 2024 z-huang/InnerTune
 * Copyright (C) 2025 OuterTune Project
 *
 * SPDX-License-Identifier: GPL-3.0
 *
 * For any other attributions, refer to the git commit history
 */

package com.dd3boh.outertune.ui.screens.settings

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.QueueMusic
import androidx.compose.material.icons.rounded.AudioFile
import androidx.compose.material.icons.rounded.NoCell
import androidx.compose.material.icons.rounded.Queue
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarScrollBehavior
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.navigation.NavController
import com.dd3boh.outertune.LocalPlayerConnection
import com.dd3boh.outertune.R
import com.dd3boh.outertune.constants.AudioDecoderKey
import com.dd3boh.outertune.constants.ENABLE_FFMETADATAEX
import com.dd3boh.outertune.constants.KeepAliveKey
import com.dd3boh.outertune.constants.MaxQueuesKey
import androidx.compose.material.icons.rounded.PlayArrow
import com.dd3boh.outertune.constants.ResumePlaybackOnLaunchKey
import com.dd3boh.outertune.constants.PersistentQueueKey
import com.dd3boh.outertune.constants.StopMusicOnTaskClearKey
import com.dd3boh.outertune.constants.TopBarInsets
import com.dd3boh.outertune.ui.component.ColumnWithContentPadding
import com.dd3boh.outertune.ui.component.ListPreference
import com.dd3boh.outertune.ui.component.PreferenceEntry
import com.dd3boh.outertune.ui.component.PreferenceGroupTitle
import com.dd3boh.outertune.ui.component.SettingsClickToReveal
import com.dd3boh.outertune.ui.component.SwitchPreference
import com.dd3boh.outertune.ui.component.button.IconButton
import com.dd3boh.outertune.ui.dialog.CounterDialog
import com.dd3boh.outertune.ui.dialog.InfoLabel
import com.dd3boh.outertune.ui.screens.settings.fragments.AudioEffectsFrag
import com.dd3boh.outertune.ui.screens.settings.fragments.AudioQualityFrag
import com.dd3boh.outertune.ui.screens.settings.fragments.SpatialAudioFrag
import com.dd3boh.outertune.ui.screens.settings.fragments.PlaybackBehaviourFrag
import com.dd3boh.outertune.ui.screens.settings.fragments.PlayerGeneralFrag
import com.dd3boh.outertune.ui.utils.backToMain
import com.dd3boh.outertune.utils.rememberPreference
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlayerSettings(
    navController: NavController,
    scrollBehavior: TopAppBarScrollBehavior,
) {
    val coroutineScope = rememberCoroutineScope()
    val playerConnection = LocalPlayerConnection.current

    // Moved off the Experimental screen. How many queues the app keeps is a fact about the
    // player, and it was never an experiment.
    val (maxQueues, onMaxQueuesChange) = rememberPreference(MaxQueuesKey, defaultValue = 19)
    var showMaxQueuesDialog by remember { mutableStateOf(false) }
    val (audioDecoder, onAudioDecoderChange) = rememberPreference(
        key = AudioDecoderKey,
        defaultValue = DefaultRenderersFactory.EXTENSION_RENDERER_MODE_OFF
    )
    val (keepAlive, onKeepAliveChange) = rememberPreference(key = KeepAliveKey, defaultValue = false)
    val (persistentQueue, onPersistentQueueChange) = rememberPreference(key = PersistentQueueKey, defaultValue = true)
    val (resumeOnLaunch, onResumeOnLaunchChange) =
        rememberPreference(key = ResumePlaybackOnLaunchKey, defaultValue = false)
    val (stopMusicOnTaskClear, onStopMusicOnTaskClearChange) = rememberPreference(
        key = StopMusicOnTaskClearKey,
        defaultValue = true
    )

    ColumnWithContentPadding(
        modifier = Modifier.fillMaxHeight(),
        columnModifier = Modifier
            .padding(horizontal = 16.dp)
    ) {
        PreferenceGroupTitle(
            title = stringResource(R.string.grp_general)
        )
        ElevatedCard(
            modifier = Modifier.fillMaxWidth()
        ) {
            PlayerGeneralFrag()
        }
        Spacer(modifier = Modifier.height(16.dp))

        PreferenceGroupTitle(
            title = stringResource(R.string.grp_audio)
        )
        ElevatedCard(
            modifier = Modifier.fillMaxWidth()
        ) {
            AudioQualityFrag()
            SpatialAudioFrag()
        }
        Spacer(modifier = Modifier.height(16.dp))

        ElevatedCard(
            modifier = Modifier.fillMaxWidth()
        ) {
            AudioEffectsFrag()
        }
        Spacer(modifier = Modifier.height(16.dp))

        PreferenceGroupTitle(
            title = stringResource(R.string.grp_behavior)
        )

        ElevatedCard(
            modifier = Modifier.fillMaxWidth()
        ) {
            PlaybackBehaviourFrag()
        }

        SettingsClickToReveal(stringResource(R.string.advanced)) {
            ElevatedCard(
                modifier = Modifier.fillMaxWidth()
            ) {
                SwitchPreference(
                    title = { Text(stringResource(R.string.persistent_queue)) },
                    description = stringResource(R.string.persistent_queue_desc_ot),
                    icon = { Icon(Icons.AutoMirrored.Rounded.QueueMusic, null) },
                    checked = persistentQueue,
                    onCheckedChange = onPersistentQueueChange
                )
                // Only meaningful when there is a restored queue to start, so it follows the
                // setting that produces one and is disabled without it.
                SwitchPreference(
                    title = { Text(stringResource(R.string.resume_on_launch)) },
                    description = stringResource(R.string.resume_on_launch_desc),
                    icon = { Icon(Icons.Rounded.PlayArrow, null) },
                    checked = resumeOnLaunch,
                    onCheckedChange = onResumeOnLaunchChange,
                    isEnabled = persistentQueue,
                )
                PreferenceEntry(
                    title = { Text(stringResource(R.string.max_queues_title)) },
                    description = stringResource(R.string.max_queues_description),
                    icon = { Icon(Icons.Rounded.Queue, null) },
                    onClick = { showMaxQueuesDialog = true }
                )
            }
            Spacer(modifier = Modifier.height(16.dp))

            ElevatedCard(
                modifier = Modifier.fillMaxWidth()
            ) {
                if (ENABLE_FFMETADATAEX) {
                    ListPreference(
                        title = { Text(stringResource(R.string.audio_decoder_preference)) },
                        icon = { Icon(Icons.Rounded.AudioFile, null) },
                        selectedValue = audioDecoder,
                        onValueSelected = onAudioDecoderChange,
                        values = listOf(
                            DefaultRenderersFactory.EXTENSION_RENDERER_MODE_OFF,
                            DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON,
                            DefaultRenderersFactory.EXTENSION_RENDERER_MODE_PREFER,
                        ),
                        valueText = {
                            when (it) {
                                DefaultRenderersFactory.EXTENSION_RENDERER_MODE_OFF -> stringResource(R.string.audio_decoder_system_only)
                                DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON -> stringResource(R.string.audio_decoder_system_with_ffmpeg)
                                DefaultRenderersFactory.EXTENSION_RENDERER_MODE_PREFER -> stringResource(R.string.audio_decoder_ffmpeg_only)
                                else -> {stringResource(R.string.error_unknown)}
                            }
                        }
                    )
                    InfoLabel(stringResource(R.string.restart_to_apply_changes))
                }
                SwitchPreference(
                    title = { Text(stringResource(R.string.keep_alive_title)) },
                    description = stringResource(R.string.keep_alive_description),
                    icon = { Icon(Icons.Rounded.NoCell, null) },
                    checked = keepAlive,
                    onCheckedChange = {
                        if (it) {
                            onStopMusicOnTaskClearChange(false)
                        }
                        onKeepAliveChange(it)
                    }
                )
            }
        }
    }


    if (showMaxQueuesDialog) {
        CounterDialog(
            title = stringResource(R.string.max_queues_title),
            description = stringResource(R.string.max_queues_description),
            initialValue = maxQueues,
            upperBound = 30,
            lowerBound = 1,
            onDismiss = { showMaxQueuesDialog = false },
            onConfirm = {
                showMaxQueuesDialog = false
                coroutineScope.launch(Dispatchers.IO) {
                    onMaxQueuesChange(it)
                    delay(500)
                    // the queues get reloaded, but not cleared by the database
                    // this will allow the user to (immediately) revert any accidental changes
                    playerConnection?.service?.initQueue()
                }
            },
            onCancel = {
                showMaxQueuesDialog = false
            }
        )
    }

    TopAppBar(
        title = { Text(stringResource(R.string.player_and_audio)) },
        navigationIcon = {
            IconButton(
                onClick = navController::navigateUp,
                onLongClick = navController::backToMain
            ) {
                Icon(
                    Icons.AutoMirrored.Rounded.ArrowBack,
                    contentDescription = null
                )
            }
        },
        windowInsets = TopBarInsets,
        scrollBehavior = scrollBehavior
    )
}
