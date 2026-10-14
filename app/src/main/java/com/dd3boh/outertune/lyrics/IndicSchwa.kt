/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.lyrics

/**
 * The "a" that Hindi, Punjabi, Gujarati and Bengali leave unsaid, taken out before ICU writes a
 * line in Latin letters.
 *
 * In these scripts a consonant written without a vowel sign carries an "a", and ICU's
 * transliteration writes every one: दिल comes out as "dila", तुमसे as "tumasē", करता as "karatā".
 * Said, they are dil, tumse and kartā. Where the "a" is not said, a virama is put after the
 * consonant here, which is how the script itself writes a consonant with no vowel, and ICU then
 * leaves the "a" out.
 *
 * Two rules, the usual ones. The last "a" of a word is not said (दिल is dil), unless the word is
 * one syllable or ends in a cluster whose last letter is ya, ra, va, na or the retroflex na (मित्र
 * is mitra, कृष्ण krishna, but दर्द is dard). And in the middle of a word the "a" of a lone
 * consonant is not said when a vowel comes before it and a lone consonant with its vowel after
 * it, worked from the end of the word back (करता is kartā, समझना samajhnā, धड़कन dhaṛkan).
 * Bengali keeps those, so for it only the first rule applies. Like any rule of thumb it gets
 * some words wrong, Sanskrit ones above all.
 *
 * The four scripts share one layout, each in its own block of 128, so one set of offsets reads all four.
 */
internal object IndicSchwa {

    private class Block(val base: Int, val inside: Boolean)

    // Devanagari, Gurmukhi and Gujarati drop the "a" inside a word as well; Bengali only at its end.
    private val BLOCKS = listOf(Block(0x0900, true), Block(0x0A00, true), Block(0x0A80, true), Block(0x0980, false))

    private const val NUKTA = 0x3C
    private const val VIRAMA = 0x4D

    // A cluster ending in one of these keeps its last "a": ya, ra, va, na and the retroflex na.
    private val KEEPS_A = setOf(0x2F, 0x30, 0x35, 0x28, 0x23)

    private fun isConsonant(o: Int) = o in 0x15..0x39 || o in 0x58..0x5F
    private fun isVowelSign(o: Int) = o in 0x3A..0x3B || o in 0x3E..0x4C || o in 0x4E..0x4F || o in 0x55..0x57 || o in 0x62..0x63
    private fun isVowel(o: Int) = o in 0x04..0x14 || o in 0x60..0x61 || o in 0x72..0x77
    // The nasal signs and the visarga, and Gurmukhi's tippi and addak: each closes the syllable.
    private fun isCloser(o: Int) = o in 0x01..0x03 || o == 0x70 || o == 0x71
    private fun inWord(o: Int) = isConsonant(o) || isVowelSign(o) || isVowel(o) || isCloser(o) || o == NUKTA || o == VIRAMA

    private enum class Vowel { INHERENT, SIGN, LETTER, NONE }

    /** One syllable as written: where its consonants end, whether there is one of them, the last one, its vowel. */
    private class Syllable(val consonantsEnd: Int, val single: Boolean, val last: Int, val vowel: Vowel, val closed: Boolean)

    /** [text] with a virama after each consonant whose "a" is not said. Text in any other script is left as it is. */
    fun drop(text: String): String {
        val out = StringBuilder(text.length + 8)
        var i = 0
        while (i < text.length) {
            val block = BLOCKS.firstOrNull { text[i].code - it.base in 0..0x7F && inWord(text[i].code - it.base) }
            if (block == null) {
                out.append(text[i])
                i++
                continue
            }
            var end = i
            while (end < text.length && text[end].code - block.base in 0..0x7F && inWord(text[end].code - block.base)) end++
            out.append(word(text.substring(i, end), block))
            i = end
        }
        return out.toString()
    }

    private fun word(w: String, block: Block): String {
        val syllables = syllables(w, block.base) ?: return w
        if (syllables.size < 2) return w
        val dropped = BooleanArray(syllables.size)
        val last = syllables.lastIndex
        val end = syllables[last]
        if (end.vowel == Vowel.INHERENT && !end.closed && (end.single || end.last !in KEEPS_A)) dropped[last] = true
        if (block.inside) {
            for (k in last - 1 downTo 1) {
                val here = syllables[k]
                if (here.vowel != Vowel.INHERENT || !here.single || here.closed) continue
                val before = syllables[k - 1]
                val after = syllables[k + 1]
                val vowelBefore = before.vowel != Vowel.NONE && !before.closed
                val consonantAndVowelAfter = after.single &&
                    (after.vowel == Vowel.SIGN || after.vowel == Vowel.INHERENT && !dropped[k + 1])
                if (vowelBefore && consonantAndVowelAfter) dropped[k] = true
            }
        }
        if (dropped.none { it }) return w
        val out = StringBuilder(w)
        for (k in last downTo 0) if (dropped[k]) out.insert(syllables[k].consonantsEnd, (block.base + VIRAMA).toChar())
        return out.toString()
    }

    /** [w] read as syllables, or null if it holds something these rules do not know. */
    private fun syllables(w: String, base: Int): List<Syllable>? {
        val out = ArrayList<Syllable>()
        fun at(j: Int) = w[j].code - base
        var i = 0
        while (i < w.length) {
            val o = at(i)
            if (isConsonant(o)) {
                var count = 1
                var last = o
                i++
                if (i < w.length && at(i) == NUKTA) i++
                while (i + 1 < w.length && at(i) == VIRAMA && isConsonant(at(i + 1))) {
                    last = at(i + 1)
                    count++
                    i += 2
                    if (i < w.length && at(i) == NUKTA) i++
                }
                val consonantsEnd = i
                var vowel = Vowel.INHERENT
                if (i < w.length && at(i) == VIRAMA) {
                    vowel = Vowel.NONE
                    i++
                } else if (i < w.length && isVowelSign(at(i))) {
                    vowel = Vowel.SIGN
                    while (i < w.length && isVowelSign(at(i))) i++
                }
                var closed = false
                while (i < w.length && isCloser(at(i))) {
                    closed = true
                    i++
                }
                out += Syllable(consonantsEnd, count == 1, last, vowel, closed)
            } else if (isVowel(o)) {
                i++
                var closed = false
                while (i < w.length && isCloser(at(i))) {
                    closed = true
                    i++
                }
                out += Syllable(i, false, -1, Vowel.LETTER, closed)
            } else {
                return null
            }
        }
        return out
    }
}
