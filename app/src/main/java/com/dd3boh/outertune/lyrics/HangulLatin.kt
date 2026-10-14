/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.lyrics

/**
 * Korean in Latin letters the way it is said: the Revised Romanization of Korean with the sound
 * rules it writes down, worked out from the syllables themselves.
 *
 * Not ICU's Hangul-Latin, which spells a word letter by letter: 사랑해 comes out as "salanghae",
 * 같이 as "gat-i" and 괜찮아요 as "gwaenchanh-ayo", where anyone singing along says saranghae,
 * gachi and gwaenchanayo. The rules here are the ones that change what is heard: a final consonant
 * carried over to a syllable that starts with a vowel, nasals before ㄴ and ㅁ, ㄹ after ㄹ or ㄴ,
 * ㄷ and ㅌ before 이, and ㅎ with the consonant next to it. Like the official system it does not
 * mark the doubled consonants that are heard but not written (학교 is hakgyo), and it knows no
 * exceptions that only a dictionary has. Rules apply within a word: a space starts again.
 *
 * Every Hangul syllable is one code point built from its three parts, so this needs no data.
 */
internal object HangulLatin {

    private const val FIRST = 0xAC00
    private const val LAST = 0xD7A3

    // The parts of a syllable, in the order Unicode counts them.
    private val INITIAL = arrayOf("g", "kk", "n", "d", "tt", "r", "m", "b", "pp", "s", "ss", "", "j", "jj", "ch", "k", "t", "p", "h")
    private val VOWEL = arrayOf(
        "a", "ae", "ya", "yae", "eo", "e", "yeo", "ye", "o", "wa", "wae", "oe", "yo", "u", "wo", "we", "wi", "yu", "eu", "ui", "i",
    )

    // Finals by their index: 0 none, 1 ㄱ, 2 ㄲ, 3 ㄳ, 4 ㄴ, 5 ㄵ, 6 ㄶ, 7 ㄷ, 8 ㄹ, 9 ㄺ, 10 ㄻ, 11 ㄼ, 12 ㄽ, 13 ㄾ, 14 ㄿ,
    // 15 ㅀ, 16 ㅁ, 17 ㅂ, 18 ㅄ, 19 ㅅ, 20 ㅆ, 21 ㅇ, 22 ㅈ, 23 ㅊ, 24 ㅋ, 25 ㅌ, 26 ㅍ, 27 ㅎ.

    /** How a final sounds at the end of a word or before a consonant: one of seven sounds. */
    private val FINAL = arrayOf(
        "", "k", "k", "k", "n", "n", "n", "t", "l", "k", "m", "l", "l", "l", "p", "l", "m", "p", "p", "t", "t", "ng", "t", "t", "k", "t", "p", "t",
    )

    /** A final before a syllable that starts with a vowel: what stays, and what moves over to start the next one. */
    private val CARRIED = arrayOf(
        "" to "", "" to "g", "" to "kk", "k" to "s", "" to "n", "n" to "j", "" to "n", "" to "d", "" to "r", "l" to "g",
        "l" to "m", "l" to "b", "l" to "s", "l" to "t", "l" to "p", "" to "r", "" to "m", "" to "b", "p" to "s", "" to "s",
        "" to "ss", "ng" to "", "" to "j", "" to "ch", "" to "k", "" to "t", "" to "p", "" to "",
    )

    // Initials and the vowel the rules look at.
    private const val G = 0
    private const val N = 2
    private const val D = 3
    private const val R = 5
    private const val M = 6
    private const val S = 9
    private const val SILENT = 11
    private const val J = 12
    private const val H = 18
    private const val VOWEL_I = 20

    // The separate letters, ㅋㅋ or ㅠㅠ written on their own: consonants, then vowels.
    private val LETTER_CONSONANTS = arrayOf(
        "g", "kk", "gs", "n", "nj", "nh", "d", "tt", "r", "lg", "lm", "lb", "ls", "lt", "lp", "lh", "m", "b", "pp", "bs",
        "s", "ss", "ng", "j", "jj", "ch", "k", "t", "p", "h",
    )

