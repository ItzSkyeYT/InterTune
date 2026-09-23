/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.ui.component.button

import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.dd3boh.outertune.ui.component.topBarSurface

/** One UI 8's back circle, measured on a Galaxy S25 Ultra: 180px at density 600. */
private val BackButtonSize = 48.dp

/**
 * Material's navigation slot already starts 4dp in, so 8dp more puts the circle's edge 12dp from
 * the screen's, where Samsung's is. The 4dp after it, with the 4dp Material puts before a title,
 * leaves the same 8dp between the circle and the title pill.
 */
private val BackButtonStart = 8.dp
private val BackButtonEnd = 4.dp

/**
 * Puts the back arrow on something instead of floating it against the screen.
 *
 * Never the app backdrop. The first version called drawBackdrop with it, the way the dock and the
 * mini player do, and it killed the process: those two are deliberately placed OUTSIDE the
 * published layer, while every screen reached through the nav host is inside it. A backdrop reader
 * inside the layer it reads makes that layer contain itself, and RenderNode::prepareTreeImpl
 * recurses until the native stack overflows. It cost a SIGSEGV on the RenderThread to learn that
 * it applies to anything inside the nav host, a back button included. The glass it has now reads
 * the screen's own backdrop from a layer drawn outside it; see TopBarGlass.kt.
 *
 * The size reaches the button inside through its constraints, so the ripple fills the whole
 * circle and the touch target is the circle's own 48dp. The clip is a plain clip, not a backdrop
 * read. The surface is the one the title pill uses, see topBarSurface.
 */
@Composable
fun Modifier.backButtonSurface(): Modifier = this
    .padding(start = BackButtonStart, end = BackButtonEnd)
    .size(BackButtonSize)
    .topBarSurface()
    .clip(CircleShape)
