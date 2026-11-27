/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.utils

import androidx.datastore.preferences.core.mutablePreferencesOf
import com.dd3boh.outertune.BuildConfig
import com.dd3boh.outertune.constants.SimilarFromLastFmKey
import com.dd3boh.outertune.constants.SimilarSource
import com.dd3boh.outertune.constants.SimilarSourceKey
import com.dd3boh.outertune.constants.Unreleased
import com.dd3boh.outertune.engine.SimilarMatch
import com.dd3boh.outertune.engine.SongRow
import com.dd3boh.outertune.engine.SourceMix
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.io.IOException

/**
 * ListenBrainz's similar songs in the place Last.fm's had (Unreleased.LB_SIMILAR, ListenBrainzSimilar): how a song
 * is asked about, what a song it does not know leaves (nothing, so YouTube's list stands: ListenBrainzSqlTest has the
 * per-seed fallback in the engine's own query), the switch with the flag and without it, where the app asks it, and
 * its words. The answers are real, from the 18 Nov 2026 trial (experiments/recs-trial-1118/cache), cut to their
 * first rows.
 */
class ListenBrainzSimilarTest {

    @After
    fun putBack() {
        LastFmEnd.clock = { System.currentTimeMillis() }
    }

    // ---- Asking ListenBrainz, on its real answers

    private val katy = "2d6f51c6-afc5-457e-9c58-689679c08d30"

    /** acr-lookup's answers, by the artist and title asked. The uploaded title is not a recording's name; the plain one is. */
    private val lookups = mapOf(
        ("Katy Perry" to "The One That Got Away (Official Music Video)") to "[]",
        ("Katy Perry" to "The One That Got Away") to
            """[{"index": 0, "artist_credit_arg": "Katy Perry", "recording_arg": "The One That Got Away", "artist_credit_name": "Katy Perry", "release_name": "Teenage Dream", "recording_name": "The One That Got Away", "artist_credit_id": 315753, "artist_mbids": ["122d63fc-8671-43e4-9752-34e846d62a9c"], "release_mbid": "2a666025-eb7c-3cc2-bd76-4fbd80487395", "recording_mbid": "$katy"}]""",
        ("LiSA" to "紅蓮華 - Gurenge") to "[]",
    )

    /** similar-recordings' answer for The One That Got Away, its first six of a hundred rows. */
    private val similar = mapOf(
        katy to """[{"recording_mbid": "cac5b2b9-5fda-4856-b71a-31c94bb5da9d", "recording_name": "Call Me Maybe", "artist_credit_name": "Carly Rae Jepsen", "artist_credit_mbids": null, "release_name": "Kiss", "release_mbid": "11f00518-df75-4c2b-8df5-8f136dce0e72", "caa_id": 44634030212, "caa_release_mbid": "742fdcb4-4203-4343-9ed9-051f01f255e6", "score": 263, "reference_mbid": "$katy"}, {"recording_mbid": "c3fe7791-0a91-4f0a-a89b-b056f38d3cde", "recording_name": "Blank Space", "artist_credit_name": "Taylor Swift", "artist_credit_mbids": null, "release_name": "1989", "release_mbid": "e3f0984a-4036-4cd7-8c8c-0217b87c577a", "caa_id": 9282848916, "caa_release_mbid": "d5cb50d8-8897-4029-b9b3-32806df7e06d", "score": 248, "reference_mbid": "$katy"}, {"recording_mbid": "ce4eb46b-1a27-4950-a02f-9499cab56c6b", "recording_name": "Only Girl (In the World)", "artist_credit_name": "Rihanna", "artist_credit_mbids": null, "release_name": "Loud", "release_mbid": "79d4fea0-0fbb-3722-a2e6-9830268b4342", "caa_id": 14542240865, "caa_release_mbid": "7d3ebe0a-94a1-45a2-89f3-dc008837b338", "score": 238, "reference_mbid": "$katy"}, {"recording_mbid": "11294597-b4ac-4717-8e95-f99d665c1ece", "recording_name": "We Found Love", "artist_credit_name": "Rihanna feat. Calvin Harris", "artist_credit_mbids": null, "release_name": "Talk That Talk", "release_mbid": "a0f48c3b-125b-4958-921b-f2e9f9f2c490", "caa_id": 32362057914, "caa_release_mbid": "6c4c5763-1a3c-4b2f-88c0-18f60047b71e", "score": 235, "reference_mbid": "$katy"}, {"recording_mbid": "d1e0a99e-1894-457b-ba6a-985eeef4d0c4", "recording_name": "Set Fire to the Rain", "artist_credit_name": "Adele", "artist_credit_mbids": null, "release_name": "21", "release_mbid": "768bc7f7-6b91-4b57-8a7b-1508636719e6", "caa_id": 3222560647, "caa_release_mbid": "768bc7f7-6b91-4b57-8a7b-1508636719e6", "score": 232, "reference_mbid": "$katy"}, {"recording_mbid": "7a1040dc-bac0-479c-9921-f43797ed0b81", "recording_name": "Paparazzi", "artist_credit_name": "Lady Gaga", "artist_credit_mbids": null, "release_name": "The Fame", "release_mbid": "785d7c67-a920-4cee-a871-8cd9896eb8aa", "caa_id": 33619706574, "caa_release_mbid": "8c86403a-6bfb-40f8-a1db-1cde6cbf68a5", "score": 222, "reference_mbid": "$katy"}]""",
    )

