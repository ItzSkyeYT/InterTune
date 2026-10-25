/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.ui.screens.recap

import android.graphics.Bitmap
import android.os.Build
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.GenericShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import com.dd3boh.outertune.R
import com.dd3boh.outertune.recap.AutoFinds
import com.dd3boh.outertune.recap.CountdownLine
import com.dd3boh.outertune.recap.RecapShown
import com.dd3boh.outertune.recap.RecapStory
import java.time.LocalDate
import kotlin.math.PI
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * What a page is drawn from: the year, its words, its colours, and the stage it is on, whose
 * clock its parts read to arrive (RecapMotion's Modifier.enter) and whose shapes sit on its spots.
 */
internal class SlideScope(
    val shown: RecapShown,
    val words: RecapWords,
    val covers: RecapCovers,
    val paper: SlidePaper,
    val stage: RecapStage,
    val page: PageState,
) {
    val still: Boolean get() = stage.still
    val visit: Int get() = page.visit

    /** Pixels for one of the design's: how far things travel as they arrive. */
    val unit: Float get() = stage.unit

    /**
     * How long the page has been up, in ms of the story's clock, up to [ARRIVED_MS]: by then every
     * part has arrived and rests, so what reads it is not drawn again for each frame. Read in
     * drawing only.
     */
    private val sinceUp = derivedStateOf { (stage.clock.now - page.start).coerceAtMost(ARRIVED_MS) }
    fun elapsed(): Float = sinceUp.value.toFloat()

    /** A part that begins at step [at] and takes [ms] to arrive: the page has settled once every part has. */
    fun arrives(at: Float, ms: Int) = stage.arrives(page.visit, (at * RecapMotion.STEP_MS).toLong() + ms)

    /** How long ago the page began to go, 0 while it is on. */
    fun leftFor(): Float {
        stage.leaving?.let { if (it.page.visit == page.visit) return (stage.clock.now - it.since).toFloat().coerceAtLeast(0.01f) }
        if (page.at == 0 && stage.startGoing >= 0) return (stage.clock.now - stage.startGoing).toFloat().coerceAtLeast(0.01f)
        return 0f
    }

    companion object {
        /** Everything on a page has arrived this long after it came up: the latest, Only on InterTune's last line, by about 4 s. */
        const val ARRIVED_MS = 7_000L
    }
}

/** The room a slide has, in the dp it is drawn in (RecapFit): a short one is a phone on its side or large text. [tallFrom] grows with the text size. */
internal class SlideRoom(val width: Dp, val height: Dp, private val tallFrom: Dp = 540.dp) {
    val tall: Boolean get() = height >= tallFrom
}

/**
 * The Recap on a phone smaller than the one it is laid out for: drawn that much smaller, as the
 * design is zoomed to fill a small phone, rather than cut or made to scroll. Only on a screen held
 * upright, never below [LEAST], and large text never below its usual size; whatever still does not
 * fit (a phone on its side, very large text) scrolls (SlideColumn) or stands one under another
 * (RecapStart).
 */
internal object RecapFit {
    /** The room a slide is laid out for, between the bars at the top and the song at the foot. */
    val ROOM = 600.dp

    /** The start screen is laid out for the design's whole phone, 736 high. */
    val START = 736.dp

    /** Never drawn smaller than this. */
    const val LEAST = 0.7f

    /** How much smaller to draw what needs [need] of height in a space of [width] by [height]: 1 where it fits. */
    fun zoom(width: Dp, height: Dp, need: Dp, fontScale: Float): Float {
        if (width >= height || height >= need) return 1f
        // Larger text than usual is made smaller only as far as its usual size.
        val least = if (fontScale > 1f) maxOf(LEAST, 1f / fontScale) else LEAST
        return (height / need).coerceIn(least, 1f)
    }
}

/** [content] drawn [zoom] times its size: every dp and sp it lays out in, while the pixels round it stay as they are. */
@Composable
internal fun Zoomed(zoom: Float, content: @Composable () -> Unit) {
    val density = LocalDensity.current
    if (zoom >= 1f) content()
    else CompositionLocalProvider(LocalDensity provides Density(density.density * zoom, density.fontScale), content = content)
}

/** The Recap's type, as the design sets it: a heavy condensed face for what is shouted, the phone's own for the rest. */
internal object RecapType {
    private val display = RecapFaces.Display
    val kick = TextStyle(fontSize = 15.sp, fontWeight = FontWeight.Medium, lineHeight = 1.3.em, letterSpacing = 0.005.em)
    val h1 = TextStyle(fontFamily = display, fontWeight = FontWeight.Black, fontSize = 46.sp, lineHeight = 0.98.em, letterSpacing = (-0.005).em)
    val h2 = h1.copy(fontSize = 34.sp, lineHeight = 1.em)
    val song = h1.copy(fontSize = 40.sp, lineHeight = 1.em)
    val giant = TextStyle(fontFamily = display, fontWeight = FontWeight.Black, fontSize = 160.sp, lineHeight = 1.em, letterSpacing = (-0.01).em, fontFeatureSettings = "tnum, lnum")
    val unit = TextStyle(fontFamily = display, fontWeight = FontWeight.Black, fontSize = 32.sp, lineHeight = 1.em)
    val line = TextStyle(fontSize = 16.5.sp, lineHeight = 1.42.em)
    val quiet = TextStyle(fontSize = 14.5.sp, lineHeight = 1.42.em)
    val tag = TextStyle(fontFamily = display, fontWeight = FontWeight.Black, fontSize = 17.sp, lineHeight = 1.em)
    val button = TextStyle(fontFamily = display, fontWeight = FontWeight.Black, fontSize = 19.sp, lineHeight = 1.em)
}

/** A tap on a button of a slide: its own, never also a tap on the story behind it. */
internal fun Modifier.recapClick(onClick: () -> Unit): Modifier = clickable(onClick = onClick)

/** Words in a button of fixed height, in the middle of it. */
internal fun Modifier.wrapCentre(): Modifier = wrapContentHeight(Alignment.CenterVertically)

/**
 * A slide's column: clear of the bars at the top and of the song at the foot. On a phone held
 * upright with less room than [RecapFit.ROOM], drawn smaller to fit (RecapFit). It fills its room
 * when it fits, its gaps ([Grow]) sharing out what is spare, and scrolls where it does not.
 */
@Composable
internal fun SlideColumn(content: @Composable ColumnScope.(SlideRoom) -> Unit) {
    BoxWithConstraints(
        Modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .padding(top = 62.dp, bottom = 76.dp),
        contentAlignment = Alignment.TopCenter,
    ) {
        val fontScale = LocalDensity.current.fontScale
        val zoom = RecapFit.zoom(maxWidth, maxHeight, RecapFit.ROOM, fontScale)
        val width = maxWidth / zoom
        val height = maxHeight / zoom
        Zoomed(zoom) {
            val room = SlideRoom(minOf(width, 480.dp) - 48.dp, height, 540.dp * maxOf(1f, fontScale))
            val scroll = rememberScrollState()
            Column(
                Modifier
                    .widthIn(max = 480.dp)
                    .fillMaxWidth()
                    // Only a slide taller than its room answers a drag: one that fits never stretches.
                    .verticalScroll(scroll, enabled = scroll.maxValue > 0)
                    .heightIn(min = height)
                    .padding(horizontal = 24.dp)
            ) { content(room) }
        }
    }
}

/** The design's flexible gap: whatever room is spare, and never less than a small fixed one (larger where the slide is short). */
@Composable
internal fun ColumnScope.Grow(room: SlideRoom) {
    Spacer(Modifier.height(if (room.tall) 8.dp else 18.dp))
    Spacer(Modifier.weight(1f))
}

@Composable
internal fun Kick(text: String, s: SlideScope, at: Float = 0.7f, modifier: Modifier = Modifier) {
    Text(text, style = RecapType.kick, color = s.paper.ink2, modifier = Modifier.padding(bottom = 6.dp).then(modifier).enter(s, Entrance.UP, at))
}

/** [template] with %1$s cut out: what comes before it and after it. */
internal fun around(template: String): Pair<String, String> {
    val i = template.indexOf(MARK)
    return if (i < 0) template to "" else template.substring(0, i) to template.substring(i + MARK.length)
}

internal const val MARK = "⁣"

/**
 * Words that rise out of their own line one after another, from step [at], [step] steps apart;
 * [strip], when given, sits among them on a strip of the slide's accent, slapped on at [stripAt].
 * Very long words wrap within their own line. Given a name, [behind], confetti passes behind
 * them, each word kept clear as it rises (Modifier.overConfetti).
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun Words(
    text: String,
    s: SlideScope,
    at: Float,
    style: TextStyle,
    colour: Color,
    modifier: Modifier = Modifier,
    step: Float = 0.22f,
    strip: String? = null,
    after: String = "",
    stripAt: Float = at + 0.9f,
    stripTurn: Float = -2.5f,
    behind: String? = null,
) {
    val before = text.split(' ').filter { it.isNotEmpty() }
    val later = after.split(' ').filter { it.isNotEmpty() }
    val gap = with(LocalDensity.current) { (style.fontSize * 0.24f).toDp() }
    fun clear(i: Int, from: Float) = if (behind == null) Modifier else Modifier.overConfetti(s, "$behind/$i", from)
    FlowRow(
        modifier.clearAndSetSemantics { contentDescription = listOfNotNull(text, strip, after).joinToString(" ") },
        horizontalArrangement = Arrangement.spacedBy(gap),
        itemVerticalAlignment = Alignment.CenterVertically,
    ) {
        before.forEachIndexed { i, word -> RisingWord(word, s, at + i * step, style, colour, clear(i, at + i * step)) }
        if (strip != null) Strip(strip, s, stripAt, stripTurn, style)
        later.forEachIndexed { i, word ->
            val from = stripAt + 0.3f + i * step
            RisingWord(word, s, from, style, colour, clear(before.size + i, from))
        }
    }
}

/** One word coming up out of its line: the line clips it, so it seems to rise from under the words above. */
@Composable
private fun RisingWord(word: String, s: SlideScope, at: Float, style: TextStyle, colour: Color, modifier: Modifier = Modifier) {
    s.arrives(at, if (s.still) RecapMotion.CALM_FADE_MS else RecapMotion.WORD_MS)
    Box(
        modifier.drawWithContent {
            val pad = size.height * 0.06f
            clipRect(-pad, -pad, size.width + pad, size.height * 1.2f) { this@drawWithContent.drawContent() }
        }
    ) {
        Text(
            word, style = style, color = colour,
            modifier = Modifier.graphicsLayer {
                val t = s.elapsed() - at * RecapMotion.STEP_MS
                if (s.still) {
                    alpha = RecapMotion.Ease.transform((t / RecapMotion.CALM_FADE_MS).coerceIn(0f, 1f))
                } else {
                    val e = RecapMotion.Snap.transform((t / RecapMotion.WORD_MS).coerceIn(0f, 1f))
                    translationY = (1f - e) * RecapMotion.WORD_RISE * size.height
                }
            },
        )
    }
}

/** A word or two on a strip of the slide's accent, slapped on a little crooked. */
@Composable
internal fun Strip(text: String, s: SlideScope, at: Float, turn: Float, style: TextStyle) {
    val shape = RoundedCornerShape(5.dp)
    Text(
        text, style = style, color = s.paper.onAccent,
        modifier = Modifier
            .enter(s, Entrance.SLAP, at, turn)
            .drawBehind { drawRoundRect(RecapInk.Ink.copy(alpha = 0.2f), Offset(3.dp.toPx(), 4.dp.toPx()), size, CornerRadius(5.dp.toPx())) }
            .background(s.paper.accent, shape)
            .padding(horizontal = 6.dp, vertical = 1.dp),
    )
}

/** A cover stuck on with a white paper edge, as a sticker: it arrives as [how] says and is thrown off with its slide ([toss]). */
@Composable
internal fun Sticker(
    model: Any?,
    side: Dp,
    s: SlideScope,
    modifier: Modifier = Modifier,
    how: Entrance? = Entrance.FLY,
    at: Float = 0f,
    turn: Float = 0f,
    fx: Float = 0f,
    fy: Float = 300f,
    fr: Float = 30f,
    toss: Toss? = Toss(),
    round: Boolean = false,
) {
    val small = side < 72.dp
    val edge = if (small) 3.dp else 6.dp
    val outer = if (round) CircleShape else RoundedCornerShape(if (small) 9.dp else 18.dp)
    val inner = if (round) CircleShape else RoundedCornerShape(if (small) 6.dp else 12.dp)
    Box(
        modifier
            .then(if (how != null) Modifier.enter(s, how, at, turn, fx, fy, fr, toss) else Modifier.graphicsLayer { rotationZ = turn })
            .size(side)
            .shadow(if (small) 5.dp else 14.dp, outer, ambientColor = RecapInk.Shade, spotColor = RecapInk.Shade)
            .background(RecapInk.White, outer)
            .padding(edge)
    ) {
        AsyncImage(
            model = model,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize().clip(inner).background(Color(0xFFDDDAD3)),
        )
    }
}

/** A round lemon badge with a paper edge. */
@Composable
internal fun Badge(main: String, s: SlideScope, at: Float, turn: Float, side: Dp, modifier: Modifier = Modifier, small: String? = null, size: Float = 26f) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
        modifier = modifier
            .enter(s, Entrance.SLAP, at, turn)
            .size(side)
            .shadow(9.dp, CircleShape, ambientColor = RecapInk.Shade, spotColor = RecapInk.Shade)
            .background(RecapInk.Lemon, CircleShape)
            .border(4.dp, RecapInk.White, CircleShape),
    ) {
        Text(main, style = RecapType.tag.copy(fontSize = size.sp, lineHeight = 0.95.em), color = RecapInk.Ink, maxLines = 2, softWrap = true, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
        if (small != null) Text(small, fontSize = 11.sp, fontWeight = FontWeight.Bold, color = RecapInk.Ink, modifier = Modifier.padding(top = 3.dp))
    }
}

