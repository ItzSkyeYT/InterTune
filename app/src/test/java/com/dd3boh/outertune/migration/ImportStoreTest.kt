/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.migration

import com.zionhuang.innertube.models.Album
import com.zionhuang.innertube.models.Artist
import com.zionhuang.innertube.models.SongItem
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * An import written down and read back, as it is when the app is closed over one and opened again.
 *
 * What has to come back is everything somebody would otherwise do twice: the songs of the file,
 * what each lookup came to, and every pick and leave-out. What has to hold is the file itself: a
 * phone that dies in mid-write, or a file that came back damaged, costs lookups at most.
 */
class ImportStoreTest {

    private val dir: File = Files.createTempDirectory("import-store").toFile()

    @After
    fun tidy() {
        dir.deleteRecursively()
    }

    private fun song(
        title: String,
        seconds: Int? = 200,
        id: String = title,
        artists: List<Artist> = listOf(Artist(name = "x", id = "UCx")),
        album: Album? = null,
        explicit: Boolean = false,
    ) = SongItem(id = id, title = title, artists = artists, album = album, duration = seconds, thumbnail = "https://lh3.example/$id=w60", explicit = explicit)

    private fun track(title: String, vararg artists: String = arrayOf("x"), seconds: Int? = 200) =
        ImportedTrack(title, artists.toList(), durationSeconds = seconds)

    /** [count] songs in one playlist. */
    private fun run(count: Int) = ImportRun(
        ImportParse.Parsed(ExportFormat.EXPORTIFY, listOf(ImportedPlaylist("Mine", (0 until count).map { track("Song $it") })), skippedRows = 0)
    )

    private fun matched(run: ImportRun, index: Int) =
        Outcome.Matched(score(run.tracks[index].wanted(), song(run.tracks[index].title, id = "found-$index")))

    /** A store on the same folder that has never seen the run: the app started again. */
    private fun reopened(block: Int = 4) = ImportStore(dir, block)

    private fun files() = dir.list().orEmpty().sorted()

    // What a file held.

    @Test
    fun `nothing is kept until an import begins, and then one is`() {
        val store = ImportStore(dir)
        assertFalse(store.holdsOne())
        assertNull(store.load())

        store.begin("mine.csv", ExportFormat.EXPORTIFY, 0, run(3))
        assertTrue(store.holdsOne())
        assertTrue(reopened().holdsOne())
    }

    @Test
    fun `what the file held comes back as it was read`() {
        val both = ImportedTrack("Get Lucky (feat. Pharrell Williams)", listOf("Daft Punk", "Pharrell Williams"), album = "Random Access Memories", durationSeconds = 369, isrc = "USQX91300108")
        val bare = ImportedTrack("Untitled", emptyList())
        val awkward = ImportedTrack("He said \"no\" \\ twice\n(and left) 🎵 夜に駆ける", listOf("Tyler, The Creator"), durationSeconds = 1)
        val parsed = ImportParse.Parsed(
            ExportFormat.TUNEMYMUSIC,
            listOf(
                ImportedPlaylist("Road trip", listOf(both, bare, awkward)),
                ImportedPlaylist("Empty of new songs", listOf(both)),
                ImportedPlaylist("Été 2024", listOf(awkward, both)),
            ),
            skippedRows = 2,
        )
        val run = ImportRun(parsed)
        ImportStore(dir).begin("my library.zip", ExportFormat.TUNEMYMUSIC, 2, run)

        val kept = reopened().load()!!
        assertEquals("my library.zip", kept.fileName)
        assertEquals(ExportFormat.TUNEMYMUSIC, kept.format)
        assertEquals(2, kept.skippedRows)
        assertEquals(0, kept.lostBlocks)
        // The songs once each, in the order the file first had them, and each playlist's places among them.
        assertEquals(listOf(both, bare, awkward), kept.run.tracks)
        assertEquals(run.playlists, kept.run.playlists)
        assertEquals(listOf("Road trip", "Empty of new songs", "Été 2024"), kept.run.playlists.map { it.first })
        assertEquals(listOf(listOf(0, 1, 2), listOf(0), listOf(2, 0)), kept.run.playlists.map { it.second })
        // Nothing was found yet, so nothing is.
        assertEquals(0, kept.run.snapshot().checked)
        assertFalse(kept.run.complete())
    }

