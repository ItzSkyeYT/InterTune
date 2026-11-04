/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.widget

import android.annotation.SuppressLint
import android.app.Activity
import android.app.WallpaperColors
import android.app.WallpaperManager
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
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
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
import androidx.compose.runtime.collectAsState
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
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.lifecycle.lifecycleScope
import com.dd3boh.outertune.R
import com.dd3boh.outertune.constants.Unreleased
import com.dd3boh.outertune.ui.utils.Animations
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

        // Where the preview sits on the real wallpaper the window is a full one, which the system
        // draws the wallpaper under and the home screen not at all (Theme.InterTune.WidgetConfig.Wallpaper).
        // A build whose theme is still the dialog keeps the patch of colour: through a see-through
        // dialog, the home screen's own widgets and icons show behind the preview.
        val onWallpaper = Unreleased.WIDGET_ON_WALLPAPER && !themeFloats()
        if (onWallpaper) barsFollowWallpaper()

        // The window's compositions keep the app's pace: none with animations off (Animations).
        setContent(parent = Animations.recomposer(this)) {
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
                    initial?.let { settings ->
                        if (onWallpaper) {
                            // A full window: the settings sit in the middle, clear of the system bars,
                            // with the wallpaper around them, as the dialog had.
                            Box(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .windowInsetsPadding(WindowInsets.safeDrawing)
                                    .padding(horizontal = 16.dp),
                                contentAlignment = Alignment.Center,
                            ) {
                                Box(modifier = Modifier.widthIn(max = 560.dp)) {
                                    Config(settings, widgetSize(), onWallpaper = true, onDone = ::save)
                                }
                            }
                        } else {
                            Config(settings, widgetSize(), onWallpaper = false, onDone = ::save)
                        }
                    }
                }
            }
        }
    }

    /** Whether this screen's theme is the dialog. Read from the theme: the window knows only once its decor is made. */
    private fun themeFloats(): Boolean {
        val attrs = theme.obtainStyledAttributes(intArrayOf(android.R.attr.windowIsFloating))
        return try { attrs.getBoolean(0, false) } finally { attrs.recycle() }
    }

    /**
     * The status and navigation bars' icons dark on a light wallpaper and light on a dark one, as a
     * launcher has them, from the hint the system gives about the wallpaper. The system draws the
     * wallpaper itself and nothing is read from the picture, so no permission is asked.
     */
    private fun barsFollowWallpaper() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return
        val hints = WallpaperManager.getInstance(this).getWallpaperColors(WallpaperManager.FLAG_SYSTEM)?.colorHints ?: return
        val lightWallpaper = hints and WallpaperColors.HINT_SUPPORTS_DARK_TEXT != 0
        WindowCompat.getInsetsController(window, window.decorView).run {
            isAppearanceLightStatusBars = lightWallpaper
            isAppearanceLightNavigationBars = lightWallpaper
        }
    }

    /** This widget's size on the home screen now, for the preview: see [placedSize]. */
    private fun widgetSize(): IntSize? = placedSize(this, appWidgetId)

    /** What this widget is set to now, or the defaults when it has never been asked. */
    private suspend fun current(): WidgetSettings = WidgetStore.settingsOf(this, appWidgetId)

    @OptIn(DelicateCoroutinesApi::class)
    private fun save(settings: WidgetSettings) {
        lifecycleScope.launch {
            val appContext = applicationContext
            // The write a change in Settings > Widgets makes too: this widget's own state, and the
            // widget drawn again with it.
            WidgetStore.saveSettings(this@WidgetConfigActivity, appWidgetId, settings)
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

    companion object {
        /**
         * This screen for widget [appWidgetId], opened from inside the app (All options in Settings >
         * Widgets): an edit, as from the launcher's own button. In the app's task, so Done and Back
         * come back to the page. Nothing waits for its answer there, so leaving without Done takes
         * nothing off the home screen.
         */
        fun edit(context: Context, appWidgetId: Int): Intent {
            return Intent(context, WidgetConfigActivity::class.java).putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId)
        }
    }
}

/**
 * Widget [appWidgetId]'s size on the home screen now, in dp. A launcher gives a widget its size as
 * a range: the narrowest width with the tallest height is its portrait shape, the widest with the
 * shortest its shape on its side. Null while the launcher has not said, which a Pixel does not
 * until the widget is placed, after its setup sheet has closed.
 */
