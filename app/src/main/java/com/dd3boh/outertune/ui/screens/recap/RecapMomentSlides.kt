/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.ui.screens.recap

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.dd3boh.outertune.R
import com.dd3boh.outertune.recap.CameBack
import com.dd3boh.outertune.recap.DayPlays
import com.dd3boh.outertune.recap.OnRepeat
import com.dd3boh.outertune.recap.PartOfDay
import com.dd3boh.outertune.recap.RecapMoments
import com.dd3boh.outertune.recap.Season
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.TimeZone
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/*
 * The four slides of 24 Nov 2026, each from the phone's own play history (RecapMoments): your seasons,
 * your 2 AM songs, the week you couldn't stop and the one you came back to. In the Recap's own look and
 * motion: their parts arrive once, with character, on the story's clock, and then rest.
 */

/**
 * A season's name and its colour on the slide. December to February is winter in the north and summer
 * in the south: which side of the equator the phone is on is told by its time zone's country, a list of
 * those that lie mostly south of it ([SOUTH]); anywhere else, or where it is not known, the north's.
 */
internal object RecapSeasonNames {
    enum class Name(val words: Int, val colour: Color) {
        WINTER(R.string.recap_season_winter, RecapInk.Cream),
        SPRING(R.string.recap_season_spring, RecapInk.Mint),
        SUMMER(R.string.recap_season_summer, RecapInk.Lemon),
        AUTUMN(R.string.recap_season_autumn, RecapInk.Orange),
    }

    /** Countries, by their two letters, that lie mostly south of the equator, or whose capital does. */
    val SOUTH = setOf(
        "AO", "AQ", "AR", "AS", "AU", "BI", "BO", "BR", "BW", "CD", "CG", "CK", "CL", "EC", "FJ", "FK", "GS", "ID", "KE", "KM",
        "LS", "MG", "MU", "MW", "MZ", "NA", "NC", "NR", "NU", "NZ", "PE", "PF", "PG", "PN", "PY", "RE", "RW", "SB", "SC", "SH",
        "SZ", "TF", "TL", "TO", "TV", "TZ", "UY", "VU", "WF", "WS", "YT", "ZA", "ZM", "ZW",
    )

    fun south(region: String?): Boolean = region != null && region.uppercase(Locale.ROOT) in SOUTH

    /** The country of the time zone [zone], as Android's ICU knows it: null where it is none or not known. */
    fun regionOf(zone: String): String? = runCatching { android.icu.util.TimeZone.getRegion(zone) }.getOrNull()

    /** The name of season [season] (RecapMoments.seasonOf, 0 for December to February) in the north, or in the [south]. */
    fun of(season: Int, south: Boolean): Name = Name.entries[(season + if (south) 2 else 0) % 4]

    /** The first and the last month of season [season], 1 to 12: December and February for the first. */
    fun months(season: Int): Pair<Int, Int> {
        val first = (season * 3 + 11) % 12 + 1
        return first to (first + 1) % 12 + 1
    }
}

/**
 * Two days of one week as its line says them: "3" and "9 March" where the language puts the day first
 * ("From 3 to 9 March"), "March 3" and "9" where it puts the month first, and both whole across two months.
 */
internal fun dayRange(w: RecapWords, from: Long, to: Long): Pair<String, String> {
    val a = LocalDate.ofEpochDay(from)
    val b = LocalDate.ofEpochDay(to)
    if (a.year != b.year || a.month != b.month) return w.day(from) to w.day(to)
    val whole = w.day(to)
    return if (whole.firstOrNull()?.isDigit() == true) w.number(a.dayOfMonth) to whole else w.day(from) to w.number(b.dayOfMonth)
}

// ---- your seasons

/**
 * One song a season (Recap.seasons), in the order of the year: each a row flying in from alternate
 * sides, its cover as a sticker, its season's name slapped on a strip of the season's colour, its title,
 * its plays then and the season's months. The busiest season's song plays under it.
 */
