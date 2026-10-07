/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.ui.screens

import android.content.res.Resources
import android.text.format.DateFormat
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.TrendingDown
import androidx.compose.material.icons.automirrored.rounded.TrendingFlat
import androidx.compose.material.icons.automirrored.rounded.TrendingUp
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Bedtime
import androidx.compose.material.icons.rounded.Event
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.Explore
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.Headphones
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.LibraryMusic
import androidx.compose.material.icons.rounded.LocalFireDepartment
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.Radio
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.Weekend
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.dd3boh.outertune.R
import com.dd3boh.outertune.constants.ListThumbnailSize
import com.dd3boh.outertune.constants.StatPeriod
import com.dd3boh.outertune.constants.ThumbnailCornerRadius
import com.dd3boh.outertune.db.entities.ArtistEntity
import com.dd3boh.outertune.db.entities.Song
import com.dd3boh.outertune.stats.HourProfile
import com.dd3boh.outertune.stats.Insight
import com.dd3boh.outertune.stats.ListenSource
import com.dd3boh.outertune.stats.ListeningInsights
import com.dd3boh.outertune.stats.Summary
import com.dd3boh.outertune.ui.component.items.ItemThumbnail
import com.dd3boh.outertune.ui.utils.LocalLandscape
import com.dd3boh.outertune.utils.LocaleDateFormat
import com.dd3boh.outertune.viewmodels.PeriodInsights
import java.text.NumberFormat
import java.time.LocalDate
import java.time.LocalTime
import java.util.Locale

/** Findings shown before Show more: enough to fill a screen, few enough to reach the lists. */
private const val SHOWN_INSIGHTS = 6

/** What a finding about a song or an artist does when tapped, and what is playing now. */
class InsightActions(
    val activeSongId: String?,
    val isPlaying: Boolean,
    val onSong: (Song) -> Unit,
    val onSongMenu: (Song) -> Unit,
    val onArtist: (ArtistEntity) -> Unit,
)

/**
 * The top of the Stats page: the period's listening time, when in the day it happens, and the
 * findings under them, strongest first, the rest behind Show more.
 *
 * [loading] is true while another period is being worked out: what is on screen then belongs to
 * the period picked before, so it is dimmed rather than passed off as the new one. On a wide
 * screen the cards go two to a row.
 */
fun LazyListScope.statsInsights(
    shown: PeriodInsights,
    loading: Boolean,
    wide: Boolean,
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    actions: InsightActions,
) {
    val stats = shown.stats
    val dim = if (loading) 0.4f else 1f
    val summary = stats.summary
    val hours = stats.hours
    if (wide && summary != null && hours != null) {
        item(key = "insights_top") {
            Row(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 6.dp)
                    .height(IntrinsicSize.Min)
                    .alpha(dim)
                    .animateItem()
            ) {
                SummaryCard(summary, shown.period, Modifier.weight(1f).fillMaxHeight())
                HoursCard(hours, Modifier.weight(1f).fillMaxHeight())
            }
        }
    } else {
        // One of the two alone on a phone on its side is no wider than a page of settings
        // (Landscape.cardWidth). Upright that is no cap, and the card is the width it was.
        if (summary != null) item(key = "insights_summary") {
            val width = LocalLandscape.current.cardWidth()
            SummaryCard(summary, shown.period, Modifier.widthIn(max = width).cardPadding().alpha(dim).animateItem())
        }
        if (hours != null) item(key = "insights_hours") {
            val width = LocalLandscape.current.cardWidth()
            HoursCard(hours, Modifier.widthIn(max = width).cardPadding().alpha(dim).animateItem())
        }
    }

    // A finding whose song or artist is not there to show is left out rather than shown nameless.
    val cards = stats.insights.filter { i ->
        i.songId.let { it == null || it in shown.songs } && i.artistId.let { it == null || it in shown.artists }
    }
    val visible = if (expanded || cards.size <= SHOWN_INSIGHTS + 1) cards else cards.take(SHOWN_INSIGHTS)
    if (wide) {
        // Not made equal in height: a card's artwork measures itself in a way that cannot be asked
        // its height in advance, which IntrinsicSize would do, and throw.
        items(visible.chunked(2), key = { row -> "insights_" + row.joinToString("_") { it.javaClass.simpleName } }) { row ->
            Row(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 6.dp)
                    .alpha(dim)
                    .animateItem()
            ) {
                row.forEach { InsightCard(it, shown, actions, Modifier.weight(1f)) }
                if (row.size == 1) Spacer(Modifier.weight(1f))
            }
        }
    } else {
        items(visible, key = { "insight_" + it.javaClass.simpleName }) {
            InsightCard(it, shown, actions, Modifier.cardPadding().alpha(dim).animateItem())
        }
    }
    if (cards.size > visible.size || (expanded && cards.size > SHOWN_INSIGHTS + 1)) {
        item(key = "insights_more") {
            val hidden = cards.size - SHOWN_INSIGHTS
            val format = rememberStatsFormat()
            TextButton(
                onClick = { onExpandedChange(!expanded) },
                modifier = Modifier
                    .padding(horizontal = 4.dp)
                    .alpha(dim)
                    .animateItem()
            ) {
                Text(
                    if (expanded) stringResource(R.string.stats_show_fewer)
                    else pluralStringResource(R.plurals.stats_show_more, hidden, format.count(hidden))
                )
                Spacer(Modifier.width(4.dp))
                Icon(if (expanded) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore, contentDescription = null)
            }
        }
    }
}

