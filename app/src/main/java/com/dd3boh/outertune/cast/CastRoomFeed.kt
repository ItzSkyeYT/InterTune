/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.cast

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The Listen together room this phone is in, as a TV's screen shows it, put here by whatever holds
 * the room and read by the cast session. Null while there is no room, so a build without rooms
 * (they are a preview feature) simply never fills it, and the TV never shows one.
 */
object CastRoomFeed {
    private val _room = MutableStateFlow<CastRoom?>(null)
    val room: StateFlow<CastRoom?> = _room.asStateFlow()

    /** The room as it is now, or null once it is closed or left. */
    fun set(room: CastRoom?) {
        _room.value = room
    }
}
