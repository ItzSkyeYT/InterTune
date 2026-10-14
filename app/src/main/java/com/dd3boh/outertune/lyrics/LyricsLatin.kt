/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.lyrics

import java.text.Normalizer

/**
 * What [LyricsLatin] needs from ICU, which Android has from Android 10 (android.icu.text). Kept
 * behind this so that every rule in LyricsLatin runs in a unit test, where there is no android.icu.
 * The phone's is IcuLatinTools.
 */
interface LatinTools {
    /** [text] through ICU's transform [id], or null where the phone does not have that transform. */
    fun transform(id: String, text: String): String?

    /**
     * [text] cut into words by ICU's Japanese dictionary: the words in order, spaces and
     * punctuation as words of their own, together the whole of [text]. Null where there is none.
     */
    fun japaneseWords(text: String): List<String>?
}

/** The script a line of lyrics is in, as far as writing it in Latin letters goes. */
enum class LineScript {
    /** No letters at all: an empty line, punctuation, numbers, a note sign. */
    NONE,

    /** Mostly Latin letters already, accented ones included. */
    LATIN,
    JAPANESE,
    KOREAN,
    CHINESE,
    CYRILLIC,
    GREEK,
    DEVANAGARI,

    /** Bengali, Gurmukhi, Gujarati, Oriya, Tamil, Telugu, Kannada and Malayalam. */
    INDIC,
    ARMENIAN,
    GEORGIAN,

    /** One with no Latin version here: Thai, Arabic, Hebrew and the rest. See [LyricsLatin.latinOf]. */
    OTHER,
}

/** The Cyrillic languages told apart, by the transform that writes each in Latin letters. */
enum class Cyrillic(val transform: String) {
    RUSSIAN("Russian-Latin/BGN"),
    UKRAINIAN("Ukrainian-Latin/BGN"),
    BELARUSIAN("Belarusian-Latin/BGN"),
    SERBIAN("Serbian-Latin/BGN"),
    MACEDONIAN("Macedonian-Latin/BGN"),
}

/** What the whole of a song's lyrics says about each of its lines. */
data class SongScripts(
    /** Kana in enough of its lines that one in kanji alone is Japanese too, not Chinese. */
    val japanese: Boolean = false,
    /** The language its Cyrillic lines are in, for those that do not show it themselves. */
    val cyrillic: Cyrillic = Cyrillic.RUSSIAN,
)

/**
 * Lyrics in Latin letters ("Show in Latin letters" in Settings > Lyrics): a line written in a
 * script the listener may not read gets a second line under it, in Latin letters.
 *
 * Which script a line is in decides how, and each was picked from what ICU gives on a phone
 * (experiments/features/lyrics-latin/probe has every result):
 *  - Japanese: ICU's Hiragana-Latin and Katakana-Latin, on the kana only, word by word as ICU's
 *    dictionary cuts the line. Kanji are left as they are: a kanji has several readings and only
 *    a dictionary of words tells which one a line uses (kuromoji's is over ten megabytes), so a
 *    line of kanji alone gets no Latin line at all.
 *  - Korean: the Revised Romanization, with the sound rules, in [HangulLatin]. ICU's
 *    Hangul-Latin spells letter by letter (salanghae for 사랑해).
 *  - Chinese: ICU's Han-Latin, which is pinyin, with the tone marks taken off. In a song the
 *    tune sets the pitch, so they do not help anyone sing; ICU reads each character on its own,
 *    so for one with two readings the mark can be wrong as well (了解 is liǎojiě, ICU says le
 *    jiě); and plain letters read better at the size the line is shown. The ü stays: it is a
 *    vowel of its own, not a tone.
 *  - Cyrillic: ICU's BGN transform of the language, Russian unless the letters say Ukrainian,
 *    Belarusian, Serbian or Macedonian. The marks for the soft and hard signs are dropped, and
 *    ë is written yo, the way most people write Russian in Latin letters.
 *  - Greek: ICU's Greek-Latin/UNGEGN, which is the standard Greece itself uses, without the line
 *    it puts under the letters for η and ω. The stress accents stay.
 *  - Devanagari and the other Indian scripts: ICU's ISO 15919, with the "a" that is not said
 *    taken out first ([IndicSchwa]), then plain letters for its marks: sh, ch, chh, n.
 *  - Armenian (BGN) and Georgian: ICU's, without its apostrophes.
 *  - Thai, Lao, Khmer, Arabic, Hebrew: nothing. ICU writes them letter by letter, and that does
 *    not say how they are read: Thai comes out as "khxbkhun" for khop khun, and Arabic and
 *    Hebrew, written without their short vowels as lyrics are, as consonants alone ("ḥbyby").
 */
