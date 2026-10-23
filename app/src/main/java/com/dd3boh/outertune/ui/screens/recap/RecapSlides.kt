/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.ui.screens.recap

import android.graphics.Bitmap
import android.os.Build
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
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
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import com.dd3boh.outertune.R
import com.dd3boh.outertune.recap.AutoFinds
import com.dd3boh.outertune.recap.CountdownLine
import com.dd3boh.outertune.recap.RecapShown
import com.dd3boh.outertune.recap.RecapStory
import kotlinx.coroutines.delay
import java.time.LocalDate
import kotlin.math.PI
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/** What every slide is drawn from. [key] changes each time a slide comes up, which runs its entrances again. */
internal class SlideScope(
    val shown: RecapShown,
    val words: RecapWords,
    val covers: RecapCovers,
    val colours: SlideColours,
    val still: Boolean,
    val key: Any,
)

/** The room a slide has: on a short screen, a phone on its side or large text, the slide scrolls instead. */
internal class SlideRoom(val width: Dp, val height: Dp) {
    val tall: Boolean get() = height >= 540.dp
}

internal object RecapType {
    val kick = TextStyle(fontSize = 16.sp, fontWeight = FontWeight.Medium, lineHeight = 20.sp)
    val h1 = TextStyle(fontSize = 40.sp, fontWeight = FontWeight.Black, lineHeight = 1.04.em, letterSpacing = (-0.02).em)
    val giant = TextStyle(fontSize = 118.sp, fontWeight = FontWeight.Black, lineHeight = 0.9.em, letterSpacing = (-0.045).em)
    val unit = TextStyle(fontSize = 30.sp, fontWeight = FontWeight.Bold, letterSpacing = (-0.01).em)
    val line = TextStyle(fontSize = 17.sp, lineHeight = 1.42.em)
    val quiet = TextStyle(fontSize = 15.sp, lineHeight = 1.42.em)
}

/** A tap on a button of a slide: its own, never also a tap on the story behind it. */
internal fun Modifier.recapClick(onClick: () -> Unit): Modifier = clickable(onClick = onClick)

/** Words in a button of fixed height, in the middle of it. */
internal fun Modifier.wrapCentre(): Modifier = wrapContentHeight(Alignment.CenterVertically)

/** A slide's column: clear of the bars at the top and of the song at the foot. */
@Composable
internal fun SlideColumn(content: @Composable ColumnScope.(SlideRoom) -> Unit) {
    BoxWithConstraints(
        Modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .padding(top = 62.dp, bottom = 80.dp),
        contentAlignment = Alignment.TopCenter,
    ) {
        val room = SlideRoom(minOf(maxWidth, 480.dp) - 56.dp, maxHeight)
        Column(
            Modifier
                .widthIn(max = 480.dp)
                .fillMaxWidth()
                .then(if (room.tall) Modifier.fillMaxHeight() else Modifier.verticalScroll(rememberScrollState()))
                .padding(horizontal = 28.dp, vertical = 6.dp)
        ) { content(room) }
    }
}

/** The mock's flexible gap; a fixed one where the slide scrolls. */
@Composable
internal fun ColumnScope.Grow(room: SlideRoom) {
    if (room.tall) Spacer(Modifier.weight(1f)) else Spacer(Modifier.height(18.dp))
}

@Composable
internal fun Kick(text: String, s: SlideScope, order: Float = 0f) {
    Text(text, style = RecapType.kick, color = s.colours.ink2, modifier = Modifier.padding(bottom = 8.dp).rise(order, s))
}

/** A number as big as the slide's width lets it be, up to the mock's size. */
@Composable
internal fun Giant(text: String, color: Color, modifier: Modifier = Modifier, largest: TextUnit = 118.sp) {
    BasicText(
        text = text,
        modifier = modifier.fillMaxWidth().offset(x = (-4).dp),
        style = RecapType.giant.copy(color = color, fontSize = largest),
        maxLines = 1,
        softWrap = false,
        autoSize = TextAutoSize.StepBased(minFontSize = 36.sp, maxFontSize = largest, stepSize = 2.sp),
    )
}

@Composable
internal fun Cover(model: Any?, modifier: Modifier = Modifier, radius: Dp = 14.dp) {
    val shape = RoundedCornerShape(radius)
    AsyncImage(
        model = model,
        contentDescription = null,
        contentScale = ContentScale.Crop,
        modifier = modifier
            .shadow(22.dp, shape, clip = false)
            .clip(shape)
            .background(Color.White.copy(alpha = 0.08f)),
    )
}

@Composable
internal fun Face(model: Any?, size: Dp, ring: Color?, ringWidth: Dp = 3.dp) {
    AsyncImage(
        model = model,
        contentDescription = null,
        contentScale = ContentScale.Crop,
        modifier = Modifier
            .size(size)
            .then(if (ring != null) Modifier.border(BorderStroke(ringWidth, ring), CircleShape).padding(ringWidth) else Modifier)
            .clip(CircleShape)
            .background(Color.White.copy(alpha = 0.08f)),
    )
}

@Composable
private fun Hair(c: SlideColours, modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth().padding(vertical = 14.dp).height(1.dp).background(c.ink.copy(alpha = 0.16f)))
}

