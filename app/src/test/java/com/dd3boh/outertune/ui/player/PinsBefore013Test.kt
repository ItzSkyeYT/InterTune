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
import com.dd3boh.outertune.constants.NewInstallSongCache
import com.dd3boh.outertune.constants.OobeStatusKey
import com.dd3boh.outertune.constants.ClassicPinsBefore013Key
import com.dd3boh.outertune.constants.PinsBefore013Key
import com.dd3boh.outertune.constants.PlayerPinsKey
import com.dd3boh.outertune.lyrics.LyricsTimingOnTime
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * 0.13 takes the sleep timer out of Connected's row by default (his word of 19 Nov 2026), and the update moves nobody's
 * buttons: an install set up before keeps the row it had, written down once. Each launch is the settings file opened
 * the way the app opens it (utils/DataStore.kt's migrations, in that order) and closed again; an older build's file is
 * written without them, as LyricsTimingOnTimeTest does.
 */
class PinsBefore013Test {

    @get:Rule
    val dir = TemporaryFolder()

    private val file: File by lazy { dir.root.resolve("settings.preferences_pb") }

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

    private fun connectedRow(prefs: Preferences) = PlayerPins.read(prefs[PlayerPinsKey].orEmpty())[PinPlace.CONNECTED_ROW]

    private fun classic(prefs: Preferences) = PlayerPins.read(prefs[PlayerPinsKey].orEmpty())[PinPlace.CLASSIC]

    @Test
    fun `an install set up before keeps the sleep timer by Classic's title, and a new one has the heart alone`() {
        olderBuildLeft { it[OobeStatusKey] = 4 }
        val first = launch()
        // The cast button joins it at the far end since 0.13 (CastPinTest).
        assertEquals(listOf(PlayerPin.CAST, PlayerPin.TIMER, PlayerPin.LIKE), classic(first))
        assertEquals(true, first[ClassicPinsBefore013Key])
        dir.root.resolve("settings.preferences_pb").delete()
        assertEquals(listOf(PlayerPin.CAST, PlayerPin.LIKE), classic(launch()))
    }

    @Test
    fun `an install that ran 19 Nov's keeping still gets Classic's, once`() {
        // The debug app and the Preview ran PinsBefore013 on 19 and 20 Nov, before Classic's default changed.
        olderBuildLeft {
            it[OobeStatusKey] = 4
            it[PinsBefore013Key] = true
            it[PlayerPinsKey] = "CONNECTED_ROW=LYRICS+TIMER"
        }
        val first = launch()
        assertEquals(listOf(PlayerPin.CAST, PlayerPin.TIMER, PlayerPin.LIKE), classic(first))
        assertEquals(listOf(PlayerPin.LYRICS, PlayerPin.TIMER), connectedRow(first))
        // Changed after the update: never written over.
        launch { it.edit { prefs -> prefs[PlayerPinsKey] = "CONNECTED_ROW=LYRICS+TIMER;CLASSIC=LIKE" } }
        assertEquals(listOf(PlayerPin.LIKE), classic(launch()))
    }

    @Test
    fun `an install set up before keeps the sleep timer in Connected's row`() {
        olderBuildLeft { it[OobeStatusKey] = 4 }
        val first = launch()
        assertEquals(listOf(PlayerPin.LYRICS, PlayerPin.TIMER), connectedRow(first))
        assertEquals(true, first[PinsBefore013Key])
        // The other places still follow their defaults, which did not change.
        assertEquals(listOf(PlayerPin.CAST, PlayerPin.SHARE, PlayerPin.LIKE), PlayerPins.read(first[PlayerPinsKey].orEmpty())[PinPlace.CONNECTED_TITLE])
    }

    @Test
    fun `a new install has the new default and is never written down later`() {
        val first = launch()
        assertNull(first[PlayerPinsKey])
        assertEquals(listOf(PlayerPin.LYRICS), connectedRow(first))
        // Setup runs, then the next launch: still the new default.
        open(newBuild = true) { it.edit { prefs -> prefs[OobeStatusKey] = 4 } }
        val next = launch()
        assertNull(next[PlayerPinsKey])
        assertEquals(listOf(PlayerPin.LYRICS), connectedRow(next))
    }

    @Test
    fun `a row already chosen is left as chosen`() {
        olderBuildLeft {
            it[OobeStatusKey] = 4
            it[PlayerPinsKey] = "CONNECTED_ROW=LIKE"
        }
        // Connected's row as chosen; Classic, never chosen, keeps the timer it had (ClassicPinsBefore013), and the cast
        // button joins it (CastPinAtUpdate).
        assertEquals("CLASSIC=CAST+TIMER+LIKE;CONNECTED_ROW=LIKE", launch()[PlayerPinsKey])
    }

    @Test
    fun `other places chosen keep their choice, and the row never chosen keeps its timer`() {
        olderBuildLeft {
            it[OobeStatusKey] = 4
            it[PlayerPinsKey] = "CLASSIC=SHARE+LIKE"
        }
        val first = launch()
        assertEquals(listOf(PlayerPin.CAST, PlayerPin.SHARE, PlayerPin.LIKE), PlayerPins.read(first[PlayerPinsKey].orEmpty())[PinPlace.CLASSIC])
        assertEquals(listOf(PlayerPin.LYRICS, PlayerPin.TIMER), connectedRow(first))
    }

    @Test
    fun `it runs once, so a row changed after the update is never written over`() {
        olderBuildLeft { it[OobeStatusKey] = 4 }
        launch { it.edit { prefs -> prefs[PlayerPinsKey] = "CONNECTED_ROW=" } }
        assertEquals(emptyList<PlayerPin>(), connectedRow(launch()))
    }

    @Test
    fun `the app opens its settings with it, after the new install's song cache`() {
        val source = File("src/main/java/com/dd3boh/outertune/utils/DataStore.kt").readText()
        assertEquals(true, source.contains("produceMigrations = { listOf(NewInstallSongCache, LyricsTimingOnTime, PinsBefore013, ClassicPinsBefore013, CastPinAtUpdate) },"))
    }
}
