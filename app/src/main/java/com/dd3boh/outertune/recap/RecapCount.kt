/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.recap

/**
 * How a Recap being opened is counted: exactly as the first opening of an announcement or a poll
 * from the banner on Home is (PollChecker.viewed). One event, Umami's "poll_view", carrying these
 * four fields and nothing else: no id, no account, nothing about the phone or what was played.
 * Once per install for each year's Recap, the first time its story is opened, and only with News
 * from InterTune switched on, whose words already say that the first time one is opened is counted
 * anonymously. Nothing at all in a build without the maintainer's keys.
 *
 * The dashboard already groups these by type and id, so the Recap shows up there as its own row.
 */
object RecapCount {
    /** The type the dashboard tells it apart by, beside "poll" and "announcement". */
    const val TYPE = "recap"

    fun id(year: Int) = "recap-$year"

    fun title(year: Int) = "InterTune Recap $year"

    /** Everything the count sends, in the shape of a banner's first opening. */
    fun data(year: Int, version: String): Map<String, String> = linkedMapOf(
        "poll" to title(year),
        "poll_id" to id(year),
        "type" to TYPE,
        "version" to version,
    )
}
