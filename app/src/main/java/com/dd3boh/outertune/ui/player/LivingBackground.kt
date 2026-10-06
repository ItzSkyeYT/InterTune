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
import android.provider.Settings
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
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.BlurEffect
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
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
import com.dd3boh.outertune.constants.LivingColours
import com.dd3boh.outertune.extensions.isPowerSaver
import com.dd3boh.outertune.playback.LevelTap
import com.dd3boh.outertune.playback.MusicLevels
import com.dd3boh.outertune.utils.coilCoroutine
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
 * turned off in the system, it does not move at all.
 *
 * The cover takes up most of the player and hides what is behind it, so most of what answers the
 * music is an aura on the cover's own edge ([PlayerCoverPlace] says where that is), half of it
 * behind the cover and half spilling out round it.
 *
 * @param cover what Coil is given for the cover; the small one the blurred background uses
 * @param tap where the levels come from, or null to drift without them
 * @param onScreen false while the player is closed to its mini player
 * @param strength how strongly it answers the music, 0 to 1: the setting (LivingField.reach)
 * @param smoothing how softly, 0 to 1: the other setting (LivingField.ease)
 * @param colours what it is coloured from: the cover's main colours, or the cover itself
 * @param coverPlace where the cover it stands behind is, in the root's measure, or null for none.
 *   The player's own by default; the sample in Settings has a small cover of its own.
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
    coverPlace: () -> Rect? = { PlayerCoverPlace.bounds },
) {
    // The grid is made for the room the picture is given, not for the window: in the player the
    // two are the same, in the Settings sample the picture is a strip.
    BoxWithConstraints(modifier) {
        LivingPicture(cover, tap, playing, onScreen, strength, smoothing, colours, coverPlace, constraints.maxWidth, constraints.maxHeight)
    }
}

@Composable
private fun LivingPicture(
    cover: Any?,
    tap: LevelTap?,
    playing: Boolean,
    onScreen: Boolean,
    strength: Float,
    smoothing: Float,
    colours: LivingColours,
    coverPlace: () -> Rect?,
    wide: Int,
    tall: Int,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val look = remember { Look() }
    // Where this picture itself is, and how far it is stretched, to bring the cover's place into
    // the small picture's own measure.
    var origin by remember { mutableStateOf(Offset.Zero) }
    val stretch = remember { mutableStateOf(Offset(1f, 1f)) }

    // Three patches along the short side and as many along the long side as keeps them round. A
    // picture that changes shape starts over with the new grid.
    val upright = tall >= wide
    val along = LivingField.along(long = max(wide, tall).toFloat(), short = min(wide, tall).toFloat())
    val motion = remember(upright, along) {
        if (upright) LivingMotion(LivingField.ACROSS, along) else LivingMotion(along, LivingField.ACROSS)
    }

    val coverThere = coverPlace() != null
    motion.strength = strength
    motion.smoothing = smoothing
    motion.coverThere = coverThere
    motion.separate = colours == LivingColours.MAIN

    // Read by the drawing alone, so a new frame redraws the canvas and recomposes nothing.
    var frame by remember { mutableLongStateOf(0L) }
    // Counts the covers, so a cover that changes while nothing moves starts the frames again.
    var covers by remember { mutableIntStateOf(0) }

    LaunchedEffect(cover, motion, colours) {
        // the patches, and for the main colours the bass's own, which the glow on the edges takes
        val (patches, glow) = withContext(coilCoroutine) {
            val bitmap = context.imageLoader.execute(
                ImageRequest.Builder(context)
                    .data(cover)
                    .allowHardware(false)
                    .build()
            ).image?.toBitmap() ?: return@withContext null
            val pixels = IntArray(bitmap.width * bitmap.height)
            bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
            when (colours) {
                LivingColours.MAIN -> LivingField.mainColours(pixels, bitmap.width, bitmap.height).let { mains ->
                    LivingField.mainPatches(mains, motion.columns, motion.rows) to mains[MusicLevels.BASS]
                }
                LivingColours.COVER -> LivingField.patches(pixels, bitmap.width, bitmap.height, motion.columns, motion.rows) to null
            }
        } ?: return@LaunchedEffect
        motion.turnTo(patches, glow)
        covers++
    }

    // coverThere is a key because the aura comes and goes with the cover, and has to be seen to
    // do it even while nothing else moves.
    LaunchedEffect(onScreen, playing, covers, coverThere, motion, lifecycleOwner) {
        if (!onScreen) return@LaunchedEffect
        if (context.wantsStillness()) {
            motion.settle()
            frame++
            return@LaunchedEffect
        }
        lifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
            if (playing) tap?.watch()
            try {
                val levels = FloatArray(MusicLevels.BANDS)
                var last = 0L
                var shownAt = 0L
                while (playing || !motion.atRest()) {
                    withFrameNanos { now ->
                        val seconds = if (last == 0L) 0f else (now - last) / 1e9f
                        last = now
                        val heard = levels.takeIf { playing && tap?.now(it, (motion.lead() * 1_000_000).toLong()) == true }
                        motion.step(seconds, heard, playing)
                        // The picture is soft and slow: it is redrawn at most FRAMES_A_SECOND times,
                        // whatever the screen can do, because everything drawn over it that looks
                        // through it (the glass panels) is redrawn with it.
                        if (now - shownAt >= FRAME_NANOS) {
                            shownAt = now
                            frame = now
                        }
                    }
                }
                // where it came to rest, in case the last step fell between two redraws
                frame = last + 1
            } finally {
                if (playing) tap?.unwatch()
            }
        }
    }

    Canvas(Modifier.fillMaxSize().onGloballyPositioned { origin = it.positionInRoot() }.drawnSmall(stretch)) {
        @Suppress("UNUSED_VARIABLE")
        val redrawnAt = frame
        // The cover's place, or where it last was while the aura fades out after it.
        val coverAt = coverPlace()?.also { look.lastCover = it } ?: look.lastCover
        drawIntoCanvas { canvas ->
            val native = canvas.nativeCanvas
            native.drawColor(motion.under)
            val cellWidth = size.width / motion.columns
            val cellHeight = size.height / motion.rows
            for (i in motion.order) {
                val radius = motion.radius(i)
                look.disc(native, motion.x(i) * size.width, motion.y(i) * size.height, radius * cellWidth, radius * cellHeight, motion.color(i), 1f)
            }
            // A glow on the bottom edge and one on the top, half of each off the screen: the bass
            // has both ends of the picture.
            look.disc(native, size.width / 2, size.height, size.width * 0.8f, size.height * motion.glowHeight(), motion.glow, motion.glowStrength())
            look.disc(native, size.width / 2, 0f, size.width * 0.8f, size.height * motion.glowHeight() * 0.7f, motion.glow, motion.glowStrength() * 0.8f)

            if (coverAt != null && motion.auraPresence > 0.004f) {
                val by = stretch.value
                val left = (coverAt.left - origin.x) / by.x
                val top = (coverAt.top - origin.y) / by.y
                val width = coverAt.width / by.x
                val height = coverAt.height / by.y
                val side = min(width, height)
                motion.coverAt(left / size.width, top / size.height, (left + width) / size.width, (top + height) / size.height)
                for (i in motion.aura.indices) {
                    val point = motion.aura[i]
                    val radius = motion.auraRadius(i) * side
                    look.disc(native, left + point.x * width, top + point.y * height, radius, radius, motion.auraColor(i), motion.auraStrength(i))
                }
            }
        }
    }
}

