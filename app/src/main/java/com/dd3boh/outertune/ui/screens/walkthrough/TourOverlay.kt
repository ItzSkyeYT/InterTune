/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.ui.screens.walkthrough

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.dd3boh.outertune.LocalPlayerAwareWindowInsets
import com.dd3boh.outertune.R
import kotlin.math.roundToInt
import kotlinx.coroutines.launch

/**
 * Breathing room between the cut-out and the control inside it.
 *
 * Ten is what driver.js, react-joyride and intro.js all landed on independently, for their
 * stage/spotlight/helper padding respectively. Three libraries arriving at the same number from
 * different codebases is a better argument than anything I would reason my way to.
 */
private val HOLE_PADDING = 10.dp
private val HOLE_RADIUS = 12.dp

/**
 * The beak, at Material 3's own size.
 *
 * `TooltipDefaults.caretSize` is 16dp by 8dp, and every other system in the sample draws the same
 * 2:1 width-to-depth wedge: Radix 10x5, Carbon 12x6, MDC roughly 20x10. Under about 10dp wide it
 * reads as a rendering artefact and over about 24dp it reads as a comic speech balloon.
 */
private val BEAK_WIDTH = 16.dp
private val BEAK_DEPTH = 8.dp

/** How far short of the cut-out the tip stops. Systems that look right land within 0 to 4. */
private val BEAK_TIP_GAP = 2.dp

/** Declared here rather than taken from the theme so the beak's clamp and the shape agree. */
private val BUBBLE_RADIUS = 16.dp

/** The bubble never touches a screen edge. */
private val SCREEN_MARGIN = 16.dp

/**
 * One width for the whole tour.
 *
 * Spectrum asks for it outright, and the number matters as much as the constancy. Material 3 caps
 * a rich tooltip at 320dp, but 320 on a 360dp phone is 89% of the screen: the clamp to the margin
 * then fires on every single step, the bubble sits in the same place no matter where the target is,
 * and the thing is anchored in the arithmetic while still reading as a sheet. At 280 there is
 * enough slack left that centring on the target visibly moves it.
 */
private val BUBBLE_WIDTH = 280.dp

/** driver.js ships 0.7, intro.js and react-joyride 0.5. Dark enough to isolate, light enough to place. */
private const val SCRIM_ALPHA = 0.7f

/** Material's own travel curve, at Material's own medium duration. */
private val EMPHASIZED = CubicBezierEasing(0.2f, 0f, 0f, 1f)
private const val TRAVEL_MS = 400