/** A label of ink on a slant, its paper's shadow hard behind it. */
@Composable
internal fun Tag(text: String, s: SlideScope, at: Float, turn: Float, modifier: Modifier = Modifier) {
    Text(
        text, style = RecapType.tag, color = RecapInk.Cream, maxLines = 1, softWrap = false,
        modifier = modifier
            .enter(s, Entrance.SLAP, at, turn)
            .drawBehind { drawRoundRect(RecapInk.Ink.copy(alpha = 0.22f), Offset(2.dp.toPx(), 3.dp.toPx()), size, CornerRadius(8.dp.toPx())) }
            .background(RecapInk.Ink, RoundedCornerShape(8.dp))
            .padding(start = 10.dp, end = 10.dp, top = 6.dp, bottom = 7.dp),
    )
}

/** A note on white paper with its grain, taped on and a little crooked. */
@Composable
internal fun PaperNote(s: SlideScope, at: Float, turn: Float, modifier: Modifier = Modifier, fy: Float = 260f, fr: Float = -10f, content: @Composable ColumnScope.() -> Unit) {
    val density = LocalDensity.current.density
    val grain = remember(density) { PaperGrain.brush(density) }
    val shape = RoundedCornerShape(6.dp)
    Column(
        modifier
            .enter(s, Entrance.FLY, at, turn, fy = fy, fr = fr)
            .drawWithContent {
                drawContent()
                // The tape across its top edge.
                rotate(-3f, Offset(size.width / 2, 1.dp.toPx())) {
                    drawRect(Color.White.copy(alpha = 0.55f), Offset(size.width / 2 - 32.dp.toPx(), -9.dp.toPx()), Size(64.dp.toPx(), 20.dp.toPx()))
                }
            }
            .shadow(10.dp, shape, ambientColor = RecapInk.Shade, spotColor = RecapInk.Shade)
            .background(RecapInk.White, shape)
            .drawBehind { drawRect(grain) }
            .padding(start = 16.dp, end = 16.dp, top = 14.dp, bottom = 15.dp),
        content = content,
    )
}

/** The button that shares a card: in the slide's accent, popping on at [at]. */
@Composable
internal fun ShareButton(s: SlideScope, busy: Boolean, at: Float, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val c = s.paper
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = modifier
            .enter(s, Entrance.POP, at)
            .height(46.dp)
            .shadow(8.dp, RoundedCornerShape(23.dp))
            .clip(RoundedCornerShape(23.dp))
            .background(c.accent)
            .recapClick(onClick)
            .padding(start = 16.dp, end = 20.dp)
            .graphicsLayer { alpha = if (busy) 0.6f else 1f },
    ) {
        Icon(Icons.Rounded.Share, null, tint = c.onAccent, modifier = Modifier.size(20.dp))
        Text(stringResource(R.string.recap_share), style = RecapType.button, color = c.onAccent, maxLines = 1, softWrap = false)
    }
}

/**
 * A number that counts up once, from step [at] until it lands at step [land], fast then slowing,
 * with one small swell as it lands; then it stays still. The final number sits unseen underneath
 * and holds the space, so nothing round it moves while it counts. With [fit] it is made as large
 * as the width lets it be, up to [style]'s size. [spot] names it for the shapes to sit on.
 */
