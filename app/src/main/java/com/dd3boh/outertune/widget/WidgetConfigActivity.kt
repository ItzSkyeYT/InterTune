/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.widget

import android.annotation.SuppressLint
import android.app.Activity
import android.appwidget.AppWidgetManager
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.MotionEvent
import android.widget.FrameLayout
import android.widget.RemoteViews
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.glance.appwidget.state.getAppWidgetState
import androidx.glance.appwidget.state.updateAppWidgetState
import androidx.glance.state.PreferencesGlanceStateDefinition
import androidx.lifecycle.lifecycleScope
import com.dd3boh.outertune.R
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

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
                Surface {
                    // Reopened from the launcher, this is an edit rather than a fresh question, so
                    // it opens on what the widget is already set to.
                    var initial by remember { mutableStateOf<WidgetSettings?>(null) }
                    LaunchedEffect(Unit) { initial = current() }
                    initial?.let { Config(it, widgetSize(), onDone = ::save) }
                }
            }
        }
    }

    /**
     * This widget's size on the home screen now, in dp, for the preview. A launcher gives a widget
     * its size as a range: the narrowest width with the tallest height is its portrait shape.
     * Null while the launcher has not said, which a Pixel does not until the widget is placed,
     * after this screen has closed.
     */
    private fun widgetSize(): IntSize? {
        val options = AppWidgetManager.getInstance(this).getAppWidgetOptions(appWidgetId)
        val landscape = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        val w = options.getInt(if (landscape) AppWidgetManager.OPTION_APPWIDGET_MAX_WIDTH else AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH)
        val h = options.getInt(if (landscape) AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT else AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT)
        return if (w > 0 && h > 0) IntSize(w, h) else null
    }

    /** What this widget is set to now, or the defaults when it has never been asked. */
    private suspend fun current(): WidgetSettings = runCatching {
        val glanceId = GlanceAppWidgetManager(this).getGlanceIdBy(appWidgetId)
        WidgetKeys.read(getAppWidgetState(this, PreferencesGlanceStateDefinition, glanceId))
    }.getOrDefault(WidgetSettings())

    @OptIn(DelicateCoroutinesApi::class)
    private fun save(settings: WidgetSettings) {
        lifecycleScope.launch {
            val appContext = applicationContext
            runCatching {
                val glanceId = GlanceAppWidgetManager(this@WidgetConfigActivity).getGlanceIdBy(appWidgetId)
                updateAppWidgetState(this@WidgetConfigActivity, glanceId) { prefs ->
                    WidgetKeys.write(prefs, settings)
                }
                MusicWidget().update(this@WidgetConfigActivity, glanceId)
            }
            // hydrate fetches artwork over the network and can take a while, so it runs after this
            // screen has said yes: waiting for it here would leave Done hanging, and Back or leaving
            // meanwhile would finish with the RESULT_CANCELED set in onCreate, which makes the
            // launcher remove the widget. GlobalScope because the work must outlive this activity;
            // hydrate ends in its own updateAll. Until then a new widget can show its rows empty,
            // since hydrate writes the snapshot once, after every row's artwork.
            GlobalScope.launch { runCatching { WidgetStore.hydrate(appContext) } }
            setResult(Activity.RESULT_OK, Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId))
            finish()
        }
    }
}

@Composable
private fun Config(initial: WidgetSettings, size: IntSize?, onDone: (WidgetSettings) -> Unit) {
    var s by remember { mutableStateOf(initial) }
    Column(modifier = Modifier.fillMaxWidth()) {
        // The widget itself, above everything that changes it, so each choice shows as it is made.
        Preview(s, size, modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp))

        Column(
            modifier = Modifier
                .weight(1f, fill = false)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp)
                .padding(bottom = 12.dp),
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
        }

        HorizontalDivider()
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 12.dp),
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

/** The tallest the preview is drawn, so a big widget leaves the settings room on a small screen. */
private val PreviewMaxHeight = 220.dp

/** A four by two as most launchers draw one, for a widget whose size is not known yet. */
private val UsualSize = IntSize(360, 170)

/**
 * The widget on a patch of colour standing in for a home screen, with a line saying what it is:
 * this widget at its size, or, before the launcher has said what that is, a usual one.
 */
@Composable
private fun Preview(settings: WidgetSettings, known: IntSize?, modifier: Modifier = Modifier) {
    val size = known ?: UsualSize
    val colors = MaterialTheme.colorScheme
    Column(modifier = modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(24.dp))
                .background(Brush.linearGradient(listOf(colors.primaryContainer, colors.tertiaryContainer)))
                .padding(16.dp),
            contentAlignment = Alignment.Center,
        ) {
            LiveWidget(settings, size)
        }
        Text(
            stringResource(if (known != null) R.string.widget_config_preview_hint else R.string.widget_config_preview_hint_guess),
            style = MaterialTheme.typography.bodySmall,
            color = colors.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 8.dp, start = 8.dp, end = 8.dp),
        )
    }
}

/**
 * The widget as the launcher will draw it, redrawn as the settings change: Glance composes the
 * same views it sends the launcher, from the same snapshot, and they are inflated here. Scaled
 * down, never up, when the widget is bigger than the room.
 */
@Composable
private fun LiveWidget(settings: WidgetSettings, size: IntSize) {
    val context = LocalContext.current
    var views by remember { mutableStateOf<RemoteViews?>(null) }
    LaunchedEffect(settings) {
        // A slider dragged end to end asks for a drawing at every step; only the last is wanted.
        delay(60)
        views = runCatching { renderWidget(context, settings, size.width, size.height) }
            .onFailure { Log.w("WidgetConfig", "Could not draw the preview", it) }
            .getOrNull()
    }
    BoxWithConstraints(
        modifier = Modifier.fillMaxWidth().heightIn(max = PreviewMaxHeight),
        contentAlignment = Alignment.Center,
    ) {
        val scale = minOf(1f, maxWidth / size.width.dp, maxHeight / size.height.dp)
        AndroidView(
            factory = { TouchlessFrame(it) },
            update = { frame ->
                frame.removeAllViews()
                val drawn = views ?: return@AndroidView
                runCatching {
                    val density = frame.resources.displayMetrics.density
                    val widget = drawn.apply(frame.context, frame)
                    widget.pivotX = 0f
                    widget.pivotY = 0f
                    widget.scaleX = scale
                    widget.scaleY = scale
                    frame.addView(
                        widget,
                        FrameLayout.LayoutParams((size.width * density).roundToInt(), (size.height * density).roundToInt()),
                    )
                }.onFailure { Log.w("WidgetConfig", "Could not show the preview", it) }
            },
            modifier = Modifier.size(size.width.dp * scale, size.height.dp * scale),
        )
    }
}

/** Holds the preview and keeps every touch from it: a tap on its play button would really play. */
private class TouchlessFrame(context: Context) : FrameLayout(context) {
    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean = true

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean = true
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
