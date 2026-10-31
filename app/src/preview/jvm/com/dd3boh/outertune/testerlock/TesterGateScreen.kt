/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.testerlock

/** What the gate's big button is. */
enum class GateMain {
    /** Open: ask the door about the code in the field. */
    OPEN,

    /** Open #testers: the channel on Discord, where the newest build and the code's own button are. */
    OPEN_TESTERS,
}

/**
 * What the gate shows for one answer of the door, as a plain description, so that each answer's screen is
 * tested without a phone. TesterGate.kt draws it and decides nothing.
 */
data class GateScreen(
    /** The code field is there, with Paste at its end while it is empty. Without it the answer is the body text. */
    val field: Boolean,
    val main: GateMain,
    /** What the door said: under the field when there is one, and in the body in place of the lead when there is not. */
    val message: GateMessage?,
    /** The quiet line under the big button: the code is fine, only the build is old. */
    val codeIsFine: Boolean,
    /** The text button that brings the field back, for a code another device holds. */
    val anotherCode: Boolean,
    /** The line that says where a code comes from. */
    val help: Boolean,
)

object TesterGateScreen {
    /**
     * A code the door refused, or one that could not be checked, keeps the field: the tester has a code to
     * change. A good code the door refused the build with has none, since the code is not the trouble:
     * out of date, the build needs updating, and in use, the code needs moving, or another code.
     */
    fun of(message: GateMessage?): GateScreen = when (message) {
        GateMessage.OUTDATED -> GateScreen(
            field = false, main = GateMain.OPEN_TESTERS, message = message, codeIsFine = true, anotherCode = false, help = false,
        )
        GateMessage.IN_USE -> GateScreen(
            field = false, main = GateMain.OPEN_TESTERS, message = message, codeIsFine = false, anotherCode = true, help = false,
        )
        else -> GateScreen(
            field = true, main = GateMain.OPEN, message = message, codeIsFine = false, anotherCode = false, help = true,
        )
    }

    /** Paste is offered at the end of the field while it is empty, and not while a check is under way. */
    fun pasteShown(screen: GateScreen, fieldEmpty: Boolean, busy: Boolean): Boolean = screen.field && fieldEmpty && !busy
}