@Composable
internal fun Counted(
    target: Long,
    s: SlideScope,
    at: Float,
    land: Float,
    style: TextStyle,
    colour: Color,
    format: (Long) -> String,
    modifier: Modifier = Modifier,
    fit: Boolean = false,
    spot: String? = null,
) {
    val final = remember(target) { format(target) }
    if (s.still) s.arrives(at, RecapMotion.CALM_FADE_MS) else s.arrives(land, RecapMotion.LAND_MS)
    val live by remember(s.visit, target) {
        derivedStateOf { if (s.still) final else format(RecapMotion.counted(target, at, land, s.elapsed())) }
    }
    @Composable
    fun Number(sized: TextStyle) {
        val fontPx = with(LocalDensity.current) { sized.fontSize.toPx() }
        Box(
            (if (spot != null) Modifier.anchor(s, spot) else Modifier)
                .graphicsLayer {
                    transformOrigin = TransformOrigin(0f, 0.75f)
                    val t = s.elapsed() - at * RecapMotion.STEP_MS
                    if (s.still) {
                        alpha = RecapMotion.Ease.transform((t / RecapMotion.CALM_FADE_MS).coerceIn(0f, 1f))
                        return@graphicsLayer
                    }
                    val e = RecapMotion.CountInEasing.transform((t / RecapMotion.COUNT_IN_MS).coerceIn(0f, 1f))
                    alpha = e
                    translationY = (1f - e) * 0.12f * fontPx
                    var scale = 0.9f + 0.1f * e
                    val q = (s.elapsed() - land * RecapMotion.STEP_MS) / RecapMotion.LAND_MS
                    if (q in 0f..1f) scale *= if (q < 0.3f) 1f + 0.07f * RecapMotion.LandEasing.transform(q / 0.3f)
                    else 1.07f - 0.07f * RecapMotion.LandEasing.transform((q - 0.3f) / 0.7f)
                    scaleX = scale
                    scaleY = scale
                }
                .semantics { contentDescription = final }
        ) {
            Text(final, style = sized, color = colour, maxLines = 1, softWrap = false, modifier = Modifier.graphicsLayer { alpha = 0f }.clearAndSetSemantics { })
            Text(live, style = sized, color = colour, maxLines = 1, softWrap = false, modifier = Modifier.clearAndSetSemantics { })
        }
    }
    if (!fit) {
        Box(modifier) { Number(style) }
        return
    }
    BoxWithConstraints(modifier) {
        val measurer = rememberTextMeasurer()
        val room = constraints.maxWidth
        val sized = remember(final, style, room) {
            var size = style.fontSize.value
            while (size > 40f && measurer.measure(final, style.copy(fontSize = size.sp)).size.width > room) size -= 4f
            style.copy(fontSize = size.sp)
        }
        Number(sized)
    }
}

// ---- 1: since you started

@Composable
internal fun SinceSlide(s: SlideScope) {
    val r = s.shown.recap
    val c = s.paper
    val w = s.words
    val song = s.shown.song(r.firstSong.songId)
    SlideColumn { room ->
        Kick(stringResource(if (r.firstYear) R.string.recap_first_kick else R.string.recap_year_kick), s)
        val (before, after) = around(stringResource(if (r.firstYear) R.string.recap_first_title else R.string.recap_year_title, MARK))
        Words(before, s, at = 1f, style = RecapType.h1, colour = c.ink, strip = if (r.firstYear) w.day(r.sinceDay) else r.year.toString(), after = after, stripAt = 1.9f)
        val side = minOf(236.dp, room.width * 0.64f, room.height * 0.34f)
        val badge = side * 0.415f
        Box(Modifier.padding(top = 18.dp).fillMaxWidth().height(side + 26.dp)) {
            Sticker(
                song?.cover, side, s, Modifier.padding(start = 22.dp, top = 10.dp).anchor(s, Spot.COVER),
                at = 2.3f, turn = -5f, fx = 240f, fy = 320f, fr = 40f, toss = Toss(-320f, 120f, -40f),
            )
            Badge(
                if (r.firstYear) w.time(r.firstSong.minute) else w.dayShort(r.firstSong.day), s, at = 3.4f, turn = 11f, side = badge,
                modifier = Modifier.offset(x = 22.dp + side - badge * 0.37f, y = (-2).dp), small = stringResource(R.string.recap_badge_first_play),
                // Its time on two lines and "first play" under them, inside its circle at any size.
                size = (badge.value * 0.3f).coerceIn(18f, 30f),
            )
        }
        Text(
            if (r.firstYear) stringResource(R.string.recap_first_song_at, w.time(r.firstSong.minute)) else stringResource(R.string.recap_first_song_on, w.day(r.firstSong.day)),
            style = RecapType.kick, color = c.ink2, modifier = Modifier.padding(top = 14.dp, bottom = 4.dp).enter(s, Entrance.UP, 3.9f),
        )
        Words(song?.title.orEmpty(), s, at = 4.1f, style = RecapType.song, colour = c.ink)
        Text(song?.artists.orEmpty(), style = RecapType.line, color = c.ink2, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 6.dp).enter(s, Entrance.UP, 4.5f))
        Grow(room)
        Text(
            if (r.firstYear) pluralStringResource(R.plurals.recap_days_since, r.days, w.number(r.days)) else stringResource(R.string.recap_this_year_here),
            style = RecapType.quiet, color = c.ink2, modifier = Modifier.enter(s, Entrance.UP, 5.4f),
        )
    }
}

// ---- 2: minutes

@Composable
internal fun MinutesSlide(s: SlideScope) {
    val r = s.shown.recap
    val c = s.paper
    val w = s.words
    val minutes = w.minutes(r.playedMs)
    SlideColumn { room ->
        Kick(if (r.firstYear) stringResource(R.string.recap_minutes_kick_since, w.day(r.sinceDay)) else stringResource(R.string.recap_minutes_kick_year, r.year.toString()), s)
        Counted(minutes, s, at = 1f, land = 4f, style = RecapType.giant, colour = c.ink, format = { w.number(it) }, fit = true, spot = Spot.NUMBER, modifier = Modifier.offset(x = (-3).dp))
        Text(
            pluralStringResource(R.plurals.recap_minutes_unit, minutes.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()),
            style = RecapType.unit, color = c.ink, modifier = Modifier.padding(top = 2.dp).enter(s, Entrance.UP, 2f),
        )
        Grow(room)
        Sky(s, Modifier.fillMaxWidth().height(minOf(196.dp, room.height * 0.3f)))
        Grow(room)
        val days = minutes / 1440.0
        val half = Math.round(days * 2) / 2.0
        val time = when {
            days < 1 -> {
                val hours = max(1L, Math.round(minutes / 60.0)).toInt()
                pluralStringResource(R.plurals.recap_minutes_hours, hours, w.number(hours))
            }
            half % 1.0 == 0.0 -> pluralStringResource(R.plurals.recap_minutes_days, half.toInt(), w.number(half.toInt()))
            else -> stringResource(R.string.recap_minutes_days_half, w.number(floor(half).toInt()))
        }
        val most = r.mostTime?.let { m ->
            w.bold(R.string.recap_minutes_top, w.number(w.minutes(m.ms)) to true, s.shown.song(m.songId)?.title.orEmpty() to true)
        }
        Text(
            if (most != null) AnnotatedString("$time ") + most else AnnotatedString(time),
            style = RecapType.line, color = c.ink, modifier = Modifier.enter(s, Entrance.UP, 4.6f),
        )
    }
}