/** The button that shares a card: in the slide's own accent. */
@Composable
internal fun ShareButton(s: SlideScope, busy: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val c = s.colours
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = modifier
            .height(48.dp)
            .shadow(8.dp, RoundedCornerShape(24.dp))
            .clip(RoundedCornerShape(24.dp))
            .background(c.accent)
            .recapClick(onClick)
            .padding(start = 18.dp, end = 22.dp)
            .graphicsLayer { alpha = if (busy) 0.6f else 1f },
    ) {
        Icon(Icons.Rounded.Share, null, tint = c.onAccent, modifier = Modifier.size(20.dp))
        Text(stringResource(R.string.recap_share), color = c.onAccent, fontSize = 16.sp, fontWeight = FontWeight.Bold)
    }
}

// ---- 1: since you started

@Composable
internal fun SinceSlide(s: SlideScope) {
    val r = s.shown.recap
    val c = s.colours
    val w = s.words
    val song = s.shown.song(r.firstSong.songId)
    SlideColumn { room ->
        Kick(stringResource(if (r.firstYear) R.string.recap_first_kick else R.string.recap_year_kick), s)
        Text(
            if (r.firstYear) stringResource(R.string.recap_first_title, w.day(r.sinceDay)) else stringResource(R.string.recap_year_title, r.year.toString()),
            style = RecapType.h1.copy(fontSize = 44.sp), color = c.ink, modifier = Modifier.rise(1f, s),
        )
        Grow(room)
        Cover(song?.cover, Modifier.padding(top = 18.dp).size(minOf(292.dp, room.width, room.height * 0.4f)).rise(2f, s), radius = 16.dp)
        Spacer(Modifier.height(20.dp))
        Column(Modifier.rise(3f, s)) {
            Text(
                if (r.firstYear) stringResource(R.string.recap_first_song_at, w.time(r.firstSong.minute))
                else stringResource(R.string.recap_first_song_on, w.day(r.firstSong.day)),
                fontSize = 15.sp, color = c.ink2,
            )
            Text(song?.title.orEmpty(), fontSize = 30.sp, fontWeight = FontWeight.Black, lineHeight = 1.05.em, color = c.ink, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 4.dp))
            Text(song?.artists.orEmpty(), fontSize = 17.sp, color = c.ink2, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 4.dp))
        }
        Grow(room)
        Text(
            if (r.firstYear) pluralStringResource(R.plurals.recap_days_since, r.days, w.number(r.days)) else stringResource(R.string.recap_this_year_here),
            style = RecapType.quiet, color = c.ink2, modifier = Modifier.rise(4f, s),
        )
    }
}

// ---- 2: minutes

@Composable
internal fun MinutesSlide(s: SlideScope) {
    val r = s.shown.recap
    val c = s.colours
    val w = s.words
    val minutes = w.minutes(r.playedMs)
    SlideColumn { room ->
        Kick(
            if (r.firstYear) stringResource(R.string.recap_minutes_kick_since, w.day(r.sinceDay)) else stringResource(R.string.recap_minutes_kick_year, r.year.toString()),
            s,
        )
        Giant(w.number(countUp(minutes, s)), c.accent, Modifier.rise(1f, s))
        Text(
            pluralStringResource(R.plurals.recap_minutes_unit, minutes.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()),
            style = RecapType.unit, color = c.ink, modifier = Modifier.padding(top = 6.dp).rise(2f, s),
        )
        Grow(room)
        Sky(s, Modifier.fillMaxWidth().height(minOf(224.dp, room.height * 0.3f)))
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
            style = RecapType.line, color = c.ink, modifier = Modifier.rise(4f, s),
        )
    }
}

/** Listening on each day, a bar each, the biggest day lit and named. */
@Composable
private fun Sky(s: SlideScope, modifier: Modifier) {
    val r = s.shown.recap
    val c = s.colours
    val w = s.words
    val measurer = rememberTextMeasurer()
    val grow = remember(s.key) { Animatable(if (s.still) 1f else 0f) }
    LaunchedEffect(s.key) { if (!s.still) grow.animateTo(1f, tween(RecapMotion.SKY_MS, easing = LinearEasing)) }
    val flag = stringResource(R.string.recap_flag, w.dayShort(r.biggestDay.day), w.length(r.biggestDay.ms))
    val ticks = remember(r) {
        (r.sinceDay..r.lastDay).filter { it == r.sinceDay || LocalDate.ofEpochDay(it).dayOfMonth == 1 }
            .filterIndexed { i, d -> i == 0 || d - r.sinceDay > 12 }
    }
    val tickWords = ticks.map { w.monthShort(it) }
    Canvas(modifier.semantics { contentDescription = flag }) {
        val n = r.msByDay.size
        val chartH = size.height - 22.dp.toPx()
        // The flag of the biggest day stands over its bar, the tallest: room for it above the bars.
        val style = TextStyle(fontSize = 13.sp, fontWeight = FontWeight.Medium, color = c.accent)
        val layout = measurer.measure(flag, style)
        val barsH = chartH - layout.size.height - 8.dp.toPx()
        val most = (r.msByDay.maxOrNull() ?: 0L).coerceAtLeast(1L)
        val gap = if (n > 120) 0f else 1.dp.toPx()
        val bar = (size.width - gap * (n - 1)) / n
        val spread = 1000f / n
        for ((k, ms) in r.msByDay.withIndex()) {
            if (ms <= 0) continue
            val t = ((grow.value * RecapMotion.SKY_MS - 250f - k * spread) / 700f).coerceIn(0f, 1f)
            val p = 1f - (1f - t).pow(3)
            val h = max(1.dp.toPx(), ms.toFloat() / most * barsH) * p
            val biggest = r.sinceDay + k == r.biggestDay.day
            drawRect(
                color = if (biggest) c.accent else c.ink.copy(alpha = 0.42f),
                topLeft = Offset(k * (bar + gap), chartH - h),
                size = Size(bar, h),
            )
        }
        // The flag over the biggest day, turned to the side with more room.
        val bx = ((r.biggestDay.day - r.sinceDay) + 0.5f) / n * size.width
        val fy = chartH - r.biggestDay.ms.toFloat() / most * barsH - layout.size.height - 8.dp.toPx()
        val fx = if (bx > size.width * 0.6f) bx - layout.size.width else bx
        drawText(layout, topLeft = Offset(fx.coerceIn(0f, size.width - layout.size.width), fy.coerceAtLeast(0f)), alpha = ((grow.value - 0.5f) * 3f).coerceIn(0f, 1f))
        // The months under it.
        drawLine(c.ink.copy(alpha = 0.16f), Offset(0f, chartH + 6.dp.toPx()), Offset(size.width, chartH + 6.dp.toPx()), 1.dp.toPx())
        val tick = TextStyle(fontSize = 12.sp, color = c.ink2)
        ticks.forEachIndexed { i, d ->
            drawText(measurer, tickWords[i], topLeft = Offset((d - r.sinceDay).toFloat() / n * size.width, chartH + 9.dp.toPx()), style = tick)
        }
    }
}