@Composable
internal fun SeasonsSlide(s: SlideScope) {
    val seasons = s.shown.recap.seasons?.seasons ?: return
    val south = remember { RecapSeasonNames.south(RecapSeasonNames.regionOf(TimeZone.getDefault().id)) }
    SlideColumn { room ->
        Kick(stringResource(R.string.recap_seasons_kick), s)
        val few = seasons.size <= 2
        val gap = if (few) 28.dp else 16.dp
        // The covers as large as four rows leave room for, larger for two or three.
        val side = minOf(if (few) 132.dp else 96.dp, room.width * 0.32f, (room.height - 70.dp - gap * (seasons.size - 1)) / seasons.size)
            .coerceAtLeast(56.dp)
        Grow(room)
        Column(verticalArrangement = Arrangement.spacedBy(gap)) {
            seasons.forEachIndexed { i, season -> SeasonRow(season, i, side, south, s) }
        }
        Grow(room)
    }
}

@Composable
private fun SeasonRow(season: Season, i: Int, side: Dp, south: Boolean, s: SlideScope) {
    val c = s.paper
    val w = s.words
    val name = RecapSeasonNames.of(season.season, south)
    val song = s.shown.song(season.songId)
    val (first, last) = RecapSeasonNames.months(season.season)
    val year = s.shown.recap.year
    val at = 1.2f + i * 0.45f
    val left = i % 2 == 0
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        modifier = Modifier
            .fillMaxWidth()
            .enter(s, Entrance.FLY, at, fx = if (left) -380f else 380f, fy = 0f, fr = if (left) -14f else 14f),
    ) {
        Sticker(song?.cover, side, s, how = null, turn = listOf(-5f, 4f, -3f, 5f)[i % 4])
        Column(Modifier.weight(1f)) {
            ColouredStrip(stringResource(name.words), name.colour, s, at = at + 0.6f, turn = listOf(-3f, 2.5f, -2f, 3f)[i % 4])
            Text(
                song?.title.orEmpty(), style = RecapType.h2.copy(fontSize = if (side < 110.dp) 24.sp else 28.sp, lineHeight = 1.04.em), color = c.ink,
                maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 8.dp),
            )
            Text(
                stringResource(
                    R.string.recap_season_line, w.plays(season.songPlays),
                    w.monthShort(LocalDate.of(year, first, 1).toEpochDay()), w.monthShort(LocalDate.of(year, last, 1).toEpochDay()),
                ),
                style = RecapType.quiet, color = c.ink2, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 3.dp),
            )
        }
    }
}

/** A word on a strip of [colour], slapped on a little crooked, as the slides' accent strips are. */
@Composable
private fun ColouredStrip(text: String, colour: Color, s: SlideScope, at: Float, turn: Float) {
    val shape = RoundedCornerShape(5.dp)
    Text(
        text, style = RecapType.tag.copy(fontSize = 19.sp), color = RecapInk.Ink, maxLines = 1, softWrap = false,
        modifier = Modifier
            .enter(s, Entrance.SLAP, at, turn)
            .drawBehind { drawRoundRect(RecapInk.Ink.copy(alpha = 0.2f), Offset(3.dp.toPx(), 4.dp.toPx()), size, CornerRadius(5.dp.toPx())) }
            .background(colour, shape)
            .padding(horizontal = 8.dp, vertical = 3.dp),
    )
}

// ---- your 2 AM songs, and the morning's

/**
 * What played after midnight (Recap.nightAndMorning), on the night's blue: its song's cover with the
 * hours on a tag, "After midnight it was" round its title, how often; and, where the mornings had
 * enough too, their song on a sunny note at the foot. Where only the mornings had enough, theirs alone,
 * as the night's would be.
 */
