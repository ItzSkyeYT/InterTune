/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.ui.component

import androidx.compose.animation.Animatable
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.dp
import androidx.core.graphics.ColorUtils
import com.dd3boh.outertune.engine.Lane
import com.dd3boh.outertune.engine.RowMix
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.min

/** The places of the row in the picture, in two lines of ten. */
internal const val ROW_PLACES = 20
private const val ROW_ACROSS = 10

/**
 * The order the places are handed out in. Any stretch of it lies evenly over the row, so each kind
 * of card, which takes a stretch, is scattered through the row as the real one is and not stood in
 * a block. And a kind that gains a card takes the place next to its stretch, so one step of a
 * slider turns a few tiles and leaves the others as they were.
 */
internal val ROW_SPREAD = intArrayOf(0, 12, 4, 17, 9, 1, 14, 6, 18, 11, 3, 15, 8, 2, 13, 5, 16, 10, 7, 19)

/** The kinds in the order they take their stretch. Songs new to the listener come last. */
internal val ROW_KINDS = listOf(Lane.RELATED, Lane.AGAIN, Lane.ARTIST, Lane.REDISCOVER, Lane.EXPLORE)

/** How many cards of the kind a choice leads with stand at the head of the row. */
internal const val ROW_HEAD = 4

/**
 * Which kind of card stands in each of the row's places under [mix]. With a choice leading, its
 * kind has the first [ROW_HEAD] places and then the first stretch of what is left.
 */
internal fun rowPlaces(mix: RowMix): List<Lane> {
    val places = arrayOfNulls<Lane>(ROW_PLACES)
    val left = ROW_KINDS.associateWith { mix.cards[it] ?: 0 }.toMutableMap()
    val lead = mix.lead
    if (lead != null) {
        repeat(min(ROW_HEAD, left.getValue(lead))) { place ->
            places[place] = lead
            left[lead] = left.getValue(lead) - 1
        }
    }
    val free = ROW_SPREAD.filter { places[it] == null }.iterator()
    for (kind in listOfNotNull(lead) + ROW_KINDS.filter { it != lead }) {
        repeat(left.getValue(kind)) { if (free.hasNext()) places[free.next()] = kind }
    }
    // The kinds add up to the row, so nothing is left over: this is for a row that somehow is not.
    return places.map { it ?: Lane.RELATED }
}

/** How long a tile takes to turn to its new colour, in milliseconds. */
private const val TURN_MS = 420

/** How much later each further tile starts than the one before, so a change runs along the row. */
private const val STAGGER_MS = 34

/** How far a turning tile swells on the way, as a share of its size. */
private const val SWELL = 0.16f

/**
 * A colour for each kind of card. The kind most of a row is has the theme's own colour, three
 * more stand a quarter of the colour wheel from it and from each other, and old favourites gone
 * quiet are grey. All at the same strength, a little apart in lightness for eyes that tell
 * colours apart badly.
 */
@Composable
internal fun rowColours(): Map<Lane, Color> {
    val scheme = MaterialTheme.colorScheme
    val dark = scheme.surface.luminance() < 0.5f
    return remember(scheme.primary, scheme.outline, dark) {
        val own = FloatArray(3)
        ColorUtils.colorToHSL(scheme.primary.toArgb(), own)
        fun turned(by: Float, lift: Float) = Color(
            ColorUtils.HSLToColor(
                floatArrayOf((own[0] + by).mod(360f), own[1].coerceIn(0.5f, 0.8f), (if (dark) 0.68f else 0.46f) + lift)
            )
        )
        mapOf(
            Lane.RELATED to turned(0f, 0f),
            Lane.EXPLORE to turned(90f, 0.04f),
            Lane.AGAIN to turned(180f, -0.06f),
            Lane.ARTIST to turned(270f, 0.02f),
            Lane.REDISCOVER to scheme.outline,
        )
    }
}

/**
 * The Best recommendations row as twenty small tiles, each in the colour of its kind of card, as
 * the settings under it leave it: drag a slider or change the choice and the tiles that change
 * kind turn to their new colour, one after the other along the row and each with a small swell,
 * so that a change of two cards in twenty is seen. It has no words and no key: a colour is told
 * by the control that moves it.
 *
 * Nothing moves while nothing changes, so a picture left on screen draws nothing. On a phone with
 * animations off the tiles change at once.
 */
@Composable
fun RowPicturePanel(mix: RowMix, modifier: Modifier = Modifier) {
    val places = remember(mix) { rowPlaces(mix) }
    val colours = rowColours()
    // Each tile's colour and how far through its turn it is, read where the picture is drawn and
    // nowhere else: a turning tile draws the picture again and composes nothing.
    val shown = remember { List(ROW_PLACES) { Animatable(colours.getValue(places[it])) } }
    val turn = remember { List(ROW_PLACES) { Animatable(1f) } }
    val before = remember { arrayOfNulls<Lane>(ROW_PLACES) }
    LaunchedEffect(places, colours) {
        var waiting = 0
        places.forEachIndexed { place, kind ->
            val to = colours.getValue(kind)
            val changed = before[place] != null && before[place] != kind
            before[place] = kind
            if (changed) {
                // A change of kind: after the tiles that changed before it in the row.
                val wait = waiting++ * STAGGER_MS.toLong()
                launch {
                    delay(wait)
                    launch { shown[place].animateTo(to, tween(TURN_MS, easing = FastOutSlowInEasing)) }
                    turn[place].snapTo(0f)
                    turn[place].animateTo(1f, tween(TURN_MS + 140, easing = FastOutSlowInEasing))
                }
            } else if (shown[place].value != to || turn[place].value != 1f) {
                // Caught half way by the next change, or the theme changed under it: on to where it was going.
                launch { shown[place].animateTo(to, tween(TURN_MS, easing = FastOutSlowInEasing)) }
                launch { turn[place].animateTo(1f, tween(TURN_MS, easing = FastOutSlowInEasing)) }
            }
        }
    }
    BoxWithConstraints(modifier = modifier.fillMaxWidth()) {
        val edge = 14.dp
        val gap = 5.dp
        val tile = (maxWidth - edge * 2 - gap * (ROW_ACROSS - 1)) / ROW_ACROSS
        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .height(edge * 2 + tile * (ROW_PLACES / ROW_ACROSS) + gap * (ROW_PLACES / ROW_ACROSS - 1))
                .clip(RoundedCornerShape(20.dp))
                .background(MaterialTheme.colorScheme.surfaceContainerLowest)
                // A picture of what the lines under the sliders say in numbers: a screen reader is read those.
                .clearAndSetSemantics { }
        ) {
            val side = tile.toPx()
            val step = side + gap.toPx()
            for (place in 0 until ROW_PLACES) {
                // Born and gone at nothing, so the swell has no start and no end to see.
                val through = turn[place].value
                val grown = side * (1f + SWELL * 4f * through * (1f - through))
                val centre = Offset(
                    x = edge.toPx() + (place % ROW_ACROSS) * step + side / 2,
                    y = edge.toPx() + (place / ROW_ACROSS) * step + side / 2,
                )
                drawRoundRect(
                    color = shown[place].value,
                    topLeft = Offset(centre.x - grown / 2, centre.y - grown / 2),
                    size = Size(grown, grown),
                    cornerRadius = CornerRadius(grown * 0.24f),
                )
            }
        }
    }
}
