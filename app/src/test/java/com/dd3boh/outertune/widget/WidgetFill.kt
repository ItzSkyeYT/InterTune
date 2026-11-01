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
            WidgetShape.BIG_COVER, WidgetShape.CRATE, WidgetShape.RECORDS -> {
                val look = p.look ?: return out
                look.hero?.let { out += Box("cover", it.x, it.y, it.w + look.discOut, it.h) }
                for ((name, line) in lookLines(p, scale)) out += Box(name, line.x, line.y, minOf(line.w, WORDS_DP), line.h)
                p.glass?.let { out += Box("buttons", it.x, it.y, it.w, it.h) }
                look.heading?.let { out += Box("heading", it.x, it.y, minOf(it.w, WORDS_DP), it.h) }
                for (pick in look.picks) out += Box("covers", pick.x, pick.y, pick.w, pick.h)
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

    /**
     * A look's lines of words, each as wide as it may be drawn: the heading drawn over the title, the
     * title's lines, and the artist, each stopping short of a pill at the end of its line.
     */
    fun lookLines(p: WidgetPlan, scale: Float): List<Pair<String, Spot>> {
        val look = p.look ?: return emptyList()
        val words = look.words ?: return emptyList()
        val t = WidgetLayout.line(p.titleSp, scale)
        val a = WidgetLayout.line(p.artistSp, scale)
        var y = words.y
        val out = ArrayList<Pair<String, Spot>>()
        if (look.headingInWords > 0) {
            out += "heading" to Spot(words.x, y, words.w, look.headingInWords)
            y += look.headingInWords
        }
        if (p.titleLines > 0) {
            val title = Spot(words.x, y, words.w, p.titleLines * t)
            out += "title" to title.copy(w = look.lineRoom(title, p.glass))
            y += p.titleLines * t
        }
        if (p.showArtist) {
            val artist = Spot(words.x, y, words.w, a)
            out += "artist" to artist.copy(w = look.lineRoom(artist, p.glass))
        }
        return out
    }
}
