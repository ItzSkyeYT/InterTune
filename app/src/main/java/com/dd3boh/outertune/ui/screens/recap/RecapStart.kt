/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.ui.screens.recap

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.LineBreak
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.dd3boh.outertune.R

/** The covers stuck round the start screen: middle x, middle y and size in the design's 414 by 736, and their tilt. */
private val FLOATS = listOf(
    floatArrayOf(50f, 214f, 84f, -10f), floatArrayOf(364f, 214f, 76f, 9f), floatArrayOf(40f, 470f, 70f, 7f),
    floatArrayOf(374f, 446f, 88f, -6f), floatArrayOf(116f, 150f, 56f, -8f), floatArrayOf(304f, 512f, 56f, 12f),
)

/**
 * The start screen, on the app's red paper: the name, the year in cream with a hard ink shadow
 * inside the big three-paper blob, covers from the year stuck round it (never the top five, which
 * are the story's to tell), one button, and a way out at the top for whoever does not want it now:
 * it opens by itself once. Open Recap opens the blob into a tunnel the story goes through
 * (RecapStage.open), and the covers are thrown off.
 *
 * On a phone held upright the design's proportions; short of height (on its side, or with large
 * text) the same things stand one under another and scroll if they must.
 */
@Composable
internal fun RecapStart(s: SlideScope, onOpen: () -> Unit, onClose: () -> Unit) {
    val recap = s.shown.recap
    val density = LocalDensity.current
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val width = maxWidth
        val height = maxHeight
        val roomy = height >= 600.dp * maxOf(1f, density.fontScale) && width < height
        // dp for one of the design's pixels, as the stage works it out: the narrower of the two ways.
        val dpUnit = minOf(width.value / 414f, height.value / 736f)
        FLOATS.take(minOf(FLOATS.size, s.shown.mosaic.size)).forEachIndexed { i, (x, y, size, turn) ->
            val side = (size * dpUnit).dp
            Sticker(
                s.shown.mosaic[i], side, s,
                Modifier.offset(x = width * (x / 414f) - side / 2, y = height * (y / 736f) - side / 2),
                at = 0.4f + i * 0.3f, turn = turn, fx = (x - 207f) * 1.6f, fy = (y - 368f) * 1.6f, fr = turn * 3f,
                toss = Toss((x - 207f) * 2.2f, (y - 368f) * 2.2f, turn * 4f),
            )
        }
        val yearSize = with(density) { minOf(128.dp, height * 0.17f, width * 0.36f).toSp() }
        if (roomy) {
            Brand(s, Modifier.align(Alignment.TopCenter).padding(top = height * (74f / 736f)))
            Year(s, yearSize, Modifier.align(Alignment.TopCenter).centredAt(height * (330f / 736f)))
            Since(s, Modifier.align(Alignment.TopCenter).padding(top = height * (550f / 736f), start = 40.dp, end = 40.dp))
            Open(s, onOpen, Modifier.align(Alignment.TopCenter).padding(top = height * (616f / 736f)))
            Foot(
                s,
                Modifier
                    .align(Alignment.BottomCenter)
                    .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom))
                    .padding(bottom = 22.dp, start = 24.dp, end = 24.dp),
            )
        } else {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(14.dp),
                modifier = Modifier
                    .fillMaxSize()
                    .windowInsetsPadding(WindowInsets.safeDrawing)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 32.dp, vertical = 24.dp),
            ) {
                Brand(s, Modifier)
                Year(s, yearSize, Modifier)
                Since(s, Modifier)
                Open(s, onOpen, Modifier)
                Foot(s, Modifier)
            }
        }
        CloseButton(
            onClose,
            Modifier
                .align(Alignment.TopEnd)
                .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.End))
                .padding(top = 10.dp, end = 8.dp)
                .enter(s, Entrance.FADE, 3f),
            tint = RecapInk.Ink,
        )
    }
}

/** Places the content with its middle [y] from the top. */
private fun Modifier.centredAt(y: Dp): Modifier = layout { measurable, constraints ->
    val p = measurable.measure(constraints.copy(minWidth = 0, minHeight = 0))
    layout(constraints.maxWidth, constraints.maxHeight) { p.place((constraints.maxWidth - p.width) / 2, y.roundToPx() - p.height / 2) }
}

@Composable
private fun Brand(s: SlideScope, modifier: Modifier) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = modifier.enter(s, Entrance.UP, 0.3f)) {
        RecapMark(34.dp, Modifier.shadow(6.dp, RoundedCornerShape(50), ambientColor = RecapInk.Shade, spotColor = RecapInk.Shade))
        Spacer(Modifier.width(10.dp))
        Text(stringResource(R.string.recap_name), style = RecapType.tag.copy(fontSize = 25.sp), color = RecapInk.Ink)
    }
}

@Composable
private fun Year(s: SlideScope, size: androidx.compose.ui.unit.TextUnit, modifier: Modifier) {
    val density = LocalDensity.current
    Text(
        s.shown.recap.year.toString(),
        style = RecapType.giant.copy(
            fontSize = size,
            textAlign = TextAlign.Center,
            shadow = Shadow(RecapInk.Ink, Offset(with(density) { 4.dp.toPx() }, with(density) { 5.dp.toPx() }), 0f),
        ),
        color = RecapInk.Cream,
        maxLines = 1,
        softWrap = false,
        modifier = modifier.anchor(s, Spot.YEAR).enter(s, Entrance.SLAP, 1.2f, -4f),
    )
}

@Composable
private fun Since(s: SlideScope, modifier: Modifier) {
    val recap = s.shown.recap
    Text(
        if (recap.firstYear) stringResource(R.string.recap_start_first, s.words.day(recap.sinceDay))
        else stringResource(R.string.recap_start_year, recap.year.toString()),
        // Lines of about the same length, as the design's text-wrap: balance.
        style = TextStyle(lineBreak = LineBreak.Heading),
        fontSize = 17.sp, lineHeight = 23.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.Medium, color = RecapInk.Ink, textAlign = TextAlign.Center,
        modifier = modifier.widthIn(max = 334.dp).enter(s, Entrance.UP, 2f),
    )
}

@Composable
private fun Open(s: SlideScope, onOpen: () -> Unit, modifier: Modifier) {
    Text(
        stringResource(R.string.recap_open), style = RecapType.tag.copy(fontSize = 21.sp, letterSpacing = 0.01.em), color = RecapInk.Cream, textAlign = TextAlign.Center,
        modifier = modifier
            .enter(s, Entrance.POP, 2.5f)
            .shadow(10.dp, RoundedCornerShape(28.dp), ambientColor = RecapInk.Shade, spotColor = RecapInk.Shade)
            .clip(RoundedCornerShape(28.dp))
            .background(RecapInk.Ink)
            .clickable(onClick = onOpen)
            .height(56.dp)
            .widthIn(min = 192.dp)
            .padding(horizontal = 30.dp)
            .wrapCentre(),
    )
}

@Composable
private fun Foot(s: SlideScope, modifier: Modifier) {
    Text(
        stringResource(R.string.recap_start_foot),
        style = TextStyle(lineBreak = LineBreak.Heading),
        fontSize = 13.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.Medium, color = s.paper.ink2, textAlign = TextAlign.Center,
        modifier = modifier.enter(s, Entrance.FADE, 3f),
    )
}

