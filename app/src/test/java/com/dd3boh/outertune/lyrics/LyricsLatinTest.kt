/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.lyrics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Show in Latin letters: which script a line is in, and what goes under it ([LyricsLatin]).
 *
 * The JVM has no android.icu, so ICU is played here by [Icu], which knows only the answers it is
 * given and fails on any other question, so every test also says what ICU is asked. The answers
 * are what ICU gave for the same text on an Android 17 emulator
 * (experiments/features/lyrics-latin/probe). Every line is invented.
 */
class LyricsLatinTest {

    private class Icu(
        private val answers: Map<Pair<String, String>, String> = emptyMap(),
        private val words: Map<String, List<String>> = emptyMap(),
        private val missing: Set<String> = emptySet(),
    ) : LatinTools {
        val asked = mutableListOf<Pair<String, String>>()

        override fun transform(id: String, text: String): String? {
            asked += id to text
            if (id in missing) return null
            return answers[id to text] ?: throw AssertionError("ICU was not expected to be asked $id for \"$text\"")
        }

        override fun japaneseWords(text: String): List<String>? = words[text]
    }

    private val kana = "Hiragana-Latin; Katakana-Latin"

    // ---- which script ----

    @Test
    fun `kana, kanji, hangul and the other scripts are told apart`() {
        assertEquals(LineScript.JAPANESE, LyricsLatin.scriptOf("あさのひかりがまどにとどく"))
        assertEquals(LineScript.JAPANESE, LyricsLatin.scriptOf("コーヒーとパンケーキ"))
        assertEquals(LineScript.JAPANESE, LyricsLatin.scriptOf("朝の光が窓に届く"))
        assertEquals(LineScript.CHINESE, LyricsLatin.scriptOf("早晨的阳光照进窗户"))
        assertEquals(LineScript.CHINESE, LyricsLatin.scriptOf("我們的愛不會改變"))
        assertEquals(LineScript.KOREAN, LyricsLatin.scriptOf("아침 햇살이 창문에 닿아"))
        assertEquals(LineScript.KOREAN, LyricsLatin.scriptOf("사랑 愛"))
        assertEquals(LineScript.CYRILLIC, LyricsLatin.scriptOf("Утренний свет приходит в окно"))
        assertEquals(LineScript.GREEK, LyricsLatin.scriptOf("Καλημέρα κόσμε"))
        assertEquals(LineScript.DEVANAGARI, LyricsLatin.scriptOf("नमस्ते दुनिया"))
        assertEquals(LineScript.INDIC, LyricsLatin.scriptOf("வணக்கம் நண்பா"))
        assertEquals(LineScript.INDIC, LyricsLatin.scriptOf("আমি তোমাকে ভালোবাসি"))
        assertEquals(LineScript.ARMENIAN, LyricsLatin.scriptOf("Բարեւ աշխարհ"))
        assertEquals(LineScript.GEORGIAN, LyricsLatin.scriptOf("გამარჯობა მეგობარო"))
        assertEquals(LineScript.OTHER, LyricsLatin.scriptOf("สวัสดีครับ"))
        assertEquals(LineScript.OTHER, LyricsLatin.scriptOf("حبيبي"))
        assertEquals(LineScript.OTHER, LyricsLatin.scriptOf("שלום עולם"))
    }

    @Test
    fun `a line with kana is Japanese, and one of kanji alone is Chinese unless the song is Japanese`() {
        assertEquals(LineScript.CHINESE, LyricsLatin.scriptOf("東京夜空"))
        assertEquals(LineScript.JAPANESE, LyricsLatin.scriptOf("東京夜空", SongScripts(japanese = true)))
        // One kana among kanji is enough: no Chinese line has any.
        assertEquals(LineScript.JAPANESE, LyricsLatin.scriptOf("東京駅前の夜"))
    }

    @Test
    fun `Latin letters, accents and all, are Latin, and a line without letters is nothing`() {
        for (line in listOf("Hello world", "Café, naïve, Ærø, Straße", "Tiếng Việt rất hay", "Ça va, ñandú")) {
            assertEquals(line, LineScript.LATIN, LyricsLatin.scriptOf(line))
        }
        for (line in listOf("", "   ", "123 456", "!!! ... ???", "♪ ♪ ♪", "(…)", "ー")) {
            assertEquals("\"$line\"", LineScript.NONE, LyricsLatin.scriptOf(line))
        }
    }

