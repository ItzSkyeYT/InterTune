/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.ui.screens.recap

import android.os.Build
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlurEffect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.dd3boh.outertune.R
import com.dd3boh.outertune.recap.RecapShown
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlin.math.ceil
import kotlin.math.roundToInt

/** The covers on the start screen, small: they are seen blurred and moving. */
private const val TILE_PX = 240

/** At most this many of them, a few columns' worth. */
private const val TILES = 24

/**
 * The start screen: covers from the year rolling by behind, blurred into colour (never the top
 * five, which are the story's to tell), the mark, the year in the colours of the number one, of
 * the top artist and of the first song, and one button. The mock's start screen, with a way out
 * at the top for whoever does not want it now: it opens by itself once.
 *
 * [openness] goes from 0 to 1 as it opens onto the first slide: the covers come into focus and
 * close in, the words fade, and the slide spreads out of the button ([onOpen] says from where).
 */
@Composable
internal fun RecapStart(
    shown: RecapShown,
    words: RecapWords,
    covers: RecapCovers,
    still: Boolean,
    openness: () -> Float,
    onOpen: (Offset) -> Unit,
    onClose: () -> Unit,
) {
    val recap = shown.recap
    val tiles by produceState(emptyList<ImageBitmap>(), shown) {
        value = coroutineScope {
            shown.mosaic.take(TILES).map { async { covers.bitmap(it, TILE_PX)?.asImageBitmap() } }.awaitAll().filterNotNull()
        }
    }
    // The year in three colours from the year itself, the app's own until they are read.
    val yearColours by produceState(listOf(Color(0xFFFFB3B5), Color.White, RecapRed), shown) {
        val top = recap.topSongs.firstOrNull()?.let { shown.song(it.songId)?.cover }
        val artist = recap.topArtists.firstOrNull()?.let { shown.artists[it.artistId]?.picture ?: shown.song(it.topSongId)?.cover }
        val first = shown.song(recap.firstSong.songId)?.cover
        value = listOf(
            top?.let { Color(covers.colours(it).accent) } ?: Color(0xFFFFB3B5),
            artist?.let { Color(covers.colours(it).accent) } ?: Color.White,
            first?.let { Color(covers.colours(it).accent) } ?: RecapRed,
        )
    }
    var buttonCentre by remember { mutableStateOf(Offset.Zero) }

    BoxWithConstraints(Modifier.fillMaxSize().background(RecapNight)) {
        val height = maxHeight
        Mosaic(tiles, still, openness, Modifier.fillMaxSize())
        // Darker in the middle where the words are, and at the top and the foot.
        Box(
            Modifier.fillMaxSize().drawBehind {
                drawRect(
                    Brush.radialGradient(
                        0f to RecapNight.copy(alpha = 0.78f),
                        0.7f to RecapNight.copy(alpha = 0.42f),
                        1f to RecapNight.copy(alpha = 0.2f),
                        center = Offset(size.width / 2, size.height * 0.47f),
                        radius = size.maxDimension * 0.62f,
                    )
                )
                drawRect(
                    Brush.verticalGradient(
                        0f to RecapNight.copy(alpha = 0.55f),
                        0.22f to Color.Transparent,
                        0.74f to Color.Transparent,
                        1f to RecapNight.copy(alpha = 0.92f),
                    )
                )
            }
        )

        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = height * 0.19f, start = 32.dp, end = 32.dp)
                .graphicsLayer {
                    val k = openness()
                    alpha = (1f - k * 2.2f).coerceIn(0f, 1f)
                    scaleX = 1f + 0.06f * k
                    scaleY = scaleX
                },
        ) {
            Box(Modifier.padding(bottom = 18.dp).shadow(30.dp, RoundedCornerShape(50), ambientColor = RecapRed, spotColor = RecapRed)) {
                RecapMark(64.dp)
            }
            Text(stringResource(R.string.recap_name), fontSize = 26.sp, fontWeight = FontWeight.Bold, letterSpacing = (-0.01).em, color = Color.White)
            BasicText(
                text = recap.year.toString(),
                style = TextStyle(
                    brush = Brush.horizontalGradient(0f to yearColours[0], 0.48f to yearColours[1], 1f to yearColours[2]),
                    fontSize = 150.sp,
                    fontWeight = FontWeight.Black,
                    letterSpacing = (-0.06).em,
                    lineHeight = 0.9.em,
                    textAlign = TextAlign.Center,
                ),
                maxLines = 1,
                autoSize = TextAutoSize.StepBased(minFontSize = 60.sp, maxFontSize = 150.sp, stepSize = 4.sp),
                modifier = Modifier.padding(top = 10.dp, bottom = 18.dp),
            )
            Text(
                if (recap.firstYear) stringResource(R.string.recap_start_first, words.day(recap.sinceDay))
                else stringResource(R.string.recap_start_year, recap.year.toString()),
                fontSize = 18.sp, lineHeight = 1.4.em, color = Color.White.copy(alpha = 0.9f), textAlign = TextAlign.Center,
                modifier = Modifier.widthIn(max = 300.dp),
            )
        }

        // The one button, and a ring breathing out of it.
        val breath = rememberInfiniteTransition(label = "breath")
        val ring by breath.animateFloat(0f, 1f, infiniteRepeatable(tween(2600, easing = LinearEasing), RepeatMode.Restart), label = "ring")
        Box(
            Modifier
                .align(Alignment.TopCenter)
                .padding(top = height * 0.695f)
                .graphicsLayer { alpha = (1f - openness() * 2.5f).coerceIn(0f, 1f) }
                .drawBehind {
                    if (still) return@drawBehind
                    val grow = 18.dp.toPx() * ring
                    drawRoundRect(
                        Color.White.copy(alpha = 0.32f * (1f - ring)),
                        topLeft = Offset(-grow, -grow),
                        size = Size(size.width + 2 * grow, size.height + 2 * grow),
                        cornerRadius = CornerRadius(size.height / 2 + grow),
                        style = Stroke(2.dp.toPx()),
                    )
                }
                .shadow(20.dp, RoundedCornerShape(29.dp))
                .clip(RoundedCornerShape(29.dp))
                .background(Color.White)
                .onGloballyPositioned { buttonCentre = it.boundsInRoot().center }
                .clickable { onOpen(buttonCentre) }
                .height(58.dp)
                .padding(horizontal = 34.dp)
                .wrapContentHeight(Alignment.CenterVertically),
        ) {
            Text(stringResource(R.string.recap_open), fontSize = 18.sp, fontWeight = FontWeight.Bold, color = Color(0xFF1A1111))
        }

        Text(
            stringResource(R.string.recap_start_foot),
            fontSize = 13.sp, color = Color.White.copy(alpha = 0.58f), textAlign = TextAlign.Center,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom))
                .padding(bottom = 34.dp, start = 24.dp, end = 24.dp)
                .graphicsLayer { alpha = (1f - openness() * 2.5f).coerceIn(0f, 1f) },
        )

        CloseButton(
            onClose,
            Modifier
                .align(Alignment.TopEnd)
                .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.End))
                .padding(top = 10.dp, end = 8.dp)
                .graphicsLayer { alpha = (1f - openness() * 2.5f).coerceIn(0f, 1f) },
        )
    }
}