    fun isSyllable(c: Char) = c.code in FIRST..LAST

    /** [text] with every Hangul word in Latin letters and everything else as it was. */
    fun of(text: String): String {
        val out = StringBuilder(text.length * 2)
        var i = 0
        while (i < text.length) {
            val c = text[i]
            when {
                isSyllable(c) -> {
                    var end = i
                    while (end < text.length && isSyllable(text[end])) end++
                    word(text, i, end, out)
                    i = end
                }
                c.code in 0x3131..0x314E -> { out.append(LETTER_CONSONANTS[c.code - 0x3131]); i++ }
                c.code in 0x314F..0x3163 -> { out.append(VOWEL[c.code - 0x314F]); i++ }
                else -> { out.append(c); i++ }
            }
        }
        return out.toString()
    }

    private fun word(text: String, start: Int, end: Int, out: StringBuilder) {
        val n = end - start
        val initial = IntArray(n)
        val vowel = IntArray(n)
        val final = IntArray(n)
        for (k in 0 until n) {
            val s = text[start + k].code - FIRST
            initial[k] = s / 588
            vowel[k] = s % 588 / 28
            final[k] = s % 28
        }
        val onset = Array(n) { "" }
        val coda = Array(n) { "" }
        onset[0] = INITIAL[initial[0]]
        for (k in 0 until n - 1) {
            val (stays, starts) = between(final[k], initial[k + 1], vowel[k + 1])
            coda[k] = stays
            onset[k + 1] = starts
        }
        coda[n - 1] = FINAL[final[n - 1]]
        for (k in 0 until n) out.append(onset[k]).append(VOWEL[vowel[k]]).append(coda[k])
    }

    /**
     * What is heard where a syllable with [final] meets one that starts with [initial] and has
     * [vowel]: the end of the first and the start of the second.
     */
    private fun between(final: Int, initial: Int, vowel: Int): Pair<String, String> {
        val sound = FINAL[final]
        if (initial == SILENT) {
            // ㄷ and ㅌ before 이 are said as ㅈ and ㅊ: 같이 is gachi, 굳이 is guji.
            if (vowel == VOWEL_I && final == 7) return "" to "j"
            if (vowel == VOWEL_I && final == 25) return "" to "ch"
            return CARRIED[final]
        }
        if (initial == H) return when (final) {
            1, 2, 3, 24 -> "" to "k"
            9 -> "l" to "k"
            7, 25 -> "" to if (vowel == VOWEL_I) "ch" else "t"
            19, 20 -> "" to "t"
            22, 23 -> "" to "ch"
            17, 18, 26 -> "" to "p"
            27 -> "" to "h"
            else -> sound to "h"
        }
        // A final ㅎ, alone or after ㄴ or ㄹ, joins the consonant after it: 좋고 is joko, 많다 is manta.
        if (final == 27 || final == 6 || final == 15) {
            val stays = when (final) { 6 -> "n"; 15 -> "l"; else -> "" }
            when (initial) {
                G -> return stays to "k"
                D -> return stays to "t"
                J -> return stays to "ch"
                S -> return stays to "ss"
                N -> return if (final == 15) "l" to "l" else "n" to "n"
            }
        }
        // 읽고 is ilgo: ㄺ keeps its ㄹ before ㄱ.
        if (final == 9 && initial == G) return "l" to "g"
        return when (initial) {
            N, M -> when (sound) {
                "k" -> "ng" to INITIAL[initial]
                "t" -> "n" to INITIAL[initial]
                "p" -> "m" to INITIAL[initial]
                "l" -> if (initial == N) "l" to "l" else "l" to "m"
                else -> sound to INITIAL[initial]
            }
            R -> when (sound) {
                "" -> "" to "r"
                "l", "n" -> "l" to "l"
                "m", "ng" -> sound to "n"
                "k" -> "ng" to "n"
                "t" -> "n" to "n"
                "p" -> "m" to "n"
                else -> sound to "r"
            }
            else -> sound to INITIAL[initial]
        }
    }
}
