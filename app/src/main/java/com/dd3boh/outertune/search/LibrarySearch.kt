/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.search

/**
 * How the library search finds and orders what it shows.
 *
 * It asked the database for the typed words as one piece of a title or of an artist's name, in
 * whatever order the rows were stored. So a song called "c" came ninth when searching "c", under
 * every title with a c somewhere in it, and "c toe" (the song and its artist) found nothing at all
 * (Ti-Die, #general, 18 Nov 2026: "the search system really hates stupid titles").
 *
 * Now what is named exactly as typed comes first, then what starts with it, then a word that starts
 * with it, then the rest, the way the big music apps sort. And several words are looked for apart:
 * a result has each of them in its title, its artists or its album, in any order.
 */
object LibrarySearch {
    private val SPACES = Regex("\\s+")
    private val WORD_EDGE = Regex("[^\\p{L}\\p{N}]+")

    /** The words typed, in order, each once. */
    fun words(query: String): List<String> =
        query.trim().split(SPACES).filter { it.isNotEmpty() }.distinctBy { it.lowercase() }

    /** Whether the words are to be looked for apart: more than one of them. */
    fun apart(query: String): Boolean = words(query).size > 1

    /**
     * What the database is asked for: one word, or words together, as typed. Several words: each of
     * them, once, but a single letter only when there is nothing longer, since it is in most of the
     * library; whatever comes back is then kept only with every word ([order]). So a song is found
     * when one of its words is in its title or among its artists, and the others anywhere in those or
     * in its album.
     */
    fun asked(query: String): List<String> {
        val words = words(query)
        if (words.size < 2) return listOf(query)
        return words.filter { it.length > 1 }.ifEmpty { listOf(words.first()) }
    }

    /** Whether each word is in one of [fields]: a title, its artists, its album. */
    fun hasAll(words: List<String>, fields: List<String>): Boolean =
        words.all { word -> fields.any { it.contains(word, ignoreCase = true) } }

    /**
     * Where a result goes, smaller first. Its [name] (a song's or an album's title, an artist's or a
     * playlist's name) is what was typed; several words that are each a whole title, artist or album of
     * it ("c toe": the song c by Toe); the name starts with what was typed, or [others] (a song's artists
     * and album, an album's artists) holds exactly that; then a word of the name starts with it, the name
     * holds it, the others hold it. Last, what was found by its words apart, first the ones where more of
     * the words are a whole title or a whole artist.
     */
    fun rank(query: String, name: String, others: List<String> = emptyList()): Int {
        val typed = query.trim()
        val typedWords = words(typed)
        val whole = typedWords.count { word -> name.equals(word, ignoreCase = true) || others.any { it.equals(word, ignoreCase = true) } }
        val place = when {
            typed.isEmpty() -> 0
            name.equals(typed, ignoreCase = true) -> 0
            typedWords.size > 1 && whole == typedWords.size -> 1
            name.startsWith(typed, ignoreCase = true) -> 2
            others.any { it.equals(typed, ignoreCase = true) } -> 3
            name.split(WORD_EDGE).any { it.startsWith(typed, ignoreCase = true) } -> 4
            name.contains(typed, ignoreCase = true) -> 5
            others.any { it.contains(typed, ignoreCase = true) } -> 6
            else -> 7
        }
        return if (place < 7) place * 100 else 700 - whole
    }

    /**
     * The results as shown: with several words, only those that have every one of them; then by
     * [rank], the database's own order kept between results of the same rank.
     */
    fun <T> order(query: String, items: List<T>, name: (T) -> String, others: (T) -> List<String>): List<T> {
        val words = words(query)
        val kept = if (words.size > 1) items.filter { hasAll(words, listOf(name(it)) + others(it)) } else items
        return kept.sortedBy { rank(query, name(it), others(it)) }
    }
}