@Composable
internal fun NightSlide(s: SlideScope) {
    val n = s.shown.recap.nightAndMorning ?: return
    val night = n.night
    val main = night ?: n.morning ?: return
    val c = s.paper
    val w = s.words
    val song = s.shown.song(main.songId)
    val both = night != null && n.morning != null
    SlideColumn { room ->
        Kick(stringResource(if (night != null) R.string.recap_night_kick else R.string.recap_morning_kick), s)
        val side = minOf(if (both) 150.dp else 196.dp, room.width * 0.5f, room.height * (if (both) 0.26f else 0.34f))
        Box(Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 14.dp).height(side + 22.dp)) {
            Sticker(
                song?.cover, side, s, Modifier.padding(start = 8.dp, top = 6.dp).anchor(s, Spot.NIGHT),
                at = 1.2f, turn = -5f, fx = -260f, fy = -80f, fr = -30f, toss = Toss(-320f, 160f, -40f),
            )
            // The hours it stands for, on a tag at the cover's corner, never past the slide's edge.
            val hours = if (night != null) stringResource(R.string.recap_part_hours, w.time(0), w.time(RecapMoments.DAY_BEGINS_MIN))
            else stringResource(R.string.recap_part_hours, w.time(RecapMoments.DAY_BEGINS_MIN), w.time(RecapMoments.MORNING_ENDS_MIN))
            Tag(
                hours, s, at = 2.2f, turn = 6f,
                modifier = Modifier.layout { m, cs ->
                    val p = m.measure(cs.copy(minWidth = 0, minHeight = 0))
                    val x = (side - 22.dp).roundToPx().coerceIn(0, max(0, cs.maxWidth - p.width))
                    layout(p.width, p.height) { p.place(x, (side - 16.dp).roundToPx()) }
                },
            )
        }
        val (before, after) = around(stringResource(if (night != null) R.string.recap_night_title else R.string.recap_morning_title, MARK))
        Words(before, s, at = 2.4f, style = RecapType.h2, colour = c.ink, strip = song?.title.orEmpty(), after = after, stripAt = 3.1f, stripTurn = -3f, modifier = Modifier.padding(bottom = 8.dp))
        Text(
            pluralStringResource(if (night != null) R.plurals.recap_night_line else R.plurals.recap_morning_line, main.songPlays, w.number(main.songPlays)),
            style = RecapType.line, color = c.ink, modifier = Modifier.enter(s, Entrance.UP, 3.7f),
        )
        Grow(room)
        if (night != null) n.morning?.let { MorningNote(it, s) }
    }
}

/** The morning's song, on a note of sunny paper taped on at the foot of the night. */
@Composable
private fun MorningNote(m: PartOfDay, s: SlideScope) {
    val w = s.words
    val song = s.shown.song(m.songId)
    val quiet = RecapInk.Ink.copy(alpha = 0.78f)
    TapedNote(s, at = 4.4f, turn = 1.4f, paper = RecapInk.Lemon, modifier = Modifier.padding(top = 16.dp).fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            Sticker(song?.cover, 64.dp, s, how = null, turn = -5f)
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.recap_morning_note), style = RecapType.quiet, color = quiet, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    song?.title.orEmpty(), style = RecapType.tag.copy(fontSize = 22.sp), color = RecapInk.Ink, maxLines = 2, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 4.dp, bottom = 3.dp),
                )
                Text(w.plays(m.songPlays), style = RecapType.quiet, color = quiet, maxLines = 1)
            }
        }
    }
}

/** A note of [paper] with its grain, taped on and a little crooked: the slides' PaperNote, in a colour of its own. */
@Composable
private fun TapedNote(s: SlideScope, at: Float, turn: Float, paper: Color, modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    val density = LocalDensity.current.density
    val grain = remember(density) { PaperGrain.brush(density) }
    val shape = RoundedCornerShape(6.dp)
    Column(
        modifier
            .enter(s, Entrance.FLY, at, turn, fy = 280f, fr = 10f)
            .drawWithContent {
                drawContent()
                // The tape across its top edge.
                rotate(-3f, Offset(size.width / 2, 1.dp.toPx())) {
                    drawRect(Color.White.copy(alpha = 0.55f), Offset(size.width / 2 - 32.dp.toPx(), -9.dp.toPx()), Size(64.dp.toPx(), 20.dp.toPx()))
                }
            }
            .shadow(10.dp, shape, ambientColor = RecapInk.Shade, spotColor = RecapInk.Shade)
            .background(paper, shape)
            .drawBehind { drawRect(grain) }
            .padding(start = 16.dp, end = 16.dp, top = 14.dp, bottom = 15.dp),
        content = content,
    )
}

// ---- the week you couldn't stop

/** When the week's squares begin to pop in, in steps, and how long all of them take, however many there are. */
private const val WEEK_TILES_FROM = 2.6f
private const val WEEK_TILES_MS = 1_500f

