/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.ui.screens.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Equalizer
import androidx.compose.material.icons.rounded.Headphones
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarScrollBehavior
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import com.dd3boh.outertune.R
import com.dd3boh.outertune.constants.EqualizerEnabledKey
import com.dd3boh.outertune.constants.EqualizerGainsKey
import com.dd3boh.outertune.constants.EqualizerHeadphoneKey
import com.dd3boh.outertune.playback.AutoEq
import com.dd3boh.outertune.playback.EqualizerAudioProcessor
import com.dd3boh.outertune.playback.EqualizerPreset
import com.dd3boh.outertune.ui.component.ColumnWithContentPadding
import com.dd3boh.outertune.ui.component.EqualizerGraph
import com.dd3boh.outertune.ui.component.FloatingTopBar
import com.dd3boh.outertune.ui.component.PreferenceEntry
import com.dd3boh.outertune.ui.component.SwitchPreference
import com.dd3boh.outertune.ui.dialog.DefaultDialog
import com.dd3boh.outertune.ui.dialog.InfoLabel
import com.dd3boh.outertune.ui.screens.walkthrough.Tour
import com.dd3boh.outertune.ui.screens.walkthrough.tourTarget
import com.dd3boh.outertune.utils.rememberPreference
import kotlinx.coroutines.launch
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
    val (headphone, onHeadphone) = rememberPreference(EqualizerHeadphoneKey, defaultValue = "")
    val chosen = AutoEq.chosen(headphone, EqualizerPreset.format(gains))
    var picking by remember { mutableStateOf(false) }

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
            EqualizerGraph(
                gainsDb = gains,
                enabled = enabled,
                onBand = { band, db -> onKept(EqualizerPreset.format(gains.toMutableList().also { it[band] = db })) },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 8.dp),
            )
            Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp), horizontalArrangement = Arrangement.End) {
                TextButton(enabled = enabled && preset != EqualizerPreset.FLAT, onClick = { onKept(EqualizerPreset.format(EqualizerPreset.FLAT.gainsDb)) }) {
                    Text(stringResource(R.string.reset))
                }
            }
        }
        InfoLabel(stringResource(R.string.equalizer_note))
        Spacer(Modifier.height(16.dp))

        ElevatedCard(modifier = Modifier.fillMaxWidth()) {
            PreferenceEntry(
                title = { Text(stringResource(R.string.equalizer_headphones)) },
                description = chosen?.let { (name, source) -> stringResource(R.string.equalizer_headphones_set, name, source) }
                    ?: stringResource(R.string.equalizer_headphones_pick),
                icon = { Icon(Icons.Rounded.Headphones, null) },
                isEnabled = enabled,
                onClick = { picking = true },
            )
        }
        Spacer(Modifier.height(16.dp))
    }

    if (picking) {
        HeadphonePicker(
            onDismiss = { picking = false },
            onPicked = { model, db ->
                val curve = EqualizerPreset.format(db)
                onKept(curve)
                onHeadphone(AutoEq.keep(model, curve))
                picking = false
            },
        )
    }

    FloatingTopBar(title = stringResource(R.string.equalizer), navController = navController)
}

/**
 * AutoEq's models to search, and the one picked fetched for its ten band curve. The list is
 * fetched when this opens (kept a month); nothing goes out before.
 */
@Composable
private fun HeadphonePicker(
    onDismiss: () -> Unit,
    onPicked: (AutoEq.Model, List<Float>) -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var models by remember { mutableStateOf<List<AutoEq.Model>?>(null) }
    var unreachable by remember { mutableStateOf(false) }
    var query by rememberSaveable { mutableStateOf("") }
    var fetching by remember { mutableStateOf<AutoEq.Model?>(null) }
    var problem by remember { mutableStateOf<Int?>(null) }

    LaunchedEffect(Unit) {
        models = AutoEq.models(context)
        unreachable = models == null
    }

    DefaultDialog(
        onDismiss = onDismiss,
        icon = { Icon(Icons.Rounded.Headphones, null) },
        title = { Text(stringResource(R.string.equalizer_headphones)) },
        horizontalAlignment = Alignment.Start,
        buttons = {
            TextButton(onClick = onDismiss) { Text(stringResource(android.R.string.cancel)) }
        },
    ) {
        OutlinedTextField(
            value = query,
            onValueChange = { query = it; problem = null },
            singleLine = true,
            placeholder = { Text(stringResource(R.string.equalizer_headphones_search)) },
            leadingIcon = { Icon(Icons.Rounded.Search, null) },
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        )
        val list = models
        val note = when {
            unreachable -> R.string.equalizer_headphones_offline
            list == null -> R.string.equalizer_headphones_loading
            else -> problem
        }
        if (list == null && !unreachable) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                Spacer(Modifier.size(12.dp))
                Text(stringResource(R.string.equalizer_headphones_loading), style = MaterialTheme.typography.bodyMedium)
            }
        } else if (note != null) {
            Text(
                stringResource(note),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )
        }
        if (list != null) {
            val found = remember(list, query) { AutoEq.search(list, query) }
            if (query.isNotBlank() && found.isEmpty()) {
                Text(
                    stringResource(R.string.equalizer_headphones_none_found),
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                )
            }
            LazyColumn(modifier = Modifier.heightIn(max = 360.dp)) {
                items(found, key = { it.path }) { model ->
                    ListItem(
                        headlineContent = { Text(model.name) },
                        supportingContent = { Text(listOfNotNull(model.source, model.rig).joinToString(", ")) },
                        trailingContent = if (fetching == model) {
                            { CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp) }
                        } else null,
                        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                        modifier = Modifier.clickable(enabled = fetching == null) {
                            fetching = model
                            problem = null
                            scope.launch {
                                val curve = AutoEq.curve(model)
                                fetching = null
                                when (curve) {
                                    is AutoEq.Curve.Gains -> onPicked(model, curve.db)
                                    AutoEq.Curve.NotTenBands -> problem = R.string.equalizer_headphones_no_curve
                                    AutoEq.Curve.Unreachable -> problem = R.string.equalizer_headphones_offline
                                }
                            }
                        },
                    )
                }
            }
        }
    }
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
        modifier = Modifier.tourTarget(Tour.SETTING_EQUALIZER),
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