// ---- 3: the hour

@Composable
internal fun HourSlide(s: SlideScope) {
    val r = s.shown.recap
    val c = s.colours
    val w = s.words
    SlideColumn { room ->
        Kick(stringResource(R.string.recap_hour_kick), s)
        Giant(w.hour(r.peakHour), c.accent, Modifier.rise(1f, s))
        Grow(room)
        Dial(s, Modifier.align(Alignment.CenterHorizontally).size(minOf(300.dp, room.width, room.height * 0.4f)).rise(2f, s))
        Grow(room)
        Text(
            w.bold(
                R.string.recap_hour_line,
                w.hour(r.peakHour) to false,
                w.hour((r.peakHour + 1) % 24) to false,
                w.number(w.minutes(r.msByHour[r.peakHour])) to true,
                s.shown.song(r.peakSong.songId)?.title.orEmpty() to true,
            ),
            style = RecapType.line, color = c.ink, modifier = Modifier.rise(3f, s),
        )
        r.lateNights?.let { late ->
            Text(
                stringResource(R.string.recap_late_nights, w.number(late.nights), w.time(late.latestMinute)),
                style = RecapType.quiet, color = c.ink2, modifier = Modifier.padding(top = 10.dp).rise(4f, s),
            )
        }
    }
}

/** The day as a clock face: a line for each hour, as long as its listening, the listening hour lit. */
@Composable
private fun Dial(s: SlideScope, modifier: Modifier) {
    val r = s.shown.recap
    val c = s.colours
    val measurer = rememberTextMeasurer()
    val drawn = remember(s.key) { Animatable(if (s.still) 1f else 0f) }
    LaunchedEffect(s.key) { if (!s.still) drawn.animateTo(1f, tween(RecapMotion.DIAL_MS, easing = LinearEasing)) }
    // The quarters of the day as a clock face marks them, in every language: the hour itself is
    // said above and below in the phone's own way, "6:00 PM" or "18:00", which is too long here.
    val labels = listOf("00", "06", "12", "18")
    Canvas(modifier) {
        val unit = size.minDimension / 320f
        val cx = size.width / 2
        val cy = size.height / 2
        val most = (r.msByHour.maxOrNull() ?: 0L).coerceAtLeast(1L)
        for (h in 0 until 24) {
            val a = h / 24.0 * 2 * PI - PI / 2
            val len = max(3f, r.msByHour[h].toFloat() / most * 84f) * unit
            val t = ((drawn.value * RecapMotion.DIAL_MS - 260f - h * 22f) / 800f).coerceIn(0f, 1f)
            val p = 1f - (1f - t).pow(3)
            if (p <= 0f) continue
            val r0 = 64f * unit
            val start = Offset(cx + (cos(a) * r0).toFloat(), cy + (sin(a) * r0).toFloat())
            val end = Offset(cx + (cos(a) * (r0 + len * p)).toFloat(), cy + (sin(a) * (r0 + len * p)).toFloat())
            val peak = h == r.peakHour
            drawLine(
                color = if (peak) c.accent else c.ink.copy(alpha = 0.5f),
                start = start, end = end,
                strokeWidth = (if (peak) 11f else 8f) * unit,
                cap = StrokeCap.Round,
            )
        }
        val style = TextStyle(fontSize = 11.sp, color = c.ink2)
        listOf(0 to -1, 1 to 0, 0 to 1, -1 to 0).forEachIndexed { i, (dx, dy) ->
            val layout = measurer.measure(labels[i], style)
            drawText(layout, topLeft = Offset(cx + dx * 42f * unit - layout.size.width / 2f, cy + dy * 42f * unit - layout.size.height / 2f))
        }
    }
}

// ---- 4: the streak