/** Listening on each day, a bar each in ink, the biggest day in lemon with its flag; the months under it. */
@Composable
private fun Sky(s: SlideScope, modifier: Modifier) {
    val r = s.shown.recap
    val c = s.paper
    val w = s.words
    val measurer = rememberTextMeasurer()
    val flag = stringResource(R.string.recap_flag, w.dayShort(r.biggestDay.day), w.length(r.biggestDay.ms))
    val months = remember(r) { (r.sinceDay..r.lastDay).filter { LocalDate.ofEpochDay(it).dayOfMonth == 1 } }
    val monthWords = months.map { w.monthShort(it) }
    if (s.still) s.arrives(2f, RecapMotion.CALM_FADE_MS)
    else s.arrives(RecapMotion.BAR_FROM, (r.msByDay.size * RecapMotion.BAR_GAP_MS).toInt() + RecapMotion.BAR_MS)
    s.arrives(2.3f, RecapMotion.FADE_MS)
    BoxWithConstraints(modifier.semantics { contentDescription = flag }) {
        val ticks = 24.dp
        val chart = maxHeight - ticks
        val n = r.msByDay.size
        val most = (r.msByDay.maxOrNull() ?: 0L).coerceAtLeast(1L)
        Canvas(Modifier.fillMaxWidth().height(chart)) {
            val el = s.elapsed()
            val gap = if (n > 120) 0f else 1.dp.toPx()
            val bar = (size.width - gap * (n - 1)) / n
            val corner = CornerRadius(minOf(bar / 2, 1.5.dp.toPx()))
            for ((k, ms) in r.msByDay.withIndex()) {
                if (ms <= 0) continue
                val grown = if (s.still) RecapMotion.Ease.transform(((el - 2 * RecapMotion.STEP_MS) / RecapMotion.CALM_FADE_MS).coerceIn(0f, 1f))
                else RecapMotion.BarEasing.transform(((el - RecapMotion.BAR_FROM * RecapMotion.STEP_MS - k * RecapMotion.BAR_GAP_MS) / RecapMotion.BAR_MS).coerceIn(0f, 1f))
                if (grown <= 0f) continue
                val full = max(1.dp.toPx(), ms.toFloat() / most * size.height)
                val h = if (s.still) full else full * grown
                val biggest = r.sinceDay + k == r.biggestDay.day
                val alpha = if (s.still) grown else 1f
                val top = Offset(k * (bar + gap), size.height - h)
                if (biggest) {
                    val ring = 1.5.dp.toPx()
                    drawRoundRect(RecapInk.Ink, Offset(top.x - ring, top.y - ring), Size(bar + 2 * ring, h + ring), corner, alpha = alpha)
                    drawRoundRect(RecapInk.Lemon, top, Size(bar, h), corner, alpha = alpha)
                } else drawRoundRect(RecapInk.Ink, top, Size(bar, h), corner, alpha = alpha)
            }
        }
        // The flag over the biggest day, turned to the side with more room.
        val bx = ((r.biggestDay.day - r.sinceDay) + 0.5f) / n
        val barTop = chart * (1f - r.biggestDay.ms.toFloat() / most)
        Tag(
            flag, s, at = 3.6f, turn = -4f,
            modifier = Modifier.layout { m, cs ->
                val p = m.measure(cs.copy(minWidth = 0, minHeight = 0))
                val width = cs.maxWidth
                val x = if (bx > 0.6f) ((bx + 0.04f) * width - p.width) else bx * width
                val y = barTop.roundToPx() - 10.dp.roundToPx() - p.height
                layout(p.width, p.height) { p.place(x.roundToInt().coerceIn(0, max(0, width - p.width)), y.coerceAtLeast(0)) }
            },
        )
        // The months under it.
        Canvas(Modifier.align(Alignment.BottomStart).fillMaxWidth().height(ticks).graphicsLayer {
            alpha = RecapMotion.Ease.transform(((s.elapsed() - 2.3f * RecapMotion.STEP_MS) / RecapMotion.FADE_MS).coerceIn(0f, 1f))
        }) {
            drawLine(RecapInk.Ink, Offset(0f, 1.dp.toPx()), Offset(size.width, 1.dp.toPx()), 2.dp.toPx())
            val style = TextStyle(fontSize = 12.sp, fontWeight = FontWeight.Medium, color = c.ink2)
            var lastEnd = -1f
            months.forEachIndexed { i, d ->
                val x = (d - r.sinceDay).toFloat() / n * size.width
                val layout = measurer.measure(monthWords[i], style)
                if (x < lastEnd || x + layout.size.width > size.width) return@forEachIndexed
                drawText(layout, topLeft = Offset(x, 6.dp.toPx()))
                lastEnd = x + layout.size.width + 6.dp.toPx()
            }
        }
    }
}

// ---- 3: the hour

@Composable
internal fun HourSlide(s: SlideScope) {
    val r = s.shown.recap
    val c = s.paper
    val w = s.words
    SlideColumn { room ->
        Kick(stringResource(R.string.recap_hour_kick), s)
        Counted(r.peakHour.toLong(), s, at = 1f, land = 3f, style = RecapType.giant, colour = c.ink, format = { w.hour(it.toInt()) }, fit = true, modifier = Modifier.offset(x = (-3).dp))
        Grow(room)
        // The design's 320, or what the room allows: the sun the shapes put in its middle, and the
        // ring round it, are sized from it (Choreography, Pose.fit).
        val side = minOf(320.dp, room.width, room.height * 0.42f)
        Dial(s, side, Modifier.align(Alignment.CenterHorizontally))
        Grow(room)
        PaperNote(s, at = 3.8f, turn = -1.2f) {
            Text(
                w.bold(
                    R.string.recap_hour_line,
                    w.hour(r.peakHour) to false,
                    w.hour((r.peakHour + 1) % 24) to false,
                    w.number(w.minutes(r.msByHour[r.peakHour])) to true,
                    s.shown.song(r.peakSong.songId)?.title.orEmpty() to true,
                ),
                style = RecapType.line, color = RecapInk.Ink,
            )
            r.lateNights?.let { late ->
                Box(Modifier.padding(top = 8.dp).fillMaxWidth()) {
                    Text(
                        stringResource(R.string.recap_late_nights, w.number(late.nights), w.time(late.latestMinute)),
                        style = RecapType.quiet, color = RecapInk.Ink.copy(alpha = 0.74f), modifier = Modifier.padding(end = 46.dp),
                    )
                    Moon(Modifier.align(Alignment.BottomEnd).offset(x = 8.dp, y = 6.dp).size(46.dp))
                }
            }
        }
    }
}

/** The day as a sun: a ray for each hour, as long as its listening, the listening hour in full ink. */
@Composable
private fun Dial(s: SlideScope, side: Dp, modifier: Modifier) {
    val r = s.shown.recap
    val w = s.words
    val measurer = rememberTextMeasurer()
    // The quarters of the day as a clock face marks them, in every language.
    val labels = listOf("00", "06", "12", "18")
    val most = (r.msByHour.maxOrNull() ?: 0L).coerceAtLeast(1L)
    if (s.still) s.arrives(2f, RecapMotion.CALM_FADE_MS) else s.arrives(RecapMotion.RAY_FROM, (23 * RecapMotion.RAY_GAP_MS).toInt() + RecapMotion.RAY_MS)
    s.arrives(1.6f, 500)
    Box(modifier.size(side).anchor(s, Spot.DIAL)) {
        Canvas(Modifier.fillMaxSize()) {
            val k = size.minDimension / 320f
            val el = s.elapsed()
            val r0 = 72f * k
            for (h in 0 until 24) {
                val a = h / 24.0 * 2 * PI - PI / 2
                val len = max(4f, r.msByHour[h].toFloat() / most * 58f) * k
                val peak = h == r.peakHour
                val (shown, alpha) = if (s.still) {
                    1f to RecapMotion.Ease.transform(((el - 2 * RecapMotion.STEP_MS) / RecapMotion.CALM_FADE_MS).coerceIn(0f, 1f))
                } else {
                    RecapMotion.RayEasing.transform(((el - RecapMotion.RAY_FROM * RecapMotion.STEP_MS - h * RecapMotion.RAY_GAP_MS) / RecapMotion.RAY_MS).coerceIn(0f, 1f)) to 1f
                }
                if (shown <= 0f || alpha <= 0f) continue
                val start = Offset(center.x + (cos(a) * r0).toFloat(), center.y + (sin(a) * r0).toFloat())
                val end = Offset(center.x + (cos(a) * (r0 + len * shown)).toFloat(), center.y + (sin(a) * (r0 + len * shown)).toFloat())
                drawLine(RecapInk.Ink.copy(alpha = if (peak) 1f else 0.42f), start, end, (if (peak) 13f else 9f) * k, StrokeCap.Round, alpha = alpha)
            }
            val labelAlpha = RecapMotion.Ease.transform(((el - (if (s.still) 2f else 1.6f) * RecapMotion.STEP_MS) / 500f).coerceIn(0f, 1f))
            val style = TextStyle(fontSize = 12.sp, fontWeight = FontWeight.Bold, color = RecapInk.Ink)
            listOf(0 to -1, 1 to 0, 0 to 1, -1 to 0).forEachIndexed { i, (dx, dy) ->
                val layout = measurer.measure(labels[i], style)
                drawText(layout, topLeft = Offset(center.x + dx * 44f * k - layout.size.width / 2f, center.y + dy * 44f * k - layout.size.height / 2f), alpha = labelAlpha)
            }
        }
        // The hour's minutes on a tag, to the left, level with the end of its ray.
        val k = side / 320f
        val pa = r.peakHour / 24.0 * 2 * PI - PI / 2
        val reach = (72f + r.msByHour[r.peakHour].toFloat() / most * 58f)
        Tag(
            stringResource(R.string.recap_minutes_only, w.number(w.minutes(r.msByHour[r.peakHour]))), s, at = 3.2f, turn = -6f,
            modifier = Modifier.offset(x = -(37f * k.value).dp, y = ((160f + sin(pa).toFloat() * reach - 76f) * k.value).dp),
        )
    }
}