    private val asked = mutableListOf<Pair<String, String>>()
    private val askedNear = mutableListOf<String>()

    /** ListenBrainz as the app reads it (LbLabs), answering from the recorded answers, and writing down what it was asked. */
    private suspend fun ask(title: String, artist: String?): List<SimilarMatch.Neighbour> = LbSimilar.ask(
        title, artist,
        recordingOf = { a, t -> asked += a to t; LbLabs.mbidOf(lookups[a to t] ?: "[]") },
        similarTo = { mbid -> askedNear += mbid; LbLabs.neighboursOf(similar.getValue(mbid)) ?: throw IOException("labs changed") },
    )

    /** Songs on the phone, as YouTube uploads them: what the neighbours can come to. */
    private val phone = SimilarMatch.Index(
        listOf(
            SongRow("seed", "The One That Got Away (Official Music Video)", "a1", "Katy Perry"),
            SongRow("carly", "Call Me Maybe", "a2", "Carly Rae Jepsen"),
            SongRow("blank", "Blank Space (Official Video)", "a3", "Taylor Swift"),
            SongRow("love", "We Found Love (feat. Calvin Harris)", "a4", "Rihanna"),
            SongRow("rain", "Set Fire to the Rain", "a5", "Adele"),
            SongRow("rain-live", "Set Fire to the Rain (Live at The Royal Albert Hall)", "a5", "Adele"),
            SongRow("other", "Toxic", "a6", "Britney Spears"),
        ),
    )

    @Test
    fun `a song it knows becomes the songs on the phone people play near it, most similar first`() = runBlocking {
        val neighbours = ask("The One That Got Away (Official Music Video)", "Katy Perry")
        assertEquals(6, neighbours.size)
        assertEquals(SimilarMatch.Neighbour("Call Me Maybe", "Carly Rae Jepsen"), neighbours.first())
        // Only Girl and Paparazzi are not on the phone, and nothing plays what is not.
        assertEquals(
            listOf("carly", "blank", "love", "rain", "rain-live"),
            SimilarMatch.match("seed", "The One That Got Away (Official Music Video)", "Katy Perry", neighbours, phone),
        )
    }

    @Test
    fun `it is looked for by the title as uploaded, then by its plain title, and its neighbours asked for once`() = runBlocking {
        ask("The One That Got Away (Official Music Video)", "Katy Perry")
        assertEquals(
            listOf("Katy Perry" to "The One That Got Away (Official Music Video)", "Katy Perry" to "The One That Got Away"),
            asked,
        )
        assertEquals(listOf(katy), askedNear)
    }

    @Test
    fun `a song it does not know has no neighbours, and nothing more is asked`() = runBlocking {
        assertEquals(emptyList<SimilarMatch.Neighbour>(), ask("紅蓮華 - Gurenge", "LiSA"))
        assertTrue(asked.isNotEmpty())
        assertTrue("no recording, so no question about its neighbours", askedNear.isEmpty())
        // And so nothing to store: ListenBrainzSimilar writes no row, and the engine reads YouTube's list for the song.
        assertEquals(emptyList<String>(), SimilarMatch.match("gurenge", "紅蓮華 - Gurenge", "LiSA", emptyList(), phone))
    }

