/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.ui.screens.settings

import android.widget.Toast
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarScrollBehavior
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.navigation.NavController
import com.dd3boh.outertune.R
import com.dd3boh.outertune.ui.component.ColumnWithContentPadding
import com.dd3boh.outertune.ui.component.FloatingTopBar
import com.dd3boh.outertune.ui.component.ListPreference
import com.dd3boh.outertune.ui.component.PreferenceEntry
import com.dd3boh.outertune.widget.LookChoice
import com.dd3boh.outertune.widget.OfferedWidget
import com.dd3boh.outertune.widget.PickerPicture
import com.dd3boh.outertune.widget.ShelfLook
import com.dd3boh.outertune.widget.StyleChoice
import com.dd3boh.outertune.widget.WidgetBackground
import com.dd3boh.outertune.widget.WidgetButtons
import com.dd3boh.outertune.widget.WidgetConfigActivity
import com.dd3boh.outertune.widget.WidgetList
import com.dd3boh.outertune.widget.WidgetOnHomeScreen
import com.dd3boh.outertune.widget.WidgetSettings
import com.dd3boh.outertune.widget.WidgetStore
import com.dd3boh.outertune.widget.WidgetStyle
import com.dd3boh.outertune.widget.canPinWidgets
import com.dd3boh.outertune.widget.pinWidget
import com.dd3boh.outertune.widget.placedSize
import com.dd3boh.outertune.utils.Toaster

/**
 * Settings > Widgets (Unreleased.WIDGETS_PAGE), his ask of 3 Nov 2026 for "a easy settings menu for
 * widgets", built as the mockup he approved (experiments/features/widget-settings-mock): a card for
 * each widget on the home screen, with the widget itself, its style, the two choices most changed
 * and All options, which is that widget's own setup sheet; then the two widgets there are, to add.
 *
 * Every choice on a card belongs to that widget, as on its sheet, and is written the way the sheet
 * writes it (WidgetStore), so the two edit the same values and the widget on the home screen
 * changes at once. None of it is a setting of the app, which is why search finds this page by its
 * name and its two widgets to add rather than by the rows of its cards (SettingsIndexTest).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WidgetsSettings(
    navController: NavController,
    scrollBehavior: TopAppBarScrollBehavior,
) {
    val context = LocalContext.current
    var placed by remember { mutableStateOf(WidgetStore.placed(context)) }
    // Each time the app is back in front, the widgets are read again: back from the launcher's box
    // there can be one more, back from a widget's own sheet it can be set otherwise, and back from
    // the home screen there can be one fewer or one of another size.
    var back by remember { mutableIntStateOf(0) }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        var away = false
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_PAUSE) away = true
            if (event == Lifecycle.Event.ON_RESUME && away) {
                away = false
                placed = WidgetStore.placed(context)
                back++
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    val canPin = remember { canPinWidgets(context) }
    fun add(widget: OfferedWidget) {
        if (!pinWidget(context, widget)) Toaster.show(context, R.string.welcome_back_no_widget_host, Toast.LENGTH_LONG)
    }

    ColumnWithContentPadding(
        modifier = Modifier.fillMaxHeight(),
        columnModifier = Modifier.padding(horizontal = 16.dp),
    ) {
        // No empty cards: with no widget on the home screen the page is the two to add.
        if (placed.isNotEmpty()) {
            WidgetsGroupTitle(stringResource(R.string.widgets_on_home_screen))
            for (id in placed) key(id) {
                WidgetCard(id, back)
                Spacer(Modifier.height(12.dp))
            }
        }

        WidgetsGroupTitle(stringResource(R.string.widgets_add_title))
        ElevatedCard(modifier = Modifier.fillMaxWidth()) {
            WidgetAddRow(
                title = stringResource(R.string.widget_label),
                widget = OfferedWidget.NOW_PLAYING,
                onAdd = if (canPin) { { add(OfferedWidget.NOW_PLAYING) } } else null,
                modifier = Modifier.settingRow(R.string.widget_label),
            )
            WidgetsRowLine()
            WidgetAddRow(
                title = stringResource(R.string.recognise),
                widget = OfferedWidget.RECOGNITION,
                onAdd = if (canPin) { { add(OfferedWidget.RECOGNITION) } } else null,
                modifier = Modifier.settingRow(R.string.recognise),
            )
        }
        // Where the home screen takes no widget from an app, the way it does take one.
        if (!canPin) {
            Text(
                text = stringResource(R.string.widgets_add_from_home),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            )
        }
    }

    FloatingTopBar(title = stringResource(R.string.widgets), navController = navController)
}

/** What a card has under its tiles: the choices most changed in its style, and All options for the rest. */
internal enum class WidgetCardRow { BACKGROUND, BUTTONS, WHICH_LIST, ALL_OPTIONS }

