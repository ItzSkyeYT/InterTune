/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.migration

import com.zionhuang.innertube.models.Artist
import com.zionhuang.innertube.models.SongItem
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.IOException

/**
 * The pipeline between a parsed file and the playlists it becomes, against a fake search, so what
 * is imported, what waits for review and what is left out can be checked without a network.
 */
class ImportMatcherTest {

    private fun song(title: String, artist: String, seconds: Int?, id: String = "$title/$seconds") = SongItem(
        id = id,
        title = title,
        artists = artist.split(", ").map { Artist(name = it, id = null) },
        album = null,
        duration = seconds,
        thumbnail = "",
        explicit = false,
    )

    private fun track(title: String, vararg artists: String, seconds: Int? = null) =
        ImportedTrack(title, artists.toList(), durationSeconds = seconds)

    /** What the fake YouTube holds, by query. Anything else comes back empty. */
    private class FakeSearch(private val answers: Map<String, List<SongItem>>) {
        val queries = mutableListOf<String>()
        suspend fun search(query: String): List<SongItem> {
            queries += query
            return answers[query].orEmpty()
        }
    }

    private fun matcher(fake: FakeSearch, waits: MutableList<Long> = mutableListOf()) =
        ImportMatcher(search = fake::search, wait = { waits += it })

    @Test
    fun `an exact match is imported without review`() = runBlocking {
        val fake = FakeSearch(mapOf("Blinding Lights The Weeknd" to listOf(song("Blinding Lights", "The Weeknd", 200))))
        val outcome = matcher(fake).resolve(track("Blinding Lights", "The Weeknd", seconds = 200))
        assertTrue("got $outcome", outcome is Outcome.Matched)
        assertEquals("Blinding Lights", (outcome as Outcome.Matched).best.candidate.title)
    }

    @Test
    fun `a file with no lengths sends even an exact name to review, the plain one first`() = runBlocking {
        // TuneMyMusic's file has no length column at all. The name is all there is, and the live
        // take has it too, so somebody looks.
        val fake = FakeSearch(
            mapOf(
                "Blinding Lights The Weeknd" to listOf(
                    song("Blinding Lights (Live)", "The Weeknd", 261, id = "live"),
                    song("Blinding Lights", "The Weeknd", 200, id = "studio"),
                )
            )
        )
        val outcome = matcher(fake).resolve(track("Blinding Lights", "The Weeknd"))
        assertTrue("got $outcome", outcome is Outcome.Review)
        assertEquals(listOf("studio", "live"), (outcome as Outcome.Review).candidates.map { it.candidate.id })
        // One search, as for any other song: asking again would not bring a length.
        assertEquals(1, fake.queries.size)
    }

    @Test
    fun `only a remix and a live take goes to review with the candidates best first`() = runBlocking {
        val fake = FakeSearch(
            mapOf(
                "Midnight City M83" to listOf(
                    song("Midnight City (Extended Mix)", "M83", 431),
                    song("Midnight City (Live)", "M83", 262),
                    song("Wait", "M83", 343),
                    song("Outro", "M83", 247),
                    song("Reunion", "M83", 235),
                )
            )
        )
        val outcome = matcher(fake).resolve(track("Midnight City", "M83", seconds = 244))
        assertTrue("got $outcome", outcome is Outcome.Review)
        val candidates = (outcome as Outcome.Review).candidates
        assertEquals(ImportMatcher.REVIEW_CANDIDATES, candidates.size)
        assertEquals(candidates.sortedByDescending { it.total }, candidates)
        assertTrue(candidates.none { it.confident })
    }

    @Test
    fun `a version written after a dash finds the same version in brackets`() = runBlocking {
        // The 29 Sep import test: ranked first, and still sent to review at 0.70.
        val fake = FakeSearch(
            mapOf(
                "Hide - CS01 Version Dorian Concept" to listOf(
                    song("Hide (CS01 Version)", "Dorian Concept", 182, id = "cs01"),
                    song("Hide", "Dorian Concept", 181, id = "plain"),
                )
            )
        )
        val outcome = matcher(fake).resolve(track("Hide - CS01 Version", "Dorian Concept", seconds = 181))
        assertTrue("got $outcome", outcome is Outcome.Matched)
        assertEquals("cs01", (outcome as Outcome.Matched).best.candidate.id)
    }

