/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.ui.dialog

import androidx.annotation.StringRes
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.dd3boh.outertune.R
import com.dd3boh.outertune.ui.utils.Animations

/*
 * InterTune's developer in a room (together/DevBadge): a gold badge with a spanner on his circle, where the host's star
 * goes, and his name lit in gold, in the people list and in the line on who chooses the music. As in the mock he picked
 * on 22 Nov 2026 ("spanner"). Only a phone whose proof was checked is drawn so (TogetherSession.Member.dev), and this
 * phone's own circle while it holds the developer key (TogetherSession.Status.dev).
 */

/** The badge's gold, and the halo's round his name. */
private val Gold = Color(0xFFFFC94A)

/** The spanner on the gold. */
private val SpannerInk = Color(0xFF2A2000)

/** The marks the developer can wear: both drawn, [DEV_MARK] picks the one worn. */
internal enum class DevMark(val icon: ImageVector) {
    SPANNER(Icons.Filled.Build),
    SHIELD(Icons.Filled.Shield),
}

/** The spanner, his pick of 22 Nov 2026. */
internal val DEV_MARK = DevMark.SPANNER

/**
 * The developer's badge, the size and make of the host's star (Person): [DEV_MARK] dark on gold, with a ring of the
 * sheet's own colour, [ring], round it. TalkBack reads it as InterTune's developer.
 */
@Composable
internal fun DevBadgeMark(ring: Color, modifier: Modifier = Modifier) {
    Icon(
        DEV_MARK.icon,
        contentDescription = stringResource(R.string.together_developer),
        tint = SpannerInk,
        modifier = modifier
            .size(20.dp)
            .border(2.dp, ring, CircleShape)
            .padding(2.dp)
            .background(Gold, CircleShape)
            .padding(2.dp),
    )
}

/** His name's letters, the light that passes over them and the halo round them. */
private class NameInk(val letters: Color, val light: Color, val halo: Color)

/** On a dark sheet, as in the mock: white letters, a gold light, a gold halo. */
private val OnDark = NameInk(letters = Color.White, light = Color(0xFFFFD76A), halo = Gold.copy(alpha = 0.85f))

/** On a light sheet white letters would vanish: a deep gold, and a brighter one passing. */
private val OnLight = NameInk(letters = Color(0xFF6E5100), light = Color(0xFFC99700), halo = Gold.copy(alpha = 0.9f))

/** How far the halo spreads round the letters. */
private val HALO = 5.dp

/** One pass of the light and the rest after it, then the next: slow, and still most of the time. */
internal const val GLOW_CYCLE_MS = 5_000
internal const val GLOW_PASS_MS = 3_000

/** Half the light's width, in widths of the name. */
internal const val GLOW_HALF = 0.28f

/** Where the light rests with animations off: over the name's first letters, as in the mock. */
internal const val GLOW_STILL_AT = 0.35f

/**
 * Where the light's middle is, in widths of the name from its start, at [time] of a cycle (0 to 1): across the name
 * from just before it to just past it in [GLOW_PASS_MS], then out of it for the rest of the cycle.
 */
internal fun glowAt(time: Float): Float {
    val pass = time * GLOW_CYCLE_MS / GLOW_PASS_MS
    return if (pass >= 1f) 1f + 4 * GLOW_HALF else -GLOW_HALF + pass * (1f + 2 * GLOW_HALF)
}

/**
 * [text] with the developer's name, from [start] to [end], lit: its letters with a soft halo and a light passing over
 * them now and then, standing still with animations off (Animations.off). The rest of [text] as ever.
 */
@Composable
internal fun GlowingName(
    text: String,
    start: Int,
    end: Int,
    style: TextStyle,
    modifier: Modifier = Modifier,
    color: Color = Color.Unspecified,
    maxLines: Int = Int.MAX_VALUE,
    overflow: TextOverflow = TextOverflow.Clip,
    textAlign: TextAlign? = null,
) {
    val ink = if (MaterialTheme.colorScheme.surface.luminance() < 0.5f) OnDark else OnLight
    val halo = Shadow(ink.halo, Offset.Zero, with(LocalDensity.current) { HALO.toPx() })
    // Where the name is drawn, from the last layout: the light crosses that.
    var box by remember(text, start, end) { mutableStateOf<Rect?>(null) }
    val time = rememberInfiniteTransition(label = "developer's name")
        .animateFloat(0f, 1f, infiniteRepeatable(tween(GLOW_CYCLE_MS, easing = LinearEasing)), label = "light")
    // Composed again only while the light moves, not through the rest between passes.
    val at by remember { derivedStateOf { if (Animations.off) GLOW_STILL_AT else glowAt(time.value) } }
    val name = box
    val lit = if (name == null || name.width <= 0f) SpanStyle(color = ink.letters, shadow = halo) else {
        val middle = name.left + at * name.width
        val half = GLOW_HALF * name.width
        SpanStyle(
            brush = Brush.horizontalGradient(
                0f to ink.letters, 0.5f to ink.light, 1f to ink.letters,
                startX = middle - half, endX = middle + half, tileMode = TileMode.Clamp,
            ),
            shadow = halo,
        )
    }
    Text(
        buildAnnotatedString {
            append(text)
            addStyle(lit, start, end)
        },
        modifier = modifier,
        color = color,
        style = style,
        maxLines = maxLines,
        overflow = overflow,
        textAlign = textAlign,
        onTextLayout = { layout ->
            if (end <= layout.layoutInput.text.length) box = layout.getPathForRange(start, end).getBounds()
        },
    )
}

/** What a name is put in as first, to find where it lands in the words (NamedLine). */
private const val NAME_MARK = ""

/**
 * The words [id] with [name] put in for their %1$s, the name lit as the developer's when [glow] ([GlowingName]). Where
 * the name lands is found by a mark put in its place first, so a name that the words also hold is lit where it was put.
 */
@Composable
internal fun NamedLine(
    @StringRes id: Int,
    name: String,
    glow: Boolean,
    style: TextStyle,
    modifier: Modifier = Modifier,
    color: Color = Color.Unspecified,
) {
    val marked = stringResource(id, NAME_MARK)
    val at = marked.indexOf(NAME_MARK)
    if (!glow || at < 0) {
        Text(stringResource(id, name), modifier = modifier, style = style, color = color)
        return
    }
    val text = marked.substring(0, at) + name + marked.substring(at + NAME_MARK.length).replace(NAME_MARK, name)
    GlowingName(text, at, at + name.length, style = style, modifier = modifier, color = color)
}