@Composable
internal fun StreakSlide(s: SlideScope) {
    val r = s.shown.recap
    val streak = r.streak ?: return
    val c = s.colours
    val w = s.words
    SlideColumn { room ->
        Kick(stringResource(R.string.recap_streak_kick), s)
        Giant(w.number(countUp(streak.days.toLong(), s)), c.accent, Modifier.rise(1f, s))
        Text(pluralStringResource(R.plurals.recap_streak_unit, streak.days), style = RecapType.unit, color = c.ink, modifier = Modifier.padding(top = 6.dp).rise(2f, s))
        Grow(room)
        Calendar(s, Modifier.fillMaxWidth().padding(top = 18.dp).rise(3f, s), room)
        Grow(room)
        Text(stringResource(R.string.recap_streak_line, w.day(streak.firstDay), w.day(streak.lastDay)), style = RecapType.line, color = c.ink, modifier = Modifier.rise(4f, s))
        Text(
            w.boldPlural(R.plurals.recap_streak_song, streak.songDays, s.shown.song(streak.songId)?.title.orEmpty() to true, w.number(streak.songDays) to false),
            style = RecapType.quiet, color = c.ink2, modifier = Modifier.padding(top = 6.dp).rise(5f, s),
        )
    }
}

/** Every day of the Recap as a square, a column a week from Monday: the streak lit, other days with music faint. */
@Composable
private fun Calendar(s: SlideScope, modifier: Modifier, room: SlideRoom) {
    val r = s.shown.recap
    val streak = r.streak ?: return
    val c = s.colours
    val measurer = rememberTextMeasurer()
    val monday = r.sinceDay - Math.floorMod(r.sinceDay + 3, 7L)
    val weeks = ((r.lastDay - monday) / 7 + 1).toInt()
    val gapRatio = 0.17f
    val cell = minOf(16.dp, room.width / (weeks + (weeks - 1) * gapRatio))
    val gap = cell * gapRatio
    val lit = remember(s.key) { Animatable(if (s.still) 1f else 0f) }
    LaunchedEffect(s.key) { if (!s.still) lit.animateTo(1f, tween(RecapMotion.CALENDAR_MS, easing = LinearEasing)) }
    val months = remember(r) {
        (r.sinceDay..r.lastDay).filter { it == r.sinceDay || LocalDate.ofEpochDay(it).dayOfMonth == 1 }
    }
    val monthWords = months.map { s.words.monthShort(it) }
    val fourHours = 4 * 3_600_000f
    Canvas(modifier.height(cell * 7 + gap * 6 + 18.dp)) {
        val px = cell.toPx()
        val g = gap.toPx()
        val top = 18.dp.toPx()
        val style = TextStyle(fontSize = 12.sp, color = c.ink2)
        var lastLabelEnd = -1f
        months.forEachIndexed { i, d ->
            val x = ((d - monday) / 7) * (px + g)
            if (x < lastLabelEnd) return@forEachIndexed
            val layout = measurer.measure(monthWords[i], style)
            drawText(layout, topLeft = Offset(x, 0f))
            lastLabelEnd = x + layout.size.width + 6.dp.toPx()
        }
        var k = 0
        for (d in monday until monday + weeks * 7L) {
            if (d < r.sinceDay || d > r.lastDay) continue
            val col = ((d - monday) / 7).toInt()
            val row = Math.floorMod(d + 3, 7L).toInt()
            val at = Offset(col * (px + g), top + row * (px + g))
            val ms = r.msByDay[(d - r.sinceDay).toInt()]
            val corner = CornerRadius(px / 4)
            val inStreak = d in streak.firstDay..streak.lastDay
            when {
                ms <= 0 -> drawRoundRect(Color.White.copy(alpha = 0.2f), at, Size(px, px), corner, style = Stroke(1.dp.toPx()))
                inStreak -> {
                    val t = ((lit.value * RecapMotion.CALENDAR_MS - 400f - k++ * (1000f / max(1, streak.days))) / 500f).coerceIn(0f, 1f)
                    val strength = 0.5f + 0.5f * min(1f, ms / fourHours)
                    drawRoundRect(c.accent.copy(alpha = strength * (0.12f + 0.88f * t)), at, Size(px, px), corner)
                }
                else -> drawRoundRect(c.ink.copy(alpha = 0.16f + 0.34f * min(1f, ms / fourHours)), at, Size(px, px), corner)
            }
        }
    }
}

// ---- 5: your artists