private val MOON_PATH by lazy { PathParser().parsePathString("M38 6a24 24 0 1 0 16 38A20 20 0 0 1 38 6z").toPath() }
private val MOON_STAR by lazy { PathParser().parsePathString("M48 12l1.6 3.6 3.6 1.6-3.6 1.6L48 22.4l-1.6-3.6-3.6-1.6 3.6-1.6z").toPath() }

/** The late nights' moon, in plum with a paper edge, and a little lemon star. */
@Composable
private fun Moon(modifier: Modifier) {
    Canvas(modifier.graphicsLayer { rotationZ = -8f }) {
        scale(size.width / 60f, size.height / 60f, Offset.Zero) {
            drawPath(MOON_PATH, RecapInk.Plum)
            drawPath(MOON_PATH, RecapInk.White, style = Stroke(3.5f))
            drawPath(MOON_STAR, RecapInk.Lemon)
        }
    }
}

// ---- 4: the streak

@Composable
internal fun StreakSlide(s: SlideScope) {
    val r = s.shown.recap
    val streak = r.streak ?: return
    val c = s.paper
    val w = s.words
    SlideColumn { room ->
        Kick(stringResource(R.string.recap_streak_kick), s)
        Counted(streak.days.toLong(), s, at = 1f, land = 3f, style = RecapType.giant, colour = c.ink, format = { w.number(it) }, fit = true, modifier = Modifier.offset(x = (-3).dp))
        Text(pluralStringResource(R.plurals.recap_streak_unit, streak.days), style = RecapType.unit, color = c.ink, modifier = Modifier.padding(top = 2.dp).enter(s, Entrance.UP, 2f))
        Grow(room)
        Calendar(s, room)
        Grow(room)
        Text(stringResource(R.string.recap_streak_line, w.day(streak.firstDay), w.day(streak.lastDay)), style = RecapType.line, color = c.ink, modifier = Modifier.enter(s, Entrance.UP, 4.6f))
        Text(
            w.boldPlural(R.plurals.recap_streak_song, streak.songDays, s.shown.song(streak.songId)?.title.orEmpty() to true, w.number(streak.songDays) to false),
            style = RecapType.quiet, color = c.ink2, modifier = Modifier.padding(top = 6.dp).enter(s, Entrance.UP, 5f),
        )
    }
}

/** Every day of the Recap as a square, a column a week from Monday: the streak in violet, laid in one after another, other days with music in faint ink. */
@Composable
private fun Calendar(s: SlideScope, room: SlideRoom) {
    val r = s.shown.recap
    val streak = r.streak ?: return
    val c = s.paper
    val w = s.words
    val measurer = rememberTextMeasurer()
    val monday = r.sinceDay - Math.floorMod(r.sinceDay + 3, 7L)
    val weeks = ((r.lastDay - monday) / 7 + 1).toInt()
    val cell = minOf(15.dp, room.width / (weeks + (weeks - 1) * 0.2f))
    val gap = cell * 0.2f
    val months = remember(r) { (r.sinceDay..r.lastDay).filter { LocalDate.ofEpochDay(it).dayOfMonth == 1 || it == r.sinceDay } }
    val monthWords = months.map { w.monthShort(it) }
    val spread = minOf(RecapMotion.TILE_GAP_MS, RecapMotion.TILES_SPREAD_MS / max(1, streak.days))
    val firstCol = ((streak.firstDay - monday) / 7).toInt()
    val lastCol = ((streak.lastDay - monday) / 7).toInt()
    val gridHeight = cell * 7 + gap * 6
    s.arrives(2f, RecapMotion.FADE_MS)
    if (s.still) s.arrives(2f, RecapMotion.CALM_FADE_MS) else s.arrives(RecapMotion.TILE_FROM, (max(0, streak.days - 1) * spread).toInt() + RecapMotion.TILE_MS)
    Box(Modifier.fillMaxWidth().height(16.dp + gridHeight + 44.dp)) {
        Canvas(Modifier.fillMaxSize()) {
            val el = s.elapsed()
            val px = cell.toPx()
            val g = gap.toPx()
            val top = 16.dp.toPx()
            val fade = RecapMotion.Ease.transform(((el - 2 * RecapMotion.STEP_MS) / RecapMotion.FADE_MS).coerceIn(0f, 1f))
            val style = TextStyle(fontSize = 12.sp, fontWeight = FontWeight.Medium, color = c.ink2)
            var lastEnd = -1f
            months.forEachIndexed { i, d ->
                val x = ((d - monday) / 7) * (px + g)
                if (x < lastEnd) return@forEachIndexed
                val layout = measurer.measure(monthWords[i], style)
                if (x + layout.size.width > size.width) return@forEachIndexed
                drawText(layout, topLeft = Offset(x, 0f), alpha = fade)
                lastEnd = x + layout.size.width + 6.dp.toPx()
            }
            var k = 0
            val corner = CornerRadius(px * 0.27f)
            for (d in monday until monday + weeks * 7L) {
                if (d < r.sinceDay || d > r.lastDay) continue
                val col = ((d - monday) / 7).toInt()
                val row = Math.floorMod(d + 3, 7L).toInt()
                val at = Offset(col * (px + g), top + row * (px + g))
                val ms = r.msByDay[(d - r.sinceDay).toInt()]
                val inStreak = d in streak.firstDay..streak.lastDay
                when {
                    ms <= 0 -> {
                        val inset = 0.75.dp.toPx()
                        drawRoundRect(RecapInk.Ink.copy(alpha = 0.28f), at + Offset(inset, inset), Size(px - 2 * inset, px - 2 * inset), corner, style = Stroke(1.5.dp.toPx()), alpha = fade)
                    }
                    inStreak -> {
                        val t = if (s.still) fade else RecapMotion.TileEasing.transform(((el - RecapMotion.TILE_FROM * RecapMotion.STEP_MS - k++ * spread) / RecapMotion.TILE_MS).coerceIn(0f, 1f))
                        if (t > 0f) {
                            val mid = at + Offset(px / 2, px / 2)
                            rotate(if (s.still) 0f else -40f * (1f - t), mid) {
                                scale(if (s.still) 1f else t, if (s.still) 1f else t, mid) {
                                    drawRoundRect(RecapInk.Ink.copy(alpha = 0.25f), at + Offset(0f, 2.dp.toPx()), Size(px, px), corner, alpha = if (s.still) t else 1f)
                                    drawRoundRect(c.accent, at, Size(px, px), corner, alpha = if (s.still) t else 1f)
                                }
                            }
                        }
                    }
                    else -> drawRoundRect(RecapInk.Ink.copy(alpha = 0.24f), at, Size(px, px), corner, alpha = fade)
                }
            }
        }
        // "Day 1" under the streak's first week, "Day 64" under its last.
        val pinTop = 16.dp + gridHeight + 8.dp
        Tag(stringResource(R.string.recap_streak_day_first), s, at = 3.2f, turn = -6f, modifier = Modifier.offset(x = ((cell + gap) * firstCol - 20.dp).coerceAtLeast(0.dp), y = pinTop))
        Tag(
            stringResource(R.string.recap_streak_day_n, w.number(streak.days)), s, at = 4.2f, turn = 5f,
            modifier = Modifier.layout { m, cs ->
                val p = m.measure(cs.copy(minWidth = 0, minHeight = 0))
                val x = ((cell + gap) * lastCol - 20.dp).roundToPx().coerceIn(0, max(0, cs.maxWidth - p.width))
                layout(p.width, p.height) { p.place(x, pinTop.roundToPx()) }
            },
        )
    }
}

// ---- 5: your artists

/** The flower outline of a face, from the shapes' own. */
internal val FlowerShape = GenericShape { size, _ ->
    val pts = PaperShapes.of(Paper.FLOWER)
    for (p in 0 until PaperShapes.N) {
        val x = pts[2 * p] / 100f * size.width
        val y = pts[2 * p + 1] / 100f * size.height
        if (p == 0) moveTo(x, y) else lineTo(x, y)
    }
    close()
}