    @Test
    fun `a line is what most of it is, a syllable counting as two letters`() {
        assertEquals(LineScript.KOREAN, LyricsLatin.scriptOf("Baby 사랑해"))
        assertEquals(LineScript.LATIN, LyricsLatin.scriptOf("I love you 사랑해"))
        assertEquals(LineScript.JAPANESE, LyricsLatin.scriptOf("Oh 好き"))
        assertEquals(LineScript.LATIN, LyricsLatin.scriptOf("Hey hey hey 君"))
        assertEquals(LineScript.CYRILLIC, LyricsLatin.scriptOf("OK, моя любовь"))
        assertEquals(LineScript.LATIN, LyricsLatin.scriptOf("Nothing else matters, любовь"))
    }

    @Test
    fun `a song with kana in most lines is Japanese, one quoting a single Japanese line is not`() {
        val japanese = listOf("永遠", "きっと明日も晴れるよ", "朝の光が窓に届く", "東京の夜空")
        assertTrue(LyricsLatin.songScripts(japanese).japanese)
        val chinese = listOf("早晨的阳光照进窗户", "我们一起走吧", "绿色的旅行", "我們的愛不會改變", "你好 world", "きみ")
        assertFalse(LyricsLatin.songScripts(chinese).japanese)
        assertFalse(LyricsLatin.songScripts(listOf("Hello", "안녕")).japanese)
    }

    @Test
    fun `the rarer Cyrillic letters decide the song's language`() {
        assertEquals(Cyrillic.RUSSIAN, LyricsLatin.songScripts(listOf("Здравствуй, моя любовь", "Щука")).cyrillic)
        assertEquals(Cyrillic.UKRAINIAN, LyricsLatin.songScripts(listOf("Добрий ранок", "Їжак і ґанок, Євген")).cyrillic)
        assertEquals(Cyrillic.BELARUSIAN, LyricsLatin.songScripts(listOf("Гэта ўсё мая зямля", "і ён")).cyrillic)
        assertEquals(Cyrillic.SERBIAN, LyricsLatin.songScripts(listOf("Ђорђе, љубав, њива, џеп, ћерка")).cyrillic)
    }

    // ---- what goes under it ----

    @Test
    fun `Latin lines and lines without letters get nothing, and ICU is never asked`() {
        val icu = Icu()
        val latin = LyricsLatin.of(listOf("Hello world", "Café au lait\nTiếng Việt rất hay", "", "   ", "123", "♪ ♪"), icu)
        assertTrue(latin.isEmpty())
        assertTrue(icu.asked.isEmpty())
    }

    @Test
    fun `Thai, Arabic and Hebrew get nothing, since ICU writes them letter by letter`() {
        val icu = Icu()
        assertTrue(LyricsLatin.of(listOf("สวัสดีครับ", "حبيبي", "שלום עולם", "ສະບາຍດີ"), icu).isEmpty())
        assertTrue(icu.asked.isEmpty())
    }

    @Test
    fun `Japanese kana go into Latin letters word by word, kanji stay, and the particles read wa and e`() {
        val line = "ぼくは駅へ行く、きみを待つ"
        val icu = Icu(
            answers = mapOf(kana to "ぼく" to "boku", kana to "く" to "ku", kana to "きみ" to "kimi", kana to "を" to "wo", kana to "つ" to "tsu"),
            // The words as ICU's dictionary cut this line on the emulator.
            words = mapOf(line to listOf("ぼく", "は", "駅", "へ", "行く", "、", "きみ", "を", "待つ")),
        )
        assertEquals(mapOf(line to "boku wa 駅 e 行ku, kimi wo 待tsu"), LyricsLatin.of(listOf(line), icu))
    }

    @Test
    fun `without ICU's dictionary a Japanese line is cut where the script changes`() {
        val icu = Icu(answers = mapOf(kana to "の" to "no", kana to "が" to "ga", kana to "に" to "ni", kana to "く" to "ku"))
        assertEquals("朝 no 光 ga 窓 ni 届 ku", LyricsLatin.of(listOf("朝の光が窓に届く"), icu)["朝の光が窓に届く"])
    }

    @Test
    fun `a Japanese line of kanji alone gets nothing, since kanji are left as they are`() {
        val icu = Icu()
        val song = listOf("永遠", "東京夜空", "きっと")
        val latin = LyricsLatin.of(song, Icu(answers = mapOf(kana to "きっと" to "kitto")))
        assertEquals(mapOf("きっと" to "kitto"), latin)
        assertNull(LyricsLatin.latinOf("永遠", SongScripts(japanese = true), icu))
        assertTrue(icu.asked.isEmpty())
    }