/**
 * The week one song took over (Recap.onRepeat), its song under it: how many times counting up beside
 * its cover, its title, the seven days with a square for each play of it popping in day after day (the
 * busiest day in lemon), and the dates with how much of everything played those days it was.
 */
@Composable
internal fun WeekSlide(s: SlideScope) {
    val week = s.shown.recap.onRepeat ?: return
    val c = s.paper
    val w = s.words
    val song = s.shown.song(week.songId)
    SlideColumn { room ->
        Kick(stringResource(R.string.recap_week_kick), s, at = 0.6f)
        val side = minOf(136.dp, room.width * 0.38f, room.height * 0.24f)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp), modifier = Modifier.fillMaxWidth().padding(top = 2.dp)) {
            Column(Modifier.weight(1f).anchor(s, Spot.WEEK)) {
                Counted(week.plays.toLong(), s, at = 1.2f, land = 3.2f, style = RecapType.giant.copy(fontSize = 128.sp), colour = c.ink, format = { w.number(it) }, fit = true)
                Text(pluralStringResource(R.plurals.recap_week_times, week.plays), style = RecapType.unit, color = c.ink, modifier = Modifier.padding(top = 2.dp).enter(s, Entrance.UP, 2f))
            }
            Sticker(song?.cover, side, s, at = 1.8f, turn = 6f, fx = 320f, fy = -60f, fr = 30f, toss = Toss(300f, 200f, 40f))
        }
        Words(song?.title.orEmpty(), s, at = 2.4f, step = 0.18f, style = RecapType.h1.copy(fontSize = 31.sp, lineHeight = 1.em), colour = c.ink, modifier = Modifier.padding(top = 14.dp))
        Text(
            song?.artists.orEmpty(), fontSize = 15.5.sp, lineHeight = 1.3.em, color = c.ink2, maxLines = 1, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 5.dp).enter(s, Entrance.UP, 3f),
        )
        Grow(room)
        WeekChart(week, s, Modifier.fillMaxWidth().height(minOf(158.dp, room.height * 0.27f)))
        Grow(room)
        val (first, last) = dayRange(w, week.firstDay, week.lastDay)
        Text(
            w.boldPlural(R.plurals.recap_week_line, week.allPlays, first to false, last to false, w.number(week.plays) to true, w.number(week.allPlays) to false),
            style = RecapType.line, color = c.ink, modifier = Modifier.enter(s, Entrance.UP, 4.9f),
        )
    }
}