/**
 * Four columns of covers on a slant, rolling up and down at two speeds, blurred, as the mock's
 * start screen has them. As the story opens they come into focus, turn a little and close in.
 */
@Composable
private fun Mosaic(tiles: List<ImageBitmap>, still: Boolean, openness: () -> Float, modifier: Modifier) {
    if (tiles.isEmpty()) return
    val roll = rememberInfiniteTransition(label = "roll")
    val up by roll.animateFloat(0f, 1f, infiniteRepeatable(tween(46_000, easing = LinearEasing)), label = "up")
    val down by roll.animateFloat(0f, 1f, infiniteRepeatable(tween(58_000, easing = LinearEasing)), label = "down")
    // Blurred until it opens, from Android 12, which can blur what it draws.
    val blurred = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) Modifier.graphicsLayer {
        val radius = 7.dp.toPx() * (1f - openness())
        renderEffect = if (radius < 0.5f) null else BlurEffect(radiusX = radius, radiusY = radius, edgeTreatment = TileMode.Clamp)
    } else Modifier
    val columns = 4
    val perColumn = ceil(tiles.size / columns.toFloat()).toInt() + 1
    Canvas(modifier.then(blurred)) {
        val k = openness()
        val width = size.width * 1.9f
        val height = size.height * 1.6f
        val gap = 14.dp.toPx()
        val side = (width - gap * (columns - 1)) / columns
        val stride = side + gap
        val loop = perColumn * stride
        val corner = CornerRadius(18.dp.toPx())
        val rows = ceil(height / stride).toInt() + 2
        rotate(-13f + 5f * k, pivot = center) {
            scale(1f + 0.5f * k, pivot = center) {
                val left = center.x - width / 2
                val top = center.y - height / 2
                for (c in 0 until columns) {
                    // Every other column the other way, and slower.
                    val shift = if (still) 0f else if (c % 2 == 0) up * loop else (1f - down) * loop
                    val x = left + c * stride
                    for (r in 0 until rows + perColumn) {
                        val y = top + r * stride - shift
                        if (y + side < top - stride || y > top + height + stride) continue
                        val tile = tiles[(c * 3 + (r % perColumn) * 5) % tiles.size]
                        val box = RoundRect(Rect(Offset(x, y), Size(side, side)), corner)
                        clipPath(Path().apply { addRoundRect(box) }) {
                            drawImage(
                                tile,
                                dstOffset = IntOffset(x.roundToInt(), y.roundToInt()),
                                dstSize = IntSize(side.roundToInt(), side.roundToInt()),
                                alpha = 0.8f + 0.15f * k,
                                filterQuality = FilterQuality.Medium,
                            )
                        }
                    }
                }
            }
        }
    }
}
