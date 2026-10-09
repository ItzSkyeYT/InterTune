/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.ui.player

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection

/**
 * What plays music, laid out from left to right in every language: previous on the left and next
 * on the right, and a song's line filling from the left.
 *
 * A page turns round for a language read from right to left, and the buttons of a player turned
 * with it: in Arabic, Hebrew and Persian next stood where previous stands everywhere else, and
 * the line ran backwards. Material keeps playback controls out of that, and so do the other
 * players these listeners know. The title, the cover's row and the lists still turn, as text does.
 */
@Composable
internal fun PlaybackOrder(content: @Composable () -> Unit) =
    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr, content = content)