@Composable
internal fun ArtistsSlide(s: SlideScope) {
    val r = s.shown.recap
    val first = r.topArtists.firstOrNull() ?: return
    val c = s.paper
    val w = s.words
    fun picture(id: String, songId: String) = s.shown.artists[id]?.picture ?: s.shown.song(songId)?.cover
    SlideColumn { room ->
        Kick(stringResource(R.string.recap_artists_kick), s)
        val face = minOf(150.dp, room.width * 0.4f)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(18.dp), modifier = Modifier.padding(top = 6.dp, bottom = 4.dp)) {
            Box(Modifier.size(face).anchor(s, Spot.FACE)) {
                Box(
                    Modifier
                        .fillMaxSize()
                        .enter(s, Entrance.POP, 1.2f, -4f)
                        .shadow(10.dp, FlowerShape, ambientColor = RecapInk.Shade, spotColor = RecapInk.Shade)
                        .background(RecapInk.White, FlowerShape)
                        .padding(7.dp)
                ) {
                    AsyncImage(picture(first.artistId, first.topSongId), null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize().clip(FlowerShape).background(Color(0xFFDDDAD3)))
                }
                Badge(
                    stringResource(R.string.recap_badge_number_one), s, at = 2.2f, turn = 12f, side = face * 0.41f, size = 19f,
                    modifier = Modifier.align(Alignment.TopEnd).offset(x = 14.dp, y = (-8).dp),
                )
            }
            Column {
                Words(s.shown.artists[first.artistId]?.name.orEmpty(), s, at = 1.6f, style = RecapType.song, colour = c.ink, modifier = Modifier.padding(bottom = 8.dp))
                Text(
                    pluralStringResource(R.plurals.recap_artist_minutes, w.minutes(first.ms).toInt(), w.plays(first.plays), w.number(w.minutes(first.ms))) + "\n" +
                        stringResource(R.string.recap_most_played, s.shown.song(first.topSongId)?.title.orEmpty()),
                    style = RecapType.quiet, color = c.ink2, maxLines = 3, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.enter(s, Entrance.UP, 2.4f),
                )
            }
        }
        Column(Modifier.padding(top = 14.dp)) {
            r.topArtists.drop(1).forEachIndexed { i, a ->
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier
                        .height(50.dp)
                        .enter(s, Entrance.FLY, 3.2f + i * 0.4f, fx = if (i % 2 == 1) -360f else 360f, fy = 0f, fr = if (i % 2 == 1) -12f else 12f),
                ) {
                    Text("${i + 2}", style = RecapType.tag.copy(fontSize = 24.sp), color = c.accent, modifier = Modifier.width(22.dp))
                    Sticker(picture(a.artistId, a.topSongId), 44.dp, s, how = null, turn = listOf(-6f, 5f, -3f, 7f)[i % 4], round = true)
                    Text(s.shown.artists[a.artistId]?.name.orEmpty(), fontSize = 18.sp, fontWeight = FontWeight.Bold, color = c.ink, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                    Text(w.plays(a.plays), fontSize = 14.sp, color = c.ink2)
                }
            }
        }
        Grow(room)
        r.newArtists?.let { found ->
            val count = if (found.afterFirstMonth) R.plurals.recap_new_artists_first else R.plurals.recap_new_artists_year
            // "The best of them" only where there were several to choose from.
            val sentence = pluralStringResource(count, found.count, w.number(found.count)) +
                (if (found.bestFind != null && found.count > 1) " " + stringResource(R.string.recap_best_of_them) else "")
            val best = found.bestFind
            PaperNote(s, at = 5.2f, turn = 1.2f, fy = 280f, fr = 12f, modifier = Modifier.padding(top = 16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    if (best != null) {
                        val artist = s.shown.artists[best.artistId]
                        Sticker(artist?.picture ?: s.shown.song(best.topSongId)?.cover, 64.dp, s, how = null, turn = -5f, round = true)
                    }
                    Column {
                        Text(sentence, style = RecapType.quiet, color = RecapInk.Ink.copy(alpha = 0.74f))
                        if (best != null) {
                            Text(s.shown.artists[best.artistId]?.name.orEmpty(), style = RecapType.tag.copy(fontSize = 24.sp), color = RecapInk.Ink, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 6.dp, bottom = 4.dp))
                            Text(stringResource(R.string.recap_best_find_line, w.day(best.firstDay), w.plays(best.plays)), style = RecapType.quiet, color = RecapInk.Ink.copy(alpha = 0.74f))
                        }
                    }
                }
            }
        }
    }
}

// ---- 6: only on InterTune