/**
 * Where the cover of the song that plays is on screen, in the root's measure, or null while there
 * is none to be seen (the lyrics are up, the song failed, the window is too small for a cover).
 * The cover says so itself, in Thumbnail; the living background reads it.
 *
 * One for the app, like the tour's targets and for the same reason: there is one full player on
 * screen at a time, and the two ends are too far apart in the tree to hand it down.
 */
object PlayerCoverPlace {
    var bounds by mutableStateOf<Rect?>(null)
}

/** What the drawing keeps between frames, so that a frame allocates nothing. */
private class Look {
    var lastCover: Rect? = null

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
}

/**
 * The picture is redrawn at most this often: every frame of a 60 Hz screen, every second one at
 * 120 Hz. It was 30, which is cheaper, and a kick could then wait 33 ms to be drawn at all, which
 * is the difference between on the beat and after it. A little under the frame's own time, so that
 * one is not missed by a hair.
 */
private const val FRAMES_A_SECOND = 60
private const val FRAME_NANOS = 1_000_000_000L / FRAMES_A_SECOND - 2_000_000L

/** The picture is drawn at one part in this many of its size, each way. */
private const val SHRINK = 8

/** How much the small picture is blurred before it is stretched, in its own pixels. Hides the steps of the stretching. */
private const val SMOOTHING = 2.5f

/**
 * Lays the content out [SHRINK] times smaller than the room it is given and stretches it back to
 * fill that room, through a layer of its own so that it is the small picture that gets stretched
 * and not its drawing that gets scaled. [stretched] is told by how much, each way.
 */
private fun Modifier.drawnSmall(stretched: MutableState<Offset>): Modifier = run {
    var stretch by stretched
    layout { measurable, constraints ->
        if (!constraints.hasBoundedWidth || !constraints.hasBoundedHeight) {
            val placeable = measurable.measure(constraints)
            return@layout layout(placeable.width, placeable.height) { placeable.place(0, 0) }
        }
        val width = constraints.maxWidth
        val height = constraints.maxHeight
        val small = measurable.measure(Constraints.fixed(max(1, width / SHRINK), max(1, height / SHRINK)))
        stretch = Offset(width / small.width.toFloat(), height / small.height.toFloat())
        layout(width, height) { small.place(0, 0) }
    }.graphicsLayer {
        scaleX = stretch.x
        scaleY = stretch.y
        transformOrigin = TransformOrigin(0f, 0f)
        compositingStrategy = CompositingStrategy.Offscreen
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            renderEffect = BlurEffect(SMOOTHING, SMOOTHING, TileMode.Clamp)
        }
    }
}

/** Battery saver, or animations turned off in the system's accessibility or developer settings. */
private fun Context.wantsStillness(): Boolean =
    isPowerSaver() || Settings.Global.getFloat(contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