    @Test
    fun `nothing for the title and artist falls back to the title, then gives up`() = runBlocking {
        val fake = FakeSearch(emptyMap())
        val outcome = matcher(fake).resolve(track("Obscure B-side", "Nobody Knows", seconds = 180))
        assertEquals(Outcome.NotFound, outcome)
        assertEquals(listOf("Obscure B-side Nobody Knows", "Obscure B-side"), fake.queries)
    }

    @Test
    fun `results that share neither title nor artist count as not found`() = runBlocking {
        val noise = listOf(song("Totally Different", "Someone Else", 200), song("Another One", "Nobody", 181))
        val fake = FakeSearch(mapOf("Obscure B-side Nobody Knows" to noise, "Obscure B-side" to noise))
        val outcome = matcher(fake).resolve(track("Obscure B-side", "Nobody Knows", seconds = 180))
        assertEquals(Outcome.NotFound, outcome)
        assertEquals(2, fake.queries.size)
    }

    @Test
    fun `the title alone can still find it`() = runBlocking {
        val fake = FakeSearch(mapOf("Kids MGMT" to emptyList(), "Kids" to listOf(song("Kids", "MGMT", 302))))
        val outcome = matcher(fake).resolve(track("Kids", "MGMT", seconds = 302))
        assertTrue("got $outcome", outcome is Outcome.Matched)
    }

    @Test
    fun `YouTube naming only the lead artist still matches a three artist export`() = runBlocking {
        // Searched as the file has it, title and first artist, and answered with the lead alone.
        val fake = FakeSearch(
            mapOf(
                "Get Lucky (feat. Pharrell Williams and Nile Rodgers) Daft Punk" to
                        listOf(song("Get Lucky", "Daft Punk", 369))
            )
        )
        val outcome = matcher(fake).resolve(
            track("Get Lucky (feat. Pharrell Williams and Nile Rodgers)", "Daft Punk", "Pharrell Williams", "Nile Rodgers", seconds = 370)
        )
        assertTrue("got $outcome", outcome is Outcome.Matched)
    }

    @Test
    fun `requests are spaced and the first one does not wait`() = runBlocking {
        val fake = FakeSearch(
            mapOf(
                "A x" to listOf(song("A", "x", 100)),
                "B x" to listOf(song("B", "x", 100)),
                "C x" to listOf(song("C", "x", 100)),
            )
        )
        val waits = mutableListOf<Long>()
        val tracks = listOf(track("A", "x", seconds = 100), track("B", "x", seconds = 100), track("C", "x", seconds = 100))
        matcher(fake, waits).run(tracks, isDone = { false }, onResult = { _, _ -> })
        assertEquals(3, fake.queries.size)
        assertEquals(listOf(ImportMatcher.SPACING_MS, ImportMatcher.SPACING_MS), waits)
    }

    @Test
    fun `a failed request is retried, and a dead connection stops the run`() = runBlocking {
        var calls = 0
        val flaky = ImportMatcher(
            search = { calls++; if (calls == 1) throw IOException("blip") else listOf(song("A", "x", 100)) },
            wait = {},
        )
        assertTrue(flaky.resolve(track("A", "x", seconds = 100)) is Outcome.Matched)

        val dead = ImportMatcher(search = { throw IOException("offline") }, wait = {})
        try {
            dead.resolve(track("A", "x", seconds = 100))
            fail("should have stopped")
        } catch (expected: SearchUnavailable) {
        }
    }