@Composable
internal fun OnlySlide(s: SlideScope) {
    val r = s.shown.recap
    val c = s.paper
    val w = s.words
    SlideColumn { room ->
        Kick(stringResource(R.string.recap_only_kick), s)
        r.auto?.let { auto ->
            val song = s.shown.song(auto.stuck.songId)
            val side = minOf(132.dp, room.width * 0.36f)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(20.dp), modifier = Modifier.padding(top = 4.dp, bottom = 12.dp)) {
                Box(Modifier.size(side + 10.dp)) {
                    Sticker(song?.cover, side, s, at = 1.2f, turn = -6f, fx = -260f, fy = 120f, fr = -30f)
                    Tag(stringResource(R.string.recap_auto_chip), s, at = 2.2f, turn = -9f, modifier = Modifier.align(Alignment.BottomEnd).offset(x = 6.dp, y = 2.dp))
                }
                Column(Modifier.anchor(s, Spot.FOUND)) {
                    Counted(auto.stuck.plays.toLong(), s, at = 1.6f, land = 4f, style = RecapType.giant.copy(fontSize = 118.sp), colour = c.ink, format = { w.number(it) }, fit = true)
                    Text(stringResource(R.string.recap_auto_plays_since, w.day(auto.stuckFirstDay)), style = RecapType.tag.copy(fontSize = 19.sp, lineHeight = 1.05.em), color = c.ink, modifier = Modifier.padding(top = 2.dp).enter(s, Entrance.UP, 2.4f))
                }
            }
            val (before, after) = around(stringResource(R.string.recap_auto_title, MARK))
            Words(before, s, at = 2.6f, style = RecapType.h2, colour = c.ink, strip = song?.title.orEmpty(), after = after, stripAt = 3.4f, stripTurn = -3f, modifier = Modifier.padding(bottom = 6.dp))
            Text(stringResource(R.string.recap_auto_line, w.day(auto.stuckFirstDay)), style = RecapType.line, color = c.ink, modifier = Modifier.enter(s, Entrance.UP, 3.8f))
            Grow(room)
            val brought = auto.sinceDay?.let { pluralStringResource(R.plurals.recap_auto_brought_since, auto.songs, w.number(auto.songs), w.day(it)) }
                ?: pluralStringResource(R.plurals.recap_auto_brought, auto.songs, w.number(auto.songs))
            Text(brought + " " + stringResource(R.string.recap_auto_kept, w.number(auto.kept), w.number(auto.liked)), style = RecapType.quiet, color = c.ink2, modifier = Modifier.enter(s, Entrance.UP, 4.4f))
            Dots(auto, s, Modifier.fillMaxWidth().padding(top = 8.dp), maxHeight = minOf(150.dp, room.height * 0.18f))
            Grow(room)
        } ?: Grow(room)
        r.recognition?.let { heard ->
            Extra(s, at = 6.2f, first = true) {
                Box(Modifier.size(38.dp).background(RecapInk.Ink, CircleShape), contentAlignment = Alignment.Center) {
                    Icon(Icons.Rounded.GraphicEq, null, tint = RecapInk.Lemon, modifier = Modifier.size(20.dp))
                }
                Column(Modifier.weight(1f)) {
                    Text(pluralStringResource(R.plurals.recap_by_ear, heard.count, w.number(heard.count)), fontSize = 15.5.sp, fontWeight = FontWeight.Bold, color = c.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    val whenText = when {
                        heard.firstDay != heard.lastDay -> stringResource(R.string.recap_by_ear_from_to, w.day(heard.firstDay), w.day(heard.lastDay))
                        heard.count == 1 -> stringResource(R.string.recap_by_ear_on, w.day(heard.firstDay))
                        heard.count == 2 -> stringResource(R.string.recap_by_ear_both_on, w.day(heard.firstDay))
                        else -> stringResource(R.string.recap_by_ear_all_on, w.day(heard.firstDay))
                    }
                    Text(whenText, fontSize = 13.sp, color = c.ink2, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                Row {
                    heard.songIds.mapNotNull { s.shown.song(it)?.cover }.forEachIndexed { i, cover ->
                        Sticker(cover, 38.dp, s, Modifier.offset(x = (-10 * i).dp), how = null)
                    }
                }
            }
        }
        r.rooms?.let { rooms ->
            Extra(s, at = 6.6f, first = r.recognition == null) {
                People(Modifier.size(44.dp, 38.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        pluralStringResource(R.plurals.recap_rooms, rooms.rooms, w.number(rooms.rooms), pluralStringResource(R.plurals.recap_people, rooms.people, w.number(rooms.people))),
                        fontSize = 15.5.sp, fontWeight = FontWeight.Bold, color = c.ink,
                    )
                    Text(stringResource(R.string.recap_listen_together), fontSize = 13.sp, color = c.ink2)
                }
            }
        }
    }
}

/** One more thing only InterTune does, on a line of its own under a hairline of ink. */
@Composable
private fun Extra(s: SlideScope, at: Float, first: Boolean, content: @Composable androidx.compose.foundation.layout.RowScope.() -> Unit) {
    Column(Modifier.enter(s, Entrance.UP, at)) {
        if (!first) Box(Modifier.fillMaxWidth().height(2.dp).background(RecapInk.Ink.copy(alpha = 0.18f)))
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.height(46.dp), content = content)
    }
}

/** Three faces in a huddle, for the rooms: no one's in particular. */
@Composable
private fun People(modifier: Modifier) {
    Box(modifier) {
        listOf(RecapInk.Lemon to Offset(0f, 0f), RecapInk.Sky to Offset(20f, 3f), RecapInk.Mint to Offset(10f, 13f)).forEach { (colour, at) ->
            Box(Modifier.offset(at.x.dp, at.y.dp).size(24.dp).border(2.dp, RecapInk.White, CircleShape).padding(2.dp).background(colour, CircleShape))
        }
    }
}

/** Every song Auto brought, one dot each in the order they came, coming in once: kept ones in cream and bigger, liked ones ringed. */
@Composable
private fun Dots(auto: AutoFinds, s: SlideScope, modifier: Modifier, maxHeight: Dp) {
    BoxWithConstraints(modifier) {
        val n = auto.marks.size
        val width = maxWidth
        // As far apart as the room allows: 5.6 dp in the design's year of 1,733 songs, closer for
        // more, and bigger for a few, up to 12 dp.
        val fitting = sqrt(width.value * maxHeight.value / max(1, n)).dp
        val step = minOf(12.dp, maxOf(2.4.dp, fitting))
        val cols = max(1, floor(width / step).toInt())
        val rows = ceil(n / cols.toFloat()).toInt()
        s.arrives(RecapMotion.DOTS_FROM, if (s.still) 0 else (RecapMotion.DOTS_STEPS * RecapMotion.STEP_MS).toInt())
        Canvas(Modifier.fillMaxWidth().height(step * rows)) {
            val el = s.elapsed()
            val p = step.toPx()
            val k = p / 5.6.dp.toPx()
            val shown = if (s.still) (if (el > RecapMotion.DOTS_FROM * RecapMotion.STEP_MS) 1f else 0f)
            else ((el - RecapMotion.DOTS_FROM * RecapMotion.STEP_MS) / (RecapMotion.DOTS_STEPS * RecapMotion.STEP_MS)).coerceIn(0f, 1f)
            val upto = (shown * n).roundToInt()
            for (i in 0 until upto) {
                val m = auto.marks[i]
                val centre = Offset((i % cols) * p + p / 2, (i / cols) * p + p / 2)
                val fresh = if (s.still) 0f else max(0f, 1f - (upto - i) / 160f)
                if (m and AutoFinds.MARK_KEPT != 0) drawCircle(RecapInk.Cream, (2.35f + fresh * 1.6f) * k * density, centre)
                else drawCircle(RecapInk.Ink.copy(alpha = 0.3f), (1.45f + fresh) * k * density, centre)
                if (m and AutoFinds.MARK_LIKED != 0) drawCircle(RecapInk.Ink, 2.9f * k * density, centre, style = Stroke(1.3f * k * density))
            }
        }
    }
}

// ---- 7: the countdown

@Composable
internal fun CountdownSlide(s: SlideScope, step: Int) {
    val r = s.shown.recap
    val c = s.paper
    val w = s.words
    val rank = r.topSongs.size - step
    val top = r.topSongs[rank - 1]
    val song = s.shown.song(top.songId)
    val line = remember(r) { RecapStory.countdown(r.topSongs) }.getOrNull(step)
    val density = LocalDensity.current
    SlideColumn { room ->
        Kick(stringResource(R.string.recap_top_songs_kick), s, at = 0.6f)
        val stage = minOf(200.dp, room.height * 0.28f)
        val side = minOf(190.dp, room.width * 0.52f, stage - 10.dp)
        val odd = step % 2 == 1
        Box(Modifier.fillMaxWidth().height(stage).padding(bottom = 0.dp)) {
            Text(
                "$rank",
                style = RecapType.giant.copy(
                    fontSize = with(density) { (stage * 1.25f).toSp() },
                    shadow = Shadow(RecapInk.White, Offset(with(density) { 6.dp.toPx() }, with(density) { 7.dp.toPx() }), 0f),
                ),
                color = RecapInk.Ink,
                maxLines = 1,
                softWrap = false,
                overflow = TextOverflow.Visible,
                // Taller than its stage, as the design's is: measured at its own height, so that its foot is not cut.
                modifier = Modifier
                    .wrapContentHeight(Alignment.Top, unbounded = true)
                    .offset(x = (-6).dp, y = -(stage * 0.14f))
                    .anchor(s, Spot.NUMERAL)
                    .enter(s, Entrance.SLAP, 1f, -4f),
            )
            Sticker(
                song?.cover, side, s, Modifier.align(Alignment.TopEnd).padding(end = 2.dp, top = 4.dp).anchor(s, Spot.STAGE),
                at = 1.5f, turn = if (odd) -5f else 5f, fx = 280f, fy = -60f, fr = if (odd) -40f else 40f,
                toss = Toss(if (odd) -300f else -360f, if (odd) 300f else -200f, 40f),
            )
        }
        Spacer(Modifier.height(12.dp))
        Words(song?.title.orEmpty(), s, at = 2.1f, step = 0.18f, style = RecapType.h1.copy(fontSize = 31.sp, lineHeight = 1.em), colour = c.ink)
        Text(song?.artists.orEmpty(), fontSize = 15.5.sp, lineHeight = 1.3.em, color = c.ink2, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 5.dp, bottom = 8.dp).enter(s, Entrance.UP, 2.9f))
        Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(bottom = 6.dp).enter(s, Entrance.UP, 3.1f)) {
            Counted(top.plays.toLong(), s, at = 3.1f, land = 4f, style = RecapType.giant.copy(fontSize = 38.sp), colour = c.ink, format = { w.number(it) })
            Text(pluralStringResource(R.plurals.recap_plays_unit, top.plays), style = RecapType.tag.copy(fontSize = 26.sp), color = c.ink, modifier = Modifier.padding(bottom = 4.dp))
        }
        line?.let { Text(countdownWords(it, w), style = RecapType.line, color = c.ink, modifier = Modifier.enter(s, Entrance.UP, 3.6f)) }
        Grow(room)
        Column {
            for (place in 1..r.topSongs.size) {
                val other = r.topSongs[place - 1]
                val now = place == rank
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    modifier = Modifier
                        .enter(s, Entrance.UP, 4.4f + (place - 1) * 0.12f)
                        .fillMaxWidth()
                        .height(32.dp)
                        .then(if (now) Modifier.background(RecapInk.Ink, RoundedCornerShape(8.dp)) else Modifier)
                        .padding(horizontal = 8.dp),
                ) {
                    val ink = if (now) RecapInk.Cream else c.ink
                    Text("$place", style = RecapType.tag.copy(fontSize = 18.sp), color = ink, modifier = Modifier.width(16.dp))
                    if (place < rank) {
                        // Still to come: hidden, a blur and a bar.
                        Box(Modifier.size(26.dp).clip(RoundedCornerShape(5.dp))) {
                            AsyncImage(
                                s.shown.song(other.songId)?.cover, null, contentScale = ContentScale.Crop,
                                modifier = Modifier.fillMaxSize().then(if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) Modifier.blur(6.dp) else Modifier.graphicsLayer { alpha = 0.3f }),
                            )
                        }
                        Box(Modifier.fillMaxWidth(listOf(0.44f, 0.56f, 0.36f, 0.5f)[(place - 1) % 4]).height(9.dp).background(RecapInk.Ink.copy(alpha = 0.2f), RoundedCornerShape(5.dp)))
                    } else {
                        AsyncImage(s.shown.song(other.songId)?.cover, null, contentScale = ContentScale.Crop, modifier = Modifier.size(26.dp).clip(RoundedCornerShape(5.dp)).background(Color(0xFFDDDAD3)))
                        Text(s.shown.song(other.songId)?.title.orEmpty(), fontSize = 14.5.sp, fontWeight = FontWeight.Medium, color = ink, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                        Text(w.plays(other.plays), fontSize = 12.5.sp, color = if (now) RecapInk.Cream.copy(alpha = 0.75f) else c.ink2)
                    }
                }
            }
        }
        Text(
            if (rank > 2) stringResource(R.string.recap_tap_for_number, w.number(rank - 1)) else stringResource(R.string.recap_tap_for_one),
            fontSize = 13.sp, fontWeight = FontWeight.Medium, color = c.ink2, modifier = Modifier.padding(top = 10.dp).enter(s, Entrance.FADE, 5.2f),
        )
    }
}

