/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.utils

/**
 * Whether one title is a version of another: the same song live, remixed, remastered or re-edited.
 *
 * Recommending a version of the song you are already playing is the plainest way for a
 * recommendation to fail, and YouTube's related list does it on its own: for Take on Me its "You
 * might also like" shelf includes "Take on Me (1985 Single Mix) (1985 Single Mix; 2015 Remaster)",
 * even with the "Other performances" shelf split off.
 *
 * Deliberately equality on a normalised base title and not containment, so "Take Me Home" can never
 * be taken for a version of "Take on Me". Brackets and a spaced " - suffix" are dropped, the rest is
 * reduced to letters and digits. The same normalisation Flow uses for its canonical key, minus the
 * artist and duration, because a remix or cover by someone else is still a version of the seed in
 * a list of songs related to it.
 */
object SongVersions {
    // Full-width （） ［］ and lenticular 【】 too, which is how Japanese and Chinese titles mark a
    // version ("（Live）", "【MV】"); without them those versions slipped through. Corner brackets
    // 「」 are left alone on purpose: they usually quote the title itself, and stripping them would
    // empty it.
    private val BRACKETED = Regex("""\s*[\(\[（［【][^\)\]）］】]*[\)\]）］】]""")
    private val TRAILING_QUALIFIER = Regex("""\s+-\s+(.*)$""")
    private val NON_ALPHANUMERIC = Regex("""[^\p{L}\p{N}]+""")
    private val YEAR = Regex("""^(19|20)\d{2}$""")

    /**
     * Words that mean "this is a treatment of a song" rather than naming one.
     *
     * Deliberately not shared with [com.dd3boh.outertune.engine.SongTags], which answers a
     * different question and would be muddied by half of this: "official video" says nothing about
     * what a recording sounds like, and matters a great deal to whether it is the same song.
     */
    private val QUALIFIER_WORDS = setOf(
        "remaster", "remastered", "official", "video", "audio", "lyric", "lyrics", "visualizer", "mv",
        "feat", "ft", "featuring", "prod", "version", "ver", "edit", "mix", "remix", "radio",
        "extended", "instrumental", "acoustic", "live", "cover", "karaoke", "sped", "speed",
        "slowed", "slow", "reverb", "nightcore", "bootleg", "flip", "vip", "mashup", "demo",
        "session", "take", "mono", "anniversary", "deluxe", "bonus", "theme", "ost", "soundtrack",
        "intro", "outro", "interlude", "remake", "rework", "bass", "boosted", "8d", "loop", "hour",
        "clean", "explicit", "single", "original", "club", "dance",
    )

    /**
     * Whether what follows " - " describes the recording, or is simply more of its name.
     *
     * The strip used to be unconditional, which is right for "Levitating - Maduk Remix" and wrong
     * for "Initial D - Deja Vu". On this maintainer's library it merged sixty four entirely
     * different Initial D tracks into one version group, and since a build excludes a seed's whole
     * group, one of those seeds deleted sixty four real candidates before ranking began. That
     * matters more than it sounds: the replay found that about nine in ten of the songs he played
     * next were never candidates at all.
     */
    private fun describesTheRecording(segment: String): Boolean {
        val words = NON_ALPHANUMERIC.replace(segment, " ").trim().lowercase().split(" ").filter { it.isNotEmpty() }
        if (words.isEmpty()) return true
        if (words.any { it in QUALIFIER_WORDS }) return true
        return words.size == 1 && YEAR.matches(words[0])
    }

    fun baseTitle(title: String): String {
        val unbracketed = title.replace(BRACKETED, "")
        val match = TRAILING_QUALIFIER.find(unbracketed)
        val trimmed =
            if (match != null && describesTheRecording(match.groupValues[1])) unbracketed.substring(0, match.range.first)
            else unbracketed
        return trimmed
            .replace(NON_ALPHANUMERIC, " ")
            .trim()
            .lowercase()
    }

    /** A title that is nothing but brackets has no base, and is never counted as anything's version. */
    fun isVersionOf(candidate: String, seed: String): Boolean {
        val base = baseTitle(candidate)
        return base.isNotEmpty() && base == baseTitle(seed)
    }
}
