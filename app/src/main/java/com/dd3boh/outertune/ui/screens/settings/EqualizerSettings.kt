/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.ui.screens.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Equalizer
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarScrollBehavior
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import com.dd3boh.outertune.R
import com.dd3boh.outertune.constants.EqualizerEnabledKey
import com.dd3boh.outertune.constants.EqualizerGainsKey
import com.dd3boh.outertune.playback.EqualizerAudioProcessor
import com.dd3boh.outertune.playback.EqualizerPreset
import com.dd3boh.outertune.ui.component.ColumnWithContentPadding
import com.dd3boh.outertune.ui.component.FloatingTopBar
import com.dd3boh.outertune.ui.component.PreferenceEntry
import com.dd3boh.outertune.ui.component.SwitchPreference
import com.dd3boh.outertune.ui.dialog.InfoLabel
import com.dd3boh.outertune.utils.rememberPreference
import kotlin.math.roundToInt

/**
 * The equalizer in the app (Unreleased.EQUALIZER): on or off, a ready-made curve or the
 * listener's own, ten bands. Every change is heard at once, the processor reads its settings
 * once a buffer.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun EqualizerSettings(
    navController: NavController,
    scrollBehavior: TopAppBarScrollBehavior,
) {
    val (enabled, onEnabled) = rememberPreference(EqualizerEnabledKey, defaultValue = false)
    val (kept, onKept) = rememberPreference(EqualizerGainsKey, defaultValue = "")
    val gains = EqualizerPreset.parse(kept)
    val preset = EqualizerPreset.of(gains)

    ColumnWithContentPadding(
        modifier = Modifier.fillMaxHeight(),
        columnModifier = Modifier.padding(horizontal = 16.dp)
    ) {
        ElevatedCard(modifier = Modifier.fillMaxWidth()) {
            SwitchPreference(
                title = { Text(stringResource(R.string.equalizer)) },
                description = stringResource(R.string.equalizer_description),
                icon = { Icon(Icons.Rounded.Equalizer, null) },
                checked = enabled,
                onCheckedChange = onEnabled,
            )
        }
        Spacer(Modifier.height(16.dp))

        ElevatedCard(modifier = Modifier.fillMaxWidth()) {
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            ) {
                for (p in EqualizerPreset.entries) {
                    FilterChip(
                        selected = p == preset,
                        enabled = enabled,
                        onClick = { onKept(EqualizerPreset.format(p.gainsDb)) },
                        label = { Text(stringResource(presetName(p))) },
                    )
                }
            }
            for ((band, hz) in EqualizerAudioProcessor.BANDS.withIndex()) {
                val label = bandLabel(hz)
                val value = gains[band]
                val valueText = "%+d dB".format(value.roundToInt())
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                ) {
                    Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.width(64.dp))
                    Slider(
                        value = value,
                        enabled = enabled,
                        onValueChange = { moved ->
                            val next = gains.toMutableList().also { it[band] = moved.roundToInt().toFloat() }
                            onKept(EqualizerPreset.format(next))
                        },
                        valueRange = -EqualizerAudioProcessor.MAX_DB..EqualizerAudioProcessor.MAX_DB,
                        steps = (EqualizerAudioProcessor.MAX_DB * 2).toInt() - 1,
                        modifier = Modifier.weight(1f).semantics { contentDescription = "$label, $valueText" },
                    )
                    Text(valueText, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.width(56.dp).padding(start = 8.dp))
                }
            }
            Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp), horizontalArrangement = Arrangement.End) {
                TextButton(enabled = enabled && preset != EqualizerPreset.FLAT, onClick = { onKept(EqualizerPreset.format(EqualizerPreset.FLAT.gainsDb)) }) {
                    Text(stringResource(R.string.reset))
                }
            }
        }
        InfoLabel(stringResource(R.string.equalizer_note))
        Spacer(Modifier.height(16.dp))
    }

    FloatingTopBar(title = stringResource(R.string.equalizer), navController = navController)
}

/** The row under Player and audio that opens the equalizer, saying what it is set to. */
@Composable
fun EqualizerEntry(navController: NavController) {
    val (enabled) = rememberPreference(EqualizerEnabledKey, defaultValue = false)
    val (kept) = rememberPreference(EqualizerGainsKey, defaultValue = "")
    val preset = EqualizerPreset.of(EqualizerPreset.parse(kept))
    PreferenceEntry(
        title = { Text(stringResource(R.string.equalizer)) },
        description = when {
            !enabled -> stringResource(R.string.equalizer_off)
            preset != null -> stringResource(presetName(preset))
            else -> stringResource(R.string.equalizer_custom)
        },
        icon = { Icon(Icons.Rounded.Equalizer, null) },
        onClick = { navController.navigate("settings/player/equalizer") },
    )
}

private fun presetName(p: EqualizerPreset): Int = when (p) {
    EqualizerPreset.FLAT -> R.string.equalizer_preset_flat
    EqualizerPreset.MORE_BASS -> R.string.equalizer_preset_bass
    EqualizerPreset.MORE_TREBLE -> R.string.equalizer_preset_treble
    EqualizerPreset.VOCALS -> R.string.equalizer_preset_vocals
    EqualizerPreset.LOUDNESS -> R.string.equalizer_preset_loudness
    EqualizerPreset.SMALL_SPEAKERS -> R.string.equalizer_preset_small_speakers
}

/** "31 Hz", "1 kHz", "16 kHz". */
private fun bandLabel(hz: Double): String =
    if (hz >= 1000) "${(hz / 1000).roundToInt()} kHz" else "${hz.roundToInt()} Hz"