/** The week's days, a column of squares each, one square a play of the song, the busiest day's in the accent; its weekday and date under each. */
@Composable
private fun WeekChart(week: OnRepeat, s: SlideScope, modifier: Modifier) {
    val c = s.paper
    val w = s.words
    val measurer = rememberTextMeasurer()
    val locale = LocalConfiguration.current.locales[0] ?: Locale.getDefault()
    val days = week.byDay
    val labels = remember(week, locale) {
        val weekday = DateTimeFormatter.ofPattern("EEE", locale)
        days.indices.map { i -> LocalDate.ofEpochDay(week.firstDay + i).let { d -> weekday.format(d) to w.number(d.dayOfMonth) } }
    }
    val described = remember(week) { days.indices.joinToString(", ") { i -> "${labels[i].first} ${labels[i].second}: ${w.plays(days[i])}" } }
    val total = days.sum()
    val most = (days.maxOrNull() ?: 0).coerceAtLeast(1)
    val spread = WEEK_TILES_MS / max(1, total)
    if (s.still) s.arrives(2f, RecapMotion.CALM_FADE_MS) else s.arrives(WEEK_TILES_FROM, (WEEK_TILES_MS + RecapMotion.TILE_MS).toInt())
    s.arrives(2.2f, RecapMotion.FADE_MS)
    Canvas(modifier.semantics { contentDescription = described }) {
        val el = s.elapsed()
        // Seven places whatever the week holds, so that one cut short by the year's end keeps the same squares.
        val slot = size.width / RecapMoments.WEEK_DAYS
        val labelsH = 34.dp.toPx()
        val countH = 18.dp.toPx()
        val floor = size.height - labelsH
        val room = floor - countH
        val gap = 2.5.dp.toPx()
        val fits = (room + gap) / most - gap
        // A square a play where they can be seen as squares; for a day of more plays than that, one bar that grows as they come.
        val squares = fits >= 5.dp.toPx()
        val side = if (squares) minOf(slot * 0.5f, fits) else slot * 0.5f
        val fade = RecapMotion.Ease.transform(((el - 2.2f * RecapMotion.STEP_MS) / RecapMotion.FADE_MS).coerceIn(0f, 1f))
        drawLine(RecapInk.Ink, Offset(0f, floor + 1.dp.toPx()), Offset(size.width, floor + 1.dp.toPx()), 2.dp.toPx(), alpha = fade)
        val small = TextStyle(fontSize = 12.sp, fontWeight = FontWeight.Medium, color = c.ink2)
        val bold = TextStyle(fontSize = 13.sp, fontWeight = FontWeight.Bold, color = c.ink)
        val ring = 1.5.dp.toPx()
        var k = 0
        for (i in days.indices) {
            val left = i * slot + (slot - side) / 2
            val busiest = days[i] == most
            val fill = if (busiest) c.accent else RecapInk.Ink
            val top = if (squares) floor - days[i] * (side + gap) + gap else floor - days[i] * room / most
            var grown = 0f
            for (j in 0 until days[i]) {
                val t = if (s.still) RecapMotion.Ease.transform(((el - 2 * RecapMotion.STEP_MS) / RecapMotion.CALM_FADE_MS).coerceIn(0f, 1f))
                else RecapMotion.TileEasing.transform(((el - WEEK_TILES_FROM * RecapMotion.STEP_MS - k * spread) / RecapMotion.TILE_MS).coerceIn(0f, 1f))
                k++
                if (t <= 0f) continue
                if (!squares) {
                    grown += if (s.still) 1f else t
                    continue
                }
                // Each square from the floor up, popping in with a turn.
                val y = floor - (j + 1) * (side + gap) + gap
                val mid = Offset(left + side / 2, y + side / 2)
                val corner = CornerRadius(side * 0.22f)
                val alpha = if (s.still) t else 1f
                rotate(if (s.still) 0f else -30f * (1f - t), mid) {
                    scale(if (s.still) 1f else t, if (s.still) 1f else t, mid) {
                        if (busiest) drawRoundRect(RecapInk.Ink, Offset(left - ring, y - ring), Size(side + 2 * ring, side + 2 * ring), corner, alpha = alpha)
                        drawRoundRect(fill, Offset(left, y), Size(side, side), corner, alpha = alpha)
                    }
                }
            }
            if (!squares && grown > 0f) {
                val h = grown * room / most
                val corner = CornerRadius(min(side / 2, 4.dp.toPx()))
                if (busiest) drawRoundRect(RecapInk.Ink, Offset(left - ring, floor - h - ring), Size(side + 2 * ring, h + ring), corner)
                drawRoundRect(fill, Offset(left, floor - h), Size(side, h), corner)
            }
            // The day's count over its column once its squares are in, and its weekday and date under it.
            val last = WEEK_TILES_FROM * RecapMotion.STEP_MS + k * spread
            val shown = if (s.still) fade else RecapMotion.Ease.transform(((el - last) / RecapMotion.FADE_MS).coerceIn(0f, 1f))
            if (days[i] > 0) {
                val count = measurer.measure(w.number(days[i]), bold)
                drawText(count, topLeft = Offset(i * slot + (slot - count.size.width) / 2, top - count.size.height - 3.dp.toPx()), alpha = shown)
            }
            val day = measurer.measure(labels[i].first, small)
            drawText(day, topLeft = Offset(i * slot + (slot - day.size.width) / 2, floor + 6.dp.toPx()), alpha = fade)
            val date = measurer.measure(labels[i].second, bold)
            drawText(date, topLeft = Offset(i * slot + (slot - date.size.width) / 2, floor + 6.dp.toPx() + day.size.height), alpha = fade)
        }
    }
}

// ---- the one you came back to

