/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.ui.player

import androidx.annotation.DrawableRes
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.media3.common.Player.REPEAT_MODE_ALL
import androidx.media3.common.Player.REPEAT_MODE_OFF
import androidx.media3.common.Player.REPEAT_MODE_ONE
import com.dd3boh.outertune.R
import com.dd3boh.outertune.constants.Unreleased

/**
 * How the repeat button shows its three states, his pick 3B of 15 Nov 2026 (#182: the grey of off
 * read as a button that does not work). Off is the plain repeat icon, as strong as the buttons
 * beside it. All is the same icon on a lit rounded box, one is the repeat-one icon on that box.
 * The box wears the play button's colours. Behind [Unreleased.REPEAT_LIT_BOX]: with it off, off is
 * the repeat icon greyed out, as the media notification draws it (his word of 5 Nov 2026), and
 * there is no box.
 *
 * The box is drawn around the button and takes no room, so the button keeps its place and size.
 */
internal object RepeatLook {
    /** How far the box reaches past the button on each side: a 32dp button gets the mockup's 38dp box. */
    val BoxOutset = 3.dp

    /** The box's corner as a share of its side, 12dp on the mockup's 38dp box: the same shape at every size. */
    const val BOX_CORNER = 12f / 38f

    /** How strong the box is while the button does not work (no song yet), as the icon is dimmed then. */
    const val DISABLED_ALPHA = 0.5f

    /** Off without the box: the repeat icon at this strength (5 Nov 2026). */
    const val OFF_ALPHA = 0.4f

    @DrawableRes
    fun icon(mode: Int): Int = if (mode == REPEAT_MODE_ONE) R.drawable.repeat_one else R.drawable.repeat_on

    /** Whether the box is lit behind the icon: all and one, with [litBox] on. */
    fun lit(mode: Int, litBox: Boolean = Unreleased.REPEAT_LIT_BOX): Boolean = litBox && (mode == REPEAT_MODE_ALL || mode == REPEAT_MODE_ONE)

    /** The icon's colour off the box: [row], the colour of the buttons beside it, greyed while off without the box. */
    fun iconColor(mode: Int, row: Color, litBox: Boolean = Unreleased.REPEAT_LIT_BOX): Color =
        if (!litBox && mode == REPEAT_MODE_OFF) row.copy(alpha = OFF_ALPHA) else row
}

/**
 * The repeat button of the player and of the queue's bar, in the three looks of [RepeatLook]. The
 * rest is [com.dd3boh.outertune.ui.component.button.ResizableIconButton]'s: [modifier] sizes and
 * places it, [color] is its row's colour for the icon while off.
 */
@Composable
internal fun RepeatButton(
    mode: Int,
    color: Color,
    enabled: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val lit = RepeatLook.lit(mode)
    val box = MaterialTheme.colorScheme.primary
    Image(
        painter = painterResource(RepeatLook.icon(mode)),
        contentDescription = null,
        colorFilter = ColorFilter.tint(if (lit) MaterialTheme.colorScheme.onPrimary else RepeatLook.iconColor(mode, color)),
        modifier = Modifier
            // First in the chain: the dimming below clips to the button's bounds, and the box reaches past them.
            .repeatBox(lit, if (enabled) box else box.copy(alpha = box.alpha * RepeatLook.DISABLED_ALPHA))
            .clickable(
                indication = ripple(bounded = false),
                interactionSource = remember { MutableInteractionSource() },
                enabled = enabled,
                onClick = onClick,
            )
            .alpha(if (enabled) 1f else RepeatLook.DISABLED_ALPHA)
            .then(modifier)
    )
}

/** The lit box behind the icon while repeat is on, [RepeatLook.BoxOutset] past the button's bounds; nothing while off. */
private fun Modifier.repeatBox(lit: Boolean, color: Color): Modifier =
    if (!lit) this
    else drawBehind {
        val out = RepeatLook.BoxOutset.toPx()
        val side = Size(size.width + out * 2, size.height + out * 2)
        drawRoundRect(color, topLeft = Offset(-out, -out), size = side, cornerRadius = CornerRadius(side.minDimension * RepeatLook.BOX_CORNER))
    }
