/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.constants

/**
 * The addresses this app sends people to, in one place.
 *
 * They were scattered across About, the head tracking row and a settings fragment, spelled out in
 * full each time, which is how three of them ended up pointing at three different parts of the
 * repository for the same purpose.
 */
object Links {
    const val REPO = "https://github.com/ItzSkyeYT/InterTune"
    const val ISSUES = "$REPO/issues"
    const val DISCUSSIONS = "$REPO/discussions"
    const val RELEASES = "$REPO/releases"
    const val WIKI = "$REPO/wiki"

    /** The community server. Support, bug reports from people without a GitHub account, and releases. */
    const val DISCORD = "https://discord.gg/68jmqhMjXk"
}
