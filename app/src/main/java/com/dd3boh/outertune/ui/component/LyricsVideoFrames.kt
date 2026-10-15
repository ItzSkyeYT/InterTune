/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.ui.component

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.Shader
import android.graphics.Typeface
import android.text.StaticLayout
import androidx.core.graphics.createBitmap
import androidx.core.graphics.withClip
import androidx.core.graphics.withTranslation
import com.dd3boh.outertune.constants.LivingColours
import com.dd3boh.outertune.lyrics.LyricsVideoPlan
import com.dd3boh.outertune.playback.MusicLevels
import com.dd3boh.outertune.ui.player.LIVING_SHRINK
import com.dd3boh.outertune.ui.player.LivingDiscs
import com.dd3boh.outertune.ui.player.LivingField
import com.dd3boh.outertune.ui.player.LivingMotion
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * The frames of a lyrics video ([LyricsVideoPlan]), one at a time, the shape of a phone's screen:
 * the share card's header at the top and the app's mark at the foot, the lines between them
 * scrolling as they are sung, and behind it all Living blur moving with the song ([LivingVideo]),
 * or the card's colours where the player has another background.
 *
 * Asked for its frames in order, on the thread that encodes them. Allocates nothing per frame.
 */
class LyricsVideoFrames(
    context: Context,
    private val clip: LyricsVideoPlan.Clip,
    title: String,
    artists: String,
    cover: Bitmap?,
    private val living: LivingVideo?,
) {
    private val header = LyricsCard.Header(title, artists, cover)

    // Stories and statuses lay their own bars over the top and the foot of a video, so the
    // header and the mark stand clear of where those go.
    private val roomTop = CLEAR + header.height + LyricsCard.GAP
    private val markTop = HEIGHT - CLEAR - LyricsCard.LOGO
    private val roomBottom = markTop - LyricsCard.GAP

    /** Where the top of the line being sung stands. */
    private val sungAt = roomTop + (roomBottom - roomTop) * SUNG_AT

    private val bold = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
    private val linePaint = LyricsCard.paint(SIZE, bold)
    private val translationPaint = LyricsCard.paint(TRANSLATION_SIZE, Typeface.DEFAULT)
    private val inner = (WIDTH - 2 * LyricsCard.MARGIN).toInt()
    private val texts: List<StaticLayout> = clip.lines.map { LyricsCard.layout(it.text, linePaint, inner, spacing = 1.1f) }
    private val translations: List<StaticLayout?> = clip.lines.map { line ->
        line.translation?.let { LyricsCard.layout(it, translationPaint, inner, spacing = 1.05f) }
    }
    private val heights = FloatArray(clip.lines.size) { i ->
        texts[i].height + (translations[i]?.let { TRANSLATION_GAP + it.height } ?: 0f)
    }

    /** Where the top of each line is in the column of them, with the card's room between two. */
    private val tops = FloatArray(clip.lines.size).also { tops ->
        var at = 0f
        for (i in tops.indices) {
            tops[i] = at
            at += heights[i] + SIZE * 0.45f
        }
    }

    /**
     * What does not move. With the card's colours behind it that is everything but the lines,
     * drawn once and copied onto every frame; over Living blur, the header and the mark, each
     * drawn once and laid over the moving picture.
     */
    private val still: Bitmap?
    private val headerLayer: Bitmap?
    private val markLayer: Bitmap?

    init {
        if (living == null) {
            still = createBitmap(WIDTH, HEIGHT).also { bitmap ->
                val canvas = Canvas(bitmap)
                val (top, bottom) = LyricsCard.colours(cover)
                canvas.drawPaint(Paint().apply {
                    shader = LinearGradient(0f, 0f, WIDTH * 0.4f, HEIGHT.toFloat(), top, bottom, Shader.TileMode.CLAMP)
                })
                header.draw(canvas, CLEAR)
                LyricsCard.drawMark(context, canvas, LyricsCard.MARGIN, markTop)
            }
            headerLayer = null
            markLayer = null
        } else {
            still = null
            headerLayer = createBitmap(WIDTH, max(1f, header.height).roundToInt()).also { header.draw(Canvas(it), 0f) }
            markLayer = createBitmap(WIDTH, LyricsCard.LOGO.roundToInt()).also { LyricsCard.drawMark(context, Canvas(it), LyricsCard.MARGIN, 0f) }
        }
    }

    /** The frame at [ms] into the video, drawn over the whole of [canvas], which is [WIDTH] by [HEIGHT]. */
    fun draw(canvas: Canvas, ms: Long) {
        if (living != null) {
            living.draw(canvas, ms)
            canvas.drawBitmap(headerLayer!!, 0f, CLEAR, null)
            canvas.drawBitmap(markLayer!!, 0f, markTop, null)
        } else {
            canvas.drawBitmap(still!!, 0f, 0f, null)
        }

        val scroll = LyricsVideoPlan.scroll(clip, tops, ms)
        canvas.withClip(0f, roomTop, WIDTH.toFloat(), roomBottom) {
            for (i in texts.indices) {
                val top = sungAt + tops[i] - scroll
                if (top + heights[i] < roomTop || top > roomBottom) continue
                // Fading out towards the edges of the room the lines have, the past ones at the
                // top, the ones to come at the foot.
                val middle = top + heights[i] / 2
                val edge = smooth((middle - roomTop) / EDGE) * smooth((roomBottom - middle) / EDGE)
                val shown = LyricsVideoPlan.light(clip, i, ms) * edge
                if (shown < 0.004f) continue
                linePaint.alpha = (shown * 255).roundToInt()
                withTranslation(LyricsCard.MARGIN, top) { texts[i].draw(this) }
                translations[i]?.let { translation ->
                    translationPaint.alpha = (shown * 0.75f * 255).roundToInt()
                    withTranslation(LyricsCard.MARGIN, top + texts[i].height + TRANSLATION_GAP) { translation.draw(this) }
                }
            }
        }

        val shows = LyricsVideoPlan.picture(ms, clip.durationMs)
        if (shows < 1f) canvas.drawColor(Color.argb(((1f - shows) * 255).roundToInt(), 0, 0, 0))
    }

    companion object {
        const val WIDTH = LyricsCard.WIDTH
        const val HEIGHT = 1920

        /** How far the header and the mark stand from the top and the foot. */
        private const val CLEAR = 216f

        private const val SIZE = 68f
        private const val TRANSLATION_SIZE = 44f
        private const val TRANSLATION_GAP = 10f

        /** Where the line being sung stands, down from the top of the lines' room, in parts of it. */
        private const val SUNG_AT = 0.3f

        /** How far from the edges of their room the lines fade out. */
        private const val EDGE = 200f

        private fun smooth(t: Float): Float {
            val x = t.coerceIn(0f, 1f)
            return x * x * (3 - 2 * x)
        }
    }
}