object LyricsLatin {

    /**
     * The Latin version of each line of [texts] that has one, by its text, trimmed. A text with
     * line breaks, unsynced lyrics as one block or a synced line over two, is a line each. A line
     * that comes back, a chorus, is worked out once.
     */
    fun of(texts: List<String>, tools: LatinTools): Map<String, String> {
        val lines = texts.asSequence().flatMap { it.split('\n') }.map { it.trim() }.filter { it.isNotEmpty() }.distinct().toList()
        val song = songScripts(lines)
        val out = HashMap<String, String>()
        for (line in lines) latinOf(line, song, tools)?.let { out[line] = it }
        return out
    }

    /**
     * [text]'s lines, each with its Latin version from [latin] or null, or null when none of
     * them has one, so that a text with nothing to add is shown as it always was.
     */
    fun under(text: String, latin: Map<String, String>): List<Pair<String, String?>>? {
        if (latin.isEmpty()) return null
        val lines = text.split('\n').map { it to latin[it.trim()] }
        return lines.takeIf { pairs -> pairs.any { it.second != null } }
    }

    /** What all of [lines] say together: whether the song is Japanese, and which Cyrillic it is in. */
    fun songScripts(lines: List<String>): SongScripts {
        var kana = 0
        var hanOrKana = 0
        val cyrillic = HashSet<Cyrillic>()
        for (line in lines) {
            val hasKana = line.any { isKana(it) }
            if (hasKana || line.any { Character.UnicodeScript.of(it.code) == Character.UnicodeScript.HAN }) hanOrKana++
            if (hasKana) kana++
            cyrillicOf(line)?.let(cyrillic::add)
        }
        return SongScripts(
            // Japanese has kana in nearly every line, Chinese in none. A quarter leaves room for a
            // Japanese song with many short lines of kanji, and for a Chinese one quoting a Japanese line.
            japanese = kana > 0 && kana * 4 >= hanOrKana,
            // The rarer letter decides for the whole song: Belarusian shares і with Ukrainian, and
            // Macedonian shares љ, њ, џ and ј with Serbian.
            cyrillic = CYRILLIC_ORDER.firstOrNull { it in cyrillic } ?: Cyrillic.RUSSIAN,
        )
    }