/**
 * The tour: the screen dimmed, a hole over the control being described, and a bubble attached to it.
 *
 * Attached is the entire point. The first version pinned the text to the top or bottom edge of the
 * screen and drew a separate arrow floating near the target, and the two read as unrelated objects.
 * That is not a matter of taste: the spatial contiguity effect is the best-evidenced thing in this
 * whole area, at g = 0.63 over 58 comparisons, and it says a caption away from what it describes is
 * measurably harder to understand. So the words sit against the control, and the beak bridges the
 * twelve device-independent pixels between them.
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
    val holePadPx = with(density) { HOLE_PADDING.toPx() }
    val holeRadiusPx = with(density) { HOLE_RADIUS.toPx() }
    val beakHalfPx = with(density) { BEAK_WIDTH.toPx() } / 2f
    val beakDepthPx = with(density) { BEAK_DEPTH.toPx() }
    val cornerPx = with(density) { BUBBLE_RADIUS.toPx() }
    val marginPx = with(density) { SCREEN_MARGIN.toPx() }

    /** Cut-out edge to bubble body. The beak occupies all but [BEAK_TIP_GAP] of it. */
    val standoff = holePadPx + with(density) { BEAK_TIP_GAP.toPx() } + beakDepthPx

    // The hole travels between targets rather than teleporting, so it is possible to see where it
    // went and therefore what is being talked about now. The first one is snapped rather than
    // animated: from a zero rect it would otherwise grow out of the top left corner of the screen.
    val left = remember { Animatable(0f) }
    val top = remember { Animatable(0f) }
    val right = remember { Animatable(0f) }
    val bottom = remember { Animatable(0f) }
    var seeded by remember { mutableStateOf(false) }

    LaunchedEffect(target) {
        val t = target ?: return@LaunchedEffect
        val edges = listOf(left to t.left, top to t.top, right to t.right, bottom to t.bottom)
        if (!seeded) {
            seeded = true
            edges.forEach { (a, v) -> a.snapTo(v) }
        } else {
            val spec = tween<Float>(TRAVEL_MS, easing = EMPHASIZED)
            edges.forEach { (a, v) -> launch { a.animateTo(v, spec) } }
        }
    }

    val hole = if (target == null || !seeded) null
    else Rect(left.value, top.value, right.value, bottom.value)

    // Material grows the highlighted control by 10% over a second and shrinks it back. Pulsing a
    // ring rather than the cut-out itself, because animating the hole would make the control's own
    // edges breathe in and out of the dim.
    val pulse by rememberInfiniteTransition(label = "pulse").animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            tween(1000, easing = FastOutLinearInEasing), RepeatMode.Reverse
        ),
        label = "pulse",
    )

    // Only the bubble's size round-trips through layout, and with a fixed width it does not change
    // while the hole is travelling. Its position is computed from the same function the placement
    // uses, so the beak and the bubble cannot disagree with each other mid-flight. Deriving the
    // beak from a reported position instead put it a frame behind for the whole 400ms.
    var cardSize by remember { mutableStateOf(Size.Zero) }

    // Read here rather than in the draw block: a draw scope is not a composition and cannot see
    // the theme. The beak has to be the bubble's own colour or it stops looking attached to it,
    // and the ring shares it so that bubble, beak and target are one colour against the scrim.
    val accent = MaterialTheme.colorScheme.surfaceContainerHigh
    val insets = LocalPlayerAwareWindowInsets.current

    // The first bubble scales up out of its own beak, the way Material grows a tooltip from the
    // corner of its anchor. Only the first: between steps the bubble travels with the hole, and a
    // second animation on top of that is how the two came apart in the first place.
    val appear = remember { Animatable(0f) }
    LaunchedEffect(Unit) { appear.animateTo(1f, tween(180, easing = FastOutSlowInEasing)) }

    fun placementFor(d: Density, w: Float, h: Float): Placement? {
        if (cardSize == Size.Zero) return null
        return placeBubble(
            hole = hole,
            card = cardSize,
            screen = Size(w, h),
            topInset = insets.getTop(d).toFloat(),
            bottomInset = insets.getBottom(d).toFloat(),
            holePad = holePadPx,
            standoff = standoff,
            margin = marginPx,
            cornerInset = cornerPx + beakHalfPx,
        )
    }

    val advance: () -> Unit = {
        val wasLast = state.index == state.stops.lastIndex
        state.next()
        if (wasLast) onFinish() else state.current?.route?.let(onNavigate)
        Unit
    }

    Layout(
        content = {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    // The overlay is drawn inline rather than in its own window, so without this
                    // every tap lands on the app underneath: it is possible to open the very
                    // control the tour is pointing at and end up somewhere it cannot follow.
                    // Tapping the highlighted control moves on, which is what it looks like it
                    // should do; tapping the dim does nothing, because Skip is right there.
                    .pointerInput(hole) {
                        detectTapGestures { at ->
                            val h = hole ?: return@detectTapGestures
                            if (h.inflate(holePadPx).contains(at)) advance()
                        }
                    }
                    .drawBehind {
                        // One layer, so the clear actually clears rather than punching through the
                        // window to the wallpaper behind the app.
                        drawContext.canvas.saveLayer(Rect(Offset.Zero, size), Paint())
                        drawRect(color = Color.Black.copy(alpha = SCRIM_ALPHA))
                        hole?.let { h ->
                            val cut = h.inflate(holePadPx)
                            drawRoundRect(
                                color = Color.Black,
                                topLeft = Offset(cut.left, cut.top),
                                size = Size(cut.width, cut.height),
                                cornerRadius = radiusFor(cut, holeRadiusPx),
                                blendMode = BlendMode.Clear,
                            )
                        }
                        drawContext.canvas.restore()

                        // Everything below is drawn after the layer is restored, so it is not cut
                        // away along with the hole.
                        val place = placementFor(this, size.width, size.height)
                        hole?.let { h ->
                            val ring = h.inflate(holePadPx + pulse * 3.dp.toPx())
                            drawRoundRect(
                                color = accent,
                                topLeft = Offset(ring.left, ring.top),
                                size = Size(ring.width, ring.height),
                                cornerRadius = radiusFor(ring, holeRadiusPx),
                                style = Stroke(width = 2.dp.toPx()),
                            )
                        }
                        if (place?.beakX != null) {
                            val bub = Rect(
                                place.x, place.y,
                                place.x + cardSize.width, place.y + cardSize.height,
                            )
                            val tipY = if (place.below) bub.top - beakDepthPx
                            else bub.bottom + beakDepthPx
                            // A pixel inside the bubble, so the two shapes share an edge rather
                            // than leaving a hairline of scrim between them.
                            val baseY = if (place.below) bub.top + 1f else bub.bottom - 1f
                            drawPath(
                                Path().apply {
                                    moveTo(place.beakX, tipY)
                                    lineTo(place.beakX - beakHalfPx, baseY)
                                    lineTo(place.beakX + beakHalfPx, baseY)
                                    close()
                                },
                                color = accent,
                            )
                        }
                    }
            )

            TourBubble(
                state = state,
                stop = stop,
                onAdvance = advance,
                onFinish = onFinish,
                modifier = Modifier
                    .onGloballyPositioned {
                        cardSize = Size(it.size.width.toFloat(), it.size.height.toFloat())
                    }
                    .graphicsLayer {
                        val scale = 0.8f + 0.2f * appear.value
                        scaleX = scale
                        scaleY = scale
                        alpha = appear.value
                        val place = placementFor(this, 0f, 0f)
                        transformOrigin =
                            if (place?.beakX == null || cardSize.width <= 0f) TransformOrigin.Center
                            else TransformOrigin(
                                pivotFractionX = ((place.beakX - place.x) / cardSize.width)
                                    .coerceIn(0f, 1f),
                                pivotFractionY = if (place.below) 0f else 1f,
                            )
                    },
            )
        },
    ) { measurables, constraints ->
        val scrim = measurables[0].measure(constraints)
        val topInset = insets.getTop(this)
        val bottomInset = insets.getBottom(this)

        val width = minOf(
            constraints.maxWidth - marginPx * 2,
            BUBBLE_WIDTH.toPx(),
        ).roundToInt().coerceAtLeast(0)

        val card = measurables[1].measure(
            Constraints(
                minWidth = width,
                maxWidth = width,
                maxHeight = (constraints.maxHeight - topInset - bottomInset).coerceAtLeast(0),
            )
        )

        layout(constraints.maxWidth, constraints.maxHeight) {
            scrim.place(0, 0)

            val place = placeBubble(
                hole = hole,
                card = Size(card.width.toFloat(), card.height.toFloat()),
                screen = Size(constraints.maxWidth.toFloat(), constraints.maxHeight.toFloat()),
                topInset = topInset.toFloat(),
                bottomInset = bottomInset.toFloat(),
                holePad = holePadPx,
                standoff = standoff,
                margin = marginPx,
                cornerInset = cornerPx + beakHalfPx,
            )
            card.place(place.x.roundToInt(), place.y.roundToInt())
        }
    }
}

