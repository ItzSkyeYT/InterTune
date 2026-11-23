/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.ui.player

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Paint
import android.graphics.RectF
import android.os.Build
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlurEffect
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Constraints
import androidx.core.graphics.createBitmap
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import coil3.imageLoader
import coil3.request.ImageRequest
import coil3.request.allowHardware
import coil3.toBitmap
import com.dd3boh.outertune.LocalBatteryMode
import com.dd3boh.outertune.constants.BatteryMode
import com.dd3boh.outertune.constants.LivingColours
import com.dd3boh.outertune.constants.Unreleased
import com.dd3boh.outertune.extensions.isPowerSaver
import com.dd3boh.outertune.playback.LevelTap
import com.dd3boh.outertune.playback.MusicLevels
import com.dd3boh.outertune.ui.utils.Animations
import com.dd3boh.outertune.utils.coilCoroutine
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.withContext
import kotlin.math.max
import kotlin.math.min

/**
 * The player's living background: the cover reduced to patches of colour that breathe with the
 * music. LivingField.kt says what is drawn where; this draws it.
 *
 * It is drawn an eighth of the size and stretched, with a slight blur where the system has one.
 * There is nothing in the picture finer than a patch, so nothing is lost, and some twenty soft
 * discs over a whole phone screen at every frame would be several times the screen in fill for no
 * gain.
 *
 * Frames are asked for only while there is something to move: the player open, the app in view,
 * and music playing or the picture still settling after it stopped. A paused player is a still
 * picture and costs what the plain blurred cover costs. With battery saver on, or animations
 * off (Animations), it does not move at all.
 *
 * While it moves it is redrawn thirty times a second at most, whatever the screen can do
 * ([RedrawPace]). What a redraw costs is not this picture, which is small: it is the glass panels
 * over it, which look through it and are redrawn with it, and the rest of the window. On the
 * screen's frames between two redraws nothing of it is stepped and nothing is drawn.
 *
 * Nothing is drawn round the cover, in the player or in the sample in Settings: his word of
 * 20 Nov 2026 took away the aura that stood on its edge.
 *
 * @param cover what Coil is given for the cover; the small one the blurred background uses
 * @param tap where the levels come from, or null to drift without them
 * @param onScreen false while the player is closed to its mini player
 * @param strength how strongly it answers the music, 0 to 1: the setting (LivingField.reach)
 * @param smoothing how softly, 0 to 1: the other setting (LivingField.ease)
 * @param colours what it is coloured from: the cover's main colours, or the cover itself
 * @param alpha how much of the picture shows over what is behind it, 0 to 1. Asked for here and
 *   not with a modifier's alpha: that one has the stretched picture drawn into a buffer the size
 *   of the screen at every redraw and then fades the buffer. This fades the small picture as it
 *   is stretched, which comes to the same on screen.
 * @param covered true while it is on screen and none of it can be seen, because something that
 *   lets nothing through lies over all of it: the player's queue, open. It asks for no frames
 *   then, as when it is off screen, and goes on listening, so that it comes back knowing where
 *   the song is in its shape.
 */
@Composable
fun LivingBackground(
    cover: Any?,
    tap: LevelTap?,
    playing: Boolean,
    onScreen: Boolean,
    strength: Float,
    smoothing: Float,
    colours: LivingColours,
    modifier: Modifier = Modifier,
    alpha: Float = 1f,
    covered: Boolean = false,
) {
    // The grid is made for the room the picture is given, not for the window: in the player the
    // two are the same, in the Settings sample the picture is a strip.
    BoxWithConstraints(modifier) {
        LivingPicture(cover, tap, playing, onScreen, covered, strength, smoothing, colours, alpha, constraints.maxWidth, constraints.maxHeight)
    }
}

