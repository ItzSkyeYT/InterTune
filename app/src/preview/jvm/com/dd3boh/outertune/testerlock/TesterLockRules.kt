/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.testerlock

/**
 * What the phone keeps between launches, and nothing more: no account, nothing of the library.
 *
 * [code] is the last code given that the door has not refused: one it said yes to, or one typed
 * while it could not be reached. [openedBuild] is the build the door said yes for, and nothing more
 * is sent while it is the one installed. [graceBuild] is a build whose check could not be made, and
 * [graceSince] when its first try failed.
 */
data class TesterMemory(
    val code: String? = null,
    val openedBuild: String? = null,
    val graceBuild: String? = null,
    val graceSince: Long = 0L,
)

/** What the gate says under the field. */
enum class GateMessage { UNKNOWN, REVOKED, GRACE_OVER }

/** What a launch does. */
sealed interface AtLaunch {
    /** The app opens, and nothing is sent. */
    data object Open : AtLaunch

    /** The app opens at once, and the code kept from before is checked behind it, with no typing. */
    data class OpenAndCheck(val code: String) : AtLaunch

    /** The gate, asking for a code. */
    data object Ask : AtLaunch

    /** The gate, checking the code kept from before: the days a check could be missed are over. */
    data class AskAndCheck(val code: String) : AtLaunch
}

/** After the door's answer: what to keep, and whether the app opens or the gate says [message]. */
data class AfterCheck(val memory: TesterMemory, val opens: Boolean, val message: GateMessage?)

/**
 * The tester lock's rules, on their own so they can be tested without a phone.
 *
 * A locked build asks for the tester's code the first time it opens, and once for each new build,
 * where a code kept from an earlier build is checked with no typing. The door decides; the app only
 * keeps the answer. When the door cannot be reached the app opens anyway and tries again at the next
 * launch, for seven days from the first failed try on that build: a tester is never locked out by an
 * outage of ours, while a phone kept offline on purpose is, after a week.
 */
object TesterLockRules {
    const val GRACE_MILLIS = 7L * 24 * 60 * 60 * 1000

    fun atLaunch(lockOn: Boolean, build: String, memory: TesterMemory, now: Long): AtLaunch {
        val code = memory.code
        return when {
            !lockOn -> AtLaunch.Open
            // One good check for each build, and then nothing more is sent for it.
            memory.openedBuild == build -> AtLaunch.Open
            code == null -> AtLaunch.Ask
            graceOver(build, memory, now) -> AtLaunch.AskAndCheck(code)
            else -> AtLaunch.OpenAndCheck(code)
        }
    }

    /** What the door's [answer] about [code] means for [build], asked at [now]. */
    fun afterCheck(build: String, memory: TesterMemory, code: String, answer: DoorAnswer, now: Long): AfterCheck =
        when (answer) {
            DoorAnswer.OPEN -> AfterCheck(TesterMemory(code = code, openedBuild = build), opens = true, message = null)
            DoorAnswer.UNKNOWN -> refused(memory, code, GateMessage.UNKNOWN)
            DoorAnswer.REVOKED -> refused(memory, code, GateMessage.REVOKED)
            DoorAnswer.COULD_NOT_CHECK -> {
                // The days count from the first failed try on this build, and start again for the next.
                val since = if (memory.graceBuild == build) memory.graceSince else now
                val kept = memory.copy(code = code, graceBuild = build, graceSince = since)
                if (over(since, now)) AfterCheck(kept, opens = false, message = GateMessage.GRACE_OVER)
                else AfterCheck(kept, opens = true, message = null)
            }
        }

    /** A refused code is not kept. One kept from before stays when it was another code that was refused. */
    private fun refused(memory: TesterMemory, code: String, message: GateMessage) =
        AfterCheck(memory.copy(code = memory.code?.takeIf { it != code }), opens = false, message = message)

    private fun graceOver(build: String, memory: TesterMemory, now: Long) =
        memory.graceBuild == build && over(memory.graceSince, now)

    /** A clock put back counts as within the days: an outage of ours never locks anyone out. */
    private fun over(since: Long, now: Long) = now - since >= GRACE_MILLIS
}