    @Test
    fun `a request that fails, or an answer labs changed, is not an answer about the song`() {
        assertThrows(IOException::class.java) {
            runBlocking { LbSimilar.ask("Song", "Artist", { _, _ -> throw IOException("ListenBrainz answered 429") }, { emptyList() }) }
        }
        assertThrows(IOException::class.java) {
            runBlocking {
                LbSimilar.ask("Song", "Artist", { _, _ -> katy }, { LbLabs.neighboursOf("""{"error": "gone"}""") ?: throw IOException("labs changed") })
            }
        }
        // While a recording found with nobody playing anything near it is an answer: none.
        assertEquals(emptyList<SimilarMatch.Neighbour>(), runBlocking { LbSimilar.ask("Song", "Artist", { _, _ -> katy }, { emptyList() }) })
    }

    @Test
    fun `a failure is held back and tried again after the cooldown, never counted as knowing nothing`() {
        val code = code("utils/ListenBrainzSimilar.kt")
        // ask() turns a thrown request into null, attempt() turns null into FAILED and a cooldown, and a CancellationException goes on up.
        assertTrue(Regex("""catch \(e: CancellationException\) \{\s*throw e""").containsMatchIn(code))
        assertTrue("?: return Outcome.FAILED.also { failures.note(songId) }" in code)
        assertTrue("if (ids.isNotEmpty()) database.transaction" in code)
        assertTrue("deleteListenBrainzSimilar(songId)" in code && "insertListenBrainzEdge(songId, id, now)" in code)
        assertFalse("nothing of Last.fm's is touched", Regex("""LastFm(Edge|Similar\()""").containsMatchIn(code))
    }

    // ---- The switch, with the flag and without it

    private val stored = listOf(
        mutablePreferencesOf(SimilarSourceKey to SimilarSource.BOTH.name),
        mutablePreferencesOf(SimilarSourceKey to SimilarSource.LASTFM.name),
        mutablePreferencesOf(SimilarFromLastFmKey to true),
        mutablePreferencesOf(SimilarSourceKey to SimilarSource.YOUTUBE.name),
        mutablePreferencesOf(),
    )

    @Test
    fun `with ListenBrainz in Last-fm's place the choice holds after the first of December, and is asked for without a key`() {
        assumeTrue(Unreleased.ENGINE)
        val chosen = listOf(SimilarSource.BOTH, SimilarSource.LASTFM, SimilarSource.LASTFM, SimilarSource.YOUTUBE, SimilarSource.YOUTUBE)
        for (day in listOf(LastFmEnd.PRO_FROM_MS - 1, LastFmEnd.PRO_FROM_MS)) {
            LastFmEnd.clock = { day }
            assertEquals(chosen, stored.map { similarSourceOf(it, listenBrainz = true) })
            assertTrue(lastFmQuestionAskable(listenBrainz = true))
        }
    }

    @Test
    fun `without the flag it is Last-fm's, as before - YouTube alone from the date, and no question`() {
        assumeTrue(Unreleased.ENGINE)
        LastFmEnd.clock = { LastFmEnd.PRO_FROM_MS }
        assertEquals(List(stored.size) { SimilarSource.YOUTUBE }, stored.map { similarSourceOf(it, listenBrainz = false) })
        assertFalse(lastFmQuestionAskable(listenBrainz = false))
    }

    @Test
    fun `the flag is the preview builds', and what the app acts on reads it`() {
        assertEquals(BuildConfig.PREVIEW_FEATURES, Unreleased.LB_SIMILAR)
        val unreleased = File(sources, "constants/Unreleased.kt").readText()
        assertTrue(Regex("""val LB_SIMILAR = BuildConfig\.PREVIEW_FEATURES\s""").containsMatchIn(unreleased))
        val similar = code("utils/LastFmSimilar.kt")
        assertTrue("fun lastFmQuestionAskable(listenBrainz: Boolean = Unreleased.LB_SIMILAR)" in similar)
        assertTrue("fun similarSourceOf(prefs: Preferences, listenBrainz: Boolean = Unreleased.LB_SIMILAR)" in similar)
        assertTrue("hasKey = listenBrainz || lastFmSimilarReachable()," in similar)
    }

    @Test
    fun `the split under Both learns from ListenBrainz's own cards, from when it took Last-fm's place`() {
        val now = 1_800_000_000_000L
        val window = now - SourceMix.WINDOW_MS
        assertEquals("without the flag, the whole window as before", window, LbSimilar.evidenceSince(now, now - 1_000, listenBrainz = false))
        assertEquals("with it, nothing before it took the place", now - 1_000, LbSimilar.evidenceSince(now, now - 1_000, listenBrainz = true))
        assertEquals("and never more than the window", window, LbSimilar.evidenceSince(now, window - 5_000, listenBrainz = true))
        assertEquals("not yet in its place: no card is its", now, LbSimilar.evidenceSince(now, null, listenBrainz = true))
    }