    // What was found and decided.

    @Test
    fun `every answer and every decision comes back with it`() {
        val run = run(6)
        val store = ImportStore(dir, block = 4)
        store.begin("mine.csv", ExportFormat.EXPORTIFY, 0, run)

        val full = song(
            "Song 1 (Remastered 2011)", seconds = 201, id = "full",
            artists = listOf(Artist("x", "UCx"), Artist("Guest", null)),
            album = Album("The Album", "MPREb_1"), explicit = true,
        )
        val noLength = song("Song 2", seconds = null, id = "no-length")
        val live = song("Song 2 (Live)", seconds = 260, id = "live")
        run.record(0, matched(run, 0))
        run.record(1, Outcome.Matched(score(run.tracks[1].wanted(), full)))
        run.record(2, Outcome.Review(listOf(score(run.tracks[2].wanted(), noLength), score(run.tracks[2].wanted(), live))))
        run.record(3, Outcome.NotFound)
        run.record(4, Outcome.Review(listOf(score(run.tracks[4].wanted(), live))))
        // The sixth has no answer yet.
        run.pick(2, live)
        run.skip(4)
        run.skip(0)
        store.save(run)

        val back = reopened().load()!!.run
        assertEquals(run.snapshot(), back.snapshot())
        assertEquals(run.playlistsToCreate(), back.playlistsToCreate())
        assertEquals(listOf(true, true, true, true, true, false), (0 until 6).map(back::isDone))
        // Field by field for the one with everything, and the flag that keeps a name alone out.
        val best = back.snapshot().matchedItems.single { it.index == 1 }.best
        assertEquals(full, best.candidate)
        assertEquals(score(run.tracks[1].wanted(), full), best)
        val unsure = back.snapshot().review.first().candidates.first()
        assertNull(unsure.candidate.duration)
        assertFalse(unsure.lengthCompared)
    }

    @Test
    fun `a decision taken back is taken back on disk`() {
        val run = run(3)
        val store = ImportStore(dir)
        store.begin("mine.csv", ExportFormat.EXPORTIFY, 0, run)
        val candidate = song("Song 1 (Live)", seconds = 260, id = "live")
        run.record(0, matched(run, 0))
        run.record(1, Outcome.Review(listOf(score(run.tracks[1].wanted(), candidate))))
        run.pick(1, candidate)
        run.skip(0)
        store.save(run)
        assertEquals(1, reopened().load()!!.run.snapshot().picked)

        run.undecide(1)
        run.undecide(0)
        store.save(run)

        val back = reopened().load()!!.run.snapshot()
        assertEquals(0, back.picked)
        assertEquals(1, back.undecided)
        assertFalse(back.matchedItems.single().leftOut)
    }

    // Not on every tiny change, and never a whole library for one song.

    @Test
    fun `only the blocks that changed are written again`() {
        val run = run(10)
        val store = ImportStore(dir, block = 4)
        store.begin("mine.csv", ExportFormat.EXPORTIFY, 0, run)
        assertEquals("the file's songs, once", 1, store.written)
        assertEquals(listOf("import.json"), files())

        run.record(0, matched(run, 0))
        run.record(1, matched(run, 1))
        store.save(run)
        assertEquals(2, store.written)
        assertEquals(listOf("found-0.json", "import.json"), files())

        // Nothing has changed since, so nothing is written.
        store.save(run)
        assertEquals(2, store.written)

        // A song of the third block and a leave-out in the first: two blocks, and the songs of the file are left alone.
        run.record(9, matched(run, 9))
        run.skip(0)
        store.save(run)
        assertEquals(4, store.written)
        assertEquals(listOf("found-0.json", "found-2.json", "import.json"), files())
        assertFalse(store.owes(run))
    }

