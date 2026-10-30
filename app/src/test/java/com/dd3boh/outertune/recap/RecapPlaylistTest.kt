/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.recap

import com.dd3boh.outertune.db.entities.Playlist
import com.dd3boh.outertune.db.entities.PlaylistEntity
import com.dd3boh.outertune.db.entities.PlaylistSongMap
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.time.LocalDateTime

/**
 * "Save as a playlist" on the Recap's year card: the list it saves, its name, saving again, and
 * the second, separate choice of putting it on YouTube Music. Made-up songs and a made-up database
 * and YouTube Music; nothing here touches Room or the network.
 */
class RecapPlaylistTest {
    private val now = LocalDateTime.of(2026, 12, 2, 12, 0)
    private val sources = File("src/main/java/com/dd3boh/outertune")

    private fun ids(n: Int, tag: String = "s") = (1..n).map { "$tag$it" }

    /** A database holding [known] songs (id to whether it is a file on the phone) and the playlists written into it. */
    private class FakeStore(val known: Map<String, Boolean>) : RecapPlaylist.Store {
        val playlists = LinkedHashMap<String, PlaylistEntity>()
        val maps = ArrayList<PlaylistSongMap>()
        var transactions = 0

        override fun <T> atomically(block: () -> T): T {
            transactions++
            return block()
        }

        override fun exists(playlistId: String) = playlistId in playlists

        override fun songs(ids: List<String>) = ids.filter { it in known }.map { RecapPlaylist.SongRow(it, known.getValue(it)) }

        override fun create(playlist: PlaylistEntity) {
            check(playlist.id !in playlists) { "${playlist.id} written twice" }
            playlists[playlist.id] = playlist
        }

        override fun clear(playlistId: String) {
            maps.removeAll { it.playlistId == playlistId }
        }

        override fun setCoverIfNone(playlistId: String, cover: String) {
            playlists[playlistId]?.let { if (it.thumbnailUrl == null) playlists[playlistId] = it.copy(thumbnailUrl = cover) }
        }

        override fun add(maps: List<PlaylistSongMap>) {
            this.maps += maps
        }

        fun songsOf(playlistId: String) = maps.filter { it.playlistId == playlistId }.sortedBy { it.position }.map { it.songId }
    }

    private fun store(songs: List<String>, local: Set<String> = emptySet()) = FakeStore(songs.associateWith { it in local })

    // ---- the list

    @Test
    fun `the list is the ranking, the most played first, fifty songs at most`() {
        assertEquals(50, RecapPlaylist.SONGS)
        assertEquals(Recaps.PLAYLIST, RecapPlaylist.SONGS)
        val ranking = ids(80)
        assertEquals(ids(50), RecapPlaylist.songsOf(ranking))
        assertEquals(ids(7), RecapPlaylist.songsOf(ids(7)))
        // A song is in a playlist once, at the place it ranks.
        assertEquals(listOf("a", "b", "c"), RecapPlaylist.songsOf(listOf("a", "b", "a", "c", "b")))
    }

    @Test
    fun `saving writes the songs in the order of the ranking, from position 0`() {
        val db = store(ids(60))
        val saved = RecapPlaylist.save(db, 2026, "Your 2026 on InterTune", ids(60), now)
        assertNotNull(saved)
        assertEquals(ids(50), db.songsOf(saved!!.id))
        assertEquals((0 until 50).toList(), db.maps.map { it.position })
        assertEquals(ids(50), saved.songs.map { it.id })
        assertTrue(saved.created)
        // In one transaction.
        assertEquals(1, db.transactions)
    }

    @Test
    fun `the playlist is local, on the phone, and has the name it was given`() {
        val db = store(ids(10))
        val saved = RecapPlaylist.save(db, 2026, "Your 2026 on InterTune", ids(10), now)!!
        val playlist = db.playlists.getValue(saved.id)
        assertEquals("Your 2026 on InterTune", playlist.name)
        assertTrue(playlist.isLocal)
        assertTrue(playlist.isEditable)
        assertNull(playlist.browseId)
        assertEquals(now, playlist.bookmarkedAt)
    }

    // ---- the name and the year

    @Test
    fun `its name is the owner's words with the Recap's year in them, not a year written in`() {
        val strings = File("src/main/res/values/strings-ot.xml").readText()
        val template = Regex("""<string name="recap_playlist_name"[^>]*>([^<]*)</string>""").find(strings)?.groupValues?.get(1)
        assertEquals("Your %1\$s on InterTune", template)
        assertEquals("Your 2026 on InterTune", template!!.format("2026"))
        assertEquals("Your 2031 on InterTune", template.format("2031"))
        // The screen hands the string the Recap's own year, and the rules name no year.
        val story = File(sources, "ui/screens/recap/RecapScreen.kt").readText()
        assertTrue("stringResource(R.string.recap_playlist_name, recap.year.toString())" in story)
        val rules = File(sources, "recap/RecapPlaylist.kt").readText()
        assertFalse("a year written into the rules", Regex("""\b20[2-9]\d\b""").containsMatchIn(rules.replace(Regex("""/\*.*?\*/""", RegexOption.DOT_MATCHES_ALL), "").lines().joinToString("\n") { it.substringBefore("//") }))
    }

