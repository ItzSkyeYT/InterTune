/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.ui.screens.settings.fragments

import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.SmartToy
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.dd3boh.outertune.R
import com.dd3boh.outertune.constants.AiFilterKey
import com.dd3boh.outertune.models.AiFilterMode
import com.dd3boh.outertune.ui.component.EnumListPreference
import com.dd3boh.outertune.ui.component.ExplainButton
import com.dd3boh.outertune.ui.screens.settings.settingRow
import com.dd3boh.outertune.utils.rememberEnumPreference

/**
 * The AI filter's row (AiFilter, Unreleased.AI_FILTER): Off, Hide from recommendations or Block
 * everywhere. The line under it names the list it goes by as hand-made and says where it comes
 * from, since hiding an artist as AI is a claim about them made on a list's word; the (i) says
 * what each state hides and what it never touches.
 */
@Composable
fun ColumnScope.AiFilterFrag() {
    val (mode, onModeChange) = rememberEnumPreference(AiFilterKey, defaultValue = AiFilterMode.OFF)
    val title = stringResource(R.string.ai_filter)
    EnumListPreference(
        modifier = Modifier.settingRow(R.string.ai_filter),
        title = { Text(stringResource(R.string.ai_filter)) },
        icon = { Icon(Icons.Rounded.SmartToy, null) },
        trailingContent = { ExplainButton(title = title, body = stringResource(R.string.ai_filter_explain)) },
        content = {
            Text(
                text = stringResource(R.string.ai_filter_note),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp),
            )
        },
        selectedValue = mode,
        valueText = {
            when (it) {
                AiFilterMode.OFF -> stringResource(R.string.ai_filter_off)
                AiFilterMode.RECOMMENDATIONS -> stringResource(R.string.ai_filter_recommendations)
                AiFilterMode.EVERYWHERE -> stringResource(R.string.ai_filter_everywhere)
            }
        },
        onValueSelected = onModeChange,
    )
}