/** When the line of the song's year draws itself, in steps: its plays before, the gap, its plays after. */
private const val BACK_BEFORE_FROM = 2.6f
private const val BACK_GAP_FROM = 3.3f
private const val BACK_AFTER_FROM = 4f
private const val BACK_GAP_MS = 700
private const val BACK_DOTS_MS = 700f
private const val BACK_DOT_MS = 450

/**
 * The song loved, dropped for months and found again (Recap.cameBack), its own song under it: its cover
 * flying back in from the side, "Back" stamped on it once the line of its year has shown it gone and
 * found, its title, and how long it was away and when it came back.
 */
@Composable
internal fun CameBackSlide(s: SlideScope) {
    val back = s.shown.recap.cameBack ?: return
    val c = s.paper
    val w = s.words
    val song = s.shown.song(back.songId)
    SlideColumn { room ->
        Kick(stringResource(R.string.recap_back_kick), s, at = 0.6f)
        val side = minOf(210.dp, room.width * 0.56f, room.height * 0.3f)
        Box(Modifier.fillMaxWidth().padding(top = 6.dp, bottom = 12.dp).height(side + 26.dp), contentAlignment = Alignment.Center) {
            Sticker(song?.cover, side, s, Modifier.anchor(s, Spot.BACK), how = Entrance.FLY, at = 1f, turn = -4f, fx = -440f, fy = 0f, fr = -28f)
            Tag(stringResource(R.string.recap_back_stamp), s, at = 4.4f, turn = -9f, modifier = Modifier.align(Alignment.BottomCenter).offset(x = side * 0.3f, y = (-4).dp))
        }
        Words(song?.title.orEmpty(), s, at = 1.8f, step = 0.2f, style = RecapType.h1.copy(fontSize = 33.sp, lineHeight = 1.em), colour = c.ink)
        Text(
            song?.artists.orEmpty(), fontSize = 16.sp, lineHeight = 1.3.em, color = c.ink2, maxLines = 1, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 6.dp).enter(s, Entrance.UP, 2.4f),
        )
        Grow(room)
        YearLine(back, s, Modifier.fillMaxWidth().height(96.dp))
        Grow(room)
        Text(
            w.boldPlural(R.plurals.recap_back_line, back.awayDays, w.number(back.awayDays) to true, w.day(back.lastBefore) to false, w.day(back.backOn) to false),
            style = RecapType.line, color = c.ink, modifier = Modifier.enter(s, Entrance.UP, 5f),
        )
    }
}

/**
 * The song's year as a line: a dot for each day it was played (bigger for more plays), in ink before it
 * was dropped and in the accent once it was back, the gap between as a dashed line drawn across with its
 * days on a tag, and the months under it.
 */