/**
 * The rows of a card in [style], each one a choice its setup sheet offers in that style. Cover's
 * background and buttons; Full cover's buttons alone, since it is drawn on its cover and its sheet
 * has no background to pick either; a Shelf's list, its look being the tiles over it.
 */
internal fun widgetCardRows(style: WidgetStyle): List<WidgetCardRow> = when (style) {
    WidgetStyle.COVER -> listOf(WidgetCardRow.BACKGROUND, WidgetCardRow.BUTTONS, WidgetCardRow.ALL_OPTIONS)
    WidgetStyle.FULL_COVER -> listOf(WidgetCardRow.BUTTONS, WidgetCardRow.ALL_OPTIONS)
    WidgetStyle.SHELF -> listOf(WidgetCardRow.WHICH_LIST, WidgetCardRow.ALL_OPTIONS)
}

/** The line under All options: what else the sheet holds for [style]. Full cover has no background, so no opacity. */
@StringRes
internal fun allOptionsLine(style: WidgetStyle): Int = when (style) {
    WidgetStyle.COVER -> R.string.widgets_all_options_cover
    WidgetStyle.FULL_COVER -> R.string.widgets_all_options_full_cover
    WidgetStyle.SHELF -> R.string.widgets_all_options_shelf
}

/** The backgrounds in the order the setup sheet offers them. */
private val WidgetBackgrounds = listOf(
    WidgetBackground.ARTWORK, WidgetBackground.SYSTEM, WidgetBackground.DARK, WidgetBackground.LIGHT, WidgetBackground.NONE,
)

/**
 * One widget on the home screen: the widget as it is now at its size, its style, the choices most
 * changed and All options. Drawn once its settings are read; a choice is shown at once and written
 * after, and the widget on the home screen is drawn again with it.
 */
@Composable
private fun WidgetCard(id: Int, back: Int) {
    val context = LocalContext.current
    var settings by remember { mutableStateOf<WidgetSettings?>(null) }
    // As it is set now, and again each time the app is back: its own sheet may have changed any of it.
    LaunchedEffect(back) { settings = WidgetStore.settingsOf(context, id) }
    // Its size on the home screen, the other shape once the phone is turned.
    val orientation = LocalConfiguration.current.orientation
    val size = remember(back, orientation) { placedSize(context, id) }
    fun change(to: WidgetSettings) {
        if (to == settings) return
        settings = to
        WidgetStore.changeSettings(context, id, to)
    }
    val s = settings
    if (s != null) {
        ElevatedCard(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp)) {
                // Drawn again on the way back, from what plays now.
                WidgetOnHomeScreen(s, size, redraw = back)
                WidgetCardLabel(stringResource(R.string.widget_config_style))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    for (style in WidgetStyle.entries) {
                        StyleChoice(style, s.style == style, s.look, Modifier.weight(1f)) { change(s.copy(style = style)) }
                    }
                }
                if (s.style == WidgetStyle.SHELF) {
                    WidgetCardLabel(stringResource(R.string.widget_config_look))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                        for (look in ShelfLook.entries) {
                            LookChoice(look, s.look == look, Modifier.weight(1f)) { change(s.copy(look = look)) }
                        }
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
            widgetCardRows(s.style).forEachIndexed { at, row ->
                if (at > 0) WidgetsRowLine()
                when (row) {
                    WidgetCardRow.BACKGROUND -> ListPreference(
                        title = { Text(stringResource(R.string.widget_config_background)) },
                        selectedValue = s.backgroundShown,
                        values = WidgetBackgrounds,
                        valueText = { backgroundName(it) },
                        onValueSelected = { change(s.copy(background = it)) },
                    )
                    WidgetCardRow.BUTTONS -> ListPreference(
                        title = { Text(stringResource(R.string.widget_config_buttons)) },
                        selectedValue = s.buttons,
                        values = WidgetButtons.entries,
                        valueText = { buttonsName(it) },
                        onValueSelected = { change(s.copy(buttons = it)) },
                    )
                    WidgetCardRow.WHICH_LIST -> ListPreference(
                        title = { Text(stringResource(R.string.widget_config_which_list)) },
                        selectedValue = s.list,
                        values = WidgetList.entries,
                        valueText = { listName(it) },
                        onValueSelected = { change(s.copy(list = it)) },
                    )
                    WidgetCardRow.ALL_OPTIONS -> PreferenceEntry(
                        title = { Text(stringResource(R.string.widgets_all_options)) },
                        description = stringResource(allOptionsLine(s.style)),
                        onClick = { context.startActivity(WidgetConfigActivity.edit(context, id)) },
                    )
                }
            }
        }
    }
}

