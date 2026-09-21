/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.ui.screens.walkthrough

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.dd3boh.outertune.LocalPlayerAwareWindowInsets
import com.dd3boh.outertune.R

/**
 * The tour itself: a dimmed screen with a hole over the thing being talked about, and an arrow.
 *
 * Drawn over everything rather than inside any screen, because it points at things belonging to
 * several screens and has to survive navigating between them.
 *
 * The hole is cut rather than drawn. A ring around the target would have to guess the target's
 * shape; clearing a rounded rectangle out of the scrim shows whatever is actually under it, so a
 * circular button looks circular and a row of chips looks like a row of chips.
 */
@Composable
fun TourOverlay(
    state: TourState,
    onNavigate: (String) -> Unit,
    onFinish: () -> Unit,
) {
    if (!state.running) return
    val stop = state.current ?: return

    val target = stop.targetId?.let { TourTargets[it] }

    BackHandler {
        if (state.index > 0) state.back() else { state.stop(); onFinish() }
    }

    // Breathing, so the hole reads as something being pointed at rather than as a rendering fault.
    val pulse by rememberInfiniteTransition(label = "tour").animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(1_100), RepeatMode.Reverse),
        label = "pulse",
    )

    val density = LocalDensity.current
    val padPx = with(density) { 8.dp.toPx() }
    val radiusPx = with(density) { 16.dp.toPx() }

    Box(modifier = Modifier.fillMaxSize()) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            // One layer, so the clear actually clears instead of punching a hole in the window and
            // showing the wallpaper.
            drawContext.canvas.saveLayer(Rect(Offset.Zero, size), androidx.compose.ui.graphics.Paint())
            drawRect(color = Color.Black.copy(alpha = 0.78f))

            target?.let { t ->
                val grow = padPx + pulse * padPx * 0.5f
                val hole = Rect(
                    left = t.left - grow,
                    top = t.top - grow,
                    right = t.right + grow,
                    bottom = t.bottom + grow,
                )
                drawRoundRect(
                    color = Color.Black,
                    topLeft = Offset(hole.left, hole.top),
                    size = Size(hole.width, hole.height),
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(radiusPx, radiusPx),
                    blendMode = BlendMode.Clear,
                )
            }
            drawContext.canvas.restore()

            // The arrow, from the card towards the hole. Drawn after the scrim so it sits on top
            // of it rather than being cut away with the hole.
            target?.let { t ->
                val below = t.bottom < size.height / 2
                // Long enough to read as an arrow rather than a tick, and stopping short of the
                // hole so the head is not swallowed by the thing it points at.
                val from = Offset(t.center.x, if (below) t.bottom + padPx * 9 else t.top - padPx * 9)
                val to = Offset(t.center.x, if (below) t.bottom + padPx * 2f else t.top - padPx * 2f)
                drawArrow(from, to, Color.White)
            }
        }

        // The card goes on the opposite side of the target from the edge it is nearest, so it never
        // covers the thing it is describing.
        val cardAlignment = when {
            target == null -> Alignment.Center
            target.center.y < with(density) { 360.dp.toPx() } -> Alignment.BottomCenter
            else -> Alignment.TopCenter
        }

        Card(
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            shape = RoundedCornerShape(20.dp),
            modifier = Modifier
                .align(cardAlignment)
                // The player aware insets, not safeDrawing. The thing that was eating the card was
                // the app's own bottom navigation bar and mini player, which are ordinary content
                // and so appear in no system inset at all. It sat behind them and took Skip and
                // Next with it, which on a tour nobody can leave is the worst thing to lose.
                .windowInsetsPadding(LocalPlayerAwareWindowInsets.current)
                .padding(20.dp),
        ) {
            Column(modifier = Modifier.padding(20.dp)) {
                Text(
                    text = stringResource(R.string.walkthrough_progress, state.index + 1, state.stops.size),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    text = stringResource(stop.title),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    text = stringResource(stop.body),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(16.dp))
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    TextButton(onClick = { state.stop(); onFinish() }) {
                        Text(stringResource(R.string.walkthrough_skip))
                    }
                    Spacer(Modifier.weight(1f))
                    if (state.index > 0) {
                        TextButton(onClick = {
                            state.back()
                            state.current?.route?.let(onNavigate)
                        }) { Text(stringResource(R.string.walkthrough_back)) }
                    }
                    Button(onClick = {
                        val wasLast = state.index == state.stops.lastIndex
                        state.next()
                        if (wasLast) onFinish() else state.current?.route?.let(onNavigate)
                    }) {
                        Text(
                            stringResource(
                                if (state.index == state.stops.lastIndex) R.string.walkthrough_done
                                else R.string.walkthrough_next
                            )
                        )
                    }
                }
            }
        }
    }
}

/** A straight shaft with a head, pointing from [from] to [to]. */
private fun DrawScope.drawArrow(from: Offset, to: Offset, color: Color) {
    val stroke = 4f
    drawLine(color = color, start = from, end = to, strokeWidth = stroke)

    val direction = to - from
    val length = kotlin.math.hypot(direction.x, direction.y).takeIf { it > 0f } ?: return
    val unit = Offset(direction.x / length, direction.y / length)
    val perpendicular = Offset(-unit.y, unit.x)
    val head = 18f

    val path = Path().apply {
        moveTo(to.x, to.y)
        lineTo(to.x - unit.x * head + perpendicular.x * head * 0.5f, to.y - unit.y * head + perpendicular.y * head * 0.5f)
        lineTo(to.x - unit.x * head - perpendicular.x * head * 0.5f, to.y - unit.y * head - perpendicular.y * head * 0.5f)
        close()
    }
    drawPath(path, color)
}