internal fun placedSize(context: Context, appWidgetId: Int): IntSize? {
    val options = AppWidgetManager.getInstance(context).getAppWidgetOptions(appWidgetId)
    val landscape = context.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
    val w = options.getInt(if (landscape) AppWidgetManager.OPTION_APPWIDGET_MAX_WIDTH else AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH)
    val h = options.getInt(if (landscape) AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT else AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT)
    return if (w > 0 && h > 0) IntSize(w, h) else null
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
                    StyleChoice(style, s.style == style, s.look, Modifier.weight(1f)) { s = s.copy(style = style) }
                }
            }

            // Cover and Full cover are what is playing; a Shelf holds a list too, or a list alone, in one of its looks.
            if (s.style == WidgetStyle.SHELF) {
                Section(stringResource(R.string.widget_config_look))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth().padding(top = 4.dp)) {
                    for (look in ShelfLook.entries) {
                        LookChoice(look, s.look == look, Modifier.weight(1f)) { s = s.copy(look = look) }
                    }
                }

                Section(stringResource(R.string.widget_config_shows))
                Choice(stringResource(R.string.widget_content_both), s.contentShown == WidgetContent.BOTH) { s = s.copy(content = WidgetContent.BOTH) }
                Choice(stringResource(R.string.widget_content_list), s.contentShown == WidgetContent.LIST) { s = s.copy(content = WidgetContent.LIST) }

                Section(stringResource(R.string.widget_config_which_list))
                Choice(stringResource(R.string.quick_picks), s.list == WidgetList.QUICK_PICKS) { s = s.copy(list = WidgetList.QUICK_PICKS) }
                Choice(stringResource(R.string.forgotten_favorites), s.list == WidgetList.FORGOTTEN_FAVOURITES) { s = s.copy(list = WidgetList.FORGOTTEN_FAVOURITES) }
                Choice(stringResource(R.string.keep_listening), s.list == WidgetList.KEEP_LISTENING) { s = s.copy(list = WidgetList.KEEP_LISTENING) }
                Choice(stringResource(R.string.widget_list_recent), s.list == WidgetList.RECENT) { s = s.copy(list = WidgetList.RECENT) }

                // The crate's rows and the records': Big cover lays its mosaic out round its cover itself.
                if (s.look != ShelfLook.BIG_COVER) {
                    Section(stringResource(R.string.widget_config_rows))
                    // Wraps: on a narrow dialog, in a plain Row, the chips past 3 were once squashed out
                    // of reach. Rows of covers, so no more than a look ever has: a ceiling of four set
                    // when they were rows of songs is the look's three.
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                        Chip(stringResource(R.string.widget_rows_auto), s.maxRows == 0) { s = s.copy(maxRows = 0) }
                        for (n in 1..WidgetLayout.MAX_SHELF_ROWS) {
                            Chip(n.toString(), s.maxRows.coerceAtMost(WidgetLayout.MAX_SHELF_ROWS) == n) { s = s.copy(maxRows = n) }
                        }
                    }
                }
            }

            // Full cover is drawn on its cover, so it has no background to pick. The others start on
            // their own (the song's colours, and for a Shelf its cover blurred) until one is picked.
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
 * the widget, and the line is the first thing on the sheet under it. Without [hint], the patch and
 * no line, as Settings > Widgets draws each widget on its card.
 */
@Composable
private fun Preview(
    settings: WidgetSettings,
    known: IntSize?,
    onWallpaper: Boolean,
    modifier: Modifier = Modifier,
    hint: Boolean = true,
    redraw: Int = 0,
) {
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
            LiveWidget(settings, size, redraw)
        }
        if (!onWallpaper && hint) PreviewHint(known != null, modifier = Modifier.padding(top = 8.dp, start = 8.dp, end = 8.dp))
    }
}

/**
 * A widget on the home screen as Settings > Widgets shows it on its card: this sheet's own preview,
 * the same live drawing at the widget's size on the same patch of colour, without the line under it.
 * A new [redraw] draws it again from what plays now, with nothing changed.
 */
