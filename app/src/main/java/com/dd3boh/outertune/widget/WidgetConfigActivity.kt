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
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.RemoteViews
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
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
import androidx.compose.material3.contentColorFor
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
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.graphics.drawable.toDrawable
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.glance.appwidget.state.getAppWidgetState
import androidx.glance.appwidget.state.updateAppWidgetState
import androidx.glance.state.PreferencesGlanceStateDefinition
import androidx.lifecycle.lifecycleScope
import com.dd3boh.outertune.R
import com.dd3boh.outertune.constants.Unreleased
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.roundToInt
import androidx.compose.foundation.selection.toggleable
import androidx.compose.ui.semantics.Role

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

        val onWallpaper = Unreleased.WIDGET_ON_WALLPAPER
        if (onWallpaper) showWallpaperBehind()

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
                // On the wallpaper this paints nothing: what is under the preview paints its own.
                // The words keep the colour they have on the surface either way.
                val surface = MaterialTheme.colorScheme.surface
                Surface(
                    color = if (onWallpaper) Color.Transparent else surface,
                    contentColor = contentColorFor(surface),
                ) {
                    // Reopened from the launcher, this is an edit rather than a fresh question, so
                    // it opens on what the widget is already set to.
                    var initial by remember { mutableStateOf<WidgetSettings?>(null) }
                    LaunchedEffect(Unit) { initial = current() }
                    initial?.let { Config(it, widgetSize(), onWallpaper, onDone = ::save) }
                }
            }
        }
    }

    /**
     * The home screen's own wallpaper behind this window, with nothing of the window's over it, so
     * a widget set to be see-through is judged against what it will sit on. The system draws the
     * wallpaper there: nothing is read from it, so no permission is asked, which reading it
     * through WallpaperManager would need. The dialog's panel and the dimming behind it both go,
     * or the preview would sit on a darkened wallpaper.
     */
    private fun showWallpaperBehind() {
        window.addFlags(WindowManager.LayoutParams.FLAG_SHOW_WALLPAPER)
        window.setBackgroundDrawable(android.graphics.Color.TRANSPARENT.toDrawable())
        window.setDimAmount(0f)
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
private fun Config(initial: WidgetSettings, size: IntSize?, onWallpaper: Boolean, onDone: (WidgetSettings) -> Unit) {
    var s by remember { mutableStateOf(initial) }
    // On the wallpaper the window paints nothing, so the settings paint a sheet of their own.
    val sheet = MaterialTheme.colorScheme.surface
    Column(modifier = Modifier.fillMaxWidth()) {
        // The widget itself, above everything that changes it, so each choice shows as it is made.
        Preview(s, size, onWallpaper, modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp))

        Column(
            modifier = Modifier
                .weight(1f, fill = false)
                .then(if (onWallpaper) Modifier.padding(top = 16.dp).background(sheet, SheetTop) else Modifier)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp)
                .padding(bottom = 12.dp),
        ) {
            // Under the preview there is only wallpaper, where small words cannot be read.
            if (onWallpaper) PreviewHint(known = size != null, modifier = Modifier.fillMaxWidth().padding(top = 12.dp))
            // The style first: every other choice here is about how that style looks.
            Section(stringResource(R.string.widget_config_style))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth().padding(top = 4.dp)) {
                for (style in WidgetStyle.entries) {
                    StyleChoice(style, s.style == style, Modifier.weight(1f)) { s = s.copy(style = style) }
                }
            }

            // Cover and Full cover are what is playing; a Shelf holds a list too, or a list alone.
            if (s.style == WidgetStyle.SHELF) {
                Section(stringResource(R.string.widget_config_shows))
                Choice(stringResource(R.string.widget_content_both), s.contentShown == WidgetContent.BOTH) { s = s.copy(content = WidgetContent.BOTH) }
                Choice(stringResource(R.string.widget_content_list), s.contentShown == WidgetContent.LIST) { s = s.copy(content = WidgetContent.LIST) }

                Section(stringResource(R.string.widget_config_which_list))
                Choice(stringResource(R.string.quick_picks), s.list == WidgetList.QUICK_PICKS) { s = s.copy(list = WidgetList.QUICK_PICKS) }
                Choice(stringResource(R.string.forgotten_favorites), s.list == WidgetList.FORGOTTEN_FAVOURITES) { s = s.copy(list = WidgetList.FORGOTTEN_FAVOURITES) }
                Choice(stringResource(R.string.keep_listening), s.list == WidgetList.KEEP_LISTENING) { s = s.copy(list = WidgetList.KEEP_LISTENING) }
                Choice(stringResource(R.string.widget_list_recent), s.list == WidgetList.RECENT) { s = s.copy(list = WidgetList.RECENT) }

                Section(stringResource(R.string.widget_config_rows))
                // Wraps: on a narrow dialog, in a plain Row, the chips past 3 were once squashed out
                // of reach. Rows of covers, so no more than a shelf ever has: a ceiling of four set
                // when they were rows of songs is the shelf's three.
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    Chip(stringResource(R.string.widget_rows_auto), s.maxRows == 0) { s = s.copy(maxRows = 0) }
                    for (n in 1..WidgetLayout.MAX_SHELF_ROWS) {
                        Chip(n.toString(), s.maxRows.coerceAtMost(WidgetLayout.MAX_SHELF_ROWS) == n) { s = s.copy(maxRows = n) }
                    }
                }
            }

            // Full cover is drawn on its cover, so it has no background to pick. The others start on
            // their own (the song's colours, or the launcher's for a Shelf) until one is picked.
            if (s.style != WidgetStyle.FULL_COVER) {
                val shown = s.backgroundShown
                Section(stringResource(R.string.widget_config_background))
                Choice(stringResource(R.string.widget_background_artwork), shown == WidgetBackground.ARTWORK) { s = s.copy(background = WidgetBackground.ARTWORK) }
                Choice(stringResource(R.string.widget_background_system), shown == WidgetBackground.SYSTEM) { s = s.copy(background = WidgetBackground.SYSTEM) }
                Choice(stringResource(R.string.widget_background_dark), shown == WidgetBackground.DARK) { s = s.copy(background = WidgetBackground.DARK) }
                Choice(stringResource(R.string.widget_background_light), shown == WidgetBackground.LIGHT) { s = s.copy(background = WidgetBackground.LIGHT) }
                Choice(stringResource(R.string.widget_background_none), shown == WidgetBackground.NONE) { s = s.copy(background = WidgetBackground.NONE) }

                if (shown != WidgetBackground.NONE) {
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
            }

            Section(stringResource(R.string.widget_config_buttons))
            Choice(stringResource(R.string.widget_buttons_all), s.buttons == WidgetButtons.ALL) { s = s.copy(buttons = WidgetButtons.ALL) }
            Choice(stringResource(R.string.widget_buttons_play), s.buttons == WidgetButtons.PLAY_ONLY) { s = s.copy(buttons = WidgetButtons.PLAY_ONLY) }
            Choice(stringResource(R.string.widget_buttons_none), s.buttons == WidgetButtons.NONE) { s = s.copy(buttons = WidgetButtons.NONE) }
            // The heart goes beside previous and next, so it is offered only with them.
            if (s.buttons == WidgetButtons.ALL) {
                Toggle(stringResource(R.string.widget_show_like), s.showLike) { s = s.copy(showLike = it) }
            }

            Section(stringResource(R.string.widget_config_text_size))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                Chip(stringResource(R.string.widget_text_small), s.textSize == WidgetTextSize.SMALL) { s = s.copy(textSize = WidgetTextSize.SMALL) }
                Chip(stringResource(R.string.widget_text_normal), s.textSize == WidgetTextSize.NORMAL) { s = s.copy(textSize = WidgetTextSize.NORMAL) }
                Chip(stringResource(R.string.widget_text_large), s.textSize == WidgetTextSize.LARGE) { s = s.copy(textSize = WidgetTextSize.LARGE) }
            }

            Section(stringResource(R.string.widget_config_show))
            // Full cover and Shelf are made of covers: only Cover can be a plain player.
            if (s.style == WidgetStyle.COVER) {
                Toggle(stringResource(R.string.widget_show_artwork), s.showArtwork) { s = s.copy(showArtwork = it) }
            }
            Toggle(stringResource(R.string.widget_show_artist), s.showArtist) { s = s.copy(showArtist = it) }
            if (s.style == WidgetStyle.SHELF) {
                Toggle(stringResource(R.string.widget_show_heading), s.showHeading) { s = s.copy(showHeading = it) }
            }
            Toggle(stringResource(R.string.widget_rounded_corners), s.rounded) { s = s.copy(rounded = it) }
        }

        HorizontalDivider()
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .then(if (onWallpaper) Modifier.background(sheet, SheetBottom) else Modifier)
                .padding(horizontal = 24.dp, vertical = 12.dp),
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

