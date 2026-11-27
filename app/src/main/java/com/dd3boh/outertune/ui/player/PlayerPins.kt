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
import com.dd3boh.outertune.constants.CastPinAddedKey
import com.dd3boh.outertune.constants.PlayerButtonsStyle
import com.dd3boh.outertune.constants.PlayerPinsKey

/**
 * A button the player can keep outside its menu (issue #27, for 0.13: "I would like the pinned
 * buttons to be configurable, so you could have other ones or even none pinned").
 *
 * CAST is the cast button, one of them since 0.13 (his word of 20 Nov 2026: "fix the cast button layout or like put
 * it somewhere as right now it looks bad on both portrait and landscape"): by the title until someone moves it, at
 * the far end, so the others stay where they were; in the menu while it is pinned nowhere.
 */
enum class PlayerPin { SHARE, LYRICS, TIMER, LIKE, CAST }

/**
 * Where the player pins buttons, with the order they stand in there and how many fit. The menu
 * always comes after them. The ones a place has always had stand last, beside the menu, so that one
 * added goes further out and none of those moves (memory: player controls stay put).
 *
 * The cast button stands outside the four a place has always had, so they stay where they were, and takes none of
 * [most]'s room, as a room's button takes none: it came from the top right, and no button of anyone's makes way for it.
 */
enum class PinPlace(val order: List<PlayerPin>, val most: Int, val defaults: List<PlayerPin>) {
    /**
     * Classic's circles beside the title: like until somebody chooses, the sleep timer in the menu as in Connected since
     * 0.13 (his pick of 20 Nov 2026); an install set up before keeps the timer it had there ([ClassicPinsBefore013]).
     */
    CLASSIC(listOf(PlayerPin.CAST, PlayerPin.SHARE, PlayerPin.LYRICS, PlayerPin.TIMER, PlayerPin.LIKE), 3, listOf(PlayerPin.CAST, PlayerPin.LIKE)),

    /** Connected's pair beside the title: share and like, and the cast button in front of them. */
    CONNECTED_TITLE(listOf(PlayerPin.CAST, PlayerPin.LYRICS, PlayerPin.TIMER, PlayerPin.SHARE, PlayerPin.LIKE), 2, listOf(PlayerPin.CAST, PlayerPin.SHARE, PlayerPin.LIKE)),

