/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.ui.utils

import androidx.compose.ui.unit.LayoutDirection

/**
 * The direction a line reads in, from the first of its letters that has one: left to right for
 * Latin, Cyrillic, Chinese, Japanese, Korean, right to left for Arabic, Hebrew and Persian.
 * [fallback], the app's own direction, where no letter has one: digits, punctuation, nothing.
 *
 * A song's title is in the language of the song, not of the app. Laid out in the app's direction
 * a line that scrolls starts at the far end of a title written the other way: in the Arabic app
 * the player showed "…Julian Casablancas)" for "Instant Crush (feat. Julian Casablancas)", and
 * in the English app an Arabic title showed its last words.
 */
fun readingDirection(text: String, fallback: LayoutDirection): LayoutDirection {
    var i = 0
    while (i < text.length) {
        val letter = text.codePointAt(i)
        when (Character.getDirectionality(letter)) {
            Character.DIRECTIONALITY_LEFT_TO_RIGHT -> return LayoutDirection.Ltr
            Character.DIRECTIONALITY_RIGHT_TO_LEFT,
            Character.DIRECTIONALITY_RIGHT_TO_LEFT_ARABIC -> return LayoutDirection.Rtl
        }
        i += Character.charCount(letter)
    }
    return fallback
}