    /**
     * The script [line] is mostly in. Kana, kanji and hangul count double, since each stands for
     * a syllable, about two Latin letters' worth, so "Baby 사랑해" is Korean and "I love you 사랑해"
     * is Latin. A line with any kana is Japanese; one of kanji alone is Chinese, unless [song] says
     * the song is Japanese.
     */
    fun scriptOf(line: String, song: SongScripts = SongScripts()): LineScript {
        var latin = 0
        var kana = 0
        var han = 0
        var hangul = 0
        val others = HashMap<LineScript, Int>()
        var i = 0
        while (i < line.length) {
            val cp = line.codePointAt(i)
            i += Character.charCount(cp)
            if (!Character.isLetter(cp)) continue
            when (val script = Character.UnicodeScript.of(cp)) {
                Character.UnicodeScript.LATIN -> latin++
                Character.UnicodeScript.HIRAGANA, Character.UnicodeScript.KATAKANA -> kana++
                Character.UnicodeScript.HAN -> han++
                Character.UnicodeScript.HANGUL -> hangul++
                Character.UnicodeScript.COMMON, Character.UnicodeScript.INHERITED, Character.UnicodeScript.UNKNOWN -> Unit
                else -> {
                    val group = when (script) {
                        Character.UnicodeScript.CYRILLIC -> LineScript.CYRILLIC
                        Character.UnicodeScript.GREEK -> LineScript.GREEK
                        Character.UnicodeScript.DEVANAGARI -> LineScript.DEVANAGARI
                        Character.UnicodeScript.BENGALI, Character.UnicodeScript.GURMUKHI, Character.UnicodeScript.GUJARATI,
                        Character.UnicodeScript.ORIYA, Character.UnicodeScript.TAMIL, Character.UnicodeScript.TELUGU,
                        Character.UnicodeScript.KANNADA, Character.UnicodeScript.MALAYALAM -> LineScript.INDIC
                        Character.UnicodeScript.ARMENIAN -> LineScript.ARMENIAN
                        Character.UnicodeScript.GEORGIAN -> LineScript.GEORGIAN
                        else -> LineScript.OTHER
                    }
                    others[group] = (others[group] ?: 0) + 1
                }
            }
        }
        val east = 2 * (kana + han + hangul)
        val rest = others.values.sum()
        if (east + rest == 0) return if (latin == 0) LineScript.NONE else LineScript.LATIN
        if (latin >= east + rest) return LineScript.LATIN
        return when {
            kana > 0 -> LineScript.JAPANESE
            hangul > 0 -> LineScript.KOREAN
            2 * han >= rest -> if (song.japanese) LineScript.JAPANESE else LineScript.CHINESE
            else -> others.maxBy { it.value }.key
        }
    }

    /** [line] in Latin letters, or null when it is in Latin letters already or has no Latin version here. */
    fun latinOf(line: String, song: SongScripts, tools: LatinTools): String? {
        val text = line.trim()
        val latin = when (scriptOf(text, song)) {
            LineScript.NONE, LineScript.LATIN, LineScript.OTHER -> return null
            LineScript.JAPANESE -> japanese(Normalizer.normalize(text, Normalizer.Form.NFKC), tools)
            // NFC, not NFKC: that would turn ㅋㅋ, written as letters on their own, into the parts of a syllable.
            LineScript.KOREAN -> HangulLatin.of(Normalizer.normalize(text, Normalizer.Form.NFC))
            LineScript.CHINESE -> tools.transform("Han-Latin", Normalizer.normalize(text, Normalizer.Form.NFKC))?.let(::withoutTones)
            LineScript.CYRILLIC -> cyrillic(text, song, tools)
            LineScript.GREEK -> (tools.transform("Greek-Latin/UNGEGN", text) ?: tools.transform("Greek-Latin", text))
                ?.let { without(it, UNDERLINE) }
            LineScript.DEVANAGARI -> tools.transform("Devanagari-Latin", IndicSchwa.drop(text))?.let(::plainIndic)
            LineScript.INDIC -> tools.transform("Any-Latin", IndicSchwa.drop(text))?.let(::plainIndic)
            LineScript.ARMENIAN -> tools.transform("Armenian-Latin/BGN", text)?.replace("\u2019", "")
            LineScript.GEORGIAN -> tools.transform("Georgian-Latin", text)?.replace("\u02BC", "")
        } ?: return null
        val tidy = tidy(latin)
        return tidy.takeIf { it != text && it.any(::isLatinLetter) }
    }

    // ---- Japanese ----

    private const val KANA_TO_LATIN = "Hiragana-Latin; Katakana-Latin"

    // The particles written は and へ, said wa and e. The dictionary gives them as words of their own.
    private val SAID_OTHERWISE = mapOf("は" to "wa", "へ" to "e", "こんにちは" to "konnichiwa", "こんばんは" to "konbanwa")

    // A word never starts with these: a small tsu, the long vowel mark, a small kana, a repeat mark.
    private const val ENDS_A_WORD = "っッーゃゅょぁぃぅぇぉゎャュョァィゥェォヮゝゞヽヾ\u3099\u309A"

    // One hiragana that is a word of its own far more often than the start of one.
    private const val PARTICLES = "はがをにでとものへやかなねよぞさわば"

