/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.widget

import android.content.Context
import android.content.res.Configuration
import androidx.annotation.DrawableRes
import android.graphics.Bitmap
import android.view.View
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
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
import androidx.glance.action.actionParametersOf
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
import androidx.glance.layout.absolutePadding
import androidx.glance.layout.fillMaxHeight
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.size
import androidx.glance.layout.width
import androidx.glance.semantics.contentDescription
import androidx.glance.semantics.semantics
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import com.dd3boh.outertune.R
import com.dd3boh.outertune.constants.Unreleased
import com.dd3boh.outertune.widget.WidgetLayout.COVER_GAP_DP
import com.dd3boh.outertune.widget.WidgetLayout.PANE_GAP_DP
import com.dd3boh.outertune.widget.WidgetLayout.SIDE_GAP_DP
import com.dd3boh.outertune.widget.WidgetLayout.SMALL_GAP_DP
import com.dd3boh.outertune.widget.WidgetLayout.UP_NEXT_COVER_DP
import kotlin.math.roundToInt

/**
 * InterTune on the home screen: what is playing, a shelf of songs to start, or both, in one of
 * three styles (Cover, Full cover, Shelf) and in whatever shape the size allows.
 *
 * It draws from [WidgetStore]'s snapshot and never from the app, because the launcher asks for this
 * at moments the app has no say in, including with the process long dead. Everything it shows was
 * therefore true the last time the app had something to say, and the app says something whenever
 * the song, the playback state, the queue or one of Home's rows changes.
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
     * background's own as its icon (white on Cover's deep colour, as in the mockup).
     */
    val playFill: ColorProvider,
    val playIcon: ColorProvider,
    /** Behind the note that stands in for a cover not fetched yet. */
    val placeholder: ColorProvider,
    /** Up next's line between the panes. */
    val divider: ColorProvider,
    /** Under a Shelf's pill of buttons where it sits on the background: light glass on a dark one, ink on a light one, either as the theme goes. */
    @param:DrawableRes val glass: Int = R.drawable.widget_glass_plain,
)

/** Words and buttons over a cover are white whatever the widget's colours: the scrim under them is always dark. */
private val OnCover = ColorProvider(Color.White)
private val OnCoverMuted = ColorProvider(Color.White.copy(alpha = 0.78f))

private val CornerRadius = 22.dp

/** Under this a filled play button is drawn with a glyph to its size rather than Glance's 24 dp one. */
private const val SMALL_PLAY_DP = 44

