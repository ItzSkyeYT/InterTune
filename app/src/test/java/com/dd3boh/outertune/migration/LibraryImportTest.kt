/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.migration

import com.dd3boh.outertune.migration.LibraryImport.Stage
import com.zionhuang.innertube.models.Artist
import com.zionhuang.innertube.models.SongItem
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.util.concurrent.CopyOnWriteArrayList

/**
 * The import as the screen drives it, from the file somebody chose to the playlists, with a
 * YouTube that is a map, a library that is a list and a folder of its own for the app's files.
 * What the screen can ask for is all here: a file, Cancel and Continue, a pick and a skip, the
 * first result for every song at one tap, Create playlists, Start over. So is what the screen
 * cannot ask for and Android does anyway: the app killed in the middle, and started again.
 */
class LibraryImportTest {

    /** Where the app keeps the import under way. It outlives every app of a test, as the phone's files do. */
    private val dir: File = Files.createTempDirectory("library-import").toFile()

    /** What each app of a test runs on. */
    private val apps = CopyOnWriteArrayList<CoroutineScope>()

    @After
    fun stop() {
        kill()
        dir.deleteRecursively()
    }

    /**
     * The app killed where it stands, as Android kills one for its memory: nothing of it runs
     * again, nothing is told, and what is on the disk is all that is left.
     */
    private fun kill() {
        apps.forEach { it.cancel() }
        apps.clear()
    }

    private fun song(title: String, artist: String, seconds: Int?, id: String = "$title/$seconds") = SongItem(
        id = id,
        title = title,
        artists = listOf(Artist(name = artist, id = null)),
        album = null,
        duration = seconds,
        thumbnail = "",
        explicit = false,
    )

    /**
     * YouTube for a test: what it holds by query, and every query it was asked, in order. A test
     * gets in a run's way with [holdSearch], which keeps one search open until it is let go or
     * its run is cancelled, and with [failing], thrown in place of every answer while it is set.
     */
    private class FakeYouTube(private val answers: Map<String, List<SongItem>>) {
        val queries = CopyOnWriteArrayList<String>()

        @Volatile
        var failing: Exception? = null

        @Volatile
        private var hold: Hold? = null

        class Hold(val at: Int) {
            val reached = CompletableDeferred<Unit>()
            val letGo = CompletableDeferred<Unit>()
        }

        /** The search number [at], counting every search made so far, waits where it is. */
        fun holdSearch(at: Int) = Hold(at).also { hold = it }

        suspend fun search(query: String): List<SongItem> {
            queries.add(query)
            hold?.takeIf { it.at == queries.size }?.let {
                it.reached.complete(Unit)
                it.letGo.await()
            }
            failing?.let { throw it }
            return answers[query].orEmpty()
        }
    }

    /** The playlists Create playlists was asked to make, each time it was asked. */
    private val made = CopyOnWriteArrayList<List<Pair<String, List<SongItem>>>>()

    @Volatile
    private var libraryFails = false

    /** The app's back-off, which says YouTube is refusing this connection for now. */
    @Volatile
    private var backingOff = false

    /** How many times the library was asked to make playlists, whether it did or not. */
    private val askedToMake = java.util.concurrent.atomic.AtomicInteger()

