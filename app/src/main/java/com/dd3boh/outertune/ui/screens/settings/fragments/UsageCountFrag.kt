/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.ui.screens.settings.fragments

import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Groups
import androidx.compose.material.icons.rounded.RestartAlt
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.res.stringResource
import com.dd3boh.outertune.LocalActiveCount
import com.dd3boh.outertune.R
import com.dd3boh.outertune.constants.Polls
import com.dd3boh.outertune.constants.UsageCountEnabledKey
import com.dd3boh.outertune.ui.component.PreferenceEntry
import com.dd3boh.outertune.ui.component.SwitchPreference
import com.dd3boh.outertune.utils.rememberPreference
import kotlinx.coroutines.launch

/**
 * Where being counted is turned on, off, and undone.
 *
 * Sits beside the questions section rather than inside it because they are two different bargains
 * and one switch for both would be consent in name only. See
 * [com.dd3boh.outertune.utils.ActiveCount] for exactly what a count carries.
 */
@Composable
fun ColumnScope.UsageCountFrag() {
    // Same reasoning as the questions section: a build with no Umami endpoint cannot count
    // anything, and offering the switch would be asking somebody to agree to something that then
    // never happens. The endpoint is the same one, so the same check answers for both.
    if (!Polls.isConfigured) {
        PreferenceEntry(
            title = { Text(stringResource(R.string.polls_unavailable)) },
            description = stringResource(R.string.polls_unavailable_description),
            icon = { Icon(Icons.Rounded.Groups, null) },
            isEnabled = false,
            onClick = null,
        )
        return
    }

    val coroutineScope = rememberCoroutineScope()
    val activeCount = LocalActiveCount.current

    val (enabled, onEnabledChange) = rememberPreference(UsageCountEnabledKey, defaultValue = false)

    SwitchPreference(
        title = { Text(stringResource(R.string.usage_count_enabled)) },
        description = stringResource(R.string.usage_count_enabled_description),
        icon = { Icon(Icons.Rounded.Groups, null) },
        checked = enabled,
        onCheckedChange = onEnabledChange,
    )

    if (enabled) {
        PreferenceEntry(
            title = { Text(stringResource(R.string.usage_count_forget)) },
            description = stringResource(R.string.usage_count_forget_description),
            icon = { Icon(Icons.Rounded.RestartAlt, null) },
            onClick = { coroutineScope.launch { activeCount.forget() } },
        )
    }
}
