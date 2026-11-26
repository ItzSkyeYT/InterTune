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
import com.dd3boh.lastfm.LastFmException
import com.dd3boh.outertune.BuildConfig
import com.dd3boh.outertune.constants.LastFmActAsEndedKey
import com.dd3boh.outertune.constants.LastFmRefusedAtKey
import com.dd3boh.outertune.constants.LastFmScrobbleKey
import com.dd3boh.outertune.constants.LastFmSessionKey
import com.dd3boh.outertune.constants.LastFmUsernameKey
import com.dd3boh.outertune.constants.SimilarFromLastFmKey
import com.dd3boh.outertune.constants.SimilarSource
import com.dd3boh.outertune.constants.SimilarSourceKey
import com.dd3boh.outertune.constants.Unreleased
import com.dd3boh.outertune.models.MediaMetadata
import com.dd3boh.outertune.ui.screens.SetupQuestion
import com.dd3boh.outertune.ui.screens.catchUpQuestions
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
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
 * Last.fm from 1 December 2026 (LastFmEnd). Last.fm keeps scrobbling free (its announcement of
 * 26 Nov 2026), so from that day only its similar songs stop: they need Last.fm Pro. Scrobbles,
 * now playing, logging in and the account go on. Moved through on a clock of the test's own,
 * either side of the date.
 *
 * And should Last.fm refuse the key itself one day: the first refusal of a scrobbling call closes
 * it all, written down so that it holds after a restart.
 */
class LastFmEndTest {

    @get:Rule
    val dir = TemporaryFolder()

    private val storeScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private val file: DataStore<Preferences> by lazy {
        PreferenceDataStoreFactory.create(scope = storeScope) { dir.root.resolve("settings.preferences_pb") }
    }

    private val dayBefore = LastFmEnd.PRO_FROM_MS - 1
    private val theDay = LastFmEnd.PRO_FROM_MS

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
    fun `similar songs stop at midnight UTC on the first of December, and nothing else does`() {
        assertEquals(Instant.parse("2026-12-01T00:00:00Z").toEpochMilli(), LastFmEnd.PRO_FROM_MS)
        assertFalse(LastFmEnd.similarGone(dayBefore, null))
        assertTrue(LastFmEnd.similarGone(theDay, null))
        assertTrue("and after it, for good", LastFmEnd.similarGone(Instant.parse("2031-06-01T12:00:00Z").toEpochMilli(), null))
        assertFalse("a long way before", LastFmEnd.similarGone(Instant.parse("2026-11-18T17:00:00Z").toEpochMilli(), mutablePreferencesOf()))
        // Last.fm itself is not closed by a date any more.
        LastFmEnd.clock = { theDay }
        assertFalse(LastFmEnd.closed(null))
        assertFalse(LastFmEnd.closed(mutablePreferencesOf()))
        assertFalse(LastFmEnd.closed())
    }

    @Test
    fun `a debug build can act as after the date for similar songs, and that closes nothing`() {
        assertEquals(BuildConfig.DEBUG, LastFmEnd.similarGone(dayBefore, mutablePreferencesOf(LastFmActAsEndedKey to true)))
        assertFalse(LastFmEnd.similarGone(dayBefore, mutablePreferencesOf(LastFmActAsEndedKey to false)))
        assertFalse(LastFmEnd.closed(mutablePreferencesOf(LastFmActAsEndedKey to true)))
        // Read through the settings the app attached, as Scrobbler reads it.
        LastFmEnd.clock = { dayBefore }
        LastFmEnd.attach(settings(LastFmActAsEndedKey to true))
        assertEquals(BuildConfig.DEBUG, LastFmEnd.similarGone())
        assertFalse(LastFmEnd.closed())
    }

    @Test
    fun `the key and its secret are still there after the date`() {
        assumeTheBuildHasAKey()
        LastFmEnd.clock = { theDay }
        assertTrue(BuiltInKeys.lastFmApiKey.isNotEmpty())
        assertTrue(BuiltInKeys.lastFmApiSecret.isNotEmpty())
    }

