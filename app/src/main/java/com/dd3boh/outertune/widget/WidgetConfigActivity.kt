/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.widget

import android.app.Activity
import android.appwidget.AppWidgetManager
import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.glance.appwidget.state.getAppWidgetState
import androidx.glance.appwidget.state.updateAppWidgetState
import androidx.glance.state.PreferencesGlanceStateDefinition
import androidx.lifecycle.lifecycleScope
import com.dd3boh.outertune.R
import kotlinx.coroutines.launch

/**
 * What this widget holds and how it looks, asked when it is dropped on the home screen and
 * answerable again from the launcher's own edit button.
 *
 * Every choice belongs to the widget, not to the app: two widgets side by side can be a player in
 * the cover's own colour and a plain list of forgotten favourites. That is why nothing here writes
 * to the app's settings; it writes to this widget's Glance state and redraws that widget alone.
 */
class WidgetConfigActivity : ComponentActivity() {

    private var appWidgetId = AppWidgetManager.INVALID_APPWIDGET_ID

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        appWidgetId = intent?.extras?.getInt(
            AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID
        ) ?: AppWidgetManager.INVALID_APPWIDGET_ID

        // Cancelled unless the person says otherwise: a widget whose configuration was abandoned
        // must not be left on the home screen.
        setResult(Activity.RESULT_CANCELED, Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId))
        if (appWidgetId == AppWidgetManager.INVALID_APPWIDGET_ID) {
            finish()
            return
        }

        setContent {
            val context = LocalContext.current
            val dark = isSystemInDarkTheme()
            val colors = when {
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
                    if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
                dark -> darkColorScheme()
                else -> lightColorScheme()
            }
            MaterialTheme(colorScheme = colors) {
                Surface(modifier = Modifier.fillMaxSize()) {
                    // Reopened from the launcher, this is an edit rather than a fresh question, so
                    // it opens on what the widget is already set to.
                    var initial by remember { mutableStateOf<WidgetSettings?>(null) }
                    LaunchedEffect(Unit) { initial = current() }
                    initial?.let { Config(it, onDone = ::save) }
                }
            }
        }
    }

    /** What this widget is set to now, or the defaults when it has never been asked. */
    private suspend fun current(): WidgetSettings = runCatching {
        val glanceId = GlanceAppWidgetManager(this).getGlanceIdBy(appWidgetId)
        WidgetKeys.read(getAppWidgetState(this, PreferencesGlanceStateDefinition, glanceId))
    }.getOrDefault(WidgetSettings())

    private fun save(settings: WidgetSettings) {
        lifecycleScope.launch {
            runCatching {
                val glanceId = GlanceAppWidgetManager(this@WidgetConfigActivity).getGlanceIdBy(appWidgetId)
                updateAppWidgetState(this@WidgetConfigActivity, glanceId) { prefs ->
                    WidgetKeys.write(prefs, settings)
                }
                // Filled before it is first drawn, so a new widget is never an empty box.
                WidgetStore.hydrate(this@WidgetConfigActivity)
                MusicWidget().update(this@WidgetConfigActivity, glanceId)
            }
            setResult(Activity.RESULT_OK, Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId))
            finish()
        }
    }
}

