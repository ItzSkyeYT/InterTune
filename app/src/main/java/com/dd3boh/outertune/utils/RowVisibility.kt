/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.utils

/** A card in a horizontal row, in the row's own coordinates. */
data class CardBox(val index: Int, val x: Int, val y: Int, val width: Int, val height: Int)

/**
 * Which cards of a row the listener can actually see: at least half of the card, both ways, inside
 * the row's own viewport and inside the screen the row is scrolling through. The row's grid only
 * knows its own viewport, so a row half pushed off the top of Home would otherwise count every
 * card in its hidden half as passed over.
 *
 * @param rowTop where the row starts, in the outer list's coordinates
 * @param screenTop the outer list's visible span, in the same coordinates
 */
fun seenSlots(
    cards: List<CardBox>,
    rowViewportStart: Int,
    rowViewportEnd: Int,
    rowTop: Int,
    screenTop: Int,
    screenBottom: Int,
): Set<Int> = cards.filter { card ->
    if (card.width <= 0 || card.height <= 0) return@filter false
    val visibleWidth = minOf(card.x + card.width, rowViewportEnd) - maxOf(card.x, rowViewportStart)
    val top = rowTop + card.y
    val visibleHeight = minOf(top + card.height, screenBottom) - maxOf(top, screenTop)
    visibleWidth * 2 >= card.width && visibleHeight * 2 >= card.height
}.map { it.index }.toSet()
