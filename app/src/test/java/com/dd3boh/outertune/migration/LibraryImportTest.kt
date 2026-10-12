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
 * What the screen can ask for is all here: a file, Cancel and Continue, a pick and a skip, Create
 * playlists, Start over. So is what the screen cannot ask for and Android does anyway: the app
 * killed in the middle, and started again.
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
        matcher = { ImportMatcher(search = youTube::search, wait = { yield() }) },
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
