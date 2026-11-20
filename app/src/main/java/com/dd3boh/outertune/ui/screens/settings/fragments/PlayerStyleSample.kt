/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.ui.screens.settings.fragments

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.QueueMusic
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.SkipPrevious
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.platform.LocalDensity
import com.dd3boh.outertune.R
import com.dd3boh.outertune.constants.PlayerButtonsStyle
import com.dd3boh.outertune.constants.Unreleased
import com.dd3boh.outertune.ui.player.PinPlace
import com.dd3boh.outertune.ui.player.PlayerPin
import com.dd3boh.outertune.ui.player.PlayerPins

/** The picture's width and height in its own units: those of the two pictures it replaces, at half their pixels. */
const val SAMPLE_WIDTH = 336f
const val SAMPLE_HEIGHT = 202.5f

private val Lavender = Color(0xFFBAC6EF)
private val Navy = Color(0xFF2E3A59)
private val DarkSegment = Color(0xFF34405F)
private val Backdrop = Brush.linearGradient(
    listOf(Color(0xFF3F3735), Color(0xFF55403C), Color(0xFF6B6157)),
    start = Offset.Zero,
    end = Offset.Infinite,
)

/** One button in a style's picture: the queue when it is a button, a pinned one, or the menu. */
sealed interface SampleButton {
    data object Queue : SampleButton
    data class Pin(val pin: PlayerPin) : SampleButton
    data object Menu : SampleButton
}

/**
 * The buttons a style's picture shows, in the order the player draws them (Player.kt): Classic's circles by the title
 * (the queue first when it is a button, then the pins as they fit beside it, then the menu), Connected's segments by
 * the title, and Connected's row under the controls (the queue, the pins, the menu). The cast button only where the
 * app casts.
 */
data class SampleButtons(val byTitle: List<SampleButton>, val underControls: List<SampleButton>)

fun sampleButtons(style: PlayerButtonsStyle, pins: Map<PinPlace, List<PlayerPin>>, queueButton: Boolean, casting: Boolean = Unreleased.CASTING): SampleButtons {
    fun shown(list: List<PlayerPin>) = list.filter { it != PlayerPin.CAST || casting }.map { SampleButton.Pin(it) }
    val queue = if (queueButton) listOf(SampleButton.Queue) else emptyList()
    return when (style) {
        PlayerButtonsStyle.CLASSIC -> SampleButtons(
            byTitle = queue + shown(PlayerPins.fit(PinPlace.CLASSIC, pins[PinPlace.CLASSIC].orEmpty(), PlayerPins.classicMost(queueButton))) +
                    SampleButton.Menu,
            underControls = emptyList(),
        )
        PlayerButtonsStyle.CONNECTED -> SampleButtons(
            byTitle = shown(pins[PinPlace.CONNECTED_TITLE].orEmpty()),
            underControls = queue + shown(pins[PinPlace.CONNECTED_ROW].orEmpty()) + SampleButton.Menu,
        )
    }
}

/**
 * The picture of a player style in Look and feel, drawn from the buttons as they are pinned (his yes of 20 Nov 2026:
 * the two still pictures of September showed a sleep timer Connected's row no longer has, and no cast button). Shapes
 * and colours only, as those pictures had them: the title as two bars, the seek line, the controls in their bar, and
 * the buttons where the style puts them.
 */
