/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.together

import com.dd3boh.outertune.models.MediaMetadata
import com.dd3boh.outertune.playback.StandIn
import com.zionhuang.innertube.models.Artist
import com.zionhuang.innertube.models.SongItem
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.io.IOException
import kotlin.coroutines.EmptyCoroutineContext

/**
 * A song from a file on the phone in control broke the room for everyone else (#testers, 22 Nov 2026): Niko played his
 * file of AFI's Totalimmortal, the others were sent its id in the app, which played nowhere, and nobody was told why.
 * The others now hear the same recording from YouTube Music, found by the file's tags; a file with no tags, or no match,
 * goes as a made-up id that every phone knows it can't play, and they say so. Nothing of the file itself leaves the phone
 * (the reason of 18 Nov 2026: an untagged file's name was its title, and every guest saw it).
 */
class PhoneSongsTest {
    private val t0 = 1_791_000_000_000L
    private var now = t0
    private val searches = mutableListOf<String>()
    private var results: (String) -> List<SongItem> = { emptyList() }
    private var mayAsk = true
    private val answers = Channel<String>(Channel.UNLIMITED)

    private val folder = "/storage/emulated/0/Music/Mel Boro/Daft Punk"
    private val path = "$folder/05 Instant Crush.flac"

    private fun file(
        title: String = "Instant Crush",
        artist: String = "Daft Punk",
        seconds: Int = 337,
        localPath: String? = path,
        id: String = "LSa1b2c3d4",
    ) = MediaMetadata(
        id = id,
        title = title,
        artists = listOf(MediaMetadata.Artist(id = "LA0000001", name = artist, isLocal = true)),
        duration = seconds,
        thumbnailUrl = localPath,
        album = MediaMetadata.Album(id = "LB0000001", title = "Random Access Memories", isLocal = true),
        genre = null,
        isLocal = true,
        localPath = localPath,
    )

    private fun result(id: String, title: String = "Instant Crush", artist: String = "Daft Punk", seconds: Int? = 338, explicit: Boolean = false) =
        SongItem(
            id = id, title = title, artists = listOf(Artist(name = artist, id = null)), album = null, duration = seconds,
            thumbnail = "https://lh3.googleusercontent.com/$id=w60-h60-l90-rj", explicit = explicit,
        )

    private fun CoroutineScope.phoneSongs() = PhoneSongs(
        scope = this,
        search = { query ->
            searches += query
            results(query)
        },
        answered = { id, _ -> answers.trySend(id) },
        mayAsk = { mayAsk },
        spacingMs = 0,
        searchOn = EmptyCoroutineContext,
        now = { now },
    )

    private suspend fun answered(): String = withTimeout(5_000) { answers.receive() }

    @Test
    fun `an untagged file goes as a made-up id, and its name is never searched for nor sent`() = runBlocking {
        val songs = phoneSongs()
        // Untagged, the scanners title a file by its name, with or without its extension; an unknown artist is "<unknown>".
        val recording = "$folder/Recording 2026-11-18 call with Jane.m4a"
        val untagged = listOf(
            file(title = "Recording 2026-11-18 call with Jane", artist = "<unknown>", localPath = recording, id = "LSuntagged"),
            file(title = "Recording 2026-11-18 call with Jane.m4a", artist = "Mel", localPath = recording, id = "LSextension"),
            file(title = "Recording 2026-11-18 call with Jane", artist = "Mel", localPath = recording, id = "LSnameonly"),
            file(title = "Instant Crush", artist = "<unknown>", id = "LSnoartist"),
            file(title = "Instant Crush", artist = " ", id = "LSblankart"),
            file(title = "Instant Crush", localPath = null, id = "LSnopathxx"),
            file(title = " ", id = "LSnotitlex"),
        )
        for (song in untagged) {
            assertNull(song.title, tagsOf(song))
            val answer = songs.forRoom(song.id, song, lookUp = true) as PhoneSongs.OnlyHere
            assertEquals("", answer.title)
            assertEquals("", answer.artist)
            // A phone's own song in shape, which every phone in a room knows it can't play, and not this song's id.
            assertTrue(fromThisPhone(null, answer.id))
            assertNotEquals(song.id, answer.id)
        }
        assertEquals(emptyList<String>(), searches)
    }