    @Test
    fun `small kana ICU leaves apart are joined as they are said`() {
        // ICU's own output for these, on the emulator.
        assertEquals("va vi ve vo vu", LyricsLatin.kanaFixes("vu~a vu~i vu~e vu~o vu"))
        assertEquals("ti di tu du dyu", LyricsLatin.kanaFixes("ti de~i to~u do~u de~yu"))
        assertEquals("wi we wo", LyricsLatin.kanaFixes("u~i u~e u~o"))
        assertEquals("she je che tsa", LyricsLatin.kanaFixes("she ji~e che tsu~a"))
        assertEquals("wōtā", LyricsLatin.kanaFixes("u~ōtā"))
        assertEquals("nee", LyricsLatin.kanaFixes("ne~e"))
        assertEquals("aa", LyricsLatin.kanaFixes("a~a"))
        assertEquals("konnichiha", LyricsLatin.kanaFixes("kon'nichiha"))
        assertEquals("kin'youbi", LyricsLatin.kanaFixes("kin'youbi"))
    }

    @Test
    fun `a small tsu at the end is a cut-off sound, not a doubled one`() {
        val icu = Icu(answers = mapOf(kana to "あ" to "a", kana to "え" to "e"))
        assertEquals(mapOf("あっ" to "a", "えっ！" to "e!"), LyricsLatin.of(listOf("あっ", "えっ！"), icu))
    }

    @Test
    fun `ICU's dictionary cuts are mended where no word starts`() {
        assertEquals(listOf("待って"), LyricsLatin.joinWords(listOf("待", "って")))
        assertEquals(listOf("すごーい", "！"), LyricsLatin.joinWords(listOf("す", "ご", "ー", "い", "！")))
        assertEquals(listOf("そば", "に", "いて", "、", "ずっと"), LyricsLatin.joinWords(listOf("そば", "に", "い", "て", "、", "ずっと")))
        assertEquals(listOf("ぼく", "は", "駅"), LyricsLatin.joinWords(listOf("ぼく", "は", "駅")))
        assertEquals(listOf("Baby", " ", "今夜", "は"), LyricsLatin.joinWords(listOf("Baby", " ", "今夜", "は")))
    }

    @Test
    fun `Chinese is pinyin without tone marks, ü kept, and its punctuation Latin`() {
        val icu = Icu(
            answers = mapOf(
                "Han-Latin" to "早晨的阳光照进窗户" to "zǎo chén de yáng guāng zhào jìn chuāng hù",
                "Han-Latin" to "绿色的旅行" to "lǜ sè de lǚ xíng",
                // The full width comma and exclamation mark are plain ones by the time ICU sees them.
                "Han-Latin" to "你好,世界!" to "nǐ hǎo,shì jiè!",
            ),
        )
        assertEquals(
            mapOf(
                "早晨的阳光照进窗户" to "zao chen de yang guang zhao jin chuang hu",
                "绿色的旅行" to "lü se de lü xing",
                "你好，世界！" to "ni hao, shi jie!",
            ),
            LyricsLatin.of(listOf("早晨的阳光照进窗户", "绿色的旅行", "你好，世界！"), icu),
        )
    }

    @Test
    fun `Russian is BGN without the sign marks, and ë is yo`() {
        val icu = Icu(
            answers = mapOf(
                "Russian-Latin/BGN" to "Щука, ёжик, съесть, объём" to "Shchuka, yëzhik, sʺyestʹ, obʺyëm",
                "Russian-Latin/BGN" to "Ёлка, всё, жёлтый, чёрный, шёл" to "Yëlka, vsë, zhëltyy, chërnyy, shël",
                "Russian-Latin/BGN" to "Гэта" to "G·eta",
            ),
        )
        assertEquals(
            mapOf(
                "Щука, ёжик, съесть, объём" to "Shchuka, yozhik, syest, obyom",
                "Ёлка, всё, жёлтый, чёрный, шёл" to "Yolka, vsyo, zholtyy, chornyy, shol",
                "Гэта" to "Geta",
            ),
            LyricsLatin.of(listOf("Щука, ёжик, съесть, объём", "Ёлка, всё, жёлтый, чёрный, шёл", "Гэта"), icu),
        )
    }

