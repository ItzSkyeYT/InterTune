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
import androidx.datastore.preferences.core.edit
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
 * A new install starts with a song cache of 256 MB, and an install that has settings keeps its own.
 *
 * Each launch here is the settings file opened the way the app opens it, with NewInstallSongCache,
 * and closed again, as the app's process ends. An older build's file is written without it.
 */
class NewInstallSongCacheTest {

    @get:Rule
    val dir = TemporaryFolder()

    private val file: File by lazy { dir.root.resolve("settings.preferences_pb") }

    private fun <T> open(newBuild: Boolean, block: suspend (DataStore<Preferences>) -> T): T {
        val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        return try {
            val migrations = if (newBuild) listOf(NewInstallSongCache) else emptyList()
            val settings = PreferenceDataStoreFactory.create(migrations = migrations, scope = scope) { file }
            runBlocking { block(settings) }
        } finally {
            // Closed before the next launch opens the same file, as a process that ended.
            runBlocking { scope.coroutineContext[Job]!!.cancelAndJoin() }
        }
    }

    /** One launch of this build: the first read of the settings, then whatever [then] does. */
    private fun launch(then: suspend (DataStore<Preferences>) -> Unit = {}): Preferences =
        open(newBuild = true) { settings -> settings.data.first().also { then(settings) } }

    private fun olderBuildLeft(settings: (MutablePreferences) -> Unit) {
        open(newBuild = false) { older -> older.edit { settings(it) } }
    }

    @Test
    fun `a new install starts with a song cache of 256 MB`() {
        assertEquals(256, launch()[MaxSongCacheSizeKey])
        // Written in the file, not only shown: the next launch reads it from there.
        assertEquals(256, launch()[MaxSongCacheSizeKey])
    }

    @Test
    fun `a size picked after that is kept at the next launch, Off included`() {
        for (picked in listOf(0, 1024, -1)) {
            file.delete()
            launch { settings -> settings.edit { it[MaxSongCacheSizeKey] = picked } }
            assertEquals("picked $picked", picked, launch()[MaxSongCacheSizeKey])
        }
    }

    @Test
    fun `an install that stored a size keeps it`() {
        for (stored in listOf(0, 128, 256, 1024, -1)) {
            file.delete()
            olderBuildLeft {
                it[OobeStatusKey] = OOBE_VERSION
                it[MaxSongCacheSizeKey] = stored
            }
            assertEquals("stored $stored", stored, launch()[MaxSongCacheSizeKey])
        }
    }

    @Test
    fun `an install that never picked a size keeps its cache off`() {
        olderBuildLeft { it[OobeStatusKey] = OOBE_VERSION }
        // Nothing written, so every reader still takes it as 0, off, as before the update.
        assertNull(launch()[MaxSongCacheSizeKey])
        assertNull(launch()[MaxSongCacheSizeKey])
    }

    @Test
    fun `the app opens its settings file with it`() {
        val source = File("src/main/java/com/dd3boh/outertune/utils/DataStore.kt").readText()
        assertTrue(
            // First: it asks for settings that hold nothing at all, and the next migration writes its mark.
            Regex("""preferencesDataStore\(\s*name = "settings",\s*produceMigrations = \{ listOf\(NewInstallSongCache[,)]""")
                .containsMatchIn(source)
        )
    }

    /**
     * A size never stored is off wherever it is read: Settings, setup, the cached songs tab, the
     * evictor and the write sink. Read as anything else in one of them, an older install that
     * never picked a size would be shown a cache it does not have, or build one it never chose.
     *
     * One exception, and every reader that knows it asks the same rule: Battery use on Saver keeps
     * 512 MB where no size was ever picked (BatteryUse.songCacheMb, BatteryUseTest), and off in
     * every other mode. Such a reader hands the size as stored, none included, to that rule:
     * straight in, or as the stored value alone that the same file then gives the rule.
     */
    @Test
    fun `every reader takes a size never stored as off`() {
        val off = Regex("""^MaxSongCacheSizeKey(] \?: 0\b|, (defaultValue = )?0\b)""")
        val reads = File("src/main/java").walk().filter { it.extension == "kt" }.flatMap { source ->
            val text = source.readText()
            Regex("""\bMaxSongCacheSizeKey\b""").findAll(text).mapNotNull { found ->
                val line = text.substring(text.lastIndexOf('\n', found.range.first) + 1).substringBefore('\n')
                val after = text.substring(found.range.first).take(80).replace(Regex("""\s+"""), " ")
                val toRule = line.substringBefore("MaxSongCacheSizeKey").contains("BatteryUse.songCacheMb(") ||
                    Regex("""val (\w+) by rememberNullablePreference\(MaxSongCacheSizeKey\)""").find(line)
                        ?.let { "BatteryUse.songCacheMb(${it.groupValues[1]}," in text } == true
                when {
                    line.trimStart().startsWith("import ") -> null
                    line.trimStart().startsWith("val MaxSongCacheSizeKey =") -> null
                    // The one write: a new install's size, as its settings are first read.
                    after.startsWith("MaxSongCacheSizeKey] = NEW_INSTALL_SONG_CACHE_MB") -> null
                    else -> "${source.name}: $after" to (toRule || off.containsMatchIn(after))
                }
            }
        }.toList()
        assertTrue("found no reads", reads.size >= 5)
        reads.forEach { (read, ok) -> assertTrue(read, ok) }
    }
}
