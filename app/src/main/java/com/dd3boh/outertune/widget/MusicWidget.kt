/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.widget

import android.content.Context
import android.graphics.Bitmap
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.LocalContext
import androidx.glance.LocalSize
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
import androidx.glance.layout.Alignment
import androidx.glance.layout.Column
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
import androidx.glance.action.Action
import com.dd3boh.outertune.R

/**
 * InterTune on the home screen: what is playing, its controls, and as much of Quick picks as the
 * space allows.
 *
 * It draws from [WidgetStore]'s snapshot and never from the app, because the launcher asks for this
 * at moments the app has no say in, including with the process long dead. Everything it shows was
 * therefore true the last time the app had something to say, and the app says something whenever
 * the song, the playback state or the Quick picks row changes.
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
            val shown = drawn ?: initial
            GlanceTheme {
                Body(shown.snapshot, shown.art)
            }
        }
    }
}

@Composable
private fun Body(snapshot: WidgetSnapshot, art: Map<String, Bitmap>) {
    val size = LocalSize.current
    val widthDp = size.width.value.toInt()
    val heightDp = size.height.value.toInt()
    Column(
        modifier = GlanceModifier
            .fillMaxSize()
            .appWidgetBackground()
            .background(GlanceTheme.colors.widgetBackground)
            .cornerRadius(20.dp)
            .padding(horizontal = 12.dp, vertical = 10.dp)
    ) {
        NowPlaying(snapshot, art[snapshot.nowPlaying?.id], widthDp, heightDp)
        val picks = WidgetLayout.pickCount(heightDp, snapshot.picks.size)
        if (picks > 0) {
            Spacer(modifier = GlanceModifier.height(6.dp))
            Text(
                text = LocalContext.current.getString(R.string.quick_picks),
                style = TextStyle(color = GlanceTheme.colors.onSurfaceVariant, fontSize = 12.sp, fontWeight = FontWeight.Medium),
                modifier = GlanceModifier.padding(bottom = 4.dp)
            )
            snapshot.picks.take(picks).forEach { PickRow(it, art[it.id]) }
        }
    }
}

@Composable
private fun NowPlaying(snapshot: WidgetSnapshot, art: Bitmap?, widthDp: Int, heightDp: Int) {
    val song = snapshot.nowPlaying
    Row(
        modifier = GlanceModifier.fillMaxWidth().clickable(actionStartActivity(WidgetCommands.appIntent(LocalContext.current))),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (WidgetLayout.showsArtwork(heightDp)) {
            Artwork(art, 56.dp)
            Spacer(modifier = GlanceModifier.width(10.dp))
        }
        Column(modifier = GlanceModifier.defaultWeight()) {
            Text(
                text = song?.title ?: LocalContext.current.getString(R.string.widget_nothing_playing),
                style = TextStyle(color = GlanceTheme.colors.onSurface, fontSize = 15.sp, fontWeight = FontWeight.Medium),
                maxLines = 1,
            )
            if (song != null) {
                Text(
                    text = song.artist,
                    style = TextStyle(color = GlanceTheme.colors.onSurfaceVariant, fontSize = 13.sp),
                    maxLines = 1,
                )
            }
        }
        Spacer(modifier = GlanceModifier.width(6.dp))
        if (WidgetLayout.showsSkipButtons(widthDp)) {
            ControlButton(R.drawable.skip_previous, R.string.widget_previous, actionRunCallback<PreviousAction>())
        }
        ControlButton(
            if (snapshot.isPlaying) R.drawable.pause else R.drawable.play,
            if (snapshot.isPlaying) R.string.widget_pause else R.string.widget_play,
            actionRunCallback<PlayPauseAction>(),
        )
        if (WidgetLayout.showsSkipButtons(widthDp)) {
            ControlButton(R.drawable.skip_next, R.string.widget_next, actionRunCallback<NextAction>())
        }
    }
}

@Composable
private fun PickRow(song: WidgetSong, art: Bitmap?) {
    Row(
        modifier = GlanceModifier
            .fillMaxWidth()
            .height(WidgetLayout.PICK_ROW_DP.dp)
            .clickable(actionStartActivity(WidgetCommands.playIntent(LocalContext.current, song))),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Artwork(art, 40.dp)
        Spacer(modifier = GlanceModifier.width(10.dp))
        Column(modifier = GlanceModifier.defaultWeight()) {
            Text(
                text = song.title,
                style = TextStyle(color = GlanceTheme.colors.onSurface, fontSize = 14.sp),
                maxLines = 1,
            )
            Text(
                text = song.artist,
                style = TextStyle(color = GlanceTheme.colors.onSurfaceVariant, fontSize = 12.sp),
                maxLines = 1,
            )
        }
    }
}

@Composable
private fun Artwork(art: Bitmap?, size: androidx.compose.ui.unit.Dp) {
    val provider = if (art != null) ImageProvider(art) else ImageProvider(R.drawable.music_note)
    Image(
        provider = provider,
        contentDescription = null,
        modifier = GlanceModifier.size(size).cornerRadius(8.dp),
    )
}

@Composable
private fun ControlButton(icon: Int, description: Int, action: Action) {
    CircleIconButton(
        imageProvider = ImageProvider(icon),
        contentDescription = LocalContext.current.getString(description),
        onClick = action,
        backgroundColor = null,
        contentColor = GlanceTheme.colors.onSurface,
        modifier = GlanceModifier.size(44.dp),
    )
}
