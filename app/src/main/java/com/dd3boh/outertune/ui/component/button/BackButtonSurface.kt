/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.ui.component.button

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.dd3boh.outertune.ui.utils.rememberGlassSpec

/** The circle a top bar's navigation icon sits on. */
private val BackButtonSize = 40.dp

/**
 * Keeps the circle inside the touch target rather than replacing it.
 *
 * Sizing the button itself to 40dp took the slot's own breathing room with it and the circle ended
 * up against the edge of the screen. Padding first, size second: the node still measures the 48dp
 * a finger expects, and the visible circle sits centred inside it.
 */
private val BackButtonInset = 4.dp

/**
 * Puts the back arrow on something instead of floating it against the screen.
 *
 * A tint, not a lens, and that is not a shortcut. The first version called drawBackdrop with the
 * app backdrop, the way the dock and the mini player do, and it killed the process: those two are
 * deliberately placed OUTSIDE the published layer, while every screen reached through the nav host
 * is inside it. A backdrop reader inside the layer it reads makes that layer contain itself, and
 * RenderNode::prepareTreeImpl recurses until the native stack overflows. MainActivity carries a
 * comment saying exactly this, about exactly this layer. It cost a SIGSEGV on the RenderThread to
 * learn that it applies to anything inside the nav host, a back button included.
 *
 * So the circle is a translucent surface instead. On glass it borrows the glass tint, which is the
 * same colour and alpha the panels use, so the two agree without one of them reading the other. On
 * a plain theme it is an opaque grey. Both give the arrow an edge, which was the point.
 */
@Composable
fun Modifier.backButtonSurface(): Modifier {
    val glass = rememberGlassSpec()
    val fill = glass?.tint(min = 0.70f, max = 0.98f)
        ?: MaterialTheme.colorScheme.surfaceContainerHigh
    return this
        .padding(BackButtonInset)
        .size(BackButtonSize)
        .background(fill, CircleShape)
}