@Composable
private fun YearLine(back: CameBack, s: SlideScope, modifier: Modifier) {
    val r = s.shown.recap
    val c = s.paper
    val w = s.words
    val measurer = rememberTextMeasurer()
    val months = remember(r) { (r.sinceDay..r.lastDay).filter { LocalDate.ofEpochDay(it).dayOfMonth == 1 } }
    val monthWords = months.map { w.monthShort(it) }
    val before = back.byDay.filter { it.day <= back.lastBefore }
    val after = back.byDay.filter { it.day >= back.backOn }
    if (s.still) s.arrives(2f, RecapMotion.CALM_FADE_MS)
    else {
        s.arrives(BACK_BEFORE_FROM, BACK_DOTS_MS.toInt() + BACK_DOT_MS)
        s.arrives(BACK_GAP_FROM, BACK_GAP_MS)
        s.arrives(BACK_AFTER_FROM, BACK_DOTS_MS.toInt() + BACK_DOT_MS)
    }
    s.arrives(2.2f, RecapMotion.FADE_MS)
    val span = (r.lastDay - r.sinceDay + 1).toFloat()
    fun share(day: Long) = ((day - r.sinceDay) + 0.5f) / span
    BoxWithConstraints(modifier) {
        val ticks = 22.dp
        val dotsRoom = 18.dp
        Canvas(Modifier.fillMaxSize()) {
            val el = s.elapsed()
            val line = size.height - ticks.toPx() - dotsRoom.toPx() / 2
            val fade = RecapMotion.Ease.transform(((el - 2.2f * RecapMotion.STEP_MS) / RecapMotion.FADE_MS).coerceIn(0f, 1f))
            drawLine(RecapInk.Ink.copy(alpha = 0.25f), Offset(0f, line), Offset(size.width, line), 2.dp.toPx(), alpha = fade)
            // The months under it, as the minutes' chart has them.
            val style = TextStyle(fontSize = 12.sp, fontWeight = FontWeight.Medium, color = c.ink2)
            var lastEnd = -1f
            months.forEachIndexed { i, d ->
                val x = share(d) * size.width
                val layout = measurer.measure(monthWords[i], style)
                if (x < lastEnd || x + layout.size.width > size.width) return@forEachIndexed
                drawText(layout, topLeft = Offset(x, size.height - ticks.toPx() + 4.dp.toPx()), alpha = fade)
                lastEnd = x + layout.size.width + 6.dp.toPx()
            }
            // The gap: dashed, drawn across from its last play before to its first after.
            val x0 = share(back.lastBefore) * size.width
            val x1 = share(back.backOn) * size.width
            val g = if (s.still) 1f else RecapMotion.Snap.transform(((el - BACK_GAP_FROM * RecapMotion.STEP_MS) / BACK_GAP_MS).coerceIn(0f, 1f))
            val gapAlpha = if (s.still) RecapMotion.Ease.transform(((el - 2 * RecapMotion.STEP_MS) / RecapMotion.CALM_FADE_MS).coerceIn(0f, 1f)) else 1f
            if (g > 0f && gapAlpha > 0f) {
                clipRect(left = x0, right = x0 + (x1 - x0) * g) {
                    drawLine(
                        RecapInk.Ink, Offset(x0, line), Offset(x1, line), 2.5.dp.toPx(), alpha = 0.75f * gapAlpha,
                        pathEffect = PathEffect.dashPathEffect(floatArrayOf(6.dp.toPx(), 5.dp.toPx())),
                    )
                }
            }
            // The plays: before in ink, after in the accent, each day popping in in turn.
            fun dots(list: List<DayPlays>, from: Float, colour: Color) {
                val step = BACK_DOTS_MS / max(1, list.size)
                list.forEachIndexed { i, d ->
                    val t = if (s.still) RecapMotion.Ease.transform(((el - 2 * RecapMotion.STEP_MS) / RecapMotion.CALM_FADE_MS).coerceIn(0f, 1f))
                    else RecapMotion.TileEasing.transform(((el - from * RecapMotion.STEP_MS - i * step) / BACK_DOT_MS).coerceIn(0f, 1f))
                    if (t <= 0f) return@forEachIndexed
                    val radius = (2.6f + min(d.plays, 4) * 1.1f).dp.toPx() * (if (s.still) 1f else t)
                    val centre = Offset(share(d.day) * size.width, line)
                    drawCircle(RecapInk.Ink.copy(alpha = 0.25f), radius, centre + Offset(0f, 1.5.dp.toPx()), alpha = if (s.still) t else 1f)
                    drawCircle(colour, radius, centre, alpha = if (s.still) t else 1f)
                    drawCircle(RecapInk.Ink, radius, centre, style = Stroke(1.dp.toPx()), alpha = if (s.still) t else 1f)
                }
            }
            dots(before, BACK_BEFORE_FROM, RecapInk.Ink)
            dots(after, BACK_AFTER_FROM, c.accent)
        }
        // How long it was away, on a tag over the middle of the gap, never past the edges.
        val mid = (share(back.lastBefore) + share(back.backOn)) / 2f
        Tag(
            pluralStringResource(R.plurals.recap_back_days, back.awayDays, w.number(back.awayDays)), s, at = 3.8f, turn = -4f,
            modifier = Modifier.layout { m, cs ->
                val p = m.measure(cs.copy(minWidth = 0, minHeight = 0))
                val x = (mid * cs.maxWidth - p.width / 2f).roundToInt().coerceIn(0, max(0, cs.maxWidth - p.width))
                val y = (cs.maxHeight - ticks.roundToPx() - dotsRoom.roundToPx() / 2 - 14.dp.roundToPx() - p.height).coerceAtLeast(0)
                layout(p.width, p.height) { p.place(x, y) }
            },
        )
    }
}
