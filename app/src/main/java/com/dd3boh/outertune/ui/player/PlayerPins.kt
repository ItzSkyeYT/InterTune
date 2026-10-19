/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.ui.player

/**
 * A button the player can keep outside its menu (issue #27, for 0.13: "I would like the pinned
 * buttons to be configurable, so you could have other ones or even none pinned"): the four it has
 * always had, every entry of its menu, and Listen together (his word of 18 Oct 2026).
 */
enum class PlayerPin {
    SHARE, LYRICS, TIMER, LIKE,
    RADIO, ADD_TO_QUEUE, ADD_TO_PLAYLIST, REMOVE_FROM_PLAYLIST, DOWNLOAD, LIBRARY, ARTIST, ALBUM,
    DETAILS, EQUALIZER, ADVANCED, SIGNAL_PATH, RECENTRE, LISTEN_TOGETHER,
}

/** The menu's entries and Listen together, which stand further from the menu than any of the four, in the menu's own order. */
private val FromTheMenu = listOf(
    PlayerPin.RADIO, PlayerPin.ADD_TO_QUEUE, PlayerPin.ADD_TO_PLAYLIST, PlayerPin.REMOVE_FROM_PLAYLIST, PlayerPin.DOWNLOAD,
    PlayerPin.LIBRARY, PlayerPin.ARTIST, PlayerPin.ALBUM, PlayerPin.DETAILS, PlayerPin.EQUALIZER, PlayerPin.ADVANCED,
    PlayerPin.SIGNAL_PATH, PlayerPin.RECENTRE, PlayerPin.LISTEN_TOGETHER,
)

/**
 * Where the player pins buttons, with the order they stand in there and how many fit. The menu
 * always comes after them. The ones a place has always had stand last, beside the menu, so that one
 * added goes further out and none of those moves (memory: player controls stay put).
 */
enum class PinPlace(val order: List<PlayerPin>, val most: Int, val defaults: List<PlayerPin>) {
    /** Classic's circles beside the title: the sleep timer and like until somebody chooses. */
    CLASSIC(FromTheMenu + listOf(PlayerPin.SHARE, PlayerPin.LYRICS, PlayerPin.TIMER, PlayerPin.LIKE), 3, listOf(PlayerPin.TIMER, PlayerPin.LIKE)),

    /** Connected's pair beside the title: share and like. */
    CONNECTED_TITLE(FromTheMenu + listOf(PlayerPin.LYRICS, PlayerPin.TIMER, PlayerPin.SHARE, PlayerPin.LIKE), 2, listOf(PlayerPin.SHARE, PlayerPin.LIKE)),

    /** Connected's row under the controls: lyrics and the sleep timer, then the menu. */
    CONNECTED_ROW(FromTheMenu + listOf(PlayerPin.SHARE, PlayerPin.LIKE, PlayerPin.LYRICS, PlayerPin.TIMER), 3, listOf(PlayerPin.LYRICS, PlayerPin.TIMER)),
}

/**
 * The pins chosen, kept in one setting as "PLACE=PIN+PIN" joined by ";". A place never chosen has
 * its defaults, and a place chosen empty has none, which is the menu alone.
 */
object PlayerPins {
    fun read(raw: String): Map<PinPlace, List<PlayerPin>> {
        val chosen = raw.split(";").mapNotNull { part ->
            val at = part.indexOf('=')
            val place = if (at < 0) null else PinPlace.entries.firstOrNull { it.name == part.substring(0, at) }
            place?.let { it to part.substring(at + 1).split("+").mapNotNull { name -> PlayerPin.entries.firstOrNull { pin -> pin.name == name } } }
        }.toMap()
        // More than fit keeps the ones nearest the menu, the far ones being the ones added last.
        return PinPlace.entries.associateWith { place -> inOrder(place, chosen[place] ?: place.defaults).takeLast(place.most) }
    }

    fun write(pins: Map<PinPlace, List<PlayerPin>>): String =
        pins.entries.joinToString(";") { (place, chosen) -> place.name + "=" + chosen.joinToString("+") { it.name } }

    /**
     * [pins] with [pin] put on or taken off [place]. On, it takes its own spot in the place's order
     * when there is room for it ([most], fewer where Classic's queue circle takes one), and in
     * Connected it leaves the other place, a button being in one place at a time.
     */
    fun toggle(pins: Map<PinPlace, List<PlayerPin>>, place: PinPlace, pin: PlayerPin, most: Int = place.most): Map<PinPlace, List<PlayerPin>> {
        val now = pins[place].orEmpty()
        if (pin in now) return pins + (place to now - pin)
        if (now.size >= most) return pins
        val other = when (place) {
            PinPlace.CONNECTED_TITLE -> PinPlace.CONNECTED_ROW
            PinPlace.CONNECTED_ROW -> PinPlace.CONNECTED_TITLE
            PinPlace.CLASSIC -> null
        }
        val moved = if (other != null && pin in pins[other].orEmpty()) pins + (other to pins[other].orEmpty() - pin) else pins
        return moved + (place to inOrder(place, now + pin))
    }

    /** [pins] with [places] back to the buttons they have until somebody chooses. */
    fun reset(pins: Map<PinPlace, List<PlayerPin>>, places: List<PinPlace>): Map<PinPlace, List<PlayerPin>> =
        pins + places.associateWith { it.defaults }

    /** Whether any of [places] pins other buttons than it has until somebody chooses. */
    fun changed(pins: Map<PinPlace, List<PlayerPin>>, places: List<PinPlace>): Boolean =
        places.any { pins[it].orEmpty() != it.defaults }

    /** How many pins Classic's row takes beside the title: one fewer while the queue has a circle there. */
    fun classicMost(queueCircle: Boolean): Int = if (queueCircle) PinPlace.CLASSIC.most - 1 else PinPlace.CLASSIC.most

    private fun inOrder(place: PinPlace, chosen: List<PlayerPin>): List<PlayerPin> = place.order.filter { it in chosen }
}