    @Test
    fun `a tagged file is heard by the others as the same recording on YouTube Music`() = runBlocking {
        results = { listOf(result("cover00001", title = "Instant Crush (Cover)", artist = "Some Band"), result("dpInstant1")) }
        val songs = phoneSongs()
        val song = file()
        // Looked for once, by its title and first artist; nothing goes to the room meanwhile.
        assertNull(songs.forRoom(song.id, song, lookUp = true))
        assertNull(songs.forRoom(song.id, song, lookUp = true))
        assertEquals(song.id, answered())
        assertEquals(listOf("Instant Crush Daft Punk"), searches)
        val heard = songs.forRoom(song.id, song, lookUp = true) as PhoneSongs.Heard
        assertEquals("dpInstant1", heard.id)
        assertEquals("Instant Crush", heard.title)
        assertEquals("Daft Punk", heard.artist)
        // Its cover from YouTube's own picture servers, at the size the app keeps.
        assertTrue(heard.song.thumbnail, heard.song.thumbnail.startsWith("https://lh3.googleusercontent.com/dpInstant1=w544-h544"))
        assertEquals(listOf("Instant Crush Daft Punk"), searches)
        // What goes to the room has nothing of the file.
        val sent = TogetherMessage.encode(TogetherMessage.Queue(listOf(heard.song), repeat = 0, shuffle = false)) +
            TogetherMessage.encode(TogetherMessage.State(heard.id, heard.title, heard.artist, 1_000, true, 1.0, t0))
        for (mine in listOf(song.id, "/storage", "Mel Boro", "05 Instant Crush", ".flac")) assertFalse(mine, mine in sent)
    }

    @Test
    fun `a file with no match goes as a made-up id with its tags' name, the same one all along`() = runBlocking {
        // The same name, but seven minutes long: another version, not this recording.
        results = { listOf(result("extended01", seconds = 420)) }
        val songs = phoneSongs()
        val song = file()
        assertNull(songs.forRoom(song.id, song, lookUp = true))
        answered()
        val answer = songs.forRoom(song.id, song, lookUp = true) as PhoneSongs.OnlyHere
        assertTrue(fromThisPhone(null, answer.id))
        assertNotEquals(song.id, answer.id)
        assertEquals("Instant Crush", answer.title)
        assertEquals("Daft Punk", answer.artist)
        assertEquals(answer, songs.forRoom(song.id, song, lookUp = true))
        assertEquals(1, searches.size)
        // Another song gets another id.
        val other = file(title = "Untagged", id = "LSz9y8x7w6", localPath = "$folder/Untagged.mp3")
        assertNotEquals(answer.id, songs.forRoom(other.id, other, lookUp = true)!!.id)
    }

    @Test
    fun `a clean and an explicit take both passing, the others hear the explicit one`() = runBlocking {
        results = { listOf(result("clean00001", explicit = false), result("explicit01", explicit = true)) }
        val songs = phoneSongs()
        val song = file()
        songs.forRoom(song.id, song, lookUp = true)
        answered()
        assertEquals("explicit01", songs.forRoom(song.id, song, lookUp = true)!!.id)
    }

    @Test
    fun `a search that fails is no answer, and nothing more is asked for a while`() = runBlocking {
        results = { throw IOException("no network") }
        val songs = phoneSongs()
        val song = file()
        assertNull(songs.forRoom(song.id, song, lookUp = true))
        answered()
        // Meanwhile the others are told they can't hear it, and the song is not taken for one YouTube doesn't have.
        val meanwhile = songs.forRoom(song.id, song, lookUp = true) as PhoneSongs.OnlyHere
        assertEquals("Instant Crush", meanwhile.title)
        assertEquals(1, searches.size)
        now += StandIn.SEARCH_REST_MS
        results = { listOf(result("dpInstant1")) }
        assertNull(songs.forRoom(song.id, song, lookUp = true))
        answered()
        assertEquals("dpInstant1", songs.forRoom(song.id, song, lookUp = true)!!.id)
        assertEquals(2, searches.size)
    }

    @Test
    fun `nothing is asked while YouTube refuses this network, nor for a song not asked for`() = runBlocking {
        results = { listOf(result("dpInstant1")) }
        val songs = phoneSongs()
        val song = file()
        assertNull("a song further on in the queue is left out until it is looked for", songs.forRoom(song.id, song, lookUp = false))
        mayAsk = false
        assertTrue(songs.forRoom(song.id, song, lookUp = true) is PhoneSongs.OnlyHere)
        assertEquals(emptyList<String>(), searches)
        mayAsk = true
        assertNull(songs.forRoom(song.id, song, lookUp = true))
        answered()
        assertEquals("dpInstant1", songs.forRoom(song.id, song, lookUp = true)!!.id)
    }

    @Test
    fun `a file with no length is not searched for, since nothing could pass for the same recording`() = runBlocking {
        val songs = phoneSongs()
        val song = file(seconds = 0)
        val answer = songs.forRoom(song.id, song, lookUp = true) as PhoneSongs.OnlyHere
        assertEquals("Instant Crush", answer.title)
        assertEquals(emptyList<String>(), searches)
    }

    private val session = File("src/main/java/com/dd3boh/outertune/together/TogetherSession.kt").readText()
    private val sheet = File("src/main/java/com/dd3boh/outertune/ui/dialog/TogetherDialog.kt").readText()