    /**
     * The app started, with the import screen open on it: a new session over the files that are
     * there. [saveAfter] is how long a change waits to be written; by default it does not wait.
     */
    private fun session(
        youTube: FakeYouTube,
        store: ImportStore = ImportStore(dir),
        saveAfter: suspend () -> Unit = {},
    ) = LibraryImport(
        store = store,
        readFile = { error("a test hands its file over itself") },
        // No waiting between searches, only the chance for a cancelled run to notice.
        matcher = { ImportMatcher(search = youTube::search, wait = { yield() }, backingOff = { backingOff }) },
        makePlaylists = { lists ->
            askedToMake.incrementAndGet()
            if (libraryFails) throw IOException("the database is closed")
            made.add(lists)
            LibraryImport.Created(lists.size, lists.sumOf { it.second.size })
        },
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Default).also { apps.add(it) },
        saveAfter = saveAfter,
    )

    /** A YouTube nobody should be asking anything. */
    private fun silent() = FakeYouTube(emptyMap())

    /** Whether an import is kept on the disk, asked as an app starting would ask. */
    private fun kept() = ImportStore(dir).holdsOne()

    /** [count] songs of one playlist as a plain CSV, each with its length. */
    private fun csv(count: Int) = buildString {
        append("Title,Artist,Duration\n")
        for (i in 1..count) append("Song $i,x,${100 + i}\n")
    }

    /** A YouTube that has every one of them, under the query the import makes for it. */
    private fun has(count: Int) = FakeYouTube((1..count).associate { "Song $it x" to listOf(song("Song $it", "x", 100 + it)) })

    private fun asked(vararg songs: Int) = songs.map { "Song $it x" }

    private fun LibraryImport.open(name: String, text: String) = open { LibraryImport.Picked(name, text.toByteArray()) }

    private suspend fun LibraryImport.reaches(stage: Stage): LibraryImport.State =
        withTimeout(10_000) { state.first { it.stage == stage } }

    private suspend fun FakeYouTube.Hold.isReached() = withTimeout(10_000) { reached.await() }

    // A file, and its songs looked up.

    @Test
    fun `a file is read, each song is looked up once, and the import is ready to create`() = runBlocking {
        val youTube = has(5)
        val session = session(youTube)
        assertEquals(Stage.IDLE, session.state.value.stage)

        session.open("road_trip.csv", csv(5))
        val state = session.reaches(Stage.MATCHED)

        assertEquals("road_trip.csv", state.fileName)
        assertEquals(ExportFormat.CSV, state.format)
        assertEquals(1, state.playlists)
        val progress = state.progress!!
        assertEquals(5, progress.total)
        assertEquals(5, progress.checked)
        assertEquals(5, progress.matched)
        assertEquals(asked(1, 2, 3, 4, 5), youTube.queries)
    }

    @Test
    fun `what cannot be read, or is not an export, says so and looks nothing up`() = runBlocking {
        val youTube = has(1)
        val session = session(youTube)

        session.open { LibraryImport.Picked("gone.csv", null) }
        var state = session.reaches(Stage.FAILED)
        assertEquals("gone.csv", state.fileName)
        assertNull("no reason is known for a file that would not open", state.problem)

        session.open("notes.csv", "Day,Weather\nMonday,Rain\n")
        state = withTimeout(10_000) { session.state.first { it.fileName == "notes.csv" && it.stage == Stage.FAILED } }
        assertEquals(ImportParse.Problem.NO_TITLE_COLUMN, state.problem)
        assertEquals("Day, Weather", state.detail)

        session.open { throw IOException("the picker's provider died") }
        state = withTimeout(10_000) { session.state.first { it.fileName == null && it.stage == Stage.FAILED } }
        assertNull(state.problem)

        assertTrue(youTube.queries.isEmpty())
        assertNull(state.progress)
    }

    @Test
    fun `the count on the screen follows the lookups`() = runBlocking {
        val youTube = has(5)
        val third = youTube.holdSearch(at = 3)
        val session = session(youTube)

        session.open("mine.csv", csv(5))
        third.isReached()

        val state = session.state.value
        assertEquals(Stage.MATCHING, state.stage)
        assertEquals(2, state.progress!!.checked)
        assertEquals(5, state.progress!!.total)

        third.letGo.complete(Unit)
        assertEquals(5, session.reaches(Stage.MATCHED).progress!!.checked)
    }

    // Cancel and Continue.

    @Test
    fun `Cancel stops the lookups and keeps what was found, and Continue takes up where it stopped`() = runBlocking {
        val youTube = has(6)
        val fourth = youTube.holdSearch(at = 4)
        val session = session(youTube)
        session.open("mine.csv", csv(6))
        fourth.isReached()

        session.cancel()
        // At once, with nothing to wait for: the screen changes under the finger.
        assertEquals(Stage.STOPPED, session.state.value.stage)
        assertEquals(3, session.state.value.progress!!.checked)

        session.resume()
        val state = session.reaches(Stage.MATCHED)
        assertEquals(6, state.progress!!.matched)
        // The fourth was asked for twice, having had no answer the first time, and the first three once.
        assertEquals(asked(1, 2, 3, 4, 4, 5, 6), youTube.queries)
    }

    @Test
    fun `Continue tapped twice is one run, and Cancel stops it`() = runBlocking {
        val youTube = has(6)
        val second = youTube.holdSearch(at = 2)
        val session = session(youTube)
        session.open("mine.csv", csv(6))
        second.isReached()
        session.cancel()

        val third = youTube.holdSearch(at = 3)
        session.resume()
        session.resume()
        third.isReached()
        session.cancel()
        assertEquals(Stage.STOPPED, session.state.value.stage)

        // A second run waiting behind the first would go on from here by itself.
        third.letGo.complete(Unit)
        delay(300)
        assertEquals(Stage.STOPPED, session.state.value.stage)
        assertEquals(asked(1, 2, 2), youTube.queries)
        assertEquals(1, session.state.value.progress!!.checked)
    }

    @Test
    fun `a connection that drops stops the run after three tries, and Continue picks it up`() = runBlocking {
        val youTube = has(5)
        val third = youTube.holdSearch(at = 3)
        val session = session(youTube)
        session.open("mine.csv", csv(5))
        third.isReached()

        youTube.failing = IOException("Unable to resolve host")
        third.letGo.complete(Unit)
        val offline = session.reaches(Stage.OFFLINE)
        assertEquals(2, offline.progress!!.checked)
        assertEquals(asked(1, 2, 3, 3, 3), youTube.queries)

        youTube.failing = null
        session.resume()
        assertEquals(5, session.reaches(Stage.MATCHED).progress!!.matched)
        assertEquals(asked(1, 2, 3, 3, 3, 3, 4, 5), youTube.queries)
    }

    // YouTube refusing.

    @Test
    fun `a search YouTube refuses pauses the run at once with what it found, and Continue goes on`() = runBlocking {
        val youTube = has(5)
        val third = youTube.holdSearch(at = 3)
        val session = session(youTube)
        session.open("mine.csv", csv(5))
        third.isReached()

        youTube.failing = SearchRefused(IOException("429 Too Many Requests"))
        third.letGo.complete(Unit)
        val paused = session.reaches(Stage.PAUSED)
        assertEquals(2, paused.progress!!.checked)
        // Asked once. A dropped connection is asked three times, and a refusal must not be.
        assertEquals(asked(1, 2, 3), youTube.queries)

        // Continue too soon is one more search, refused, and the run waits again.
        session.resume()
        withTimeout(10_000) { session.state.first { it.stage == Stage.PAUSED && youTube.queries.size == 4 } }
        assertEquals(2, session.state.value.progress!!.checked)

        youTube.failing = null
        session.resume()
        assertEquals(5, session.reaches(Stage.MATCHED).progress!!.matched)
        assertEquals(asked(1, 2, 3, 3, 3, 4, 5), youTube.queries)
    }

    @Test
    fun `while the app is backing off the run waits and asks nothing, Continue or not`() = runBlocking {
        val youTube = has(4)
        backingOff = true
        val session = session(youTube)
        session.open("mine.csv", csv(4))
        val paused = session.reaches(Stage.PAUSED)
        assertEquals(0, paused.progress!!.checked)
        assertEquals(4, paused.progress!!.total)

        session.resume()
        session.reaches(Stage.PAUSED)
        assertTrue("not one search goes out during the back-off", youTube.queries.isEmpty())

        backingOff = false
        session.resume()
        assertEquals(4, session.reaches(Stage.MATCHED).progress!!.matched)
        assertEquals(asked(1, 2, 3, 4), youTube.queries)
    }

    @Test
    fun `a back-off that begins during a run pauses it before the next search`() = runBlocking {
        val youTube = has(5)
        val third = youTube.holdSearch(at = 3)
        val session = session(youTube)
        session.open("mine.csv", csv(5))
        third.isReached()

        backingOff = true
        third.letGo.complete(Unit)
        // The third was already asked and its answer counts. The fourth is not asked.
        assertEquals(3, session.reaches(Stage.PAUSED).progress!!.checked)
        assertEquals(asked(1, 2, 3), youTube.queries)
    }

    @Test
    fun `a paused import is kept, and comes back stopped when the app is started again`() = runBlocking {
        val youTube = has(5)
        val third = youTube.holdSearch(at = 3)
        // Nothing would be written in time by itself: what is there after the kill, the pause wrote.
        val before = session(youTube, saveAfter = { awaitCancellation() })
        before.open("mine.csv", csv(5))
        third.isReached()
        youTube.failing = SearchRefused()
        third.letGo.complete(Unit)
        before.reaches(Stage.PAUSED)
        before.written()
        kill()

        val after = session(silent()).reaches(Stage.STOPPED)
        assertEquals(2, after.progress!!.checked)
        assertEquals(5, after.progress!!.total)
    }

    // Picks, and the playlists.

    /** Two playlists that share a song: one that matches, one with two versions to choose from, one YouTube does not have. */
    private val mixed = "Title,Artist,Duration,Playlist\n" +
        "Song 1,x,101,First\n" +
        "Song 2,x,102,First\n" +
        "Song 3,x,103,First\n" +
        "Song 1,x,101,Second\n"

    private val edit = song("Song 2", "x", 140, id = "edit")
    private val live = song("Song 2 (Live)", "x", 150, id = "live")

    private fun hasMixed() = FakeYouTube(
        mapOf(
            "Song 1 x" to listOf(song("Song 1", "x", 101, id = "one")),
            "Song 2 x" to listOf(live, edit),
        )
    )

    @Test
    fun `picks and leave-outs decide what Create playlists makes`() = runBlocking {
        val session = session(hasMixed())
        session.open("everything.csv", mixed)
        var state = session.reaches(Stage.MATCHED)
        assertEquals(2, state.playlists)
        // Song 1 is in both playlists and is one song to look up.
        assertEquals(3, state.progress!!.total)
        assertEquals(listOf("one"), state.progress!!.matchedItems.map { it.best.candidate.id })
        assertEquals(listOf("edit", "live"), state.progress!!.review.single().candidates.map { it.candidate.id })
        assertEquals(listOf("Song 3"), state.progress!!.notFound.map { it.title })
        assertEquals(1, state.progress!!.kept)

        session.pick(1, edit)
        assertEquals(2, session.state.value.progress!!.kept)
        session.skip(0)
        assertEquals(1, session.state.value.progress!!.kept)
        session.undecide(0)
        assertEquals(2, session.state.value.progress!!.kept)

        session.create()
        state = session.reaches(Stage.CREATED)
        assertEquals(2, state.createdPlaylists)
        assertEquals(3, state.createdSongs)
        assertEquals(
            listOf("First" to listOf("one", "edit"), "Second" to listOf("one")),
            made.single().map { (name, songs) -> name to songs.map { it.id } },
        )

        // Done, or Import another file.
        session.reset()
        assertEquals(LibraryImport.State(), session.state.value)
    }

    @Test
    fun `Create playlists tapped twice makes them once`() = runBlocking {
        val session = session(has(3))
        session.open("mine.csv", csv(3))
        session.reaches(Stage.MATCHED)

        session.create()
        session.create()
        session.reaches(Stage.CREATED)
        session.create()
        delay(200)

        assertEquals(1, made.size)
        assertEquals(Stage.CREATED, session.state.value.stage)
    }

    @Test
    fun `a library that cannot be written to leaves the import ready to try again`() = runBlocking {
        val session = session(has(3))
        session.open("mine.csv", csv(3))
        session.reaches(Stage.MATCHED)

        libraryFails = true
        session.create()
        val back = withTimeout(10_000) { session.state.first { it.stage == Stage.MATCHED && askedToMake.get() == 1 } }
        assertEquals(3, back.progress!!.kept)
        assertTrue(made.isEmpty())

        libraryFails = false
        session.create()
        assertEquals(3, session.reaches(Stage.CREATED).createdSongs)
    }

    @Test
    fun `nothing is created from a run that has not finished`() = runBlocking {
        val youTube = has(4)
        val third = youTube.holdSearch(at = 3)
        val session = session(youTube)
        session.open("mine.csv", csv(4))
        third.isReached()

        session.create()
        session.cancel()
        session.create()
        delay(200)

        assertTrue(made.isEmpty())
        assertEquals(Stage.STOPPED, session.state.value.stage)
    }

    // The first result for every song still to check, in one tap.

    private fun studio(i: Int) = song("Song $i", "x", 100 + i, id = "studio-$i")
    private fun live(i: Int) = song("Song $i (Live)", "x", 150, id = "live-$i")

    /**
     * Seven songs in two playlists. The first has its length and goes in without asking. The next
     * five have none, as in an export with no length column, and wait to be checked however well
     * the name matches. YouTube has nothing for the last.
     */
    private val lengthless = "Title,Artist,Duration,Playlist\n" +
        "Song 1,x,101,First\n" +
        "Song 2,x,,First\n" +
        "Song 3,x,,First\n" +
        "Song 4,x,,Second\n" +
        "Song 5,x,,Second\n" +
        "Song 6,x,,Second\n" +
        "Song 7,x,,Second\n"

    /**
     * Two versions of each song that waits, the live one first, as a search may well list it. The
     * import puts the closer match first, and that one is the first a tap for all takes.
     */
    private fun hasLengthless() = FakeYouTube(
        mapOf("Song 1 x" to listOf(studio(1))) + (2..6).associate { "Song $it x" to listOf(live(it), studio(it)) }
    )

    /** [count] songs of one playlist in a file with no length column at all: every one of them waits. */
    private fun noLengths(count: Int) = buildString {
        append("Title,Artist\n")
        for (i in 1..count) append("Song $i,x\n")
    }

    private fun hasTwoOfEach(count: Int) = FakeYouTube((1..count).associate { "Song $it x" to listOf(live(it), studio(it)) })

    /** What stands against each song of the review list, in its order: the id picked, "skipped", or null while it waits. */
    private fun LibraryImport.decided(): List<String?> = state.value.progress!!.review.map {
        when (val choice = it.choice) {
            is ImportRun.Choice.Picked -> choice.song.id
            ImportRun.Choice.Skipped -> "skipped"
            null -> null
        }
    }

    @Test
    fun `one tap takes the first result for every song still waiting, and for no other`() = runBlocking {
        val session = session(hasLengthless())
        session.open("everything.csv", lengthless)
        val before = session.reaches(Stage.MATCHED).progress!!
        // The closer match is listed first, whichever way round YouTube had them.
        assertEquals(listOf("studio-2", "live-2"), before.review.first().candidates.map { it.candidate.id })
        assertEquals(listOf(null, null, null, null, null), session.decided())

        // By hand: the other version of one song, another skipped, and the one that matched left out.
        session.pick(1, live(2))
        session.skip(2)
        session.skip(0)

        session.pickFirsts()

        assertEquals(listOf("live-2", "skipped", "studio-4", "studio-5", "studio-6"), session.decided())
        val after = session.state.value.progress!!
        assertTrue("a song left out by hand stays out", after.matchedItems.single().leftOut)
        assertEquals("studio-1", after.matchedItems.single().best.candidate.id)
        // A song YouTube does not have has no first result to take.
        assertEquals(listOf("Song 7"), after.notFound.map { it.title })
        assertEquals(Stage.MATCHED, session.state.value.stage)
    }

    @Test
    fun `the counts follow it, and Create playlists makes what it took`() = runBlocking {
        val session = session(hasLengthless())
        session.open("everything.csv", lengthless)
        var progress = session.reaches(Stage.MATCHED).progress!!
        assertEquals(1, progress.matched)
        assertEquals(5, progress.review.size)
        assertEquals(1, progress.notFound.size)
        // Ready to go into playlists: only the one that matched. Still to check: all five.
        assertEquals(1, progress.kept)
        assertEquals(5, progress.undecided)

        session.pickFirsts()

        progress = session.state.value.progress!!
        assertEquals("the one that matched and the five it took", 6, progress.kept)
        assertEquals(5, progress.picked)
        assertEquals(0, progress.undecided)
        // It decides, it does not move a song from one list to another.
        assertEquals(1, progress.matched)
        assertEquals(5, progress.review.size)
        assertEquals(1, progress.notFound.size)
        assertEquals(7, progress.checked)

        session.create()
        assertEquals(6, session.reaches(Stage.CREATED).createdSongs)
        assertEquals(
            listOf("First" to listOf("studio-1", "studio-2", "studio-3"), "Second" to listOf("studio-4", "studio-5", "studio-6")),
            made.single().map { (name, songs) -> name to songs.map { it.id } },
        )
    }

    @Test
    fun `after it each song can still be changed, skipped or taken back`() = runBlocking {
        val session = session(hasLengthless())
        session.open("everything.csv", lengthless)
        session.reaches(Stage.MATCHED)
        session.pickFirsts()

        // Its picks are picks like any other: another version, a skip, and one taken back.
        session.pick(1, live(2))
        session.skip(2)
        session.undecide(3)

        assertEquals(listOf("live-2", "skipped", null, "studio-5", "studio-6"), session.decided())
        val progress = session.state.value.progress!!
        assertEquals(1, progress.undecided)
        assertEquals(4, progress.kept)

        session.create()
        assertEquals(4, session.reaches(Stage.CREATED).createdSongs)
        assertEquals(
            listOf("First" to listOf("studio-1", "live-2"), "Second" to listOf("studio-5", "studio-6")),
            made.single().map { (name, songs) -> name to songs.map { it.id } },
        )
    }

    @Test
    fun `what it took is kept like any pick, and is there after the app was killed`() = runBlocking {
        // Seven songs kept two to a file: what one tap takes is spread over three files.
        val before = session(hasLengthless(), ImportStore(dir, block = 2))
        before.open("everything.csv", lengthless)
        before.reaches(Stage.MATCHED)
        before.pick(1, live(2))
        before.pickFirsts()
        before.saved()
        val left = before.state.value
        kill()

        val youTube = silent()
        val after = session(youTube, ImportStore(dir, block = 2))
        assertEquals(left, after.reaches(Stage.MATCHED))
        assertEquals(listOf("live-2", "studio-3", "studio-4", "studio-5", "studio-6"), after.decided())
        assertEquals(0, after.state.value.progress!!.undecided)
        assertTrue("an import that comes back asks YouTube nothing", youTube.queries.isEmpty())

        // And they are picks still: one can be skipped, and the playlists are made of the rest.
        after.skip(5)
        after.create()
        assertEquals(5, after.reaches(Stage.CREATED).createdSongs)
        assertEquals(
            listOf("First" to listOf("studio-1", "live-2", "studio-3"), "Second" to listOf("studio-4", "studio-5")),
            made.single().map { (name, songs) -> name to songs.map { it.id } },
        )
    }

    @Test
    fun `one tap is one write of each file it changed, and a second tap changes and writes nothing`() = runBlocking {
        // Two songs to a file: [1, 2] [3, 4] [5, 6] [7].
        val before = session(hasLengthless(), ImportStore(dir, block = 2))
        before.open("everything.csv", lengthless)
        before.reaches(Stage.MATCHED)
        before.pick(1, live(2))
        before.skip(2)
        before.saved()
        kill()

        // The app started again over what was kept. No lookups run in it, so nothing is written
        // but what the taps below ask for, and each of those waits to be let go.
        val store = ImportStore(dir, block = 2)
        val wait = Channel<Unit>(Channel.UNLIMITED)
        val session = session(silent(), store, saveAfter = { wait.receive() })
        session.reaches(Stage.MATCHED)
        assertEquals("reading an import back writes nothing", 0, store.written)

        // Still waiting: songs 4, 5 and 6, which are in the second and third files.
        session.pickFirsts()
        session.written()
        assertEquals("nothing is written before its turn", 0, store.written)
        wait.send(Unit)
        session.saved()
        assertEquals("the file of the song that matched and the file of the song not found are not written again", 2, store.written)

        val once = session.state.value
        session.pickFirsts()
        wait.send(Unit)
        session.saved()
        session.written()
        assertEquals(once, session.state.value)
        assertEquals(2, store.written)
        assertEquals(listOf("live-2", "skipped", "studio-4", "studio-5", "studio-6"), session.decided())
    }

    @Test
    fun `during the lookups it takes what has been found so far, as a tap on one song does`() = runBlocking {
        val youTube = hasTwoOfEach(6)
        val fifth = youTube.holdSearch(at = 5)
        val session = session(youTube)
        session.open("mine.csv", noLengths(6))
        fifth.isReached()
        assertEquals(listOf(null, null, null, null), session.decided())

        // A tap on one song is taken while the others are still being looked up.
        session.pick(0, live(1))
        assertEquals(listOf("live-1", null, null, null), session.decided())
        // So is the tap for all, which is for all there are at that moment.
        session.pickFirsts()
        assertEquals(listOf("live-1", "studio-2", "studio-3", "studio-4"), session.decided())
        assertEquals(Stage.MATCHING, session.state.value.stage)

        // What is found after it was not there to take, and waits for a tap of its own.
        fifth.letGo.complete(Unit)
        val done = session.reaches(Stage.MATCHED).progress!!
        assertEquals(listOf("live-1", "studio-2", "studio-3", "studio-4", null, null), session.decided())
        assertEquals(2, done.undecided)
        assertEquals(4, done.kept)
    }

    @Test
    fun `it is offered when more than three songs wait and the list stands still`() = runBlocking {
        assertFalse("with no import there is nothing to offer", LibraryImport.State().offersFirsts)
        val youTube = hasTwoOfEach(5)
        val fifth = youTube.holdSearch(at = 5)
        val session = session(youTube)
        session.open("mine.csv", noLengths(5))
        fifth.isReached()
        // Four wait, and the lookups are still bringing more: "all" would not be all of them.
        assertEquals(4, session.state.value.progress!!.undecided)
        assertFalse(session.state.value.offersFirsts)

        session.cancel()
        assertTrue("a stopped run adds nothing to its list", session.state.value.offersFirsts)
        // Three left to check: a tap each is no more work than a button.
        session.pick(0, studio(1))
        assertFalse(session.state.value.offersFirsts)
        session.undecide(0)
        assertTrue(session.state.value.offersFirsts)
        session.skip(3)
        assertFalse("a skipped song is not waiting", session.state.value.offersFirsts)
        session.undecide(3)

        session.resume()
        val waiting = session.reaches(Stage.MATCHED)
        assertEquals(5, waiting.progress!!.undecided)
        assertTrue(waiting.offersFirsts)
        // The same five in every stage: offered where a run waits for Continue or has every answer,
        // and not once Create playlists has been tapped, when a pick would come too late.
        for (stage in Stage.entries) {
            val offered = stage in setOf(Stage.STOPPED, Stage.OFFLINE, Stage.PAUSED, Stage.MATCHED)
            assertEquals(stage.name, offered, waiting.copy(stage = stage).offersFirsts)
        }

        session.pickFirsts()
        assertFalse("nothing is left to take", session.state.value.offersFirsts)
    }

    @Test
    fun `with no import, or nothing waiting, it does nothing`() = runBlocking {
        val session = session(has(3))
        session.pickFirsts()
        assertEquals(LibraryImport.State(), session.state.value)

        // Every song matched: there is no review list.
        session.open("mine.csv", csv(3))
        val matched = session.reaches(Stage.MATCHED)
        session.pickFirsts()
        assertEquals(matched, session.state.value)
        assertEquals(3, session.state.value.progress!!.kept)

        session.reset()
        session.pickFirsts()
        assertEquals(LibraryImport.State(), session.state.value)
    }

    // Start over, and another file.

    @Test
    fun `Start over forgets the file, and an answer that comes back late changes nothing`() = runBlocking {
        val youTube = has(4)
        val second = youTube.holdSearch(at = 2)
        val session = session(youTube)
        session.open("mine.csv", csv(4))
        second.isReached()

        session.reset()
        assertEquals(LibraryImport.State(), session.state.value)

        second.letGo.complete(Unit)
        delay(300)
        assertEquals(LibraryImport.State(), session.state.value)
        assertEquals(asked(1, 2), youTube.queries)
        // Nothing to carry on with, and nothing to create.
        session.resume()
        session.create()
        delay(100)
        assertEquals(LibraryImport.State(), session.state.value)
    }

    @Test
    fun `a file chosen while another is still being read takes its place`() = runBlocking {
        val session = session(has(2))
        val slow = CompletableDeferred<Unit>()
        session.open {
            slow.await()
            LibraryImport.Picked("first.csv", csv(2).toByteArray())
        }
        assertEquals(Stage.READING, session.state.value.stage)

        session.open("second.csv", csv(2))
        assertEquals("second.csv", session.reaches(Stage.MATCHED).fileName)

        slow.complete(Unit)
        delay(300)
        assertEquals("second.csv", session.state.value.fileName)
        assertEquals(Stage.MATCHED, session.state.value.stage)
    }

    // The app closed, or killed, and started again.

    @Test
    fun `an import is there again when the app is started again, with every pick`() = runBlocking {
        val before = session(hasMixed())
        before.open("everything.csv", mixed)
        before.reaches(Stage.MATCHED)
        before.pick(1, edit)
        before.saved()
        val left = before.state.value
        kill()

        val youTube = silent()
        val after = session(youTube)
        // Not the page that offers a file, not even for a moment.
        assertNotEquals(Stage.IDLE, after.state.value.stage)
        assertEquals(left, after.reaches(Stage.MATCHED))
        assertTrue("an import that comes back asks YouTube nothing", youTube.queries.isEmpty())

        after.create()
        assertEquals(3, after.reaches(Stage.CREATED).createdSongs)
        assertEquals(
            listOf("First" to listOf("one", "edit"), "Second" to listOf("one")),
            made.single().map { (name, songs) -> name to songs.map { it.id } },
        )
    }

    @Test
    fun `killed in the middle of the lookups, it comes back stopped and Continue looks up the rest`() = runBlocking {
        val first = has(6)
        val fourth = first.holdSearch(at = 4)
        val before = session(first)
        before.open("mine.csv", csv(6))
        fourth.isReached()
        before.saved()
        kill()

        val youTube = has(6)
        val after = session(youTube)
        val stopped = after.reaches(Stage.STOPPED)
        assertEquals("mine.csv", stopped.fileName)
        assertEquals(ExportFormat.CSV, stopped.format)
        assertEquals(1, stopped.playlists)
        assertEquals(3, stopped.progress!!.checked)
        assertEquals(6, stopped.progress!!.total)
        // Nothing is looked up because the app was opened. Somebody says Continue.
        delay(100)
        assertTrue(youTube.queries.isEmpty())

        after.resume()
        assertEquals(6, after.reaches(Stage.MATCHED).progress!!.matched)
        assertEquals(asked(4, 5, 6), youTube.queries)

        // And what Continue found is kept like the rest.
        after.saved()
        kill()
        assertEquals(6, session(silent()).reaches(Stage.MATCHED).progress!!.matched)
    }

    @Test
    fun `what had not been written yet when the app was killed is looked up again, and the file is not lost`() = runBlocking {
        val first = has(6)
        val fourth = first.holdSearch(at = 4)
        // An app killed before any of its changes had waited long enough to be written.
        val before = session(first, saveAfter = { awaitCancellation() })
        before.open("mine.csv", csv(6))
        fourth.isReached()
        before.written()
        kill()

        val youTube = has(6)
        val after = session(youTube)
        val stopped = after.reaches(Stage.STOPPED)
        assertEquals(6, stopped.progress!!.total)
        assertEquals(0, stopped.progress!!.checked)

        after.resume()
        assertEquals(6, after.reaches(Stage.MATCHED).progress!!.matched)
        assertEquals(asked(1, 2, 3, 4, 5, 6), youTube.queries)
    }

    @Test
    fun `a burst of picks is one write, and not one each`() = runBlocking {
        val store = ImportStore(dir)
        val wait = Channel<Unit>(Channel.UNLIMITED)
        val session = session(hasMixed(), store, saveAfter = { wait.receive() })
        session.open("everything.csv", mixed)
        session.reaches(Stage.MATCHED)
        // What the lookups found is written when they end, without waiting. The first of them
        // also left a save waiting, which is let go here, and finds nothing more to write.
        wait.send(Unit)
        session.saved()
        val written = store.written

        repeat(5) {
            session.pick(1, edit)
            session.pick(1, live)
            session.skip(0)
            session.undecide(0)
        }
        session.written()
        assertEquals("nothing is written while the changes are still coming", written, store.written)

        wait.send(Unit)
        session.saved()
        assertEquals("twenty changes, one file", written + 1, store.written)

        kill()
        val back = session(silent()).reaches(Stage.MATCHED).progress!!
        assertEquals("live", (back.review.single().choice as ImportRun.Choice.Picked).song.id)
        assertFalse(back.matchedItems.single().leftOut)
    }

    @Test
    fun `a pick is written at once when the screen goes out of sight`() = runBlocking {
        // Nothing would be written in time by itself: what is there after the kill, the screen asked for.
        val before = session(hasMixed(), saveAfter = { awaitCancellation() })
        before.open("everything.csv", mixed)
        before.reaches(Stage.MATCHED)
        before.pick(1, live)
        before.skip(0)

        before.keep()
        before.written()
        kill()

        val back = session(silent()).reaches(Stage.MATCHED).progress!!
        assertEquals("live", (back.review.single().choice as ImportRun.Choice.Picked).song.id)
        assertTrue(back.matchedItems.single().leftOut)

        // The screen asks as it stops being seen, and as it is left for another.
        val screen = File("src/main/java/com/dd3boh/outertune/ui/screens/settings/LibraryImportScreen.kt").readText()
        assertTrue("if (event == Lifecycle.Event.ON_STOP) session.keep()" in screen)
        assertTrue(Regex("""onDispose \{\s*lifecycleOwner\.lifecycle\.removeObserver\(observer\)\s*session\.keep\(\)""").containsMatchIn(screen))
    }

    @Test
    fun `the import under way stays on the phone, out of the cloud backup`() {
        // It is kept for the app being closed, not for another phone, and Android's cloud backup
        // takes the app's files up to 25 MB in all or takes nothing: an import of some megabytes
        // left half done must not be what costs somebody the backup of their library.
        val session = File("src/main/java/com/dd3boh/outertune/migration/LibraryImport.kt").readText()
        val folder = Regex("""private const val FOLDER = "([a-z-]+)"""").find(session)!!.groupValues[1]
        assertTrue("ImportStore(File(context.filesDir, FOLDER))" in session)
        val kept = Regex("""<exclude\s+domain="file"\s+path="$folder/"\s*/>""")

        // Android 12 and later: out of the cloud backup, and still carried from one phone to another.
        val rules = File("src/main/res/xml/data_extraction_rules.xml").readText()
        assertTrue("in the cloud backup", kept.containsMatchIn(rules.substringAfter("<cloud-backup>").substringBefore("</cloud-backup>")))
        assertFalse(kept.containsMatchIn(rules.substringAfter("<device-transfer>").substringBefore("</device-transfer>")))
        // Android 11 and earlier, where one set of rules is both.
        assertTrue(kept.containsMatchIn(File("src/main/res/xml/backup_rules.xml").readText()))
    }

    @Test
    fun `Cancel keeps the import, on the screen and on the disk`() = runBlocking {
        val first = has(6)
        val fourth = first.holdSearch(at = 4)
        // Nothing would be written in time by itself: what is there after the kill, Cancel wrote.
        val before = session(first, saveAfter = { awaitCancellation() })
        before.open("mine.csv", csv(6))
        fourth.isReached()
        before.cancel()
        before.written()
        kill()

        val after = session(silent()).reaches(Stage.STOPPED)
        assertEquals(3, after.progress!!.checked)
        assertEquals("mine.csv", after.fileName)
    }

    @Test
    fun `Start over forgets it on the disk too`() = runBlocking {
        val before = session(has(3))
        before.open("mine.csv", csv(3))
        before.reaches(Stage.MATCHED)
        before.saved()
        assertTrue(kept())

        before.reset()
        before.written()
        assertFalse(kept())
        kill()

        assertEquals(LibraryImport.State(), session(silent()).state.value)
    }

    @Test
    fun `Create playlists forgets it once the playlists are made, and not before`() = runBlocking {
        val before = session(has(3))
        before.open("mine.csv", csv(3))
        before.reaches(Stage.MATCHED)

        libraryFails = true
        before.create()
        withTimeout(10_000) { before.state.first { it.stage == Stage.MATCHED && askedToMake.get() == 1 } }
        before.written()
        assertTrue("an import whose playlists could not be made is still to do", kept())

        libraryFails = false
        before.create()
        before.reaches(Stage.CREATED)
        before.written()
        assertFalse(kept())
        kill()

        // Killed on the page that says Made 1 playlist, before Done: there is nothing to make twice.
        assertEquals(LibraryImport.State(), session(silent()).state.value)
    }

    @Test
    fun `another file takes the place of the one that was kept`() = runBlocking {
        val before = session(has(3))
        before.open("first.csv", csv(3))
        before.reaches(Stage.MATCHED)
        before.saved()

        before.open("second.csv", csv(2))
        withTimeout(10_000) { before.state.first { it.fileName == "second.csv" && it.stage == Stage.MATCHED } }
        before.saved()
        kill()

        val after = session(silent()).reaches(Stage.MATCHED)
        assertEquals("second.csv", after.fileName)
        assertEquals(2, after.progress!!.total)
    }

    @Test
    fun `a file that would not open leaves nothing kept of the one before it`() = runBlocking {
        val before = session(has(3))
        before.open("first.csv", csv(3))
        before.reaches(Stage.MATCHED)
        before.saved()

        before.open { LibraryImport.Picked("gone.csv", null) }
        before.reaches(Stage.FAILED)
        before.written()
        kill()

        // What the screen showed last is what comes back: no import.
        assertEquals(LibraryImport.State(), session(silent()).state.value)
    }

    @Test
    fun `an import that cannot be read back starts from the page that offers a file`() = runBlocking {
        val before = session(has(3))
        before.open("mine.csv", csv(3))
        before.reaches(Stage.MATCHED)
        before.saved()
        kill()
        File(dir, "import.json").writeText("{\"version\":1,\"tra")

        val after = session(silent())
        assertEquals(LibraryImport.State(), after.reaches(Stage.IDLE))
        after.written()
        assertFalse(kept())

        // And it still imports.
        after.open("mine.csv", csv(3))
        assertEquals(3, after.reaches(Stage.MATCHED).progress!!.total)
    }

    @Test
    fun `a file chosen while the kept import is still being read takes its place for good`() = runBlocking {
        val before = session(has(3))
        before.open("first.csv", csv(3))
        before.reaches(Stage.MATCHED)
        before.saved()
        kill()

        val after = session(has(2))
        after.open("second.csv", csv(2))
        withTimeout(10_000) { after.state.first { it.fileName == "second.csv" && it.stage == Stage.MATCHED } }
        after.saved()
        delay(100)
        assertEquals("second.csv", after.state.value.fileName)
        kill()

        assertEquals("second.csv", session(silent()).reaches(Stage.MATCHED).fileName)
    }
}
