/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.ui.screens.walkthrough

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import com.dd3boh.outertune.LocalPlayerAwareWindowInsets
import com.dd3boh.outertune.R
import kotlin.math.roundToInt

/** Space between the cut-out and the bubble that describes it. */
private val GAP = 10.dp

/** The bubble's beak. */
private val TAIL = 9.dp

/** Breathing room between the cut-out and the control inside it. */
private val HOLE_PADDING = 8.dp
private val HOLE_RADIUS = 14.dp

/** The bubble never touches a screen edge. */
private val SCREEN_MARGIN = 16.dp
private val MAX_BUBBLE_WIDTH = 320.dp

private const val SCRIM_ALPHA = 0.72f

/**
 * The tour: the screen dimmed, a hole over the control being described, and a bubble attached to it.
 *
 * Attached is the entire point. The first version pinned the text to the top or bottom edge of the
 * screen and drew a separate arrow floating near the target, and the two read as unrelated objects:
 * the words were nowhere near the thing they described and the arrow came out of nothing. A bubble
 * with a beak is one object, so the eye travels from the sentence to the control without being asked.
 *
 * Drawn over everything rather than inside any screen, because it points at controls belonging to
 * several screens and has to survive moving between them.
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

    val density = LocalDensity.current
    val gapPx = with(density) { GAP.toPx() }
    val tailPx = with(density) { TAIL.toPx() }
    val holePadPx = with(density) { HOLE_PADDING.toPx() }
    val holeRadiusPx = with(density) { HOLE_RADIUS.toPx() }
    val marginPx = with(density) { SCREEN_MARGIN.toPx() }

    // The hole travels between targets rather than teleporting, so it is possible to see where it
    // went and therefore what is being talked about now.
    val spring = tween<Float>(320, easing = FastOutSlowInEasing)
    val l by animateFloatAsState(target?.left ?: 0f, spring, label = "l")
    val t by animateFloatAsState(target?.top ?: 0f, spring, label = "t")
    val r by animateFloatAsState(target?.right ?: 0f, spring, label = "r")
    val b by animateFloatAsState(target?.bottom ?: 0f, spring, label = "b")
    val hole = if (target == null) null else Rect(l, t, r, b)

    // Recorded rather than derived, so the beak is drawn from where the bubble actually ended up
    // after being clamped to the screen, not from where it would have liked to be.
    var bubble by remember { mutableStateOf<Rect?>(null) }
    // Read here rather than in the draw block: a draw scope is not a composition and cannot see
    // the theme. The beak has to be the bubble's own colour or it stops looking attached to it.
    val bubbleColour = MaterialTheme.colorScheme.surfaceContainerHigh
    val insets = LocalPlayerAwareWindowInsets.current

    Layout(
        content = {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .drawBehind {
                        // One layer, so the clear actually clears rather than punching through the
                        // window to the wallpaper behind the app.
                        drawContext.canvas.saveLayer(Rect(Offset.Zero, size), Paint())
                        drawRect(color = Color.Black.copy(alpha = SCRIM_ALPHA))
                        hole?.let { h ->
                            drawRoundRect(
                                color = Color.Black,
                                topLeft = Offset(h.left - holePadPx, h.top - holePadPx),
                                size = Size(h.width + holePadPx * 2, h.height + holePadPx * 2),
                                cornerRadius = CornerRadius(holeRadiusPx, holeRadiusPx),
                                blendMode = BlendMode.Clear,
                            )
                        }
                        drawContext.canvas.restore()

                        // The beak, after the layer is restored so it is not cut away with the hole.
                        val bub = bubble
                        if (hole != null && bub != null) {
                            val below = bub.top > hole.bottom
                            val tipY = if (below) bub.top - tailPx else bub.bottom + tailPx
                            val baseY = if (below) bub.top + 1f else bub.bottom - 1f
                            // Pinned to the target, then kept inside the bubble's own corners so it
                            // never grows out of thin air beside it.
                            val x = hole.center.x.coerceIn(
                                bub.left + tailPx * 2f,
                                (bub.right - tailPx * 2f).coerceAtLeast(bub.left + tailPx * 2f),
                            )
                            drawPath(
                                Path().apply {
                                    moveTo(x, tipY)
                                    lineTo(x - tailPx, baseY)
                                    lineTo(x + tailPx, baseY)
                                    close()
                                },
                                color = bubbleColour,
                            )
                        }
                    }
            )

            TourBubble(
                state = state,
                stop = stop,
                onNavigate = onNavigate,
                onFinish = onFinish,
                modifier = Modifier
                    .widthIn(max = MAX_BUBBLE_WIDTH)
                    .onGloballyPositioned { bubble = it.boundsInRoot() },
            )
        },
    ) { measurables, constraints ->
        val scrim = measurables[0].measure(constraints)
        val topInset = insets.getTop(this)
        val bottomInset = insets.getBottom(this)

        val card = measurables[1].measure(
            Constraints(
                maxWidth = (constraints.maxWidth - marginPx * 2).roundToInt().coerceAtLeast(0),
                maxHeight = (constraints.maxHeight - topInset - bottomInset).coerceAtLeast(0),
            )
        )

        layout(constraints.maxWidth, constraints.maxHeight) {
            scrim.place(0, 0)

            if (hole == null) {
                // Nothing to point at: the welcome simply sits in the middle.
                card.place(
                    x = (constraints.maxWidth - card.width) / 2,
                    y = (constraints.maxHeight - card.height) / 2,
                )
                return@layout
            }

            val needed = card.height + gapPx + holePadPx + tailPx
            val roomBelow = constraints.maxHeight - bottomInset - hole.bottom
            val below = roomBelow >= needed

            val y = if (below) hole.bottom + holePadPx + tailPx + gapPx
            else hole.top - holePadPx - tailPx - gapPx - card.height

            // Centred on the target, then pulled back inside the screen. The beak stays on the
            // target regardless, which is why it is drawn from the bubble's final position.
            val x = (hole.center.x - card.width / 2f).coerceIn(
                marginPx,
                (constraints.maxWidth - marginPx - card.width).coerceAtLeast(marginPx),
            )

            card.place(
                x = x.roundToInt(),
                y = y.roundToInt().coerceIn(
                    topInset,
                    (constraints.maxHeight - bottomInset - card.height).coerceAtLeast(topInset),
                ),
            )
        }
    }
}

/** The bubble. Title, a sentence, and the three things you can do about it. */
@Composable
private fun TourBubble(
    state: TourState,
    stop: TourStop,
    onNavigate: (String) -> Unit,
    onFinish: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        shape = MaterialTheme.shapes.large,
        tonalElevation = 3.dp,
        modifier = modifier,
    ) {
        Column(modifier = Modifier.padding(horizontal = 18.dp, vertical = 16.dp)) {
            Text(
                text = stringResource(stop.title),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
            )
            Spacer(Modifier.height(6.dp))
            Text(
                text = stringResource(stop.body),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(14.dp))
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    text = stringResource(
                        R.string.walkthrough_progress, state.index + 1, state.stops.size
                    ),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.weight(1f))
                TextButton(onClick = { state.stop(); onFinish() }) {
                    Text(stringResource(R.string.walkthrough_skip))
                }
                Spacer(Modifier.width(4.dp))
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