@Composable
private fun LivingPicture(
    cover: Any?,
    tap: LevelTap?,
    playing: Boolean,
    onScreen: Boolean,
    covered: Boolean,
    strength: Float,
    smoothing: Float,
    colours: LivingColours,
    alpha: Float,
    wide: Int,
    tall: Int,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val look = remember { Look() }
    // How far the small picture is stretched to fill its room (drawnSmall).
    val stretch = remember { mutableStateOf(Offset(1f, 1f)) }

    // Three patches along the short side and as many along the long side as keeps them round, or
    // five where the ranges lie in ribbons, whose edges are curves and want more to be drawn with.
    // A picture that changes shape, or the way it is coloured, starts over with the new grid.
    val upright = tall >= wide
    val ribbons = colours == LivingColours.MAIN
    val across = if (ribbons) LivingField.FLOW_ACROSS else LivingField.ACROSS
    val along = LivingField.along(
        long = max(wide, tall).toFloat(), short = min(wide, tall).toFloat(),
        across = across, most = if (ribbons) LivingField.FLOW_MOST_ALONG else LivingField.MOST_ALONG,
    )
    val motion = remember(upright, along, across) {
        if (upright) LivingMotion(across, along) else LivingMotion(along, across)
    }

    motion.strength = strength
    motion.smoothing = smoothing
    motion.separate = ribbons

    // Saver, or animations turned off: nothing moves, and nothing is measured for it.
    val still = wantsStillness(context, LocalBatteryMode.current)

    // Read by the drawing alone, so a new frame redraws the canvas and recomposes nothing.
    var frame by remember { mutableLongStateOf(0L) }
    // Counts the covers, so a cover that changes while nothing moves starts the frames again.
    var covers by remember { mutableIntStateOf(0) }

    LaunchedEffect(cover, motion, colours) {
        // the patches, or for the main colours those three, the bass's first
        val (patches, mains) = withContext(coilCoroutine) {
            val bitmap = context.imageLoader.execute(
                ImageRequest.Builder(context)
                    .data(cover)
                    .allowHardware(false)
                    .build()
            ).image?.toBitmap() ?: return@withContext null
            val pixels = IntArray(bitmap.width * bitmap.height)
            bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
            when (colours) {
                // what is under the ribbons, should they leave anything uncovered, is the bass's
                LivingColours.MAIN -> LivingField.mainColours(pixels, bitmap.width, bitmap.height).let { three ->
                    IntArray(motion.count) { three[MusicLevels.BASS] } to three
                }
                LivingColours.COVER -> LivingField.patches(pixels, bitmap.width, bitmap.height, motion.columns, motion.rows) to null
            }
        } ?: return@LaunchedEffect
        motion.turnTo(patches, glow = mains?.get(MusicLevels.BASS), mains = mains)
        covers++
    }

    LaunchedEffect(onScreen, covered, playing, covers, motion, lifecycleOwner, still) {
        // Covered, it is as good as off screen: nobody sees it move, and every frame of it would
        // still have the window drawn again over it.
        if (!onScreen || covered) {
            look.unseen = true
            return@LaunchedEffect
        }
        if (still) {
            motion.settle()
            frame++
            return@LaunchedEffect
        }
        lifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
            // While nothing was drawn (the player closed to its mini player, the app out of view)
            // the covers went on arriving and nothing turned into them. It comes back in the
            // colours of the song that plays, not turning into them from an older one's.
            if (look.unseen) {
                look.unseen = false
                motion.arrive()
            }
            try {
                val levels = FloatArray(MusicLevels.VALUES)
                var frameAt = 0L
                // What is heard as each of the picture's steps ends. A step ends a little before
                // the frame it is taken on, or a hair after, and the tap answers for the present:
                // so it is asked that much less far ahead, or more. It is never asked backwards,
                // and a step from further back than the lead, which takes a frame that came very
                // late, is told what is heard now.
                val heard = LivingMotion.Heard { at ->
                    val aheadUs = (motion.lead() * 1_000_000).toLong() + (at - frameAt) / 1000
                    levels.takeIf { playing && tap?.now(it, aheadUs) == true }
                }
                while (playing || !motion.atRest()) {
                    withFrameNanos { now ->
                        frameAt = now
                        // Thirty times a second at most, and on the screen's other frames nothing
                        // is stepped and nothing drawn: see RedrawPace.
                        if (motion.frame(now, redraws, playing, heard)) frame = now
                    }
                }
                // At rest, or never moved at all: drawn once more, for the colours that arrived
                // while nothing was drawn.
                frame++
            } finally {
                if (!lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) look.unseen = true
            }
        }
    }

    // The music is watched for as long as the picture is on screen, playing or not, covered or
    // not, and not from the effect above, which starts again at every pause, every cover and when
    // the lyrics come up. Each of those took the last watcher away for a moment, the tap took the
    // moment for music it had missed, and the song's shape started over: a pause in a build-up
    // cost the drop, and so would a look at the queue. Nothing is offered to measure while
    // nothing plays, so watching a pause costs nothing.
    LaunchedEffect(tap, onScreen, still, lifecycleOwner) {
        if (tap == null || !onScreen || still) return@LaunchedEffect
        lifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
            tap.watch()
            try {
                awaitCancellation()
            } finally {
                tap.unwatch()
            }
        }
    }

    Canvas(Modifier.fillMaxSize().drawnSmall(stretch, alpha)) {
        @Suppress("UNUSED_VARIABLE")
        val redrawnAt = frame
        drawIntoCanvas { canvas ->
            look.discs.field(canvas.nativeCanvas, motion, size.width, size.height)
        }
    }
}

/**
 * The one pace every living picture in the app is redrawn at. Two can be up at once, the player's
 * and the sample in Settings under it, and each with a pace of its own would have frames of its
 * own: a window redrawn sixty times a second for two pictures redrawn thirty times each. Asked on
 * the main thread only, where the frames come.
 */
private val redraws = RedrawPace()

/** What the drawing keeps between frames, so that a frame allocates nothing. */
private class Look {
    /** True from when the picture stops being drawn, off screen, until its frames start again. */
    var unseen = false

    val discs = LivingDiscs()
}

