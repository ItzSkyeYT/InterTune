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
 * while it could not be reached, or a good one it refused this build for (see [refusedBuild]).
 * [install] is the number this install made for itself the first time it asked the door (TesterInstall):
 * random, never read from the phone, and new for every install.
 * [openedBuild] is the build the door said yes for and [openedAt] when it said so; nothing more is sent
 * for that build until the yes is [TesterLockRules.RECHECK_MILLIS] old. [claimed] is whether that yes
 * gave this install the code (the door held it for nobody), which is looked at again sooner.
 * [graceBuild] is a build whose check could not be made, and [graceSince] when its first try failed.
 * [refusedBuild] is a build the door refused although the code is good, as out of date or as in use
 * on another device: the gate stays in front of it, and looks again behind the gate at each launch.
 */
data class TesterMemory(
    val code: String? = null,
    val install: String? = null,
    val openedBuild: String? = null,
    val openedAt: Long = 0L,
    val claimed: Boolean = false,
    val graceBuild: String? = null,
    val graceSince: Long = 0L,
    val refusedBuild: String? = null,
)

/**
 * What the gate says of the door's answer: under the code field, or, for a good code the door refused the
 * build with (IN_USE, OUTDATED), in the body in place of the lead, since the field is not the trouble.
 */
enum class GateMessage { UNKNOWN, REVOKED, IN_USE, OUTDATED, GRACE_OVER }

/** What a launch does. */
sealed interface AtLaunch {
    /** The app opens, and nothing is sent. */
    data object Open : AtLaunch

    /** The app opens at once, and the code kept from before is checked behind it, with no typing. */
    data class OpenAndCheck(val code: String) : AtLaunch

    /** The gate, asking for a code. */
    data object Ask : AtLaunch

    /**
     * The gate, checking the code kept from before: the days a check could be missed are over, or the
     * door refused this build with it and the answer may have changed since.
     */
    data class AskAndCheck(val code: String) : AtLaunch
}

/** After the door's answer: what to keep, and whether the app opens or the gate says [message]. */
data class AfterCheck(val memory: TesterMemory, val opens: Boolean, val message: GateMessage?)

/**
 * The tester lock's rules, on their own so they can be tested without a phone.
 *
 * A locked build asks for the tester's code the first time it opens, and once for each new build,
 * where a code kept from an earlier build is checked with no typing. The door decides; the app only
 * keeps the answer. A yes stands for a few days: after that the kept code is checked again, quietly,
 * with the app open and the check behind it, and a refusal then puts the gate in front. When the door
 * cannot be reached the app opens anyway and tries again at the next launch, for seven days from the
 * first failed try on that build: a tester is never locked out by an outage of ours, while a phone kept
 * offline on purpose is, after a week.
 */
object TesterLockRules {
    const val GRACE_MILLIS = 7L * 24 * 60 * 60 * 1000

    /** The door's yes for a build stands for this long. After it, the next launch checks the code again, quietly. */
    const val RECHECK_MILLIS = 3L * 24 * 60 * 60 * 1000

    /**
     * After a yes that gave this install the code, the next check comes this soon instead: the door's
     * storage takes up to a minute to agree with itself, so another phone that opened in that minute
     * may have been told yes too, and the one the door's note went to finds out here, not in days.
     */
    const val CLAIM_RECHECK_MILLIS = 10L * 60 * 1000

    /**
     * The memory with the install number it needs: one made by [make] when it has none, which is the
     * first time the lock asks and every memory saved before the lock had one. A number that is not
     * the shape the door takes is not kept.
     */
    fun withInstall(memory: TesterMemory, make: () -> String): TesterMemory {
        val install = memory.install
        return if (install != null && TesterInstall.isWellFormed(install)) memory else memory.copy(install = make())
    }

