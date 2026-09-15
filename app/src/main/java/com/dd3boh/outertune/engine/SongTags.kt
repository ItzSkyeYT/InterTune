/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.engine

/**
 * What a song sounds like, as far as its own title will admit.
 *
 * Every one of the engine's other features is a fact about the listener's behaviour: what they
 * finish, what they return to, what they scroll past. Not one is a fact about the music, so when
 * the engine asks "what sounds like this" it is really asking "what do people play near this",
 * borrowed wholesale from YouTube's related edges. That is a real hole, and the cheapest quarter
 * of it is already written down: a third of this library's listening is to titles that say
 * "Slowed", "Sped Up", "Remix", "Reverb". The words are free, they need no network and no audio
 * decoding, and they are the same words [com.dd3boh.outertune.utils.SongVersions] already parses
 * and then throws away.
 *
 * This is deliberately not a genre classifier. It reads treatment, the thing done to a recording,
 * because that is what titles reliably carry and what this listener's library is full of. A song
 * with no qualifier gets no tags, which is the common case and is correct: most music is not a
 * version of anything.
 */
object SongTags {

    /** The bracketed asides and the trailing dash qualifier, which is where a title says this. */
    private val REGIONS = Regex("""[\(\[（［【][^\)\]）］】]*[\)\]）］】]|\s+-\s+.*$""")
    private val TIDY = Regex("""[^\p{L}\p{N}]+""")

    /**
     * Tag by tag, the spellings seen in the wild. Order matters: the first match wins, so the
     * narrower reading goes first. "slowed reverb" is one treatment written two ways and must not
     * count twice, and nightcore is sped up and pitched at once, so it is its own thing rather
     * than a synonym for sped up.
     */
    private val VOCABULARY: List<Pair<String, List<String>>> = listOf(
        "nightcore" to listOf("nightcore"),
        "slowed" to listOf("slowed", "slow down", "slowed down", "ultra slowed", "extreme slowed", "super slowed", "slowly"),
        "sped" to listOf("sped up", "speed up", "spedup", "sped", "faster"),
        "reverb" to listOf("reverb", "reverbed"),
        "bassboost" to listOf("bass boost", "bassboost", "bass boosted", "bassboosted"),
        "eightd" to listOf("8d audio", "8d"),
        "remix" to listOf("remix", "rmx", "bootleg", "flip", "vip mix"),
        "mashup" to listOf("mashup", "mash up"),
        "cover" to listOf("cover", "covered by"),
        "acoustic" to listOf("acoustic", "unplugged"),
        "instrumental" to listOf("instrumental", "karaoke", "off vocal"),
        "live" to listOf("live", "live at", "live from", "concert"),
        "extended" to listOf("extended", "extended mix", "long version"),
        "edit" to listOf("radio edit", "edit", "short version"),
        "mix" to listOf("club mix", "dance mix", "original mix", "mix"),
        "loop" to listOf("loop", "looped", "1 hour", "one hour"),
    )

    /**
     * The treatments a title claims, or nothing.
     *
     * Only the bracketed and dashed regions are read, never the body of the title, because the
     * body is the song's name and songs are called all sorts of things. "Live and Let Die" is not
     * a live recording, "Remix" is somebody's band name, and a rule that read the whole line would
     * tag both. The qualifier regions are where a title speaks about the recording rather than
     * naming it.
     */
    fun of(title: String?): Set<String> {
        if (title.isNullOrBlank()) return emptySet()
        val regions = REGIONS.findAll(title).map { it.value }.toList()
        if (regions.isEmpty()) return emptySet()
        val text = " " + regions.joinToString(" ") { TIDY.replace(it, " ") }.lowercase().trim() + " "
        val found = LinkedHashSet<String>()
        for ((tag, spellings) in VOCABULARY) {
            if (spellings.any { text.contains(" $it ") }) found.add(tag)
        }
        return found
    }

    /** Whether two songs were treated the same way, which is a crude but free kind of "sounds alike". */
    fun overlap(a: Set<String>, b: Set<String>): Double {
        if (a.isEmpty() || b.isEmpty()) return 0.0
        val shared = a.count { it in b }
        return shared.toDouble() / minOf(a.size, b.size)
    }
}