private fun Modifier.cardPadding() = fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp)

/**
 * Numbers, lengths, dates and times, in the phone's locale and its 12 or 24 hour setting.
 * [pattern] turns a skeleton into ICU's pattern for the locale; tests give it fixed ones.
 */
class StatsFormat(
    private val res: Resources,
    private val locale: Locale,
    private val is24Hour: Boolean,
    private val pattern: (Locale, String) -> String? = { l, skeleton -> DateFormat.getBestDateTimePattern(l, skeleton) },
) {
    private val integer = NumberFormat.getIntegerInstance(locale)
    private val percent = NumberFormat.getPercentInstance(locale)
    private val decimal = NumberFormat.getNumberInstance(locale).apply { maximumFractionDigits = 1 }
    private val thisYear = LocalDate.now().year

    fun count(n: Int): String = integer.format(n)
    fun percent(share: Double): String = percent.format(share)
    fun ratio(r: Double): String = decimal.format(r)

    fun duration(ms: Long): String {
        val minutes = ms / ListeningInsights.MINUTE_MS
        val h = minutes / 60
        val m = (minutes % 60).toInt()
        return when {
            h == 0L -> res.getString(R.string.stats_duration_minutes, m)
            m == 0 -> res.getString(R.string.stats_duration_hours, integer.format(h))
            else -> res.getString(R.string.stats_duration_hours_minutes, integer.format(h), m)
        }
    }

    /** Each skeleton's format, made once: see [LocaleDateFormat] for why not straight from ICU. */
    private val formats = HashMap<String, LocaleDateFormat>()

    private fun format(skeleton: String, fallback: String) =
        formats.getOrPut(skeleton) { LocaleDateFormat(locale, skeleton, fallback, pattern) }

    /** A local epoch day: "Saturday 5 September", with the year when it is not this one. */
    fun day(epochDay: Long, weekday: Boolean = true): String {
        val date = LocalDate.ofEpochDay(epochDay)
        val withYear = date.year != thisYear
        val skeleton = (if (weekday) "EEEE" else "") + "dMMMM" + (if (withYear) "y" else "")
        val fallback = (if (weekday) "EEEE " else "") + "d MMMM" + (if (withYear) " y" else "")
        return format(skeleton, fallback).format(date)
    }

    fun timeOfDay(minute: Int): String =
        (if (is24Hour) format("Hm", "HH:mm") else format("hm", "h:mm a")).format(LocalTime.of(minute / 60, minute % 60))

    fun hour(h: Int): String =
        (if (is24Hour) format("Hm", "HH:mm") else format("ha", "h a")).format(LocalTime.of(h, 0))
}