@Composable
private fun paintOf(settings: WidgetSettings, snapshot: WidgetSnapshot): Paint {
    val colour = snapshot.nowPlaying?.colour
    if (settings.style == WidgetStyle.FULL_COVER) {
        // On its cover, or with none yet on the cover's own deep colour: white words either way.
        return Paint(
            settings,
            background = ColorProvider(Color(WidgetColours.deep(colour))),
            text = OnCover,
            muted = OnCoverMuted,
            playFill = OnCover,
            playIcon = ColorProvider(Color(WidgetColours.deep(colour))),
            placeholder = ColorProvider(Color.White.copy(alpha = 0.12f)),
            divider = ColorProvider(Color.White.copy(alpha = 0.15f)),
        )
    }
    val night = (LocalContext.current.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
            Configuration.UI_MODE_NIGHT_YES
    val alpha = settings.opacity.coerceIn(0, 100) / 100f
    val chosen = settings.backgroundShown
    val solid: Color? = when (chosen) {
        WidgetBackground.SYSTEM -> if (night) Color(0xFF17181C) else Color(0xFFF6F6FA)
        WidgetBackground.DARK -> Color(0xFF101114)
        WidgetBackground.LIGHT -> Color(0xFFFBFBFE)
        WidgetBackground.ARTWORK -> Color(WidgetColours.deep(colour))
        WidgetBackground.NONE -> null
    }
    // The launcher's own widget background is the nicest of the lot: it follows the phone's theme
    // and its wallpaper tint. It is used whenever nothing was asked for that it cannot give.
    val useSystem = chosen == WidgetBackground.SYSTEM && alpha >= 1f
    val background = when {
        solid == null -> null
        useSystem -> GlanceTheme.colors.widgetBackground
        else -> ColorProvider(solid.copy(alpha = alpha))
    }
    val themed = chosen == WidgetBackground.NONE || useSystem
    // Dark words from where they read better than white ones, which is a luminance of 0.18 and
    // not a half: on a mid grey white words were at two to one.
    val light = solid != null && solid.luminance() > (if (Unreleased.COVER_ACCENT) 0.18f else 0.5f)
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
    val divider = when {
        themed || solid == null -> GlanceTheme.colors.surfaceVariant
        light -> ColorProvider(ink.copy(alpha = 0.12f))
        else -> ColorProvider(Color.White.copy(alpha = 0.15f))
    }
    val glass = when {
        themed || solid == null -> R.drawable.widget_glass_theme
        light -> R.drawable.widget_glass_ink
        else -> R.drawable.widget_glass_plain
    }
    return Paint(settings, background, text, muted, playFill, playIcon, placeholder, divider, glass)
}

/** Everything a shape is drawn from. */
private class Look(
    val plan: WidgetPlan,
    val snapshot: WidgetSnapshot,
    val paint: Paint,
    /** The now playing cover at the size it is drawn: the big file where it is drawn big. */
    val cover: Bitmap?,
    val art: Map<String, Bitmap>,
    val w: Int,
    val h: Int,
    val rtl: Boolean,
    /** The lists' first songs' big covers, where the phone has them: Big cover with a list alone draws that song big. */
    val firsts: Map<String, Bitmap> = emptyMap(),
) {
    val song: WidgetSong? get() = snapshot.nowPlaying
}

@Composable
private fun Body(drawn: WidgetStore.Drawn) {
    val context = LocalContext.current
    val settings = WidgetKeys.read(currentState<Preferences>() ?: emptyPreferences())
    val snapshot = drawn.snapshot
    val size = LocalSize.current
    val w = size.width.value.toInt()
    val h = size.height.value.toInt()
    val list = snapshot.list(settings.list)
    val plan = WidgetLayout.plan(w, h, settings, list.size, context.resources.configuration.fontScale, snapshot.upNext.size)
    val paint = paintOf(settings, snapshot)
    // The now playing song's own file, not the list entry the id-keyed art map would otherwise
    // resolve to whenever this same song sits at the head of Recently played; the big one only where
    // the cover is drawn big, since sending it everywhere spends the launcher's room for nothing.
    val small = drawn.nowCover ?: drawn.art[snapshot.nowPlaying?.id]
    val cover = if (WidgetArt.wantsBig(plan)) drawn.big ?: small else small
    val rtl = context.resources.configuration.layoutDirection == View.LAYOUT_DIRECTION_RTL
    val look = Look(plan, snapshot, paint, cover, drawn.art, w, h, rtl, drawn.firsts)

    var frame = GlanceModifier.fillMaxSize().appWidgetBackground()
    // Full cover's face is its cover; with none to show, the cover's colour stands in. A Shelf's
    // looks are drawn on their cover blurred, when that is the background they have.
    val onCover = settings.style == WidgetStyle.FULL_COVER && cover != null
    val blurred = if (settings.style == WidgetStyle.SHELF && settings.backgroundShown == WidgetBackground.ARTWORK) {
        val hero = if (settings.contentShown == WidgetContent.LIST) list.firstOrNull()?.let { drawn.art[it.id] } else small
        hero?.let { WidgetPictures.background(it, w, h, settings.look, settings.opacity) }
    } else null
    when {
        blurred != null -> frame = frame.background(ImageProvider(blurred), ContentScale.FillBounds)
        !onCover -> paint.background?.let { frame = frame.background(it) }
    }
    if (settings.rounded) frame = frame.cornerRadius(CornerRadius)

    val full = settings.style == WidgetStyle.FULL_COVER
    when (plan.shape) {
        WidgetShape.TILE -> if (full) GlassTile(look, frame) else Tile(look, frame)
        WidgetShape.LINE -> if (full) GlassLine(look, frame) else Line(look, frame)
        WidgetShape.SIDE -> Side(look, frame)
        WidgetShape.TOP -> Top(look, frame)
        WidgetShape.POSTER -> Poster(look, frame)
        WidgetShape.BANNER -> Banner(look, frame)
        WidgetShape.BIG_COVER -> BigCoverLook(look, frame, list, settings.list)
        WidgetShape.CRATE, WidgetShape.RECORDS -> RowsLook(look, frame, list, settings.list)
        WidgetShape.PANES -> if (full) GlassPanes(look, frame) else Panes(look, frame)
        WidgetShape.NONE -> BigHeading(settings.list, paint, frame)
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

/** A list widget too short for a row of its covers still says what it is, and opens the app. */
@Composable
private fun BigHeading(which: WidgetList, paint: Paint, frame: GlanceModifier) {
    Box(modifier = frame.padding(horizontal = 12.dp).clickable(openApp()), contentAlignment = Alignment.CenterStart) {
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
 * Narrower than a title. On a cell, the cover is the tile and play sits on its corner (the mockup's
 * one by one); on a narrow, tall widget the cover keeps its shape and play goes under it.
 */
@Composable
private fun Tile(l: Look, frame: GlanceModifier) {
    val p = l.plan
    val inside = frame.padding(horizontal = p.padH.dp, vertical = p.padV.dp).clickable(openApp())
    if (p.below) {
        Column(modifier = inside, horizontalAlignment = Alignment.CenterHorizontally) {
            if (p.coverW > 0) {
                Artwork(l.cover, p.coverW, p.coverH, l.paint, l.song?.title, radius = 14)
                Spacer(modifier = GlanceModifier.height(SMALL_GAP_DP.dp))
            }
            PlayButton(l.snapshot, p.playSize, l.paint.playFill, l.paint.playIcon)
        }
        return
    }
    Box(modifier = inside, contentAlignment = if (p.coverW > 0) Alignment.BottomEnd else Alignment.Center) {
        if (p.coverW > 0) Artwork(l.cover, p.coverW, p.coverH, l.paint, l.song?.title, radius = 14)
        else if (!p.play) Note(36, l.paint.muted)
        if (p.play) PlayButton(l.snapshot, p.playSize, l.paint.playFill, l.paint.playIcon)
    }
}

/** Full cover's tile: the cover, and play in a circle of glass in the middle of it. */
@Composable
private fun GlassTile(l: Look, frame: GlanceModifier) {
    Box(modifier = frame.clickable(openApp()), contentAlignment = Alignment.Center) {
        CoverFace(l)
        l.plan.glass?.let { Glass(l, it) }
    }
}

/** A line: the cover, the song and the buttons, or the cover and play where the words have no room. */
@Composable
private fun Line(l: Look, frame: GlanceModifier) {
    val p = l.plan
    Row(
        modifier = frame.padding(horizontal = p.padH.dp, vertical = p.padV.dp).clickable(openApp()),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (p.coverW > 0) Artwork(l.cover, p.coverW, p.coverH, l.paint, l.song?.title, radius = if (p.coverH < 48) 8 else 12)
        if (p.titleLines > 0) {
            if (p.coverW > 0) Spacer(modifier = GlanceModifier.width(COVER_GAP_DP.dp))
            Column(modifier = GlanceModifier.defaultWeight()) {
                Titles(l.song, l.paint, p.titleSp, p.artistSp, p.showArtist)
            }
            if (p.play) Spacer(modifier = GlanceModifier.width(SMALL_GAP_DP.dp))
        } else {
            Spacer(modifier = GlanceModifier.defaultWeight())
        }
        if (p.play) Controls(l, l.paint.playFill, l.paint.playIcon, l.paint.text)
    }
}

/** Full cover's line: the cover, darkened from the start under the words, the buttons in glass at the end. */
@Composable
private fun GlassLine(l: Look, frame: GlanceModifier) {
    val p = l.plan
    Box(modifier = frame.clickable(openApp())) {
        CoverFace(l)
        Row(
            modifier = GlanceModifier.fillMaxSize().padding(horizontal = p.padH.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (p.titleLines > 0) {
                Column(modifier = GlanceModifier.defaultWeight()) {
                    Titles(l.song, l.paint, p.titleSp, p.artistSp, p.showArtist)
                }
                if (p.glass != null) Spacer(modifier = GlanceModifier.width(SMALL_GAP_DP.dp))
            } else {
                Spacer(modifier = GlanceModifier.defaultWeight())
            }
            p.glass?.let { Glass(l, it) }
        }
    }
}

/** Cover, wide: the cover at the start, the song at the top of the column beside it, the buttons at its foot. */
@Composable
private fun Side(l: Look, frame: GlanceModifier) {
    val p = l.plan
    val column = if (p.coverW > 0) p.coverH else l.h - 2 * p.padV
    Row(
        modifier = frame.padding(horizontal = p.padH.dp, vertical = p.padV.dp).clickable(openApp()),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (p.coverW > 0) {
            Artwork(l.cover, p.coverW, p.coverH, l.paint, l.song?.title, radius = 18)
            Spacer(modifier = GlanceModifier.width(SIDE_GAP_DP.dp))
        }
        Column(modifier = GlanceModifier.defaultWeight().height(column.dp)) {
            Titles(l.song, l.paint, p.titleSp, p.artistSp, p.showArtist, titleLines = p.titleLines)
            Spacer(modifier = GlanceModifier.defaultWeight())
            if (p.play) Controls(l, l.paint.playFill, l.paint.playIcon, l.paint.text)
        }
    }
}

/** Cover, square or tall: the cover across the top, the song under it, the buttons spread under that. */
@Composable
private fun Top(l: Look, frame: GlanceModifier) {
    val p = l.plan
    Column(modifier = frame.padding(horizontal = p.padH.dp, vertical = p.padV.dp).clickable(openApp())) {
        Artwork(l.cover, p.coverW, p.coverH, l.paint, l.song?.title, radius = if (p.coverW >= 200) 20 else 14)
        Spacer(modifier = GlanceModifier.height(8.dp))
        Titles(l.song, l.paint, p.titleSp, p.artistSp, p.showArtist)
        if (p.play) {
            Spacer(modifier = GlanceModifier.height(SMALL_GAP_DP.dp))
            SpreadControls(l, l.paint.playFill, l.paint.playIcon, l.paint.text)
        }
    }
}

/** Full cover: the cover is the widget, darkened towards the bottom, the song and the glass bar along it. */
@Composable
private fun Poster(l: Look, frame: GlanceModifier) {
    val p = l.plan
    Box(modifier = frame.clickable(openApp())) {
        CoverFace(l)
        Column(
            modifier = GlanceModifier.fillMaxSize().padding(horizontal = p.padH.dp, vertical = p.padV.dp),
            verticalAlignment = Alignment.Bottom,
        ) {
            if (p.titleLines > 0) {
                Titles(l.song, l.paint, p.titleSp, p.artistSp, p.showArtist)
                if (p.glass != null) Spacer(modifier = GlanceModifier.height(8.dp))
            }
            p.glass?.let { glass ->
                Row(modifier = GlanceModifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                    Glass(l, glass)
                }
            }
        }
    }
}

/** Full cover, wide: darkened from the start, the song at the top and the glass bar at the foot. */
@Composable
private fun Banner(l: Look, frame: GlanceModifier) {
    val p = l.plan
    Box(modifier = frame.clickable(openApp())) {
        CoverFace(l)
        Column(modifier = GlanceModifier.fillMaxSize().padding(horizontal = p.padH.dp, vertical = p.padV.dp)) {
            Titles(l.song, l.paint, p.titleSp, p.artistSp, p.showArtist)
            Spacer(modifier = GlanceModifier.defaultWeight())
            p.glass?.let { Glass(l, it) }
        }
    }
}

/** Behind a cover not fetched yet, in a look's pictures: a quiet light over whatever is under it. */
private const val PICTURE_PLACEHOLDER = 0x1FFFFFFF

/**
 * [content] at [spot] of a box [w] by [h] dp, from its top start: a look puts each thing where its
 * plan says. The box is the whole of its parent, padded on all four sides down to the spot, and
 * right to left the spot is mirrored here: where a thing lands depends on no gravity and on no
 * reading of start and end, which a widget's views and Glance's padding were seen to take apart.
 */
@Composable
private fun At(w: Int, h: Int, rtl: Boolean, spot: Spot, modifier: GlanceModifier = GlanceModifier, content: @Composable () -> Unit) {
    val x = if (rtl) w - spot.x - spot.w else spot.x
    Box(
        modifier = GlanceModifier.fillMaxSize().absolutePadding(
            left = x.coerceAtLeast(0).dp, top = spot.y.coerceAtLeast(0).dp,
            right = (w - x - spot.w).coerceAtLeast(0).dp, bottom = (h - spot.y - spot.h).coerceAtLeast(0).dp,
        ),
    ) {
        Box(modifier = modifier.fillMaxSize()) { content() }
    }
}

/** [At] in the widget itself. */
@Composable
private fun At(l: Look, spot: Spot, modifier: GlanceModifier = GlanceModifier, content: @Composable () -> Unit) =
    At(l.w, l.h, l.rtl, spot, modifier, content)

/** One of a look's pictures, drawn ahead of time (WidgetPictures), where its plan put it. */
@Composable
private fun Picture(l: Look, bitmap: Bitmap?, spot: Spot, description: String?, modifier: GlanceModifier = GlanceModifier) {
    At(l, spot, modifier) {
        if (bitmap != null) {
            Image(
                provider = ImageProvider(bitmap),
                contentDescription = description,
                contentScale = ContentScale.FillBounds,
                modifier = GlanceModifier.fillMaxSize(),
            )
        }
    }
}

/** A look's pill of buttons: one pane of glass round them, as wide as they are, with play the filled one. */
@Composable
private fun Pill(l: Look, glass: GlassPane, face: ImageProvider, fill: ColorProvider, icon: ColorProvider, tint: ColorProvider) {
    At(l, Spot(glass.x, glass.y, glass.w, glass.h)) {
        Box(
            modifier = GlanceModifier.fillMaxSize().background(face, ContentScale.FillBounds),
            contentAlignment = Alignment.Center,
        ) {
            if (glass.circle) PlayButton(l.snapshot, l.plan.playSize, fill, icon)
            else Controls(l, fill, icon, tint)
        }
    }
}

/**
 * A look's words: the heading over them where it is drawn there, the title's lines, and the artist.
 * A line that shares its height with the pill at its end stops short of it ([LookPlan.lineRoom]).
 */
@Composable
private fun LookWords(l: Look, look: LookPlan, at: Spot, song: WidgetSong?, paint: Paint, which: WidgetList) {
    val p = l.plan
    val scale = paint.settings.textSize.scale * LocalContext.current.resources.configuration.fontScale
    val titleH = p.titleLines.coerceAtLeast(1) * WidgetLayout.line(p.titleSp, scale)
    val titleTop = at.y + look.headingInWords
    val titleW = look.lineRoom(Spot(at.x, titleTop, at.w, titleH), p.glass)
    val artistW = look.lineRoom(Spot(at.x, titleTop + titleH, at.w, WidgetLayout.line(p.artistSp, scale)), p.glass)
    At(l, at) {
        Column(modifier = GlanceModifier.fillMaxSize()) {
            if (look.headingInWords > 0) {
                Text(
                    text = LocalContext.current.getString(headingOf(which)),
                    style = TextStyle(color = paint.muted, fontSize = scaled(12, paint), fontWeight = FontWeight.Medium),
                    maxLines = 1,
                    modifier = GlanceModifier.width(at.w.dp).height(look.headingInWords.dp),
                )
            }
            Text(
                text = song?.title ?: LocalContext.current.getString(R.string.widget_nothing_playing),
                style = TextStyle(color = paint.text, fontSize = scaled(p.titleSp, paint), fontWeight = FontWeight.Medium),
                maxLines = p.titleLines.coerceAtLeast(1),
                modifier = GlanceModifier.width(titleW.dp),
            )
            if (song != null && p.showArtist) {
                Text(
                    text = song.artist,
                    style = TextStyle(color = paint.muted, fontSize = scaled(p.artistSp, paint)),
                    maxLines = 1,
                    modifier = GlanceModifier.width(artistW.dp),
                )
            }
        }
    }
}

/**
 * Big cover: the cover of what is playing big, its words over its top on their band and the pill of
 * buttons in frosted glass over its foot, and the list's covers as a mosaic round it, a row of the
 * mosaic at a time, each cover tapped to play its song. With a list alone the big cover is the
 * list's first song, its heading and name over it, and a tap plays it.
 */
@Composable
private fun BigCoverLook(l: Look, frame: GlanceModifier, list: List<WidgetSong>, which: WidgetList) {
    val p = l.plan
    val look = p.look ?: return
    val hero = look.hero ?: return
    val context = LocalContext.current
    val density = context.resources.displayMetrics.density
    val alone = l.paint.settings.contentShown == WidgetContent.LIST
    val first = if (alone) list.firstOrNull() else null
    val song = first ?: l.song
    val art = if (alone) first?.let { l.firsts[it.id] ?: l.art[it.id] } else l.cover
    val tap = first?.let { actionStartActivity(WidgetCommands.playIntent(context, it)) } ?: openApp()
    val onCover = l.paint.copy(text = OnCover, muted = OnCoverMuted)
    Box(modifier = frame) {
        look.heroPicture?.let { pic ->
            val d = WidgetPictures.density(look, density)
            // The band fades out before the pill, which is frosted from the cover as it is.
            val fade = p.glass?.let { minOf(WidgetPictures.FADE_DP, it.y - hero.y - look.band) } ?: WidgetPictures.FADE_DP
            Picture(l, WidgetPictures.cover(context, art, pic, hero, look.heroRadius, look.band, fade, d, PICTURE_PLACEHOLDER), pic, song?.title, GlanceModifier.clickable(tap))
        }
        look.words?.let { LookWords(l, look, it, song, onCover, which) }
        p.glass?.let { g ->
            // Frosted from the cover under it, as Full cover's glass is.
            val pane = art?.let { WidgetGlass.pane(it, hero.w, hero.h, g.copy(x = g.x - hero.x, y = g.y - hero.y), WidgetScrim.NONE, density, l.rtl) }
            val face = if (pane != null) ImageProvider(pane) else ImageProvider(if (g.circle) R.drawable.widget_glass_plain_circle else R.drawable.widget_glass_plain)
            Pill(l, g, face, OnCover, ColorProvider(Color(WidgetColours.deep(l.song?.colour))), OnCover)
        }
        // The mosaic, a row at a time: a Box keeps no more than ten children, and a row of them is one,
        // its covers placed in it as the widget's own things are.
        val offset = if (alone) 1 else 0
        for (row in look.picks.withIndex().groupBy { it.value.y }.values) {
            val start = row.first().value
            val box = Spot(start.x, start.y, row.last().value.right - start.x, start.h)
            At(l, box) {
                Box(modifier = GlanceModifier.fillMaxSize()) {
                    for ((index, spot) in row) {
                        val pick = list.getOrNull(index + offset) ?: continue
                        At(box.w, box.h, l.rtl, spot.at(-box.x, -box.y)) { MosaicCover(l, pick, spot, look.pickRadius, context) }
                    }
                }
            }
        }
    }
}

/** A cover of Big cover's mosaic, rounded as its own picture (Android 11 and under cannot round a widget's image), tapped to play its song. */
@Composable
private fun MosaicCover(l: Look, song: WidgetSong, spot: Spot, radius: Int, context: Context) {
    val art = l.art[song.id]?.let { WidgetPictures.rounded(it, spot.w, radius) }
    val modifier = GlanceModifier.size(spot.w.dp, spot.h.dp).clickable(actionStartActivity(WidgetCommands.playIntent(context, song)))
    if (art != null) {
        Image(provider = ImageProvider(art), contentDescription = song.title, contentScale = ContentScale.FillBounds, modifier = modifier)
    } else {
        Box(modifier = modifier.background(l.paint.placeholder).cornerRadius(radius.dp), contentAlignment = Alignment.Center) {
            Note((spot.w * 0.42f).roundToInt(), l.paint.muted)
        }
    }
}

/**
 * Crate and Records: what is playing on top (the crate's line has its cover, Records its sleeve with
 * the record out), its words and the pill of buttons on the background, then the list's heading and
 * its rows, each row one picture with the places to tap laid over it, a song's where its cover shows.
 */
@Composable
private fun RowsLook(l: Look, frame: GlanceModifier, list: List<WidgetSong>, which: WidgetList) {
    val p = l.plan
    val look = p.look ?: return
    val context = LocalContext.current
    val density = context.resources.displayMetrics.density
    val d = WidgetPictures.density(look, density)
    val crate = p.shape == WidgetShape.CRATE
    Box(modifier = frame) {
        val hero = look.hero
        val pic = look.heroPicture
        if (hero != null && pic != null) {
            val bitmap = if (crate) WidgetPictures.cover(context, l.cover, pic, hero, look.heroRadius, 0, 0, d, PICTURE_PLACEHOLDER)
            else WidgetPictures.sleeve(context, l.cover, pic, hero, look.heroRadius, d, l.rtl, PICTURE_PLACEHOLDER)
            Picture(l, bitmap, pic, l.song?.title, GlanceModifier.clickable(openApp()))
        }
        look.words?.let { LookWords(l, look, it, l.song, l.paint, which) }
        // On the background: its glass is a drawable, a pill or, round play alone, a circle (their corners are round to the end).
        p.glass?.let { g -> Pill(l, g, ImageProvider(l.paint.glass), l.paint.playFill, l.paint.playIcon, l.paint.text) }
        look.heading?.let { at -> At(l, at) { Heading(which, l.paint, at.h) } }
        val taps = look.taps()
        var first = 0
        for ((r, picture) in look.rowPictures.withIndex()) {
            val count = look.rows[r]
            val spots = look.picks.subList(first, first + count)
            val arts = (first until first + count).map { i -> list.getOrNull(i)?.let { l.art[it.id] } }
            val bitmap = if (crate) WidgetPictures.crate(context, arts, spots, first, picture, look.pickRadius, d, l.rtl, PICTURE_PLACEHOLDER)
            else WidgetPictures.records(context, arts, spots, picture, d, l.rtl, PICTURE_PLACEHOLDER)
            val row = taps.subList(first, first + count)
            val top = row.minOf { it.y }
            val bottom = row.maxOf { it.bottom }
            val from = first
            At(l, picture) {
                Box(modifier = GlanceModifier.fillMaxSize()) {
                    if (bitmap != null) {
                        Image(provider = ImageProvider(bitmap), contentDescription = null, contentScale = ContentScale.FillBounds, modifier = GlanceModifier.fillMaxSize())
                    }
                    // Where each song can be tapped: the part of its cover that shows, placed in the
                    // picture as the picture's covers are, mirrored with them right to left.
                    for ((j, spot) in row.withIndex()) {
                        val song = list.getOrNull(from + j) ?: continue
                        At(picture.w, picture.h, l.rtl, Spot(spot.x - picture.x, top - picture.y, spot.w, bottom - top)) {
                            Box(
                                modifier = GlanceModifier
                                    .fillMaxSize()
                                    .clickable(actionStartActivity(WidgetCommands.playIntent(context, song)))
                                    .semantics { contentDescription = song.title },
                            ) {}
                        }
                    }
                }
            }
            first += count
        }
    }
}

/** A tablet, Cover: the cover and the song with its buttons on the left, Up next on the right. */
@Composable
private fun Panes(l: Look, frame: GlanceModifier) {
    val p = l.plan
    val column = if (p.coverW > 0) p.coverH else l.h - 2 * p.padV
    Row(
        modifier = frame.padding(horizontal = p.padH.dp, vertical = p.padV.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(modifier = GlanceModifier.defaultWeight().clickable(openApp()), verticalAlignment = Alignment.CenterVertically) {
            if (p.coverW > 0) {
                Artwork(l.cover, p.coverW, p.coverH, l.paint, l.song?.title, radius = 20)
                Spacer(modifier = GlanceModifier.width(SIDE_GAP_DP.dp))
            }
            Column(modifier = GlanceModifier.defaultWeight().height(column.dp)) {
                Titles(l.song, l.paint, p.titleSp, p.artistSp, p.showArtist, titleLines = p.titleLines)
                Spacer(modifier = GlanceModifier.defaultWeight())
                if (p.play) Controls(l, l.paint.playFill, l.paint.playIcon, l.paint.text)
            }
        }
        // The line between the panes, in the gap between them.
        Spacer(modifier = GlanceModifier.width((PANE_GAP_DP / 2).dp))
        Box(modifier = GlanceModifier.width(1.dp).height(column.dp).background(l.paint.divider)) {}
        Spacer(modifier = GlanceModifier.width((PANE_GAP_DP / 2 - 1).dp))
        UpNext(l)
    }
}

/** A tablet, Full cover: the cover across it all, darker under Up next, the song and the glass bar at its foot on the left. */
@Composable
private fun GlassPanes(l: Look, frame: GlanceModifier) {
    val p = l.plan
    Box(modifier = frame) {
        CoverFace(l)
        Row(modifier = GlanceModifier.fillMaxSize().padding(horizontal = p.padH.dp, vertical = p.padV.dp)) {
            Column(
                modifier = GlanceModifier.defaultWeight().fillMaxHeight().clickable(openApp()),
                verticalAlignment = Alignment.Bottom,
            ) {
                Titles(l.song, l.paint, p.titleSp, p.artistSp, p.showArtist)
                p.glass?.let {
                    Spacer(modifier = GlanceModifier.height(8.dp))
                    Glass(l, it)
                }
            }
            Spacer(modifier = GlanceModifier.width(PANE_GAP_DP.dp))
            UpNext(l)
        }
    }
}

/** The next songs of the queue, each one tapped to play the queue on from it. */
@Composable
private fun UpNext(l: Look) {
    val p = l.plan
    val context = LocalContext.current
    Column(modifier = GlanceModifier.width(p.paneW.dp)) {
        Text(
            text = context.getString(R.string.widget_up_next),
            style = TextStyle(color = l.paint.muted, fontSize = scaled(12, l.paint), fontWeight = FontWeight.Medium),
            maxLines = 1,
            modifier = GlanceModifier.fillMaxWidth().height(p.headingHeight.dp),
        )
        for (song in l.snapshot.upNext.take(p.upNext)) {
            Row(
                modifier = GlanceModifier
                    .fillMaxWidth()
                    .height(p.upNextRow.dp)
                    .clickable(actionRunCallback<PlayUpNextAction>(actionParametersOf(WidgetCommands.UpNextSong to song.id))),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Artwork(l.art[song.id], UP_NEXT_COVER_DP, UP_NEXT_COVER_DP, l.paint, null, radius = 8)
                Spacer(modifier = GlanceModifier.width(COVER_GAP_DP.dp))
                Column(modifier = GlanceModifier.defaultWeight()) {
                    Text(
                        text = song.title,
                        style = TextStyle(color = l.paint.text, fontSize = scaled(13, l.paint), fontWeight = FontWeight.Medium),
                        maxLines = 1,
                    )
                    Text(
                        text = song.artist,
                        style = TextStyle(color = l.paint.muted, fontSize = scaled(12, l.paint)),
                        maxLines = 1,
                    )
                }
            }
        }
    }
}

/** Full cover's face: the cover, cropped to the widget, under its scrim. With no cover yet, a note on the cover's colour. */
@Composable
private fun CoverFace(l: Look) {
    val cover = l.cover
    if (cover == null) {
        Box(modifier = GlanceModifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Note(if (minOf(l.w, l.h) >= 120) 48 else 28, l.paint.muted)
        }
        return
    }
    Image(
        provider = ImageProvider(cover),
        contentDescription = l.song?.title,
        contentScale = ContentScale.Crop,
        modifier = GlanceModifier.fillMaxSize(),
    )
    val scrim = WidgetScrim.of(l.plan)
    when {
        scrim == WidgetScrim.FLAT -> Box(modifier = GlanceModifier.fillMaxSize().background(ColorProvider(Color.Black.copy(alpha = scrim.from)))) {}
        scrim != WidgetScrim.NONE -> Image(
            provider = ImageProvider(if (l.rtl) scrim.rtl else scrim.ltr),
            contentDescription = null,
            contentScale = ContentScale.FillBounds,
            modifier = GlanceModifier.fillMaxSize(),
        )
    }
}

/**
 * Full cover's one pane of glass, at the place and size the plan gave it: the cover under it,
 * frosted ahead of time (see WidgetGlass), with the buttons on it, or the circle round play. With
 * no cover to frost, a plain pane of light, as the mockup draws it.
 */
@Composable
private fun Glass(l: Look, glass: GlassPane) {
    val density = LocalContext.current.resources.displayMetrics.density
    val pane = l.cover?.let { WidgetGlass.pane(it, l.w, l.h, glass, WidgetScrim.of(l.plan), density, l.rtl) }
    val face = if (pane != null) ImageProvider(pane)
    else ImageProvider(if (glass.circle) R.drawable.widget_glass_plain_circle else R.drawable.widget_glass_plain)
    Box(
        modifier = GlanceModifier.size(glass.w.dp, glass.h.dp).background(face, ContentScale.FillBounds),
        contentAlignment = Alignment.Center,
    ) {
        if (glass.circle) PlayButton(l.snapshot, glass.w, null, OnCover)
        else Controls(l, null, OnCover, OnCover)
    }
}

@Composable
private fun Titles(
    song: WidgetSong?,
    paint: Paint,
    titleSp: Int,
    artistSp: Int,
    showArtist: Boolean,
    titleLines: Int = 1,
) {
    Text(
        text = song?.title ?: LocalContext.current.getString(R.string.widget_nothing_playing),
        style = TextStyle(color = paint.text, fontSize = scaled(titleSp, paint), fontWeight = FontWeight.Medium),
        maxLines = titleLines.coerceAtLeast(1),
    )
    if (song != null && showArtist) {
        Text(
            text = song.artist,
            style = TextStyle(color = paint.muted, fontSize = scaled(artistSp, paint)),
            maxLines = 1,
        )
    }
}

/**
 * Previous, play and next across the width, play in the middle (Cover's square and tall shapes,
 * Shelf's line of buttons). In five places where the plan has room for them: the first is the
 * heart's, a blank without it, the last shuffle's, a blank without it, so the three in the middle
 * are where they are whether the heart is there or not.
 */
@Composable
private fun SpreadControls(l: Look, fill: ColorProvider, icon: ColorProvider, tint: ColorProvider) {
    val p = l.plan
    Row(
        modifier = GlanceModifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (p.slots == 5) {
            if (p.like && l.song != null) LikeButton(l.snapshot, p.button, tint)
            else Spacer(modifier = GlanceModifier.width(p.button.dp))
        }
        if (p.skips) {
            Gap(if (p.slots == 5) p.gap else 0) {
                TransportButton(R.drawable.skip_previous, R.string.widget_previous, actionRunCallback<PreviousAction>(), p.button, tint)
            }
            Gap(p.gap) { PlayButton(l.snapshot, p.playSize, fill, icon) }
            Gap(p.gap) { TransportButton(R.drawable.skip_next, R.string.widget_next, actionRunCallback<NextAction>(), p.button, tint) }
        } else if (p.play) {
            PlayButton(l.snapshot, p.playSize, fill, icon)
        }
        if (p.slots == 5) {
            if (p.shuffle) Gap(p.gap) { ShuffleButton(l.snapshot, p.button, tint) }
            else Spacer(modifier = GlanceModifier.width((p.gap + p.button).dp))
        }
    }
}

/**
 * [content] with [start] dp of room before it, in a box of its own: padding on a button itself
 * would sit inside the button's size and squeeze what it draws, since Glance pads inside a view.
 */
@Composable
private fun Gap(start: Int, content: @Composable () -> Unit) {
    if (start <= 0) content()
    else Box(modifier = GlanceModifier.padding(start = start.dp)) { content() }
}

/**
 * Previous, play and next as the plan has room for, play the filled one among them (or in glass a
 * larger glyph), the heart before previous where the plan found room for it, and shuffle after next
 * where it did for that. With nothing playing there is nothing to like, and the heart is not drawn.
 *
 * In a Row of their own, the room between them padding rather than spacers. Glance keeps no more
 * than ten children in a Row or a Column and drops the rest without a word, and a line of song has
 * four before its buttons: with the heart those were eleven, and next was the one that went.
 */
@Composable
private fun Controls(l: Look, fill: ColorProvider?, icon: ColorProvider, tint: ColorProvider) {
    val p = l.plan
    val heart = p.like && l.song != null
    Row(verticalAlignment = Alignment.CenterVertically) {
        if (heart) LikeButton(l.snapshot, p.button, tint)
        if (p.skips) Gap(if (heart) p.gap else 0) {
            TransportButton(R.drawable.skip_previous, R.string.widget_previous, actionRunCallback<PreviousAction>(), p.button, tint)
        }
        if (p.play) Gap(if (heart || p.skips) p.gap else 0) { PlayButton(l.snapshot, p.playSize, fill, icon) }
        if (p.skips) Gap(p.gap) { TransportButton(R.drawable.skip_next, R.string.widget_next, actionRunCallback<NextAction>(), p.button, tint) }
        if (p.shuffle) Gap(p.gap) { ShuffleButton(l.snapshot, p.button, tint) }
    }
}

/** The heart: filled when the song is liked, in the colour of the buttons beside it, as in the player. */
@Composable
private fun LikeButton(snapshot: WidgetSnapshot, size: Int, tint: ColorProvider, modifier: GlanceModifier = GlanceModifier) {
    CircleIconButton(
        imageProvider = ImageProvider(if (snapshot.isLiked) R.drawable.favorite else R.drawable.favorite_border),
        contentDescription = LocalContext.current.getString(if (snapshot.isLiked) R.string.action_remove_like else R.string.action_like),
        onClick = actionRunCallback<LikeAction>(),
        backgroundColor = null,
        contentColor = tint,
        modifier = modifier.size(size.dp),
    )
}

/** Shuffle, lit when the queue is shuffled, as the player's own button is. */
@Composable
private fun ShuffleButton(snapshot: WidgetSnapshot, size: Int, tint: ColorProvider, modifier: GlanceModifier = GlanceModifier) {
    CircleIconButton(
        imageProvider = ImageProvider(if (snapshot.isShuffled) R.drawable.shuffle_on else R.drawable.shuffle_off),
        contentDescription = LocalContext.current.getString(R.string.shuffle),
        onClick = actionRunCallback<ShuffleAction>(),
        backgroundColor = null,
        contentColor = tint,
        modifier = modifier.size(size.dp),
    )
}

/**
 * Play or pause: a filled circle of [fill], or with none (on glass) a glyph a size larger than the
 * buttons beside it, which is how the mockup marks it out there.
 */
@Composable
private fun PlayButton(snapshot: WidgetSnapshot, size: Int, fill: ColorProvider?, icon: ColorProvider, modifier: GlanceModifier = GlanceModifier) {
    val image = ImageProvider(if (snapshot.isPlaying) R.drawable.pause else R.drawable.play)
    val description = LocalContext.current.getString(if (snapshot.isPlaying) R.string.widget_pause else R.string.widget_play)
    if (fill != null && size < SMALL_PLAY_DP) {
        // Glance's round button draws a 24 dp glyph whatever its size, which fills a small circle:
        // the one by one's play is drawn here, its glyph a little over half the circle, as the mockup's.
        Box(
            modifier = modifier
                .size(size.dp)
                .background(ImageProvider(R.drawable.widget_circle), ContentScale.FillBounds, ColorFilter.tint(fill))
                .clickable(actionRunCallback<PlayPauseAction>(), R.drawable.widget_ripple_circle),
            contentAlignment = Alignment.Center,
        ) {
            Image(
                provider = image,
                contentDescription = description,
                colorFilter = ColorFilter.tint(icon),
                modifier = GlanceModifier.size((size * 0.56f).roundToInt().coerceAtLeast(14).dp),
            )
        }
        return
    }
    if (fill != null) {
        CircleIconButton(
            imageProvider = image,
            contentDescription = description,
            onClick = actionRunCallback<PlayPauseAction>(),
            backgroundColor = fill,
            contentColor = icon,
            modifier = modifier.size(size.dp),
        )
        return
    }
    Box(
        modifier = modifier.size(size.dp).clickable(actionRunCallback<PlayPauseAction>(), R.drawable.widget_ripple_circle),
        contentAlignment = Alignment.Center,
    ) {
        Image(
            provider = image,
            contentDescription = description,
            colorFilter = ColorFilter.tint(icon),
            modifier = GlanceModifier.size((size * 0.7f).roundToInt().coerceIn(24, 34).dp),
        )
    }
}

@Composable
private fun TransportButton(icon: Int, description: Int, action: Action, size: Int, tint: ColorProvider, modifier: GlanceModifier = GlanceModifier) {
    CircleIconButton(
        imageProvider = ImageProvider(icon),
        contentDescription = LocalContext.current.getString(description),
        onClick = action,
        backgroundColor = null,
        contentColor = tint,
        modifier = modifier.size(size.dp),
    )
}

/** A cover, or while there is none yet a note on a quiet square of the same size, so nothing jumps when it lands. */
@Composable
private fun Artwork(art: Bitmap?, w: Int, h: Int, paint: Paint, description: String?, radius: Int = 8) {
    var modifier = GlanceModifier.size(w.dp, h.dp)
    if (paint.settings.rounded) modifier = modifier.cornerRadius(radius.dp)
    if (art != null) {
        Image(
            provider = ImageProvider(art),
            contentDescription = description,
            contentScale = ContentScale.Crop,
            modifier = modifier,
        )
    } else {
        Box(modifier = modifier.background(paint.placeholder), contentAlignment = Alignment.Center) {
            Note((minOf(w, h) * 0.45f).roundToInt(), paint.muted)
        }
    }
}

@Composable
private fun Note(size: Int, tint: ColorProvider) {
    Image(
        provider = ImageProvider(R.drawable.music_note),
        contentDescription = null,
        colorFilter = ColorFilter.tint(tint),
        modifier = GlanceModifier.size(size.dp),
    )
}