@Composable
internal fun ArtistsSlide(s: SlideScope) {
    val r = s.shown.recap
    val first = r.topArtists.firstOrNull() ?: return
    val c = s.colours
    val w = s.words
    fun picture(id: String, songId: String) = s.shown.artists[id]?.picture ?: s.shown.song(songId)?.cover
    SlideColumn { room ->
        Kick(stringResource(R.string.recap_artists_kick), s)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(18.dp), modifier = Modifier.padding(top = 14.dp, bottom = 10.dp).rise(1f, s)) {
            Box(Modifier.shadow(16.dp, CircleShape)) { Face(picture(first.artistId, first.topSongId), minOf(144.dp, room.width * 0.4f), c.accent) }
            Column {
                Text(stringResource(R.string.recap_number_one), fontSize = 15.sp, fontWeight = FontWeight.Bold, color = c.accent)
                Text(s.shown.artists[first.artistId]?.name.orEmpty(), fontSize = 34.sp, fontWeight = FontWeight.Black, lineHeight = 1.02.em, letterSpacing = (-0.02).em, color = c.ink, maxLines = 3, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(vertical = 2.dp))
                Text(
                    pluralStringResource(R.plurals.recap_artist_minutes, w.minutes(first.ms).toInt(), w.plays(first.plays), w.number(w.minutes(first.ms))) + "\n" +
                        stringResource(R.string.recap_most_played, s.shown.song(first.topSongId)?.title.orEmpty()),
                    fontSize = 14.5.sp, lineHeight = 1.35.em, color = c.ink2, maxLines = 3, overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Column(Modifier.padding(top = 8.dp)) {
            r.topArtists.drop(1).forEachIndexed { i, a ->
                if (i > 0) Box(Modifier.fillMaxWidth().height(1.dp).background(c.ink.copy(alpha = 0.16f)))
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier.padding(vertical = 9.dp).rise(3f + i * 0.5f, s),
                ) {
                    Text("${i + 2}", fontSize = 17.sp, fontWeight = FontWeight.Bold, color = c.ink2, modifier = Modifier.width(18.dp))
                    Face(picture(a.artistId, a.topSongId), 42.dp, null)
                    Text(s.shown.artists[a.artistId]?.name.orEmpty(), fontSize = 18.sp, fontWeight = FontWeight.Medium, color = c.ink, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
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
            Text(sentence, style = RecapType.quiet, color = c.ink2, modifier = Modifier.padding(top = 16.dp).rise(5f, s))
            found.bestFind?.let { best ->
                val artist = s.shown.artists[best.artistId]
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.padding(top = 10.dp).rise(5.5f, s)) {
                    Face(artist?.picture ?: s.shown.song(best.topSongId)?.cover, 52.dp, c.accent, 2.dp)
                    Column {
                        Text(artist?.name.orEmpty(), fontSize = 20.sp, fontWeight = FontWeight.Bold, color = c.ink, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Text(stringResource(R.string.recap_best_find_line, w.day(best.firstDay), w.plays(best.plays)), fontSize = 14.sp, color = c.ink2)
                    }
                }
            }
        }
    }
}

// ---- 6: only on InterTune

@Composable
internal fun OnlyHereSlide(s: SlideScope) {
    val r = s.shown.recap
    val c = s.colours
    val w = s.words
    SlideColumn { room ->
        Kick(stringResource(R.string.recap_only_kick), s)
        r.auto?.let { auto ->
            val song = s.shown.song(auto.stuck.songId)
            Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(18.dp), modifier = Modifier.padding(top = 12.dp, bottom = 18.dp).rise(1f, s)) {
                Box {
                    Cover(song?.cover, Modifier.size(minOf(140.dp, room.width * 0.4f)))
                    Text(
                        stringResource(R.string.recap_auto_chip),
                        fontSize = 14.sp, fontWeight = FontWeight.Medium, color = c.ink,
                        modifier = Modifier
                            .align(Alignment.BottomStart)
                            .offset(x = (-8).dp, y = 10.dp)
                            .shadow(6.dp, RoundedCornerShape(8.dp))
                            .clip(RoundedCornerShape(8.dp))
                            .background(lerpColour(c.deep, c.accent, 0.34f))
                            .padding(horizontal = 14.dp, vertical = 6.dp),
                    )
                }
                Column {
                    Giant(w.number(auto.stuck.plays), c.accent, largest = 104.sp)
                    Text(stringResource(R.string.recap_auto_plays_since, w.day(auto.stuckFirstDay)), fontSize = 17.sp, fontWeight = FontWeight.Bold, color = c.ink, modifier = Modifier.padding(top = 8.dp))
                }
            }
            Text(stringResource(R.string.recap_auto_title, song?.title.orEmpty()), style = RecapType.h1.copy(fontSize = 34.sp), color = c.ink, maxLines = 3, overflow = TextOverflow.Ellipsis, modifier = Modifier.rise(2f, s))
            Text(stringResource(R.string.recap_auto_line, w.day(auto.stuckFirstDay)), style = RecapType.line, color = c.ink, modifier = Modifier.padding(vertical = 8.dp).rise(3f, s))
            Grow(room)
            val brought = auto.sinceDay?.let { pluralStringResource(R.plurals.recap_auto_brought_since, auto.songs, w.number(auto.songs), w.day(it)) }
                ?: pluralStringResource(R.plurals.recap_auto_brought, auto.songs, w.number(auto.songs))
            Text(
                brought + " " + stringResource(R.string.recap_auto_kept, w.number(auto.kept), w.number(auto.liked)),
                style = RecapType.quiet, color = c.ink2, modifier = Modifier.rise(3.5f, s),
            )
            Dots(auto, c, Modifier.fillMaxWidth().padding(top = 12.dp).rise(3.8f, s), maxHeight = minOf(150.dp, room.height * 0.18f))
            Grow(room)
        } ?: Grow(room)
        r.recognition?.let { heard ->
            Hair(c, Modifier.rise(4f, s))
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp), modifier = Modifier.rise(4.5f, s)) {
                Box(Modifier.size(44.dp).clip(CircleShape).background(Color.White.copy(alpha = 0.12f)), contentAlignment = Alignment.Center) {
                    Icon(Icons.Rounded.GraphicEq, null, tint = c.accent)
                }
                Column(Modifier.weight(1f)) {
                    Text(pluralStringResource(R.plurals.recap_by_ear, heard.count, w.number(heard.count)), fontSize = 17.sp, fontWeight = FontWeight.Bold, color = c.ink)
                    val whenText = when {
                        heard.firstDay != heard.lastDay -> stringResource(R.string.recap_by_ear_from_to, w.day(heard.firstDay), w.day(heard.lastDay))
                        heard.count == 1 -> stringResource(R.string.recap_by_ear_on, w.day(heard.firstDay))
                        heard.count == 2 -> stringResource(R.string.recap_by_ear_both_on, w.day(heard.firstDay))
                        else -> stringResource(R.string.recap_by_ear_all_on, w.day(heard.firstDay))
                    }
                    Text(whenText, fontSize = 14.sp, color = c.ink2, modifier = Modifier.padding(top = 2.dp))
                }
                Row {
                    heard.songIds.mapNotNull { s.shown.song(it)?.cover }.forEachIndexed { i, cover ->
                        Cover(cover, Modifier.offset(x = (-12 * i).dp).size(38.dp).border(2.dp, Color.Black.copy(alpha = 0.35f), RoundedCornerShape(8.dp)), radius = 8.dp)
                    }
                }
            }
        }
        r.rooms?.let { rooms ->
            Hair(c, Modifier.rise(5f, s))
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp), modifier = Modifier.rise(5.5f, s)) {
                People(Modifier.size(52.dp, 44.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        pluralStringResource(R.plurals.recap_rooms, rooms.rooms, w.number(rooms.rooms), pluralStringResource(R.plurals.recap_people, rooms.people, w.number(rooms.people))),
                        fontSize = 17.sp, fontWeight = FontWeight.Bold, color = c.ink,
                    )
                    Text(stringResource(R.string.recap_listen_together), fontSize = 14.sp, color = c.ink2, modifier = Modifier.padding(top = 2.dp))
                }
            }
        }
    }
}

