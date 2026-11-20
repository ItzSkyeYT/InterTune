/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.ui.player

import androidx.datastore.core.DataMigration
import androidx.datastore.preferences.core.Preferences
import com.dd3boh.outertune.constants.OobeStatusKey
import com.dd3boh.outertune.constants.ClassicPinsBefore013Key
import com.dd3boh.outertune.constants.PinsBefore013Key
import com.dd3boh.outertune.constants.PlayerPinsKey

/**
 * A button the player can keep outside its menu (issue #27, for 0.13: "I would like the pinned
 * buttons to be configurable, so you could have other ones or even none pinned").
 */
enum class PlayerPin { SHARE, LYRICS, TIMER, LIKE }

/**
 * Where the player pins buttons, with the order they stand in there and how many fit. The menu
 * always comes after them. The ones a place has always had stand last, beside the menu, so that one
 * added goes further out and none of those moves (memory: player controls stay put).
 */
enum class PinPlace(val order: List<PlayerPin>, val most: Int, val defaults: List<PlayerPin>) {
    /**
     * Classic's circles beside the title: like until somebody chooses, the sleep timer in the menu as in Connected since
     * 0.13 (his pick of 20 Nov 2026); an install set up before keeps the timer it had there ([ClassicPinsBefore013]).
     */
    CLASSIC(listOf(PlayerPin.SHARE, PlayerPin.LYRICS, PlayerPin.TIMER, PlayerPin.LIKE), 3, listOf(PlayerPin.LIKE)),

    /** Connected's pair beside the title: share and like. */
    CONNECTED_TITLE(listOf(PlayerPin.LYRICS, PlayerPin.TIMER, PlayerPin.SHARE, PlayerPin.LIKE), 2, listOf(PlayerPin.SHARE, PlayerPin.LIKE)),

    /**
     * Connected's row under the controls: lyrics, then the menu. The sleep timer is in the menu by default since 0.13 (his
     * word of 19 Nov 2026, "the sleep timer should be by default in the three dot menu"); an install set up before keeps
     * the timer it had there ([PinsBefore013]).
     */
    CONNECTED_ROW(listOf(PlayerPin.SHARE, PlayerPin.LIKE, PlayerPin.LYRICS, PlayerPin.TIMER), 3, listOf(PlayerPin.LYRICS)),
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

/**
 * The player's buttons as an install set up before 0.13 has them, written once so that the update moves none of them
 * (memory: player controls stay put): 0.13 took the sleep timer out of Connected's row by default, and a place nobody
 * chose follows the defaults. Only on an install that has been through setup ([OobeStatusKey] set) and for the places
 * whose defaults changed, when it never chose them; a new install has the new defaults. Run as the settings are first
 * read, after NewInstallSongCache, which needs them empty. Once: [PinsBefore013Key] says so.
 */
object PinsBefore013 : PinsKeptAtUpdate(PinsBefore013Key, mapOf(PinPlace.CONNECTED_ROW to listOf(PlayerPin.LYRICS, PlayerPin.TIMER)))

/**
 * The same for Classic, whose default lost the sleep timer a day later (20 Nov 2026), with a key of its own: the debug
 * app and the Preview had already run [PinsBefore013] by then, and an install set up before still keeps its timer.
 */
object ClassicPinsBefore013 : PinsKeptAtUpdate(ClassicPinsBefore013Key, mapOf(PinPlace.CLASSIC to listOf(PlayerPin.TIMER, PlayerPin.LIKE)))

/** A place whose defaults changed, written down as it was for an install set up before, once ([key] says so). */
open class PinsKeptAtUpdate(private val key: Preferences.Key<Boolean>, val BEFORE: Map<PinPlace, List<PlayerPin>>) : DataMigration<Preferences> {
    override suspend fun shouldMigrate(currentData: Preferences) = currentData[key] != true

    override suspend fun migrate(currentData: Preferences): Preferences =
        currentData.toMutablePreferences().apply {
            if (this[OobeStatusKey] != null) {
                val raw = this[PlayerPinsKey].orEmpty()
                val chosen = raw.split(";").map { it.substringBefore('=') }.toSet()
                val keep = BEFORE.filterKeys { it.name !in chosen }
                if (keep.isNotEmpty()) this[PlayerPinsKey] = listOf(raw, PlayerPins.write(keep)).filter { it.isNotBlank() }.joinToString(";")
            }
            this[key] = true
        }.toPreferences()

    override suspend fun cleanUp() = Unit
}

