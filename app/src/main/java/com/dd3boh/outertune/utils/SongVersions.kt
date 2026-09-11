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
    private val BRACKETED = Regex("""\s*[\(\[][^\)\]]*[\)\]]""")
    private val TRAILING_QUALIFIER = Regex("""\s+-\s+.*$""")
    private val NON_ALPHANUMERIC = Regex("""[^\p{L}\p{N}]+""")

    fun baseTitle(title: String): String =
        title.replace(BRACKETED, "")
            .replace(TRAILING_QUALIFIER, "")
            .replace(NON_ALPHANUMERIC, " ")
            .trim()
            .lowercase()

    /** A title that is nothing but brackets has no base, and is never counted as anything's version. */
    fun isVersionOf(candidate: String, seed: String): Boolean {
        val base = baseTitle(candidate)
        return base.isNotEmpty() && base == baseTitle(seed)
    }
}
