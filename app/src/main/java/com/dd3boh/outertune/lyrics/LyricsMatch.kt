/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.lyrics

import com.dd3boh.lrclib.models.Track
import java.text.Normalizer
import java.util.Locale
import kotlin.math.abs

/**
 * What the automatic lyrics lookup searches for, and whether an answer is the song that is playing.
 *
 * Kept free of Android and of the network so the rules can be tested on their own. Wrong lyrics are
 * worse than none: they scroll confidently out of time, or belong to another song, so every answer
 * has to name the same song, by the same artist, at nearly the same length.
 */
object LyricsMatch {

    /**
     * Timed lyrics are only taken from an entry within this many seconds of the song. The same
     * recording's length differs by a second or so between YouTube's whole seconds and the files
     * LRCLIB was fed; a different edit or a different intro differs by more, and its timings would
     * be early or late all the way through.
     */
    const val SYNCED_TOLERANCE_SEC = 3.0

    /**
     * Plain words, which have no timings to be wrong, may come from an entry a little further off,
     * but not from one so different in length that it is likely another arrangement: a sped up copy
     * of a 95 second song is 74 seconds, and an extended mix is minutes longer.
     */
    const val PLAIN_TOLERANCE_SEC = 10.0

    private val BRACKET = Regex("""\s*[(\[{（【]([^()\[\]{}（）【】]*)[)\]}）】]""")
    private val DASH_SUFFIX = Regex("""\s+[-\u2013\u2014]\s+([^-\u2013\u2014]+)$""")

    // "feat. Someone" only as far as a bracket or a dash suffix. Taking the rest of the title with it
    // turned "Song feat. X (Live)" into "Song", which then matched the studio recording.
    private val FEAT_TAIL = Regex(
        """\s+(?:feat\.?|ft\.|featuring)\s+[^(\[{\uff08\u3010]*?(?=\s*[(\[{\uff08\u3010]|\s+[-\u2013\u2014]\s|$)""",
        RegexOption.IGNORE_CASE
    )
    private val SPACES = Regex("""\s+""")
    private val COMBINING = Regex("""\p{M}+""")
    private val APOSTROPHES = Regex("""['’‘`´]""")
    private val NON_WORD = Regex("""[^\p{L}\p{N}]+""")
    private val SYNCED_LINE = Regex("""^\s*\[\d{1,3}:\d{2}(?:[.:]\d{1,3})?].*""")

    /**
     * Bracket or dash-suffix contents that describe the upload or the edit rather than the song:
     * "Video Edit", "Radio Edit", "7" Edit", "Official Music Video", "Lyrics", "Remastered 2011",
     * "feat. Someone". Checked against the whole contents, so "(It Goes Like)" stays.
     */
    private val VERSION_TAG = Regex(
        "^(?:" +
            "(?:official\\s+)?(?:(?:hd|hq|4k)\\s+)?(?:(?:music|lyrics?)\\s+)?(?:video|audio|visuali[sz]er|videoclip|clip)" +
            "(?:\\s+(?:officiel(?:le)?|oficial|ufficiale))?(?:\\s+(?:hd|hq|4k))?" +
            "|official|lyrics?|with\\s+lyrics|letra|paroles|hd|hq|4k|mono|stereo|explicit|clean|edit" +
            "|(?:\\d{4}\\s+)?(?:digital(?:ly)?\\s+)?remaster(?:ed)?(?:\\s+version)?(?:\\s+\\d{4})?" +
            "|remasterizad[oa]|remasteris[ée]e?|rimasterizzat[oa]" +
            "|(?:radio|video|single|album|clean|explicit|short|original|7\\s*(?:\"|'|_|in|inch)?)\\s+(?:edit|version|mix|cut)" +
            "|(?:feat\\.?|ft\\.?|featuring|with)\\s+.+" +
            ")$",
        RegexOption.IGNORE_CASE
    )

    /**
     * Words in brackets that make a different recording of the same song: faster, slower, live,
     * stripped back, or sung in another language. Two titles only match when they carry the same
     * ones, so a sped up upload is never handed the original's timings, nor the other way round.
     */
    private val RECORDING_MARKER = Regex(
        "\\b(?:sped\\s*up|speed\\s*up|slowed|reverb|nightcore|daycore|8d|live|acoustic|unplugged|instrumental|karaoke" +
            "|a\\s*cappella|acapella|cover|demo|spanish|english|french|german|italian|portuguese|japanese|korean|chinese" +
            "|espanol|español)\\b" +
            // KuGou marks many concert recordings only in Chinese, as 现场 (live) or 演唱会 (concert), and
            // lists "小幸运 (2015如果巡回演唱会高雄站)" three seconds from the studio song. Word boundaries
            // mean nothing between Chinese characters, so these stand outside them.
            "|现场|現場|演唱会|演唱會",
        RegexOption.IGNORE_CASE
    )

