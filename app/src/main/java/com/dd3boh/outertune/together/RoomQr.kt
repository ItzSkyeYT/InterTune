/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.together

import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import kotlin.math.abs
import kotlin.math.floor

/**
 * A room's invite as a QR code, for a phone beside this one to read with its camera (his yes of
 * 17 Oct 2026). It holds [RoomCode.appLink], the link Share sends, so the camera opens the same
 * "join this room?" question a shared link does.
 */
object RoomQr {
    /**
     * The modules of the QR code of [text], row by row, true for dark. Without the quiet zone:
     * whoever draws it puts white round it. Level M, which leaves room for a scuffed screen and
     * keeps a room's link in a small, coarse code.
     *
     * The mask is ZXing's own choice, unless that leaves a spot [hasLookAlike] finds (about one
     * room in ten): then it is the first of the eight masks that leaves none. Drawn exactly,
     * about one room in a hundred could not be read by ZXing's reader, the one RoomScan uses
     * (19 Oct 2026).
     */
    fun modules(text: String): Array<BooleanArray> {
        val chosen = encode(text, mask = null)
        if (!hasLookAlike(chosen)) return chosen
        for (mask in 0 until 8) {
            val other = encode(text, mask)
            if (!hasLookAlike(other)) return other
        }
        return chosen
    }