/**
 * What Living blur is drawn with: soft discs of colour, and the field of them the motion says.
 * The player draws with it, and so does a lyrics video (LyricsVideoFrames), so that the two look
 * the same. Allocates nothing once made.
 */
internal class LivingDiscs {
    private val paint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
    private val into = RectF()

    /**
     * A disc that fades to nothing at its rim, as transparency alone: drawn through a paint, it
     * takes the paint's colour. One small bitmap stretched to every size, where a gradient would
     * be a new shader for each of some twenty discs sixty times a second.
     */
    private val soft: Bitmap = run {
        val side = 64
        val alphas = IntArray(side * side) {
            val x = (it % side + 0.5f) / side * 2 - 1
            val y = (it / side + 0.5f) / side * 2 - 1
            val inside = max(0f, 1f - (x * x + y * y))
            // level in the middle and at the rim, steepest half way
            ((inside * inside * 255).toInt() shl 24)
        }
        createBitmap(side, side, Bitmap.Config.ALPHA_8).apply { setPixels(alphas, 0, side, 0, 0, side, side) }
    }

    fun disc(canvas: android.graphics.Canvas, x: Float, y: Float, halfWidth: Float, halfHeight: Float, color: Int, strength: Float) {
        paint.color = color
        paint.alpha = (strength.coerceIn(0f, 1f) * 255).toInt()
        into.set(x - halfWidth, y - halfHeight, x + halfWidth, y + halfHeight)
        canvas.drawBitmap(soft, null, into, paint)
    }

    /**
     * The field as [motion] has it now, over a picture [width] by [height]: what shows where the
     * patches leave anything uncovered, the patches, and the bass's glow.
     */
    fun field(canvas: android.graphics.Canvas, motion: LivingMotion, width: Float, height: Float) {
        canvas.drawColor(motion.under)
        val cellWidth = width / motion.columns
        val cellHeight = height / motion.rows
        for (i in 0 until motion.count) {
            val radius = motion.radius(i)
            disc(canvas, motion.x(i) * width, motion.y(i) * height, radius * cellWidth, radius * cellHeight, motion.color(i), 1f)
        }
        // A glow on the bottom edge and one on the top, half of each off the picture: the bass
        // has both ends of it.
        disc(canvas, width / 2, height, width * 0.8f, height * motion.glowHeight(), motion.glow, motion.glowStrength())
        disc(canvas, width / 2, 0f, width * 0.8f, height * motion.glowHeight() * 0.7f, motion.glow, motion.glowStrength() * 0.8f)
    }
}

/** The picture is drawn at one part in this many of its size, each way. */
internal const val LIVING_SHRINK = 8

/** How much the small picture is blurred before it is stretched, in its own pixels. Hides the steps of the stretching. */
private const val SMOOTHING = 2.5f

/**
 * Lays the content out [LIVING_SHRINK] times smaller than the room it is given and stretches it back to
 * fill that room, through a layer of its own so that it is the small picture that gets stretched
 * and not its drawing that gets scaled. [stretched] is told by how much, each way. [alpha] is how
 * much of the stretched picture shows: the layer's own, so that the picture is faded as one thing
 * and nothing as big as the screen is drawn to fade it.
 */
internal fun Modifier.drawnSmall(stretched: MutableState<Offset>, alpha: Float): Modifier = run {
    var stretch by stretched
    layout { measurable, constraints ->
        if (!constraints.hasBoundedWidth || !constraints.hasBoundedHeight) {
            val placeable = measurable.measure(constraints)
            return@layout layout(placeable.width, placeable.height) { placeable.place(0, 0) }
        }
        val width = constraints.maxWidth
        val height = constraints.maxHeight
        val small = measurable.measure(Constraints.fixed(max(1, width / LIVING_SHRINK), max(1, height / LIVING_SHRINK)))
        stretch = Offset(width / small.width.toFloat(), height / small.height.toFloat())
        layout(width, height) { small.place(0, 0) }
    }.graphicsLayer {
        scaleX = stretch.x
        scaleY = stretch.y
        transformOrigin = TransformOrigin(0f, 0f)
        compositingStrategy = CompositingStrategy.Offscreen
        this.alpha = alpha
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            renderEffect = BlurEffect(SMOOTHING, SMOOTHING, TileMode.Clamp)
        }
    }
}

/**
 * Battery use on Saver, which counts the phone's battery saver as the switch under it says, or
 * animations off, in Android's settings or by the Animations switch (Animations, read as it is
 * composed, so a flip is seen at once); before Battery use, the phone's battery saver itself. The
 * phone's saver was read here directly in every build, so the picture stood still with nothing
 * anywhere to say why, and against the switch that tells the app to leave the phone's saver aside
 * (#49, 27 Oct 2026). Saver says so under the background row; animations off, under the sample.
 */
private fun wantsStillness(context: Context, mode: BatteryMode): Boolean =
    mode == BatteryMode.SAVER || (!Unreleased.BATTERY_MODES && context.isPowerSaver()) || Animations.off
