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
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import com.dd3boh.outertune.R

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
 * into that widget's state, so two widgets side by side can be entirely different things.
 */
class MusicWidget : GlanceAppWidget() {

    /** Exact, because what fits is decided per size by [WidgetLayout] rather than by a few buckets. */
    override val sizeMode = SizeMode.Exact

    override suspend fun provideGlance(context: Context, id: GlanceId) {
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
)

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
    val text = when {
        settings.background == WidgetBackground.NONE -> GlanceTheme.colors.onSurface
        useSystem -> GlanceTheme.colors.onSurface
        solid != null && solid.luminance() > 0.5f -> ColorProvider(Color(0xFF101114))
        else -> ColorProvider(Color.White)
    }
    val muted = when {
        settings.background == WidgetBackground.NONE || useSystem -> GlanceTheme.colors.onSurfaceVariant
        solid != null && solid.luminance() > 0.5f -> ColorProvider(Color(0xFF101114).copy(alpha = 0.7f))
        else -> ColorProvider(Color.White.copy(alpha = 0.72f))
    }
    return Paint(settings, background, text, muted)
}

@Composable
private fun Body(drawn: WidgetStore.Drawn) {
    val settings = WidgetKeys.read(currentState<Preferences>() ?: androidx.datastore.preferences.core.emptyPreferences())
    val snapshot = drawn.snapshot
    val paint = paintOf(settings, snapshot)
    val size = LocalSize.current
    val widthDp = size.width.value.toInt()
    val heightDp = size.height.value.toInt()
    val shape = WidgetLayout.nowShape(widthDp, heightDp, settings.content)
    val list = snapshot.list(settings.list)
    val rows = settings.rowsAt(widthDp, heightDp, list.size)

    var frame = GlanceModifier.fillMaxSize().appWidgetBackground()
    paint.background?.let { frame = frame.background(it) }
    if (settings.rounded) frame = frame.cornerRadius(20.dp)

    if (shape == NowShape.TINY) {
        Tiny(snapshot, drawn.art[snapshot.nowPlaying?.id], heightDp, frame, paint)
        return
    }

    Column(modifier = frame.padding(horizontal = 12.dp, vertical = 10.dp)) {
        when (shape) {
            NowShape.ART -> BigArtwork(snapshot, drawn.big ?: drawn.art[snapshot.nowPlaying?.id], widthDp, heightDp, paint)
            NowShape.STACKED -> Stacked(snapshot, drawn.art[snapshot.nowPlaying?.id], widthDp, paint)
            NowShape.ROW -> NowPlayingRow(snapshot, drawn.art[snapshot.nowPlaying?.id], widthDp, heightDp, paint)
            else -> Unit
        }
        if (rows > 0) {
            if (shape != NowShape.NONE) Spacer(modifier = GlanceModifier.height(6.dp))
            if (settings.showHeading) Heading(settings.list, paint)
            list.take(rows).forEach { ListRow(it, drawn.art[it.id], paint) }
        } else if (shape == NowShape.NONE) {
            // A list widget too short for a row of it still says what it is, and opens the app.
            Heading(settings.list, paint, big = true)
        }
    }
}

@Composable
private fun Heading(which: WidgetList, paint: Paint, big: Boolean = false) {
    Text(
        text = LocalContext.current.getString(headingOf(which)),
        style = TextStyle(
            color = if (big) paint.text else paint.muted,
            fontSize = scaled(if (big) 14 else 12, paint),
            fontWeight = FontWeight.Medium,
        ),
        modifier = GlanceModifier
            .fillMaxWidth()
            .padding(bottom = 4.dp)
            .clickable(actionStartActivity(WidgetCommands.appIntent(LocalContext.current))),
    )
}

private fun headingOf(which: WidgetList): Int = when (which) {
    WidgetList.QUICK_PICKS -> R.string.quick_picks
    WidgetList.FORGOTTEN_FAVOURITES -> R.string.forgotten_favorites
    WidgetList.KEEP_LISTENING -> R.string.keep_listening
    WidgetList.RECENT -> R.string.widget_list_recent
}

private fun scaled(sp: Int, paint: Paint): TextUnit = (sp * paint.settings.textSize.scale).sp

/**
 * One cell: the cover, and play over it when there is height for a button. Everything else needs
 * words, and words need width this widget does not have.
 */
@Composable
private fun Tiny(snapshot: WidgetSnapshot, art: Bitmap?, heightDp: Int, frame: GlanceModifier, paint: Paint) {
    Box(
        modifier = frame.clickable(actionStartActivity(WidgetCommands.appIntent(LocalContext.current))),
        contentAlignment = Alignment.Center,
    ) {
        if (paint.settings.showArtwork && art != null) {
            Image(
                provider = ImageProvider(art),
                contentDescription = snapshot.nowPlaying?.title,
                contentScale = ContentScale.Crop,
                modifier = if (paint.settings.rounded) GlanceModifier.fillMaxSize().cornerRadius(20.dp) else GlanceModifier.fillMaxSize(),
            )
        } else if (paint.settings.buttons == WidgetButtons.NONE) {
            Image(
                provider = ImageProvider(R.drawable.music_note),
                contentDescription = null,
                modifier = GlanceModifier.size(36.dp),
            )
        }
        if (heightDp >= 90 && paint.settings.buttons != WidgetButtons.NONE) {
            PlayButton(snapshot, paint)
        }
    }
}

