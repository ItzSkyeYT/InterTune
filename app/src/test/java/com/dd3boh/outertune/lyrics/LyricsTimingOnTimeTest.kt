/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.lyrics

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import com.dd3boh.outertune.constants.LyricsTimingKey
import com.dd3boh.outertune.constants.LyricsTimingOnTimeKey
import com.dd3boh.outertune.constants.MaxSongCacheSizeKey
import com.dd3boh.outertune.constants.NEW_INSTALL_SONG_CACHE_MB
import com.dd3boh.outertune.constants.NewInstallSongCache
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
 * The lyrics timing saved before the lyrics clock was put right is moved 100 ms later, once.
 *
 * Each launch is the settings file opened the way the app opens it (NewInstallSongCache, then
 * LyricsTimingOnTime) and closed again, as the app's process ends; an older build's file is
 * written without them, as NewInstallSongCacheTest does.
 */
class LyricsTimingOnTimeTest {

    @get:Rule
    val dir = TemporaryFolder()

    private val file: File by lazy { dir.root.resolve("settings.preferences_pb") }

    private fun <T> open(newBuild: Boolean, block: suspend (DataStore<Preferences>) -> T): T {
        val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        return try {
            val migrations = if (newBuild) listOf(NewInstallSongCache, LyricsTimingOnTime) else emptyList()
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
    fun `each move is 100 ms later, and one back at 0 is forgotten`() {
        assertEquals("b=-200,c=300", LyricsTiming.caughtUp("a=-100,b=-300,c=200"))
        assertEquals("", LyricsTiming.caughtUp("a=-100"))
        assertEquals("", LyricsTiming.caughtUp(""))
        // Kept to the slider's range, and what cannot be read is left out, as withShift does.
        assertEquals("a=5000,b=-4900", LyricsTiming.caughtUp("a=5000,b=-5000,c=x,broken"))
    }

    @Test
    fun `moves saved before the clock was right are moved once, at the first launch`() {
        olderBuildLeft { it[LyricsTimingKey] = "song1=-100,song2=-300,song3=200" }
        val first = launch()
        assertEquals("song2=-200,song3=300", first[LyricsTimingKey])
        assertEquals(true, first[LyricsTimingOnTimeKey])
        // Not again at the next launch, nor a move made after it.
        assertEquals("song2=-200,song3=300", launch()[LyricsTimingKey])
        launch { settings -> settings.edit { it[LyricsTimingKey] = LyricsTiming.withShift(it[LyricsTimingKey].orEmpty(), "song4", -100) } }
        assertEquals("song2=-200,song3=300,song4=-100", launch()[LyricsTimingKey])
    }

    @Test
    fun `an install with nothing moved is only marked, and a new install keeps its song cache`() {
        olderBuildLeft { it[MaxSongCacheSizeKey] = 0 }
        val updated = launch()
        assertNull(updated[LyricsTimingKey])
        assertEquals(true, updated[LyricsTimingOnTimeKey])
        assertEquals(0, updated[MaxSongCacheSizeKey])

        file.delete()
        val fresh = launch()
        assertEquals(NEW_INSTALL_SONG_CACHE_MB, fresh[MaxSongCacheSizeKey])
        assertEquals(true, fresh[LyricsTimingOnTimeKey])
    }

    @Test
    fun `a backup from before the clock was right is moved as it is read`() {
        launch { settings -> settings.edit { it[LyricsTimingKey] = "kept=-400" } }
        assertEquals("kept=-400", launch()[LyricsTimingKey])
        // Restored: the settings of a backup made before, its moves and no mark.
        file.delete()
        olderBuildLeft { it[LyricsTimingKey] = "old=-100,older=-500" }
        assertEquals("older=-400", launch()[LyricsTimingKey])
    }

    @Test
    fun `the app opens its settings with it, after the new install's song cache`() {
        val source = File("src/main/java/com/dd3boh/outertune/utils/DataStore.kt").readText()
        assertTrue(source.contains("produceMigrations = { listOf(NewInstallSongCache, LyricsTimingOnTime) },"))
    }
}
