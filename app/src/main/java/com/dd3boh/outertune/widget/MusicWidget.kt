/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.widget

import android.content.Context
import android.content.res.Configuration
import android.graphics.Bitmap
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import androidx.glance.ColorFilter
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.LocalContext
import androidx.glance.LocalSize
import androidx.glance.action.Action
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.action.actionRunCallback
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.appwidget.appWidgetBackground
import androidx.glance.appwidget.components.CircleIconButton
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.provideContent
import androidx.glance.background
import androidx.glance.currentState
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.ContentScale
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.size
import androidx.glance.layout.width
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextAlign
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import com.dd3boh.outertune.R
import com.dd3boh.outertune.widget.WidgetLayout.BUTTON_SPACING_DP
import com.dd3boh.outertune.widget.WidgetLayout.COVER_GAP_DP
import com.dd3boh.outertune.widget.WidgetLayout.LIST_COVER_DP
import com.dd3boh.outertune.widget.WidgetLayout.PAD_H_DP
import com.dd3boh.outertune.widget.WidgetLayout.PAD_V_DP
import com.dd3boh.outertune.widget.WidgetLayout.SMALL_GAP_DP

/**
 * InterTune on the home screen: what is playing, a list to start something from, or both, in
 * whatever shape the size allows and whatever colours it was asked for.
 *
 * It draws from [WidgetStore]'s snapshot and never from the app, because the launcher asks for this
 * at moments the app has no say in, including with the process long dead. Everything it shows was
 * therefore true the last time the app had something to say, and the app says something whenever
 * the song, the playback state or one of Home's rows changes.
 *
 * What each widget holds and how it looks is its own: [WidgetConfigActivity] writes the settings
 * into that widget's state, so two widgets side by side can be entirely different things. What it
 * draws at a given size is [WidgetLayout.plan]'s to decide, down to the dp, and nothing here
 * measures or guesses.
 */
class MusicWidget(
    /** What to draw instead of the snapshot on disk: a preview inside the app, never a widget on a home screen. */
    private val fixed: WidgetStore.Drawn? = null,
) : GlanceAppWidget() {

    /** Exact, because what fits is decided per size by [WidgetLayout] rather than by a few buckets. */
    override val sizeMode = SizeMode.Exact

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        // provideContent never returns, so a preview stops here.
        if (fixed != null) provideContent { GlanceTheme { Body(fixed) } }
        val initial = WidgetStore.load(context)
        provideContent {
            // Collected rather than captured: provideGlance runs once for the life of this
            // widget's session, so anything read here and passed down would be frozen at the song
            // that was playing when the launcher first asked.
            val drawn by WidgetStore.drawn.collectAsState(initial)
            GlanceTheme {
                Body(drawn ?: initial)
            }
        }
    }
}

/** The colours one widget draws in, worked out once from its settings and the phone's theme. */
private data class Paint(
    val settings: WidgetSettings,
    val background: ColorProvider?,
    val text: ColorProvider,
    val muted: ColorProvider,
    /**
     * The play button is the one filled button, so it is found without looking: in the theme's
     * accent where the launcher's colours are in use, otherwise in the words' colour with the
     * background's own as its icon.
     */
    val playFill: ColorProvider,
    val playIcon: ColorProvider,
    /** Behind the note that stands in for a cover not fetched yet. */
    val placeholder: ColorProvider,
)

/** Words and buttons over a cover are white whatever the widget's colours: the scrim under them is always dark. */
private val OnCover = ColorProvider(Color.White)
private val OnCoverMuted = ColorProvider(Color.White.copy(alpha = 0.78f))
private val OnCoverIcon = ColorProvider(Color(0xFF101114))

/** Play over a cover with nothing under it to darken it. */
private val OverCoverFill = ColorProvider(Color.Black.copy(alpha = 0.45f))

private val CornerRadius = 20.dp

