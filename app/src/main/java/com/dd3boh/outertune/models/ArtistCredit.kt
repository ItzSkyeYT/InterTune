/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.models

/**
 * A song's credit: its artists, as a file's tag or YouTube writes them out as one name.
 *
 * YouTube Music can send a song's credit as one piece of text with no link in it, before it has linked the
 * artists of a new release: "Hoax, So What, & Dynamite MC", in the words of the language asked for (its player
 * gives the very same text as the video's author, with "et" in French and "und" in German). Every reader of a
 * byline keeps an unlinked piece as one artist, so such a song was stored with one artist of that name, under an
 * id made up here, whose page had nothing to load. YouTube names each artist later (on 27 Nov 2026: Hoax and
 * Dynamite MC linked, So What with no page of its own), but a song already stored was never written again, and
 * his liked, downloaded copy of "Thread The Needle" kept the one name. [spells] is how the app knows that the
 * artists it is told now are that one name split, and only then is the stored artist replaced
 * (DatabaseDao.splitStoredCredit, ArtistCreditRepair).
 */
object ArtistCredit {

    /** Where a file's tag is split into artists: ";", "ft.", "feat.", "&" and ",". */
    val TAG_SEPARATORS = Regex("\\s*;\\s*|\\s*ft\\.\\s*|\\s*feat\\.\\s*|\\s*&\\s*|\\s*,\\s*", RegexOption.IGNORE_CASE)

    /**
     * The artists a file's tag names, in its order, each once: split at [TAG_SEPARATORS] and trimmed, with no
     * empty name. "A, B, & C" has nothing between its comma and its "&", which the MediaStore and FFmpeg readers
     * kept as an artist with no name; the scan's lookup by name then took whichever artist was shortest for it.
     */
    fun fromTag(tag: String?): List<String> =
        tag?.split(TAG_SEPARATORS).orEmpty().map { it.trim() }.filter { it.isNotEmpty() }.distinct()

    /** ArtistEntity.generateArtistId: LA and eight letters. No id of YouTube's looks like it. */
    private val MADE_UP = Regex("LA[A-Za-z]{8}")

    /** An artist id this app made up for an artist YouTube gave no page, or for a file's artist. */
    fun isMadeUp(id: String?): Boolean = id != null && MADE_UP.matches(id)

    /**
     * The one word a joiner may hold besides its punctuation: "and" in the languages YouTube writes credits in,
     * and the words of a feature. Any other word between two names means they are not the whole credit.
     */
    private val JOINER_WORDS = setOf(
        "and", "et", "und", "y", "e", "en", "og", "och", "i", "a", "ve", "és", "dan", "và", "ja", "oraz",
        "и", "і", "та", "και", "ו", "و", "と", "和", "및", "และ",
        "x", "vs", "feat", "ft", "featuring", "with", "con", "mit", "avec", "com", "med",
    )

    /**
     * Whether [names], two or more, in their order, are [credit] written out as one name: each found in turn,
     * the first at its start and the last at its end, and nothing between two of them but a joiner, which is
     * punctuation and spaces with at most one word of [JOINER_WORDS] (", ", " & ", ", & ", " + ", "、", " and ",
     * " et ", " x ", " feat. "). So "Hoax, So What, & Dynamite MC" is Hoax, So What and Dynamite MC, while
     * "Earth, Wind & Fire" is never Earth and Fire, nor "A B" A and B. Capitals are not minded.
     */
    fun spells(credit: String, names: List<String>): Boolean {
        val parts = names.map { it.trim() }
        if (parts.size < 2 || parts.any { it.isEmpty() }) return false
        val text = credit.trim()
        if (!text.startsWith(parts.first(), ignoreCase = true)) return false
        var at = parts.first().length
        for (name in parts.drop(1)) {
            val found = text.indexOf(name, at, ignoreCase = true)
            if (found < 0 || !isJoiner(text.substring(at, found))) return false
            at = found + name.length
        }
        return at == text.length
    }

    /**
     * Whether a song's one stored artist, [id] [name] (a file's when [isLocal]), is to give way to [names], the
     * artists YouTube names now: only an artist YouTube gave no id (made up here, [isMadeUp]), never a file's,
     * and only for names that spell its own ([spells]). DatabaseDao.splitStoredCredit.
     */
    fun splits(id: String, name: String, isLocal: Boolean, names: List<String>): Boolean =
        !isLocal && isMadeUp(id) && spells(name, names)

    /**
     * Whether [name] may be several artists written out as one: it holds a joiner's mark (",", "&", "+", ";",
     * "/", "、", "×") or a joiner's word between two others (" and ", " et ", " x ", " feat. "). Only such a name
     * is asked about (ArtistCreditRepair): "Milk Talk" never is.
     */
    fun maybeSeveral(name: String): Boolean {
        if (name.any { it in ",&+;/、×" }) return true
        val words = name.lowercase().split(WORD_BREAK).filter { it.isNotEmpty() }
        return words.size >= 3 && words.subList(1, words.size - 1).any { it in JOINER_WORDS }
    }

    private val WORD_BREAK = Regex("[\\s\\p{P}\\p{S}]+")

    private fun isJoiner(between: String): Boolean {
        if (between.isBlank()) return false
        val words = between.split(WORD_BREAK).filter { it.isNotEmpty() }
        return words.size <= 1 && words.all { it.lowercase() in JOINER_WORDS }
    }
}
