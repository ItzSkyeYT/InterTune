/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.ui.component

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.unit.dp
import com.dd3boh.outertune.ui.utils.GlassSpec
import com.dd3boh.outertune.ui.utils.LocalAppBackdrop
import com.kyant.backdrop.backdrops.LayerBackdrop
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.effects.blur
import com.kyant.backdrop.effects.lens
import com.kyant.backdrop.effects.vibrancy

/*
 * Glass for the floating top bars.
 *
 * The bar is part of the screen, and every screen is inside the app backdrop, so the bar cannot read
 * that backdrop: the layer would contain its own reader, and RenderNode::prepareTreeImpl recurses
 * until the native stack overflows (see BackButtonSurface.kt). That is why the bar used to be a
 * flat grey on glass.
 *
 * So each destination records a backdrop of its own from the screen's content, and the bar is not
 * drawn where it is composed. It is still laid out and still takes touches there, but its drawing
 * goes into a layer of its own, which the destination draws over the content, outside the screen's
 * backdrop. The screen's backdrop holds no bar, the bar's layer reads the screen's backdrop, and
 * nothing reads a layer it is part of.
 */

/** One destination's backdrop and the bars it draws. */
@Stable
class TopBarGlassHost internal constructor(internal val backdrop: LayerBackdrop) {
    internal var coordinates: LayoutCoordinates? = null
    internal val bars = mutableStateListOf<HostedTopBar>()
}

/** A bar drawn by its destination: the layer it records into, and where the destination puts it. */
@Stable
internal class HostedTopBar(val layer: GraphicsLayer) {
    var coordinates: LayoutCoordinates? = null
    var offset by mutableStateOf(Offset.Zero)

    fun place(host: LayoutCoordinates) {
        val bar = coordinates ?: return
        if (host.isAttached && bar.isAttached) offset = host.localPositionOf(bar, Offset.Zero)
    }
}

/** The destination's host while glass is on, null otherwise, and then the bar draws itself in place. */
val LocalTopBarGlassHost = staticCompositionLocalOf<TopBarGlassHost?> { null }

/**
 * The glass the circle and the pills are drawn with. Provided only inside a bar that its host
 * draws: anywhere else inside a screen, reading the screen's backdrop would make it contain its
 * own reader.
 */
internal val LocalTopBarGlass = staticCompositionLocalOf<GlassSpec?> { null }

/**
 * Glass for the search pill, over a backdrop of the whole nav host. Provided by MainActivity only
 * around the search bar that sits beside the nav host. The one the "search" destination composes is
 * inside the nav host, so it must never get this, and it does not: it is outside the provider.
 */
val LocalSearchBarGlass = staticCompositionLocalOf<GlassSpec?> { null }

/**
 * Wraps one navigation destination. With glass off, or for a destination with nothing floating
 * over it ([floating] false: no top bar and no floating button, like search or the walkthrough),
 * this is a plain full size box that records nothing.
 *
 * Decided by route rather than by waiting for a bar to register. Registering happens in an effect,
 * after the frame that needs the backdrop, so gating on it gave every screen a first frame of flat
 * pills.
 */
