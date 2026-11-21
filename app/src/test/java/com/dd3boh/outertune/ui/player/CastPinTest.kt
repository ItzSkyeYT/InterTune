/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.ui.player

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import com.dd3boh.outertune.constants.CastPinAddedKey
import com.dd3boh.outertune.constants.OobeStatusKey
import com.dd3boh.outertune.constants.PlayerPinsKey
import com.dd3boh.outertune.ui.player.PlayerPin.CAST
import com.dd3boh.outertune.ui.player.PlayerPin.LIKE
import com.dd3boh.outertune.ui.player.PlayerPin.LYRICS
import com.dd3boh.outertune.ui.player.PlayerPin.SHARE
import com.dd3boh.outertune.ui.player.PlayerPin.TIMER
import com.dd3boh.outertune.lyrics.LyricsTimingOnTime
import com.dd3boh.outertune.constants.NewInstallSongCache
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * The cast button as one of the player's buttons since 0.13 (his word of 20 Nov 2026: "fix the cast button layout or
 * like put it somewhere as right now it looks bad on both portrait and landscape"): by the title in Classic and
 * Connected until someone moves it, at the far end so nothing else moves, taking no room from the others, in the menu
 * while it is pinned nowhere, and added once to the buttons of an install set up before, whose places were chosen.
 */
class CastPinTest {

    @get:Rule
    val dir = TemporaryFolder()

    private val file: File by lazy { dir.root.resolve("settings.preferences_pb") }
    private val source = File("src/main/java/com/dd3boh/outertune")

    private fun <T> open(newBuild: Boolean, block: suspend (DataStore<Preferences>) -> T): T {
        val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        return try {
            val migrations = if (newBuild) listOf(NewInstallSongCache, LyricsTimingOnTime, PinsBefore013, ClassicPinsBefore013, CastPinAtUpdate) else emptyList()
            val settings = PreferenceDataStoreFactory.create(migrations = migrations, scope = scope) { file }
            runBlocking { block(settings) }
        } finally {
            runBlocking { scope.coroutineContext[Job]!!.cancelAndJoin() }
        }
    }

    private fun launch(then: suspend (DataStore<Preferences>) -> Unit = {}): Preferences =
        open(newBuild = true) { settings -> settings.data.first().also { then(settings) } }

    private fun olderBuildLeft(settings: (MutablePreferences) -> Unit) {
        open(newBuild = false) { older -> older.edit { settings(it) } }
    }

    private fun pins(prefs: Preferences) = PlayerPins.read(prefs[PlayerPinsKey].orEmpty())

    @Test
    fun `by the title in both styles, at the far end, so the buttons beside the menu stay where they were`() {
        val pins = PlayerPins.read("")
        assertEquals(listOf(CAST, LIKE), pins[PinPlace.CLASSIC])
        assertEquals(listOf(CAST, SHARE, LIKE), pins[PinPlace.CONNECTED_TITLE])
        assertEquals("Connected's row as it was", listOf(LYRICS), pins[PinPlace.CONNECTED_ROW])
        for (place in PinPlace.entries) for (pin in listOf(SHARE, LYRICS, TIMER, LIKE))
            assertTrue("outside $pin in $place", place.order.indexOf(CAST) < place.order.indexOf(pin))
    }

    @Test
    fun `it takes no room from the others`() {
        val full = PlayerPins.read("CLASSIC=SHARE+TIMER+LIKE")
        assertTrue(PlayerPins.full(full[PinPlace.CLASSIC]!!, PinPlace.CLASSIC.most))
        val added = PlayerPins.toggle(full, PinPlace.CLASSIC, CAST)
        assertEquals(listOf(CAST, SHARE, TIMER, LIKE), added[PinPlace.CLASSIC])
        assertEquals("read back as written", added[PinPlace.CLASSIC], PlayerPins.read(PlayerPins.write(added))[PinPlace.CLASSIC])
        // Beside Classic's queue circle there is room for one fewer: the far one goes, the cast button stays.
        assertEquals(listOf(CAST, TIMER, LIKE), PlayerPins.fit(PinPlace.CLASSIC, added[PinPlace.CLASSIC]!!, PlayerPins.classicMost(queueCircle = true)))
        // Connected's pair stays a pair beside it.
        val title = PlayerPins.toggle(PlayerPins.read(""), PinPlace.CONNECTED_TITLE, TIMER)
        assertEquals(listOf(CAST, SHARE, LIKE), title[PinPlace.CONNECTED_TITLE])
    }

    @Test
    fun `pinned nowhere in the style shown, it is in the menu`() {
        val classic = listOf(PinPlace.CLASSIC)
        val connected = listOf(PinPlace.CONNECTED_TITLE, PinPlace.CONNECTED_ROW)
        assertTrue(PlayerPins.castPinned(PlayerPins.read(""), classic))
        assertTrue(PlayerPins.castPinned(PlayerPins.read(""), connected))
        val off = PlayerPins.toggle(PlayerPins.read(""), PinPlace.CLASSIC, CAST)
        assertFalse(PlayerPins.castPinned(off, classic))
        assertTrue("Connected keeps its own", PlayerPins.castPinned(off, connected))
        val inRow = PlayerPins.toggle(PlayerPins.read(""), PinPlace.CONNECTED_ROW, CAST)
        assertTrue("moved to Connected's row, still pinned", PlayerPins.castPinned(inRow, connected))
        val menu = File(source, "ui/menu/PlayerMenu.kt").readText()
        assertTrue("val castInMenu = !songHeader && !PlayerPins.castPinned(PlayerPins.read(pinsRaw), castPlaces)" in menu)
        assertTrue("if (castLook != null) {" in menu && "title = R.string.cast" in menu)
    }