@Composable
private fun backgroundName(background: WidgetBackground): String = stringResource(
    when (background) {
        WidgetBackground.ARTWORK -> R.string.widget_background_artwork
        WidgetBackground.SYSTEM -> R.string.widget_background_system
        WidgetBackground.DARK -> R.string.widget_background_dark
        WidgetBackground.LIGHT -> R.string.widget_background_light
        WidgetBackground.NONE -> R.string.widget_background_none
    }
)

@Composable
private fun buttonsName(buttons: WidgetButtons): String = stringResource(
    when (buttons) {
        WidgetButtons.ALL -> R.string.widget_buttons_all
        WidgetButtons.PLAY_ONLY -> R.string.widget_buttons_play
        WidgetButtons.NONE -> R.string.widget_buttons_none
    }
)

@Composable
private fun listName(list: WidgetList): String = stringResource(
    when (list) {
        WidgetList.QUICK_PICKS -> R.string.quick_picks
        WidgetList.FORGOTTEN_FAVOURITES -> R.string.forgotten_favorites
        WidgetList.KEEP_LISTENING -> R.string.keep_listening
        WidgetList.RECENT -> R.string.widget_list_recent
    }
)

/** The width of a widget's picture beside its name, as in the mockup. */
private val AddPictureWidth = 92.dp

/** Add's padding, as in the mockup. */
private val AddButtonPadding = PaddingValues(horizontal = 20.dp, vertical = 8.dp)

/**
 * One widget to add: the launcher's own picture of it, its name and the size it is dropped at, and
 * Add, which asks the launcher for its box. [onAdd] is null where the launcher takes no widget from
 * the app, and then there is no button.
 */
@Composable
private fun WidgetAddRow(title: String, widget: OfferedWidget, onAdd: (() -> Unit)?, modifier: Modifier = Modifier) {
    PreferenceEntry(
        modifier = modifier,
        title = { Text(title) },
        description = stringResource(R.string.widgets_cells, widget.cellsWide, widget.cellsHigh),
        icon = { PickerPicture(widget, AddPictureWidth) },
        // The button as drawn, narrower at its sides than Material's own: a 360 dp phone leaves the name little room.
        trailingContent = if (onAdd != null) {
            { Button(onClick = onAdd, contentPadding = AddButtonPadding) { Text(stringResource(R.string.widgets_add)) } }
        } else {
            null
        },
        onClick = null,
    )
}

/** A part of the page, named in the accent as the other screens' groups are, and in its own case, as the mockup has it. */
@Composable
private fun WidgetsGroupTitle(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold),
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 20.dp, bottom = 10.dp),
    )
}

/** A heading over a card's tiles, as the setup sheet heads them. */
@Composable
private fun WidgetCardLabel(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleMedium,
        modifier = Modifier.padding(top = 16.dp, bottom = 8.dp),
    )
}

/** The faint line between two rows of a card. */
@Composable
private fun WidgetsRowLine() {
    HorizontalDivider(
        modifier = Modifier.padding(horizontal = 16.dp),
        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f),
    )
}
