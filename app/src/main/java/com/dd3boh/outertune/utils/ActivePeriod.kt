/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.utils

import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/**
 * When to say "still here", and under which name.
 *
 * Counting people is the part of analytics that is easy to do badly. The usual shortcuts are a
 * permanent install id, which is a tracking identifier however politely it is described, or no id
 * at all, which is what this app does today: the poll sender pins its User-Agent to a constant on
 * purpose, so the only thing left varying in Umami's visitor hash is the address. That is honest
 * but it does not count people. Carrier NAT folds strangers into one number and a rotating address
 * splits one person into several, so "unique visitors" there means unique addresses today and
 * nothing more.
 *
 * So: an id that is random, held only for the calendar month it belongs to, and replaced with an
 * unrelated one when the month turns. Within a month it counts a device exactly once however often
 * the app is opened. Across months nothing joins them, because by then the old value is gone from
 * the device as well as unguessable from the new one. Distinct ids in a month is therefore a real
 * monthly figure, and distinct ids on a day is a real daily one, from the same single event.
 *
 * The month is UTC so that every device turns over at the same instant and a month's count is a
 * month's count. The day is the listener's own, because "once a day" should mean what they would
 * think it means. No Android in here, so it is tested.
 */
object ActivePeriod {

    private val PERIOD = DateTimeFormatter.ofPattern("yyyy-MM")
    private val DAY = DateTimeFormatter.ISO_LOCAL_DATE

    /** The month an id belongs to. UTC, so the turnover is the same moment everywhere. */
    fun period(nowMillis: Long): String =
        Instant.ofEpochMilli(nowMillis).atZone(ZoneOffset.UTC).format(PERIOD)

    /** The listener's own day, which is what the once-a-day limit is measured in. */
    fun day(nowMillis: Long, zone: ZoneId): String =
        Instant.ofEpochMilli(nowMillis).atZone(zone).format(DAY)

    /** One day's ping: which name to send it under, and what to write back. */
    data class Ping(val id: String, val period: String, val day: String, val rotated: Boolean)

    /**
     * What to send now, or null for the far more common answer of nothing.
     *
     * [freshId] is passed in rather than called here so that the test can watch whether a new name
     * was minted, and so that the only source of randomness sits at the edge.
     */
    fun decide(
        nowMillis: Long,
        zone: ZoneId,
        storedId: String,
        storedPeriod: String,
        lastDay: String,
        freshId: () -> String,
    ): Ping? {
        val today = day(nowMillis, zone)
        // Already counted today. This is the answer almost every time the app is opened.
        if (today == lastDay) return null

        val period = period(nowMillis)
        // A missing id is a first run. A stale one is a month that has turned; either way the old
        // value is not reused, and the caller overwrites it, so the device stops holding it too.
        val rotate = storedId.isEmpty() || storedPeriod != period

        return Ping(
            id = if (rotate) freshId() else storedId,
            period = period,
            day = today,
            rotated = rotate,
        )
    }
}
