/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.together

/**
 * Where a guest's change to the room's queue lands in the queue of the person in control (TogetherMessage.Arrange). A
 * guest's copy holds the room's songs from the one playing on; the queue of the person in control can hold more: the
 * songs played before, songs from files on that phone, more than the room is sent. So songs are named by their ids, a
 * move by the song it comes after, and each is looked for after the song playing, the first time it comes there. The
 * song playing itself is never moved or taken out by a guest: what plays now is the person in control's.
 */
internal object RoomArrange {
    /** Where [songId] is in [ids] (the queue, in its order) after the song playing at [current], or null when it is not. */
    fun placeOf(ids: List<String>, songId: String, current: Int): Int? =
        ids.indices.firstOrNull { it > current && ids[it] == songId }

    /**
     * Where the song at [from] goes so that it comes right after [after], or right after the song playing at [current]
     * when that is null, as Player.moveMediaItem counts the place: in the queue without the song that moves. Null when
     * [after] is not in the queue from the song playing on.
     */
    fun moveTo(ids: List<String>, from: Int, after: String?, current: Int): Int? {
        val anchor = if (after == null) current else ids.indices.firstOrNull { it >= current && it != from && ids[it] == after } ?: return null
        return if (from > anchor) anchor + 1 else anchor
    }
}