    @Test
    fun `each year has its own playlist, found again by its id and not by its name`() {
        assertEquals("LP_RECAP_2026", RecapPlaylist.idOf(2026))
        assertEquals("LP_RECAP_2027", RecapPlaylist.idOf(2027))
        val db = store(ids(10))
        val first = RecapPlaylist.save(db, 2026, "Your 2026 on InterTune", ids(10), now)!!
        val next = RecapPlaylist.save(db, 2027, "Your 2027 on InterTune", ids(10), now)!!
        assertEquals(2, db.playlists.size)
        assertEquals(setOf(first.id, next.id), db.playlists.keys)
    }

    // ---- saving again

    @Test
    fun `saving again updates the playlist instead of making a second one`() {
        val db = store(ids(60))
        val first = RecapPlaylist.save(db, 2026, "Your 2026 on InterTune", ids(60), now)!!
        assertTrue(first.created)
        // More listening later in the month: a different order, a different song in.
        val later = listOf("s7") + ids(60).filter { it != "s7" }
        val second = RecapPlaylist.save(db, 2026, "Your 2026 on InterTune", later, now.plusDays(9))!!
        assertFalse(second.created)
        assertEquals(first.id, second.id)
        assertEquals(1, db.playlists.size)
        assertEquals(later.take(50), db.songsOf(second.id))
        // Not the old songs kept and the new ones added after them.
        assertEquals(50, db.maps.size)
        assertEquals((0 until 50).toList(), db.maps.map { it.position })
    }

    @Test
    fun `an update leaves the name and the date the listener's playlist has`() {
        val db = store(ids(10))
        val first = RecapPlaylist.save(db, 2026, "Your 2026 on InterTune", ids(10), now)!!
        // Renamed by hand since.
        db.playlists[first.id] = db.playlists.getValue(first.id).copy(name = "My year")
        RecapPlaylist.save(db, 2026, "Your 2026 on InterTune", ids(10).reversed(), now.plusDays(3))
        val playlist = db.playlists.getValue(first.id)
        assertEquals("My year", playlist.name)
        assertEquals(now, playlist.bookmarkedAt)
        assertEquals(ids(10).reversed(), db.songsOf(first.id))
    }

    @Test
    fun `a playlist of another year is left as it was`() {
        val db = store(ids(10))
        val old = RecapPlaylist.save(db, 2025, "Your 2025 on InterTune", ids(5), now.minusYears(1))!!
        RecapPlaylist.save(db, 2026, "Your 2026 on InterTune", ids(10).reversed(), now)
        assertEquals(ids(5), db.songsOf(old.id))
    }

    // ---- its own cover

    @Test
    fun `a new playlist is made with the cover it is given, and none without one`() {
        val db = store(ids(10))
        val saved = RecapPlaylist.save(db, 2026, "Your 2026 on InterTune", ids(10), now, cover = "file:///files/recap/recap-playlist-2026.jpg")!!
        assertEquals("file:///files/recap/recap-playlist-2026.jpg", db.playlists.getValue(saved.id).thumbnailUrl)
        // Where the cover could not be drawn the playlist has none, and the app shows its first song's.
        val bare = store(ids(10))
        val without = RecapPlaylist.save(bare, 2026, "Your 2026 on InterTune", ids(10), now, cover = null)!!
        assertNull(bare.playlists.getValue(without.id).thumbnailUrl)
    }

    @Test
    fun `saving again gives a playlist with no cover its cover, and leaves one it has`() {
        val db = store(ids(10))
        val first = RecapPlaylist.save(db, 2026, "Your 2026 on InterTune", ids(10), now, cover = null)!!
        assertNull(db.playlists.getValue(first.id).thumbnailUrl)
        RecapPlaylist.save(db, 2026, "Your 2026 on InterTune", ids(10), now.plusDays(1), cover = "file:///a.jpg")
        assertEquals("file:///a.jpg", db.playlists.getValue(first.id).thumbnailUrl)
        // A picture the playlist has is never replaced.
        RecapPlaylist.save(db, 2026, "Your 2026 on InterTune", ids(10), now.plusDays(2), cover = "file:///b.jpg")
        assertEquals("file:///a.jpg", db.playlists.getValue(first.id).thumbnailUrl)
    }