@Composable
fun TopBarGlassDestination(floating: Boolean, content: @Composable () -> Unit) {
    // Only whether glass is on. The intensity is the bar's business: asking for it here cost every
    // tab a blocking preference read on each visit.
    val hosting = floating && LocalAppBackdrop.current != null
    // Recorded over the page colour, as the app backdrop is: the screen is transparent between
    // rows, and a blur there would bleed into nothing. The colour is read inside the draw, so a new
    // album colour re-records the layer. Keying the draw on it replaced the backdrop and the host
    // instead, and dynamic theme dropped the glass for a frame on every song change.
    val surface = rememberUpdatedState(MaterialTheme.colorScheme.surface)
    val host = if (hosting) {
        val onDraw = remember {
            val draw: ContentDrawScope.() -> Unit = {
                drawRect(surface.value)
                drawContent()
            }
            draw
        }
        val backdrop = rememberLayerBackdrop(onDraw = onDraw)
        remember(backdrop) { TopBarGlassHost(backdrop) }
    } else null

    Box(
        Modifier
            .fillMaxSize()
            .then(
                if (host != null) Modifier.onGloballyPositioned { coordinates ->
                    host.coordinates = coordinates
                    host.bars.forEach { it.place(coordinates) }
                } else Modifier
            )
    ) {
        Box(
            Modifier
                .fillMaxSize()
                // The screen in a layer of its own under the recorder, so a change inside it (the
                // scrollbar thumb moves on every scroll frame) re-records that layer once. Without
                // it, each of those frames ran the recorder's two passes over the whole screen.
                .then(if (host != null) Modifier.layerBackdrop(host.backdrop).graphicsLayer() else Modifier)
        ) {
            CompositionLocalProvider(LocalTopBarGlassHost provides host) {
                content()
            }
        }
        if (host != null) {
            Spacer(
                Modifier
                    .matchParentSize()
                    .drawBehind {
                        host.bars.forEach { bar ->
                            translate(bar.offset.x, bar.offset.y) { drawLayer(bar.layer) }
                        }
                    }
            )
        }
    }
}

/**
 * Hands this bar's drawing to [host]: recorded into a layer of the bar's own and drawn by the
 * destination, instead of here.
 */
@Composable
internal fun Modifier.drawnBy(host: TopBarGlassHost): Modifier {
    val layer = rememberGraphicsLayer()
    val bar = remember(layer) { HostedTopBar(layer) }
    DisposableEffect(host, bar) {
        host.bars += bar
        host.coordinates?.let(bar::place)
        onDispose { host.bars -= bar }
    }
    return this
        .onGloballyPositioned { coordinates ->
            bar.coordinates = coordinates
            host.coordinates?.let(bar::place)
        }
        .drawWithContent {
            layer.record { this@drawWithContent.drawContent() }
        }
}

/**
 * What the circle and the pills are made of: glass inside a bar its destination draws, and the
 * flat grey of [topBarSurfaceColor] anywhere else.
 *
 * The mini player's numbers, since that is the other panel carrying text: a floor of 0.66 on the
 * tint, and a 12dp rim, which on a 48dp shape still leaves a flat middle for the title.
 */
@Composable
fun Modifier.topBarSurface(shape: Shape = CircleShape): Modifier {
    val glass = LocalTopBarGlass.current ?: return background(topBarSurfaceColor(), shape)
    return floatingGlass(glass, shape)
}

/**
 * The glass itself, for anything floating over the content: the top bar's shapes, the floating
 * buttons, the search pill. [tint] defaults to the surface tint the pills use; a button passes its
 * own container colour so it still reads as a button.
 *
 * The caller answers for [glass] being safe to read here, which means this node must not be inside
 * the layer that [GlassSpec.backdrop] records. See the top of this file.
 */
@Composable
fun Modifier.floatingGlass(glass: GlassSpec, shape: Shape, tint: Color = glass.tint(min = 0.66f, max = 0.98f)): Modifier {
    // One provider for the node's lifetime that reads the shape as it is now, so the search pill,
    // which recomposes on every frame of its open and close, does not hand the node a new lambda
    // each time. Not a fix: the node already re-reads its provider whenever its size changes.
    val currentShape = rememberUpdatedState(shape)
    val shapeProvider = remember { { currentShape.value } }
    return this
        .drawBackdrop(
            backdrop = glass.backdrop,
            shape = shapeProvider,
            effects = {
                vibrancy()
                blur(glass.blur.toPx())
                lens(
                    refractionHeight = 12f.dp.toPx() * glass.lensT,
                    refractionAmount = 24f.dp.toPx() * glass.lensT,
                    depthEffect = true,
                )
            },
        )
        // Tint as a background after the backdrop, as the dock does: drawn on the node's own
        // canvas it would paint a square patch.
        .background(tint, shape)
}
