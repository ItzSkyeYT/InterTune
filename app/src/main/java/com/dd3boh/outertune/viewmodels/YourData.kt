/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.viewmodels

/*
 * The rules behind Your data, apart from the screen so they can be tested: which buttons ask before
 * they act, what they ask with, and what each says it did in place of its description.
 */

/** The buttons on Your data. */
enum class DataAction { FORGET_SESSION, FORGET_TODAY, RESET, REBUILD, SAVE, LOAD }

/**
 * Everything that changes what it has learned asks first. Saving a copy changes nothing, so it
 * goes straight to the file picker.
 */
fun asksFirst(action: DataAction): Boolean = action != DataAction.SAVE

/** A change waiting for a yes, with what the dialog names: how much, and for a session, when it began. */
sealed interface DataAsk {
    val action: DataAction

    data class ForgetSession(val sessionId: Long, val listens: Int, val began: Long) : DataAsk {
        override val action get() = DataAction.FORGET_SESSION
    }

    data class ForgetToday(val listens: Int) : DataAsk {
        override val action get() = DataAction.FORGET_TODAY
    }

    /** [cards]: the cards it has learned from, which stay, so Rebuild can bring the learning back. */
    data class Reset(val cards: Int) : DataAsk {
        override val action get() = DataAction.RESET
    }

    /** [cards]: the cards it will learn from again. */
    data class Rebuild(val cards: Int) : DataAsk {
        override val action get() = DataAction.REBUILD
    }

    /** Asked before the file picker opens: the copy chosen there replaces what it has learned. */
    data object Load : DataAsk {
        override val action get() = DataAction.LOAD
    }
}

/** What a button did, shown in place of its description until the page is left. */
sealed interface DataResult {
    data object Working : DataResult

    /** [listens] is 0 when there was nothing to forget. */
    data class Forgot(val listens: Int) : DataResult
    data object ResetDone : DataResult
    data object AlreadyAtStart : DataResult
    data class Rebuilt(val cards: Int) : DataResult
    data object Saved : DataResult
    data object Loaded : DataResult

    /** Something went wrong with the data; try again. */
    data object Failed : DataResult

    /** The file could not be written, or read as a copy. */
    data object FileFailed : DataResult
}

/** After a tap: ask first, or say straight away that there is nothing to do. */
sealed interface DataStep {
    data class Ask(val ask: DataAsk) : DataStep
    data class Tell(val result: DataResult) : DataStep
}

/**
 * The last session: the one playing now, or the latest one. With no listens that still teach in
 * it, there is nothing to ask about, and the line says so at once rather than after a dialog.
 */
fun forgetSessionStep(sessionId: Long?, listens: Int, began: Long): DataStep =
    if (sessionId == null || listens <= 0) DataStep.Tell(DataResult.Forgot(0))
    else DataStep.Ask(DataAsk.ForgetSession(sessionId, listens, began))

fun forgetTodayStep(listens: Int): DataStep =
    if (listens <= 0) DataStep.Tell(DataResult.Forgot(0)) else DataStep.Ask(DataAsk.ForgetToday(listens))

/** With nothing learned it is already where a reset would put it, so there is nothing to ask. */
fun resetStep(learnedAnything: Boolean, cards: Int): DataStep =
    if (!learnedAnything) DataStep.Tell(DataResult.AlreadyAtStart) else DataStep.Ask(DataAsk.Reset(cards))

/** A rebuild always asks: even from no cards it replaces what is there, a loaded copy included. */
fun rebuildStep(cards: Int): DataStep = DataStep.Ask(DataAsk.Rebuild(cards))

fun loadStep(): DataStep = DataStep.Ask(DataAsk.Load)

/**
 * A new tap clears what the button said last time, and only that: "Nothing to forget." from an
 * hour ago stood under the button while its new dialog asked to forget 14 listens.
 */
fun clearedFor(results: Map<DataAction, DataResult>, action: DataAction): Map<DataAction, DataResult> = results - action

/** Null when forgetting failed. */
fun forgotResult(listens: Int?): DataResult = listens?.let { DataResult.Forgot(it) } ?: DataResult.Failed

/**
 * Today, for Forget today's listening: from local midnight, in the zone the phone is in now
 * ([offsetMs] from UTC), to just after [now].
 */
fun today(now: Long, offsetMs: Int): LongRange {
    val midnight = Math.floorDiv(now + offsetMs, DAY_MS) * DAY_MS - offsetMs
    return midnight..now
}

private const val DAY_MS = 86_400_000L