    @Test
    fun `the cover is one file for each year, drawn square and kept in the app's files, not its cache`() {
        assertEquals("recap-playlist-2026.jpg", RecapPlaylist.coverName(2026))
        assertEquals("recap-playlist-2027.jpg", RecapPlaylist.coverName(2027))
        val cards = File(sources, "ui/screens/recap/RecapCards.kt").readText()
            .replace(Regex("""/\*.*?\*/""", RegexOption.DOT_MATCHES_ALL), "").lines().joinToString("\n") { it.substringBefore("//") }
        val draw = cards.substringAfter("fun cover(").substringBefore("\n    }\n")
        // Square, in the year card's own paper and shapes, with the year big.
        assertTrue("createBitmap(COVER_PX, COVER_PX)" in draw)
        assertTrue("paper(canvas, RecapInk.Blue)" in draw)
        assertTrue("Paper.BURST" in draw && "year.toString()" in draw)
        // Kept: in filesDir, which the system never empties, and never in the cache the cards go through.
        val keep = cards.substringAfter("fun saveCover(").substringBefore("\n    }\n")
        assertTrue("context.filesDir" in keep)
        assertFalse("cacheDir" in keep)
        assertTrue("RecapPlaylist.coverName(year)" in keep)
        // And the playlist is handed it by the one place that saves.
        val model = File(sources, "viewmodels/RecapViewModel.kt").readText()
        assertTrue("cover = RecapCards.saveCover(context, shown.recap.year)" in model)
    }

    // ---- songs the database does not have

    @Test
    fun `a song the database no longer holds is left out, the others keep their order`() {
        val db = store(ids(10).filterNot { it == "s3" || it == "s9" })
        val saved = RecapPlaylist.save(db, 2026, "Your 2026 on InterTune", ids(10), now)!!
        assertEquals(listOf("s1", "s2", "s4", "s5", "s6", "s7", "s8", "s10"), db.songsOf(saved.id))
        assertEquals((0 until 8).toList(), db.maps.map { it.position })
    }

    @Test
    fun `with none of the songs there nothing is written`() {
        val db = store(emptyList())
        assertNull(RecapPlaylist.save(db, 2026, "Your 2026 on InterTune", ids(10), now))
        assertTrue(db.playlists.isEmpty() && db.maps.isEmpty())
        assertNull(RecapPlaylist.save(store(ids(3)), 2026, "Your 2026 on InterTune", emptyList(), now))
    }

    // ---- opening it

    @Test
    fun `Open goes where the library opens a playlist of the phone`() {
        val db = store(ids(10))
        val saved = RecapPlaylist.save(db, 2026, "Your 2026 on InterTune", ids(10), now)!!
        val library = Playlist(db.playlists.getValue(saved.id), songCount = 10, downloadCount = 0, songThumbnails = emptyList())
        assertEquals("local_playlist/LP_RECAP_2026", library.navigationRoute)
        // MainActivity opens the id the card hands it by that same route, once the Recap has closed (RecapSoundRulesTest).
        val main = File(sources, "MainActivity.kt").readText()
        assertTrue("onOpenPlaylist = { id -> navController.navigate(\"local_playlist/\$id\") }" in main)
    }

    @Test
    fun `the story waits while the playlist is being saved or copied, and the copy goes on if the Recap is closed`() {
        val story = File(sources, "ui/screens/recap/RecapScreen.kt").readText()
        assertTrue("held || stopped || away || sharing || playlist.busy" in story)
        // Not on the window's scope: closing the Recap would leave half a playlist in the account.
        val model = File(sources, "viewmodels/RecapViewModel.kt").readText()
        assertTrue("viewModelScope.launch(Dispatchers.IO) {\n            val result = try {\n                RecapPlaylist.putOnYouTubeMusic(" in model)
    }

    // ---- the second, separate choice: YouTube Music

    /** YouTube Music as the tests make it: what it was asked, and which songs it refuses. */
    private class FakeRemote(val refuse: Set<String> = emptySet(), val createFails: Boolean = false) : RecapPlaylist.Remote {
        val created = ArrayList<String>()
        val added = ArrayList<Pair<String, String>>()
        val tried = ArrayList<String>()
        val deleted = ArrayList<String>()

        override suspend fun create(name: String): Result<String> {
            if (createFails) return Result.failure(IllegalStateException("offline"))
            created += name
            return Result.success("PL${created.size}")
        }

        override suspend fun add(playlistId: String, songId: String): Result<Unit> {
            tried += songId
            if (songId in refuse) return Result.failure(IllegalStateException("refused"))
            added += playlistId to songId
            return Result.success(Unit)
        }

        override suspend fun delete(playlistId: String): Result<Unit> {
            deleted += playlistId
            return Result.success(Unit)
        }
    }