    private fun isKana(c: Char): Boolean {
        if (c == 'ヵ' || c == 'ヶ') return false // counters, written with kanji: 三ヶ月
        val script = Character.UnicodeScript.of(c.code)
        return script == Character.UnicodeScript.HIRAGANA || script == Character.UnicodeScript.KATAKANA ||
            c in "ー\u3099\u309A"
    }

    private fun isHiragana(c: Char) = Character.UnicodeScript.of(c.code) == Character.UnicodeScript.HIRAGANA

    private fun japanese(text: String, tools: LatinTools): String? {
        val words = joinWords(tools.japaneseWords(text) ?: scriptRuns(text))
        val out = ArrayList<String>()
        for (word in words) {
            val w = word.trim()
            if (w.isEmpty()) continue
            out += SAID_OTHERWISE[w] ?: (kanaInLatin(w, tools) ?: return null)
        }
        return out.joinToString(" ")
    }

    /**
     * ICU's dictionary cuts some words where nobody would: a verb's ending (待|って), a long vowel
     * (す|ご|ー|い). A piece that cannot start a word goes back onto the one before it, as does a
     * lone hiragana after a long vowel mark, and a lone hiragana that is not a particle takes the
     * hiragana after it (い|て is ite).
     */
    internal fun joinWords(words: List<String>): List<String> {
        val out = ArrayList<String>()
        for (word in words) {
            if (word.isEmpty()) continue
            val previous = out.lastOrNull()
            if (previous != null && previous.isNotBlank() && (isKana(previous.last()) || isHan(previous.last()))) {
                val loneHiragana = previous.length == 1 && isHiragana(previous[0]) && previous[0] !in PARTICLES
                val stretched = previous.last() == 'ー' && word.length == 1 && isHiragana(word[0]) && word[0] !in PARTICLES
                if (word.first() in ENDS_A_WORD || stretched || loneHiragana && isHiragana(word.first())) {
                    out[out.lastIndex] = previous + word
                    continue
                }
            }
            out += word
        }
        return out
    }

    private fun isHan(c: Char) = Character.UnicodeScript.of(c.code) == Character.UnicodeScript.HAN || c == 'ヵ' || c == 'ヶ'

    /**
     * Without the dictionary, the line cut where it changes from kanji to hiragana, katakana or
     * anything else, which is where words most often change too.
     */
    internal fun scriptRuns(text: String): List<String> {
        val out = ArrayList<String>()
        var start = 0
        var kind = -1
        for (i in text.indices) {
            val c = text[i]
            val k = when {
                c in "ー\u3099\u309A" -> kind
                isHan(c) -> 0
                isHiragana(c) -> 1
                isKana(c) -> 2
                c.isWhitespace() -> 3
                c.isLetterOrDigit() -> 4
                else -> 5
            }
            if (i > 0 && k != kind) {
                out += text.substring(start, i)
                start = i
            }
            kind = k
        }
        if (start < text.length) out += text.substring(start)
        return out
    }

    /** The kana of [word] in Latin letters and the rest of it as it is, or null where ICU has no kana transform. */
    private fun kanaInLatin(word: String, tools: LatinTools): String? {
        if (word.none { isKana(it) }) return word
        val out = StringBuilder()
        var i = 0
        while (i < word.length) {
            var end = i
            val kana = isKana(word[i])
            while (end < word.length && isKana(word[end]) == kana) end++
            val run = word.substring(i, end)
            if (!kana) {
                out.append(run)
            } else {
                // A small tsu that ends the run doubles nothing ("あっ" is a cut-off "a"): ICU writes "a~tsu".
                val sounds = run.trimEnd('っ', 'ッ')
                if (sounds.isNotEmpty()) out.append(kanaFixes(tools.transform(KANA_TO_LATIN, sounds) ?: return null))
            }
            i = end
        }
        return out.toString()
    }

    // A u or an i that is a syllable of its own, ウ or イ, and not the end of one such as "tsu".
    private val BARE_U = Regex("""(^|[^bcdfghjklmnprstvwxyz])u~([aeiouāīūēō])""")
    private val BARE_I = Regex("""(^|[^bcdfghjklmnprstvwxyz])i~e""")
    private val SAME_VOWEL = Regex("""([aeiou])~\1""")
    private val GLIDE = Regex("""([bcdfghjklmnprstvwz]+)[aeiou]~(y?[aeiouāīūēō])""")

