/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 *
 * For any other attributions, refer to the git commit history
 */
package com.dd3boh.outertune.ui.screens.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Badge
import androidx.compose.material.icons.rounded.Timer
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarScrollBehavior
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import com.dd3boh.outertune.R
import com.dd3boh.outertune.constants.TogetherCatchUpKey
import com.dd3boh.outertune.constants.TogetherDelayKey
import com.dd3boh.outertune.constants.TogetherShowTimingKey
import com.dd3boh.outertune.ui.component.SwitchPreference
import com.dd3boh.outertune.constants.TogetherNameKey
import com.dd3boh.outertune.constants.TogetherToleranceKey
import com.dd3boh.outertune.together.SyncLock
import com.dd3boh.outertune.together.TogetherTuning
import com.dd3boh.outertune.ui.component.ColumnWithContentPadding
import com.dd3boh.outertune.ui.component.EditTextPreference
import com.dd3boh.outertune.ui.component.FloatingTopBar
import com.dd3boh.outertune.ui.component.PreferenceGroupTitle
import com.dd3boh.outertune.ui.dialog.phoneName
import com.dd3boh.outertune.utils.rememberPreference
import kotlin.math.roundToInt

/**
 * Listening rooms, set in one place (his word of 17 Oct 2026: "a category for it to essentially
 * adjust a bunch of settings regarding the listen together feature, including the sensitivity for
 * the adjusting"). A room under way reads each of them as it changes (TogetherSession), so a
 * slider moved in the middle of one takes effect at once.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ListenTogetherSettings(
    navController: NavController,
    scrollBehavior: TopAppBarScrollBehavior,
) {
    val context = LocalContext.current
    val (name, onNameChange) = rememberPreference(TogetherNameKey, defaultValue = "")
    val (tolerance, onToleranceChange) = rememberPreference(TogetherToleranceKey, defaultValue = SyncLock.DEFAULT_TOLERANCE_MS.toInt())
    val (catchUp, onCatchUpChange) = rememberPreference(TogetherCatchUpKey, defaultValue = TogetherTuning.DEFAULT_CATCH_UP_PERCENT)
    val (delay, onDelayChange) = rememberPreference(TogetherDelayKey, defaultValue = 0)
    val (showTiming, onShowTimingChange) = rememberPreference(TogetherShowTimingKey, defaultValue = false)

    ColumnWithContentPadding(
        modifier = Modifier.fillMaxHeight(),
        columnModifier = Modifier.padding(horizontal = 16.dp)
    ) {
        PreferenceGroupTitle(title = stringResource(R.string.together_settings_you))
        ElevatedCard(modifier = Modifier.fillMaxWidth()) {
            // Left empty, the phone's own name is used, as it was before there was a setting.
            EditTextPreference(
                modifier = Modifier.settingRow(R.string.together_name_setting),
                title = { Text(stringResource(R.string.together_name_setting)) },
                icon = { Icon(Icons.Rounded.Badge, null) },
                value = name.ifBlank { phoneName(context) },
                onValueChange = { onNameChange(it.trim().take(NAME_MAX)) },
                isInputValid = { it.trim().length <= NAME_MAX },
            )
        }

        PreferenceGroupTitle(title = stringResource(R.string.together_settings_step))
        ElevatedCard(modifier = Modifier.fillMaxWidth()) {
            SliderSetting(
                title = stringResource(R.string.together_tolerance),
                shown = { stringResource(R.string.together_tolerance_value, it) },
                explanation = stringResource(R.string.together_tolerance_explain),
                value = tolerance,
                range = SyncLock.MIN_TOLERANCE_MS.toInt()..SyncLock.MAX_TOLERANCE_MS.toInt(),
                step = 10,
                onDone = onToleranceChange,
            )
            HorizontalDivider(Modifier.padding(horizontal = 16.dp))
            SliderSetting(
                title = stringResource(R.string.together_catch_up_speed),
                shown = { stringResource(R.string.together_catch_up_value, it) },
                explanation = stringResource(R.string.together_catch_up_explain),
                value = catchUp,
                range = TogetherTuning.MIN_CATCH_UP_PERCENT..TogetherTuning.MAX_CATCH_UP_PERCENT,
                step = 1,
                onDone = onCatchUpChange,
            )
            HorizontalDivider(Modifier.padding(horizontal = 16.dp))
            SliderSetting(
                title = stringResource(R.string.together_delay),
                shown = { stringResource(R.string.together_delay_value, it) },
                explanation = stringResource(R.string.together_delay_explain),
                value = delay,
                range = 0..TogetherTuning.MAX_DELAY_MS.toInt(),
                step = 10,
                onDone = onDelayChange,
            )
            HorizontalDivider(Modifier.padding(horizontal = 16.dp))
            SwitchPreference(
                modifier = Modifier.settingRow(R.string.together_show_timing),
                title = { Text(stringResource(R.string.together_show_timing)) },
                description = stringResource(R.string.together_show_timing_description),
                icon = { Icon(Icons.Rounded.Timer, null) },
                checked = showTiming,
                onCheckedChange = onShowTimingChange,
            )
        }
        Spacer(Modifier.height(16.dp))
    }

    FloatingTopBar(title = stringResource(R.string.together_title), navController = navController)
}

/**
 * A setting that is a number on a slider: its name, the value as it moves, and two short
 * sentences on what it does. Saved when it is let go rather than on every step of a drag, since a
 * room under way takes every saved value.
 */
@Composable
private fun SliderSetting(
    title: String,
    shown: @Composable (Int) -> String,
    explanation: String,
    value: Int,
    range: IntRange,
    step: Int,
    onDone: (Int) -> Unit,
) {
    var moving by remember(value) { mutableFloatStateOf(value.coerceIn(range).toFloat()) }
    Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
        Text(title, style = MaterialTheme.typography.titleMedium)
        Text(
            shown(moving.roundToInt()),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.primary,
        )
        Slider(
            value = moving,
            onValueChange = { moving = it },
            onValueChangeFinished = { onDone(moving.roundToInt()) },
            valueRange = range.first.toFloat()..range.last.toFloat(),
            steps = (range.last - range.first) / step - 1,
        )
        Text(
            explanation,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** As long as a room shows a name: the phone's own name is cut there too. */
private const val NAME_MAX = 40
