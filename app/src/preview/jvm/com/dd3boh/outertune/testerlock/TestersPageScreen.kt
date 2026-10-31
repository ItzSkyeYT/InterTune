/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.testerlock

import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException

/** What the lock's last check said of this build. */
enum class CheckResult {
    /** The door said yes to this build. */
    OPENS,

    /**
     * The door said this build is out of date. A build that is out of date has the gate in front of it, so the page is seldom
     * drawn with this: it is the instant before the gate takes over.
     */
    OUT_OF_DATE,

    /** The door could not be reached: the app opened all the same, and asks again at the next launch. */
    NOT_REACHED,

    /** Nothing is on file for this build yet. */
    NOT_YET,
}

/** When the lock next asks by itself. */
sealed interface NextCheck {
    /** At the next launch: a check is due, or none has been made for this build. */
    data object AtNextOpening : NextCheck

    /** At the first launch after [time], in epoch milliseconds. */
    data class After(val time: Long) : NextCheck
}

/**
 * What the Testers page shows of the lock, as a plain description, so that every state is tested without a phone.
 * TestersPage.kt draws it and decides nothing.
 */
data class TestersScreen(
    val result: CheckResult,
    /** When the door last said yes to this build, in epoch milliseconds; null when it has not. */
    val lastCheck: Long?,
    val next: NextCheck,
    /** The code as the page draws it, all but its last four symbols hidden; null when none is kept. */
    val maskedCode: String?,
    /** The last four symbols of the code, which a screen reader says in place of the dots; empty with no code. */
    val codeEnd: String,
)

object TestersPageScreen {
    /** The #testers channel on Discord: the newest build is there, and the code's own button. The gate's link is this one. */
    const val CHANNEL = "https://discord.com/channels/1544002995887411230/1559515871305666560"

    /** The file a build made with -PtesterTry=<file> carries in its assets: what to try in this build. */
    const val TRY_ASSET = "tester_try.txt"

    /** The page for [build], from what the lock [memory] holds at [now]. */
    fun of(build: String, memory: TesterMemory, now: Long): TestersScreen {
        val result = when {
            // The door's last word on this build was no.
            memory.refusedBuild == build -> CheckResult.OUT_OF_DATE
            // A check could not be made, and no yes has come since: a yes forgets the trouble.
            memory.graceBuild == build -> CheckResult.NOT_REACHED
            memory.openedBuild == build -> CheckResult.OPENS
            else -> CheckResult.NOT_YET
        }
        val yes = memory.openedAt.takeIf { memory.openedBuild == build && it > 0L }
        val due = TesterLockRules.nextCheckAt(build, memory)
        // A yes that is older than it stands for is due at the next launch; so is a clock put back past it, as the rules have it.
        val next = if (due == null || yes == null || now < yes || now > due) NextCheck.AtNextOpening else NextCheck.After(due)
        val code = memory.code
        return TestersScreen(
            result = result,
            lastCheck = yes,
            next = next,
            maskedCode = code?.let { TesterCode.masked(it) }?.takeIf { it.isNotEmpty() },
            codeEnd = code?.let { TesterCode.lastSymbols(it) }.orEmpty(),
        )
    }

    /**
     * The lines of what to try, from the text of the file a build carries: the bullets as plain lines, and any other line as it is.
     * The first line is left out when it only introduces the list ("What to try first:"), since the page's own heading says so.
     * Nothing for no file, an empty one, or one that holds no words.
     */
    fun tryLines(text: String?): List<String> {
        val lines = text.orEmpty().lines().map { it.trim() }.filter { it.isNotEmpty() }
        val bullets = lines.map { line -> BULLET.find(line)?.let { line.substring(it.range.last + 1).trim() } ?: line }
        val introduced = lines.firstOrNull()?.let { BULLET.find(it) == null && it.endsWith(":") } == true
        return bullets.drop(if (introduced) 1 else 0).filter { it.isNotEmpty() }
    }

    /** A bullet at the start of a line: a dash, a star or a dot, and the space after it (or the end of the line, for a bullet with nothing after it). */
    private val BULLET = Regex("^[-*\\u2022](?:\\s+|$)")

    /**
     * When a tester build was made, from the stamp at the end of its name (<version>-preview.<yyyyMMdd>.<HHmm>, which
     * publish-preview.sh puts there, as the maker's clock had it). Null for a build without a stamp: one made by hand.
     */
    fun builtAt(versionName: String): LocalDateTime? {
        val stamp = STAMP.find(versionName) ?: return null
        return try {
            LocalDateTime.parse("${stamp.groupValues[1]}${stamp.groupValues[2]}", STAMP_FORMAT)
        } catch (e: DateTimeParseException) {
            null
        }
    }

    private val STAMP = Regex("-preview\\.(\\d{8})\\.(\\d{4})$")
    private val STAMP_FORMAT = DateTimeFormatter.ofPattern("yyyyMMddHHmm")
}