    @Test
    fun `a save for anything but the import that is kept writes nothing`() {
        val kept = run(3)
        val other = run(3)
        val store = ImportStore(dir)
        // Before anything began.
        other.record(0, matched(other, 0))
        store.save(other)
        assertTrue(files().isEmpty())

        store.begin("mine.csv", ExportFormat.EXPORTIFY, 0, kept)
        store.save(other)
        assertEquals(listOf("import.json"), files())
        assertFalse("the other run is not this store's to write", store.owes(other))
        // And what the other run had found is still its own to write somewhere, some day.
        assertTrue(other.hasChanged())

        // After Start over, or once the playlists are made.
        store.clear()
        kept.record(0, matched(kept, 0))
        store.save(kept)
        assertTrue(files().isEmpty())
    }

    // Written safely.

    @Test
    fun `a file is put in place whole, with nothing left beside it`() {
        val run = run(5)
        val store = ImportStore(dir, block = 2)
        store.begin("mine.csv", ExportFormat.EXPORTIFY, 0, run)
        (0 until 5).forEach { run.record(it, matched(run, it)) }
        store.save(run)
        assertEquals(listOf("found-0.json", "found-1.json", "found-2.json", "import.json"), files())
        assertTrue("nothing is written under the real name directly", files().none { it.endsWith(".tmp") })
    }

    @Test
    fun `what a phone that died in mid-write left behind is not taken for a file`() {
        val run = run(4)
        val store = ImportStore(dir, block = 2)
        store.begin("mine.csv", ExportFormat.EXPORTIFY, 0, run)
        run.record(0, matched(run, 0))
        store.save(run)
        // Half of the next write of each, never renamed into place.
        File(dir, "found-0.json.tmp").writeText("{\"import\":\"")
        File(dir, "found-1.json.tmp").writeText("{\"import\":\"")
        File(dir, "import.json.tmp").writeText("{\"version\":1,\"tra")

        val back = reopened(block = 2).load()!!
        assertEquals(4, back.run.tracks.size)
        assertEquals(1, back.run.snapshot().checked)
        assertEquals(0, back.lostBlocks)
        assertTrue(files().none { it.endsWith(".tmp") })
    }

    @Test
    fun `the first write cut short leaves nothing to come back to`() {
        dir.mkdirs()
        File(dir, "import.json.tmp").writeText("{\"version\":1,\"tra")
        val store = reopened()
        assertFalse(store.holdsOne())
        assertNull(store.load())
        assertTrue(files().isEmpty())
    }

    @Test
    fun `a block that cannot be read costs its lookups and nothing else`() {
        val run = run(6)
        val store = ImportStore(dir, block = 2)
        store.begin("mine.csv", ExportFormat.EXPORTIFY, 0, run)
        (0 until 6).forEach { run.record(it, matched(run, it)) }
        run.skip(5)
        store.save(run)
        File(dir, "found-1.json").writeText("not json at all")

        val back = reopened(block = 2).load()!!
        assertEquals(1, back.lostBlocks)
        assertEquals(6, back.run.tracks.size)
        assertEquals(listOf(true, true, false, false, true, true), (0 until 6).map(back.run::isDone))
        assertTrue(back.run.snapshot().matchedItems.single { it.index == 5 }.leftOut)
    }

    @Test
    fun `one answer that cannot be read is looked up again, and its block is kept`() {
        val run = run(3)
        val store = ImportStore(dir, block = 4)
        store.begin("mine.csv", ExportFormat.EXPORTIFY, 0, run)
        (0 until 3).forEach { run.record(it, matched(run, it)) }
        store.save(run)
        val block = File(dir, "found-0.json")
        // The second song's answer loses its id, as a build that wrote it another way would have left it.
        block.writeText(block.readText().replace("\"id\":\"found-1\",", ""))

        val back = reopened().load()!!
        assertEquals(0, back.lostBlocks)
        assertEquals(listOf(true, false, true), (0 until 3).map(back.run::isDone))
    }

