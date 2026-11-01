/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.widget

/**
 * Where a plan puts what it draws, in dp from the widget's top left, worked out from the plan alone
 * the way MusicWidget lays it out. It is for the test that no big widget is left mostly empty: the
 * boxes of a plan are its covers, its buttons, its Up next, and its words, and how far they reach
 * across and down is how much of the widget the plan uses.
 *
 * A plan does not know how long a title is, so words count as [WORDS_DP] wide at most, however wide
 * the column they are in: a column wide enough for a long title is not a widget that is full.
 */
internal object WidgetFill {
    const val WORDS_DP = 240

    data class Box(val name: String, val x: Int, val y: Int, val w: Int, val h: Int) {
        val right get() = x + w
        val bottom get() = y + h
    }

    /** How much of the widget's width and height the plan's boxes reach, each from 0 to 1. */
    data class Share(val across: Float, val down: Float)

    fun share(p: WidgetPlan, w: Int, h: Int, scale: Float): Share {
        val boxes = boxes(p, w, h, scale)
        if (boxes.isEmpty()) return Share(0f, 0f)
        val across = boxes.maxOf { it.right } - boxes.minOf { it.x }
        val down = boxes.maxOf { it.bottom } - boxes.minOf { it.y }
        return Share(across.toFloat() / w, down.toFloat() / h)
    }

    fun boxes(p: WidgetPlan, w: Int, h: Int, scale: Float): List<Box> {
        val out = ArrayList<Box>()
        val controlsH = if (p.play) maxOf(p.button, p.playSize) else 0
        val row = WidgetLayout.rowWidth(p)
        val t = WidgetLayout.line(p.titleSp, scale)
        val a = WidgetLayout.line(p.artistSp, scale)
        val words = (if (p.titleLines > 0) p.titleLines * t else 0) + (if (p.showArtist) a else 0)
        when (p.shape) {
            WidgetShape.NONE -> out += Box("heading", p.padH, p.padV, w - 2 * p.padH, 20)
            WidgetShape.TILE ->
                if (p.style == WidgetStyle.FULL_COVER) out += Box("cover", 0, 0, w, h)
                else out += Box("cover", p.padH, p.padV, w - 2 * p.padH, h - 2 * p.padV)
            WidgetShape.LINE ->
                if (p.style == WidgetStyle.FULL_COVER) {
                    out += Box("cover", 0, 0, w, h)
                } else {
                    var x = p.padH
                    if (p.coverW > 0) {
                        out += Box("cover", x, (h - p.coverH) / 2, p.coverW, p.coverH)
                        x += p.coverW + WidgetLayout.COVER_GAP_DP
                    }
                    if (p.titleLines > 0) {
                        val room = w - p.padH - x - (if (p.play) row + WidgetLayout.SMALL_GAP_DP else 0)
                        out += Box("words", x, (h - words) / 2, minOf(room, WORDS_DP), words)
                    }
                    if (p.play) out += Box("buttons", w - p.padH - row, (h - controlsH) / 2, row, controlsH)
                }
            WidgetShape.SIDE -> {
                var x = p.padH
                val colH = if (p.coverW > 0) p.coverH else h - 2 * p.padV
                if (p.coverW > 0) {
                    out += Box("cover", x, p.padV, p.coverW, p.coverH)
                    x += p.coverW + WidgetLayout.SIDE_GAP_DP
                }
                out += Box("words", x, p.padV, minOf(w - p.padH - x, WORDS_DP), words)
                if (p.play) out += Box("buttons", x, p.padV + colH - controlsH, row, controlsH)
            }
            WidgetShape.TOP -> {
                out += Box("cover", p.padH, p.padV, p.coverW, p.coverH)
                val y = p.padV + p.coverH + 8
                out += Box("words", p.padH, y, minOf(w - 2 * p.padH, WORDS_DP), words)
                if (p.play) out += Box("buttons", p.padH, y + words + 6, w - 2 * p.padH, controlsH)
            }
            WidgetShape.POSTER, WidgetShape.BANNER -> out += Box("cover", 0, 0, w, h)
            WidgetShape.SHELF -> {
                var y = p.padV
                out += Box("cover", p.padH, y + (p.headerHeight - p.headerCover) / 2, p.headerCover, p.headerCover)
                val x = p.padH + p.headerCover + WidgetLayout.COVER_GAP_DP
                val line = t + (if (p.showArtist) a else 0)
                out += Box("words", x, y + (p.headerHeight - line) / 2, minOf(WidgetLayout.headerWords(p, w).coerceAtLeast(0), WORDS_DP), line)
                if (p.buttonsInline && p.play) out += Box("buttons", w - p.padH - row, y + (p.headerHeight - controlsH) / 2, row, controlsH)
                y += p.headerHeight
                if (!p.buttonsInline && p.play) {
                    y += WidgetLayout.SMALL_GAP_DP
                    out += Box("buttons", p.padH, y, w - 2 * p.padH, controlsH)
                    y += controlsH
                }
                y += if (p.buttonsInline) 10 else 8
                if (p.heading) {
                    out += Box("heading", p.padH, y, minOf(w - 2 * p.padH, WORDS_DP), p.headingHeight)
                    y += p.headingHeight
                }
                covers(p, y, scale, out)
            }
            WidgetShape.GRID -> {
                var y = p.padV
                if (p.heading) {
                    out += Box("heading", p.padH, y, minOf(w - 2 * p.padH, WORDS_DP), p.headingHeight)
                    y += p.headingHeight
                }
                covers(p, y, scale, out)
            }
            WidgetShape.PANES -> {
                val colH = if (p.coverW > 0) p.coverH else h - 2 * p.padV
                val upNext = p.headingHeight + p.upNext * p.upNextRow
                val tall = maxOf(colH, upNext)
                val top = (h - tall) / 2
                if (p.style == WidgetStyle.FULL_COVER) {
                    out += Box("cover", 0, 0, w, h)
                } else {
                    var x = p.padH
                    if (p.coverW > 0) {
                        out += Box("cover", x, top + (tall - p.coverH) / 2, p.coverW, p.coverH)
                        x += p.coverW + WidgetLayout.SIDE_GAP_DP
                    }
                    val y = top + (tall - colH) / 2
                    out += Box("words", x, y, minOf(w - p.padH - p.paneW - WidgetLayout.PANE_GAP_DP - x, WORDS_DP), words)
                    if (p.play) out += Box("buttons", x, y + colH - controlsH, row, controlsH)
                }
                out += Box("up next", w - p.padH - p.paneW, top + (tall - upNext) / 2, p.paneW, upNext)
            }
        }
        return out
    }

    private fun covers(p: WidgetPlan, top: Int, scale: Float, out: MutableList<Box>) {
        val caption = if (p.captions) WidgetLayout.captionHeight(scale) else 0
        var y = top
        for (row in 0 until p.shelfRows) {
            if (row > 0) y += p.shelfGap
            val n = minOf(p.columns, p.shown - row * p.columns)
            if (n > 0) out += Box("covers", p.padH, y, n * p.tile + (n - 1) * p.shelfGap, p.tile + caption)
            y += p.tile + caption
        }
    }
}