@Composable
private fun countdownWords(line: CountdownLine, w: RecapWords): String = when (line) {
    is CountdownLine.Month -> stringResource(R.string.recap_line_month, w.number(line.plays), w.number(line.of), w.month(line.month))
    is CountdownLine.Days -> stringResource(R.string.recap_line_days, w.number(line.days))
    is CountdownLine.Minutes -> stringResource(R.string.recap_line_minutes, w.number(line.minutes))
    is CountdownLine.FoundDay -> stringResource(R.string.recap_line_found_day, w.number(line.plays), w.day(line.day))
    is CountdownLine.InARow -> stringResource(R.string.recap_line_in_a_row, w.number(line.times), w.day(line.day))
    is CountdownLine.BestDay -> stringResource(R.string.recap_line_best_day, w.number(line.plays), w.day(line.day))
    is CountdownLine.FirstHeard -> stringResource(R.string.recap_line_first, w.day(line.day))
}

// ---- 8: number one

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun NumberOneSlide(s: SlideScope, sharing: Boolean, onShare: () -> Unit) {
    val r = s.shown.recap
    val top = r.topSongs.first()
    val c = s.paper
    val w = s.words
    val song = s.shown.song(top.songId)
    // Its confetti passes behind every line of its words (Modifier.overConfetti): falling over them, it made them hard to read.
    val foundLines = remember { TextLines() }
    SlideColumn { room ->
        Kick(stringResource(R.string.recap_number_one_kick), s, at = 0.6f, modifier = Modifier.overConfetti(s, "kick", 0.6f))
        val side = minOf(260.dp, room.width * 0.72f, room.height * 0.36f)
        Box(Modifier.fillMaxWidth().padding(top = 6.dp, bottom = 14.dp).height(side + 28.dp), contentAlignment = Alignment.Center) {
            Sticker(song?.cover, side, s, Modifier.anchor(s, Spot.HERO), how = Entrance.BOOM, at = 3f, turn = -3f, toss = Toss(0f, 600f, 40f))
        }
        Words(song?.title.orEmpty(), s, at = 3.8f, step = 0.2f, style = RecapType.h1.copy(fontSize = 33.sp, lineHeight = 1.em), colour = c.ink, behind = "title")
        Text(
            song?.artists.orEmpty(), fontSize = 16.sp, lineHeight = 1.3.em, color = c.ink2, maxLines = 1, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 6.dp, bottom = 10.dp).overConfetti(s, "artists", 4.6f).enter(s, Entrance.UP, 4.6f),
        )
        // The plays and Share this on one line where they fit, the button under them where they do not.
        FlowRow(
            horizontalArrangement = Arrangement.SpaceBetween,
            itemVerticalAlignment = Alignment.Bottom,
            modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
        ) {
            Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Counted(
                    top.plays.toLong(), s, at = 4.8f, land = 6f, style = RecapType.giant.copy(fontSize = 92.sp), colour = c.accent, format = { w.number(it) },
                    modifier = Modifier.overConfetti(s, "plays", 4.8f),
                )
                Text(
                    pluralStringResource(R.plurals.recap_plays_unit, top.plays), style = RecapType.tag.copy(fontSize = 28.sp), color = c.ink,
                    modifier = Modifier.padding(bottom = 14.dp).overConfetti(s, "unit", 5f).enter(s, Entrance.UP, 5f),
                )
            }
            ShareButton(s, sharing, at = 6.4f, onClick = onShare, modifier = Modifier.padding(bottom = 12.dp))
        }
        val found = stringResource(R.string.recap_found_on, w.day(top.firstHeardDay), w.time(top.firstHeardMinute))
        val day = RecapStory.numberOneDay(top)?.let {
            " " + stringResource(if (it.inARow) R.string.recap_in_a_row_on else R.string.recap_times_on, w.day(it.day), w.number(it.times))
        }.orEmpty()
        Text(
            found + day, style = RecapType.line, color = c.ink, onTextLayout = { foundLines.layout = it },
            modifier = Modifier.overConfetti(s, "found", 5.6f) { foundLines.layout }.enter(s, Entrance.UP, 5.6f),
        )
        Grow(room)
    }
}

// ---- 9: the year on one card

@Composable
internal fun CardSlide(s: SlideScope, card: Bitmap?, sharing: Boolean, onShare: () -> Unit) {
    SlideColumn { room ->
        val width = minOf(300.dp, room.width, (room.height - 84.dp) * (9f / 16f))
        Box(
            Modifier
                .padding(top = 2.dp)
                .align(Alignment.CenterHorizontally)
                .enter(s, Entrance.DROP, 0.8f, -2f)
                .width(width)
                .aspectRatio(9f / 16f)
                .shadow(18.dp, RoundedCornerShape(14.dp), ambientColor = RecapInk.Shade, spotColor = RecapInk.Shade)
                .background(RecapInk.White, RoundedCornerShape(14.dp))
                .padding(5.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(RecapInk.Blue),
        ) {
            card?.let { Image(it.asImageBitmap(), stringResource(R.string.recap_card_described), Modifier.fillMaxSize(), contentScale = ContentScale.Crop) }
        }
        ShareButton(s, sharing, at = 3f, onClick = onShare, modifier = Modifier.align(Alignment.CenterHorizontally).padding(top = 18.dp))
    }
}

// ---- 10: thank you

@Composable
internal fun ThanksSlide(s: SlideScope, onAgain: () -> Unit, onDone: () -> Unit) {
    val r = s.shown.recap
    val first = s.shown.song(r.firstSong.songId)?.title.orEmpty()
    val density = LocalDensity.current
    SlideColumn { room ->
        Grow(room)
        Words(
            stringResource(R.string.recap_thanks_title), s, at = 1f, step = 0.35f,
            style = RecapType.h1.copy(fontSize = 84.sp, lineHeight = 0.92.em, shadow = Shadow(RecapInk.Ink, Offset(with(density) { 5.dp.toPx() }, with(density) { 6.dp.toPx() }), 0f)),
            colour = RecapInk.Cream,
        )
        // The thank-you words, signed, in the design's box.
        Column(
            Modifier
                .padding(top = 22.dp, bottom = 14.dp)
                .enter(s, Entrance.FLY, 2f, -1.2f, fy = 300f, fr = 8f)
                .fillMaxWidth()
                .drawBehind {
                    drawRoundRect(
                        RecapInk.Ink.copy(alpha = 0.55f),
                        cornerRadius = CornerRadius(8.dp.toPx()),
                        style = Stroke(2.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(7.dp.toPx(), 5.dp.toPx()))),
                    )
                }
                .background(RecapInk.Cream.copy(alpha = 0.22f), RoundedCornerShape(8.dp))
                .padding(start = 18.dp, end = 18.dp, top = 20.dp, bottom = 18.dp)
        ) {
            Text(stringResource(R.string.recap_thanks_words), fontSize = 18.sp, lineHeight = 1.45.em, color = RecapInk.Ink)
            Text(stringResource(R.string.recap_thanks_signature), style = RecapType.tag.copy(fontSize = 24.sp), color = RecapInk.Ink, modifier = Modifier.padding(top = 10.dp))
        }
        Grow(room)
        Text(
            stringResource(if (r.firstYear) R.string.recap_see_you_first else R.string.recap_see_you_year, first),
            style = RecapType.quiet, color = s.paper.ink2, modifier = Modifier.enter(s, Entrance.UP, 3.4f),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.padding(top = 14.dp).enter(s, Entrance.UP, 3.8f)) {
            Text(
                stringResource(R.string.recap_watch_again), style = RecapType.button, color = RecapInk.Ink,
                modifier = Modifier
                    .height(46.dp)
                    .clip(RoundedCornerShape(23.dp))
                    .background(RecapInk.Ink.copy(alpha = 0.14f))
                    .border(2.dp, RecapInk.Ink, RoundedCornerShape(23.dp))
                    .recapClick(onAgain)
                    .padding(horizontal = 22.dp)
                    .wrapCentre(),
            )
            Text(
                stringResource(R.string.recap_done), style = RecapType.button, color = RecapInk.Cream,
                modifier = Modifier
                    .height(46.dp)
                    .clip(RoundedCornerShape(23.dp))
                    .background(RecapInk.Ink)
                    .recapClick(onDone)
                    .padding(horizontal = 22.dp)
                    .wrapCentre(),
            )
        }
        Spacer(Modifier.height(4.dp))
    }
}