@Composable
internal fun WidgetOnHomeScreen(settings: WidgetSettings, size: IntSize?, redraw: Int, modifier: Modifier = Modifier) {
    Preview(settings, size, onWallpaper = false, modifier = modifier, hint = false, redraw = redraw)
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
 * down, never up, when the widget is bigger than the room. Redrawn too at a new [size], which in
 * the app comes with a turn of the phone and no new screen, and at a new [redraw].
 */
@Composable
private fun LiveWidget(settings: WidgetSettings, size: IntSize, redraw: Int = 0) {
    val context = LocalContext.current
    var views by remember { mutableStateOf<RemoteViews?>(null) }
    // The covers come after a widget's first draw (the store decodes them once they are on the
    // phone): drawn again when they arrive, or a widget just added keeps a note for its cover.
    val drawnNow by WidgetStore.drawn.collectAsState()
    val covers = drawnNow?.let { Triple(it.nowCover != null, it.big != null, it.art.size + it.firsts.size) }
    LaunchedEffect(settings, size, redraw, covers) {
        // A slider dragged end to end asks for a drawing at every step; only the last is wanted.
        delay(60)
        // Never reached by a drawing that a newer change has cancelled: the preview keeps what it
        // shows until the newer drawing is there. Drawn from what the widget shows now, handed over
        // as it is: a drawing of its own that follows the store's flow, off the main thread where
        // a Shelf's pictures are drawn, came back once and then never again.
        views = drawnIfStillWanted { renderWidget(context, settings, size.width, size.height, WidgetStore.load(context)) }
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

/**
 * One style to pick: a small picture of it over its name, the card the button, outlined when chosen. The Shelf's picture is of its look.
 * Settings > Widgets offers the styles with these very tiles.
 */
@Composable
internal fun StyleChoice(style: WidgetStyle, selected: Boolean, look: ShelfLook, modifier: Modifier, onSelect: () -> Unit) {
    PictureCard(
        stringResource(
            when (style) {
                WidgetStyle.COVER -> R.string.widget_style_cover
                WidgetStyle.FULL_COVER -> R.string.widget_style_full_cover
                WidgetStyle.SHELF -> R.string.widget_style_shelf
            }
        ),
        selected, modifier, onSelect,
    ) { StylePicture(style, look, it) }
}

/** One of a Shelf's looks to pick, the same kind of card as a style, here and in Settings > Widgets. */
@Composable
internal fun LookChoice(look: ShelfLook, selected: Boolean, modifier: Modifier, onSelect: () -> Unit) {
    PictureCard(
        stringResource(
            when (look) {
                ShelfLook.BIG_COVER -> R.string.widget_look_big_cover
                ShelfLook.CRATE -> R.string.widget_look_crate
                ShelfLook.RECORDS -> R.string.widget_look_records
            }
        ),
        selected, modifier, onSelect,
    ) { LookPicture(look, it) }
}

/** A small picture over its name, the card the button, outlined when chosen. */
@Composable
private fun PictureCard(name: String, selected: Boolean, modifier: Modifier, onSelect: () -> Unit, picture: @Composable (Modifier) -> Unit) {
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
        picture(Modifier.fillMaxWidth().aspectRatio(1.5f))
        Text(
            name,
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
 * for Full cover, and for Shelf the look it has.
 */
@Composable
private fun StylePicture(style: WidgetStyle, look: ShelfLook, modifier: Modifier) {
    if (style == WidgetStyle.SHELF) {
        LookPicture(look, modifier)
        return
    }
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
            WidgetStyle.SHELF -> Unit
        }
    }
}

/** The mockups' covers, as colours: none of them anybody's music. */
private val LookCovers = listOf(
    Color(0xFF7B3FA0) to Color(0xFFF4A261),
    Color(0xFF0F6E56) to Color(0xFF5DCAA5),
    Color(0xFFD85A30) to Color(0xFFF0997B),
    Color(0xFF185FA5) to Color(0xFF85B7EB),
    Color(0xFF854F0B) to Color(0xFFEF9F27),
    Color(0xFF72243E) to Color(0xFFD4537E),
    Color(0xFF2C2C2A) to Color(0xFF888780),
)

/**
 * A Shelf's look in miniature, from the mockups (experiments/features/shelf-redo): on the playing
 * cover blurred, Big cover's big cover with its pill and the mosaic beside it, the crate's line over
 * its leaning covers, and the sleeve with its record out over a row of records.
 */
@Composable
private fun LookPicture(look: ShelfLook, modifier: Modifier) {
    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height
        val corner = CornerRadius(w * 0.1f)
        drawRoundRect(Brush.linearGradient(listOf(Color(0xFF3B2140), Color(0xFF1A1C2C)), Offset.Zero, Offset(w, h)), cornerRadius = corner)
        val word = Color.White.copy(alpha = 0.92f)
        val soft = Color.White.copy(alpha = 0.55f)
        fun cover(i: Int, x: Float, y: Float, side: Float, radius: Float = side * 0.16f) {
            val (a, b) = LookCovers[i % LookCovers.size]
            drawRoundRect(Brush.linearGradient(listOf(a, b), Offset(x, y), Offset(x + side, y + side)), Offset(x, y), Size(side, side), CornerRadius(radius))
        }
        fun bar(x: Float, y: Float, length: Float, colour: Color, thick: Float = h * 0.06f) =
            drawRoundRect(colour, Offset(x, y), Size(length, thick), CornerRadius(thick / 2))
        fun pill(x: Float, y: Float, pw: Float, ph: Float) {
            drawRoundRect(Color.White.copy(alpha = 0.2f), Offset(x, y), Size(pw, ph), CornerRadius(ph / 2))
            drawRoundRect(Color.White.copy(alpha = 0.38f), Offset(x, y), Size(pw, ph), CornerRadius(ph / 2), style = Stroke(1.dp.toPx()))
            for (k in 0..2) drawCircle(Color.White, ph * (if (k == 1) 0.34f else 0.13f), Offset(x + pw * (0.22f + 0.28f * k), y + ph / 2))
        }
        fun record(i: Int, cx: Float, cy: Float, r: Float) {
            drawCircle(Color(0xFF101012), r, Offset(cx, cy))
            for (k in 1..3) drawCircle(Color(0xFF2A2A30), r * (0.62f + 0.12f * k), Offset(cx, cy), style = Stroke(0.6.dp.toPx()))
            val (a, b) = LookCovers[i % LookCovers.size]
            drawCircle(Brush.linearGradient(listOf(a, b), Offset(cx - r, cy - r), Offset(cx + r, cy + r)), r * 0.52f, Offset(cx, cy))
            drawCircle(Color(0xFF101012), r * 0.06f, Offset(cx, cy))
        }
        val pad = h * 0.1f
        when (look) {
            ShelfLook.BIG_COVER -> {
                val side = h - 2 * pad
                cover(0, pad, pad, side, side * 0.12f)
                drawRoundRect(
                    Brush.verticalGradient(listOf(Color(0xB8000000), Color(0x00000000)), startY = pad, endY = pad + side * 0.45f),
                    Offset(pad, pad), Size(side, side * 0.45f), CornerRadius(side * 0.12f),
                )
                bar(pad + side * 0.1f, pad + side * 0.1f, side * 0.6f, word)
                bar(pad + side * 0.1f, pad + side * 0.22f, side * 0.32f, soft, h * 0.045f)
                pill(pad + side * 0.1f, pad + side * 0.7f, side * 0.8f, side * 0.2f)
                val gap = h * 0.04f
                val cell = (side - 2 * gap) / 3
                val x0 = pad + side + gap * 1.5f
                for (k in 0 until 6) cover(k + 1, x0 + (k % 2) * (cell + gap), pad + (k / 2) * (cell + gap), cell)
            }
            ShelfLook.CRATE -> {
                val small = h * 0.26f
                cover(0, pad, pad, small)
                bar(pad + small + pad * 0.8f, pad + small * 0.1f, w * 0.34f, word)
                bar(pad + small + pad * 0.8f, pad + small * 0.55f, w * 0.18f, soft, h * 0.045f)
                pill(w - pad - w * 0.3f, pad + small * 0.42f, w * 0.3f, small * 0.62f)
                val tile = h * 0.42f
                val step = (w - 2 * pad - tile) / 5
                val angles = listOf(-7f, 4f, -3f, 6f, -5f, 3f)
                for (k in 0 until 6) {
                    val x = pad + k * step
                    val y = h - pad - tile - (if (k % 2 == 1) 0f else h * 0.02f)
                    rotate(angles[k], Offset(x + tile / 2, y + tile / 2)) { cover(k + 1, x, y, tile) }
                }
            }
            ShelfLook.RECORDS -> {
                val sleeve = h * 0.48f
                record(0, pad + sleeve * 0.95f, pad + sleeve / 2, sleeve * 0.47f)
                cover(0, pad, pad, sleeve, sleeve * 0.08f)
                val x = pad + sleeve * 1.45f + pad * 0.6f
                bar(x, pad + sleeve * 0.1f, w - x - pad, word)
                bar(x, pad + sleeve * 0.3f, (w - x - pad) * 0.5f, soft, h * 0.045f)
                pill(x, pad + sleeve * 0.55f, w - x - pad, sleeve * 0.36f)
                val r = h * 0.15f
                val step = (w - 2 * pad - 2 * r) / 5
                for (k in 0 until 6) record(k + 1, pad + r + k * step, h - pad - r, r)
            }
        }
    }
}
