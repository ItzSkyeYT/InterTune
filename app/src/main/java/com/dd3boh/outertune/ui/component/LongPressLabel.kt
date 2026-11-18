/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.ui.component

import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Box
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
 * [modifier] places the button in its parent, where the button's own placing modifier (an align) went. It
 * goes on the box around the tooltip, the one the parent places, and never into TooltipBox: material3 1.4.0
 * puts a TooltipBox's modifier on the inner box that holds the button, inside an outer box of its own at
 * the top left, so an align given to it centred the button in a box of its own size. The queue's seven
 * buttons sat at the left of their slots that way from 13 to 18 Nov 2026, the play button off the middle.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LongPressLabel(label: String, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Box(modifier) {
        TooltipBox(
            positionProvider = TooltipDefaults.rememberTooltipPositionProvider(TooltipAnchorPosition.Above),
            tooltip = { PlainTooltip { Text(label) } },
            state = rememberTooltipState(),
        ) {
            // On a long press (and a mouse's hover), as Android's own tooltips are, not at each button a keypad
            // or a keyboard moves to: material3 1.4.0 also shows the label when the button inside takes key focus
            // (its keyboardBehavior asks the focus it sees whether it is focused), and through a focus group it
            // sees only that something inside has focus. The keys still move from button to button as before.
            Box(Modifier.focusGroup()) { content() }
        }
    }
}