    @Test
    fun `answers left behind by another import are not taken for this one's`() {
        val first = run(3)
        val store = ImportStore(dir)
        store.begin("first.csv", ExportFormat.EXPORTIFY, 0, first)
        first.record(0, matched(first, 0))
        store.save(first)
        val stale = File(dir, "found-0.json").readText()

        val second = run(3)
        store.begin("second.csv", ExportFormat.EXPORTIFY, 0, second)
        assertEquals(listOf("import.json"), files())
        File(dir, "found-0.json").writeText(stale)

        val back = reopened().load()!!
        assertEquals("second.csv", back.fileName)
        assertEquals(0, back.run.snapshot().checked)
        assertEquals("a stale block is not a damaged one", 0, back.lostBlocks)
    }

    @Test
    fun `songs that cannot be read back, or another build's way of writing them, leave nothing kept`() {
        val store = ImportStore(dir)
        store.begin("mine.csv", ExportFormat.EXPORTIFY, 0, run(3))
        val head = File(dir, "import.json")
        val good = head.readText()

        head.writeText(good.replace("\"version\":1", "\"version\":99"))
        assertNull(reopened().load())
        assertTrue("what cannot be read is not kept for the next start to trip over", files().isEmpty())

        dir.mkdirs()
        head.writeText(good.dropLast(40))
        assertNull(reopened().load())
        assertTrue(files().isEmpty())

        // A playlist that points past the end of the songs.
        dir.mkdirs()
        head.writeText(good.replace("\"tracks\":[0,1,2]", "\"tracks\":[0,1,7]"))
        assertNull(reopened().load())
    }

    @Test
    fun `an unknown format name reads as a plain CSV, the songs being what matters`() {
        val store = ImportStore(dir)
        store.begin("mine.csv", ExportFormat.SOUNDIIZ, 0, run(2))
        val head = File(dir, "import.json")
        head.writeText(head.readText().replace("\"SOUNDIIZ\"", "\"SOME_LATER_TOOL\""))

        val back = reopened().load()
        assertNotNull(back)
        assertEquals(ExportFormat.CSV, back!!.format)
    }

    // Forgetting.

    @Test
    fun `clear leaves nothing, and a new import takes the place of the old one whole`() {
        val first = run(9)
        val store = ImportStore(dir, block = 2)
        store.begin("first.csv", ExportFormat.EXPORTIFY, 0, first)
        (0 until 9).forEach { first.record(it, matched(first, it)) }
        store.save(first)
        assertEquals(6, files().size)

        val second = run(2)
        store.begin("second.csv", ExportFormat.CSV, 1, second)
        assertEquals(listOf("import.json"), files())
        assertEquals(2, reopened().load()!!.run.tracks.size)

        store.clear()
        assertFalse(store.holdsOne())
        assertTrue(files().isEmpty())
        assertNull(reopened().load())
    }

    @Test
    fun `an import that is over is forgotten, and never another one begun since`() {
        val first = run(2)
        val store = ImportStore(dir)
        store.begin("first.csv", ExportFormat.EXPORTIFY, 0, first)
        val second = run(3)
        store.begin("second.csv", ExportFormat.EXPORTIFY, 0, second)

        // The first one's playlists, made late.
        store.end(first)
        assertEquals("second.csv", reopened().load()!!.fileName)

        store.end(second)
        assertTrue(files().isEmpty())
    }

    @Test
    fun `an import read back goes on being kept where it was`() {
        val run = run(4)
        ImportStore(dir, block = 2).begin("mine.csv", ExportFormat.EXPORTIFY, 0, run)

        val store = reopened(block = 2)
        val back = store.load()!!.run
        back.record(3, matched(back, 3))
        assertTrue(store.owes(back))
        store.save(back)

        assertEquals(listOf(false, false, false, true), (0 until 4).map(reopened(block = 2).load()!!.run::isDone))
    }
}