private fun lerpColour(a: Color, b: Color, t: Float) = Color(
    red = a.red + (b.red - a.red) * t,
    green = a.green + (b.green - a.green) * t,
    blue = a.blue + (b.blue - a.blue) * t,
    alpha = 1f,
)

/** Three faces in a huddle, for the rooms: no one's in particular. */
@Composable
private fun People(modifier: Modifier) {
    Box(modifier) {
        listOf(Color(0xFFFFB3B5) to Offset(0f, 0f), Color(0xFF9AD9A0) to Offset(26f, 4f), Color(0xFFC6B8FF) to Offset(12f, 18f)).forEach { (colour, at) ->
            Box(Modifier.offset(at.x.dp, at.y.dp).size(26.dp).border(2.dp, Color.Black.copy(alpha = 0.4f), CircleShape).clip(CircleShape).background(colour))
        }
    }
}

/** Every song Auto brought, one dot each in the order they came: lit when kept, ringed when liked. */
@Composable
private fun Dots(auto: AutoFinds, c: SlideColours, modifier: Modifier, maxHeight: Dp) {
    BoxWithConstraints(modifier) {
        val n = auto.marks.size
        val width = maxWidth
        // As far apart as the room there is allows: 5.6 dp in the mock's year of 1,733 songs,
        // closer for more, and bigger for a few, up to 12 dp.
        val fitting = sqrt(width.value * maxHeight.value / max(1, n)).dp
        val step = minOf(12.dp, maxOf(2.4.dp, fitting))
        val cols = max(1, floor(width / step).toInt())
        val rows = ceil(n / cols.toFloat()).toInt()
        Canvas(Modifier.fillMaxWidth().height(step * rows)) {
            val p = step.toPx()
            val k = p / 5.6.dp.toPx()
            for ((i, m) in auto.marks.withIndex()) {
                val centre = Offset((i % cols) * p + p / 2, (i / cols) * p + p / 2)
                if (m and AutoFinds.MARK_KEPT != 0) drawCircle(c.accent, 2.2.dp.toPx() * k, centre)
                else drawCircle(c.ink.copy(alpha = 0.22f), 1.5.dp.toPx() * k, centre)
                if (m and AutoFinds.MARK_LIKED != 0) drawCircle(Color.White, 2.6.dp.toPx() * k, centre, style = Stroke(1.1.dp.toPx() * k))
            }
        }
    }
}

// ---- 7: the countdown

