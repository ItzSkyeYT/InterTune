/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.ui.screens.recap

import android.content.Context
import android.graphics.Bitmap
import android.os.Build
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil3.imageLoader
import coil3.request.ImageRequest
import coil3.request.allowHardware
import coil3.toBitmap
import com.dd3boh.outertune.R
import com.dd3boh.outertune.recap.RecapColours
import com.dd3boh.outertune.recap.RecapPalette
import com.dd3boh.outertune.utils.coilCoroutine
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap

/** A slide's colours, ready to draw with. */
@Immutable
data class SlideColours(val accent: Color, val ink: Color, val ink2: Color, val deep: Color, val onAccent: Color) {
    companion object {
        fun of(c: RecapColours) = SlideColours(Color(c.accent), Color(c.ink), Color(c.ink2), Color(c.deep), Color(c.onAccent))
        val APP = of(RecapPalette.APP)
    }
}

/** InterTune's mark: the play button on its red disc, as the launcher icon has it. */
val RecapRed = Color(0xFFED5564)

/**
 * The covers the Recap draws, read once each: small for a slide's colours and its blurred
 * background, large for a share card. Kept for as long as the Recap is open.
 */
class RecapCovers(private val context: Context) {
    private val colours = ConcurrentHashMap<Any, RecapColours>()
    private val tiny = ConcurrentHashMap<Any, ImageBitmap>()

    suspend fun bitmap(model: Any?, px: Int): Bitmap? {
        if (model == null) return null
        return withContext(coilCoroutine) {
            runCatching {
                context.imageLoader.execute(
                    ImageRequest.Builder(context).data(model).size(px, px).allowHardware(false).build()
                ).image?.toBitmap()
            }.getOrNull()
        }
    }

    /** The cover's colours, the mock's way: measured on 40 by 40 of its middle. */
    suspend fun colours(model: Any?): RecapColours {
        if (model == null) return RecapPalette.APP
        colours[model]?.let { return it }
        val small = bitmap(model, MEASURED) ?: return RecapPalette.APP
        val square = squareMiddle(small, MEASURED)
        val pixels = IntArray(MEASURED * MEASURED)
        square.getPixels(pixels, 0, MEASURED, 0, 0, MEASURED, MEASURED)
        return RecapPalette.of(pixels).also { colours[model] = it }
    }

    /** The cover so small that drawn over the whole screen it is a soft wash of its colours. */
    suspend fun wash(model: Any?): ImageBitmap? {
        if (model == null) return null
        tiny[model]?.let { return it }
        val small = bitmap(model, MEASURED) ?: return null
        return squareMiddle(small, WASH).asImageBitmap().also { tiny[model] = it }
    }

    private fun squareMiddle(b: Bitmap, side: Int): Bitmap {
        val s = minOf(b.width, b.height)
        val cut = Bitmap.createBitmap(b, (b.width - s) / 2, (b.height - s) / 2, s, s)
        return Bitmap.createScaledBitmap(cut, side, side, true)
    }

    companion object {
        const val MEASURED = 40
        const val WASH = 24
    }
}

/** A slide's colours from its cover, the app's own until the cover has been read, changing softly. */
@Composable
fun slideColours(covers: RecapCovers, model: Any?, brand: Boolean = false): SlideColours {
    val measured by produceState(SlideColours.APP, model, brand) {
        value = if (brand) SlideColours.APP else SlideColours.of(covers.colours(model))
    }
    val spec = tween<Color>(RecapMotion.COLOUR_MS)
    val accent by animateColorAsState(measured.accent, spec, label = "accent")
    val ink by animateColorAsState(measured.ink, spec, label = "ink")
    val ink2 by animateColorAsState(measured.ink2, spec, label = "ink2")
    val deep by animateColorAsState(measured.deep, spec, label = "deep")
    val onAccent by animateColorAsState(measured.onAccent, spec, label = "onAccent")
    return SlideColours(accent, ink, ink2, deep, onAccent)
}

