/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.ui.dialog

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.dd3boh.outertune.R
import com.dd3boh.outertune.together.RoomSettings

/**
 * The host's room settings in the room's sheet (RoomSettings): a switch each, the owner's alone, heard by everyone in the
 * room at once and kept for the owner's next rooms. One for now, made to take more (dvd, #testers 22 Nov 2026: "to
 * configure who can do what, like adding/removing songs, editing queue").
 */
@Composable
internal fun RoomSettingsSwitches(settings: RoomSettings?, onEveryoneSeeks: (Boolean) -> Unit) {
    val haptics = LocalHapticFeedback.current
    SettingSwitch(
        title = stringResource(R.string.together_everyone_seeks),
        explain = stringResource(R.string.together_everyone_seeks_explain),
        on = settings?.everyoneSeeks == true,
        onChange = {
            haptics.performHapticFeedback(if (it) HapticFeedbackType.ToggleOn else HapticFeedbackType.ToggleOff)
            onEveryoneSeeks(it)
        },
    )
}

/**
 * One switch, the whole row its own (as SwitchPreference: TalkBack reads its words, on or off, as one), lined up with
 * the sheet's words and not indented as a settings screen's are.
 */
@Composable
private fun SettingSwitch(title: String, explain: String?, on: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .toggleable(value = on, role = Role.Switch, onValueChange = onChange)
            .heightIn(min = 56.dp)
            .padding(vertical = 8.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            if (explain != null) {
                Text(explain, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Spacer(Modifier.width(16.dp))
        Switch(checked = on, onCheckedChange = null)
    }
}
