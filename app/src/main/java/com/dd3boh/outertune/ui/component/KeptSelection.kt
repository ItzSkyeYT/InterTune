/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.ui.component

import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.toMutableStateList

/**
 * What is kept of a list's selection when the app goes to the background or the page is left for
 * another: every id, up to [MOST]. Above that nothing is kept. Select all on a very large list put
 * every id into the state the app hands Android (16,000 songs are about 500 KB), and past what a
 * binder call carries (#107 closed the app at 527 KB) the app closed as it went to the background,
 * and the music with it. A selection too large to keep comes back empty, and its page leaves
 * select mode.
 */
internal object KeptSelection {
    /** About 64 KB of song ids at most. */
    const val MOST = 2000

    fun <T : Any> saver(): Saver<MutableList<T>, Any> = listSaver<MutableList<T>, T>(
        save = { if (it.size > MOST) emptyList() else it.toList() },
        restore = { it.toMutableStateList() },
    )
}