    /**
     * ICU marks with "~" a small kana it could not join to the one before it: ヴァ is "vu~a",
     * ディ "de~i", ウィ "u~i", ねぇ "ne~e". Joined here as they are said: va, di, wi, nee. And
     * ICU's "n'" before an n (こんにちは, "kon'nichi") is written nn, since nothing can be misread there.
     */
    internal fun kanaFixes(s: String): String = s
        .replace(BARE_U) { it.groupValues[1] + "w" + it.groupValues[2] }
        .replace(BARE_I) { it.groupValues[1] + "ye" }
        .replace(SAME_VOWEL) { it.groupValues[1] + it.groupValues[1] }
        .replace(GLIDE) { it.groupValues[1] + it.groupValues[2] }
        .replace("~tsu", "")
        .replace("~", "")
        .replace("n'n", "nn")

    // ---- Chinese ----

    private const val TONE_MARKS = "\u0300\u0301\u0304\u030C"
    private const val UNDERLINE = "\u0331"

    /** Pinyin without its tone marks, ü kept: lǜ is lü, not lu. */
    internal fun withoutTones(s: String): String = without(s, TONE_MARKS)

    /** [s] with the combining marks in [marks] taken off whatever letter they sit on. */
    private fun without(s: String, marks: String): String =
        Normalizer.normalize(Normalizer.normalize(s, Normalizer.Form.NFD).filterNot { it in marks }, Normalizer.Form.NFC)

    // ---- Cyrillic ----

    /** The language [line] shows it is in by a letter only that language has, or null if it shows none. */
    private fun cyrillicOf(line: String): Cyrillic? = when {
        line.any { it in "ўЎ" } -> Cyrillic.BELARUSIAN
        line.any { it in "ѓќѕЃЌЅ" } -> Cyrillic.MACEDONIAN
        line.any { it in "ђћџљњјЂЋЏЉЊЈ" } -> Cyrillic.SERBIAN
        line.any { it in "їєґіЇЄҐІ" } -> Cyrillic.UKRAINIAN
        else -> null
    }

    private val CYRILLIC_ORDER = listOf(Cyrillic.BELARUSIAN, Cyrillic.MACEDONIAN, Cyrillic.SERBIAN, Cyrillic.UKRAINIAN)

    private fun cyrillic(text: String, song: SongScripts, tools: LatinTools): String? {
        // The song's language, from all of its lines; a line can still show its own in a song taken for Russian.
        val language = song.cyrillic.takeIf { it != Cyrillic.RUSSIAN } ?: cyrillicOf(text) ?: Cyrillic.RUSSIAN
        val bgn = tools.transform(language.transform, text)
        // Without BGN, ISO 9, whose accents (â for я, û for ю) are taken off.
        val latin = bgn ?: tools.transform("Cyrillic-Latin", text) ?: return null
        // The marks for the soft and the hard sign, and the dot BGN puts between two letters that could be read as one.
        val bare = latin.replace("\u02B9", "").replace("\u02BA", "").replace("\u00B7", "")
        val yo = yo(Normalizer.normalize(bare, Normalizer.Form.NFC))
        return if (bgn != null) yo else without(yo, ALL_MARKS)
    }

    private const val ALL_MARKS = "\u0300\u0301\u0302\u0303\u0304\u0306\u0307\u0308\u030C\u0323"
    private val YO_AFTER_HUSH = Regex("(zh|ch|sh)ë", RegexOption.IGNORE_CASE)

    /** BGN's ë written as people write it: yo, or o after zh, ch and sh (жёлтый is zholtyy). */
    internal fun yo(s: String): String = s
        .replace(YO_AFTER_HUSH) { it.groupValues[1] + "o" }
        .replace("yë", "yo").replace("Yë", "Yo").replace("YË", "YO")
        .replace("ë", "yo").replace("Ë", "Yo")