    private fun encode(text: String, mask: Int?): Array<BooleanArray> {
        val hints = mutableMapOf<EncodeHintType, Any>(EncodeHintType.MARGIN to 0, EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M)
        if (mask != null) hints[EncodeHintType.QR_MASK_PATTERN] = mask
        val matrix = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, 0, 0, hints)
        return Array(matrix.height) { y -> BooleanArray(matrix.width) { x -> matrix.get(x, y) } }
    }

    /**
     * Whether ZXing's reader (3.5.3) could take a spot in [modules] for one of the three corner
     * squares and so miss the code, whichever way up the camera sees it. It scans rows from the
     * top and stops once it holds three squares of one size, so a spot of exactly their
     * proportions, met before the third real one, hides that one. On the rows through a square
     * with data on its left, a spot it takes either ends on the square's edge and uses the edge
     * up, or, between two squares, is confirmed before the right one and sets off a skip down the
     * picture that leaves that square seen once, and the reader throws away what it saw once. All
     * looked for with ZXing's own tests counted in modules, but for its diagonal one, which only
     * turns spots down.
     */
    internal fun hasLookAlike(modules: Array<BooleanArray>): Boolean {
        var turned = modules
        repeat(4) {
            if (lookAlikeUpright(turned)) return true
            turned = quarterTurn(turned)
        }
        return false
    }

    private fun quarterTurn(m: Array<BooleanArray>) = Array(m.size) { y -> BooleanArray(m.size) { x -> m[m.size - 1 - x][y] } }

    private fun lookAlikeUpright(m: Array<BooleanArray>): Boolean {
        val n = m.size
        // A square on the right, on a row through its middle: every five runs from the row's start
        // (or the separator of a square on the left) up to and with the square's edge.
        val left = n - 7
        for (top in intArrayOf(0, n - 7)) {
            if (!squareAt(m, left, top)) continue
            val from = if (squareAt(m, 0, top)) 7 else 0
            for (y in top + 2..top + 4) {
                val starts = (from..left).filter { x -> x == from || m[y][x] != m[y][x - 1] }
                for (j in 0 until starts.size - 4) {
                    if (!m[y][starts[j]]) continue
                    val ends = IntArray(5) { k -> starts.getOrElse(j + k + 1) { left + 1 } }
                    val runs = IntArray(5) { k -> ends[k] - starts[j + k] }
                    if (taken(m, end = ends[4], y = y, runs = runs) != null) return true
                }
            }
        }
        // Light, dark, light, three dark, light, dark, light, one module each: a square's own size.
        for (y in 0 until n) for (x in 0 until n) {
            if ((-4..4).any { dark(m, x + it, y) != (abs(it) != 2 && abs(it) != 4) }) continue
            val (cx, cy) = taken(m, end = x + 4, y = y, runs = intArrayOf(1, 1, 3, 1, 1)) ?: continue
            val real = (cx == 3 || cx == n - 4) && (cy == 3 || cy == n - 4) && squareAt(m, cx - 3, cy - 3)
            if (!real) return true
        }
        return false
    }

    /**
     * Where ZXing puts the centre of [runs], met on row [y] by a scan that reaches the light
     * module [end] after them, or null when its tests turn them down: their proportions, then the
     * column through their middle, then the row again through the middle of that column.
     */
    private fun taken(m: Array<BooleanArray>, end: Int, y: Int, runs: IntArray): Pair<Int, Int>? {
        if (!proportions(runs)) return null
        val total = runs.sum()
        val column = floor(end - runs[4] - runs[3] - runs[2] / 2f).toInt()
        val row = crossCheck(m, column, y, dx = 0, dy = 1, maxCount = runs[2], total = total, fifths = 2) ?: return null
        val centre = crossCheck(m, column, row, dx = 1, dy = 0, maxCount = runs[2], total = total, fifths = 1) ?: return null
        return centre to row
    }

    /**
     * ZXing's crossCheckVertical (dy 1) or crossCheckHorizontal (dx 1) in modules: the centre of
     * the runs along the line through ([x], [y]), or null. Each outer run at most [maxCount]
     * before the middle and under it after, the whole within [fifths] fifths of [total].
     */
    private fun crossCheck(m: Array<BooleanArray>, x: Int, y: Int, dx: Int, dy: Int, maxCount: Int, total: Int, fifths: Int): Int? {
        val runs = IntArray(5)
        var i = 0
        while (dark(m, x - i * dx, y - i * dy)) { runs[2]++; i++ }
        while (!dark(m, x - i * dx, y - i * dy) && runs[1] <= maxCount) { runs[1]++; i++ }
        if (runs[1] > maxCount) return null
        while (dark(m, x - i * dx, y - i * dy) && runs[0] <= maxCount) { runs[0]++; i++ }
        if (runs[0] > maxCount) return null
        i = 1
        while (dark(m, x + i * dx, y + i * dy)) { runs[2]++; i++ }
        while (!dark(m, x + i * dx, y + i * dy) && runs[3] < maxCount) { runs[3]++; i++ }
        if (runs[3] >= maxCount) return null
        while (dark(m, x + i * dx, y + i * dy) && runs[4] < maxCount) { runs[4]++; i++ }
        if (runs[4] >= maxCount) return null
        if (5 * abs(runs.sum() - total) >= fifths * total || !proportions(runs)) return null
        val start = if (dx == 1) x else y
        return floor(start + i - runs[4] - runs[3] - runs[2] / 2f).toInt()
    }

    /** ZXing's 1:1:3:1:1, each run within half a module of it. */
    private fun proportions(runs: IntArray): Boolean {
        if (runs.any { it == 0 }) return false
        val module = runs.sum() / 7f
        val slack = module / 2f
        return abs(module - runs[0]) < slack && abs(module - runs[1]) < slack && abs(3 * module - runs[2]) < 3 * slack &&
            abs(module - runs[3]) < slack && abs(module - runs[4]) < slack
    }

    /** Whether the 7 by 7 modules from ([x], [y]) are a corner square. */
    private fun squareAt(m: Array<BooleanArray>, x: Int, y: Int): Boolean =
        (0 until 7).all { dy -> (0 until 7).all { dx -> m[y + dy][x + dx] == (maxOf(abs(dx - 3), abs(dy - 3)) != 2) } }

    private fun dark(m: Array<BooleanArray>, x: Int, y: Int) = y in m.indices && x in m.indices && m[y][x]
}