@Composable
private fun rememberStatsFormat(): StatsFormat {
    val context = LocalContext.current
    val configuration = LocalConfiguration.current
    return remember(context, configuration) {
        StatsFormat(context.resources, configuration.locales[0], DateFormat.is24HourFormat(context))
    }
}

@Composable
private fun SummaryCard(summary: Summary, period: StatPeriod, modifier: Modifier) {
    val format = rememberStatsFormat()
    val caption = summary.sinceDay?.let { stringResource(R.string.stats_summary_since, format.day(it, weekday = false)) }
        ?: when (period) {
            StatPeriod.`1_WEEK` -> stringResource(R.string.stats_summary_week)
            StatPeriod.`1_MONTH` -> stringResource(R.string.stats_summary_month)
            StatPeriod.`3_MONTH` -> stringResource(R.string.stats_summary_3_months)
            StatPeriod.`6_MONTH` -> stringResource(R.string.stats_summary_6_months)
            StatPeriod.`1_YEAR` -> stringResource(R.string.stats_summary_year)
            // All always begins where the log does, so this is never read.
            StatPeriod.ALL -> ""
        }
    val before = when (period) {
        StatPeriod.`1_WEEK` -> stringResource(R.string.stats_before_week)
        StatPeriod.`1_MONTH` -> stringResource(R.string.stats_before_month)
        StatPeriod.`3_MONTH` -> stringResource(R.string.stats_before_3_months)
        StatPeriod.`6_MONTH` -> stringResource(R.string.stats_before_6_months)
        StatPeriod.`1_YEAR` -> stringResource(R.string.stats_before_year)
        StatPeriod.ALL -> null
    }
    val r = summary.change
    val change = if (r == null || before == null) null else when {
        r >= 2.05 -> Icons.AutoMirrored.Rounded.TrendingUp to stringResource(R.string.stats_change_times, format.ratio(r), before)
        r >= 1.95 -> Icons.AutoMirrored.Rounded.TrendingUp to stringResource(R.string.stats_change_twice, before)
        r > 1.05 -> Icons.AutoMirrored.Rounded.TrendingUp to stringResource(R.string.stats_change_up, format.percent(r - 1), before)
        r >= 0.95 -> Icons.AutoMirrored.Rounded.TrendingFlat to stringResource(R.string.stats_change_same, before)
        else -> Icons.AutoMirrored.Rounded.TrendingDown to stringResource(R.string.stats_change_down, format.percent(1 - r), before)
    }
    val counts = stringResource(
        R.string.stats_songs_by_artists,
        pluralStringResource(R.plurals.stats_songs, summary.songs, format.count(summary.songs)),
        pluralStringResource(R.plurals.stats_artists, summary.artists, format.count(summary.artists)),
    )

    Card(
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer,
            contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
        ),
        modifier = modifier,
    ) {
        Column(Modifier.padding(20.dp)) {
            Text(
                text = format.duration(summary.playedMs),
                style = MaterialTheme.typography.displaySmall,
                fontWeight = FontWeight.Bold,
            )
            if (caption.isNotEmpty()) {
                Text(text = caption, style = MaterialTheme.typography.titleMedium)
            }
            Spacer(Modifier.height(12.dp))
            summary.perDayMs?.let {
                Text(
                    text = stringResource(R.string.stats_per_day, format.duration(it)),
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium,
                )
                Spacer(Modifier.height(4.dp))
            }
            if (change != null) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(change.first, contentDescription = null, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(text = change.second, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
                }
                Spacer(Modifier.height(4.dp))
            }
            Text(text = counts, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
private fun HoursCard(hours: HourProfile, modifier: Modifier) {
    val format = rememberStatsFormat()
    val peak = stringResource(R.string.stats_hours_peak, format.hour(hours.peakHour))
    val description = stringResource(R.string.stats_hours_chart_description, peak)
    ElevatedCard(modifier = modifier) {
        Column(Modifier.padding(16.dp)) {
            Text(
                text = stringResource(R.string.stats_hours_title),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = peak,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(16.dp))
            HourBars(
                hours = hours,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(88.dp)
                    .semantics { contentDescription = description }
            )
            Spacer(Modifier.height(6.dp))
            // Four labels, each starting where its hour's bar does: a quarter of the width is six bars.
            Row(Modifier.fillMaxWidth()) {
                for (h in listOf(0, 6, 12, 18)) {
                    Text(
                        text = format.hour(h),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Clip,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
    }
}

/** Twenty-four bars, one per local hour, the busiest in full colour. */
@Composable
private fun HourBars(hours: HourProfile, modifier: Modifier) {
    val strong = MaterialTheme.colorScheme.primary
    val soft = strong.copy(alpha = 0.3f)
    val baseline = MaterialTheme.colorScheme.outlineVariant
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    Canvas(modifier) {
        val max = hours.msByHour.max().coerceAtLeast(1L)
        val gap = 2.dp.toPx()
        val barWidth = (size.width - gap * 23) / 24
        val radius = CornerRadius(minOf(barWidth / 2, 3.dp.toPx()))
        val floor = 2.dp.toPx()
        for (h in 0 until 24) {
            val ms = hours.msByHour[h]
            if (ms <= 0) continue
            val height = maxOf(size.height * ms / max, floor)
            val x = (barWidth + gap) * (if (rtl) 23 - h else h)
            drawRoundRect(
                color = if (h == hours.peakHour) strong else soft,
                topLeft = Offset(x, size.height - height),
                size = Size(barWidth, height),
                cornerRadius = radius,
            )
        }
        drawLine(baseline, Offset(0f, size.height), Offset(size.width, size.height), strokeWidth = 1.dp.toPx())
    }
}

/** A finding in words, with what it is about beside it. */
private class InsightView(
    val text: AnnotatedString,
    val footnote: String? = null,
    val song: Song? = null,
    val artist: ArtistEntity? = null,
    val icon: ImageVector = Icons.Rounded.MusicNote,
    val sources: List<Pair<ListenSource, Double>>? = null,
)

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun InsightCard(insight: Insight, shown: PeriodInsights, actions: InsightActions, modifier: Modifier) {
    val view = insightView(insight, shown) ?: return
    val song = view.song
    val artist = view.artist
    val click: (() -> Unit)? = when {
        song != null -> { { actions.onSong(song) } }
        artist != null -> { { actions.onArtist(artist) } }
        else -> null
    }
    ElevatedCard(modifier = modifier) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .then(
                    if (click != null) Modifier.combinedClickable(
                        onClick = click,
                        onLongClick = song?.let { { actions.onSongMenu(it) } },
                    ) else Modifier
                )
                .padding(16.dp)
        ) {
            when {
                song != null -> Artwork(song.song.thumbnailUrl, RoundedCornerShape(ThumbnailCornerRadius), Icons.Rounded.MusicNote, song.id == actions.activeSongId, actions.isPlaying)
                artist != null -> Artwork(artist.thumbnailUrl, CircleShape, Icons.Rounded.Person, false, false)
                else -> Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .size(ListThumbnailSize)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.secondaryContainer)
                ) {
                    Icon(view.icon, contentDescription = null, tint = MaterialTheme.colorScheme.onSecondaryContainer)
                }
            }
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                Text(text = view.text, style = MaterialTheme.typography.bodyLarge)
                view.sources?.let { SourcesBar(it) }
                view.footnote?.let {
                    Text(
                        text = it,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 6.dp),
                    )
                }
            }
        }
    }
}

/** A picture over its placeholder, so an artist with no picture still shows something. */
@Composable
private fun Artwork(url: String?, shape: Shape, placeholder: ImageVector, active: Boolean, playing: Boolean) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .size(ListThumbnailSize)
            .clip(shape)
            .background(MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Icon(placeholder, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        ItemThumbnail(
            thumbnailUrl = url,
            isActive = active,
            isPlaying = playing,
            shape = shape,
            modifier = Modifier.size(ListThumbnailSize),
        )
    }
}

/** The split of listening by where it started: the three largest, and the rest together. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SourcesBar(shares: List<Pair<ListenSource, Double>>) {
    val format = rememberStatsFormat()
    val named = shares.filter { it.first != ListenSource.OTHER }.sortedByDescending { it.second }.take(3)
    val rest = 1.0 - named.sumOf { it.second }
    val parts = named + if (rest >= 0.005) listOf(ListenSource.OTHER to rest) else emptyList()
    val colors = listOf(
        MaterialTheme.colorScheme.primary,
        MaterialTheme.colorScheme.tertiary,
        MaterialTheme.colorScheme.secondary,
        MaterialTheme.colorScheme.outline,
    )
    Row(
        horizontalArrangement = Arrangement.spacedBy(2.dp),
        modifier = Modifier
            .padding(top = 12.dp)
            .fillMaxWidth()
            .height(8.dp)
            .clip(RoundedCornerShape(4.dp))
    ) {
        parts.forEachIndexed { i, (_, share) ->
            Box(
                Modifier
                    .weight(share.toFloat().coerceAtLeast(0.01f))
                    .fillMaxHeight()
                    .background(colors[i])
            )
        }
    }
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
        modifier = Modifier.padding(top = 8.dp),
    ) {
        parts.forEachIndexed { i, (source, share) ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier
                        .size(8.dp)
                        .clip(CircleShape)
                        .background(colors[i])
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    text = sourceLabel(source) + " " + format.percent(share),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun sourceLabel(source: ListenSource): String = when (source) {
    ListenSource.QUICK_PICKS -> stringResource(R.string.quick_picks)
    ListenSource.RADIO -> stringResource(R.string.radio)
    ListenSource.SEARCH -> stringResource(R.string.search)
    ListenSource.LIBRARY -> stringResource(R.string.library)
    ListenSource.HOME -> stringResource(R.string.home)
    ListenSource.RECOGNISED -> stringResource(R.string.stats_source_label_recognised)
    ListenSource.OTHER -> stringResource(R.string.stats_source_label_other)
}

/** How a filled-in part of a sentence is set: a name in bold, a number in the accent colour. */
private enum class Mark { PLAIN, NAME, NUMBER }

private class Part(val text: String, val mark: Mark)

private val placeholder = Regex("%(\\d+)\\$[sd]")

/**
 * [template], a string resource read without its arguments, with each placeholder filled from
 * [parts] and set as marked. Filled in by position rather than found in the finished sentence
 * afterwards, so a title that happens to contain the number, or a date that does, is never the
 * part that lights up, and a translation may put the parts in any order.
 */
private fun fill(template: String, parts: List<Part>, accent: Color): AnnotatedString = buildAnnotatedString {
    var from = 0
    for (match in placeholder.findAll(template)) {
        append(template.substring(from, match.range.first))
        val part = parts.getOrNull(match.groupValues[1].toInt() - 1)
        if (part != null) {
            when (part.mark) {
                Mark.PLAIN -> append(part.text)
                Mark.NAME -> withStyle(SpanStyle(fontWeight = FontWeight.SemiBold)) { append(part.text) }
                Mark.NUMBER -> withStyle(SpanStyle(color = accent, fontWeight = FontWeight.SemiBold)) { append(part.text) }
            }
        }
        from = match.range.last + 1
    }
    append(template.substring(from))
}

/** The words for one finding, or null when what it names is not there to show. */
@Composable
private fun insightView(insight: Insight, shown: PeriodInsights): InsightView? {
    val format = rememberStatsFormat()
    val accent = MaterialTheme.colorScheme.primary
    val song = insight.songId?.let { shown.songs[it] ?: return null }
    val artist = insight.artistId?.let { shown.artists[it] ?: return null }
    val songTitle = Part(song?.song?.title.orEmpty(), Mark.NAME)
    val artistName = Part(artist?.name.orEmpty(), Mark.NAME)
    fun number(text: String) = Part(text, Mark.NUMBER)
    fun plain(text: String) = Part(text, Mark.PLAIN)
    fun say(template: String, vararg parts: Part) = fill(template, parts.toList(), accent)
    val knownSince: (Long?) -> String? = { day -> day?.let { format.day(it, weekday = false) } }

    return when (insight) {
        is Insight.SongOfTheDay -> {
            val count = number(timesText(insight.plays, format))
            val day = plain(format.day(insight.day))
            val text = if (insight.inARow >= ListeningInsights.ON_REPEAT_MIN) {
                say(stringResource(R.string.stats_song_of_the_day_in_a_row), songTitle, count, day, number(format.count(insight.inARow)))
            } else {
                say(stringResource(R.string.stats_song_of_the_day), songTitle, count, day)
            }
            InsightView(text, song = song)
        }
        is Insight.OnRepeat -> InsightView(
            say(stringResource(R.string.stats_on_repeat), songTitle, number(timesText(insight.times, format)), plain(format.day(insight.day))),
            song = song,
        )
        is Insight.Comeback -> InsightView(
            say(
                stringResource(R.string.stats_comeback),
                number(pluralStringResource(R.plurals.stats_days, insight.daysAway, format.count(insight.daysAway))),
                songTitle,
                number(pluralStringResource(R.plurals.stats_plays, insight.playsSince, format.count(insight.playsSince))),
            ),
            song = song,
        )
        is Insight.TopSongFound -> InsightView(
            say(stringResource(R.string.stats_top_song_found), songTitle, plain(format.day(insight.firstDay)), number(timesText(insight.playsSince, format))),
            song = song,
        )
        is Insight.SongStreak -> InsightView(
            say(
                stringResource(R.string.stats_song_streak),
                songTitle,
                number(pluralStringResource(R.plurals.stats_days, insight.days, format.count(insight.days))),
                plain(format.day(insight.firstDay)),
            ),
            song = song,
        )
        is Insight.NewToYou -> {
            val share = number(format.percent(insight.share))
            val text = if (insight.newArtists > 0) {
                val artists = number(pluralStringResource(R.plurals.stats_new_artists, insight.newArtists, format.count(insight.newArtists)))
                say(stringResource(R.string.stats_new_to_you_artists), share, artists)
            } else {
                say(stringResource(R.string.stats_new_to_you), share)
            }
            InsightView(text, icon = Icons.Rounded.Explore)
        }
        is Insight.BestFind -> InsightView(
            say(
                stringResource(R.string.stats_best_find),
                artistName,
                plain(format.day(insight.firstDay)),
                number(pluralStringResource(R.plurals.stats_plays, insight.plays, format.count(insight.plays))),
            ),
            artist = artist,
        )
        is Insight.Skips -> InsightView(
            say(stringResource(R.string.stats_skips), number(format.percent(insight.skipped)), number(format.percent(insight.finished))),
            footnote = knownSince(insight.sinceDay)?.let { stringResource(R.string.stats_known_since, it) },
            icon = Icons.Rounded.SkipNext,
        )
        is Insight.LoveHate -> InsightView(
            say(stringResource(R.string.stats_love_hate), songTitle, number(timesText(insight.skips, format)), number(timesText(insight.plays, format))),
            footnote = knownSince(insight.sinceDay)?.let { stringResource(R.string.stats_known_since, it) },
            song = song,
        )
        is Insight.AlwaysFinish -> InsightView(
            say(stringResource(R.string.stats_always_finish), artistName, number(format.percent(insight.finished)), number(format.percent(insight.overall))),
            footnote = knownSince(insight.sinceDay)?.let { stringResource(R.string.stats_known_since, it) },
            artist = artist,
        )
        is Insight.WhereFrom -> {
            val (top, topShare) = insight.shares.first()
            val (words, icon) = when (top) {
                ListenSource.QUICK_PICKS -> R.string.stats_source_quick_picks to Icons.Rounded.AutoAwesome
                ListenSource.RADIO -> R.string.stats_source_radio to Icons.Rounded.Radio
                ListenSource.SEARCH -> R.string.stats_source_search to Icons.Rounded.Search
                ListenSource.LIBRARY -> R.string.stats_source_library to Icons.Rounded.LibraryMusic
                ListenSource.HOME -> R.string.stats_source_home to Icons.Rounded.Home
                ListenSource.RECOGNISED -> R.string.stats_source_recognised to Icons.Rounded.GraphicEq
                ListenSource.OTHER -> return null
            }
            InsightView(
                say(stringResource(words), number(format.percent(topShare))),
                footnote = knownSince(insight.sinceDay)?.let { stringResource(R.string.stats_known_since, it) },
                icon = icon,
                sources = insight.shares,
            )
        }
        is Insight.Weekends -> {
            val weekends = insight.ratio >= 1
            val r = if (weekends) insight.ratio else 1 / insight.ratio
            val text = when {
                r >= 2.05 -> say(stringResource(if (weekends) R.string.stats_weekends_times else R.string.stats_weekdays_times), number(format.ratio(r)))
                r >= 1.95 -> say(stringResource(if (weekends) R.string.stats_weekends_twice else R.string.stats_weekdays_twice))
                else -> say(stringResource(if (weekends) R.string.stats_weekends_more else R.string.stats_weekdays_more), number(format.percent(r - 1)))
            }
            InsightView(text, icon = Icons.Rounded.Weekend)
        }
        is Insight.BiggestDay -> InsightView(
            say(stringResource(R.string.stats_biggest_day), plain(format.day(insight.day)), number(format.duration(insight.playedMs))),
            icon = Icons.Rounded.Event,
        )
        is Insight.LongestSession -> InsightView(
            say(stringResource(R.string.stats_longest_session), number(format.duration(insight.playedMs)), plain(format.day(insight.day))),
            icon = Icons.Rounded.Headphones,
        )
        is Insight.LateNights -> InsightView(
            say(pluralStringResource(R.plurals.stats_late_nights, insight.nights), number(format.count(insight.nights)), number(format.timeOfDay(insight.latestMinute))),
            icon = Icons.Rounded.Bedtime,
        )
        is Insight.NightArtist -> InsightView(
            say(
                stringResource(R.string.stats_night_artist),
                artistName,
                number(format.percent(insight.share)),
                plain(format.hour(ListeningInsights.NIGHT_START_HOUR)),
                plain(format.hour(ListeningInsights.LATE_NIGHT_END_HOUR)),
            ),
            artist = artist,
        )
        is Insight.EveryDay -> InsightView(
            say(
                stringResource(R.string.stats_every_day),
                number(pluralStringResource(R.plurals.stats_days, insight.days, format.count(insight.days))),
                plain(format.day(insight.firstDay, weekday = false)),
                plain(format.day(insight.lastDay, weekday = false)),
            ),
            icon = Icons.Rounded.LocalFireDepartment,
        )
        is Insight.Loyal -> InsightView(
            if (insight.weeks == insight.ofWeeks) {
                say(pluralStringResource(R.plurals.stats_loyal_every, insight.ofWeeks), artistName, number(format.count(insight.ofWeeks)))
            } else {
                say(pluralStringResource(R.plurals.stats_loyal, insight.ofWeeks), artistName, number(format.count(insight.weeks)), plain(format.count(insight.ofWeeks)))
            },
            artist = artist,
        )
    }
}

/** "66 times", in the phone's digits. */
@Composable
private fun timesText(n: Int, format: StatsFormat): String = pluralStringResource(R.plurals.stats_times, n, format.count(n))