    // ---- Indian scripts ----

    /**
     * ISO 15919, which is what ICU writes, in the letters people use for these languages: sh for
     * ś and ṣ, ch for c and chh for ch, n for the nasal marks (m before p, b and m), gy for jñ,
     * ri for the vowel ऋ, and the long vowels and dotted consonants as plain letters.
     */
    internal fun plainIndic(s: String): String {
        val d = Normalizer.normalize(s, Normalizer.Form.NFD)
            .replace("r\u0325\u0304", "ri").replace("r\u0325", "ri").replace("l\u0325", "li")
        val out = StringBuilder(d.length + 8)
        var i = 0
        fun mark(at: Int) = d.getOrNull(at)
        // The next letter, past any marks on the one at [at].
        fun nextLetter(at: Int): Char? {
            var j = at + 1
            while (j < d.length && Character.getType(d[j]) == Character.NON_SPACING_MARK.toInt()) j++
            return d.getOrNull(j)
        }
        while (i < d.length) {
            val c = d[i]
            val m = mark(i + 1)
            when {
                (c == 's' || c == 'S') && (m == '\u0301' || m == '\u0323') -> { out.append(if (c == 's') "sh" else "Sh"); i++ }
                (c == 'm' || c == 'M') && (m == '\u0307' || m == '\u0323' || m == '\u0310') -> {
                    val labial = nextLetter(i)?.lowercaseChar().let { it == 'p' || it == 'b' || it == 'm' }
                    out.append(if (labial) "m" else "n"); i++
                }
                (c == 'g' || c == 'G') && m == '\u0307' -> { out.append(if (c == 'g') "gh" else "Gh"); i++ }
                (c == 'l' || c == 'L') && m == '\u0331' -> { out.append(if (c == 'l') "zh" else "Zh"); i++ }
                (c == 'j' || c == 'J') && d.getOrNull(i + 1) == 'n' && d.getOrNull(i + 2) == '\u0303' -> {
                    out.append(if (c == 'j') "gy" else "Gy"); i += 2
                }
                c == 'c' || c == 'C' -> {
                    val big = c == 'C'
                    if (d.getOrNull(i + 1) == 'h') { out.append(if (big) "Chh" else "chh"); i++ } else out.append(if (big) "Ch" else "ch")
                }
                c == '\'' -> Unit
                else -> out.append(c)
            }
            i++
        }
        return Normalizer.normalize(
            out.filterNot { Character.getType(it) == Character.NON_SPACING_MARK.toInt() }.toString(),
            Normalizer.Form.NFC,
        )
    }

    // ---- every script ----

    private val PUNCTUATION = listOf(
        "、" to ", ", "。" to ". ", "，" to ", ", "．" to ". ", "！" to "! ", "？" to "? ", "：" to ": ", "；" to "; ",
        "「" to " “", "」" to "” ", "『" to " “", "』" to "” ", "（" to " (", "）" to ") ", "【" to " [", "】" to "] ",
        "《" to " «", "》" to "» ", "・" to " ", "\u3000" to " ", "〜" to "~", "～" to "~",
    )
    private val SPACES = Regex("""\s+""")
    private val SPACE_BEFORE = Regex(""" +([,.!?:;…)\]”’»~])""")
    private val SPACE_AFTER = Regex("""([(\[“‘«]) +""")
    private val NO_SPACE_AFTER = Regex("""([,!?;:])(?=\p{L})""")

    /** CJK punctuation as Latin punctuation, and one space between words, none before a comma. */
    internal fun tidy(s: String): String {
        var t = s
        for ((from, to) in PUNCTUATION) t = t.replace(from, to)
        t = SPACES.replace(t, " ").trim()
        t = SPACE_BEFORE.replace(t) { it.groupValues[1] }
        t = SPACE_AFTER.replace(t) { it.groupValues[1] }
        t = NO_SPACE_AFTER.replace(t) { it.groupValues[1] + " " }
        return t
    }

    private fun isLatinLetter(c: Char) = c.isLetter() && Character.UnicodeScript.of(c.code) == Character.UnicodeScript.LATIN
}
