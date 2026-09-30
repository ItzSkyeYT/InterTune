/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.playback

import com.dd3boh.outertune.playback.MediaControlButton.LIKE
import com.dd3boh.outertune.playback.MediaControlButton.RADIO
import com.dd3boh.outertune.playback.MediaControlButton.REPEAT
import com.dd3boh.outertune.playback.MediaControlButton.SHUFFLE

/**
 * A button the session offers the phone's media controls, beside previous, play and next.
 *
 * Saved by name, so the names must never change. The order here is only the order the settings
 * dialog lists them in.
 */
enum class MediaControlButton {
    LIKE, RADIO, SHUFFLE, REPEAT, LIBRARY
}

/**
 * Which buttons the session sends as its custom layout, and in what order.
 *
 * The notification, the lock screen and One UI's media panel draw the first two buttons of the
 * layout beside previous, play and next and drop everything after them. Like and radio were third
 * and fourth, so nobody ever saw them there. The order is the whole setting: the listener picks
 * which two go first, and the rest still follow for controllers that show more, Android Auto
 * among them.
 */
object MediaControlButtons {
    /** How many the system media controls show. */
    const val SLOTS = 2

    /** The two that were always first. Nobody who updates sees a change. */
    val DEFAULT: List<MediaControlButton> = listOf(SHUFFLE, REPEAT)

    /**
     * The layout as it was before this was a setting. Whatever was not picked follows the picked
     * two in this order. Add to library was never part of it, so it joins only when picked, and
     * the default gives exactly the layout the app always sent.
     */
    private val BEFORE: List<MediaControlButton> = listOf(SHUFFLE, REPEAT, LIKE, RADIO)

    /** The picked buttons first, then the rest of [BEFORE] in its own order. */
    fun layout(chosen: List<MediaControlButton>): List<MediaControlButton> {
        val first = chosen.distinct().take(SLOTS)
        return first + BEFORE.filterNot { it in first }
    }

    /**
     * Reads the saved setting. Always two different buttons: a name this version does not know,
     * from a later one or a backup edited by hand, is skipped, and a gap is filled from [DEFAULT].
     */
    fun parse(raw: String?): List<MediaControlButton> {
        val read = raw.orEmpty()
            .split(',')
            .mapNotNull { name -> MediaControlButton.entries.firstOrNull { it.name == name.trim() } }
            .distinct()
            .take(SLOTS)
        return (read + DEFAULT.filterNot { it in read }).take(SLOTS)
    }

    fun serialize(chosen: List<MediaControlButton>): String = chosen.joinToString(",") { it.name }

    /**
     * One tap in the settings dialog. A picked button is taken back, and the one after it moves
     * up. Any other fills the next free slot, and does nothing while both are taken.
     */
    fun toggle(picked: List<MediaControlButton>, button: MediaControlButton): List<MediaControlButton> = when {
        button in picked -> picked - button
        picked.size < SLOTS -> picked + button
        else -> picked
    }
}
