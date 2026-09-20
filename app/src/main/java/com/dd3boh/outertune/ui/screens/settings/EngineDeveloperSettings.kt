/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.ui.screens.settings

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarScrollBehavior
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavController
import com.dd3boh.outertune.R
import com.dd3boh.outertune.constants.EngineOverridesKey
import com.dd3boh.outertune.engine.EngineTuning
import com.dd3boh.outertune.ui.component.ColumnWithContentPadding
import androidx.compose.material3.Slider
import com.dd3boh.outertune.constants.HeadTrackingLeadKey
import com.dd3boh.outertune.constants.StageWidthKey
import com.dd3boh.outertune.playback.BinauralAudioProcessor
import com.dd3boh.outertune.ui.component.PreferenceEntry
import com.dd3boh.outertune.ui.component.PreferenceGroupTitle
import com.dd3boh.outertune.ui.component.button.IconButton
import com.dd3boh.outertune.ui.utils.backToMain
import com.dd3boh.outertune.utils.rememberPreference
import com.dd3boh.outertune.viewmodels.EngineDeveloperViewModel

/**
 * Every constant the engine is tuned by, with its default beside a field to change it, and the
 * collision report: which titles the version rule merges in this library, and how many related
 * edges still pair a song with one of its own versions. For the maintainer, not a promise to
 * anyone else; a change shows on the next build.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EngineDeveloperSettings(
    navController: NavController,
    scrollBehavior: TopAppBarScrollBehavior,
    viewModel: EngineDeveloperViewModel = hiltViewModel(),
) {
    val (overridesJson, onOverridesChange) = rememberPreference(EngineOverridesKey, defaultValue = "")
    val overrides = remember(overridesJson) { EngineTuning.parse(overridesJson) }
    val report by viewModel.report.collectAsState()
    LaunchedEffect(Unit) { viewModel.loadReport() }

    ColumnWithContentPadding(
        modifier = Modifier.fillMaxHeight(),
        columnModifier = Modifier.padding(horizontal = 16.dp)
    ) {
        PreferenceGroupTitle(title = stringResource(R.string.engine_developer_params))
        PreferenceEntry(
            title = { Text(stringResource(R.string.engine_developer_reset)) },
            description = stringResource(R.string.engine_developer_reset_description, overrides.size),
            onClick = { onOverridesChange("") },
        )
        EngineTuning.entries.forEach { t ->
            var text by remember(overridesJson, t.name) { mutableStateOf(overrides[t.name]?.toString() ?: "") }
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) {
                Text(
                    text = "${t.name}\n${t.description}\n${stringResource(R.string.engine_developer_default, t.default)}",
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(8.dp))
                OutlinedTextField(
                    value = text,
                    onValueChange = { new ->
                        text = new
                        val v = new.toDoubleOrNull()
                        val next = overrides.toMutableMap()
                        if (v == null) next.remove(t.name) else next[t.name] = v
                        onOverridesChange(EngineTuning.encode(next))
                    },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier.width(110.dp),
                )
            }
        }
        Spacer(Modifier.height(16.dp))

        // A tuning knob, not a feature, which is why it lives here rather than in the audio
        // settings. Judging a soundstage means moving it while listening, and that cannot be done
        // by rebuilding the app between guesses.
        PreferenceGroupTitle(title = stringResource(R.string.stage_width))
        val (stageWidth, onStageWidthChange) = rememberPreference(
            StageWidthKey,
            defaultValue = BinauralAudioProcessor.DEFAULT_STAGE_WIDTH.toInt(),
        )
        Text(
            stringResource(R.string.stage_width_value, stageWidth),
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(horizontal = 16.dp),
        )
        Slider(
            value = stageWidth.toFloat(),
            onValueChange = { onStageWidthChange(it.toInt()) },
            valueRange = BinauralAudioProcessor.MIN_STAGE_WIDTH..BinauralAudioProcessor.MAX_STAGE_WIDTH,
            modifier = Modifier.padding(horizontal = 16.dp),
        )
        Text(
            stringResource(R.string.stage_width_description),
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(horizontal = 16.dp),
        )
        Spacer(Modifier.height(16.dp))

        // The delay between rendering a sample and hearing it is mostly whichever Bluetooth codec
        // got negotiated, and the platform reports no latency for that route, so this cannot be
        // measured from inside the app. Tuned by ear once against the hardware in use.
        PreferenceGroupTitle(title = stringResource(R.string.head_tracking_lead))
        val (lead, onLeadChange) = rememberPreference(HeadTrackingLeadKey, defaultValue = 260)
        Text(
            stringResource(R.string.head_tracking_lead_value, lead),
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(horizontal = 16.dp),
        )
        Slider(
            value = lead.toFloat(),
            onValueChange = { onLeadChange(it.toInt()) },
            valueRange = 0f..500f,
            modifier = Modifier.padding(horizontal = 16.dp),
        )
        Text(
            stringResource(R.string.head_tracking_lead_description),
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(horizontal = 16.dp),
        )
        Spacer(Modifier.height(16.dp))

        PreferenceGroupTitle(title = stringResource(R.string.engine_developer_collisions))
        val r = report
        if (r == null) {
            Text(stringResource(R.string.engine_developer_counting), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(16.dp))
        } else {
            Text(
                text = stringResource(R.string.engine_developer_collisions_summary, r.songs, r.multiMemberGroups, r.legacyVersionEdges),
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )
            r.groups.forEach { g ->
                PreferenceEntry(
                    title = { Text(g.base.ifBlank { "?" }) },
                    description = g.titles.joinToString("\n"),
                    onClick = null,
                )
            }
        }
        Spacer(Modifier.height(24.dp))
    }

    TopAppBar(
        title = { Text(stringResource(R.string.engine_developer)) },
        navigationIcon = {
            IconButton(
                onClick = navController::navigateUp,
                onLongClick = navController::backToMain
            ) {
                Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = null)
            }
        },
        scrollBehavior = scrollBehavior
    )
}
