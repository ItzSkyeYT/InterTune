/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.ui.component

import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.PlainTooltip
import androidx.compose.material3.Text
import androidx.compose.material3.TooltipAnchorPosition
import androidx.compose.material3.TooltipBox
import androidx.compose.material3.TooltipDefaults
import androidx.compose.material3.rememberTooltipState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/**
 * A long press on [content] says what it does: its [label] in a small tooltip above it, as Android's own
 * icon buttons do. For icon buttons whose icon alone may not say it (Niko in #testers, 11 Nov 2026: "itd
 * be nice if you long pressed the button itd say what it does"). The label is the button's own name, the
 * one screen readers already hear, so the two never disagree. A tap is still the button's.
 *
 * [modifier] places the button in its parent, where the button's own placing modifier (an align) went.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LongPressLabel(label: String, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    TooltipBox(
        positionProvider = TooltipDefaults.rememberTooltipPositionProvider(TooltipAnchorPosition.Above),
        tooltip = { PlainTooltip { Text(label) } },
        state = rememberTooltipState(),
        modifier = modifier,
        content = content,
    )
}