    @Test
    fun `an answer that is no answer is waited out longer than a connection that is not there`() = runBlocking {
        // YouTube answered one search of a long import with "invalid argument" three times in
        // seven seconds, and was itself again within the minute.
        class OddAnswer : Exception("400 Request contains an invalid argument.")
        var calls = 0
        val waits = mutableListOf<Long>()
        val odd = ImportMatcher(
            search = { calls++; if (calls <= 4) throw OddAnswer() else listOf(song("A", "x", 100)) },
            wait = { waits += it },
            answered = { it is OddAnswer },
        )
        assertTrue(odd.resolve(track("A", "x", seconds = 100)) is Outcome.Matched)
        assertEquals(5, calls)
        assertEquals(ImportMatcher.PATIENT_WAITS_MS, waits.filter { it != ImportMatcher.SPACING_MS })
        assertTrue("most of a minute and no more", ImportMatcher.PATIENT_WAITS_MS.sum() in 30_000L..60_000L)

        // It still gives up, and a connection that is not there is still said so within seconds.
        val never = ImportMatcher(search = { throw OddAnswer() }, wait = {}, answered = { it is OddAnswer })
        try {
            never.resolve(track("A", "x", seconds = 100))
            fail("should have stopped")
        } catch (expected: SearchUnavailable) {
        }
        calls = 0
        waits.clear()
        val dead = ImportMatcher(search = { calls++; throw IOException("offline") }, wait = { waits += it }, answered = { it is OddAnswer })
        try {
            dead.resolve(track("A", "x", seconds = 100))
            fail("should have stopped")
        } catch (expected: SearchUnavailable) {
        }
        assertEquals(3, calls)
        assertTrue(waits.sum() < 10_000L)
    }

    @Test
    fun `a search that YouTube refused is not tried again`() = runBlocking {
        var calls = 0
        val refused = ImportMatcher(
            search = { calls++; throw SearchRefused(IOException("429 Too Many Requests")) },
            wait = {},
        )
        try {
            refused.resolve(track("A", "x", seconds = 100))
            fail("should have stopped")
        } catch (expected: SearchRefused) {
        }
        // A dropped connection is asked three times. A refusal is asked once: asking is what keeps it up.
        assertEquals(1, calls)
    }

    @Test
    fun `nothing is asked while the app is backing off`() = runBlocking {
        val fake = FakeSearch(mapOf("A x" to listOf(song("A", "x", 100))))
        var backingOff = true
        val matcher = ImportMatcher(search = fake::search, wait = {}, backingOff = { backingOff })
        try {
            matcher.resolve(track("A", "x", seconds = 100))
            fail("should have stopped")
        } catch (expected: SearchRefused) {
        }
        assertTrue(fake.queries.isEmpty())

        backingOff = false
        assertTrue(matcher.resolve(track("A", "x", seconds = 100)) is Outcome.Matched)
    }

    @Test
    fun `a back-off that begins in the middle of a run stops it before the next search`() = runBlocking {
        val fake = FakeSearch((1..4).associate { "Song $it x" to listOf(song("Song $it", "x", 100 + it)) })
        val tracks = (1..4).map { track("Song $it", "x", seconds = 100 + it) }
        val found = mutableListOf<Int>()
        // The back-off begins as the second answer comes in.
        val matcher = ImportMatcher(search = fake::search, wait = {}, backingOff = { fake.queries.size >= 2 })
        try {
            matcher.run(tracks, isDone = { false }, onResult = { index, _ -> found += index })
            fail("should have stopped")
        } catch (expected: SearchRefused) {
        }
        assertEquals(listOf(0, 1), found)
        assertEquals(listOf("Song 1 x", "Song 2 x"), fake.queries)
    }

    @Test
    fun `a cancelled run keeps what it found and resumes where it stopped`() = runBlocking {
        val file = ImportParse.Parsed(
            ExportFormat.CSV,
            listOf(ImportedPlaylist("Mine", (1..6).map { track("Song $it", "x", seconds = 100 + it) })),
            skippedRows = 0,
        )
        val run = ImportRun(file)
        val answers = (1..6).associate { "Song $it x" to listOf(song("Song $it", "x", 100 + it)) }

        lateinit var job: Job
        var calls = 0
        val first = ImportMatcher(
            search = { query ->
                calls++
                if (calls == 3) job.cancel()
                answers[query].orEmpty()
            },
            // yield is where a cancelled coroutine notices, as delay would be in the app.
            wait = { yield() },
        )
        job = launch { first.run(run.tracks, run::isDone, run::record) }
        job.join()
        assertEquals(3, run.snapshot().checked)

        val fake = FakeSearch(answers)
        matcher(fake).run(run.tracks, run::isDone, run::record)
        assertEquals(listOf("Song 4 x", "Song 5 x", "Song 6 x"), fake.queries)
        assertEquals(6, run.snapshot().matched)
    }

