/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.recap

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The way in to the Recap from anywhere in the app: the banner in Settings, the developer
 * options' row and the debug build's sample ask here for a year's Recap to open, and the one
 * place that draws it (RecapEntry, over everything in MainActivity) opens it and says it has.
 *
 * A request rather than a destination, as the questions' are (PollChecker.openRequested): the
 * Recap is a full-screen window over the app, the same whether it opened by itself on the reveal
 * day or from Settings, and closing it leaves the screen under it as it was.
 */
object RecapDoor {
    private val _requested = MutableStateFlow<Int?>(null)

    /** The year whose Recap was asked for and is not open yet, or null. */
    val requested: StateFlow<Int?> = _requested.asStateFlow()

    fun open(year: Int) {
        _requested.value = year
    }

    /** The request was taken: the Recap is open. */
    fun opened() {
        _requested.value = null
    }
}
