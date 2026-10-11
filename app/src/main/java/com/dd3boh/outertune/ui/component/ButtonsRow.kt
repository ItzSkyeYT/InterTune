/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.ui.component

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.RowScope
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier

/**
 * The buttons at the foot of a dialog or a bubble, kept to its end.
 *
 * Every button is as wide as its label. A Row gives the last one what the others leave, and in
 * French, or at a large font size, that was a sliver: "Faites-moi visiter" stood in a tall narrow
 * pill, broken inside its words. Here a button with no room left beside the others goes on a line
 * of its own under them, still at the end.
 */
@Composable
fun ButtonsRow(modifier: Modifier = Modifier, content: @Composable RowScope.() -> Unit) = FlowRow(
    modifier = modifier,
    horizontalArrangement = Arrangement.End,
    itemVerticalAlignment = Alignment.CenterVertically,
    content = content,
)