    // ---- Where the app asks it

    private val sources = File("src/main/java/com/dd3boh/outertune")

    /** The text without its comments, so a sentence that names a thing is not taken for a use of it. */
    private fun code(path: String) = File(sources, path).readText()
        .replace(Regex("""/\*.*?\*/""", RegexOption.DOT_MATCHES_ALL), "")
        .lines().joinToString("\n") { it.substringBefore("//") }

    @Test
    fun `the service asks ListenBrainz where it asked Last-fm, on a play and in the catch-up, and Last-fm without the flag`() {
        val service = code("playback/MusicService.kt")
        assertTrue(Regex("""if \(Unreleased\.LB_SIMILAR\) listenBrainzSimilar\.ensure\(mediaId, similarTitle, similarArtist\)\s*else lastFmSimilar\.ensure\(mediaId, similarTitle, similarArtist\)""").containsMatchIn(service))
        assertTrue("(if (listenBrainz) listenBrainzSimilar.catchUp() else lastFmSimilar.catchUp())" in service)
        assertTrue("val caughtUpKey = if (listenBrainz) ListenBrainzCaughtUpAtKey else LastFmCaughtUpAtKey" in service)
        assertTrue("val listenBrainz = Unreleased.LB_SIMILAR" in service)
        // The phone's own radio reads ListenBrainz's list first with the flag, and only Last.fm's without it.
        assertTrue(Regex("""lastFm = \(if \(Unreleased\.LB_SIMILAR\) read \{ radioListedFor\(id, RelatedSongMap\.SOURCE_LISTENBRAINZ, n\) \} else emptyList\(\)\)\s*\.ifEmpty \{ read \{ radioListedFor\(id, RelatedSongMap\.SOURCE_LASTFM, n\) \} \}""").containsMatchIn(service))
    }

    @Test
    fun `the engine reads ListenBrainz's lists in Last-fm's place, and Home rebuilds when either catches up`() {
        val loader = code("engine/EngineLoader.kt")
        assertTrue("listenBrainz: Boolean = Unreleased.LB_SIMILAR," in loader)
        assertTrue("SimilarSource.LASTFM -> database.engineEdges(if (listenBrainz) RelatedSongMap.SOURCE_LISTENBRAINZ else 1)" in loader)
        assertTrue("SimilarSource.BOTH -> if (listenBrainz) database.engineEdgesAllListenBrainz() else database.engineEdgesAll()" in loader)
        assertTrue("SimilarSource.YOUTUBE -> database.engineEdges(0)" in loader)
        val home = code("viewmodels/HomeViewModel.kt")
        assertTrue("merge(lastFmSimilar.caughtUpAt.drop(1), listenBrainzSimilar.caughtUpAt.drop(1)).collect {" in home)
        for (path in listOf("viewmodels/HomeViewModel.kt", "viewmodels/RecommendationsViewModel.kt")) {
            assertTrue(path, "database.sourceEvidence(LbSimilar.evidenceSince(now, context.dataStore[ListenBrainzSimilarSinceKey]))" in code(path))
        }
    }

    // ---- Its words

    private fun strings(folder: String): Map<String, Pair<String, String>> =
        Regex("""<string name="([^"]+)"([^>]*)>(.*?)</string>""", RegexOption.DOT_MATCHES_ALL)
            .findAll(File("src/main/res/$folder/strings-ot.xml").readText())
            .associate { it.groupValues[1] to (it.groupValues[2] to it.groupValues[3]) }

    /** Each text ListenBrainz's words use, with the Last.fm text it stands in for. */
    private val twins = mapOf(
        "listenbrainz_similar_question" to "setup_lastfm_question",
        "listenbrainz_similar_line" to "setup_lastfm_line",
        "listenbrainz_similar_short" to "setup_lastfm_short",
        "listenbrainz_similar_opt_in_title" to "lastfm_opt_in_title",
        "listenbrainz_similar_opt_in_body" to "lastfm_opt_in_body",
        "listenbrainz_similar_opt_in_enabled" to "lastfm_opt_in_enabled",
        "similar_source_listenbrainz" to "similar_source_lastfm",
        "similar_mix_share_listenbrainz" to "similar_mix_share",
        "similar_mix_listenbrainz_line" to "similar_mix_lastfm_line",
        "similar_source_listenbrainz_info" to "similar_source_info",
    )