    private val ARTIST_SEPARATOR = Regex(
        """\s*(?:、|,|&|/|;|\bx\b|\bfeat\.?|\bft\.?|\bfeaturing\b|\bwith\b|\band\b)\s*""",
        RegexOption.IGNORE_CASE
    )

    /** Takes version tags off a title, leaving any bracket that is part of the name. */
    fun cleanTitle(title: String): String {
        var t = title.trim()
        while (true) {
            val before = t
            t = BRACKET.replace(t) { m -> if (isVersionTag(m.groupValues[1])) "" else m.value }
            DASH_SUFFIX.find(t)?.let { m -> if (isVersionTag(m.groupValues[1])) t = t.substring(0, m.range.first) }
            t = FEAT_TAIL.replace(t, "").trim().replace(SPACES, " ")
            if (t == before) break
        }
        // A title that is nothing but a tag is still the title.
        return t.ifBlank { title.trim() }
    }

    /** Drops "Artist - " from the front of a title when it names one of the song's artists. */
    fun dropArtistPrefix(title: String, artists: List<String>): String {
        val names = (artists + artists.joinToString(" & ") + artists.joinToString(", ") + artists.joinToString(" x "))
            .filter { it.isNotBlank() }
            .distinct()
            .sortedByDescending { it.length }
        for (name in names) {
            val m = Regex("^\\s*${Regex.escape(name)}\\s+[-\\u2013\\u2014]\\s+", RegexOption.IGNORE_CASE).find(title) ?: continue
            val rest = title.substring(m.range.last + 1)
            if (rest.isNotBlank()) return rest
        }
        return title
    }

    /** Whether two titles name the same song, recorded the same way. */
    fun titleMatches(a: String, b: String): Boolean {
        val ca = cleanTitle(a)
        val cb = cleanTitle(b)
        if (markers(ca) != markers(cb)) return false
        val (wholeA, baseA) = keys(ca)
        val (wholeB, baseB) = keys(cb)
        if (wholeA.isEmpty() || wholeB.isEmpty()) return false
        // The same title, or one title with an addition the other does not have, such as the remix name
        // that LRCLIB often leaves off. Two different additions to the same start are two songs:
        // "MONTAGEM - XONADA" is not "MONTAGEM - CORAL", nor is "Song (Part 1)" "Song (Part 2)".
        return wholeA == wholeB || baseA == wholeB || wholeA == baseB
    }

    /**
     * Whether a provider's artist string names one of the song's artists. A song with no artist, or
     * names written in scripts that share nothing, cannot be checked this way, so the title and the
     * length have to decide on their own.
     */
    fun artistMatches(artists: List<String>, candidate: String): Boolean {
        if (artists.none { it.isNotBlank() }) return true
        val whole = normaliseArtist(candidate)
        val parts = splitArtists(candidate).let { it + it.flatMap(::scriptParts) }
        val wanted = (artists + artists.flatMap { it.split(ARTIST_SEPARATOR) } + artists.joinToString(", "))
            .map(::normaliseArtist)
            .filter { it.isNotEmpty() }
            .let { it + it.flatMap(::scriptParts) }
        if (wanted.any { it == whole || it in parts }) return true
        // KuGou names artists in their own script: 米津玄師 where YouTube Music has Kenshi Yonezu, and
        // 周杰伦 for Jay Chou. Rejecting those threw away the right song whenever the player had the name
        // in Latin letters. The title is still checked, which keeps out the artist's other songs.
        val ours = scripts(wanted)
        val theirs = scripts(parts + whole)
        return ours.isNotEmpty() && theirs.isNotEmpty() && ours.none { it in theirs }
    }

    /**
     * The items that are this song by this artist, closest in length first, and only those within
     * [tolerance] seconds. With no length known, every match is kept in the provider's order, and
     * it is up to the caller not to trust any timings.
     */
    fun <T> ranked(
        items: List<T>,
        query: LyricsQuery,
        tolerance: Double,
        title: (T) -> String,
        artist: (T) -> String,
        duration: (T) -> Double?,
    ): List<T> {
        val matching = items.filter { titleMatches(query.searchTitle, title(it)) && artistMatches(query.artists, artist(it)) }
        if (!query.knowsDuration) return matching
        return matching
            .mapNotNull { item -> duration(item)?.let { abs(it - query.duration) to item } }
            .filter { it.first <= tolerance }
            .sortedBy { it.first }
            .map { it.second }
    }

    /**
     * LRCLIB's best answer for the song: timed lyrics from the closest entry within a few seconds,
     * or failing that plain words from one within [PLAIN_TOLERANCE_SEC]. With no length to check
     * against, plain words only, because timings from an unknown version are as likely wrong as
     * right.
     */
    fun chooseLrcLib(tracks: List<Track>, query: LyricsQuery): String? {
        val usable = tracks.filter { !it.instrumental }
        if (query.knowsDuration) {
            ranked(usable.filter { !it.syncedLyrics.isNullOrBlank() }, query, SYNCED_TOLERANCE_SEC, Track::trackName, Track::artistName, Track::duration)
                .firstOrNull()?.let { return it.syncedLyrics }
        }
        return ranked(usable.filter { !it.plainLyrics.isNullOrBlank() }, query, PLAIN_TOLERANCE_SEC, Track::trackName, Track::artistName, Track::duration)
            .firstOrNull()?.plainLyrics
    }

