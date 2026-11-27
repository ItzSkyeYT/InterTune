/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.ui.player

/**
 * The moves of the player buttons editor (Ti-Die's ask of 26 Nov 2026: "you have the top bar container, the bottom bar
 * container, and the rest of the unused buttons in a container, and then u just drag them where you want"): a button
 * put in a place, moved from one place to another, or taken back to Not shown. Every move goes through
 * [PlayerPins.toggle], so what is stored, and what the player draws from it, is what the chips stored before.
 */
object PinMoves {
    /** The place among [places] that shows [pin], or null while it is not shown. */
    fun placeOf(pins: Map<PinPlace, List<PlayerPin>>, places: List<PinPlace>, pin: PlayerPin): PinPlace? =
        places.firstOrNull { pin in pins[it].orEmpty() }

    /** How many more buttons [chosen] takes when [most] fit: the cast button takes no room. */
    fun free(chosen: List<PlayerPin>, most: Int): Int = (most - chosen.count { it != PlayerPin.CAST }).coerceAtLeast(0)

    /** Whether [pin] can go to [place]: it is there already, it is the cast button, or the place has room for it. */
    fun fits(pins: Map<PinPlace, List<PlayerPin>>, place: PinPlace, pin: PlayerPin, most: Int): Boolean {
        val chosen = pins[place].orEmpty()
        return pin in chosen || pin == PlayerPin.CAST || !PlayerPins.full(chosen, most)
    }

    /**
     * [pins] with [pin] in [to], or in none of [places] when [to] is null (Not shown); null when [to] is full, which
     * refuses the move. Wherever it is dropped, it takes its own spot in [to]'s order, the spot the player has always
     * given it there. Dropped back where it is, nothing changes.
     */
    fun move(
        pins: Map<PinPlace, List<PlayerPin>>,
        places: List<PinPlace>,
        pin: PlayerPin,
        to: PinPlace?,
        most: (PinPlace) -> Int,
    ): Map<PinPlace, List<PlayerPin>>? {
        val from = placeOf(pins, places, pin)
        return when {
            to == from -> pins
            to == null -> PlayerPins.toggle(pins, from!!, pin)
            !fits(pins, to, pin, most(to)) -> null
            else -> PlayerPins.toggle(if (from != null) PlayerPins.toggle(pins, from, pin) else pins, to, pin, most(to))
        }
    }
}