/** The mark: [size] across. */
@Composable
fun RecapMark(size: Dp, modifier: Modifier = Modifier) {
    Box(modifier.size(size).clip(CircleShape).background(RecapRed), contentAlignment = Alignment.Center) {
        Image(painterResource(R.drawable.launcher_foreground), null, Modifier.fillMaxSize())
    }
}

/**
 * Behind a slide: its cover twice, washed out into its colours and drifting, darker at the top
 * and the foot so the words read, and tinted with its deepest colour. The mock's .bg.
 */
@Composable
fun CoverBackdrop(covers: RecapCovers, model: Any?, colours: SlideColours, still: Boolean, modifier: Modifier = Modifier) {
    val wash by produceState<ImageBitmap?>(null, model) { value = covers.wash(model) }
    val drift = rememberInfiniteTransition(label = "drift")
    val t1 by drift.animateFloat(0f, 1f, infiniteRepeatable(tween(RecapMotion.DRIFT_MS, easing = LinearEasing), RepeatMode.Reverse), label = "t1")
    val t2 by drift.animateFloat(0f, 1f, infiniteRepeatable(tween(RecapMotion.DRIFT_2_MS, easing = LinearEasing), RepeatMode.Reverse), label = "t2")
    val saturated = remember { ColorFilter.colorMatrix(ColorMatrix().apply { setToSaturation(1.4f) }) }
    Box(modifier.fillMaxSize().background(colours.deep)) {
        wash?.let { picture ->
            val blurred = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) Modifier.blur(40.dp) else Modifier
            Image(
                bitmap = picture,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                filterQuality = FilterQuality.High,
                colorFilter = saturated,
                alpha = 0.9f,
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        val k = if (still) 0.5f else t1
                        scaleX = 1.5f + 0.08f * k
                        scaleY = 1.5f + 0.08f * k
                        translationX = size.width * (-0.03f + 0.06f * k)
                        translationY = size.height * (-0.02f + 0.04f * k)
                        rotationZ = 4f * k
                    }
                    .then(blurred),
            )
            Image(
                bitmap = picture,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                filterQuality = FilterQuality.High,
                colorFilter = saturated,
                alpha = 0.55f,
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        val k = if (still) 0.5f else t2
                        rotationZ = 180f + 6f * k
                        scaleX = 1.6f + 0.1f * k
                        scaleY = 1.6f + 0.1f * k
                        translationY = size.height * 0.45f
                        translationX = size.width * (-0.04f + 0.08f * k)
                    }
                    .then(blurred),
            )
        }
        Box(Modifier.fillMaxSize().background(colours.deep.copy(alpha = 0.34f)))
        Box(
            Modifier.fillMaxSize().background(
                Brush.verticalGradient(
                    0f to Color.Black.copy(alpha = 0.34f),
                    0.34f to Color.Black.copy(alpha = 0.12f),
                    0.7f to Color.Black.copy(alpha = 0.30f),
                    1f to Color.Black.copy(alpha = 0.62f),
                )
            )
        )
    }
}

/** The thank-you page's own backdrop, in the app's colours: it is from the people who make it. */
@Composable
fun BrandBackdrop(modifier: Modifier = Modifier) {
    Canvas(modifier.fillMaxSize()) {
        drawRect(Color(0xFF170E10))
        drawRect(
            Brush.radialGradient(
                listOf(Color(0x9EED5564), Color(0x00ED5564)),
                center = Offset(size.width * 0.18f, size.height * 0.08f),
                radius = size.maxDimension * 0.6f,
            )
        )
        drawRect(
            Brush.radialGradient(
                listOf(Color(0x8C96283C), Color(0x0096283C)),
                center = Offset(size.width * 0.92f, size.height * 0.88f),
                radius = size.maxDimension * 0.65f,
            )
        )
        drawRect(
            Brush.radialGradient(
                listOf(Color(0x4DFFB3B5), Color(0x00FFB3B5)),
                center = Offset(size.width * 0.5f, size.height * 0.12f),
                radius = size.width * 0.7f,
            )
        )
    }
}