@Composable
internal fun CountdownSlide(s: SlideScope, step: Int) {
    val r = s.shown.recap
    val c = s.colours
    val w = s.words
    val rank = r.topSongs.size - step
    val top = r.topSongs[rank - 1]
    val song = s.shown.song(top.songId)
    val line = remember(r) { RecapStory.countdown(r.topSongs) }.getOrNull(step)
    val pop = remember(s.key, step) { Animatable(if (s.still) 1f else 0f) }
    LaunchedEffect(s.key, step) { if (!s.still) pop.animateTo(1f, tween(RecapMotion.POP_MS, easing = RecapMotion.PopEasing)) }
    SlideColumn { room ->
        Kick(stringResource(R.string.recap_top_songs_kick), s)
        Box(Modifier.fillMaxWidth().height(minOf(212.dp, room.height * 0.3f)).padding(top = 8.dp)) {
            Text(
                "$rank",
                style = TextStyle(
                    fontSize = 250.sp, fontWeight = FontWeight.Black, lineHeight = 1.em, letterSpacing = (-0.06).em,
                    color = c.accent, drawStyle = Stroke(width = with(androidx.compose.ui.platform.LocalDensity.current) { 2.5.dp.toPx() }),
                ),
                maxLines = 1,
                softWrap = false,
                overflow = TextOverflow.Visible,
                // Taller than the stage, as the mock's is: measured at its own height, so that its
                // foot is not cut off at the stage's.
                modifier = Modifier
                    .wrapContentHeight(Alignment.Top, unbounded = true)
                    .offset(x = (-8).dp, y = (-34).dp)
                    .graphicsLayer { alpha = pop.value; scaleX = 0.82f + 0.18f * pop.value; scaleY = scaleX },
            )
            Cover(
                song?.cover,
                Modifier.align(Alignment.TopEnd).padding(top = 8.dp).size(minOf(200.dp, room.width * 0.56f, room.height * 0.26f))
                    .graphicsLayer { alpha = pop.value; scaleX = 0.82f + 0.18f * pop.value; scaleY = scaleX },
            )
        }
        Spacer(Modifier.height(16.dp))
        Text(song?.title.orEmpty(), fontSize = 30.sp, fontWeight = FontWeight.Black, lineHeight = 1.04.em, color = c.ink, maxLines = 2, overflow = TextOverflow.Ellipsis)
        Text(song?.artists.orEmpty(), fontSize = 16.sp, color = c.ink2, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 4.dp, bottom = 10.dp))
        Text(w.plays(top.plays), fontSize = 22.sp, fontWeight = FontWeight.Bold, color = c.accent, modifier = Modifier.padding(bottom = 4.dp))
        line?.let { Text(countdownWords(it, w), style = RecapType.line, color = c.ink) }
        Grow(room)
        Column(Modifier.padding(top = 8.dp)) {
            for (place in 1..r.topSongs.size) {
                val other = r.topSongs[place - 1]
                val now = place == rank
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(42.dp)
                        .then(if (now) Modifier.clip(RoundedCornerShape(10.dp)).background(c.accent.copy(alpha = 0.2f)) else Modifier)
                        .padding(horizontal = if (now) 10.dp else 0.dp),
                ) {
                    Text("$place", fontSize = 15.sp, fontWeight = FontWeight.Bold, color = if (now) c.accent else c.ink2, modifier = Modifier.width(16.dp))
                    if (place < rank) {
                        // Still to come: hidden, a blur and a bar.
                        val hidden = s.shown.song(other.songId)?.cover
                        Box(Modifier.size(32.dp).clip(RoundedCornerShape(6.dp))) {
                            AsyncImage(
                                hidden, null, contentScale = ContentScale.Crop,
                                modifier = Modifier.fillMaxSize().then(if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) Modifier.blur(6.dp) else Modifier.graphicsLayer { alpha = 0.3f }),
                            )
                            Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.25f)))
                        }
                        Box(Modifier.fillMaxWidth(listOf(0.44f, 0.56f, 0.36f, 0.5f)[(place - 1) % 4]).height(10.dp).clip(RoundedCornerShape(5.dp)).background(Color.White.copy(alpha = 0.16f)))
                    } else {
                        Cover(s.shown.song(other.songId)?.cover, Modifier.size(32.dp), radius = 6.dp)
                        Text(s.shown.song(other.songId)?.title.orEmpty(), fontSize = 15.sp, fontWeight = FontWeight.Medium, color = c.ink, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                        Text(w.plays(other.plays), fontSize = 13.sp, color = if (now) c.accent else c.ink2)
                    }
                }
            }
        }
        Text(
            if (rank > 2) stringResource(R.string.recap_tap_for_number, w.number(rank - 1)) else stringResource(R.string.recap_tap_for_one),
            fontSize = 13.sp, color = c.ink2, modifier = Modifier.padding(top = 10.dp),
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

@Composable
internal fun NumberOneSlide(s: SlideScope, sharing: Boolean, onShare: () -> Unit) {
    val r = s.shown.recap
    val top = r.topSongs.first()
    val c = s.colours
    val w = s.words
    val song = s.shown.song(top.songId)
    val clear = remember(s.key) { Animatable(if (s.still) 1f else 0f) }
    LaunchedEffect(s.key) {
        if (s.still) return@LaunchedEffect
        delay(RecapMotion.UNBLUR_WAIT_MS)
        clear.animateTo(1f, tween(RecapMotion.UNBLUR_MS, easing = RecapMotion.UnblurEasing))
    }
    SlideColumn { room ->
        Kick(stringResource(R.string.recap_number_one_kick), s)
        val side = minOf(272.dp, room.width, room.height * 0.36f)
        val blurred = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) Modifier.blur((26 * (1f - clear.value)).dp) else Modifier
        Cover(
            song?.cover,
            Modifier
                .align(Alignment.CenterHorizontally)
                .padding(top = 14.dp, bottom = 22.dp)
                .size(side)
                .graphicsLayer {
                    scaleX = 0.9f + 0.1f * clear.value
                    scaleY = scaleX
                    alpha = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) 1f else 0.4f + 0.6f * clear.value
                }
                .then(blurred),
            radius = 18.dp,
        )
        Text(song?.title.orEmpty(), fontSize = 31.sp, fontWeight = FontWeight.Black, lineHeight = 1.04.em, color = c.ink, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.rise(6f, s))
        Text(song?.artists.orEmpty(), fontSize = 17.sp, color = c.ink2, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 5.dp, bottom = 14.dp).rise(6f, s))
        Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.padding(bottom = 10.dp).rise(7f, s)) {
            BasicText(
                w.number(countUp(top.plays.toLong(), s)),
                style = RecapType.giant.copy(fontSize = 84.sp, color = c.accent),
                maxLines = 1,
            )
            Text(pluralStringResource(R.plurals.recap_plays_unit, top.plays), fontSize = 24.sp, fontWeight = FontWeight.Bold, color = c.ink, modifier = Modifier.padding(bottom = 10.dp))
        }
        val found = stringResource(R.string.recap_found_on, w.day(top.firstHeardDay), w.time(top.firstHeardMinute))
        val day = RecapStory.numberOneDay(top)?.let {
            " " + stringResource(if (it.inARow) R.string.recap_in_a_row_on else R.string.recap_times_on, w.day(it.day), w.number(it.times))
        }.orEmpty()
        Text(found + day, style = RecapType.line, color = c.ink, modifier = Modifier.rise(8f, s))
        Grow(room)
        ShareButton(s, sharing, onShare, Modifier.padding(top = 16.dp, bottom = 22.dp).rise(9f, s))
    }
}