@Composable
private fun Config(initial: WidgetSettings, onDone: (WidgetSettings) -> Unit) {
    var s by remember { mutableStateOf(initial) }
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp, vertical = 20.dp),
    ) {
        Section(stringResource(R.string.widget_config_shows))
        Choice(stringResource(R.string.widget_content_both), s.content == WidgetContent.BOTH) { s = s.copy(content = WidgetContent.BOTH) }
        Choice(stringResource(R.string.widget_content_now), s.content == WidgetContent.NOW_PLAYING) { s = s.copy(content = WidgetContent.NOW_PLAYING) }
        Choice(stringResource(R.string.widget_content_list), s.content == WidgetContent.LIST) { s = s.copy(content = WidgetContent.LIST) }

        if (s.content != WidgetContent.NOW_PLAYING) {
            Section(stringResource(R.string.widget_config_which_list))
            Choice(stringResource(R.string.quick_picks), s.list == WidgetList.QUICK_PICKS) { s = s.copy(list = WidgetList.QUICK_PICKS) }
            Choice(stringResource(R.string.forgotten_favorites), s.list == WidgetList.FORGOTTEN_FAVOURITES) { s = s.copy(list = WidgetList.FORGOTTEN_FAVOURITES) }
            Choice(stringResource(R.string.keep_listening), s.list == WidgetList.KEEP_LISTENING) { s = s.copy(list = WidgetList.KEEP_LISTENING) }
            Choice(stringResource(R.string.widget_list_recent), s.list == WidgetList.RECENT) { s = s.copy(list = WidgetList.RECENT) }

            Section(stringResource(R.string.widget_config_rows))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                Chip(stringResource(R.string.widget_rows_auto), s.maxRows == 0) { s = s.copy(maxRows = 0) }
                for (n in 1..WidgetLayout.MAX_PICKS) {
                    Chip(n.toString(), s.maxRows == n) { s = s.copy(maxRows = n) }
                }
            }
        }

        Section(stringResource(R.string.widget_config_background))
        Choice(stringResource(R.string.widget_background_system), s.background == WidgetBackground.SYSTEM) { s = s.copy(background = WidgetBackground.SYSTEM) }
        Choice(stringResource(R.string.widget_background_dark), s.background == WidgetBackground.DARK) { s = s.copy(background = WidgetBackground.DARK) }
        Choice(stringResource(R.string.widget_background_light), s.background == WidgetBackground.LIGHT) { s = s.copy(background = WidgetBackground.LIGHT) }
        Choice(stringResource(R.string.widget_background_artwork), s.background == WidgetBackground.ARTWORK) { s = s.copy(background = WidgetBackground.ARTWORK) }
        Choice(stringResource(R.string.widget_background_none), s.background == WidgetBackground.NONE) { s = s.copy(background = WidgetBackground.NONE) }

        if (s.background != WidgetBackground.NONE) {
            Row(modifier = Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.widget_config_opacity), style = MaterialTheme.typography.bodyLarge)
                Text(
                    "  ${s.opacity}%",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Slider(
                value = s.opacity.toFloat(),
                onValueChange = { s = s.copy(opacity = it.toInt()) },
                valueRange = 0f..100f,
                steps = 19,
            )
        }

        Section(stringResource(R.string.widget_config_buttons))
        Choice(stringResource(R.string.widget_buttons_all), s.buttons == WidgetButtons.ALL) { s = s.copy(buttons = WidgetButtons.ALL) }
        Choice(stringResource(R.string.widget_buttons_play), s.buttons == WidgetButtons.PLAY_ONLY) { s = s.copy(buttons = WidgetButtons.PLAY_ONLY) }
        Choice(stringResource(R.string.widget_buttons_none), s.buttons == WidgetButtons.NONE) { s = s.copy(buttons = WidgetButtons.NONE) }

        Section(stringResource(R.string.widget_config_text_size))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            Chip(stringResource(R.string.widget_text_small), s.textSize == WidgetTextSize.SMALL) { s = s.copy(textSize = WidgetTextSize.SMALL) }
            Chip(stringResource(R.string.widget_text_normal), s.textSize == WidgetTextSize.NORMAL) { s = s.copy(textSize = WidgetTextSize.NORMAL) }
            Chip(stringResource(R.string.widget_text_large), s.textSize == WidgetTextSize.LARGE) { s = s.copy(textSize = WidgetTextSize.LARGE) }
        }

        Section(stringResource(R.string.widget_config_show))
        Toggle(stringResource(R.string.widget_show_artwork), s.showArtwork) { s = s.copy(showArtwork = it) }
        Toggle(stringResource(R.string.widget_show_artist), s.showArtist) { s = s.copy(showArtist = it) }
        if (s.content != WidgetContent.NOW_PLAYING) {
            Toggle(stringResource(R.string.widget_show_heading), s.showHeading) { s = s.copy(showHeading = it) }
        }
        Toggle(stringResource(R.string.widget_rounded_corners), s.rounded) { s = s.copy(rounded = it) }

        HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp))
        Text(
            stringResource(R.string.widget_config_resize_hint),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 20.dp),
            horizontalArrangement = Arrangement.End,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(onClick = { s = WidgetSettings() }) { Text(stringResource(R.string.widget_config_reset)) }
            Button(onClick = { onDone(s) }, modifier = Modifier.padding(start = 8.dp)) {
                Text(stringResource(R.string.widget_config_done))
            }
        }
    }
}

@Composable
private fun Section(title: String) {
    Text(
        title,
        style = MaterialTheme.typography.titleMedium,
        modifier = Modifier.padding(top = 20.dp, bottom = 4.dp),
    )
}

@Composable
private fun Choice(label: String, selected: Boolean, onSelect: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .selectable(selected = selected, onClick = onSelect)
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = onSelect)
        Text(label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(start = 8.dp))
    }
}

@Composable
private fun Toggle(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
private fun Chip(label: String, selected: Boolean, onSelect: () -> Unit) {
    FilterChip(selected = selected, onClick = onSelect, label = { Text(label) })
}
