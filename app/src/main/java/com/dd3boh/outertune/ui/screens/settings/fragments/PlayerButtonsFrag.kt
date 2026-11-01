/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.ui.screens.settings.fragments

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.PushPin
import androidx.compose.material.icons.rounded.KeyboardDoubleArrowDown
import androidx.compose.material.icons.rounded.SmartButton
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.InputChip
import androidx.compose.material3.InputChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.foundation.clickable
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.dd3boh.outertune.ui.component.items.ListItem
import com.dd3boh.outertune.ui.dialog.ListDialog
import com.dd3boh.outertune.ui.player.pinLabel
import com.dd3boh.outertune.ui.player.pinOffered
import com.dd3boh.outertune.ui.player.pinPainter
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.dd3boh.outertune.R
import androidx.compose.material.icons.automirrored.rounded.QueueMusic
import com.dd3boh.outertune.constants.PlayerButtonsStyle
import com.dd3boh.outertune.constants.PlayerButtonsStyleKey
import com.dd3boh.outertune.constants.PlayerPinsKey
import com.dd3boh.outertune.ui.player.PinPlace
import com.dd3boh.outertune.ui.player.PlayerPin
import com.dd3boh.outertune.ui.player.PlayerPins
import com.dd3boh.outertune.constants.QueueBackClosesPlayerKey
import com.dd3boh.outertune.constants.QueueButtonKey
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
                Image(
                    painter = painterResource(
                        when (style) {
                            PlayerButtonsStyle.CLASSIC -> R.drawable.player_buttons_classic
                            PlayerButtonsStyle.CONNECTED -> R.drawable.player_buttons_connected
                        }
                    ),
                    contentDescription = null,
                    contentScale = ContentScale.FillWidth,
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

    // Off keeps the sheet pulled up from the bottom of the player, as it has always been. On puts
    // a queue button with the others: beside lyrics under the controls in Connected, and first in
    // the row by the title in Classic.
    val (queueAsButton, onQueueAsButtonChange) = rememberPreference(QueueButtonKey, defaultValue = false)

    // Which buttons stay on the player outside its menu, for the style chosen above (issue #27):
    // a chip each, in the places that style has. Today's until somebody chooses.
    val (pinsRaw, onPinsRawChange) = rememberPreference(PlayerPinsKey, defaultValue = "")
    val pins = remember(pinsRaw) { PlayerPins.read(pinsRaw) }
    // The row, its chips and the way back to the default under one mark, for the tour from What's
    // new: its bubble is about the chips, and with the row alone marked it would stand over them.
    Column(Modifier.fillMaxWidth().tourTarget(Tour.SETTING_PLAYER_PINS)) {
        PreferenceEntry(
            modifier = Modifier.settingRow(R.string.player_pins),
            title = { Text(stringResource(R.string.player_pins)) },
            description = stringResource(R.string.player_pins_description),
            icon = { Icon(Icons.Rounded.PushPin, null) },
            onClick = null
        )
        val places = if (buttonsStyle == PlayerButtonsStyle.CLASSIC) listOf(PinPlace.CLASSIC)
        else listOf(PinPlace.CONNECTED_TITLE, PinPlace.CONNECTED_ROW)
        // The place whose list of buttons to add is open.
        var adding by remember { mutableStateOf<PinPlace?>(null) }
        places.forEach { place ->
            if (places.size > 1) {
                Text(
                    text = stringResource(if (place == PinPlace.CONNECTED_TITLE) R.string.player_pins_by_title else R.string.player_pins_under_controls),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 4.dp)
                )
            }
            val most = if (place == PinPlace.CLASSIC) PlayerPins.classicMost(queueAsButton) else place.most
            val chosen = pins[place].orEmpty()
            // What is pinned, a tap on one taking it off, and Add for the rest: every entry of the
            // player's menu and Listen together are too many to show as chips side by side.
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 16.dp, end = 16.dp, bottom = 8.dp)
            ) {
                chosen.forEach { pin ->
                    val name = pinLabel(pin)
                    val takeOff = stringResource(R.string.player_pins_take_off, name)
                    InputChip(
                        selected = true,
                        onClick = { onPinsRawChange(PlayerPins.write(PlayerPins.toggle(pins, place, pin, most))) },
                        label = { Text(name) },
                        leadingIcon = {
                            Icon(pinPainter(pin, on = pin == PlayerPin.LIKE), contentDescription = null, modifier = Modifier.size(InputChipDefaults.IconSize))
                        },
                        trailingIcon = {
                            Icon(Icons.Rounded.Close, contentDescription = takeOff, modifier = Modifier.size(InputChipDefaults.IconSize))
                        },
                    )
                }
                AssistChip(
                    // A full place takes no more until one comes off.
                    enabled = chosen.size < most,
                    onClick = { adding = place },
                    label = { Text(stringResource(R.string.player_pins_add)) },
                    leadingIcon = {
                        Icon(Icons.Rounded.Add, contentDescription = null, modifier = Modifier.size(AssistChipDefaults.IconSize))
                    },
                )
            }
        }
        adding?.let { place ->
            val most = if (place == PinPlace.CLASSIC) PlayerPins.classicMost(queueAsButton) else place.most
            ListDialog(onDismiss = { adding = null }) {
                items(PlayerPin.entries.filter { it !in pins[place].orEmpty() && pinOffered(it) }) { pin ->
                    ListItem(
                        title = pinLabel(pin),
                        thumbnailContent = {
                            Icon(pinPainter(pin, on = pin == PlayerPin.LIKE), contentDescription = null, modifier = Modifier.size(24.dp))
                        },
                        modifier = Modifier.clickable {
                            onPinsRawChange(PlayerPins.write(PlayerPins.toggle(pins, place, pin, most)))
                            adding = null
                        },
                    )
                }
            }
        }
        // Back to the buttons the style shown has always had (his ask of 18 Oct 2026). Always there,
        // and only greyed out while nothing differs, so the way back is never hidden.
        Row(
            horizontalArrangement = Arrangement.End,
            modifier = Modifier
                .fillMaxWidth()
                .padding(end = 8.dp)
        ) {
            TextButton(
                enabled = PlayerPins.changed(pins, places),
                onClick = { onPinsRawChange(PlayerPins.write(PlayerPins.reset(pins, places))) },
            ) { Text(stringResource(R.string.player_pins_reset)) }
        }
    }
    SwitchPreference(
        modifier = Modifier.settingRow(R.string.queue_button),
        title = { Text(stringResource(R.string.queue_button)) },
        description = stringResource(R.string.queue_button_description),
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
        description = stringResource(R.string.queue_back_closes_player_description),
        icon = { Icon(Icons.Rounded.KeyboardDoubleArrowDown, null) },
        checked = backClosesPlayer,
        onCheckedChange = onBackClosesPlayerChange
    )
}