/** The whole widget given to the cover, with the song and its buttons under it. */
@Composable
private fun BigArtwork(snapshot: WidgetSnapshot, art: Bitmap?, widthDp: Int, heightDp: Int, paint: Paint) {
    val song = snapshot.nowPlaying
    if (paint.settings.showArtwork) {
        Box(
            modifier = GlanceModifier
                .fillMaxWidth()
                .height(WidgetLayout.artHeightDp(heightDp).dp)
                .clickable(actionStartActivity(WidgetCommands.appIntent(LocalContext.current))),
            contentAlignment = Alignment.Center,
        ) {
            if (art != null) {
                Image(
                    provider = ImageProvider(art),
                    contentDescription = song?.title,
                    contentScale = ContentScale.Crop,
                    modifier = GlanceModifier.fillMaxSize().cornerRadius(14.dp),
                )
            } else {
                Image(provider = ImageProvider(R.drawable.music_note), contentDescription = null, modifier = GlanceModifier.size(48.dp))
            }
        }
        Spacer(modifier = GlanceModifier.height(8.dp))
    }
    Titles(song, paint, 16)
    Spacer(modifier = GlanceModifier.height(4.dp))
    Row(modifier = GlanceModifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        Controls(snapshot, widthDp, paint)
    }
}

/** Too narrow for a line of song and buttons: the cover above, the song under it, buttons below. */
@Composable
private fun Stacked(snapshot: WidgetSnapshot, art: Bitmap?, widthDp: Int, paint: Paint) {
    val song = snapshot.nowPlaying
    if (paint.settings.showArtwork) {
        Row(modifier = GlanceModifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
            Artwork(art, 64.dp, paint)
        }
        Spacer(modifier = GlanceModifier.height(6.dp))
    }
    Titles(song, paint, 14)
    Row(modifier = GlanceModifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        Controls(snapshot, widthDp, paint)
    }
}

/** Cover, song, buttons, in a line: the shape most widths get. */
@Composable
private fun NowPlayingRow(snapshot: WidgetSnapshot, art: Bitmap?, widthDp: Int, heightDp: Int, paint: Paint) {
    val song = snapshot.nowPlaying
    Row(
        modifier = GlanceModifier.fillMaxWidth().clickable(actionStartActivity(WidgetCommands.appIntent(LocalContext.current))),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (paint.settings.showArtwork && WidgetLayout.showsArtwork(heightDp)) {
            Artwork(art, 56.dp, paint)
            Spacer(modifier = GlanceModifier.width(10.dp))
        }
        Column(modifier = GlanceModifier.defaultWeight()) {
            Titles(song, paint, 15)
        }
        Spacer(modifier = GlanceModifier.width(6.dp))
        Controls(snapshot, widthDp, paint)
    }
}

@Composable
private fun Titles(song: WidgetSong?, paint: Paint, titleSp: Int) {
    Text(
        text = song?.title ?: LocalContext.current.getString(R.string.widget_nothing_playing),
        style = TextStyle(color = paint.text, fontSize = scaled(titleSp, paint), fontWeight = FontWeight.Medium),
        maxLines = 1,
    )
    if (song != null && paint.settings.showArtist) {
        Text(
            text = song.artist,
            style = TextStyle(color = paint.muted, fontSize = scaled(13, paint)),
            maxLines = 1,
        )
    }
}

@Composable
private fun Controls(snapshot: WidgetSnapshot, widthDp: Int, paint: Paint) {
    val buttons = paint.settings.buttons
    if (buttons == WidgetButtons.NONE) return
    val skips = buttons == WidgetButtons.ALL && WidgetLayout.showsSkipButtons(widthDp)
    if (skips) ControlButton(R.drawable.skip_previous, R.string.widget_previous, actionRunCallback<PreviousAction>(), paint)
    PlayButton(snapshot, paint)
    if (skips) ControlButton(R.drawable.skip_next, R.string.widget_next, actionRunCallback<NextAction>(), paint)
}

@Composable
private fun PlayButton(snapshot: WidgetSnapshot, paint: Paint) {
    ControlButton(
        if (snapshot.isPlaying) R.drawable.pause else R.drawable.play,
        if (snapshot.isPlaying) R.string.widget_pause else R.string.widget_play,
        actionRunCallback<PlayPauseAction>(),
        paint,
    )
}

@Composable
private fun ListRow(song: WidgetSong, art: Bitmap?, paint: Paint) {
    Row(
        modifier = GlanceModifier
            .fillMaxWidth()
            .height(WidgetLayout.PICK_ROW_DP.dp)
            .clickable(actionStartActivity(WidgetCommands.playIntent(LocalContext.current, song))),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (paint.settings.showArtwork) {
            Artwork(art, 40.dp, paint)
            Spacer(modifier = GlanceModifier.width(10.dp))
        }
        Column(modifier = GlanceModifier.defaultWeight()) {
            Text(
                text = song.title,
                style = TextStyle(color = paint.text, fontSize = scaled(14, paint)),
                maxLines = 1,
            )
            if (paint.settings.showArtist) {
                Text(
                    text = song.artist,
                    style = TextStyle(color = paint.muted, fontSize = scaled(12, paint)),
                    maxLines = 1,
                )
            }
        }
    }
}

@Composable
private fun Artwork(art: Bitmap?, size: Dp, paint: Paint) {
    val provider = if (art != null) ImageProvider(art) else ImageProvider(R.drawable.music_note)
    var modifier = GlanceModifier.size(size)
    if (paint.settings.rounded) modifier = modifier.cornerRadius(8.dp)
    Image(
        provider = provider,
        contentDescription = null,
        contentScale = ContentScale.Crop,
        modifier = modifier,
    )
}

@Composable
private fun ControlButton(icon: Int, description: Int, action: Action, paint: Paint) {
    CircleIconButton(
        imageProvider = ImageProvider(icon),
        contentDescription = LocalContext.current.getString(description),
        onClick = action,
        backgroundColor = null,
        contentColor = paint.text,
        modifier = GlanceModifier.size(44.dp),
    )
}