// ---- 9: the year on one card

@Composable
internal fun CardSlide(s: SlideScope, card: Bitmap?, sharing: Boolean, onShare: () -> Unit) {
    SlideColumn { room ->
        val width = minOf(356.dp, room.width, (room.height - 80.dp) * (9f / 16f))
        Box(
            Modifier
                .align(Alignment.CenterHorizontally)
                .width(width)
                .aspectRatio(9f / 16f)
                .shadow(30.dp, RoundedCornerShape(16.dp))
                .clip(RoundedCornerShape(16.dp))
                .border(1.dp, Color.White.copy(alpha = 0.1f), RoundedCornerShape(16.dp))
                .background(s.colours.deep)
                .rise(0f, s),
        ) {
            card?.let { Image(it.asImageBitmap(), stringResource(R.string.recap_card_described), Modifier.fillMaxSize()) }
        }
        ShareButton(s, sharing, onShare, Modifier.align(Alignment.CenterHorizontally).padding(top = 14.dp).rise(2f, s))
    }
}

// ---- 10: thank you

@Composable
internal fun ThanksSlide(s: SlideScope, onAgain: () -> Unit, onDone: () -> Unit) {
    val r = s.shown.recap
    val ink = Color(0xFFF7EEEE)
    val first = s.shown.song(r.firstSong.songId)?.title.orEmpty()
    SlideColumn { room ->
        Grow(room)
        Text(stringResource(R.string.recap_thanks_title), fontSize = 68.sp, fontWeight = FontWeight.Black, letterSpacing = (-0.035).em, lineHeight = 1.em, color = ink, modifier = Modifier.rise(0f, s))
        // The thank-you words, once they are written. Until then the box says whose they will be.
        Box(Modifier.padding(top = 26.dp, bottom = 18.dp).rise(1f, s)) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .drawBehind {
                        drawRoundRect(
                            Color.White.copy(alpha = 0.42f),
                            cornerRadius = CornerRadius(16.dp.toPx()),
                            style = Stroke(1.5.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(6.dp.toPx(), 4.dp.toPx()))),
                        )
                    }
                    .clip(RoundedCornerShape(16.dp))
                    .background(Color.White.copy(alpha = 0.04f))
                    .padding(start = 20.dp, end = 20.dp, top = 26.dp, bottom = 20.dp)
            ) {
                Text(stringResource(R.string.recap_thanks_placeholder), fontSize = 19.sp, lineHeight = 1.45.em, color = ink)
                Text(stringResource(R.string.recap_thanks_signature), fontSize = 19.sp, fontWeight = FontWeight.Bold, color = ink, modifier = Modifier.padding(top = 12.dp))
            }
            Text(
                stringResource(R.string.recap_thanks_placeholder_tag),
                fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Color(0xFF561D22),
                modifier = Modifier.offset(x = 14.dp, y = (-11).dp).clip(RoundedCornerShape(10.dp)).background(Color(0xFFFFB3B5)).padding(horizontal = 9.dp, vertical = 3.dp),
            )
        }
        Grow(room)
        Text(
            stringResource(if (r.firstYear) R.string.recap_see_you_first else R.string.recap_see_you_year, first),
            style = RecapType.quiet, color = Color(0xFFD7C1C1), modifier = Modifier.rise(2f, s),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.padding(top = 18.dp).rise(3f, s)) {
            Text(
                stringResource(R.string.recap_watch_again), fontSize = 16.sp, fontWeight = FontWeight.Bold, color = Color.White,
                modifier = Modifier.height(48.dp).clip(RoundedCornerShape(24.dp)).background(Color.White.copy(alpha = 0.12f)).recapClick(onAgain).padding(horizontal = 22.dp).wrapCentre(),
            )
            Text(
                stringResource(R.string.recap_done), fontSize = 16.sp, fontWeight = FontWeight.Bold, color = Color(0xFF561D22),
                modifier = Modifier.height(48.dp).clip(RoundedCornerShape(24.dp)).background(Color(0xFFFFB3B5)).recapClick(onDone).padding(horizontal = 22.dp).wrapCentre(),
            )
        }
        Spacer(Modifier.height(4.dp))
    }
}
