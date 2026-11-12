/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.utils

import androidx.datastore.preferences.core.Preferences

/**
 * What Report a problem and the crash page add to a report beyond the log: the settings that bear on
 * the problem ([ReportSettings]) and the steps before it ([ReportTrail]). Put together here, with
 * nothing of Android in it, so that the page, the crash handler and the tests all build the same text.
 *
 * His idea of 2 Nov 2026 ("shouldn't it show the concerned settings active, as well as the actions
 * leading up to it?"), with his guards: only the settings of the problem's area, mostly those changed
 * from their default; the steps without a song, a search or a name; and all of it shown to the person
 * before anything is sent. It rides in the report text, so it goes wherever the report goes: copied,
 * in the form's link, and to the relay.
 */
object ReportExtras {
    /** The settings lines and the steps block of one report. [steps] is null when none were kept. */
    class Extras(val area: ReportSettings.Area, val settings: List<String>, val steps: String?)

    /**
     * For a report made from the log: the area from the fault's tag (or the latest area's warning, or the
     * screen last opened), the settings of that area, and the steps around the fault when this run's
     * ([trail] is null for a run before it, whose steps are gone with its process; its settings are then
     * "now", as its phone's state is).
     */
    fun forLog(
        lines: List<String>,
        excerpt: ProblemLog.Excerpt,
        prefs: Preferences,
        trail: ReportTrail?,
        now: Long,
        /** What bears on an area beside its settings, asked once the area is known (the microphone, for recognition). */
        facts: (ReportSettings.Area) -> List<String> = { emptyList() },
    ): Extras {
        val faultTag = excerpt.faultAt?.let { excerpt.lines.getOrNull(it)?.tag }
        val recentTags = lines.mapNotNull(ProblemLog::parse).filter { it.level == 'W' || it.level == 'E' }.map { it.tag }.takeLast(RECENT)
        val area = ReportSettings.areaOf(faultTag, recentTags, trail?.lastScreen)
        val faultAt = excerpt.faultAt?.let { excerpt.lines.getOrNull(it) }?.let { ReportTrail.faultTime(it.time, now) }
        val settings = ReportSettings.lines(area, prefs, facts(area), now = trail == null)
        return Extras(area, settings, trail?.view(faultAt, now)?.text())
    }

    /** For a crash, which is now: the steps before it, and the settings of the area they point to. */
    fun forCrash(prefs: Preferences?, trail: ReportTrail, now: Long): Extras? {
        val steps = trail.view(null, now, crash = true)?.text()
        val area = ReportSettings.areaOf(null, emptyList(), trail.lastScreen)
        val settings = prefs?.let { ReportSettings.lines(area, it) }.orEmpty()
        if (steps == null && settings.isEmpty()) return null
        return Extras(area, settings, steps)
    }

    /** What a crash file gets after its trace: the settings, then the steps, an empty line between them as in the report. */
    fun block(extras: Extras): String =
        listOfNotNull(extras.settings.joinToString("\n").takeIf { it.isNotEmpty() }, extras.steps).joinToString("\n\n")

    /** The warnings and errors of the log looked at to find the area of a problem. */
    private const val RECENT = 60
}