    @Test
    fun `a Ukrainian song is written as Ukrainian, also in its lines with no Ukrainian letter`() {
        val icu = Icu(
            answers = mapOf(
                "Ukrainian-Latin/BGN" to "Їжак і ґанок, Євген" to "Yizhak i ganok, Yevhen",
                "Ukrainian-Latin/BGN" to "Добрий ранок" to "Dobryy ranok",
            ),
        )
        assertEquals(
            mapOf("Їжак і ґанок, Євген" to "Yizhak i ganok, Yevhen", "Добрий ранок" to "Dobryy ranok"),
            LyricsLatin.of(listOf("Їжак і ґанок, Євген", "Добрий ранок"), icu),
        )
    }

    @Test
    fun `without BGN, Cyrillic falls back on ISO 9 with its accents off`() {
        val icu = Icu(
            answers = mapOf("Cyrillic-Latin" to "Здравствуй, моя любовь" to "Zdravstvuj, moâ lûbovʹ"),
            missing = setOf("Russian-Latin/BGN"),
        )
        assertEquals("Zdravstvuj, moa lubov", LyricsLatin.of(listOf("Здравствуй, моя любовь"), icu)["Здравствуй, моя любовь"])
    }

    @Test
    fun `Greek keeps its stress accents and loses the lines under eta and omega`() {
        val icu = Icu(
            answers = mapOf(
                "Greek-Latin/UNGEGN" to "Καλημέρα κόσμε" to "Kali̱méra kósme",
                "Greek-Latin/UNGEGN" to "θέλω να χορέψω" to "thélo̱ na chorépso̱",
            ),
        )
        assertEquals(
            mapOf("Καλημέρα κόσμε" to "Kaliméra kósme", "θέλω να χορέψω" to "thélo na chorépso"),
            LyricsLatin.of(listOf("Καλημέρα κόσμε", "θέλω να χορέψω"), icu),
        )
    }

    @Test
    fun `Hindi loses the a that is not said, then ISO 15919's marks`() {
        // ICU is asked the line with a virama where the a is not said, and writes it without one.
        val icu = Icu(answers = mapOf("Devanagari-Latin" to "मैं तुम्से प्यार् कर्ता हूँ" to "maiṁ tumsē pyār kartā hūm̐"))
        assertEquals(mapOf("मैं तुमसे प्यार करता हूँ" to "main tumse pyar karta hun"), LyricsLatin.of(listOf("मैं तुमसे प्यार करता हूँ"), icu))
    }

    @Test
    fun `Armenian and Georgian lose their apostrophes`() {
        val icu = Icu(
            answers = mapOf(
                "Armenian-Latin/BGN" to "Ես քեզ սիրում եմ" to "Yes k’ez sirum yem",
                "Georgian-Latin" to "მიყვარხარ" to "miqʼvarkhar",
            ),
        )
        assertEquals(
            mapOf("Ես քեզ սիրում եմ" to "Yes kez sirum yem", "მიყვარხარ" to "miqvarkhar"),
            LyricsLatin.of(listOf("Ես քեզ սիրում եմ", "მიყვარხარ"), icu),
        )
    }

    @Test
    fun `Korean needs no ICU`() {
        val icu = Icu()
        assertEquals(mapOf("괜찮아요, 같이 걸어요" to "gwaenchanayo, gachi georeoyo"), LyricsLatin.of(listOf("괜찮아요, 같이 걸어요"), icu))
        // Letters written on their own stay letters: ㅋ is not taken for the start of a syllable.
        assertEquals(mapOf("ㅋㅋㅋ ㅠㅠ" to "kkk yuyu"), LyricsLatin.of(listOf("ㅋㅋㅋ ㅠㅠ"), icu))
        assertTrue(icu.asked.isEmpty())
    }

    @Test
    fun `a transform the phone does not have gives no line`() {
        val icu = Icu(missing = setOf("Han-Latin"))
        assertTrue(LyricsLatin.of(listOf("我们一起走吧"), icu).isEmpty())
    }

    @Test
    fun `each line is worked out once, a block of unsynced lyrics line by line`() {
        val icu = Icu(answers = mapOf("Han-Latin" to "我们一起走吧" to "wǒ men yī qǐ zǒu ba", "Han-Latin" to "绿色的旅行" to "lǜ sè de lǚ xíng"))
        val latin = LyricsLatin.of(listOf("我们一起走吧", "我们一起走吧\n\n绿色的旅行\nLa la la", " 我们一起走吧 "), icu)
        assertEquals(mapOf("我们一起走吧" to "wo men yi qi zou ba", "绿色的旅行" to "lü se de lü xing"), latin)
        assertEquals(2, icu.asked.size)
    }