/**
 * Living blur behind a lyrics video: the player's own motion and drawing ([LivingMotion],
 * [LivingDiscs]), moved on by the video's clock and by the levels measured from the clip's sound
 * ([heard], by the millisecond from the video's start), drawn small and stretched as the player
 * draws it, and darkened as the player's dark theme darkens it so that white lines read on it.
 *
 * [patches] and [mains] are the cover's colours as the player takes them (LivingField.patches,
 * LivingField.mainColours), [colours] says which the setting asks for, and [strength] and
 * [smoothing] are the two settings.
 */
class LivingVideo(
    colours: LivingColours,
    patches: IntArray?,
    mains: IntArray?,
    strength: Float,
    smoothing: Float,
    private val heard: (ms: Long) -> FloatArray?,
) {
    private val ribbons = colours == LivingColours.MAIN && mains != null
    private val across = if (ribbons) LivingField.FLOW_ACROSS else LivingField.ACROSS
    private val motion = LivingMotion(
        across,
        LivingField.along(
            long = LyricsVideoFrames.HEIGHT.toFloat(), short = LyricsVideoFrames.WIDTH.toFloat(),
            across = across, most = if (ribbons) LivingField.FLOW_MOST_ALONG else LivingField.MOST_ALONG,
        ),
    ).apply {
        this.strength = strength
        this.smoothing = smoothing
        separate = ribbons
        coverThere = false
        val three = mains
        if (ribbons && three != null) turnTo(IntArray(count) { three[MusicLevels.BASS] }, glow = three[MusicLevels.BASS], mains = three)
        else turnTo(patches ?: IntArray(count) { LivingField.GREY })
    }

    /**
     * How far ahead of the frame the levels are read: as far as the player reads them, for the
     * time a rise takes to be seen, less the frame or two it allows for the way to the screen,
     * which a video's player makes up for itself.
     */
    private val aheadMs = (max(0f, motion.lead() - 0.045f) * 1000).roundToInt()

    private val small = createBitmap(LyricsVideoFrames.WIDTH / LIVING_SHRINK, LyricsVideoFrames.HEIGHT / LIVING_SHRINK)
    private val smallCanvas = Canvas(small)
    private val discs = LivingDiscs()
    private val stretched = Paint(Paint.FILTER_BITMAP_FLAG)
    private val whole = Rect(0, 0, LyricsVideoFrames.WIDTH, LyricsVideoFrames.HEIGHT)
    private val shade = Paint().apply {
        shader = LinearGradient(
            0f, 0f, 0f, small.height.toFloat(),
            Color.argb(SHADE_TOP, 0, 0, 0), Color.argb(SHADE_FOOT, 0, 0, 0), Shader.TileMode.CLAMP,
        )
    }
    private val sound = LivingMotion.Heard { nanos -> heard((nanos - CLOCK) / 1_000_000 + aheadMs) }

    init {
        // Two seconds of music before the first frame, with nothing heard yet: it starts out
        // moving, as it is in the player by the time a line is picked, and not waking up.
        var at = CLOCK - WARM_NANOS
        while (at <= CLOCK) {
            motion.moveTo(at, true)
            at += FRAME_NANOS
        }
    }

    /** The picture at [ms] into the video, over the whole of [canvas]. */
    fun draw(canvas: Canvas, ms: Long) {
        motion.moveTo(CLOCK + ms * 1_000_000, true, sound)
        discs.field(smallCanvas, motion, small.width.toFloat(), small.height.toFloat())
        smallCanvas.drawPaint(shade)
        canvas.drawBitmap(small, null, whole, stretched)
    }

    private companion object {
        /** The motion's clock at the video's start. Anything but 0, which the motion takes for no clock at all. */
        const val CLOCK = 10_000_000_000L
        const val WARM_NANOS = 2_000_000_000L
        const val FRAME_NANOS = 1_000_000_000L / LyricsVideoPlan.FRAMES_A_SECOND

        // The player's dark theme lays black at 40% over it; a little more towards the foot,
        // where the lines to come are.
        const val SHADE_TOP = 102
        const val SHADE_FOOT = 128
    }
}

/** The cover's colours as Living blur takes them, from its pixels: the patches of it, and its three main colours, the bass's first. */
internal fun livingColoursOf(cover: Bitmap, colours: LivingColours): Pair<IntArray?, IntArray?> {
    val pixels = IntArray(cover.width * cover.height)
    cover.getPixels(pixels, 0, cover.width, 0, 0, cover.width, cover.height)
    return when (colours) {
        LivingColours.MAIN -> null to LivingField.mainColours(pixels, cover.width, cover.height)
        LivingColours.COVER -> {
            val across = LivingField.ACROSS
            val along = LivingField.along(
                long = LyricsVideoFrames.HEIGHT.toFloat(), short = LyricsVideoFrames.WIDTH.toFloat(),
                across = across, most = LivingField.MOST_ALONG,
            )
            LivingField.patches(pixels, cover.width, cover.height, across, along) to null
        }
    }
}
