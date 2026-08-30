/*
 * Copyright (C) 2025 OuterTune Project
 *
 * SPDX-License-Identifier: GPL-3.0
 *
 * For any other attributions, refer to the git commit history
 */
package com.dd3boh.outertune.ui.component

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.add
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsBottomHeight
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.windowInsetsTopHeight
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.dd3boh.outertune.LocalPlayerAwareWindowInsets

/**
 * The container every settings screen is built on.
 *
 * The content column is capped and centred. Without the cap a single switch on a tablet becomes a
 * 1200dp slab with its label at the far left and its toggle at the far right, which is a long way
 * for the eye to travel to work out which control belongs to which label.
 *
 * This also owns the vertical scroll. It used to live on [columnModifier] at each call site, which
 * meant only the capped column scrolled and a fling in the wide empty margin did nothing.
 */
@Composable
fun ColumnWithContentPadding(
    modifier: Modifier = Modifier,
    columnModifier: Modifier = Modifier,
    verticalArrangement: Arrangement.Vertical = Arrangement.Top,
    horizontalAlignment: Alignment.Horizontal = Alignment.Start,
    contentPadding: WindowInsets = LocalPlayerAwareWindowInsets.current,
    maxContentWidth: Dp = 720.dp,
    scrollState: ScrollState? = rememberScrollState(),
    content: @Composable (ColumnScope.() -> Unit)
) = Row(
    modifier = modifier
        // Needed, and easy to miss: call sites only pass fillMaxHeight, so without this the Row
        // wraps to the capped width and Arrangement.Center has nothing left to centre within.
        .fillMaxWidth()
        .windowInsetsPadding(contentPadding.only(WindowInsetsSides.Horizontal))
        .then(if (scrollState != null) Modifier.verticalScroll(scrollState) else Modifier),
    horizontalArrangement = Arrangement.Center
) {
    Column(
        // widthIn goes first. AboutScreen passes fillMaxWidth, which would otherwise hand down a
        // fixed width and swallow a cap applied after it.
        modifier = Modifier
            .widthIn(max = maxContentWidth)
            .then(columnModifier),
        verticalArrangement = verticalArrangement,
        horizontalAlignment = horizontalAlignment
    ) {
        Spacer(Modifier.windowInsetsTopHeight(contentPadding))
        content()
        Spacer(Modifier.windowInsetsBottomHeight(contentPadding.add(WindowInsets(bottom = 16.dp))))
    }
}
