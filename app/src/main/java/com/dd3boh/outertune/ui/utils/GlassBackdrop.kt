/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.ui.utils

import androidx.compose.runtime.staticCompositionLocalOf
import com.kyant.backdrop.backdrops.LayerBackdrop

/**
 * The app content stack, published as a backdrop for glass panels to refract.
 *
 * Null when liquid glass is off, unsupported on this device, or when the nav rail is in use, so a
 * consumer can simply check for null rather than duplicating the gating. Deliberately the only
 * backdrop in the app: several panels read it, none of them creates a second one, because a lens
 * over a flat fill costs a full offscreen pass and renders nothing.
 */
val LocalAppBackdrop = staticCompositionLocalOf<LayerBackdrop?> { null }