/**
 * The sheet the settings sit on when the window itself paints nothing: its top under the preview,
 * its bottom under the buttons.
 */
private val SheetTop = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)
private val SheetBottom = RoundedCornerShape(bottomStart = 28.dp, bottomEnd = 28.dp)

/** The tallest the preview is drawn, so a big widget leaves the settings room on a small screen. */
private val PreviewMaxHeight = 220.dp

/** A four by two as most launchers draw one, for a widget whose size is not known yet. */
private val UsualSize = IntSize(360, 170)

/**
 * The widget on a patch of colour standing in for a home screen, with a line saying what it is:
 * this widget at its size, or, before the launcher has said what that is, a usual one. With
 * [onWallpaper] there is no patch and no line: the window lets the real wallpaper through behind
 * the widget, and the line is the first thing on the sheet under it.
 */
@Composable
private fun Preview(settings: WidgetSettings, known: IntSize?, onWallpaper: Boolean, modifier: Modifier = Modifier) {
    val size = known ?: UsualSize
    val colors = MaterialTheme.colorScheme
    Column(modifier = modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(24.dp))
                .then(
                    if (onWallpaper) Modifier
                    else Modifier.background(Brush.linearGradient(listOf(colors.primaryContainer, colors.tertiaryContainer)))
                )
                .padding(16.dp),
            contentAlignment = Alignment.Center,
        ) {
            LiveWidget(settings, size)
        }
        if (!onWallpaper) PreviewHint(known != null, modifier = Modifier.padding(top = 8.dp, start = 8.dp, end = 8.dp))
    }
}