    /** Whether lyrics carry timings, as LRC lines like "[00:29.59]Si señor" do. */
    fun isSynced(text: String): Boolean = text.lineSequence().any { SYNCED_LINE.matches(it) }

    /**
     * Lower case, no accents, Simplified Chinese for Traditional, no apostrophes, "&" as "and", other
     * punctuation as single spaces.
     */
    fun normalise(s: String): String =
        Normalizer.normalize(s, Normalizer.Form.NFKD)
            .replace(COMBINING, "")
            .lowercase(Locale.ROOT)
            .let(HanFold::fold)
            .replace("&", " and ")
            .replace(APOSTROPHES, "")
            .replace(NON_WORD, " ")
            .trim()
            .replace(SPACES, " ")

    private fun isVersionTag(contents: String): Boolean {
        val t = contents.trim()
            .replace("’’", "\"").replace("''", "\"")
            .replace('″', '"').replace('“', '"').replace('”', '"').replace('’', '\'')
        return t.isNotEmpty() && VERSION_TAG.matches(t)
    }

    /** The title without any bracket or dash suffix, and what those held. */
    private fun split(title: String): Pair<String, List<String>> {
        val held = mutableListOf<String>()
        var base = BRACKET.replace(title) { m -> held += m.groupValues[1]; " " }
        DASH_SUFFIX.find(base)?.let { m -> held += m.groupValues[1]; base = base.substring(0, m.range.first) }
        return base to held
    }

    private fun markers(cleaned: String): Set<String> =
        split(cleaned).second
            .flatMap { part -> RECORDING_MARKER.findAll(part).map { normalise(it.value).replace(" ", "") } }
            .map { if (it == "speedup") "spedup" else it }
            .map { if (it == "acappella") "acapella" else it }
            .toSet()

    /**
     * The whole cleaned title, and the title with its brackets and dash suffix gone, so that
     * "Macarena (Bayside Boys Remix)" meets "Macarena".
     */
    private fun keys(cleaned: String): Pair<String, String> {
        val whole = normalise(cleaned)
        return whole to normalise(split(cleaned).first).ifEmpty { whole }
    }

    private fun normaliseArtist(s: String): String {
        val n = normalise(BRACKET.replace(s, " ").replace(Regex("""\s+-\s+topic$""", RegexOption.IGNORE_CASE), ""))
        return if (n.endsWith("vevo") && n.length > 4) n.removeSuffix("vevo").trim() else n
    }

    private fun splitArtists(s: String): List<String> =
        BRACKET.replace(s, " ").split(ARTIST_SEPARATOR).map(::normaliseArtist).filter { it.isNotEmpty() }

    /**
     * The Latin and the other part of a normalised name written in both, so that either one alone
     * matches: KuGou lists G.E.M. as "G.E.M.邓紫棋", which is "g e m" and "邓紫棋". A name in one
     * script gives nothing. Other scripts are not split from each other, because a Japanese name
     * mixes kanji and kana.
     */
    private fun scriptParts(name: String): List<String> {
        val parts = mutableListOf<String>()
        var start = 0
        var latin: Boolean? = null
        var i = 0
        while (i < name.length) {
            val cp = name.codePointAt(i)
            scriptOf(cp)?.let { script ->
                val isLatin = script == Character.UnicodeScript.LATIN
                if (latin != null && isLatin != latin) {
                    parts += name.substring(start, i).trim()
                    start = i
                }
                latin = isLatin
            }
            i += Character.charCount(cp)
        }
        if (parts.isEmpty()) return emptyList()
        parts += name.substring(start).trim()
        return parts.filter { it.isNotEmpty() }
    }

    /** The scripts the letters of [names] are written in. Digits and punctuation belong to none. */
    private fun scripts(names: List<String>): Set<Character.UnicodeScript> {
        val found = HashSet<Character.UnicodeScript>()
        for (name in names) {
            var i = 0
            while (i < name.length) {
                val cp = name.codePointAt(i)
                scriptOf(cp)?.let(found::add)
                i += Character.charCount(cp)
            }
        }
        return found
    }

    private fun scriptOf(cp: Int): Character.UnicodeScript? {
        if (!Character.isLetter(cp)) return null
        return when (val script = Character.UnicodeScript.of(cp)) {
            Character.UnicodeScript.COMMON, Character.UnicodeScript.INHERITED, Character.UnicodeScript.UNKNOWN -> null
            else -> script
        }
    }
}