    @Test
    fun `a text gets its lines' Latin versions under them, and none when it has nothing to add`() {
        val latin = mapOf("我们一起走吧" to "wo men yi qi zou ba")
        assertEquals(
            listOf("我们一起走吧" to "wo men yi qi zou ba", "" to null, "La la la" to null),
            LyricsLatin.under("我们一起走吧\n\nLa la la", latin),
        )
        assertEquals(listOf(" 我们一起走吧" to "wo men yi qi zou ba"), LyricsLatin.under(" 我们一起走吧", latin))
        assertNull(LyricsLatin.under("La la la\nOh", latin))
        assertNull(LyricsLatin.under("我们一起走吧", emptyMap()))
    }

    @Test
    fun `CJK punctuation becomes Latin punctuation, spaced as Latin text is`() {
        assertEquals("ni hao, shi jie!", LyricsLatin.tidy("ni hao， shi jie！"))
        assertEquals("“konnichiwa”", LyricsLatin.tidy("「 konnichiwa 」"))
        assertEquals("sore wa... nani?", LyricsLatin.tidy("sore wa ... nani ？"))
        assertEquals("zutto~", LyricsLatin.tidy("zutto 〜"))
        assertEquals("hikari kaze", LyricsLatin.tidy("hikari・kaze"))
    }

    // ---- the Indian scripts ----

    @Test
    fun `the last a of a Hindi word and an a between a vowel and a consonant and vowel are not said`() {
        assertEquals("दिल्", IndicSchwa.drop("दिल"))
        assertEquals("प्यार्", IndicSchwa.drop("प्यार"))
        assertEquals("कर्ता", IndicSchwa.drop("करता"))
        assertEquals("तुम्से", IndicSchwa.drop("तुमसे"))
        assertEquals("कमल्", IndicSchwa.drop("कमल"))
        assertEquals("समझ्ना", IndicSchwa.drop("समझना"))
        assertEquals("धड़्कन्", IndicSchwa.drop("धड़कन"))
        assertEquals("दर्द्", IndicSchwa.drop("दर्द"))
        assertEquals("दिल् 2 दिल्।", IndicSchwa.drop("दिल 2 दिल।"))
    }

    @Test
    fun `an a is kept in one syllable words, before a cluster, after a nasal and after a cluster such as tr`() {
        for (word in listOf("न", "क", "नमस्ते", "ज़िंदगी", "कृष्ण", "मित्र", "सत्य", "नमस्ते दुनिया", "Hello")) {
            assertEquals(word, IndicSchwa.drop(word))
        }
    }

    @Test
    fun `Gurmukhi and Gujarati follow Hindi, Bengali drops only the last a`() {
        assertEquals("ਕਰ੍ਦਾ", IndicSchwa.drop("ਕਰਦਾ"))
        assertEquals("ਦਿਲ੍", IndicSchwa.drop("ਦਿਲ"))
        assertEquals("আমার্", IndicSchwa.drop("আমার"))
        assertEquals("করতে", IndicSchwa.drop("করতে"))
    }

    @Test
    fun `ISO 15919's marks become the letters people write these languages with`() {
        assertEquals("chal", LyricsLatin.plainIndic("cal"))
        assertEquals("chhor", LyricsLatin.plainIndic("chōṛ"))
        assertEquals("sham", LyricsLatin.plainIndic("śām"))
        assertEquals("vish", LyricsLatin.plainIndic("viṣ"))
        assertEquals("gyan", LyricsLatin.plainIndic("jñān"))
        assertEquals("krishna", LyricsLatin.plainIndic("kr̥ṣṇa"))
        assertEquals("ritu", LyricsLatin.plainIndic("r̥tu"))
        assertEquals("khuda", LyricsLatin.plainIndic("ḵẖudā"))
        assertEquals("gham", LyricsLatin.plainIndic("ġam"))
        assertEquals("sangit", LyricsLatin.plainIndic("saṅgīt"))
        assertEquals("chand", LyricsLatin.plainIndic("cām̐d"))
        assertEquals("sambandh", LyricsLatin.plainIndic("saṁbandh"))
        assertEquals("main hain", LyricsLatin.plainIndic("maiṁ haiṁ"))
        assertEquals("om", LyricsLatin.plainIndic("'om"))
        assertEquals("vanakkam nanpa", LyricsLatin.plainIndic("vaṇakkam naṇpā"))
        assertEquals("yah.", LyricsLatin.plainIndic("yah."))
    }
}
