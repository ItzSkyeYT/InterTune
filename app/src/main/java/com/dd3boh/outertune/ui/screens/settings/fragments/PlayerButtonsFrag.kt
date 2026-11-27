/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.ui.screens.settings.fragments

import androidx.compose.material.icons.rounded.Cast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.KeyboardDoubleArrowDown
import androidx.compose.material.icons.rounded.Lyrics
import androidx.compose.material.icons.rounded.PushPin
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material.icons.rounded.SmartButton
import androidx.compose.material.icons.rounded.Speaker
import androidx.compose.material.icons.rounded.Timer
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.dd3boh.outertune.R
import androidx.compose.material.icons.automirrored.rounded.QueueMusic
import com.dd3boh.outertune.constants.PlayerButtonsStyle
import com.dd3boh.outertune.constants.PlayerButtonsStyleKey
import com.dd3boh.outertune.constants.QueueBackClosesPlayerKey
import com.dd3boh.outertune.constants.PlayerPinsKey
import com.dd3boh.outertune.constants.PlayerMenuPlaceKey
import com.dd3boh.outertune.ui.player.MenuPlace
import com.dd3boh.outertune.ui.player.MenuPlaces
import androidx.compose.material.icons.rounded.MoreVert
import com.dd3boh.outertune.ui.player.PinPlace
import com.dd3boh.outertune.ui.player.PlayerPin
import com.dd3boh.outertune.ui.player.PlayerPins
import com.dd3boh.outertune.constants.QueueButtonKey
import com.dd3boh.outertune.constants.OutputPillKey
import com.dd3boh.outertune.constants.Unreleased
import com.dd3boh.outertune.ui.component.PreferenceEntry
import com.dd3boh.outertune.ui.component.SwitchPreference
import com.dd3boh.outertune.utils.rememberEnumPreference
import com.dd3boh.outertune.ui.screens.settings.settingRow
import com.dd3boh.outertune.ui.screens.walkthrough.Tour
import com.dd3boh.outertune.ui.screens.walkthrough.tourTarget
import com.dd3boh.outertune.utils.rememberPreference

/**
 * Picks the player's buttons by a picture of each rather than by name, since "classic" and
 * "connected" mean nothing until you have seen them. The pictures are the player itself with the
 * song's title and artist blanked out.
 */