/** The line that says what the preview is. */
@Composable
private fun PreviewHint(known: Boolean, modifier: Modifier = Modifier) {
    Text(
        stringResource(if (known) R.string.widget_config_preview_hint else R.string.widget_config_preview_hint_guess),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center,
        modifier = modifier,
    )
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
        // Never reached by a drawing that a newer change has cancelled: the preview keeps what it
        // shows until the newer drawing is there.
        views = drawnIfStillWanted { renderWidget(context, settings, size.width, size.height) }
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
            .selectable(selected = selected, role = Role.RadioButton, onClick = onSelect)
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // the row is the button, so a screen reader reads its words and whether it is chosen as one
        RadioButton(selected = selected, onClick = null)
        Text(label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(start = 8.dp))
    }
}

@Composable
private fun Toggle(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            // the row is the switch, so a screen reader reads its words and whether it is on as one
            .toggleable(value = checked, role = Role.Switch, onValueChange = onChange)
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = null)
    }
}

@Composable
private fun Chip(label: String, selected: Boolean, onSelect: () -> Unit) {
    FilterChip(selected = selected, onClick = onSelect, label = { Text(label) })
}

/** One style to pick: a small picture of it over its name, the card the button, outlined when chosen. */
@Composable
private fun StyleChoice(style: WidgetStyle, selected: Boolean, modifier: Modifier, onSelect: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(16.dp)
    Column(
        modifier = modifier
            .clip(shape)
            .border(if (selected) 2.dp else 1.dp, if (selected) colors.primary else colors.outlineVariant, shape)
            // the card is the button, so a screen reader reads its name and whether it is chosen as one
            .selectable(selected = selected, role = Role.RadioButton, onClick = onSelect)
            .padding(8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        StylePicture(style, Modifier.fillMaxWidth().aspectRatio(1.5f))
        Text(
            stringResource(
                when (style) {
                    WidgetStyle.COVER -> R.string.widget_style_cover
                    WidgetStyle.FULL_COVER -> R.string.widget_style_full_cover
                    WidgetStyle.SHELF -> R.string.widget_style_shelf
                }
            ),
            style = MaterialTheme.typography.labelLarge,
            color = if (selected) colors.primary else colors.onSurface,
            textAlign = TextAlign.Center,
            maxLines = 2,
            modifier = Modifier.padding(top = 6.dp),
        )
    }
}

/**
 * A style in miniature, drawn from the approved mockup's colours rather than anybody's music: the
 * deep plum and the cover beside its words for Cover, the cover filling it with its pane of glass
 * for Full cover, the line of song over a row of covers for Shelf.
 */
@Composable
private fun StylePicture(style: WidgetStyle, modifier: Modifier) {
    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height
        val corner = CornerRadius(w * 0.1f)
        val cover = Brush.linearGradient(listOf(Color(0xFF7B3FA0), Color(0xFFE0647A), Color(0xFFF4A261)), Offset.Zero, Offset(w, h))
        val word = Color.White.copy(alpha = 0.9f)
        val soft = Color.White.copy(alpha = 0.55f)
        fun bar(x: Float, y: Float, length: Float, colour: Color, thick: Float = h * 0.07f) =
            drawRoundRect(colour, Offset(x, y), Size(length, thick), CornerRadius(thick / 2))
        when (style) {
            WidgetStyle.COVER -> {
                drawRoundRect(Color(0xFF3A2150), cornerRadius = corner)
                val side = h * 0.72f
                val pad = (h - side) / 2
                drawRoundRect(
                    Brush.linearGradient(listOf(Color(0xFF7B3FA0), Color(0xFFE0647A), Color(0xFFF4A261)), Offset(pad, pad), Offset(pad + side, pad + side)),
                    Offset(pad, pad), Size(side, side), CornerRadius(side * 0.16f),
                )
                val x = pad * 2 + side
                bar(x, pad + h * 0.04f, w - x - pad * 1.4f, word)
                bar(x, pad + h * 0.16f, (w - x - pad) * 0.6f, soft, h * 0.055f)
                val r = h * 0.1f
                drawCircle(soft, r * 0.55f, Offset(x + r * 0.6f, pad + side - r))
                drawCircle(Color.White, r, Offset(x + r * 2.6f, pad + side - r))
                drawCircle(soft, r * 0.55f, Offset(x + r * 4.6f, pad + side - r))
            }
            WidgetStyle.FULL_COVER -> {
                drawRoundRect(cover, cornerRadius = corner)
                drawRoundRect(
                    Brush.verticalGradient(listOf(Color.Transparent, Color(0xC0140A1E)), startY = h * 0.35f, endY = h),
                    cornerRadius = corner,
                )
                bar(w * 0.1f, h * 0.5f, w * 0.55f, word)
                val pillH = h * 0.22f
                val pillW = w * 0.5f
                val top = h - pillH - h * 0.1f
                val left = (w - pillW) / 2
                drawRoundRect(Color.White.copy(alpha = 0.22f), Offset(left, top), Size(pillW, pillH), CornerRadius(pillH / 2))
                drawRoundRect(Color.White.copy(alpha = 0.4f), Offset(left, top), Size(pillW, pillH), CornerRadius(pillH / 2), style = Stroke(1.dp.toPx()))
                for (i in 0..2) drawCircle(Color.White, pillH * (if (i == 1) 0.26f else 0.17f), Offset(left + pillW * (0.25f + 0.25f * i), top + pillH / 2))
            }
            WidgetStyle.SHELF -> {
                drawRoundRect(Color(0xFF1F2330), cornerRadius = corner)
                val pad = h * 0.1f
                val small = h * 0.26f
                drawRoundRect(cover, Offset(pad, pad), Size(small, small), CornerRadius(small * 0.2f))
                bar(pad * 2 + small, pad + small * 0.12f, w * 0.38f, word)
                bar(pad * 2 + small, pad + small * 0.58f, w * 0.24f, soft, h * 0.055f)
                val shelf = listOf(Color(0xFF0F6E56) to Color(0xFF5DCAA5), Color(0xFFD85A30) to Color(0xFFF0997B), Color(0xFF185FA5) to Color(0xFF85B7EB), Color(0xFF854F0B) to Color(0xFFEF9F27))
                val gap = w * 0.035f
                val tile = (w - 2 * pad - 3 * gap) / 4
                val y = h - pad - tile
                for ((i, pair) in shelf.withIndex()) {
                    val x = pad + i * (tile + gap)
                    drawRoundRect(Brush.linearGradient(listOf(pair.first, pair.second), Offset(x, y), Offset(x + tile, y + tile)), Offset(x, y), Size(tile, tile), CornerRadius(tile * 0.2f))
                }
            }
        }
    }
}
