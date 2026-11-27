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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.dd3boh.outertune.R
import com.dd3boh.outertune.constants.TogetherEveryoneArrangesKey
import com.dd3boh.outertune.constants.TogetherEveryoneControlsKey
import com.dd3boh.outertune.constants.TogetherEveryoneSeeksKey
import com.dd3boh.outertune.together.RoomSettings
import com.dd3boh.outertune.utils.dataStore
import com.dd3boh.outertune.utils.get
import com.dd3boh.outertune.utils.rememberPreference

/**
 * The host's room settings in the room's sheet (RoomSettings): set once and kept for every room it makes, as its name in
 * rooms is, and changeable here or in Settings > Listen together, the same settings (his word of 22 Nov 2026: "like the
 * name, its set once and remembered in the room. changeable in settings"). The room hears a change at once
 * (TogetherSession follows them). One switch since 26 Nov 2026 (his word: Spotify's Jam, "host can control and can give a
 * toggle to let all guests control"), in the place of the two before it, moving in the song and changing the queue,
 * either of which on reads as it on until it is changed (RoomSettings.kept).
 */
@Composable
internal fun RoomSettingsSwitches() {
    val haptics = LocalHapticFeedback.current
    val context = LocalContext.current
    val before = remember {
        RoomSettings.kept(null, context.dataStore[TogetherEveryoneSeeksKey], context.dataStore[TogetherEveryoneArrangesKey])
    }
    var everyoneControls by rememberPreference(TogetherEveryoneControlsKey, defaultValue = before)
    fun felt(on: Boolean) = haptics.performHapticFeedback(if (on) HapticFeedbackType.ToggleOn else HapticFeedbackType.ToggleOff)
    SettingSwitch(
        title = stringResource(R.string.together_everyone_controls),
        explain = stringResource(R.string.together_everyone_controls_explain),
        on = everyoneControls,
        onChange = {
            felt(it)
            everyoneControls = it
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