    @Test
    fun `from the date scrobbles, now playing and logins still go out, and similar songs do not`() = runBlocking {
        assumeTheBuildHasAKey()
        val requests = AtomicInteger()
        val scrobbler = scrobbler(settings(LastFmSessionKey to "session", LastFmUsernameKey to "someone"), requests)

        LastFmEnd.clock = { dayBefore }
        assertTrue(scrobbler.isAvailable)
        assertTrue(scrobbler.canFindSimilar)
        askEverything(scrobbler)
        assertEquals("the day before, each of the five went out", 5, requests.get())

        LastFmEnd.clock = { theDay }
        assertTrue(scrobbler.isAvailable)
        assertFalse(scrobbler.canFindSimilar)
        repeat(3) { askEverything(scrobbler) }
        assertEquals("from the first of December, four of the five each time", 5 + 3 * 4, requests.get())
        assertTrue(scrobbler.similar("Somebody", "A song").isFailure)
        assertEquals("and similar songs never went out", 17, requests.get())
    }

    @Test
    fun `the account is kept from the date, and only a refused key forgets it`() = runBlocking {
        val settings = settings(
            LastFmSessionKey to "session",
            LastFmUsernameKey to "someone",
            LastFmScrobbleKey to false,
            SimilarSourceKey to "BOTH",
        )
        LastFmEnd.attach(settings)

        LastFmEnd.clock = { theDay }
        Scrobbler.forgetIfClosed(settings)
        assertEquals("kept after the date", "session", settings[LastFmSessionKey])
        assertEquals("someone", settings[LastFmUsernameKey])

        settings.edit { it[LastFmRefusedAtKey] = theDay }
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
    fun `only Last-fm refusing the key itself closes it`() {
        // 10 "Invalid API key" and 26 "API Key Suspended", from last.fm/api/errorcodes.
        assertTrue(LastFmEnd.refusesKey(LastFmException(10, "Invalid API key - You must be granted a valid key by last.fm")))
        assertTrue(LastFmEnd.refusesKey(LastFmException(26, "Suspended API key - Access for your account has been suspended, please contact Last.fm")))
        // 4 comes back for a login token not approved yet, 9 is the account's session, 6 a song
        // Last.fm does not know, and the rest are the service or the network having a bad minute.
        for (code in listOf(2, 3, 4, 5, 6, 7, 8, 9, 11, 13, 14, 15, 16, 29)) {
            assertFalse("error $code", LastFmEnd.refusesKey(LastFmException(code, "something")))
        }
        assertFalse(LastFmEnd.refusesKey(java.io.IOException("timeout")))
        assertFalse(LastFmEnd.refusesKey(IllegalStateException("No Last.fm API key in this build")))
    }

    /** A Last.fm that answers every request with this error, as it does: a JSON body, here with a 403. */
    private fun refusing(settings: SettingsSnapshot, code: Int, requests: AtomicInteger) = Scrobbler(settings) { key, secret ->
        LastFm(key, secret, LastFm.clientOver(MockEngine {
            requests.incrementAndGet()
            respond(
                content = """{"error":$code,"message":"Refused"}""",
                status = HttpStatusCode.Forbidden,
                headers = headersOf(HttpHeaders.ContentType, "application/json"),
            )
        }))
    }

    @Test
    fun `a refused key closes Last-fm at once, and nothing more is sent`() = runBlocking {
        assumeTheBuildHasAKey()
        val settings = settings(LastFmSessionKey to "session", LastFmUsernameKey to "someone", SimilarSourceKey to "BOTH")
        LastFmEnd.attach(settings)
        LastFmEnd.clock = { dayBefore }
        val requests = AtomicInteger()
        val scrobbler = refusing(settings, code = 10, requests)

        scrobbler.nowPlaying(song)
        assertEquals(1, requests.get())
        assertEquals("written down, with when", dayBefore, settings[LastFmRefusedAtKey])
        assertTrue(LastFmEnd.closed())
        assertFalse(scrobbler.isAvailable)
        assertEquals("", BuiltInKeys.lastFmApiKey)

        repeat(3) { askEverything(scrobbler) }
        assertEquals("no retry, nothing queued, nothing more at all", 1, requests.get())

        // A restart reads it back, and forgets the account.
        Scrobbler.forgetIfClosed(settings)
        assertNull(settings[LastFmSessionKey])
        assertEquals("BOTH", settings[SimilarSourceKey])
    }

    @Test
    fun `a suspended key met by a login closes it the same way`() = runBlocking {
        assumeTheBuildHasAKey()
        LastFmEnd.clock = { dayBefore }
        val settings = settings()
        LastFmEnd.attach(settings)
        val requests = AtomicInteger()
        val scrobbler = refusing(settings, code = 26, requests)
        scrobbler.beginLogin()
        assertNotNull(settings[LastFmRefusedAtKey])
        repeat(2) { askEverything(scrobbler) }
        assertEquals(1, requests.get())
    }

    @Test
    fun `similar songs refused with the key's codes only fail, and the scrobbles go on`() = runBlocking {
        // From 1 December Last.fm may answer a call that needs Pro with 10 or 26 as well. Only the
        // calls it keeps free say anything about the key.
        assumeTheBuildHasAKey()
        LastFmEnd.clock = { dayBefore }
        val settings = settings(LastFmSessionKey to "session", LastFmUsernameKey to "someone")
        LastFmEnd.attach(settings)
        val requests = AtomicInteger()
        for (code in listOf(10, 26)) {
            assertTrue(refusing(settings, code = code, requests).similar("Somebody", "A song").isFailure)
        }
        assertNull(settings[LastFmRefusedAtKey])
        assertFalse(LastFmEnd.closed())
        assertEquals("session", settings[LastFmSessionKey])
        val scrobbles = AtomicInteger()
        scrobbler(settings, scrobbles).scrobble(song, playedMs = 150_000, startedAtSeconds = 1_795_000_000)
        assertEquals(1, scrobbles.get())
    }

    @Test
    fun `any other error leaves Last-fm open`() = runBlocking {
        assumeTheBuildHasAKey()
        val settings = settings(LastFmSessionKey to "session", LastFmUsernameKey to "someone")
        LastFmEnd.attach(settings)
        LastFmEnd.clock = { dayBefore }
        val requests = AtomicInteger()
        val scrobbler = refusing(settings, code = 29, requests)
        askEverything(scrobbler)
        askEverything(scrobbler)
        assertEquals("a rate limit is not the end: each went out", 10, requests.get())
        assertNull(settings[LastFmRefusedAtKey])
        assertFalse(LastFmEnd.closed())
        // An expired session disconnects the account, as before, and closes nothing.
        refusing(settings, code = 9, requests).nowPlaying(song)
        assertNull(settings[LastFmSessionKey])
        assertNull(settings[LastFmRefusedAtKey])
        assertFalse(LastFmEnd.closed())
    }

    @Test
    fun `a login that runs into the refusal says why in the card's words`() {
        val screen = File("src/main/java/com/dd3boh/outertune/ui/screens/LastFmLoginScreen.kt").readText()
        assertTrue("closed = LastFmEnd.closed()" in screen)
        assertTrue("if (closed) stringResource(R.string.lastfm_ended_description)" in screen)
    }

    @Test
    fun `similar songs come from YouTube alone from the date, and the choice is kept`() {
        assumeTheBuildHasAKey()
        val stored = listOf(
            mutablePreferencesOf(SimilarSourceKey to SimilarSource.BOTH.name),
            mutablePreferencesOf(SimilarSourceKey to SimilarSource.LASTFM.name),
            mutablePreferencesOf(SimilarFromLastFmKey to true),
            mutablePreferencesOf(SimilarSourceKey to SimilarSource.YOUTUBE.name),
        )
        LastFmEnd.clock = { dayBefore }
        assumeTrue(Unreleased.ENGINE)
        assertEquals(
            listOf(SimilarSource.BOTH, SimilarSource.LASTFM, SimilarSource.LASTFM, SimilarSource.YOUTUBE),
            stored.map(::similarSourceOf),
        )
        LastFmEnd.clock = { theDay }
        assertEquals(List(4) { SimilarSource.YOUTUBE }, stored.map(::similarSourceOf))
        // Only read: the choice is there again should Last.fm ever be.
        assertEquals(SimilarSource.BOTH.name, stored[0][SimilarSourceKey])
    }

    @Test
    fun `setup and the questions after an update stop asking about Last-fm's similar songs from the date`() {
        assumeTheBuildHasAKey()
        LastFmEnd.clock = { dayBefore }
        assertEquals(Unreleased.ENGINE, lastFmQuestionAskable())
        LastFmEnd.clock = { theDay }
        assertFalse(lastFmQuestionAskable())
        assertFalse(SetupQuestion.LASTFM in catchUpQuestions(mutablePreferencesOf(), lastFmQuestionAskable()).asked)
    }

    @Test
    fun `Recommendations hides the choice, and a catch-up stops when similar songs go on its way`() {
        // The choice of where similar songs come from is drawn only while Last.fm can be asked
        // for them, so not from the date: no Both or Last.fm only left to pick that would do nothing.
        val recommendations = File("src/main/java/com/dd3boh/outertune/ui/screens/settings/RecommendationsSettings.kt").readText()
        val gate = recommendations.indexOf("if (lastFmSimilarReachable()) {")
        assertTrue(gate in 0 until recommendations.indexOf("settingRow(R.string.similar_source)"))
        // The catch-up looks again before each song, and stops as one that gave up: nothing is
        // recorded, and Home is not told of neighbours that never came.
        val similar = File("src/main/java/com/dd3boh/outertune/utils/LastFmSimilar.kt").readText()
        assertTrue("val isAvailable get() = scrobbler.canFindSimilar" in similar)
        val loop = similar.substringAfter("suspend fun catchUp(): Boolean {").substringBefore("when (attempt(")
        assertTrue(loop, Regex("""for \(song in [^\n]+\n(\s*//[^\n]*\n)*\s*if \(!isAvailable\) \{\s*gaveUp = true\s*break""").containsMatchIn(loop))
        assertTrue(similar.substringAfter("suspend fun catchUp(): Boolean {").contains("if (!gaveUp) _caughtUpAt.value"))
    }

    @Test
    fun `the words are in English and French, open to translators, two short sentences at most`() {
        fun text(folder: String, name: String): Pair<String, String> {
            val xml = File("src/main/res/$folder/strings-ot.xml").readText()
            val found = Regex("""<string name="$name"([^>]*)>([^<]*)</string>""").find(xml)
            assertNotNull("$name is not in $folder", found)
            return found!!.groupValues[1] to found.groupValues[2]
        }
        for (folder in listOf("values", "values-fr")) for (name in listOf("lastfm_ended", "lastfm_ended_description")) {
            val (attributes, words) = text(folder, name)
            assertFalse("$folder/$name is still held (open since his \"words good\" of 20 Nov 2026)", "translatable=\"false\"" in attributes)
            assertFalse("$folder/$name has a long dash", '—' in words || '–' in words)
            assertTrue("$folder/$name: $words", Regex("""[.!?](?=\s|$)""").findAll(words).count() <= 2)
        }
        assertNotEquals(text("values", "lastfm_ended_description").second, text("values-fr", "lastfm_ended_description").second)
        assertTrue("it says what happened: the key was turned away", "key" in text("values", "lastfm_ended_description").second)
    }

    @Test
    fun `no text says Last-fm stops on 1 December any more`() {
        for ((folder, month) in listOf("values" to "December", "values-fr" to "décembre")) {
            val xml = File("src/main/res/$folder/strings-ot.xml").readText()
            val stale = Regex("""<string name="([^"]+)"[^>]*>([^<]*)</string>""").findAll(xml)
                .filter { "Last.fm" in it.groupValues[2] && month in it.groupValues[2] }
                .map { it.groupValues[1] }.toList()
            assertEquals("$folder: $stale", emptyList<String>(), stale)
        }
    }
}
