/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.lyrics

import android.icu.text.BreakIterator
import android.icu.text.Transliterator
import android.icu.util.ULocale
import android.os.Build
import androidx.annotation.RequiresApi

/**
 * The phone's ICU for [LyricsLatin], or null below Android 10, which has no Transliterator in
 * android.icu. ICU4J itself is not in the build for those: it would add megabytes for one setting,
 * so "Show in Latin letters" is not offered there.
 */
fun latinTools(): LatinTools? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) IcuLatinTools else null

/**
 * android.icu's transforms and Japanese word breaks. A transform is built once and kept: making
 * one reads its rules, up to 40 ms on the emulator, and every song asks for the same few. ICU's
 * objects are not to be shared between threads, so each call holds the lock.
 */
@RequiresApi(Build.VERSION_CODES.Q)
private object IcuLatinTools : LatinTools {

    private val made = HashMap<String, Transliterator?>()
    private var words: BreakIterator? = null

    @Synchronized
    override fun transform(id: String, text: String): String? {
        val transliterator = if (id in made) {
            made[id]
        } else {
            // An id this phone's ICU does not know is remembered as missing, not asked for again.
            val t = try {
                Transliterator.getInstance(id)
            } catch (_: IllegalArgumentException) {
                null
            }
            made[id] = t
            t
        }
        return transliterator?.transliterate(text)
    }

    @Synchronized
    override fun japaneseWords(text: String): List<String>? {
        val breaks = words ?: BreakIterator.getWordInstance(ULocale.JAPANESE).also { words = it }
        breaks.setText(text)
        val out = ArrayList<String>()
        var start = breaks.first()
        var end = breaks.next()
        while (end != BreakIterator.DONE) {
            out += text.substring(start, end)
            start = end
            end = breaks.next()
        }
        return out
    }
}
