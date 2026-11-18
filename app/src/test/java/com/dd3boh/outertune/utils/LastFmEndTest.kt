/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.utils

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.mutablePreferencesOf
import com.dd3boh.lastfm.LastFm
import com.dd3boh.outertune.BuildConfig
import com.dd3boh.outertune.constants.LastFmActAsEndedKey
import com.dd3boh.outertune.constants.LastFmScrobbleKey
import com.dd3boh.outertune.constants.LastFmSessionKey
import com.dd3boh.outertune.constants.LastFmUsernameKey
import com.dd3boh.outertune.constants.SimilarSourceKey
import com.dd3boh.outertune.models.MediaMetadata
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.lang.reflect.Modifier
import java.time.Instant
import java.util.concurrent.atomic.AtomicInteger

/**
 * Last.fm from 1 December 2026, when it starts refusing InterTune's key (LastFmEnd): from that
 * moment the key reads as empty, every way to Last.fm takes the path a build without a key takes,
 * and nothing is sent. Moved through on a clock of the test's own, either side of the date.
 */
class LastFmEndTest {

    @get:Rule
    val dir = TemporaryFolder()

    private val storeScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private val file: DataStore<Preferences> by lazy {
        PreferenceDataStoreFactory.create(scope = storeScope) { dir.root.resolve("settings.preferences_pb") }
    }

    private val dayBefore = LastFmEnd.END_MS - 1
    private val theDay = LastFmEnd.END_MS

    @After
    fun putBack() {
        genuine(false)
        LastFmEnd.attach(null as SettingsSnapshot?)
        LastFmEnd.clock = { System.currentTimeMillis() }
        storeScope.cancel()
    }

    /** The settings of an install, as the app reads them. */
    private fun settings(vararg values: Preferences.Pair<*>): SettingsSnapshot {
        runBlocking { file.edit { it.putAll(*values) } }
        return SettingsSnapshot(file)
    }

    /** BuiltInKeys only hands keys to the app signed with the release key, or to InterTune's own test builds. */
    private fun genuine(on: Boolean) {
        val field = BuiltInKeys::class.java.getDeclaredField("genuine").apply { isAccessible = true }
        field.setBoolean(if (Modifier.isStatic(field.modifiers)) null else BuiltInKeys, on)
    }

    /** The key is in the public source (services.properties); a copy without it has nothing to stop. */
    private fun assumeTheBuildHasAKey() {
        assumeTrue(BuildConfig.LASTFM_API_KEY.isNotEmpty() && BuildConfig.LASTFM_API_SECRET.isNotEmpty())
        genuine(true)
    }

    private val song = MediaMetadata(
        id = "song",
        title = "A song",
        artists = listOf(MediaMetadata.Artist(id = null, name = "Somebody")),
        duration = 200,
        genre = null,
    )

    /** A Scrobbler whose Last.fm is a fake network that counts what reaches it. */
    private fun scrobbler(settings: SettingsSnapshot, requests: AtomicInteger) = Scrobbler(settings) { key, secret ->
        LastFm(key, secret, HttpClient(MockEngine { requests.incrementAndGet(); respond("{}") }))
    }

    /** Each thing the app asks Last.fm: now playing, a scrobble, similar songs, and both steps of a login. */
    private suspend fun askEverything(scrobbler: Scrobbler) {
        scrobbler.nowPlaying(song)
        scrobbler.scrobble(song, playedMs = 150_000, startedAtSeconds = 1_795_000_000)
        scrobbler.similar("Somebody", "A song")
        scrobbler.beginLogin()
        scrobbler.completeLogin("token")
    }

    @Test
    fun `closed from midnight UTC on the first of December, open a millisecond before`() {
        assertEquals(Instant.parse("2026-12-01T00:00:00Z").toEpochMilli(), LastFmEnd.END_MS)
        assertFalse(LastFmEnd.closed(dayBefore, null))
        assertTrue(LastFmEnd.closed(theDay, null))
        assertTrue("and after it, for good", LastFmEnd.closed(Instant.parse("2031-06-01T12:00:00Z").toEpochMilli(), null))
        assertFalse("a long way before", LastFmEnd.closed(Instant.parse("2026-11-18T17:00:00Z").toEpochMilli(), mutablePreferencesOf()))
    }

    @Test
    fun `a debug build can act as after the date, and nothing else can`() {
        assertEquals(BuildConfig.DEBUG, LastFmEnd.closed(dayBefore, mutablePreferencesOf(LastFmActAsEndedKey to true)))
        assertFalse(LastFmEnd.closed(dayBefore, mutablePreferencesOf(LastFmActAsEndedKey to false)))
        // Read through the settings the app attached, as BuiltInKeys reads it.
        LastFmEnd.clock = { dayBefore }
        LastFmEnd.attach(settings(LastFmActAsEndedKey to true))
        assertEquals(BuildConfig.DEBUG, LastFmEnd.closed())
    }

