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
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.SmartButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.dd3boh.outertune.R
import com.dd3boh.outertune.constants.PlayerButtonsStyle
import com.dd3boh.outertune.constants.PlayerButtonsStyleKey
import com.dd3boh.outertune.ui.component.PreferenceEntry
import com.dd3boh.outertune.utils.rememberEnumPreference

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
}
