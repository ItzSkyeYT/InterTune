/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.utils

import android.text.format.DateFormat
import java.time.DateTimeException
import java.time.format.DateTimeFormatter
import java.time.temporal.TemporalAccessor
import java.util.Locale

/**
 * A date or a time in the phone's locale, from a skeleton such as "hm", that never throws.
 *
 * The pattern comes from ICU, through DateFormat.getBestDateTimePattern, and java.time reads it.
 * The two do not know the same letters. As minSdk is under 26, java.time in this app is
 * desugar_jdk_libs' copy of Java 11's on every Android version, and it has no B, the day period
 * ("in the afternoon"), which ICU's time patterns carry in some locales and versions: ofPattern
 * throws on it. [forJavaTime] writes the day period as AM/PM, the nearest letter java.time has,
 * and a pattern it still cannot read, or a value it cannot print, gives [fallback] instead.
 * [bestPattern] is ICU's by default; tests give their own.
 */
class LocaleDateFormat(
    locale: Locale,
    skeleton: String,
    fallback: String,
    bestPattern: (Locale, String) -> String? = { l, s -> DateFormat.getBestDateTimePattern(l, s) },
) {
    private val fallbackFormatter = DateTimeFormatter.ofPattern(fallback, locale)

    private val formatter = runCatching { bestPattern(locale, skeleton) }.getOrNull()
        ?.let(::forJavaTime)
        ?.let {
            try {
                DateTimeFormatter.ofPattern(it, locale)
            } catch (_: IllegalArgumentException) {
                null
            }
        }
        ?: fallbackFormatter

    fun format(value: TemporalAccessor): String = try {
        formatter.format(value)
    } catch (_: DateTimeException) {
        fallbackFormatter.format(value)
    } catch (_: IllegalArgumentException) {
        // Text lookups behind a formatter can throw this too, for example the stand-alone
        // weekday names the desugared library asks java.text for.
        fallbackFormatter.format(value)
    }

    companion object {
        /** The pattern letters java.time knows in desugar_jdk_libs 2.1.5, which are Java 11's. */
        private const val KNOWN_LETTERS = "GuyDMLdQqYwWEecFaHkKhmsSAnNgpVvzOXxZ"

        /** Characters that are literal to ICU but not to java.time, which uses them for optional and reserved sections. */
        private const val SPECIAL = "[]{}#"

        /**
         * An ICU [pattern] as java.time reads it: the day period (B, and b for noon and midnight)
         * as AM/PM, one letter however many there were, as java.time takes only one, and [SPECIAL]
         * characters quoted. Quoted text is left as it is. Null when a letter java.time does not
         * know is left, such as U or r, the cyclic and related years of a Chinese calendar.
         */
        fun forJavaTime(pattern: String): String? {
            val out = StringBuilder(pattern.length + 4)
            var quoted = false
            for (c in pattern) {
                when {
                    c == '\'' -> {
                        quoted = !quoted
                        out.append(c)
                    }
                    quoted -> out.append(c)
                    c in SPECIAL -> out.append('\'').append(c).append('\'')
                    c !in 'A'..'Z' && c !in 'a'..'z' -> out.append(c)
                    c == 'a' || c == 'b' || c == 'B' -> if (out.lastOrNull() != 'a') out.append('a')
                    c in KNOWN_LETTERS -> out.append(c)
                    else -> return null
                }
            }
            return out.toString()
        }
    }
}