@Composable
private fun paintOf(settings: WidgetSettings, snapshot: WidgetSnapshot): Paint {
    val night = (LocalContext.current.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
            Configuration.UI_MODE_NIGHT_YES
    val alpha = settings.opacity.coerceIn(0, 100) / 100f
    val solid: Color? = when (settings.background) {
        WidgetBackground.SYSTEM -> if (night) Color(0xFF17181C) else Color(0xFFF6F6FA)
        WidgetBackground.DARK -> Color(0xFF101114)
        WidgetBackground.LIGHT -> Color(0xFFFBFBFE)
        WidgetBackground.ARTWORK -> snapshot.nowPlaying?.colour?.let { Color(it) }
            ?: if (night) Color(0xFF17181C) else Color(0xFFF6F6FA)
        WidgetBackground.NONE -> null
    }
    // The launcher's own widget background is the nicest of the lot: it follows the phone's theme
    // and its wallpaper tint. It is used whenever nothing was asked for that it cannot give.
    val useSystem = settings.background == WidgetBackground.SYSTEM && alpha >= 1f
    val background = when {
        solid == null -> null
        useSystem -> GlanceTheme.colors.widgetBackground
        else -> ColorProvider(solid.copy(alpha = alpha))
    }
    val themed = settings.background == WidgetBackground.NONE || useSystem
    val light = solid != null && solid.luminance() > 0.5f
    val ink = Color(0xFF101114)
    val text = when {
        themed -> GlanceTheme.colors.onSurface
        light -> ColorProvider(ink)
        else -> ColorProvider(Color.White)
    }
    val muted = when {
        themed -> GlanceTheme.colors.onSurfaceVariant
        light -> ColorProvider(ink.copy(alpha = 0.7f))
        else -> ColorProvider(Color.White.copy(alpha = 0.72f))
    }
    val (playFill, playIcon) = when {
        themed || solid == null -> GlanceTheme.colors.primary to GlanceTheme.colors.onPrimary
        else -> text to ColorProvider(solid)
    }
    val placeholder = when {
        themed || solid == null -> GlanceTheme.colors.secondaryContainer
        light -> ColorProvider(ink.copy(alpha = 0.08f))
        else -> ColorProvider(Color.White.copy(alpha = 0.12f))
    }
    return Paint(settings, background, text, muted, playFill, playIcon, placeholder)
}

@Composable
private fun Body(drawn: WidgetStore.Drawn) {
    val context = LocalContext.current
    val settings = WidgetKeys.read(currentState<Preferences>() ?: emptyPreferences())
    val snapshot = drawn.snapshot
    val paint = paintOf(settings, snapshot)
    val size = LocalSize.current
    val list = snapshot.list(settings.list)
    val plan = WidgetLayout.plan(
        size.width.value.toInt(), size.height.value.toInt(), settings, list.size,
        context.resources.configuration.fontScale,
    )
    val song = snapshot.nowPlaying
    val cover = drawn.art[song?.id]

    var frame = GlanceModifier.fillMaxSize().appWidgetBackground()
    paint.background?.let { frame = frame.background(it) }
    if (settings.rounded) frame = frame.cornerRadius(CornerRadius)

    when (plan.shape) {
        NowShape.TINY -> Tiny(plan, snapshot, cover, frame, paint)
        // The big cover where it is drawn big; the thumbnail stands in until it has been fetched.
        NowShape.POSTER -> Poster(plan, snapshot, drawn.big ?: cover, frame, paint)
        NowShape.CARD -> Card(plan, snapshot, drawn.big ?: cover, frame, paint)
        else -> Column(
            modifier = frame.padding(
                horizontal = PAD_H_DP.dp,
                vertical = (if (plan.shape == NowShape.SLIM) 4 else PAD_V_DP).dp,
            ),
            verticalAlignment = if (plan.centred) Alignment.CenterVertically else Alignment.Top,
        ) {
            when (plan.shape) {
                NowShape.SLIM, NowShape.ROW -> SongLine(plan, snapshot, cover, paint)
                NowShape.STACKED -> Stacked(plan, snapshot, cover, paint)
                else -> Unit
            }
            if (plan.rows > 0) {
                if (plan.gap > 0) Spacer(modifier = GlanceModifier.height(plan.gap.dp))
                if (plan.heading) Heading(settings.list, paint, plan.headingHeight)
                list.take(plan.rows).forEach { ListRow(it, drawn.art[it.id], paint, plan) }
            } else if (plan.shape == NowShape.NONE) {
                // A list widget too short for a row of it still says what it is, and opens the app.
                BigHeading(settings.list, paint)
            }
        }
    }
}

@Composable
private fun openApp(): Action = actionStartActivity(WidgetCommands.appIntent(LocalContext.current))

@Composable
private fun Heading(which: WidgetList, paint: Paint, height: Int) {
    Text(
        text = LocalContext.current.getString(headingOf(which)),
        style = TextStyle(color = paint.muted, fontSize = scaled(12, paint), fontWeight = FontWeight.Medium),
        maxLines = 1,
        modifier = GlanceModifier.fillMaxWidth().height(height.dp).clickable(openApp()),
    )
}

@Composable
private fun BigHeading(which: WidgetList, paint: Paint) {
    Box(modifier = GlanceModifier.fillMaxSize().clickable(openApp()), contentAlignment = Alignment.CenterStart) {
        Text(
            text = LocalContext.current.getString(headingOf(which)),
            style = TextStyle(color = paint.text, fontSize = scaled(14, paint), fontWeight = FontWeight.Medium),
            maxLines = 1,
        )
    }
}

private fun headingOf(which: WidgetList): Int = when (which) {
    WidgetList.QUICK_PICKS -> R.string.quick_picks
    WidgetList.FORGOTTEN_FAVOURITES -> R.string.forgotten_favorites
    WidgetList.KEEP_LISTENING -> R.string.keep_listening
    WidgetList.RECENT -> R.string.widget_list_recent
}

private fun scaled(sp: Int, paint: Paint): TextUnit = (sp * paint.settings.textSize.scale).sp

/**
 * Narrower than a title. On a cell, the cover is the tile and play sits over it; on a narrow, tall
 * widget the cover keeps its shape and play goes under it, rather than the cover being stretched
 * into a strip.
 */
@Composable
private fun Tiny(plan: WidgetPlan, snapshot: WidgetSnapshot, art: Bitmap?, frame: GlanceModifier, paint: Paint) {
    val title = snapshot.nowPlaying?.title
    if (plan.buttonsBelow) {
        Column(
            modifier = frame.clickable(openApp()),
            verticalAlignment = Alignment.CenterVertically,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            if (plan.cover > 0) {
                Artwork(art, plan.cover.dp, paint, title)
                Spacer(modifier = GlanceModifier.height(8.dp))
            }
            PlayButton(snapshot, plan.button, paint.playFill, paint.playIcon)
        }
        return
    }
    val onCover = plan.cover > 0 && art != null
    Box(modifier = frame.clickable(openApp()), contentAlignment = Alignment.Center) {
        if (onCover) {
            Image(
                provider = ImageProvider(art!!),
                contentDescription = title,
                contentScale = ContentScale.Crop,
                modifier = GlanceModifier.fillMaxSize(),
            )
        } else if (!plan.play) {
            Note(36.dp, paint.muted)
        }
        if (plan.play) {
            if (onCover) PlayButton(snapshot, plan.button, OverCoverFill, OnCover)
            else PlayButton(snapshot, plan.button, paint.playFill, paint.playIcon)
        }
    }
}

/** The cover as the whole widget, darkened towards the bottom, with the song and its buttons over it. */
@Composable
private fun Poster(plan: WidgetPlan, snapshot: WidgetSnapshot, art: Bitmap?, frame: GlanceModifier, paint: Paint) {
    val song = snapshot.nowPlaying
    // With no cover to put them on, the words are the widget's own colours on its own background.
    val onCover = art != null
    val text = if (onCover) OnCover else paint.text
    val muted = if (onCover) OnCoverMuted else paint.muted
    val fill = if (onCover) OnCover else paint.playFill
    val icon = if (onCover) OnCoverIcon else paint.playIcon
    Box(modifier = frame.clickable(openApp())) {
        if (art != null) {
            Image(
                provider = ImageProvider(art),
                contentDescription = song?.title,
                contentScale = ContentScale.Crop,
                modifier = GlanceModifier.fillMaxSize(),
            )
            Image(
                provider = ImageProvider(R.drawable.widget_scrim),
                contentDescription = null,
                contentScale = ContentScale.FillBounds,
                modifier = GlanceModifier.fillMaxSize(),
            )
        } else {
            Box(modifier = GlanceModifier.fillMaxSize().padding(bottom = 48.dp), contentAlignment = Alignment.Center) {
                Note(48.dp, paint.muted)
            }
        }
        Column(
            modifier = GlanceModifier.fillMaxSize().padding(PAD_H_DP.dp),
            verticalAlignment = Alignment.Bottom,
        ) {
            if (plan.buttonsBelow) {
                Titles(song, paint, 16, 13, plan.showArtist, text, muted)
                Spacer(modifier = GlanceModifier.height(4.dp))
                Row(
                    modifier = GlanceModifier.fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Controls(plan, snapshot, fill, icon, text)
                }
            } else {
                Row(modifier = GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(modifier = GlanceModifier.defaultWeight()) {
                        if (plan.titleLines > 0) Titles(song, paint, 16, 13, plan.showArtist, text, muted)
                    }
                    if (plan.play) {
                        Spacer(modifier = GlanceModifier.width(8.dp))
                        PlayButton(snapshot, plan.button, fill, icon)
                    }
                }
            }
        }
    }
}

/** Wide and tall: the cover as tall as the widget, the song beside it and the buttons under the song. */
@Composable
private fun Card(plan: WidgetPlan, snapshot: WidgetSnapshot, art: Bitmap?, frame: GlanceModifier, paint: Paint) {
    val song = snapshot.nowPlaying
    Row(
        modifier = frame.padding(PAD_H_DP.dp).clickable(openApp()),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Artwork(art, plan.cover.dp, paint, song?.title, radius = 16.dp)
        Spacer(modifier = GlanceModifier.width(14.dp))
        Column(modifier = GlanceModifier.defaultWeight().height(plan.cover.dp)) {
            Titles(song, paint, 16, 13, plan.showArtist, titleLines = plan.titleLines)
            Spacer(modifier = GlanceModifier.defaultWeight())
            if (plan.play) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Controls(plan, snapshot, paint.playFill, paint.playIcon, paint.text)
                }
            }
        }
    }
}