    /**
     * Connected's row under the controls: lyrics, then the menu. The sleep timer is in the menu by default since 0.13 (his
     * word of 19 Nov 2026, "the sleep timer should be by default in the three dot menu"); an install set up before keeps
     * the timer it had there ([PinsBefore013]).
     */
    CONNECTED_ROW(listOf(PlayerPin.CAST, PlayerPin.SHARE, PlayerPin.LIKE, PlayerPin.LYRICS, PlayerPin.TIMER), 3, listOf(PlayerPin.LYRICS)),
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
        return PinPlace.entries.associateWith { place -> fit(place, chosen[place] ?: place.defaults, place.most) }
    }

    /**
     * [chosen] in [place]'s order with no more than [most] besides the cast button: the far ones go, the ones by the
     * menu stay, and the cast button, which takes no room from them, stays where the order puts it.
     */
    fun fit(place: PinPlace, chosen: List<PlayerPin>, most: Int): List<PlayerPin> {
        val ordered = inOrder(place, chosen)
        val kept = ordered.filter { it != PlayerPin.CAST }.takeLast(most).toSet()
        return ordered.filter { it == PlayerPin.CAST || it in kept }
    }

    /** Whether the cast button is pinned in any of [places], those of the style shown; when not, the menu has Cast. */
    fun castPinned(pins: Map<PinPlace, List<PlayerPin>>, places: List<PinPlace>): Boolean =
        places.any { PlayerPin.CAST in pins[it].orEmpty() }

    fun write(pins: Map<PinPlace, List<PlayerPin>>): String =
        pins.entries.joinToString(";") { (place, chosen) -> place.name + "=" + chosen.joinToString("+") { it.name } }

    /**
     * [pins] with [pin] put on or taken off [place]. On, it takes its own spot in the place's order
     * when there is room for it ([most], fewer where Classic's queue circle takes one), and in
     * Connected it leaves the other place, a button being in one place at a time. The cast button always has room.
     */
    fun toggle(pins: Map<PinPlace, List<PlayerPin>>, place: PinPlace, pin: PlayerPin, most: Int = place.most): Map<PinPlace, List<PlayerPin>> {
        val now = pins[place].orEmpty()
        if (pin in now) return pins + (place to now - pin)
        if (pin != PlayerPin.CAST && full(now, most)) return pins
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

    /** Whether [chosen] has [most] buttons already, the cast button not counted. */
    fun full(chosen: List<PlayerPin>, most: Int): Boolean = chosen.count { it != PlayerPin.CAST } >= most

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

/**
 * The cast button among the buttons of an install set up before, once ([CastPinAddedKey]): until 0.13 it was at the
 * top right or in the pill under the controls, and an update never takes a way to it away. A place never chosen has it
 * already (its defaults); a place chosen, Classic's or Connected's by the title, gets it at its far end, the others
 * left where they are, unless Connected has it in its row. After this, taking it off stays taken off. Run after
 * [ClassicPinsBefore013], which writes Classic's place down.
 */
object CastPinAtUpdate : DataMigration<Preferences> {
    override suspend fun shouldMigrate(currentData: Preferences) = currentData[CastPinAddedKey] != true

    override suspend fun migrate(currentData: Preferences): Preferences =
        currentData.toMutablePreferences().apply {
            val raw = this[PlayerPinsKey].orEmpty()
            if (this[OobeStatusKey] != null && raw.isNotBlank()) this[PlayerPinsKey] = withCast(raw)
            this[CastPinAddedKey] = true
        }.toPreferences()

    override suspend fun cleanUp() = Unit

    /** [raw] with the cast button added to each place chosen that should have it. */
    internal fun withCast(raw: String): String {
        val chosen = raw.split(";").map { it.substringBefore('=') }.toSet()
        var pins = PlayerPins.read(raw)
        if (PinPlace.CLASSIC.name in chosen && PlayerPin.CAST !in pins[PinPlace.CLASSIC].orEmpty())
            pins = pins + (PinPlace.CLASSIC to pins[PinPlace.CLASSIC].orEmpty() + PlayerPin.CAST)
        if (PinPlace.CONNECTED_TITLE.name in chosen && !PlayerPins.castPinned(pins, listOf(PinPlace.CONNECTED_TITLE, PinPlace.CONNECTED_ROW)))
            pins = pins + (PinPlace.CONNECTED_TITLE to pins[PinPlace.CONNECTED_TITLE].orEmpty() + PlayerPin.CAST)
        // Only the places that were chosen are written: the others keep following their defaults.
        return PlayerPins.write(pins.filterKeys { it.name in chosen }.mapValues { (place, list) -> PlayerPins.fit(place, list, place.most) })
    }
}

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


/** Where the three-dots button stands: at the top of the player (by the title) or at the bottom (under the controls). */
enum class MenuPlace { TOP, BOTTOM }

/**
 * The three-dots button's place, kept per style as "STYLE=PLACE" joined by ";". It has no "none": a style never chosen
 * has it where it has always been, Classic's circles at the top and Connected's row at the bottom, so no player changes
 * until somebody moves it, and a setting that cannot be read is the same.
 */
object MenuPlaces {
    fun defaultFor(style: PlayerButtonsStyle): MenuPlace =
        if (style == PlayerButtonsStyle.CLASSIC) MenuPlace.TOP else MenuPlace.BOTTOM

    private fun parse(raw: String): Map<PlayerButtonsStyle, MenuPlace> = raw.split(";").mapNotNull { part ->
        val at = part.indexOf('=')
        if (at < 0) return@mapNotNull null
        val style = PlayerButtonsStyle.entries.firstOrNull { it.name == part.substring(0, at) }
        val place = MenuPlace.entries.firstOrNull { it.name == part.substring(at + 1) }
        if (style != null && place != null) style to place else null
    }.toMap()

    fun read(raw: String, style: PlayerButtonsStyle): MenuPlace = parse(raw)[style] ?: defaultFor(style)

    fun write(raw: String, style: PlayerButtonsStyle, place: MenuPlace): String =
        (parse(raw) + (style to place)).entries.joinToString(";") { (s, p) -> s.name + "=" + p.name }

    /**
     * The bar of buttons the three-dots button ends at [place], as the player draws it, for the buttons editor's boxes:
     * Classic's circles at the top and none at the bottom, where it stands under the controls on its own; Connected's
     * pair by the title at the top, its row at the bottom.
     */
    fun barOf(style: PlayerButtonsStyle, place: MenuPlace): PinPlace? = when (style) {
        PlayerButtonsStyle.CLASSIC -> if (place == MenuPlace.TOP) PinPlace.CLASSIC else null
        PlayerButtonsStyle.CONNECTED -> if (place == MenuPlace.TOP) PinPlace.CONNECTED_TITLE else PinPlace.CONNECTED_ROW
    }
}