/** Where the bubble goes, and where on its edge the beak sits, or null for no beak at all. */
private data class Placement(
    val x: Float,
    val y: Float,
    val below: Boolean,
    val beakX: Float?,
)

/**
 * Pure, so the placement and the beak are computed from the same inputs in the same frame.
 *
 * Collision order is the one Floating UI documents and every system follows: pick a side, then
 * shift along the other axis, then site the arrow, which is why the beak is resolved last and from
 * the already-clamped rectangle.
 */
private fun placeBubble(
    hole: Rect?,
    card: Size,
    screen: Size,
    topInset: Float,
    bottomInset: Float,
    holePad: Float,
    standoff: Float,
    margin: Float,
    cornerInset: Float,
): Placement {
    if (hole == null) {
        // Nothing to point at: the card simply sits in the middle.
        return Placement(
            x = (screen.width - card.width) / 2f,
            y = (screen.height - card.height) / 2f,
            below = true,
            beakX = null,
        )
    }

    val roomBelow = screen.height - bottomInset - hole.bottom - standoff
    val roomAbove = hole.top - topInset - standoff
    val fitsBelow = roomBelow >= card.height
    val fitsAbove = roomAbove >= card.height
    // Flip before shift, and when neither side fits take the roomier one rather than falling back
    // to a fixed side: on a phone that is the difference between a readable bubble and one
    // squashed against the status bar.
    val below = fitsBelow || (!fitsAbove && roomBelow >= roomAbove)

    val y = (if (below) hole.bottom + standoff else hole.top - standoff - card.height)
        .coerceIn(topInset, (screen.height - bottomInset - card.height).coerceAtLeast(topInset))
    // Centred on the target, then pulled back inside the screen. The beak stays on the target
    // regardless, which is the whole reason it is resolved from the final rectangle.
    val x = (hole.center.x - card.width / 2f)
        .coerceIn(margin, (screen.width - margin - card.width).coerceAtLeast(margin))

    val rect = Rect(x, y, x + card.width, y + card.height)
    // An arrow that cannot reach its target is worse than no arrow, which is why every tour library
    // hides it rather than let it point at nothing. It goes when the target is not under the bubble
    // at all, when neither side had room and the bubble is now sitting on the cut-out, and when the
    // bubble is too narrow to hold a beak clear of its own rounded corners.
    val beakX = if (
        (!fitsBelow && !fitsAbove) ||
        rect.overlaps(hole.inflate(holePad)) ||
        hole.center.x !in rect.left..rect.right ||
        card.width <= cornerInset * 2
    ) null else hole.center.x.coerceIn(rect.left + cornerInset, rect.right - cornerInset)

    return Placement(x = x, y = y, below = below, beakX = beakX)
}