@Composable
fun PlayerStyleSample(
    style: PlayerButtonsStyle,
    pins: Map<PinPlace, List<PlayerPin>>,
    queueButton: Boolean,
    icon: @Composable (PlayerPin) -> Painter,
    modifier: Modifier = Modifier,
) {
    val buttons = sampleButtons(style, pins, queueButton)
    BoxWithConstraints(
        modifier = modifier
            .aspectRatio(SAMPLE_WIDTH / SAMPLE_HEIGHT)
            .background(Backdrop)
    ) {
        val u = maxWidth / SAMPLE_WIDTH
        val connected = style == PlayerButtonsStyle.CONNECTED
        // Connected sits higher, to leave room for its row under the controls.
        val lift = if (connected) 13f else 0f

        @Composable
        fun painterOf(button: SampleButton): Painter = when (button) {
            SampleButton.Menu -> rememberVectorPainter(Icons.Rounded.MoreVert)
            SampleButton.Queue -> rememberVectorPainter(Icons.AutoMirrored.Rounded.QueueMusic)
            is SampleButton.Pin -> icon(button.pin)
        }

        // The title and the artist.
        Spot(u, 24f, 25f - lift, 126f, 10f) { Bar(Color.White.copy(alpha = 0.45f)) }
        Spot(u, 24f, 42f - lift, 76f, 7f) { Bar(Color.White.copy(alpha = 0.3f)) }

        // By the title: circles in Classic, one bar of segments in Connected, ending at the right margin.
        if (!connected) {
            val d = 27f
            val gap = 4.5f
            var x = 312f - buttons.byTitle.size * d - (buttons.byTitle.size - 1) * gap
            buttons.byTitle.forEach { button ->
                Spot(u, x, 24f, d, d) {
                    Box(Modifier.matchParentSize().background(Lavender, CircleShape))
                    Icon(painterOf(button), null, tint = Navy, modifier = Modifier.align(Alignment.Center).size(u * 14f))
                }
                x += d + gap
            }
        } else if (buttons.byTitle.isNotEmpty()) {
            val w = 39f
            val h = 29f
            var x = 312f - buttons.byTitle.size * w
            buttons.byTitle.forEachIndexed { i, button ->
                val liked = button == SampleButton.Pin(PlayerPin.LIKE)
                Spot(u, x, 10f, w, h) {
                    Box(
                        Modifier
                            .matchParentSize()
                            .background(if (liked) Lavender else DarkSegment, segmentShape(u, i == 0, i == buttons.byTitle.lastIndex, h))
                    )
                    Icon(painterOf(button), null, tint = if (liked) Navy else Lavender, modifier = Modifier.align(Alignment.Center).size(u * 13f))
                }
                x += w
            }
        }

        // The seek line, its thumb and the time.
        Spot(u, 24f, 63f - lift, 3f, 14f) { Bar(Lavender) }
        Spot(u, 31f, 72f - lift, 281f, 8f) { Bar(DarkSegment.copy(alpha = 0.9f)) }
        Spot(u, 302f, 74.5f - lift, 3f, 3f) { Box(Modifier.matchParentSize().background(Lavender, CircleShape)) }
        Spot(u, 27f, 92f - lift, 40f, 12f) {
            // In picture units like the rest, so a bigger font in Android's settings does not burst it.
            Text("0:00", color = Color.White.copy(alpha = 0.9f), fontSize = with(LocalDensity.current) { (u * 8.5f).toSp() })
        }

        // The controls in their bar.
        Spot(u, 24f, 116f - lift, 288f, 54f) {
            Box(
                Modifier
                    .matchParentSize()
                    .background(Color.White.copy(alpha = 0.12f), RoundedCornerShape(u * 27f))
                    .border(u * 0.75f, Color.White.copy(alpha = 0.35f), RoundedCornerShape(u * 27f))
            )
        }
        Spot(u, 45f, 136f - lift, 14f, 14f) { Glyph(painterResource(R.drawable.shuffle_off), Color.White.copy(alpha = 0.55f)) }
        Spot(u, 100f, 136f - lift, 14f, 14f) { Glyph(rememberVectorPainter(Icons.Rounded.SkipPrevious), Color.White) }
        Spot(u, 141f, 116f - lift, 54f, 54f) {
            Box(Modifier.matchParentSize().background(Lavender, CircleShape))
            Icon(rememberVectorPainter(Icons.Rounded.PlayArrow), null, tint = Navy, modifier = Modifier.align(Alignment.Center).size(u * 22f))
        }
        Spot(u, 222f, 136f - lift, 14f, 14f) { Glyph(rememberVectorPainter(Icons.Rounded.SkipNext), Color.White) }
        Spot(u, 277f, 136f - lift, 14f, 14f) { Glyph(painterResource(R.drawable.repeat_on), Color.White.copy(alpha = 0.55f)) }

        // Connected's row under the controls, centred.
        if (connected && buttons.underControls.isNotEmpty()) {
            val w = 54f
            val h = 32f
            val gap = 1.5f
            var x = (SAMPLE_WIDTH - buttons.underControls.size * w - (buttons.underControls.size - 1) * gap) / 2f
            buttons.underControls.forEachIndexed { i, button ->
                Spot(u, x, 165f, w, h) {
                    Box(
                        Modifier
                            .matchParentSize()
                            .background(Color.White.copy(alpha = 0.15f), segmentShape(u, i == 0, i == buttons.underControls.lastIndex, h))
                    )
                    Icon(painterOf(button), null, tint = Color.White, modifier = Modifier.align(Alignment.Center).size(u * 13f))
                }
                x += w + gap
            }
        }
    }
}

/** A box at ([x], [y]) of [w] by [h] picture units, [u] long each. */
@Composable
private fun BoxScope.Spot(u: Dp, x: Float, y: Float, w: Float, h: Float, content: @Composable BoxScope.() -> Unit) {
    Box(Modifier.offset(u * x, u * y).size(u * w, u * h), content = content)
}

@Composable
private fun BoxScope.Bar(color: Color) {
    Box(Modifier.matchParentSize().background(color, RoundedCornerShape(50)))
}

@Composable
private fun BoxScope.Glyph(painter: Painter, tint: Color) {
    Icon(painter, null, tint = tint, modifier = Modifier.matchParentSize())
}

/** A segment of a connected bar: round at the bar's own ends, nearly square where it meets its neighbours. */
private fun segmentShape(u: Dp, first: Boolean, last: Boolean, height: Float): Shape {
    val outer = u * (height / 2f)
    val inner = u * 4f
    return RoundedCornerShape(
        topStart = if (first) outer else inner,
        bottomStart = if (first) outer else inner,
        topEnd = if (last) outer else inner,
        bottomEnd = if (last) outer else inner,
    )
}