    @Test
    fun `the one in control sends a song from a file as the others can hear it, and only once that is known`() {
        val state = session.substringAfter("private fun sendState()").substringBefore("\n    }\n")
        assertTrue("phoneSongs.forRoom(item.mediaId, item.metadata, lookUp = true) ?: return" in state)
        assertTrue("songId = fromFile?.id ?: item.mediaId" in state)
        assertTrue("title = fromFile?.title ?: metadata.title" in state)
        // Nothing is said of the song before it is known how the others hear it.
        assertTrue(state.indexOf("lookUp = true) ?: return") in 0 until state.indexOf("lastSentState = "))
    }

    @Test
    fun `a follower stops for a song it can't play, says why, and never asks for it`() {
        val follow = session.substringAfter("private fun follow()").substringBefore("private fun toLeadersSong(")
        val hold = follow.indexOf("if (fromThisPhone(null, state.songId)) {")
        assertTrue(hold > follow.indexOf("if (ownWay || pausedHere) return"))
        assertTrue(hold < follow.indexOf("toLeadersSong(player, state, now)"))
        assertTrue("holdForPhoneSong(player, state)\n            return" in follow.substring(hold))
        // Its pause is the room's doing, not a pause of the listener's, which would leave it paused here afterwards.
        val held = session.substringAfter("private fun holdForPhoneSong(").substringBefore("\n    }\n")
        assertTrue(held.indexOf("mine()") in 0 until held.indexOf("player.playWhenReady = false"))
        val played = session.substringAfter("private fun listenerPlayed(").substringBefore("\n    }\n")
        assertTrue(played.indexOf("if (fromThisPhone(null, room.songId)) return") in 0 until played.indexOf("PauseHere.after("))
        // Never shown by its made-up id.
        assertTrue("message.title.ifBlank { if (onlyThere) \"\" else message.songId }" in session)
        // The sheet says it, by the name of whoever is in control, and the one in control learns why too.
        assertTrue("!status.leading && status.songOnlyThere && !status.waitingForHost -> SongOnlyThere(hostSong, status.leaderName)" in sheet)
        assertTrue("stringResource(R.string.together_song_only_here)" in sheet)
    }

    @Test
    fun `the room's sheet draws a song's cover as the player does, a file's own picture included`() {
        // Niko's own song showed an empty square: the sheet gave the file's path to the picture loader.
        assertTrue("SongRow(song.getThumbnailModel(coverPx, coverPx), song.title" in sheet)
        assertTrue("cover = song?.getThumbnailModel(coverPx, coverPx)," in sheet)
        assertFalse("song.thumbnailUrl" in sheet || "song?.thumbnailUrl" in sheet)
    }

    /** His word of 22 Nov 2026: "a little note or something saying this is a version from YTM and there's a chance it isnt what the other person is hosting". */
    @Test
    fun `a guest hearing YouTube Music's version of a file is told it may not be exactly the same`() {
        val state = TogetherMessage.State("dpInstant1", "Instant Crush", "Daft Punk", 1_000, true, 1.0, t0, fromFile = true)
        assertTrue((TogetherMessage.decode(TogetherMessage.encode(state)) as TogetherMessage.State).fromFile)
        // Only for a match: a song on YouTube Music for everyone sends nothing new, and a build before this one never sends it.
        assertFalse("\"file\"" in TogetherMessage.encode(state.copy(fromFile = false)))
        val old = """{"t":"state","song":"x","title":"t","artist":"a","pos":0,"playing":true,"speed":1.0,"at":1}"""
        assertFalse((TogetherMessage.decode(old) as TogetherMessage.State).fromFile)
        val sent = session.substringAfter("private fun sendState()").substringBefore("\n    }\n")
        assertTrue("fromFile = fromFile is PhoneSongs.Heard," in sent)
        assertTrue("songMatched = message.fromFile && !onlyThere," in session)
        assertTrue("if (!status.leading && status.songMatched && !status.waitingForHost) {" in sheet)
        for (folder in listOf("values", "values-fr")) {
            val xml = File("src/main/res/$folder/strings-ot.xml").readText()
            val note = Regex("""<string name="together_song_matched">([^<]*)</string>""").find(xml)!!.groupValues[1]
            assertTrue(note, "%1\$s" in note && "YouTube Music" in note && '—' !in note && note.length < 120)
        }
    }

    /** Both are toasts too, which show two lines of about 35 characters (ToastLengthTest), a name included. */
    @Test
    fun `the lines are short, in English and French`() {
        for (folder in listOf("values", "values-fr")) {
            val xml = File("src/main/res/$folder/strings-ot.xml").readText()
            fun text(name: String) = Regex("""<string name="$name">([^<]*)</string>""").find(xml)!!.groupValues[1].replace("\\'", "'")
            val there = text("together_song_only_there")
            assertTrue("$folder: $there", "%1\$s" in there && there.replace("%1\$s", "").length <= 56)
            val here = text("together_song_only_here")
            assertTrue("$folder: $here", here.length <= 70)
            for (line in listOf(there, here)) assertFalse(line, '—' in line)
        }
    }
}
