/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.constants

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import com.dd3boh.outertune.engine.ContextChip
import com.dd3boh.outertune.engine.Lean
import com.dd3boh.outertune.lyrics.LyricsTimingOnTime
import com.dd3boh.outertune.ui.player.CastPinAtUpdate
import com.dd3boh.outertune.ui.player.ClassicPinsBefore013
import com.dd3boh.outertune.ui.player.PinsBefore013
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * New songs only and the Never heard choice were taken out of Quick picks on 21 Nov 2026, and whoever had
 * either on lands on the Discover chip, songs never heard by one rule. Each launch is the settings file
 * opened the way the app opens it (utils/DataStore.kt's migrations, in that order) and closed again; an
 * older build's file is written without them, as PinsBefore013Test does.
 */
class NeverHeardToDiscoverTest {

    @get:Rule
    val dir = TemporaryFolder()

    private val file: File by lazy { dir.root.resolve("settings.preferences_pb") }

    /** The old switch, by the name the older builds stored it under. */
    private val newSongsOnly = booleanPreferencesKey("newSongsOnly")

    private fun <T> open(newBuild: Boolean, block: suspend (DataStore<Preferences>) -> T): T {
        val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        return try {
            val migrations = if (newBuild) listOf(NewInstallSongCache, LyricsTimingOnTime, PinsBefore013, ClassicPinsBefore013, CastPinAtUpdate, NeverHeardToDiscover) else emptyList()
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

    @Test
    fun `New songs only on lands on Discover, whatever chip was on, and the switch is gone`() {
        olderBuildLeft { it[OobeStatusKey] = 4; it[newSongsOnly] = true; it[ContextChipKey] = ContextChip.CHILL }
        val first = launch()
        assertEquals(ContextChip.DISCOVER, first[ContextChipKey])
        assertNull(first[newSongsOnly])
        assertEquals(4, first[OobeStatusKey])
    }

    @Test
    fun `New songs only off goes, and the chip stays where it was`() {
        olderBuildLeft { it[OobeStatusKey] = 4; it[newSongsOnly] = false; it[ContextChipKey] = ContextChip.FAVOURITES }
        val first = launch()
        assertEquals(ContextChip.FAVOURITES, first[ContextChipKey])
        assertNull(first[newSongsOnly])
    }

    @Test
    fun `Never heard lands on Discover, and the choice is Auto again`() {
        olderBuildLeft { it[OobeStatusKey] = 4; it[QuickPicksLeanKey] = "NEW" }
        val first = launch()
        assertEquals(ContextChip.DISCOVER, first[ContextChipKey])
        assertNull(first[QuickPicksLeanKey])
        assertEquals(Lean.AUTO, Lean.ofName(first[QuickPicksLeanKey]))
    }

    @Test
    fun `any other choice and chip are left as they were`() {
        for (lean in listOf("AUTO", "ARTIST", "FORGOTTEN", "SIMILAR")) {
            dir.root.resolve("settings.preferences_pb").delete()
            olderBuildLeft { it[OobeStatusKey] = 4; it[QuickPicksLeanKey] = lean; it[ContextChipKey] = ContextChip.PARTY }
            val first = launch()
            assertEquals(lean, first[QuickPicksLeanKey])
            assertEquals(ContextChip.PARTY, first[ContextChipKey])
        }
    }

    @Test
    fun `it moves them once, so a chip picked afterwards stays`() {
        olderBuildLeft { it[OobeStatusKey] = 4; it[newSongsOnly] = true; it[QuickPicksLeanKey] = "NEW" }
        assertEquals(ContextChip.DISCOVER, launch { settings -> settings.edit { it[ContextChipKey] = ContextChip.AUTO } }[ContextChipKey])
        val second = launch()
        assertEquals(ContextChip.AUTO, second[ContextChipKey])
        assertNull(second[newSongsOnly]); assertNull(second[QuickPicksLeanKey])
    }

    @Test
    fun `a new install has neither, and nothing is moved`() {
        val first = launch()
        assertNull(first[ContextChipKey]); assertNull(first[newSongsOnly]); assertNull(first[QuickPicksLeanKey])
    }

    @Test
    fun `the app's settings file runs it`() {
        val source = File("src/main/java/com/dd3boh/outertune/utils/DataStore.kt").readText()
        val migrations = Regex("produceMigrations = \\{ listOf\\(([^)]*)\\)").find(source)!!.groupValues[1].split(',').map { it.trim() }
        assertEquals(listOf("NewInstallSongCache", "LyricsTimingOnTime", "PinsBefore013", "ClassicPinsBefore013", "CastPinAtUpdate", "NeverHeardToDiscover"), migrations)
        assertTrue(migrations.last() == NeverHeardToDiscover::class.simpleName)
    }
}