@Composable
fun ColumnScope.PlayerButtonsFrag() {
    val (buttonsStyle, onButtonsStyleChange) = rememberEnumPreference(
        key = PlayerButtonsStyleKey,
        defaultValue = PlayerButtonsStyle.CLASSIC
    )

    // Off keeps the sheet pulled up from the bottom of the player, as it has always been. On puts
    // a queue button with the others: beside lyrics under the controls in Connected, and first in
    // the row by the title in Classic.
    val (queueAsButton, onQueueAsButtonChange) = rememberPreference(QueueButtonKey, defaultValue = false)

    // Which buttons stay on the player outside its menu, for the style chosen above (issue #27):
    // a chip each, in the places that style has. Today's until somebody chooses.
    val (pinsRaw, onPinsRawChange) = rememberPreference(PlayerPinsKey, defaultValue = "")
    val pins = remember(pinsRaw) { PlayerPins.read(pinsRaw) }

    // The three-dots button is at the top or at the bottom of the player, never off it (MenuPlaces): where the
    // style has always had it until somebody moves it.
    val (menuPlaceRaw, onMenuPlaceRawChange) = rememberPreference(PlayerMenuPlaceKey, defaultValue = "")
    val menuPlace = MenuPlaces.read(menuPlaceRaw, buttonsStyle)

    PreferenceEntry(
        modifier = Modifier.settingRow(R.string.player_buttons_style),
        title = { Text(stringResource(R.string.player_buttons_style)) },
        description = stringResource(R.string.player_buttons_style_description),
        icon = { Icon(Icons.Rounded.SmartButton, null) },
        // A label for the choice underneath, not a button.
        onClick = null
    )

    Row(
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 16.dp, bottom = 12.dp)
            .selectableGroup()
    ) {
        PlayerButtonsStyle.entries.forEach { style ->
            val selected = style == buttonsStyle
            val shape = RoundedCornerShape(16.dp)
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier
                    .weight(1f)
                    .clip(shape)
                    .selectable(
                        selected = selected,
                        role = Role.RadioButton,
                        onClick = { onButtonsStyleChange(style) }
                    )
            ) {
                // Drawn from the buttons pinned for that style, so it never shows one the player has not got.
                PlayerStyleSample(
                    style = style,
                    pins = pins,
                    queueButton = queueAsButton,
                    menuPlace = MenuPlaces.read(menuPlaceRaw, style),
                    icon = { pinIcon(it) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(shape)
                        .border(
                            if (selected) BorderStroke(3.dp, MaterialTheme.colorScheme.primary)
                            else BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                            shape
                        )
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    // The row is the control; the radio only shows the state.
                    RadioButton(selected = selected, onClick = null, modifier = Modifier.padding(8.dp))
                    Text(
                        text = stringResource(
                            when (style) {
                                PlayerButtonsStyle.CLASSIC -> R.string.player_buttons_classic
                                PlayerButtonsStyle.CONNECTED -> R.string.player_buttons_connected
                            }
                        ),
                        style = MaterialTheme.typography.labelLarge
                    )
                }
            }
        }
    }

    val places = if (buttonsStyle == PlayerButtonsStyle.CLASSIC) listOf(PinPlace.CLASSIC)
    else listOf(PinPlace.CONNECTED_TITLE, PinPlace.CONNECTED_ROW)
    // The row, the editor and the way back to the default under one mark, for the tour from What's new: its bubble is
    // about the editor, and with the row alone marked it would stand over it.
    Column(Modifier.fillMaxWidth().tourTarget(Tour.SETTING_PLAYER_PINS)) {
        PreferenceEntry(
            modifier = Modifier.settingRow(R.string.player_pins),
            title = { Text(stringResource(R.string.player_pins)) },
            icon = { Icon(Icons.Rounded.PushPin, null) },
            // Back to the buttons the style shown has always had (his ask of 18 Oct 2026). Always there, and only
            // greyed out while nothing differs, so the way back is never hidden.
            trailingContent = {
                TextButton(
                    enabled = PlayerPins.changed(pins, places),
                    onClick = { onPinsRawChange(PlayerPins.write(PlayerPins.reset(pins, places))) },
                ) { Text(stringResource(R.string.player_pins_reset)) }
            },
            onClick = null
        )
        // Ti-Die's ask of 26 Nov 2026, mock 2 his pick: a box per place shaped like that bar of the player, the buttons
        // not shown as tiles, dragged or tapped where they go. Stored as the chips stored it, so no choice changes.
        PlayerButtonsEditor(
            pins = pins,
            places = places,
            // Classic has one fewer while the queue has a circle there; the cast button always has room.
            most = { place -> if (place == PinPlace.CLASSIC) PlayerPins.classicMost(queueAsButton) else place.most },
            // Cast only where there is casting.
            available = PlayerPin.entries.filter { it != PlayerPin.CAST || Unreleased.CASTING },
            queueButton = queueAsButton,
            onPinsChange = { onPinsRawChange(PlayerPins.write(it)) },
            name = { pinName(it) },
            icon = { pinIcon(it) },
            modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 12.dp),
        )
    }
    PreferenceEntry(
        modifier = Modifier.settingRow(R.string.player_menu_place),
        title = { Text(stringResource(R.string.player_menu_place)) },
        icon = { Icon(Icons.Rounded.MoreVert, null) },
        onClick = null
    )
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 16.dp, bottom = 8.dp)
            .selectableGroup()
    ) {
        MenuPlace.entries.forEach { place ->
            FilterChip(
                selected = place == menuPlace,
                // One of the two always stays on: tapping the one shown does nothing, the other moves it.
                onClick = { if (place != menuPlace) onMenuPlaceRawChange(MenuPlaces.write(menuPlaceRaw, buttonsStyle, place)) },
                label = { Text(stringResource(if (place == MenuPlace.TOP) R.string.player_menu_top else R.string.player_menu_bottom)) },
            )
        }
    }
    SwitchPreference(
        modifier = Modifier.settingRow(R.string.queue_button),
        title = { Text(stringResource(R.string.queue_button)) },
        icon = { Icon(Icons.AutoMirrored.Rounded.QueueMusic, null) },
        checked = queueAsButton,
        onCheckedChange = onQueueAsButtonChange
    )

    // Off keeps Back from the queue going to the song first, as it always has. On, Back from the
    // queue closes the whole player and leaves the queue open in it, so the player opens on the
    // queue next time, after a restart too (issue #30).
    val (backClosesPlayer, onBackClosesPlayerChange) = rememberPreference(QueueBackClosesPlayerKey, defaultValue = false)
    SwitchPreference(
        modifier = Modifier.settingRow(R.string.queue_back_closes_player),
        title = { Text(stringResource(R.string.queue_back_closes_player)) },
        icon = { Icon(Icons.Rounded.KeyboardDoubleArrowDown, null) },
        checked = backClosesPlayer,
        onCheckedChange = onBackClosesPlayerChange
    )

    // Off by default, his pick of 15 Nov 2026: the cast button at the player's top right and nothing
    // under the controls. On gives back the pill that names where the music plays ("the pill should
    // be toggleable"). Only where casting is, and with the pick (CAST_AT_TOP): without it the pill is always there.
    if (Unreleased.CASTING && Unreleased.CAST_AT_TOP) {
        val (outputPill, onOutputPillChange) = rememberPreference(OutputPillKey, defaultValue = false)
        SwitchPreference(
            modifier = Modifier.settingRow(R.string.output_pill),
            title = { Text(stringResource(R.string.output_pill)) },
            icon = { Icon(Icons.Rounded.Speaker, null) },
            checked = outputPill,
            onCheckedChange = onOutputPillChange
        )
    }
}

@Composable
private fun pinName(pin: PlayerPin): String = stringResource(
    when (pin) {
        PlayerPin.SHARE -> R.string.share
        PlayerPin.LYRICS -> R.string.player_pin_lyrics
        PlayerPin.TIMER -> R.string.sleep_timer
        PlayerPin.LIKE -> R.string.action_like
        PlayerPin.CAST -> R.string.cast
    }
)

/** A pin's icon, on its chip and in the style's picture. */
@Composable
private fun pinIcon(pin: PlayerPin): Painter = when (pin) {
    PlayerPin.SHARE -> rememberVectorPainter(Icons.Rounded.Share)
    PlayerPin.LYRICS -> rememberVectorPainter(Icons.Rounded.Lyrics)
    PlayerPin.TIMER -> rememberVectorPainter(Icons.Rounded.Timer)
    PlayerPin.LIKE -> painterResource(R.drawable.favorite)
    PlayerPin.CAST -> rememberVectorPainter(Icons.Rounded.Cast)
}