/** Cover, song and buttons in a line: a single cell tall, or a line with a list under it. */
@Composable
private fun SongLine(plan: WidgetPlan, snapshot: WidgetSnapshot, art: Bitmap?, paint: Paint) {
    val slim = plan.shape == NowShape.SLIM
    val song = snapshot.nowPlaying
    Row(
        modifier = GlanceModifier.fillMaxWidth().height(plan.nowHeight.dp).clickable(openApp()),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (plan.cover > 0) {
            Artwork(art, plan.cover.dp, paint, song?.title, radius = if (slim) 6.dp else 10.dp)
            Spacer(modifier = GlanceModifier.width(COVER_GAP_DP.dp))
        }
        Column(modifier = GlanceModifier.defaultWeight()) {
            Titles(song, paint, if (slim) 14 else 15, if (slim) 12 else 13, plan.showArtist)
        }
        if (plan.play) {
            Spacer(modifier = GlanceModifier.width(SMALL_GAP_DP.dp))
            Controls(plan, snapshot, paint.playFill, paint.playIcon, paint.text)
        }
    }
}

/** Narrow: the cover above the song, the buttons under it. */
@Composable
private fun Stacked(plan: WidgetPlan, snapshot: WidgetSnapshot, art: Bitmap?, paint: Paint) {
    val song = snapshot.nowPlaying
    Column(
        modifier = GlanceModifier.fillMaxWidth().height(plan.nowHeight.dp).clickable(openApp()),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        if (plan.cover > 0) {
            Artwork(art, plan.cover.dp, paint, song?.title, radius = 12.dp)
            Spacer(modifier = GlanceModifier.height(SMALL_GAP_DP.dp))
        }
        Titles(song, paint, 14, 12, plan.showArtist, centred = true)
        if (plan.play) {
            Spacer(modifier = GlanceModifier.height(2.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Controls(plan, snapshot, paint.playFill, paint.playIcon, paint.text)
            }
        }
    }
}

@Composable
private fun Titles(
    song: WidgetSong?,
    paint: Paint,
    titleSp: Int,
    artistSp: Int,
    showArtist: Boolean,
    text: ColorProvider = paint.text,
    muted: ColorProvider = paint.muted,
    titleLines: Int = 1,
    centred: Boolean = false,
) {
    val align = if (centred) TextAlign.Center else TextAlign.Start
    val width = if (centred) GlanceModifier.fillMaxWidth() else GlanceModifier
    Text(
        text = song?.title ?: LocalContext.current.getString(R.string.widget_nothing_playing),
        style = TextStyle(color = text, fontSize = scaled(titleSp, paint), fontWeight = FontWeight.Medium, textAlign = align),
        maxLines = titleLines.coerceAtLeast(1),
        modifier = width,
    )
    if (song != null && showArtist) {
        Text(
            text = song.artist,
            style = TextStyle(color = muted, fontSize = scaled(artistSp, paint), textAlign = align),
            maxLines = 1,
            modifier = width,
        )
    }
}

/** Previous, play and next as the plan has room for, play the filled one among them. */
@Composable
private fun Controls(plan: WidgetPlan, snapshot: WidgetSnapshot, fill: ColorProvider, icon: ColorProvider, tint: ColorProvider) {
    if (plan.skips) {
        TransportButton(R.drawable.skip_previous, R.string.widget_previous, actionRunCallback<PreviousAction>(), plan.button, tint)
        Spacer(modifier = GlanceModifier.width(BUTTON_SPACING_DP.dp))
    }
    if (plan.play) PlayButton(snapshot, plan.button, fill, icon)
    if (plan.skips) {
        Spacer(modifier = GlanceModifier.width(BUTTON_SPACING_DP.dp))
        TransportButton(R.drawable.skip_next, R.string.widget_next, actionRunCallback<NextAction>(), plan.button, tint)
    }
}

@Composable
private fun PlayButton(snapshot: WidgetSnapshot, size: Int, fill: ColorProvider, icon: ColorProvider) {
    CircleIconButton(
        imageProvider = ImageProvider(if (snapshot.isPlaying) R.drawable.pause else R.drawable.play),
        contentDescription = LocalContext.current.getString(if (snapshot.isPlaying) R.string.widget_pause else R.string.widget_play),
        onClick = actionRunCallback<PlayPauseAction>(),
        backgroundColor = fill,
        contentColor = icon,
        modifier = GlanceModifier.size(size.dp),
    )
}

@Composable
private fun TransportButton(icon: Int, description: Int, action: Action, size: Int, tint: ColorProvider) {
    CircleIconButton(
        imageProvider = ImageProvider(icon),
        contentDescription = LocalContext.current.getString(description),
        onClick = action,
        backgroundColor = null,
        contentColor = tint,
        modifier = GlanceModifier.size(size.dp),
    )
}

@Composable
private fun ListRow(song: WidgetSong, art: Bitmap?, paint: Paint, plan: WidgetPlan) {
    Row(
        modifier = GlanceModifier
            .fillMaxWidth()
            .height(plan.rowHeight.dp)
            .clickable(actionStartActivity(WidgetCommands.playIntent(LocalContext.current, song))),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (plan.rowCover) {
            Artwork(art, LIST_COVER_DP.dp, paint, null)
            Spacer(modifier = GlanceModifier.width(COVER_GAP_DP.dp))
        }
        Column(modifier = GlanceModifier.defaultWeight()) {
            Text(
                text = song.title,
                style = TextStyle(color = paint.text, fontSize = scaled(14, paint)),
                maxLines = 1,
            )
            if (plan.rowShowsArtist) {
                Text(
                    text = song.artist,
                    style = TextStyle(color = paint.muted, fontSize = scaled(12, paint)),
                    maxLines = 1,
                )
            }
        }
    }
}

/** A cover, or while there is none yet a note on a quiet square of the same size, so nothing jumps when it lands. */
@Composable
private fun Artwork(art: Bitmap?, size: Dp, paint: Paint, description: String?, radius: Dp = 8.dp) {
    var modifier = GlanceModifier.size(size)
    if (paint.settings.rounded) modifier = modifier.cornerRadius(radius)
    if (art != null) {
        Image(
            provider = ImageProvider(art),
            contentDescription = description,
            contentScale = ContentScale.Crop,
            modifier = modifier,
        )
    } else {
        Box(modifier = modifier.background(paint.placeholder), contentAlignment = Alignment.Center) {
            Note(size * 0.45f, paint.muted)
        }
    }
}

@Composable
private fun Note(size: Dp, tint: ColorProvider) {
    Image(
        provider = ImageProvider(R.drawable.music_note),
        contentDescription = null,
        colorFilter = ColorFilter.tint(tint),
        modifier = GlanceModifier.size(size),
    )
}
