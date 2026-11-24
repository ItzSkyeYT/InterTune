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
    /** The ids YouTube Music was asked for, and its songs under them. */
    private val idAsks = mutableListOf<String>()
    private var catalogue: (String) -> SongItem? = { null }
    /** The id in a file's tags, by its path. */
    private var tagIds: (String) -> String? = { null }

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
        songById = { id ->
            idAsks += id
            catalogue(id)
        },
        idInTags = { path -> tagIds(path) },
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
    fun `a file named with its YouTube id is heard as that song, by the id and not by its tags`() = runBlocking {
        // Niko's file (#testers, 23 Nov 2026), "i downloaded it from there": its tags name the video as its uploader wrote
        // it, and no search by them finds the song.
        catalogue = { id -> if (id == "aKiAkAnE_01") result(id, title = "Flash Back", artist = "AKIAKANE", seconds = 246) else null }
        val songs = phoneSongs()
        val song = file(
            title = "秋赤音 【Flash B ack】", artist = "AKIAKANEch", seconds = 245,
            localPath = "$folder/秋赤音 【Flash B ack】 [aKiAkAnE_01].opus", id = "LSflashbck",
        )
        assertNull(songs.forRoom(song.id, song, lookUp = true))
        assertEquals(song.id, answered())
        val heard = songs.forRoom(song.id, song, lookUp = true) as PhoneSongs.Heard
        assertEquals("aKiAkAnE_01", heard.id)
        assertEquals("Flash Back", heard.title)
        assertEquals("AKIAKANE", heard.artist)
        assertEquals(listOf("aKiAkAnE_01"), idAsks)
        assertEquals(emptyList<String>(), searches)
        // What goes to the room has nothing of the file: not its path, not its name, not its tags.
        val sent = TogetherMessage.encode(TogetherMessage.Queue(listOf(heard.song), repeat = 0, shuffle = false)) +
            TogetherMessage.encode(TogetherMessage.State(heard.id, heard.title, heard.artist, 1_000, true, 1.0, t0))
        for (mine in listOf(song.id, "/storage", "Mel Boro", "秋赤音", "AKIAKANEch", ".opus")) assertFalse(mine, mine in sent)
    }

    @Test
    fun `a file whose tags carry its YouTube link is heard as that song, with no search`() = runBlocking {
        results = { listOf(result("cover00001", title = "Instant Crush (Cover)", artist = "Some Band")) }
        catalogue = { id -> result(id, seconds = 338) }
        tagIds = { p -> if (p == path) "dpInstant01" else null }
        val songs = phoneSongs()
        val song = file()
        assertNull(songs.forRoom(song.id, song, lookUp = true))
        answered()
        assertEquals("dpInstant01", songs.forRoom(song.id, song, lookUp = true)!!.id)
        assertEquals(listOf("dpInstant01"), idAsks)
        assertEquals(emptyList<String>(), searches)
    }

    @Test
    fun `an id YouTube has no song for, or whose song is another length, is not taken, and the tags are looked for`() = runBlocking {
        results = { listOf(result("dpInstant1")) }
        catalogue = { null }
        tagIds = { "notAsong_01" }
        val songs = phoneSongs()
        val song = file()
        assertNull(songs.forRoom(song.id, song, lookUp = true))
        answered()
        assertEquals("dpInstant1", songs.forRoom(song.id, song, lookUp = true)!!.id)
        assertEquals(listOf("Instant Crush Daft Punk"), searches)
        // A name that only looks like an id: YouTube's song under it is a minute and a half, the file five and a half.
        catalogue = { id -> result(id, title = "Intro", artist = "Someone", seconds = 95) }
        tagIds = { null }
        val named = file(localPath = "$folder/05 Instant Crush [Bootleg2001].flac", id = "LSbootleg1")
        assertNull(songs.forRoom(named.id, named, lookUp = true))
        answered()
        assertEquals("dpInstant1", songs.forRoom(named.id, named, lookUp = true)!!.id)
        assertEquals(listOf("notAsong_01", "Bootleg2001"), idAsks)
        assertEquals(2, searches.size)
    }

    @Test
    fun `an untagged file named with its id is heard, or goes with no name when YouTube has no song for it`() = runBlocking {
        catalogue = { id -> if (id == "dpInstant01") result(id, seconds = 337) else null }
        val songs = phoneSongs()
        val untagged = file(title = "Instant Crush [dpInstant01]", artist = "<unknown>", localPath = "$folder/Instant Crush [dpInstant01].m4a", id = "LSnamedid1")
        assertNull(songs.forRoom(untagged.id, untagged, lookUp = true))
        answered()
        assertEquals("dpInstant01", songs.forRoom(untagged.id, untagged, lookUp = true)!!.id)
        val unknown = file(title = "Voice memo [memo_000001]", artist = "<unknown>", localPath = "$folder/Voice memo [memo_000001].m4a", id = "LSnamedid2")
        assertNull(songs.forRoom(unknown.id, unknown, lookUp = true))
        answered()
        val only = songs.forRoom(unknown.id, unknown, lookUp = true) as PhoneSongs.OnlyHere
        assertEquals("", only.title)
        assertEquals("", only.artist)
        assertEquals(emptyList<String>(), searches)
    }

    @Test
    fun `an ask for the id a file carries that fails is no answer, so the tags are searched or it is asked again later`() = runBlocking {
        catalogue = { throw IOException("no network") }
        results = { listOf(result("dpInstant1")) }
        val songs = phoneSongs()
        // Tagged: the search answers in its place.
        val tagged = file(localPath = "$folder/05 Instant Crush [dpInstant01].flac")
        assertNull(songs.forRoom(tagged.id, tagged, lookUp = true))
        answered()
        assertEquals("dpInstant1", songs.forRoom(tagged.id, tagged, lookUp = true)!!.id)
        // Untagged: nothing else to go by, so no answer now, the others told they can't hear it meanwhile, and asked again
        // once the rest is over.
        val untagged = file(title = "Instant Crush [dpInstant01]", artist = "<unknown>", localPath = "$folder/Instant Crush [dpInstant01].m4a", id = "LSnamedid1")
        assertNull(songs.forRoom(untagged.id, untagged, lookUp = true))
        answered()
        assertTrue(songs.forRoom(untagged.id, untagged, lookUp = true) is PhoneSongs.OnlyHere)
        now += StandIn.SEARCH_REST_MS
        catalogue = { id -> result(id, seconds = 337) }
        assertNull(songs.forRoom(untagged.id, untagged, lookUp = true))
        answered()
        assertEquals("dpInstant01", songs.forRoom(untagged.id, untagged, lookUp = true)!!.id)
    }

    @Test
    fun `the id at the end of a file's name, as yt-dlp and the app's own downloads write it`() {
        assertEquals("dQw4w9WgXcQ", idInFileName("/storage/emulated/0/Music/Never Gonna Give You Up [dQw4w9WgXcQ].opus"))
        assertEquals("dQw4w9WgXcQ", idInFileName("/storage/emulated/0/Download/OuterTune/Never Gonna Give You Up [dQw4w9WgXcQ].mka"))
        assertEquals("a-b_c1D2e3F", idInFileName("Song [a-b_c1D2e3F]"))
        for (none in listOf(
            "/storage/emulated/0/Music/Song (dQw4w9WgXcQ).opus",
            "/storage/emulated/0/Music/[dQw4w9WgXcQ] Song.opus",
            "/storage/emulated/0/Music/[dQw4w9WgXcQ]/Song.opus",
            "/storage/emulated/0/Music/Song [shortid].mp3",
            "/storage/emulated/0/Music/Song [dQw4w9WgXcQx].mp3",
            "/storage/emulated/0/Music/Song [Remastered 2009].flac",
        )) assertNull(none, idInFileName(none))
        assertNull(idInFileName(null))
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
            assertTrue(note, "%1\$s" in note && "YouTube Music" in note && '—' !in note && note.length < 140)
        }
    }

    /** The player says it too, with a tag over the cover (his yes, 22 Nov 2026): inside the cover's square, so nothing moves. */
    @Test
    fun `the player tags the cover of YouTube Music's version, inside the cover so nothing of the player moves`() {
        val thumbnail = File("src/main/java/com/dd3boh/outertune/ui/player/Thumbnail.kt").readText()
        val cover = thumbnail.substringAfter(".aspectRatio(1f)").substringBefore("AnimatedVisibility(")
        assertTrue("RoomVersionTag(mediaMetadata?.id, Modifier.align(Alignment.BottomStart))" in cover)
        val tag = File("src/main/java/com/dd3boh/outertune/ui/player/RoomVersionTag.kt").readText()
        // Only on the room's own song, for a guest, and only for a match.
        assertTrue("if (!status.songMatched || status.leading || status.waitingForHost || shownId == null || shownId != status.hostSongId) return" in tag)
        // A tap says what the sheet says.
        assertTrue("R.string.together_song_version" in tag && "R.string.together_song_matched" in tag)
        for (folder in listOf("values", "values-fr")) {
            val xml = File("src/main/res/$folder/strings-ot.xml").readText()
            val label = Regex("""<string name="together_song_version">([^<]*)</string>""").find(xml)!!.groupValues[1]
            assertTrue(label, "YouTube Music" in label && label.length <= 24)
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
