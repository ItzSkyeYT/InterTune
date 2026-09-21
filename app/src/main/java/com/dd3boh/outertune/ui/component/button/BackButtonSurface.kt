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
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.effects.blur
import com.kyant.backdrop.effects.lens
import com.kyant.backdrop.effects.vibrancy

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
 * A bare arrow over a transparent bar is the part that reads as dated, and it gives the finger no
 * edge to aim at either. Glass where the person has turned glass on, because that same panel
 * treatment already carries the dock and the mini player, and one opaque circle in that company
 * would look like a piece that had not been finished. A plain circle otherwise, rather than
 * nothing, so both settings get a target rather than only one of them.
 *
 * The lens numbers are far smaller than the dock's, deliberately. Those are tuned for a panel tens
 * of times this size, and a 24dp rim on a 40dp circle leaves no flat centre at all: the arrow ends
 * up drawn entirely within the refracted edge and smears. Chromatic aberration is off for the same
 * reason, since on something this small it only fringes the arrow.
 */
@Composable
fun Modifier.backButtonSurface(): Modifier {
    val glass = rememberGlassSpec()
    if (glass == null) {
        return this
            .padding(BackButtonInset)
            .size(BackButtonSize)
            .background(MaterialTheme.colorScheme.surfaceContainerHigh, CircleShape)
    }
    // Held more opaque than the dock: this circle sits over album art and list rows rather than
    // over a settled background, so a clear one loses the arrow against a bright cover.
    val tint = glass.tint(min = 0.70f, max = 0.98f)
    return this
        .padding(BackButtonInset)
        .size(BackButtonSize)
        .drawBackdrop(
            backdrop = glass.backdrop,
            shape = { CircleShape },
            effects = {
                vibrancy()
                blur(glass.blur.toPx())
                lens(
                    refractionHeight = 4f.dp.toPx() * glass.lensT,
                    refractionAmount = 8f.dp.toPx() * glass.lensT,
                    depthEffect = true,
                    chromaticAberration = false,
                )
            },
        )
        .background(tint, CircleShape)
}
