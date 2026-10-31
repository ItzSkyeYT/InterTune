/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.testerlock

import java.util.Locale

/**
 * A tester's code, as the bot hands it out: 12 symbols of Crockford's base32, shown XXXX-XXXX-XXXX.
 *
 * The app holds no secret and cannot tell a real code from a made-up one: only the door can. All it
 * does is take a code as it was typed or pasted and check its shape before sending it.
 */
object TesterCode {
    /** No I, L, O or U: the first three are read as 1, 1 and 0. */
    const val ALPHABET = "0123456789ABCDEFGHJKMNPQRSTVWXYZ"
    const val LENGTH = 12

    /**
     * The code as the door wants it: in capitals, without the spaces and dashes that only group it,
     * and with O, I and L read as 0, 1 and 1. Anything else is kept, for [isWellFormed] to refuse.
     */
    fun normalise(typed: String): String = buildString {
        for (c in typed) append(read(c))
    }

    fun isWellFormed(code: String): Boolean = code.length == LENGTH && code.all { it in ALPHABET }

    /** XXXX-XXXX-XXXX, or as much of it as there is. */
    fun shown(code: String): String = code.chunked(4).joinToString("-")

    /**
     * What one typed character is in a code: nothing for a space or a dash, which only group it, and
     * otherwise itself in capitals, with O, I and L read as 0, 1 and 1. In the root locale: in
     * Turkish, a capital i is İ, which no code has.
     */
    fun read(c: Char): String {
        if (c.isWhitespace() || c in DASHES) return ""
        return when (val upper = c.toString().uppercase(Locale.ROOT)) {
            "O" -> "0"
            "I", "L" -> "1"
            else -> upper
        }
    }

    /** Where the field draws its dashes in a code of [length] symbols: after every fourth one. */
    fun dashesBefore(length: Int): List<Int> = (4 until length step 4).toList()

    /** The longest paste that goes in the field: what the door reads as a typed code at most, and nothing more. */
    const val MOST_PASTED = 64

    /**
     * What a paste puts in the field, the way it was copied. A clipboard that holds the code alone, in any
     * form it is typed in, goes in as it is, and the field then draws it as it draws typing. One that holds
     * the bot's whole message ("Your tester code: `XXXX-XXXX-XXXX`. It opens ..."), which is what copying
     * the message on Discord gives, goes in as the code in it, shown in its groups. Anything else goes in
     * as it is, cut to [MOST_PASTED], and the gate says it is no code.
     */
    fun fromPasted(pasted: String): String {
        val whole = pasted.trim()
        if (isWellFormed(normalise(whole))) return whole
        for (found in GROUPED.findAll(whole)) {
            if (isWellFormed(normalise(found.value))) return found.value
        }
        return whole.take(MOST_PASTED)
    }

    /** Three groups of four joined by the dash a chat shows, not standing inside a longer word. */
    private val GROUPED = Regex("(?<![0-9A-Za-z])[0-9A-Za-z]{4}[$DASHES][0-9A-Za-z]{4}[$DASHES][0-9A-Za-z]{4}(?![0-9A-Za-z])")

    /** The hyphen, and the look-alikes a copy from a chat can bring. */
    private const val DASHES = "-\u2010\u2011\u2012\u2013\u2014\u2212"
}