    fun atLaunch(lockOn: Boolean, build: String, memory: TesterMemory, now: Long): AtLaunch {
        val code = memory.code
        return when {
            !lockOn -> AtLaunch.Open
            code == null -> AtLaunch.Ask
            // The door refused this build with a good code: the gate, and a look at whether that still holds.
            memory.refusedBuild == build -> AtLaunch.AskAndCheck(code)
            // One good check for each build, and then nothing more is sent for it until the yes is old.
            memory.openedBuild == build && !recheckDue(memory, now) -> AtLaunch.Open
            graceOver(build, memory, now) -> AtLaunch.AskAndCheck(code)
            else -> AtLaunch.OpenAndCheck(code)
        }
    }

    /** What the door's [answer] about [code] means for [build], asked at [now]. */
    fun afterCheck(build: String, memory: TesterMemory, code: String, answer: DoorAnswer, now: Long): AfterCheck =
        when (answer) {
            DoorAnswer.OPEN -> opened(memory, code, build, now, claimed = false)
            DoorAnswer.CLAIMED -> opened(memory, code, build, now, claimed = true)
            DoorAnswer.UNKNOWN -> refused(memory, code, GateMessage.UNKNOWN)
            DoorAnswer.REVOKED -> refused(memory, code, GateMessage.REVOKED)
            DoorAnswer.IN_USE -> refusedButKept(memory, code, build, GateMessage.IN_USE)
            DoorAnswer.OUTDATED -> refusedButKept(memory, code, build, GateMessage.OUTDATED)
            DoorAnswer.COULD_NOT_CHECK -> {
                if (memory.refusedBuild == build) {
                    // The door's last word on this build was no, and an outage of ours is no reason to forget
                    // it: no days of grace, the gate asks to connect.
                    AfterCheck(memory, opens = false, message = GateMessage.GRACE_OVER)
                } else {
                    // The days count from the first failed try on this build, and start again for the next.
                    val since = if (memory.graceBuild == build) memory.graceSince else now
                    val kept = memory.copy(code = code, graceBuild = build, graceSince = since)
                    if (over(since, now)) AfterCheck(kept, opens = false, message = GateMessage.GRACE_OVER)
                    else AfterCheck(kept, opens = true, message = null)
                }
            }
        }

    /** A yes: everything about the last trouble is forgotten, and the install number is kept. */
    private fun opened(memory: TesterMemory, code: String, build: String, now: Long, claimed: Boolean) = AfterCheck(
        TesterMemory(code = code, install = memory.install, openedBuild = build, openedAt = now, claimed = claimed),
        opens = true,
        message = null,
    )

    /** A refused code is not kept. One kept from before stays when it was another code that was refused. */
    private fun refused(memory: TesterMemory, code: String, message: GateMessage) =
        AfterCheck(memory.copy(code = memory.code?.takeIf { it != code }), opens = false, message = message)

    /**
     * A good code that this build may not use: it is kept, for the newest build or the device it moves to
     * to check with no typing, and the gate stays in front of this build, checking again behind it.
     */
    private fun refusedButKept(memory: TesterMemory, code: String, build: String, message: GateMessage) = AfterCheck(
        TesterMemory(code = code, install = memory.install, refusedBuild = build),
        opens = false,
        message = message,
    )

    /**
     * The door's last yes for this build is more than its days old, or was never written down (a memory
     * saved before the check came again has no time: due at once). A clock put back past the yes counts as
     * due too: a quiet check never locks anyone, and a yes written down with the right time ends it.
     */
    private fun recheckDue(memory: TesterMemory, now: Long): Boolean {
        if (memory.openedAt <= 0L) return true
        val age = now - memory.openedAt
        val stands = if (memory.claimed) CLAIM_RECHECK_MILLIS else RECHECK_MILLIS
        return age < 0 || age > stands
    }

    private fun graceOver(build: String, memory: TesterMemory, now: Long) =
        memory.graceBuild == build && over(memory.graceSince, now)

    /** A clock put back counts as within the days: an outage of ours never locks anyone out. */
    private fun over(since: Long, now: Long) = now - since >= GRACE_MILLIS
}