    private fun rows(n: Int, local: Set<String> = emptySet()) = ids(n).map { RecapPlaylist.SongRow(it, it in local) }

    private fun push(remote: RecapPlaylist.Remote, songs: List<RecapPlaylist.SongRow>, progress: (Int, Int) -> Unit = { _, _ -> }) =
        runBlocking { RecapPlaylist.push(remote, "Your 2026 on InterTune", songs, progress) }

    @Test
    fun `it makes one playlist with the same name and puts the songs in it, in order`() {
        val remote = FakeRemote()
        val result = push(remote, rows(50))
        assertEquals(listOf("Your 2026 on InterTune"), remote.created)
        assertEquals(ids(50), remote.added.map { it.second })
        assertTrue(remote.added.all { it.first == "PL1" })
        assertEquals(RecapPlaylist.Pushed.Done(50, 50), result)
        assertTrue(remote.deleted.isEmpty())
    }

    @Test
    fun `files on the phone stay out of it, and the answer counts them against the list`() {
        val remote = FakeRemote()
        val result = push(remote, rows(10, local = setOf("s2", "s5")))
        assertEquals(listOf("s1", "s3", "s4", "s6", "s7", "s8", "s9", "s10"), remote.added.map { it.second })
        assertEquals(RecapPlaylist.Pushed.Done(8, 10), result)
    }

    @Test
    fun `nothing is made when every song is a file on the phone`() {
        val remote = FakeRemote()
        assertEquals(RecapPlaylist.Pushed.Failed, push(remote, rows(3, local = ids(3).toSet())))
        assertTrue(remote.created.isEmpty())
    }

    @Test
    fun `when the playlist cannot be made nothing is added`() {
        val remote = FakeRemote(createFails = true)
        assertEquals(RecapPlaylist.Pushed.Failed, push(remote, rows(5)))
        assertTrue(remote.tried.isEmpty())
    }

    @Test
    fun `a song YouTube Music refuses is passed over and the rest go on`() {
        val remote = FakeRemote(refuse = setOf("s4"))
        val result = push(remote, rows(8))
        assertEquals(ids(8).filter { it != "s4" }, remote.added.map { it.second })
        assertEquals(RecapPlaylist.Pushed.Done(7, 8), result)
    }

    @Test
    fun `after three refusals in a row it stops asking, and a playlist left empty is taken away again`() {
        val remote = FakeRemote(refuse = ids(50).toSet())
        assertEquals(RecapPlaylist.Pushed.Failed, push(remote, rows(50)))
        assertEquals(RecapPlaylist.GIVE_UP_AFTER, remote.tried.size)
        assertEquals(3, RecapPlaylist.GIVE_UP_AFTER)
        assertEquals(listOf("PL1"), remote.deleted)
    }

    @Test
    fun `refusals that do not come in a row do not stop it`() {
        val remote = FakeRemote(refuse = setOf("s2", "s3", "s5", "s6"))
        val result = push(remote, rows(8))
        assertEquals(listOf("s1", "s4", "s7", "s8"), remote.added.map { it.second })
        assertEquals(RecapPlaylist.Pushed.Done(4, 8), result)
        assertTrue(remote.deleted.isEmpty())
    }

    @Test
    fun `it says how far it has got after each song`() {
        val seen = ArrayList<Pair<Int, Int>>()
        push(FakeRemote(), rows(4)) { done, of -> seen += done to of }
        assertEquals(listOf(0 to 4, 1 to 4, 2 to 4, 3 to 4, 4 to 4), seen)
    }

    // ---- where it may happen

    @Test
    fun `YouTube Music is reached from this one file, once for each call, behind the account rule`() {
        val all = listOf("recap", "ui/screens/recap").flatMap { File(sources, it).listFiles()?.toList().orEmpty() }.filter { it.extension == "kt" }
        fun code(file: File) = file.readText().replace(Regex("""/\*.*?\*/""", RegexOption.DOT_MATCHES_ALL), "").lines().joinToString("\n") { it.substringBefore("//") }
        for (call in listOf("YouTube.createPlaylist(", "YouTube.addToPlaylist(", "YouTube.deletePlaylist(")) {
            val where = all.filter { call in code(it) }.map { it.name }
            assertEquals("$call", listOf("RecapPlaylist.kt"), where)
            assertEquals(1, Regex(Regex.escape(call)).findAll(code(File(sources, "recap/RecapPlaylist.kt"))).count())
        }
        // Not without a signed-in account that sync lets the app change: the rule every other push uses.
        val text = code(File(sources, "recap/RecapPlaylist.kt"))
        val gate = text.indexOf("if (!context.mayPushToYouTube()) return Pushed.Failed")
        assertTrue("the push does not ask whether the account may be changed", gate >= 0)
        assertTrue(gate < text.indexOf("push(YouTubeMusic"))
    }
}