/** Never round a short edge more than half way, or a small target's cut-out becomes a lozenge. */
private fun radiusFor(rect: Rect, radius: Float): CornerRadius {
    val r = minOf(radius, rect.width / 2f, rect.height / 2f).coerceAtLeast(0f)
    return CornerRadius(r, r)
}

/** The bubble. Title, a sentence, and the things you can do about it. */
@Composable
private fun TourBubble(
    state: TourState,
    stop: TourStop,
    onAdvance: () -> Unit,
    onFinish: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // The opening card is an offer, not a step: a tour somebody agreed to is finished far more
    // often than one that simply started on them. So it is not counted, and its buttons say so.
    val isOffer = stop.targetId == null && state.index == 0
    val steps = state.stops.count { it.targetId != null }
    val step = state.stops.take(state.index + 1).count { it.targetId != null }
    val isLast = state.index == state.stops.lastIndex
    val title = stringResource(stop.title)

    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        shape = RoundedCornerShape(BUBBLE_RADIUS),
        // Each step is announced as it arrives, rather than leaving a screen reader to discover
        // that the screen quietly changed under it.
        modifier = modifier.semantics {
            liveRegion = LiveRegionMode.Assertive
            paneTitle = title
        },
    ) {
        Column(modifier = Modifier.padding(horizontal = 18.dp, vertical = 16.dp)) {
            Text(
                text = title,
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
                if (!isOffer && stop.targetId != null) {
                    Text(
                        text = stringResource(R.string.walkthrough_progress, step, steps),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Spacer(Modifier.weight(1f))
                // Quiet, and gone on the last step, where the primary button already ends the tour.
                if (!isLast || isOffer) {
                    TextButton(onClick = { state.stop(); onFinish() }) {
                        Text(
                            stringResource(
                                if (isOffer) R.string.walkthrough_not_now
                                else R.string.walkthrough_skip
                            )
                        )
                    }
                    Spacer(Modifier.width(4.dp))
                }
                Button(onClick = onAdvance) {
                    Text(
                        stringResource(
                            when {
                                isOffer -> R.string.walkthrough_start
                                isLast -> R.string.walkthrough_done
                                else -> R.string.walkthrough_next
                            }
                        )
                    )
                }
            }
        }
    }
}