    @Test
    fun `what is ready is what will be made, also when two rows come to one song`() {
        // A service that holds the single and the album cut, and YouTube only one of them: two
        // rows, two answers, one song in the playlist. "490 songs are ready" and then "484
        // songs" made was what a file of 498 gave.
        val single = track("Golf", "x", seconds = 200)
        val album = track("Golf (Album Version)", "x", seconds = 201)
        val other = track("Hotel", "x", seconds = 200)
        val run = ImportRun(
            ImportParse.Parsed(ExportFormat.EXPORTIFY, listOf(ImportedPlaylist("Only", listOf(single, album, other))), skippedRows = 0)
        )
        val golf = song("Golf", "x", 200)
        run.record(0, Outcome.Matched(score(single.wanted(), golf)))
        run.record(1, Outcome.Matched(score(album.wanted(), golf)))
        run.record(2, Outcome.Matched(score(other.wanted(), song("Hotel", "x", 200))))

        assertEquals(3, run.snapshot().kept)
        assertEquals(2, run.snapshot().ready)
        assertEquals(run.playlistsToCreate().sumOf { it.second.size }, run.snapshot().ready)
        // One of the two taken out by hand changes nothing: the other still brings the song.
        run.skip(1)
        assertEquals(2, run.snapshot().ready)
        run.skip(0)
        assertEquals(1, run.snapshot().ready)
    }

    @Test
    fun `playlists come out in the file's order with picks in and skips out`() {
        val a = track("Alpha", "x", seconds = 200)
        val b = track("Bravo", "x", seconds = 200)
        val c = track("Charlie", "x", seconds = 200)
        val d = track("Delta", "x", seconds = 200)
        val e = track("Echo", "x", seconds = 200)
        val file = ImportParse.Parsed(
            ExportFormat.TUNEMYMUSIC,
            listOf(
                ImportedPlaylist("First", listOf(a, b, c, d)),
                ImportedPlaylist("Second", listOf(e, a)),
                ImportedPlaylist("Nothing left", listOf(c)),
            ),
            skippedRows = 0,
        )
        val run = ImportRun(file)
        // Alpha is in two playlists and is searched for, and reviewed, once.
        assertEquals(5, run.tracks.size)

        fun matched(t: ImportedTrack) = Outcome.Matched(score(t.wanted(), song(t.title, "x", 200)))
        val bravoPick = song("Bravo", "x", 203, id = "bravo-picked")
        run.record(0, matched(a))
        run.record(1, Outcome.Review(listOf(score(b.wanted(), bravoPick))))
        run.record(2, Outcome.NotFound)
        run.record(3, Outcome.Review(listOf(score(d.wanted(), song("Delta (Live)", "x", 260)))))
        run.record(4, matched(e))

        run.pick(1, bravoPick)
        run.skip(3)

        val snapshot = run.snapshot()
        assertEquals(5, snapshot.checked)
        assertEquals(2, snapshot.matched)
        assertEquals(2, snapshot.review.size)
        assertEquals(0, snapshot.undecided)
        assertEquals(listOf("Charlie"), snapshot.notFound.map { it.title })

        val created = run.playlistsToCreate()
        assertEquals(listOf("First", "Second"), created.map { it.first })
        assertEquals(listOf("Alpha/200", "bravo-picked"), created[0].second.map { it.id })
        assertEquals(listOf("Echo/200", "Alpha/200"), created[1].second.map { it.id })
        // Three songs of the file have their answer, and Alpha goes into two playlists.
        assertEquals(3, snapshot.kept)
        assertEquals(4, snapshot.ready)

        // Taking the pick back leaves Bravo out again, and counts it as undecided.
        run.undecide(1)
        assertEquals(1, run.snapshot().undecided)
        assertEquals(listOf("Alpha/200"), run.playlistsToCreate()[0].second.map { it.id })

        // A match that went in without asking can still be taken out by hand, from every playlist
        // it is in, and put back.
        run.skip(0)
        assertEquals(listOf("Echo/200"), run.playlistsToCreate().single().second.map { it.id })
        assertEquals(1, run.snapshot().kept)
        run.undecide(0)
        assertEquals(2, run.snapshot().kept)
    }
}