    @Test
    fun `a new install follows the defaults and is never written down`() {
        val first = launch()
        assertNull(first[PlayerPinsKey])
        assertEquals(true, first[CastPinAddedKey])
        open(newBuild = true) { it.edit { prefs -> prefs[OobeStatusKey] = 4 } }
        val next = launch()
        assertNull(next[PlayerPinsKey])
        assertEquals(listOf(CAST, LIKE), pins(next)[PinPlace.CLASSIC])
    }

    @Test
    fun `an install set up before gets it in each place it chose, the others where they were`() {
        olderBuildLeft {
            it[OobeStatusKey] = 4
            it[PlayerPinsKey] = "CLASSIC=SHARE+TIMER+LIKE;CONNECTED_TITLE=LIKE"
        }
        val first = launch()
        assertEquals("a full place too: it takes no room", listOf(CAST, SHARE, TIMER, LIKE), pins(first)[PinPlace.CLASSIC])
        assertEquals(listOf(CAST, LIKE), pins(first)[PinPlace.CONNECTED_TITLE])
        assertEquals("the row never chosen keeps its timer (PinsBefore013)", listOf(LYRICS, TIMER), pins(first)[PinPlace.CONNECTED_ROW])
    }

    @Test
    fun `a place chosen empty gets it too, an update never takes the way to the cast sheet away`() {
        olderBuildLeft {
            it[OobeStatusKey] = 4
            it[PlayerPinsKey] = "CLASSIC="
        }
        assertEquals(listOf(CAST), pins(launch())[PinPlace.CLASSIC])
    }

    @Test
    fun `once only, so taken off after the update it stays off and Cast is in the menu`() {
        olderBuildLeft {
            it[OobeStatusKey] = 4
            it[PlayerPinsKey] = "CLASSIC=LIKE"
        }
        launch { it.edit { prefs -> prefs[PlayerPinsKey] = "CLASSIC=LIKE" } }
        val after = launch()
        assertEquals(listOf(LIKE), pins(after)[PinPlace.CLASSIC])
        assertFalse(PlayerPins.castPinned(pins(after), listOf(PinPlace.CLASSIC)))
    }

    @Test
    fun `only places chosen are written, the others keep following their defaults`() {
        assertEquals("CLASSIC=CAST+LIKE", CastPinAtUpdate.withCast("CLASSIC=LIKE"))
        assertEquals("CONNECTED_TITLE=CAST+SHARE", CastPinAtUpdate.withCast("CONNECTED_TITLE=SHARE"))
        assertEquals("already in Connected's row: left alone", "CONNECTED_TITLE=SHARE;CONNECTED_ROW=CAST+LYRICS", CastPinAtUpdate.withCast("CONNECTED_TITLE=SHARE;CONNECTED_ROW=CAST+LYRICS"))
        assertEquals("CONNECTED_ROW=LIKE", CastPinAtUpdate.withCast("CONNECTED_ROW=LIKE"))
    }

    @Test
    fun `the player draws it in each place, and the whole player has one cast sheet`() {
        val player = File(source, "ui/player/Player.kt").readText()
        assertTrue("PlayerPin.CAST -> castCircle()" in player)
        assertEquals("by the title and in Connected's row", 2, Regex("""PlayerPin\.CAST -> castLook\?\.let \{ cast ->""").findAll(player).count())
        assertTrue(".filter(::drawable)" in player)
        assertTrue("PlayerPins.fit(PinPlace.CLASSIC, pins[PinPlace.CLASSIC].orEmpty(), PlayerPins.classicMost(queueCircle))" in player)
        assertEquals(1, Regex("""\bCastPickerHost\(\)""").findAll(player).count())
        // The button at a tablet's top right and the pill open it; neither puts up its own any more.
        val picker = File(source, "ui/player/CastPicker.kt").readText()
        assertEquals("only the host composes the sheet", 1, Regex("""\n\s+CastPicker\(session""").findAll(picker).count())
        assertTrue(picker.substringAfter("fun CastPickerHost()").substringBefore("\n}\n").contains("CastPicker(session"))
        // Settings: a chip for it, always with room.
        val settings = File(source, "ui/screens/settings/fragments/PlayerButtonsFrag.kt").readText()
        assertTrue("enabled = on || pin == PlayerPin.CAST || !PlayerPins.full(chosen, most)," in settings)
        assertTrue("PlayerPin.CAST -> R.string.cast" in settings)
    }

    @Test
    fun `the app opens its settings with it, after Classic's place is written down`() {
        val dataStore = File(source, "utils/DataStore.kt").readText()
        assertTrue("produceMigrations = { listOf(NewInstallSongCache, LyricsTimingOnTime, PinsBefore013, ClassicPinsBefore013, CastPinAtUpdate, NeverHeardToDiscover) }," in dataStore)
    }
}