    @Test
    fun `the key and its secret read as empty from the date`() {
        assumeTheBuildHasAKey()
        LastFmEnd.clock = { dayBefore }
        assertTrue(BuiltInKeys.lastFmApiKey.isNotEmpty())
        assertTrue(BuiltInKeys.lastFmApiSecret.isNotEmpty())
        LastFmEnd.clock = { theDay }
        assertEquals("", BuiltInKeys.lastFmApiKey)
        assertEquals("", BuiltInKeys.lastFmApiSecret)
    }

    @Test
    fun `nothing reaches Last-fm from the date, where the day before everything did`() = runBlocking {
        assumeTheBuildHasAKey()
        val requests = AtomicInteger()
        val scrobbler = scrobbler(settings(LastFmSessionKey to "session", LastFmUsernameKey to "someone"), requests)

        LastFmEnd.clock = { dayBefore }
        assertTrue(scrobbler.isAvailable)
        askEverything(scrobbler)
        assertEquals("the day before, each of the five went out", 5, requests.get())

        LastFmEnd.clock = { theDay }
        assertFalse(scrobbler.isAvailable)
        assertFalse(scrobbler.canFindSimilar)
        repeat(3) { askEverything(scrobbler) }
        assertEquals("from the first of December, not one more", 5, requests.get())
        assertTrue(scrobbler.similar("Somebody", "A song").isFailure)
        assertTrue(scrobbler.beginLogin().isFailure)
    }

    @Test
    fun `the second step of a login checks the key like every other call`() = runBlocking {
        assumeTheBuildHasAKey()
        val requests = AtomicInteger()
        val scrobbler = scrobbler(settings(), requests)
        LastFmEnd.clock = { theDay }
        assertTrue(scrobbler.completeLogin("token").isFailure)
        assertEquals("the login screen, left open over the date, asked Last.fm all the same", 0, requests.get())
    }

    @Test
    fun `the account is forgotten at the first start from the date, and the choices are kept`() = runBlocking {
        val settings = settings(
            LastFmSessionKey to "session",
            LastFmUsernameKey to "someone",
            LastFmScrobbleKey to false,
            SimilarSourceKey to "BOTH",
        )
        LastFmEnd.attach(settings)

        LastFmEnd.clock = { dayBefore }
        Scrobbler.forgetIfClosed(settings)
        assertEquals("kept before the date", "session", settings[LastFmSessionKey])

        LastFmEnd.clock = { theDay }
        Scrobbler.forgetIfClosed(settings)
        assertNull(settings[LastFmSessionKey])
        assertNull(settings[LastFmUsernameKey])
        assertEquals("the scrobble switch stays as it was", false, settings[LastFmScrobbleKey])
        assertEquals("so does where similar songs come from", "BOTH", settings[SimilarSourceKey])
        // And the next start finds nothing to do.
        Scrobbler.forgetIfClosed(settings)
        assertNull(settings[LastFmSessionKey])
    }

    @Test
    fun `the card says why before anything else, with nothing to tap and no tour stop`() {
        val frag = File("src/main/java/com/dd3boh/outertune/ui/screens/settings/fragments/LastFmFrag.kt").readText()
        val closed = frag.indexOf("if (LastFmEnd.closed(")
        val noKey = frag.indexOf("if (!scrobbler.isAvailable)")
        assertTrue("the closed row comes before the one for a build without a key", closed in 0 until noKey)
        val row = frag.substring(closed, frag.indexOf("return", closed))
        assertTrue(row, "R.string.lastfm_ended)" in row && "R.string.lastfm_ended_description)" in row)
        assertTrue(row, "onClick = null" in row)
        assertFalse(row, "tourTarget" in row)
        // Started with the app, before anything can read a key.
        val keys = File("src/main/java/com/dd3boh/outertune/utils/BuiltInKeys.kt").readText()
        assertTrue(keys.substringAfter("fun verify(").substringBefore("\n    }").contains("LastFmEnd.attach(context)"))
        val app = File("src/main/java/com/dd3boh/outertune/App.kt").readText()
        assertTrue("Scrobbler.forgetIfClosed(dataStore)" in app)
    }

    @Test
    fun `the words are in English and French, held from translators, two short sentences at most`() {
        fun text(folder: String, name: String): Pair<String, String> {
            val xml = File("src/main/res/$folder/strings-ot.xml").readText()
            val found = Regex("""<string name="$name"([^>]*)>([^<]*)</string>""").find(xml)
            assertNotNull("$name is not in $folder", found)
            return found!!.groupValues[1] to found.groupValues[2]
        }
        for (folder in listOf("values", "values-fr")) for (name in listOf("lastfm_ended", "lastfm_ended_description")) {
            val (attributes, words) = text(folder, name)
            assertTrue("$folder/$name is not held for his word", "translatable=\"false\"" in attributes)
            assertFalse("$folder/$name has a long dash", '—' in words || '–' in words)
            assertTrue("$folder/$name: $words", Regex("""[.!?](?=\s|$)""").findAll(words).count() <= 2)
        }
        assertNotEquals(text("values", "lastfm_ended_description").second, text("values-fr", "lastfm_ended_description").second)
        assertTrue("it names what Last.fm now asks for", "Last.fm Pro" in text("values", "lastfm_ended_description").second)
    }
}