    @Test
    fun `its words say ListenBrainz in English and French, held from translators, short, with no long dash`() {
        val english = strings("values")
        val french = strings("values-fr")
        for ((name, lastFm) in twins) {
            for ((folder, texts) in listOf("values" to english, "values-fr" to french)) {
                val (attributes, words) = texts[name] ?: error("$name is not in $folder")
                assertTrue("$folder/$name is not held from translators", "translatable=\"false\"" in attributes)
                assertTrue("$folder/$name: $words", "ListenBrainz" in words)
                assertFalse("$folder/$name: $words", "Last.fm" in words)
                assertFalse("$folder/$name has a long dash", '—' in words || '–' in words)
                assertTrue("$folder/$name: $words", Regex("""[.!?](?=\s|$)""").findAll(words).count() <= 3)
                // The same placeholders as the Last.fm text it stands in for.
                val holes = Regex("""%\d\$[.\d]*[a-z]""")
                assertEquals("$folder/$name", holes.findAll(texts.getValue(lastFm).second).map { it.value }.toList(), holes.findAll(words).map { it.value }.toList())
            }
        }
        for (name in twins.keys - "listenbrainz_similar_short" - "similar_mix_share_listenbrainz") {
            assertNotEquals("$name is not in French", english.getValue(name).second, french.getValue(name).second)
        }
        // It asks for nothing of an account, and says so where the question is put.
        for (name in listOf("listenbrainz_similar_line", "listenbrainz_similar_opt_in_body", "similar_source_listenbrainz_info")) {
            assertFalse(name, Regex("token|sign in|log in", RegexOption.IGNORE_CASE).containsMatchIn(english.getValue(name).second))
        }
        assertTrue("No account needed" in english.getValue("listenbrainz_similar_line").second)
        assertTrue("Aucun compte nécessaire" in french.getValue("listenbrainz_similar_line").second)
    }

    @Test
    fun `the setting, the setup question and the card say ListenBrainz with the flag, Last-fm's words without it`() {
        val settings = code("ui/screens/settings/RecommendationsSettings.kt")
        assertTrue("val listenBrainz = Unreleased.LB_SIMILAR" in settings)
        assertTrue("if (listenBrainz || lastFmSimilarReachable()) {" in settings)
        for ((name, lastFm) in listOf(
            "similar_source_listenbrainz_info" to "similar_source_info",
            "similar_source_listenbrainz" to "similar_source_lastfm",
            "similar_mix_share_listenbrainz" to "similar_mix_share",
            "similar_mix_listenbrainz_line" to "similar_mix_lastfm_line",
        )) assertTrue(name, "if (listenBrainz) R.string.$name else R.string.$lastFm" in settings)
        val card = code("ui/screens/OptInCards.kt")
        for ((name, lastFm) in listOf(
            "listenbrainz_similar_opt_in_title" to "lastfm_opt_in_title",
            "listenbrainz_similar_opt_in_body" to "lastfm_opt_in_body",
            "listenbrainz_similar_opt_in_enabled" to "lastfm_opt_in_enabled",
        )) assertTrue(name, "if (listenBrainz) R.string.$name else R.string.$lastFm" in card)
        val setup = code("ui/screens/ShortSetup.kt")
        assertTrue(Regex("""SetupQuestion\.LASTFM -> if \(Unreleased\.LB_SIMILAR\) QuestionTexts\(\s*icon = Icons\.Rounded\.Hub,\s*question = R\.string\.listenbrainz_similar_question,\s*line = R\.string\.listenbrainz_similar_line,\s*short = R\.string\.listenbrainz_similar_short,\s*\) else QuestionTexts\(\s*icon = Icons\.Rounded\.Hub,\s*question = R\.string\.setup_lastfm_question,""").containsMatchIn(setup))
        // Found by its name in Settings' search, and a way to the setting where an explanation names it.
        assertTrue("if (Unreleased.LB_SIMILAR) R.string.similar_source_listenbrainz else R.string.similar_source_lastfm" in code("ui/screens/settings/SettingsIndex.kt"))
        assertTrue("R.string.similar_source_listenbrainz to similarSource," in code("ui/screens/settings/SettingJump.kt"))
    }
}
